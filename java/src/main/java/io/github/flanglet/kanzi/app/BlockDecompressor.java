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

package io.github.flanglet.kanzi.app;

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
import io.github.flanglet.kanzi.Event;
import io.github.flanglet.kanzi.Error;
import io.github.flanglet.kanzi.Global;
import io.github.flanglet.kanzi.SliceByteArray;
import io.github.flanglet.kanzi.io.CompressedInputStream;
import io.github.flanglet.kanzi.io.IOUtil;
import io.github.flanglet.kanzi.io.NullOutputStream;
import io.github.flanglet.kanzi.Listener;



/**
 * The {@code BlockDecompressor} class implements a multithreaded block
 * decompression algorithm. It is designed to handle decompression tasks
 * efficiently using concurrency, allowing for fast data processing.
 *
 * <p>This class implements the {@code Runnable} and {@code Callable}
 * interfaces, enabling it to be executed in a separate thread and
 * return a status code upon completion.</p>
 */
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
   private final boolean noDotFiles;
   private final boolean noLinks;
   private final String inputName;
   private final String outputName;
   private final int jobs;
   private final int from; // start block
   private final int to; // end block
   private final ExecutorService pool;
   private final List<Listener> listeners;


  /**
   * Constructs a {@code BlockDecompressor} with the specified parameters.
   *
   * @param map a map containing configuration options for the decompressor
   */
   public BlockDecompressor(Map<String, Object> map)
   {
      this.overwrite = Boolean.TRUE.equals(map.remove("overwrite"));
      this.removeInput = Boolean.TRUE.equals(map.remove("remove"));
      this.noDotFiles = Boolean.TRUE.equals(map.remove("noDotFiles"));
      this.noLinks = Boolean.TRUE.equals(map.remove("noLinks"));
      String iName = (String) map.remove("inputName");

      if (Global.isReservedName(iName))
          throw new IllegalArgumentException("'"+iName+"' is a reserved name");

      this.inputName = iName.isEmpty() ? STDIN : iName;
      String oName = (String) map.remove("outputName");

      if (Global.isReservedName(oName))
          throw new IllegalArgumentException("'"+oName+"' is a reserved name");

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

      if ((this.verbosity > 0) && (map.isEmpty() == false))
      {
         for (String k : map.keySet())
            printOut("Warning: Ignoring invalid option [" + k + "]", true); //this.verbosity>0
      }
   }


  /**
   * Cleans up resources used by the {@code BlockDecompressor}.
   * Shuts down the thread pool if it exists.
   */
   public void dispose()
   {
      if (this.pool != null)
         this.pool.shutdown();
   }


  /**
   * Executes the decompression process by calling the {@code call} method.
   */
   @Override
   public void run()
   {
      this.call();
   }


  /**
   * Performs the decompression task and returns a status code.
   *
   * @return an integer indicating the status of the decompression (success = 0, error = negative value)
   */
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

            IOUtil.createFileList(target, files, isRecursive, this.noLinks, this.noDotFiles);
         }
         catch (IOException e)
         {
            System.err.println(e.getMessage());
            return Error.ERR_OPEN_FILE;
         }

         if (files.isEmpty())
         {
            System.err.println("Cannot find any file to decompress");
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
            String oName = formattedOutName;
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
                String tmpName = iName;

                if ((tmpName.length() >= 4) && (tmpName.substring(tmpName.length()-4).equalsIgnoreCase(".KNZ")))
                    tmpName = tmpName.substring(0, tmpName.length()-4);
                else
                    tmpName = tmpName + ".bak";

                if (oName.isEmpty())
                {
                    oName = tmpName;
                }
                else if ((inputIsDir == true) && (specialOutput == false))
                {
                    oName = formattedOutName + tmpName.substring(formattedInName.length()+1);
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
            ArrayBlockingQueue<FileDecompressTask> queue = new ArrayBlockingQueue<>(nbFiles, true);
            int[] jobsPerTask = Global.computeJobsPerTask(new int[nbFiles], this.jobs, nbFiles);
            int n = 0;
            Global.sortFilesByPathAndSize(files, true);

            // Create one task per file
            for (Path file : files)
            {
               String oName = formattedOutName;
               String iName = file.toString();
               long fileSize = Files.size(file);
               Map<String, Object> taskCtx = new HashMap<>(ctx);
               String tmpName = iName;

               if ((tmpName.length() >= 4) && (tmpName.substring(tmpName.length()-4).equalsIgnoreCase(".KNZ")))
                   tmpName = tmpName.substring(0, tmpName.length()-4);
               else
                   tmpName = tmpName + ".bak";

               if (oName.isEmpty())
               {
                   oName = tmpName;
               }
               else if ((inputIsDir == true) && (NONE.equalsIgnoreCase(oName) == false))
               {
                   oName = formattedOutName + tmpName.substring(formattedInName.length());
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
            // Using workers instead of tasks directly, allows for early exit on failure
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
      catch (InterruptedException e)
      {
          Thread.currentThread().interrupt();
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


   /**
    * Prints a message to the console if the print flag is true.
    *
    * @param msg the message to print
    * @param print flag indicating whether to print the message
    */
    private static void printOut(String msg, boolean print)
    {
       if ((print == true) && (msg != null))
          System.out.println(msg);
    }


   /**
    * Adds a listener to the list of listeners.
    *
    * @param bl the listener to add
    * @return true if the listener was added successfully, false otherwise
    */
    public final boolean addListener(Listener bl)
    {
       return (bl != null) ? this.listeners.add(bl) : false;
    }


   /**
    * Removes a listener from the list of listeners.
    *
    * @param bl the listener to remove
    * @return true if the listener was removed successfully, false otherwise
    */
    public final boolean removeListener(Listener bl)
    {
       return (bl != null) ? this.listeners.remove(bl) : false;
    }


   /**
    * Notifies all registered listeners of an event.
    *
    * @param listeners the array of listeners to notify
    * @param evt the event to be processed by the listeners
    */
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



  /**
   * Represents the result of a file decompression task.
   */
   static class FileDecompressResult
   {
      final int code;
      final long read;

     /**
      * Constructs a {@code FileDecompressResult} with the specified values.
      *
      * @param code the status code of the decompression task
      * @param read the amount of data read
      */
      public FileDecompressResult(int code, long read)
      {
         this.code = code;
         this.read = read;
      }
   }


  /**
   * Represents a task that decompresses a file.
   */
   static class FileDecompressTask implements Callable<FileDecompressResult>
   {
      private final Map<String, Object> ctx;
      private CompressedInputStream cis;
      private OutputStream os;
      private final List<Listener> listeners;


     /**
      * Constructs a {@code FileDecompressTask} with the specified context and listeners.
      *
      * @param ctx the context for the decompression task
      * @param listeners the list of listeners to notify during processing
      */
      public FileDecompressTask(Map<String, Object> ctx, List<Listener> listeners)
      {
         this.ctx = ctx;
         this.listeners = listeners;
      }


 
     /**
      * Executes the decompression task and returns the result.
      *
      * @return a {@code FileDecompressResult} representing the outcome of the task
      * @throws Exception if an error occurs during decompression
      */
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

         if (this.listeners.isEmpty() == false)
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
                  return new FileDecompressResult(Error.ERR_READ_FILE, this.cis.getRead());
               }

               try
               {
                  if (decodedBlock > 0)
                  {
                     this.os.write(sa.array, 0, decodedBlock);
                     decoded += decodedBlock;
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
         catch (io.github.flanglet.kanzi.io.IOException e)
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

            if (this.listeners.isEmpty() == false)
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
               printOut("Decompression time: "+str, true);
               printOut("Input size:         "+this.cis.getRead(), true);
               printOut("Output size:        "+decoded, true);
            }

            if (verbosity == 1)
            {
               str = String.format("Decompressed %s: %d => %d in %s", inputName, this.cis.getRead(), decoded, str);
               printOut(str, true);
            }

            if ((verbosity > 1) && (delta > 0))
               printOut("Throughput (KiB/s): "+(((decoded * 1000L) >> 10) / delta), true);

            printOut("", verbosity>1);
         }


         if (Boolean.TRUE.equals(this.ctx.get("remove")))
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


     /**
      * Cleans up resources used by the decompression task.
      *
      * @throws IOException if an error occurs while closing resources
      */
      public void dispose() throws IOException
      {
         if (this.cis != null)
            this.cis.close();

         if (this.os != null)
            this.os.close();
      }
   }


  /**
   * Represents a worker that processes file decompression tasks.
   */
   static class FileDecompressWorker implements Callable<FileDecompressResult>
   {
      private final ArrayBlockingQueue<FileDecompressTask> queue;


     /**
      * Constructs a {@code FileDecompressWorker} with the specified task queue.
      *
      * @param queue the queue of tasks to be processed
      */
      public FileDecompressWorker(ArrayBlockingQueue<FileDecompressTask> queue)
      {
         this.queue = queue;
      }


     /**
      * Executes the worker's task of decompressing files.
      *
      * @return a {@code FileDecompressResult} representing the outcome of the tasks
      * @throws Exception if an error occurs during processing
      */
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
