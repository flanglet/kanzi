/*
Copyright 2011-2024 Frederic Langlet
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
you may obtain a copy of the License at

                http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package kanzi.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kanzi.Event;
import kanzi.Error;
import kanzi.Global;
import kanzi.SliceByteArray;
import kanzi.io.CompressedInputStream;
import kanzi.io.NullOutputStream;
import kanzi.Listener;



public class BlockDecompressor implements Runnable, Callable<Integer>
{
   private static final int DEFAULT_BUFFER_SIZE = 65536;
   private static final int MAX_CONCURRENCY = 64;
   private static final String STDOUT = "STDOUT";
   private static final String STDIN = "STDIN";
   private static final String NONE = "NONE";

   private int verbosity;
   private final boolean overwrite;
   private final boolean removeInput;
   private final String inputName;
   private final String outputName;
   private final int jobs;
   private final int from; // start block
   private final int to; // end block
   private final ExecutorService pool;
   private final List<Listener> listeners;


   public BlockDecompressor(Map<String, Object> map)
   {
      Boolean bForce = (Boolean) map.remove("overwrite");
      this.overwrite = (bForce == null) ? false : bForce;
      Boolean bRemove = (Boolean) map.remove("remove");
      this.removeInput = (bRemove == null) ? false : bRemove;
      String iName = (String) map.remove("inputName");
      this.inputName = iName.isEmpty() ? STDIN : iName;
      String oName = (String) map.remove("outputName");
      this.outputName = (oName.isEmpty() && STDIN.equalsIgnoreCase(iName)) ? STDOUT : oName;
      this.verbosity = (Integer) map.remove("verbose");
      this.from = (map.containsKey("from") ? (Integer) map.remove("from") : -1);
      this.to = (map.containsKey("to") ? (Integer) map.remove("to") : -1);
      int concurrency;

      if (map.containsKey("jobs"))
      {
         concurrency = (Integer) map.remove("jobs");

         if (concurrency == 0)
         {
            // Use all cores
            int cores = Runtime.getRuntime().availableProcessors();
            concurrency = Math.min(cores, MAX_CONCURRENCY);
         }
         else if (concurrency > MAX_CONCURRENCY)
         {
            printOut("Warning: the number of jobs is too high, defaulting to "+MAX_CONCURRENCY, this.verbosity>0);
            concurrency = MAX_CONCURRENCY;
         }
      }
      else
      {
          // Default to half of cores
          int cores = Math.max(Runtime.getRuntime().availableProcessors()/2, 1);
          concurrency = Math.min(cores, MAX_CONCURRENCY);
      }

      this.jobs = concurrency;
      this.pool = Executors.newFixedThreadPool(this.jobs);
      this.listeners = new ArrayList<>(10);

      if ((this.verbosity > 0) && (map.size() > 0))
      {
         for (String k : map.keySet())
            printOut("Warning: Ignoring invalid option [" + k + "]", true); //this.verbosity>0
      }
   }


   public void dispose()
   {
      if (this.pool != null)
         this.pool.shutdown();
   }


   @Override
   public void run()
   {
      this.call();
   }


   @Override
   public Integer call()
   {
      List<Path> files = new ArrayList<>();
      long read = 0;
      long before = System.nanoTime();
      int nbFiles = 1;
      boolean isStdIn = STDIN.equalsIgnoreCase(this.inputName);

      if (isStdIn == false)
      {
         try
         {
            String suffix = File.separator + ".";
            boolean isRecursive = !this.inputName.endsWith(suffix);
            String target = isRecursive ? this.inputName :
               this.inputName.substring(0, this.inputName.length()-1);

            Kanzi.createFileList(target, files, isRecursive, false, false);
         }
         catch (IOException e)
         {
            System.err.println(e.getMessage());
            return Error.ERR_OPEN_FILE;
         }

         if (files.isEmpty())
         {
            System.err.println("Cannot open input file '"+this.inputName+"'");
            return Error.ERR_OPEN_FILE;
         }

         nbFiles = files.size();
         String strFiles = (nbFiles > 1) ? " files" : " file";
         printOut(nbFiles+strFiles+" to decompress\n", this.verbosity > 0);
      }

      String upperOutputName = this.outputName.toUpperCase();
      boolean isStdOut = STDOUT.equals(upperOutputName);

      // Limit verbosity level when output is stdout
      // Logic is duplicated here to avoid dependency to Kanzi.java
      if (isStdOut == true)
      {
          this.verbosity = 0;
      }

      // Limit verbosity level when files are processed concurrently
      if ((this.jobs > 1) && (nbFiles > 1) && (this.verbosity > 1))
      {
         printOut("Warning: limiting verbosity to 1 due to concurrent processing of input files.\n", true);
         this.verbosity = 1;
      }

      if (this.verbosity > 2)
      {
         printOut("Verbosity: "+this.verbosity, true);
         printOut("Overwrite: "+this.overwrite, true);
         printOut("Using " + this.jobs + " job" + ((this.jobs > 1) ? "s" : ""), true);
         this.addListener(new InfoPrinter(this.verbosity, InfoPrinter.Type.DECODING, System.out));
      }

      int res = 0;

      try
      {
         boolean inputIsDir = false;
         String formattedOutName = this.outputName;
         String formattedInName = this.inputName;
         boolean specialOutput = NONE.equals(upperOutputName) ||
            STDOUT.equals(upperOutputName);

         if (isStdIn == false)
         {
            if (Files.isDirectory(Paths.get(this.inputName)))
            {
               inputIsDir = true;

               if (formattedInName.endsWith(".") == true)
                  formattedInName = formattedInName.substring(0, formattedInName.length()-1);

               if (formattedInName.endsWith(File.separator) == false)
                  formattedInName += File.separator;

               if ((formattedOutName.isEmpty() == false) && (specialOutput== false))
               {
                  if (Files.isDirectory(Paths.get(formattedOutName)) == false)
                  {
                     System.err.println("Output must be an existing directory (or 'NONE')");
                     return Error.ERR_CREATE_FILE;
                  }

                  if (formattedOutName.endsWith(File.separator) == false)
                     formattedOutName += File.separator;
               }
            }
            else
            {
               inputIsDir = false;

               if ((formattedOutName.isEmpty() == false) && (specialOutput == false))
               {
                  if (Files.isDirectory(Paths.get(formattedOutName)) == true)
                  {
                     System.err.println("Output must be a file (or 'NONE')");
                     return Error.ERR_CREATE_FILE;
                  }
               }
            }
         }

         Map<String, Object> ctx = new HashMap<>();
         ctx.put("verbosity", this.verbosity);
         ctx.put("overwrite", this.overwrite);
         ctx.put("pool", this.pool);
         ctx.put("remove", this.removeInput);

         if (this.from >= 0)
            ctx.put("from", this.from);

         if (this.to >= 0)
            ctx.put("to", this.to);

         // Run the task(s)
         if (nbFiles == 1)
         {
            String oName = (formattedOutName == null) ? "" : formattedOutName;
            String iName = STDIN;

            if (isStdIn == true)
            {
                if (oName.isEmpty())
                    oName = STDOUT;
            }
            else
            {
                iName = files.get(0).toString();
                long fileSize = Files.size(files.get(0));
                ctx.put("fileSize", fileSize);

                if (oName.isEmpty())
                {
                   oName = iName + ".bak";
                }
                else if ((inputIsDir == true) && (specialOutput == false))
                {
                   oName = formattedOutName + iName.substring(formattedInName.length()+1) + ".bak";
                }
            }

            ctx.put("inputName", iName);
            ctx.put("outputName", oName);
            ctx.put("jobs", this.jobs);
            FileDecompressTask task = new FileDecompressTask(ctx, this.listeners);
            FileDecompressResult fdr = task.call();
            res = fdr.code;
            read = fdr.read;
         }
         else
         {
            ArrayBlockingQueue<FileDecompressTask> queue = new ArrayBlockingQueue(nbFiles, true);
            int[] jobsPerTask = Global.computeJobsPerTask(new int[nbFiles], this.jobs, nbFiles);
            int n = 0;
            Global.sortFilesByPathAndSize(files, true);

            // Create one task per file
            for (Path file : files)
            {
               String oName = formattedOutName;
               String iName = file.toString();
               long fileSize = Files.size(file);
               Map taskCtx = new HashMap(ctx);

               if (oName == null)
               {
                  oName = iName + ".bak";
               }
               else if ((inputIsDir == true) && (NONE.equalsIgnoreCase(oName) == false))
               {
                  oName = formattedOutName + iName.substring(formattedInName.length()) + ".bak";
               }

               taskCtx.put("fileSize", fileSize);
               taskCtx.put("inputName", iName);
               taskCtx.put("outputName", oName);
               taskCtx.put("jobs", jobsPerTask[n++]);
               FileDecompressTask task = new FileDecompressTask(taskCtx, this.listeners);

               if (queue.offer(task) == false)
                  throw new RuntimeException("Could not create a decompression task");
            }

            List<FileDecompressWorker> workers = new ArrayList<>(this.jobs);

		  	   // Create one worker per job and run it. A worker calls several tasks sequentially.
            for (int i=0; i<this.jobs; i++)
               workers.add(new FileDecompressWorker(queue));

            // Invoke the tasks concurrently and wait for results
            // Using workers instead of tasks direclty, allows for early exit on failure
            for (Future<FileDecompressResult> result : this.pool.invokeAll(workers))
            {
               FileDecompressResult fdr = result.get();
               read += fdr.read;

               if (fdr.code != 0)
               {
                  // Exit early by telling the workers that the queue is empty
                  queue.clear();
                  res = fdr.code;
               }
            }
         }
      }
      catch (Exception e)
      {
         System.err.println("An unexpected error occurred: " + e.getMessage());
         res = Error.ERR_UNKNOWN;
      }

      long after = System.nanoTime();

      if (nbFiles > 1)
      {
         long delta = (after - before) / 1000000L; // convert to ms
         printOut("", this.verbosity>0);
         String str;

         if (delta >= 100000) {
            str = String.format("%1$.1f", (float) delta/1000) + " s";
         } else {
            str = String.valueOf(delta) + " ms";
         }

         printOut("Total decompression time: "+str, this.verbosity > 0);
         printOut("Total output size: "+read+((read>1)?" bytes":" byte"), this.verbosity > 0);
       }

      return res;
   }


    private static void printOut(String msg, boolean print)
    {
       if ((print == true) && (msg != null))
          System.out.println(msg);
    }


    public final boolean addListener(Listener bl)
    {
       return (bl != null) ? this.listeners.add(bl) : false;
    }


    public final boolean removeListener(Listener bl)
    {
       return (bl != null) ? this.listeners.remove(bl) : false;
    }


    static void notifyListeners(Listener[] listeners, Event evt)
    {
       for (Listener bl : listeners)
       {
          try
          {
             bl.processEvent(evt);
          }
          catch (Exception e)
          {
            // Ignore exceptions in listeners
          }
       }
    }



   static class FileDecompressResult
   {
       final int code;
       final long read;


      public FileDecompressResult(int code, long read)
      {
         this.code = code;
         this.read = read;
      }
   }


   static class FileDecompressTask implements Callable<FileDecompressResult>
   {
      private final Map<String, Object> ctx;
      private CompressedInputStream cis;
      private OutputStream os;
      private final List<Listener> listeners;


      public FileDecompressTask(Map<String, Object> ctx, List<Listener> listeners)
      {
         this.ctx = ctx;
         this.listeners = listeners;
      }


      @Override
      public FileDecompressResult call() throws Exception
      {
         int verbosity = (Integer) this.ctx.get("verbosity");
         String inputName = (String) this.ctx.get("inputName");
         String outputName = (String) this.ctx.get("outputName");

         if (verbosity > 2)
         {
            printOut("Input file name: '" + inputName + "'", true);
            printOut("Output file name: '" + outputName + "'", true);
         }

         boolean overwrite = (Boolean) this.ctx.get("overwrite");

         long decoded = 0;
         printOut("\nDecompressing "+inputName+" ...", verbosity>1);
         printOut("", verbosity>3);

         if (this.listeners.size() > 0)
         {
            Event evt = new Event(Event.Type.DECOMPRESSION_START, -1, 0);
            Listener[] array = this.listeners.toArray(new Listener[this.listeners.size()]);
            notifyListeners(array, evt);
         }

         boolean checkOutputSize;

         if (System.getProperty("os.name").toLowerCase().contains("windows"))
         {
            checkOutputSize = !outputName.equalsIgnoreCase("NUL");
         }
         else
         {
            checkOutputSize = !outputName.equalsIgnoreCase("/DEV/NULL");
         }

         if (NONE.equalsIgnoreCase(outputName))
         {
            this.os = new NullOutputStream();
            checkOutputSize = false;
         }
         else if (STDOUT.equalsIgnoreCase(outputName))
         {
            this.os = System.out;
            checkOutputSize = false;
         }
         else
         {
            try
            {
               File output = new File(outputName);

               if (output.exists())
               {
                  if (output.isDirectory())
                  {
                     System.err.println("The output file is a directory");
                     return new FileDecompressResult(Error.ERR_OUTPUT_IS_DIR, 0);
                  }

                  if (overwrite == false)
                  {
                     System.err.println("File '" + outputName + "' exists and " +
                        "the 'force' command line option has not been provided");
                     return new FileDecompressResult(Error.ERR_OVERWRITE_FILE, 0);
                  }

                  Path path1 = FileSystems.getDefault().getPath(inputName).toAbsolutePath();
                  Path path2 = FileSystems.getDefault().getPath(outputName).toAbsolutePath();

                  if (path1.equals(path2))
                  {
                     System.err.println("The input and output files must be different");
                     return new FileDecompressResult(Error.ERR_CREATE_FILE, 0);
                  }
               }

               try
               {
                  this.os = new FileOutputStream(output);
               }
               catch (IOException e1)
               {
                  if (overwrite == false)
                     throw e1;

                  try
                  {
                     // Attempt to create the full folder hierarchy to file
                     Files.createDirectories(FileSystems.getDefault().getPath(outputName).getParent());
                     this.os = new FileOutputStream(output);
                  }
                  catch (IOException e2)
                  {
                     throw e1;
                  }
               }
            }
            catch (Exception e)
            {
               System.err.println("Cannot open output file '"+ outputName+"' for writing: " + e.getMessage());
               return new FileDecompressResult(Error.ERR_CREATE_FILE, 0);
            }
         }

         InputStream is;

         try
         {
            is = (STDIN.equalsIgnoreCase(inputName)) ? System.in :
               new FileInputStream(new File(inputName));

            try
            {
               this.cis = new CompressedInputStream(is, this.ctx);

               for (Listener bl : this.listeners)
                  this.cis.addListener(bl);
            }
            catch (Exception e)
            {
               System.err.println("Cannot create compressed stream: "+e.getMessage());
               return new FileDecompressResult(Error.ERR_CREATE_DECOMPRESSOR, 0);
            }
         }
         catch (Exception e)
         {
            System.err.println("Cannot open input file '"+ inputName+"': " + e.getMessage());
            return new FileDecompressResult(Error.ERR_OPEN_FILE, 0);
         }

         long before = System.nanoTime();

         try
         {
            SliceByteArray sa = new SliceByteArray(new byte[DEFAULT_BUFFER_SIZE], 0);
            int decodedBlock;

            // Decode next block
            do
            {
               decodedBlock = this.cis.read(sa.array, 0, sa.length);

               if (decodedBlock < 0)
               {
                  System.err.println("Reached end of stream");
                  return new FileDecompressResult(Error.ERR_READ_FILE,  this.cis.getRead());
               }

               try
               {
                  if (decodedBlock > 0)
                  {
                     this.os.write(sa.array, 0, decodedBlock);
                     decoded += (long) decodedBlock;
                  }
               }
               catch (Exception e)
               {
                  System.err.print("Failed to write decompressed block to file '"+outputName+"': ");
                  System.err.println(e.getMessage());
                  return new FileDecompressResult(Error.ERR_READ_FILE, this.cis.getRead());
               }
            }
            while (decodedBlock == sa.array.length);
         }
         catch (kanzi.io.IOException e)
         {
            System.err.println("An unexpected condition happened. Exiting ...");
            System.err.println(e.getMessage());
            return new FileDecompressResult(e.getErrorCode(), this.cis.getRead());
         }
         catch (Exception e)
         {
            System.err.println("An unexpected condition happened. Exiting ...");
            System.err.println(e.getMessage());
            return new FileDecompressResult(Error.ERR_UNKNOWN, this.cis.getRead());
         }
         finally
         {
            // Close streams to ensure all data are flushed
            this.dispose();

            try
            {
               is.close();
            }
            catch (IOException e)
            {
               // Ignore
            }

            if (this.listeners.size() > 0)
            {
               Event evt = new Event(Event.Type.DECOMPRESSION_END, -1, this.cis.getRead());
               Listener[] array = this.listeners.toArray(new Listener[this.listeners.size()]);
               notifyListeners(array, evt);
            }
         }

         long after = System.nanoTime();
         long delta = (after - before) / 1000000L; // convert to ms

         // If the whole input stream has been decoded and the original data size is present,
         // check that the output size matches the original data size.
         if ((checkOutputSize == true) && (this.ctx.containsKey("to") == false) && (this.ctx.containsKey("from") == false))
         {
             final long outputSize = (Long) this.ctx.getOrDefault("outputSize", 0L);

             if ((outputSize != 0) && (decoded != outputSize))
             {
                 String msg = String.format("Corrupted bitstream: invalid output size (expected %d, got %d)", outputSize, decoded);
                 System.err.println(msg);
                 return new FileDecompressResult(Error.ERR_INVALID_FILE, decoded);
             }
         }

         String str;

         if (verbosity >= 1)
         {
            printOut("", verbosity>1);

            if (delta >= 100000)
               str = String.format("%1$.1f", (float) delta/1000) + " s";
            else
               str = String.valueOf(delta) + " ms";

            if (verbosity > 1)
            {
               printOut("Decompressing:          "+str, true);
               printOut("Input size:             "+this.cis.getRead(), true);
               printOut("Output size:            "+decoded, true);
            }

            if (verbosity == 1)
            {
               str = String.format("Decompressing %s: %d => %d in %s", inputName, this.cis.getRead(), decoded, str);
               printOut(str, true);
            }

            if ((verbosity > 1) && (delta > 0))
               printOut("Throughput (KB/s): "+(((decoded * 1000L) >> 10) / delta), true);

            printOut("", verbosity>1);
         }


         if (((Boolean) this.ctx.get("remove")) == true)
         {
            try
            {
                // Delete input file
                if (inputName.equals("STDIN"))
                   printOut("Warning: ignoring remove option with STDIN", verbosity>0);
                else if (Files.deleteIfExists(Paths.get(inputName)) == false)
                   printOut("Warning: input file could not be deleted", verbosity>0);
            }
            catch (IOException e)
            {
               // Ignore
            }
         }

         return new FileDecompressResult(0, decoded);
      }

      public void dispose() throws IOException
      {
         if (this.cis != null)
            this.cis.close();

         if (this.os != null)
            this.os.close();
      }
   }



   static class FileDecompressWorker implements Callable<FileDecompressResult>
   {
      private final ArrayBlockingQueue<FileDecompressTask> queue;

      public FileDecompressWorker(ArrayBlockingQueue<FileDecompressTask> queue)
      {
         this.queue = queue;
      }

      @Override
      public FileDecompressResult call() throws Exception
      {
         int res = 0;
         long read = 0;

         while (res == 0)
         {
            FileDecompressTask task = this.queue.poll();

            if (task == null)
               break;

            FileDecompressResult result = task.call();
            res = result.code;
            read += result.read;
         }

         return new FileDecompressResult(res, read);
      }
   }
}
