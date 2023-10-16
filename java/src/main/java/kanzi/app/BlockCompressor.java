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
import kanzi.SliceByteArray;
import kanzi.io.CompressedOutputStream;
import kanzi.Error;
import kanzi.Global;
import kanzi.io.NullOutputStream;
import kanzi.Listener;
import kanzi.transform.TransformFactory;


public class BlockCompressor implements Runnable, Callable<Integer>
{
   private static final int DEFAULT_BUFFER_SIZE = 65536;
   private static final int DEFAULT_BLOCK_SIZE  = 4*1024*1024;
   private static final int MIN_BLOCK_SIZE  = 1024;
   private static final int MAX_BLOCK_SIZE  = 1024*1024*1024;
   private static final int MAX_CONCURRENCY = 64;
   private static final String STDOUT = "STDOUT";
   private static final String STDIN = "STDIN";
   private static final String NONE = "NONE";

   private int verbosity;
   private final boolean overwrite;
   private final boolean checksum;
   private final boolean skipBlocks;
   private final boolean reoderFiles;
   private final boolean noDotFile;
   private final boolean autoBlockSize;
   private final String inputName;
   private final String outputName;
   private final String codec;
   private final String transform;
   private int blockSize;
   private final int level; // command line compression level
   private final int jobs;
   private final List<Listener> listeners;
   private final ExecutorService pool;


