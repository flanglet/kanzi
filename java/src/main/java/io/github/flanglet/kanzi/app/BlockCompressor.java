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
import io.github.flanglet.kanzi.SliceByteArray;
import io.github.flanglet.kanzi.io.CompressedOutputStream;
import io.github.flanglet.kanzi.io.IOUtil;
import io.github.flanglet.kanzi.Error;
import io.github.flanglet.kanzi.Global;
import io.github.flanglet.kanzi.io.NullOutputStream;
import io.github.flanglet.kanzi.Listener;
import io.github.flanglet.kanzi.transform.TransformFactory;


/**
 * The {@code BlockCompressor} class implements a multithreaded block
 * compression algorithm. It is designed to handle compression tasks
 * in a concurrent manner, allowing for efficient data processing.
 *
 * <p>This class implements the {@code Runnable} and {@code Callable}
 * interfaces, enabling it to be executed in a separate thread and
 * return a status code upon completion.</p>
 */
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
   private final boolean skipBlocks;
   private final boolean removeInput;
   private final boolean reoderFiles;
   private final boolean noDotFiles;
   private final boolean noLinks;
   private final boolean autoBlockSize;
   private final String inputName;
   private final String outputName;
   private final String codec;
   private final String transform;
   private int blockSize;
   private final int checksum;
   private final int jobs;
   private final List<Listener> listeners;
   private final ExecutorService pool;


   /**
    * Constructs a {@code BlockCompressor} with the specified parameters.
    *
    * @param map a map containing configuration options for the compressor
    */
   public BlockCompressor(Map<String, Object> map)
   {
      int level = - 1;

      if (map.containsKey("level") == true)
      {
         level = (Integer) map.remove("level");

         if ((level < 0) || (level > 9))
            throw new IllegalArgumentException("Invalid compression level (must be in [0..9], got " + level);

         String tranformAndCodec = getTransformAndCodec(level);
         String[] tokens = tranformAndCodec.split("&");
         this.transform = tokens[0];
         this.codec = tokens[1];
      }
      else
      {
         if ((map.containsKey("transform") == false) && (map.containsKey("entropy") == false))
         {
            // Defaults to level 3
            String tranformAndCodec = getTransformAndCodec(3);
            String[] tokens = tranformAndCodec.split("&");
            this.transform = tokens[0];
            this.codec = tokens[1];
         }
         else
         {
            // Extract transform names. Curate input (EG. NONE+NONE+xxxx => xxxx)
            String strT = map.containsKey("transform") ? (String) map.remove("transform") : "NONE";
            TransformFactory tf = new TransformFactory();
            this.transform = tf.getName(tf.getType(strT));
            this.codec = map.containsKey("entropy") ? (String) map.remove("entropy") : "NONE";
         }
      }

      this.overwrite = Boolean.TRUE.equals(map.remove("overwrite"));
      this.skipBlocks = Boolean.TRUE.equals(map.remove("skipBlocks"));
      String iName = (String) map.remove("inputName");

      if (Global.isReservedName(iName))
          throw new IllegalArgumentException("'"+iName+"' is a reserved name");

      this.inputName = iName.isEmpty() ? STDIN : iName;
      String oName = (String) map.remove("outputName");

      if (Global.isReservedName(oName))
          throw new IllegalArgumentException("'"+oName+"' is a reserved name");

      this.outputName = (oName.isEmpty() && STDIN.equalsIgnoreCase(iName)) ? STDOUT : oName;
      Integer iBlockSize = (Integer) map.remove("block");

      if (iBlockSize == null)
      {
         switch (level)
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
               " KiB ("+MIN_BLOCK_SIZE+" bytes), got "+bs+" bytes");

         if (bs > MAX_BLOCK_SIZE)
            throw new IllegalArgumentException("Maximum block size is "+(MAX_BLOCK_SIZE/(1024*1024*1024))+
               " GiB ("+MAX_BLOCK_SIZE+" bytes), got "+bs+" bytes");

         this.blockSize = Math.min((bs+15) & -16, MAX_BLOCK_SIZE);
      }

      this.checksum = map.containsKey("checksum") ? (Integer) (map.remove("checksum")) : 0;
      this.removeInput = map.containsKey("remove") ? Boolean.TRUE.equals(map.remove("remove")) : false;
      this.reoderFiles = map.containsKey("fileReorder") ? Boolean.TRUE.equals(map.remove("fileReorder")) : true;
      this.noDotFiles = map.containsKey("noDotFiles") ? Boolean.TRUE.equals(map.remove("noDotFiles")) : false;
      this.noLinks = map.containsKey("noLinks") ? Boolean.TRUE.equals(map.remove("noLinks")) : false;
      this.autoBlockSize = map.containsKey("autoBlock") ? Boolean.TRUE.equals(map.remove("autoBlock")) : false;
      this.verbosity = (Integer) map.remove("verbose");
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


   /**
    * Cleans up resources used by the {@code BlockCompressor}.
    * Shuts down the thread pool if it exists.
    */
   public void dispose()
   {
      if (this.pool != null)
         this.pool.shutdown();
   }


   /**
    * Executes the compression process by calling the {@code call} method.
    */
   @Override
   public void run()
   {
      this.call();
   }


   /**
    * Performs the compression task and returns a status code.
    *
    * @return an integer indicating the status of the compression (success = 0, error = negative value )
    */
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

            IOUtil.createFileList(target, files, isRecursive, this.noLinks, this.noDotFiles);
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
            printOut("Block size: 'auto'", true);
         else
            printOut("Block size: " + this.blockSize + " bytes", true);

         printOut("Verbosity: " + this.verbosity, true);
         printOut("Overwrite: " + this.overwrite, true);
         String chksum = ((this.checksum == 32) || (this.checksum == 64)) ? this.checksum+" bits" : "NONE";
         printOut("Block checksum: " + chksum, true);
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
         ctx.put("entropy", this.codec);
         ctx.put("transform", this.transform);
         ctx.put("remove", this.removeInput);

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
            ArrayBlockingQueue<FileCompressTask> queue = new ArrayBlockingQueue<>(nbFiles, true);
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
               Map<String, Object> taskCtx = new HashMap<>(ctx);

               if ((this.autoBlockSize == true) && (this.jobs > 0))
               {
                   long bl = fileSize / this.jobs;
                   bl = (bl + 63) & ~63;
                   this.blockSize = (int) Math.max(Math.min(bl, MAX_BLOCK_SIZE), MIN_BLOCK_SIZE);
               }

               if (oName.isEmpty()
               {
                  oName = iName + ".bak";
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
            // Using workers instead of tasks directly, allows for early exit on failure
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

         printOut("Total compression time: "+str, this.verbosity > 0);
         printOut("Total output size: "+written+" byte"+((written>1)?"s":""), this.verbosity > 0);

         if (read != 0)
         {
            float f = written / (float) read;
            printOut("Compression ratio: "+String.format("%1$.6f", f), this.verbosity > 0);
         }
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
    * Returns the transformation and codec settings based on the specified level.
    *
    * @param level the level of compression
    * @return a string representing the transformation and codec settings
    */
   private static String getTransformAndCodec(int level)
   {
      switch (level)
      {
        case 0 :
           return "NONE&NONE";

        case 1 :
           return "PACK+LZ&NONE";

        case 2 :
           return "DNA+LZ&HUFFMAN";

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
           return "EXE+RLT+TEXT+UTF+DNA&TPAQ";

        case 9 :
           return "EXE+RLT+TEXT+UTF+DNA&TPAQX";

        default :
           return "Unknown&Unknown";
      }
   }



   /**
    * Represents the result of a file compression task.
    */
   static class FileCompressResult
   {
      final int code;
      final long read;
      final long written;


      /**
       * Constructs a {@code FileCompressResult} with the specified values.
       *
       * @param code the status code of the compression task
       * @param read the amount of data read
       * @param written the amount of data written
       */
      public FileCompressResult(int code, long read, long written)
      {
         this.code = code;
         this.read = read;
         this.written = written;
      }
   }


   /**
    * Represents a task that compresses a file.
    */
   static class FileCompressTask implements Callable<FileCompressResult>
   {
      private final Map<String, Object> ctx;
      private InputStream is;
      private CompressedOutputStream cos;
      private final List<Listener> listeners;


     /**
      * Constructs a {@code FileCompressTask} with the specified context and listeners.
      *
      * @param ctx the context for the compression task
      * @param listeners the list of listeners to notify during processing
      */
      public FileCompressTask(Map<String, Object> ctx, List<Listener> listeners)
      {
         this.ctx = ctx;
         this.listeners = listeners;
      }


      /**
       * Executes the compression task and returns the result.
       *
       * @return a {@code FileCompressResult} representing the outcome of the task
       * @throws Exception if an error occurs during compression
       */
      @Override
      public FileCompressResult call() throws Exception
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

         if (this.listeners.isEmpty() == false)
         {
            long inputSize = (Long) this.ctx.getOrDefault("fileSize", 0L);
            Event evt = new Event(Event.Type.COMPRESSION_START, 0, inputSize);
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
         catch (io.github.flanglet.kanzi.io.IOException e)
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

            if (verbosity > 1)
            {
               printOut("Compression time:  "+str, true);
               printOut("Input size:        "+read, true);
               printOut("Output size:       "+this.cos.getWritten(), true);

               if (read != 0)
                   printOut("Compression ratio: "+String.format("%1$.6f", (this.cos.getWritten() / (float) read)), true);
            }
            else
            {
               if (read == 0)
               {
                   str = String.format("Compressed %s: %d => %d in %s", inputName, read, this.cos.getWritten(), str);
               }
               else
               {
                   float f = this.cos.getWritten() / (float) read;
                   str = String.format("Compressed %s: %d => %d (%.2f%%) in %s", inputName, read, this.cos.getWritten(), 100*f, str);
               }

               printOut(str, true);
            }

            if ((verbosity > 1) && (delta != 0) && (read != 0))
               printOut("Throughput (KiB/s): "+(((read * 1000L) >> 10) / delta), true);

            printOut("", verbosity>1);
         }

         if (this.listeners.isEmpty() == false)
         {
            Event evt = new Event(Event.Type.COMPRESSION_END, -1, this.cos.getWritten());
            Listener[] array = this.listeners.toArray(new Listener[this.listeners.size()]);
            notifyListeners(array, evt);
         }

         if (Boolean.TRUE.equals(this.ctx.get("remove")))
         {
             // Delete input file
             if (inputName.equals("STDIN"))
                 printOut("Warning: ignoring remove option with STDIN", verbosity>0);
             else if (Files.deleteIfExists(Paths.get(inputName)) == false)
                 printOut("Warning: input file could not be deleted", verbosity>0);
         }

         return new FileCompressResult(0, read, this.cos.getWritten());
      }

     /**
      * Cleans up resources used by the compression task.
      *
      * @throws IOException if an error occurs while closing resources
      */
      public void dispose() throws IOException
      {
         if (this.is != null)
            this.is.close();

         if (this.cos != null)
            this.cos.close();
      }
   }


  /**
   * Represents a worker that processes file compression tasks.
   */
   static class FileCompressWorker implements Callable<FileCompressResult>
   {
      private final ArrayBlockingQueue<FileCompressTask> queue;


     /**
      * Constructs a {@code FileCompressWorker} with the specified task queue.
      *
      * @param queue the queue of tasks to be processed
      */
      public FileCompressWorker(ArrayBlockingQueue<FileCompressTask> queue)
      {
         this.queue = queue;
      }

     /**
      * Executes the worker's task of compressing files.
      *
      * @return a {@code FileCompressResult} representing the outcome of the tasks
      * @throws Exception if an error occurs during processing
      */
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