   public BlockCompressor(Map<String, Object> map)
   {
      this.level = map.containsKey("level") ? (Integer) map.remove("level") : -1;
      Boolean bForce = (Boolean) map.remove("overwrite");
      this.overwrite = (bForce == null) ? false : bForce;
      Boolean bSkip = (Boolean) map.remove("skipBlocks");
      this.skipBlocks = (bSkip == null) ? false : bSkip;
      String iName = (String) map.remove("inputName");
      this.inputName = iName.isEmpty() ? STDIN : iName;
      String oName = (String) map.remove("outputName");
      this.outputName = (oName.isEmpty() && STDIN.equalsIgnoreCase(iName)) ? STDOUT : oName;
      String strTransf;
      String strCodec;

      if (this.level >= 0)
      {
         String tranformAndCodec = getTransformAndCodec(this.level);
         String[] tokens = tranformAndCodec.split("&");
         strTransf = tokens[0];
         strCodec = tokens[1];
      }
      else
      {
         strTransf = (String) map.remove("transform");
         strCodec = (String) map.remove("entropy");
      }

      this.codec = (strCodec == null) ? "ANS0" : strCodec;
      Integer iBlockSize = (Integer) map.remove("block");
      
      if (iBlockSize == null)
      {
         switch (this.level)
         {
             case 6:
                 this.blockSize = 2 * DEFAULT_BLOCK_SIZE;
                 break;
             case 7:
                 this.blockSize = 4 * DEFAULT_BLOCK_SIZE;
                 break;
             case 8:
                 this.blockSize = 4 * DEFAULT_BLOCK_SIZE;
                 break;
             case 9:
                 this.blockSize = 8 *DEFAULT_BLOCK_SIZE;
                 break;
             default:
                 this.blockSize = DEFAULT_BLOCK_SIZE;
         }
      }
      else
      {
         final int bs = iBlockSize;
         
         if (bs < MIN_BLOCK_SIZE)
            throw new IllegalArgumentException("Minimum block size is "+(MIN_BLOCK_SIZE/1024)+
               " KB ("+MIN_BLOCK_SIZE+" bytes), got "+bs+" bytes");

         if (bs > MAX_BLOCK_SIZE)
            throw new IllegalArgumentException("Maximum block size is "+(MAX_BLOCK_SIZE/(1024*1024*1024))+
               " GB ("+MAX_BLOCK_SIZE+" bytes), got "+bs+" bytes");

         this.blockSize = Math.min((bs+15) & -16, MAX_BLOCK_SIZE);
      }
      
      // Extract transform names. Curate input (EG. NONE+NONE+xxxx => xxxx)
      TransformFactory bff = new TransformFactory();
      this.transform = (strTransf == null) ? "BWT+RANK+ZRLT" : bff.getName(bff.getType(strTransf));
      Boolean bChecksum = (Boolean) map.remove("checksum");
      this.checksum = (bChecksum == null) ? false : bChecksum;
      Boolean bReorder = (Boolean) map.remove("fileReorder");
      this.reoderFiles = (bReorder == null) ? true : bReorder;
      Boolean bNoDotFile = (Boolean) map.remove("noDotFile");
      this.noDotFile = (bNoDotFile == null) ? false : bNoDotFile;
      Boolean bAuto = (Boolean) map.remove("autoBlock");
      this.autoBlockSize = (bAuto == null) ? false : bAuto;
      this.verbosity = (Integer) map.remove("verbose");
      int concurrency = (Integer) map.remove("jobs");

      if (concurrency == 0)
      {
         // Default to half of cores
         int cores = Math.max(Runtime.getRuntime().availableProcessors()/2, 1); 
         concurrency = Math.min(cores, MAX_CONCURRENCY);
      }
      else if (concurrency > MAX_CONCURRENCY)
      {
         printOut("Warning: the number of jobs is too high, defaulting to "+MAX_CONCURRENCY, this.verbosity>0);
         concurrency = MAX_CONCURRENCY;
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


   // Return status (success = 0, error < 0)
   @Override
   public Integer call()
   {
      List<Path> files = new ArrayList<>();
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
            
            Kanzi.createFileList(target, files, isRecursive, this.noDotFile);
         }
         catch (IOException e)
         {
            System.err.println(e.getMessage());
            return Error.ERR_OPEN_FILE;
         }

         if (files.isEmpty())
         {
            System.err.println("Cannot access input file '"+this.inputName+"'");
            return Error.ERR_OPEN_FILE;
         }

         nbFiles = files.size();
         String strFiles = (nbFiles > 1) ? " files" : " file";
         printOut(nbFiles+strFiles+" to compress\n", this.verbosity > 0);
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
         if (this.autoBlockSize == true)
            printOut("Block size set to 'auto'", true);
         else
            printOut("Block size set to " + this.blockSize + " bytes", true);

         printOut("Verbosity set to " + this.verbosity, true);
         printOut("Overwrite set to " + this.overwrite, true);
         printOut("Checksum set to " +  this.checksum, true);
         String etransform = (NONE.equals(this.transform)) ? "no" : this.transform;
         printOut("Using " + etransform + " transform (stage 1)", true);
         String ecodec = (NONE.equals(this.codec)) ? "no" : this.codec;
         printOut("Using " + ecodec + " entropy codec (stage 2)", true);
         printOut("Using " + this.jobs + " job" + ((this.jobs > 1) ? "s" : ""), true);
         this.addListener(new InfoPrinter(this.verbosity, InfoPrinter.Type.ENCODING, System.out));
      }
      
      int res = 0;
      long read = 0;
      long written = 0;
      boolean inputIsDir = false;
      String formattedOutName = this.outputName;
      String formattedInName = this.inputName;
      boolean specialOutput = NONE.equals(upperOutputName) ||
         STDOUT.equals(upperOutputName);

      try
      {
         if (isStdIn == false)
         {
            if (Files.isDirectory(Paths.get(formattedInName)))
            {
               inputIsDir = true;

               if (formattedInName.endsWith(".") == true)
                  formattedInName = formattedInName.substring(0, formattedInName.length()-1);

               if (formattedInName.endsWith(File.separator) == false)
                  formattedInName += File.separator;

               if ((formattedOutName.isEmpty() == false) && (specialOutput == false))
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
         ctx.put("skipBlocks", this.skipBlocks);
         ctx.put("checksum", this.checksum);
         ctx.put("pool", this.pool);
         ctx.put("codec", this.codec);
         ctx.put("transform", this.transform);
         ctx.put("extra", "TPAQX".equals(this.codec));

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

               if ((this.autoBlockSize == true) && (this.jobs > 0))
               {
                   long bl = fileSize / this.jobs;
                   bl = (bl + 63) & ~63;
                   this.blockSize = (int) Math.max(Math.min(bl, MAX_BLOCK_SIZE), MIN_BLOCK_SIZE);
               }

               if (oName.isEmpty())
               {
                  oName = iName + ".knz";
               }
               else if ((inputIsDir == true) && (specialOutput == false))
               {
                  oName = formattedOutName + iName.substring(formattedInName.length()+1) + ".knz";
               }
            }

            ctx.put("inputName", iName);
            ctx.put("outputName", oName);
            ctx.put("blockSize", this.blockSize);
            ctx.put("jobs", this.jobs);
            FileCompressTask task = new FileCompressTask(ctx, this.listeners);
            FileCompressResult fcr = task.call();
            res = fcr.code;
            read = fcr.read;
            written = fcr.written;
         }
         else
         {
            ArrayBlockingQueue<FileCompressTask> queue = new ArrayBlockingQueue(nbFiles, true);
            int[] jobsPerTask = Global.computeJobsPerTask(new int[nbFiles], this.jobs, nbFiles);
            int n = 0;
            
            if (this.reoderFiles == true)
               Global.sortFilesByPathAndSize(files, true);

            // Create one task per file
            for (Path file : files)
            {
               String oName = formattedOutName;
               String iName = file.toString();
               long fileSize = Files.size(file);
               Map taskCtx = new HashMap(ctx);

               if ((this.autoBlockSize == true) && (this.jobs > 0))
               {
                   long bl = fileSize / this.jobs;
                   bl = (bl + 63) & ~63;
                   this.blockSize = (int) Math.max(Math.min(bl, MAX_BLOCK_SIZE), MIN_BLOCK_SIZE);
               }

               if (oName == null)
               {
                  oName = iName + ".knz";
               }
               else if ((inputIsDir == true) && (specialOutput == false))
               {
                  oName = formattedOutName + iName.substring(formattedInName.length()) + ".knz";
               }

               taskCtx.put("fileSize", fileSize);
               taskCtx.put("inputName", iName);
               taskCtx.put("outputName", oName);
               taskCtx.put("blockSize", this.blockSize);
               taskCtx.put("jobs", jobsPerTask[n++]);
               FileCompressTask task = new FileCompressTask(taskCtx, this.listeners);

               if (queue.offer(task) == false)
                  throw new RuntimeException("Could not create a compression task");
            }

            List<FileCompressWorker> workers = new ArrayList<>(this.jobs);

		  	   // Create one worker per job and run it. A worker calls several tasks sequentially.
            for (int i=0; i<this.jobs; i++)
               workers.add(new FileCompressWorker(queue));

            // Invoke the tasks concurrently and wait for results
            // Using workers instead of tasks direclty, allows for early exit on failure
            for (Future<FileCompressResult> result : this.pool.invokeAll(workers))
            {
               FileCompressResult fcr = result.get();
               read += fcr.read;
               written += fcr.written;

               if (fcr.code != 0)
               {
                  // Exit early by telling the workers that the queue is empty
                  queue.clear();
                  res = fcr.code;
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

         printOut("Total compression time: "+str, this.verbosity > 0);
         printOut("Total output size: "+written+" byte"+((written>1)?"s":""), this.verbosity > 0);

         if (read > 0)
         {
            float f = written / (float) read;
            printOut("Compression ratio: "+String.format("%1$.6f", f), this.verbosity > 0);
         }
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


   private static String getTransformAndCodec(int level)
   {
      switch (level)
      {
        case 0 :
           return "NONE&NONE";

        case 1 :
           return "PACK+LZ&NONE";

        case 2 :
           return "PACK+LZ&HUFFMAN";

        case 3 :
           return "TEXT+UTF+PACK+MM+LZX&HUFFMAN";

        case 4 :
           return "TEXT+UTF+EXE+PACK+MM+ROLZ&NONE";

        case 5 :
           return "TEXT+UTF+BWT+RANK+ZRLT&ANS0";

        case 6 :
           return "TEXT+UTF+BWT+SRT+ZRLT&FPAQ";

        case 7 :
           return "LZP+TEXT+UTF+BWT+LZP&CM";

        case 8 :
           return "EXE+RLT+TEXT+UTF&TPAQ";

        case 9 :
           return "EXE+RLT+TEXT+UTF&TPAQX";

        default :
           return "Unknown&Unknown";
      }
   }



   static class FileCompressResult
   {
      final int code;
      final long read;
      final long written;


      public FileCompressResult(int code, long read, long written)
      {
         this.code = code;
         this.read = read;
         this.written = written;
      }
   }


   static class FileCompressTask implements Callable<FileCompressResult>
   {
      private final Map<String, Object> ctx;
      private InputStream is;
      private CompressedOutputStream cos;
      private final List<Listener> listeners;


      public FileCompressTask(Map<String, Object> ctx, List<Listener> listeners)
      {
         this.ctx = ctx;
         this.listeners = listeners;
      }


      @Override
      public FileCompressResult call() throws Exception
      {
         int verbosity = (Integer) this.ctx.get("verbosity");
         String inputName = (String) this.ctx.get("inputName");
         String outputName = (String) this.ctx.get("outputName");
         
         if (verbosity > 2)
         {
            printOut("Input file name set to '" + inputName + "'", true);
            printOut("Output file name set to '" + outputName + "'", true);
         }
         
         boolean overwrite = (Boolean) this.ctx.get("overwrite");

         OutputStream os;
         File output = null;

         try
         {
            if (NONE.equalsIgnoreCase(outputName))
            {
               os = new NullOutputStream();
            }
            else if (STDOUT.equalsIgnoreCase(outputName))
            {
               os = System.out;
            }
            else
            {
               output = new File(outputName);

               if (output.exists())
               {
                  if (output.isDirectory())
                  {
                     System.err.println("The output file is a directory");
                     return new FileCompressResult(Error.ERR_OUTPUT_IS_DIR, 0, 0);
                  }

                  if (overwrite == false)
                  {
                     System.err.println("File '" + outputName + "' exists and " +
                        "the 'force' command line option has not been provided");
                     return new FileCompressResult(Error.ERR_OVERWRITE_FILE, 0, 0);
                  }

                  Path path1 = FileSystems.getDefault().getPath(inputName).toAbsolutePath();
                  Path path2 = FileSystems.getDefault().getPath(outputName).toAbsolutePath();

                  if (path1.equals(path2))
                  {
                     System.err.println("The input and output files must be different");
                     return new FileCompressResult(Error.ERR_CREATE_FILE, 0, 0);
                  }
               }

               try
               {
                  os = new FileOutputStream(output);
               }
               catch (IOException e1)
               {
                  if (overwrite == false)
                     throw e1;

                  try
                  {
                     // Attempt to create the full folder hierarchy to file
                     Files.createDirectories(FileSystems.getDefault().getPath(outputName).getParent());
                     os = new FileOutputStream(output);
                  }
                  catch (IOException e2)
                  {
                     throw e1;
                  }
               }
            }

            try
            {
               this.cos = new CompressedOutputStream(os, this.ctx);

               for (Listener bl : this.listeners)
                  this.cos.addListener(bl);
            }
            catch (Exception e)
            {
               System.err.println("Cannot create compressed stream: "+e.getMessage());
               return new FileCompressResult(Error.ERR_CREATE_COMPRESSOR, 0, 0);
            }
         }
         catch (Exception e)
         {
            System.err.println("Cannot open output file '"+outputName+"' for writing: " + e.getMessage());
            return new FileCompressResult(Error.ERR_CREATE_FILE, 0, 0);
         }

         try
         {
            this.is = (STDIN.equalsIgnoreCase(inputName)) ? System.in : new FileInputStream(inputName);
         }
         catch (Exception e)
         {
            System.err.println("Cannot open input file '"+inputName+"': " + e.getMessage());
            return new FileCompressResult(Error.ERR_OPEN_FILE, 0, 0);
         }

         // Encode
         printOut("\nCompressing "+inputName+" ...", verbosity>1);
         printOut("", verbosity>3);
         long read = 0;
         SliceByteArray sa = new SliceByteArray(new byte[DEFAULT_BUFFER_SIZE], 0);
         int len;

         if (this.listeners.size() > 0)
         {
            Event evt = new Event(Event.Type.COMPRESSION_START, -1, 0);
            Listener[] array = this.listeners.toArray(new Listener[this.listeners.size()]);
            notifyListeners(array, evt);
         }

         long before = System.nanoTime();

         try
         {
            while (true)
            {
               try
               {
                  len = this.is.read(sa.array, 0, sa.length);
               }
               catch (Exception e)
               {
                  System.err.print("Failed to read block from file '"+inputName+"': ");
                  System.err.println(e.getMessage());
                  return new FileCompressResult(Error.ERR_READ_FILE, read, this.cos.getWritten());
               }

               if (len <= 0)
                  break;

               // Just write block to the compressed output stream !
               read += len;
               this.cos.write(sa.array, 0, len);
            }
         }
         catch (kanzi.io.IOException e)
         {
            System.err.println("An unexpected condition happened. Exiting ...");
            System.err.println(e.getMessage());
            return new FileCompressResult(e.getErrorCode(), read, this.cos.getWritten());
         }
         catch (Exception e)
         {
            System.err.println("An unexpected condition happened. Exiting ...");
            System.err.println(e.getMessage());
            return new FileCompressResult(Error.ERR_UNKNOWN, read, this.cos.getWritten());
         }
         finally
         {
            // Close streams to ensure all data are flushed
            this.dispose();

            try
            {
               os.close();
            }
            catch (IOException e)
            {
               // Ignore
            }
         }

         if (read == 0)
         {
            printOut("Input file " + inputName + " is empty... nothing to do", verbosity > 0);
            
            if (output != null)
               output.delete(); // best effort to delete output file, ignore return code
            
            return new FileCompressResult(0, read, this.cos.getWritten());
         }

         long after = System.nanoTime();
         long delta = (after - before) / 1000000L; // convert to ms
         String str;

         if (verbosity >= 1)
         {
            printOut("", verbosity>1);
            
            if (delta >= 100000) 
               str = String.format("%1$.1f", (float) delta/1000) + " s";
            else
               str = String.valueOf(delta) + " ms";

            float f = this.cos.getWritten() / (float) read;
            
            if (verbosity > 1)
            {
               printOut("Compressing:       "+str, true);
               printOut("Input size:        "+read, true);
               printOut("Output size:       "+this.cos.getWritten(), true);
               printOut("Compression ratio: "+String.format("%1$.6f", f), true);
            }
            
            if (verbosity == 1)
            {
               str = String.format("Compressing %s: %d => %d (%.2f%%) in %s", inputName, read, this.cos.getWritten(), 100*f, str);
               printOut(str, true);
            }

            if ((verbosity > 1) && (delta > 0))
               printOut("Throughput (KB/s): "+(((read * 1000L) >> 10) / delta), true);

            printOut("", verbosity>1);
         }

         if (this.listeners.size() > 0)
         {
            Event evt = new Event(Event.Type.COMPRESSION_END, -1, this.cos.getWritten());
            Listener[] array = this.listeners.toArray(new Listener[this.listeners.size()]);
            notifyListeners(array, evt);
         }

         return new FileCompressResult(0, read, this.cos.getWritten());
      }


      public void dispose() throws IOException
      {
         if (this.is != null)
            this.is.close();

         if (this.cos != null)
            this.cos.close();
      }
   }


   static class FileCompressWorker implements Callable<FileCompressResult>
   {
      private final ArrayBlockingQueue<FileCompressTask> queue;


      public FileCompressWorker(ArrayBlockingQueue<FileCompressTask> queue)
      {
         this.queue = queue;
      }

      @Override
      public FileCompressResult call() throws Exception
      {
         int res = 0;
         long read = 0;
         long written = 0;

         while (res == 0)
         {
            FileCompressTask task = this.queue.poll();

            if (task == null)
               break;

            FileCompressResult result = task.call();
            res = result.code;
            read += result.read;
            written += result.written;
         }

         return new FileCompressResult(res, read, written);
      }
   }

}
