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
import java.util.HashMap;
import java.util.Map;



/**
 * The {@code Kanzi} class is a command-line application for compressing
 * and decompressing data using a fast lossless algorithm.
 *
 * <p>It provides functionalities for processing command line arguments
 * and executing compression and decompression based on those arguments.</p>
 */
public class Kanzi
{
   /**
     * An array of command line argument options.
     */
   private static final String[] CMD_LINE_ARGS = new String[]
   {
      "-c", "-d", "-i", "-o", "-b", "-t", "-e", "-j", "-v", "-l", "-s", "-x", "-f", "-h"
   };

   // Argument index constants for easier reference
   //private static final int ARG_IDX_COMPRESS = 0;
   //private static final int ARG_IDX_DECOMPRESS = 1;
   private static final int ARG_IDX_INPUT = 2;
   private static final int ARG_IDX_OUTPUT = 3;
   private static final int ARG_IDX_BLOCK = 4;
   private static final int ARG_IDX_TRANSFORM = 5;
   private static final int ARG_IDX_ENTROPY = 6;
   private static final int ARG_IDX_JOBS = 7;
   private static final int ARG_IDX_VERBOSE = 8;
   private static final int ARG_IDX_LEVEL = 9;
   // private static final int ARG_IDX_CHECKSUM = 10;
   // private static final int ARG_IDX_FROM = 11;
   // private static final int ARG_IDX_TO = 12;

   private static final String KANZI_VERSION = "2.3.0";
   private static final String APP_HEADER = "Kanzi " + KANZI_VERSION + " (c) Frederic Langlet";
   private static final String APP_SUB_HEADER = "Fast lossless data compressor.";
   private static final String APP_USAGE = "Usage: java -jar kanzi.jar [-c|-d] [flags and files in any order]";



   /**
    * The main method that serves as the entry point for the Kanzi application.
    *
    * @param args command line arguments passed to the application
    */
   public static void main(String[] args)
   {
      Map<String, Object> map = new HashMap<>();
      int status = processCommandLine(args, map);

      // Command line processing error ?
      if (status != 0)
         System.exit(status);

      // Help mode only ?
      if (map.containsKey("mode") == false)
         System.exit(0);

      char mode = (char) map.remove("mode");

      if (mode == 'c')
      {
         BlockCompressor bc = null;

         try
         {
            bc = new BlockCompressor(map);
         }
         catch (Exception e)
         {
            System.err.println("Could not create the compressor: "+e.getMessage());
            System.exit(io.github.flanglet.kanzi.Error.ERR_CREATE_COMPRESSOR);
         }

         int code = bc.call();

         try
         {
            if (code != 0)
               bc.dispose();
         }
         catch (Exception e)
         {
            String inputName = String.valueOf(map.get("inputName"));
            System.err.println("Compression failure for '" + inputName+"' : " + e.getMessage());
            code = io.github.flanglet.kanzi.Error.ERR_WRITE_FILE;
         }

         System.exit(code);
      }

      if (mode == 'd')
      {
         BlockDecompressor bd = null;

         try
         {
            bd = new BlockDecompressor(map);
         }
         catch (Exception e)
         {
            System.err.println("Could not create the decompressor: "+e.getMessage());
            System.exit(io.github.flanglet.kanzi.Error.ERR_CREATE_DECOMPRESSOR);
         }

         int code = bd.call();

         try
         {
            if (code != 0)
               bd.dispose();
         }
         catch (Exception e)
         {
            String inputName = String.valueOf(map.get("inputName"));
            System.err.println("Decompression failure for '" + inputName+"' : " + e.getMessage());
            code = io.github.flanglet.kanzi.Error.ERR_WRITE_FILE;
         }

         System.exit(code);
      }

      System.out.println("Missing arguments: try --help or -h");
      System.exit(1);
   }


    /**
     * Processes the command line arguments and populates the provided map with options.
     *
     * @param args the command line arguments
     * @param map a map to store processed options and their values
     * @return an integer indicating the status of the processing
     */
    private static int processCommandLine(String[] args, Map<String, Object> map)
    {
        int blockSize = -1;
        int verbose = 1;
        boolean overwrite = false;
        boolean skip = false;
        boolean remove = false;
        boolean fileReorder = true;
        boolean noDotFiles = false;
        boolean noLinks = false;
        boolean autoBlockSize = false;
        String inputName = "";
        String outputName = "";
        String verboseLevel = null;
        String codec = null;
        String transform = null;
        int checksum = 0;
        int from = -1;
        int to = -1;
        int tasks = -1;
        int ctx = -1;
        int level = -1;
        char mode = ' ';
        boolean showHeader = true;
        boolean showHelp = false;

        for (String arg : args)
        {
           arg = arg.trim();

           if ((arg.startsWith("--output=")) || (arg.equals("-o")))
           {
              ctx = ARG_IDX_OUTPUT;
              continue;
           }

           if ((arg.startsWith("--input=")) || (arg.equals("-i")))
           {
              ctx = ARG_IDX_INPUT;
              continue;
           }

           if ((arg.startsWith("--verbose=")) || (arg.equals("-v")))
           {
              ctx = ARG_IDX_VERBOSE;
              continue;
           }

           // Extract verbosity, output and mode first
           if (arg.equals("--compress") || (arg.equals("-c")))
           {
              if (mode == 'd')
              {
                  System.err.println("Both compression and decompression options were provided.");
                  return io.github.flanglet.kanzi.Error.ERR_INVALID_PARAM;
              }

              mode = 'c';
              continue;
           }

           if (arg.equals("--decompress") || (arg.equals("-d")))
           {
              if (mode == 'c')
              {
                  System.err.println("Both compression and decompression options were provided.");
                  return io.github.flanglet.kanzi.Error.ERR_INVALID_PARAM;
              }

              mode = 'd';
              continue;
           }

           if (arg.startsWith("--verbose=") || (ctx == ARG_IDX_VERBOSE))
           {
               if (verboseLevel != null)
               {
                   String msg = (ctx == ARG_IDX_VERBOSE) ? CMD_LINE_ARGS[ctx] : "--verbose";
                   printWarning(msg, " (duplicate verbosity).", verbose);
               }
               else
               {
                  verboseLevel = arg.startsWith("--verbose=") ? arg.substring(10).trim() : arg;

                  try
                  {
                      verbose = Integer.parseInt(verboseLevel);

                      if ((verbose < 0) || (verbose > 5))
                         throw new NumberFormatException();
                  }
                  catch (NumberFormatException e)
                  {
                     System.err.println("Invalid verbosity level provided on command line: "+arg);
                     return io.github.flanglet.kanzi.Error.ERR_INVALID_PARAM;
                  }
               }
           }
           else if (ctx == ARG_IDX_OUTPUT)
           {
               outputName =  arg.trim();
           }
           else if (ctx == ARG_IDX_INPUT)
           {
               inputName =  arg.trim();
           }
           else if (arg.equals("--help") || arg.equals("-h"))
           {
               showHelp = true;
           }

           ctx = -1;
        }

        if ((showHelp == true) || (args.length == 0))
        {
            printHelp(mode, showHeader);
            return 0;
        }

        // Overwrite verbosity if the output goes to stdout
        if (((inputName.length() == 0) && (outputName.length() == 0)) || "STDOUT".equalsIgnoreCase(outputName))
            verbose = 0;

        if (showHeader == true)
        {
            printOut("\n"+APP_HEADER +"\n", verbose>=1);
            printOut(APP_SUB_HEADER, verbose>1);
        }

        inputName = "";
        outputName = "";
        ctx = -1;

        for (String arg : args)
        {
           arg = arg.trim();

           if (arg.equals("--compress") || arg.equals("-c") || arg.equals("--decompress") || arg.equals("-d"))
           {
               if (ctx != -1)
               {
                  printWarning(CMD_LINE_ARGS[ctx], " with no value.", verbose);
               }

               ctx = -1;
               continue;
           }

           if (arg.equals("--force") || arg.equals("-f"))
           {
               if (ctx != -1)
                  printWarning(CMD_LINE_ARGS[ctx], " with no value.", verbose);

               overwrite = true;
               ctx = -1;
               continue;
           }

           if (arg.equals("--skip") || arg.equals("-s"))
           {
               if (ctx != -1)
                  printWarning(CMD_LINE_ARGS[ctx], " with no value.", verbose);

               skip = true;
               ctx = -1;
               continue;
           }

           if (arg.equals("-x32") || arg.equals("-x64") || arg.equals("-x"))
           {
               if (ctx != -1)
                  printWarning(CMD_LINE_ARGS[ctx], " with no value.", verbose);

               checksum = (arg.equals("-x64")) ? 64 : 32;
               ctx = -1;
               continue;
           }

           if (arg.equals("--rm"))
           {
               if (ctx != -1)
                  printWarning(CMD_LINE_ARGS[ctx], " with no value.", verbose);

               remove = true;
               ctx = -1;
               continue;
           }

           if (arg.equals("--no-file-reorder"))
           {
               if (ctx != -1)
                  printWarning(CMD_LINE_ARGS[ctx], " with no value.", verbose);

               ctx = -1;

               if (mode != 'c')
               {
                  printWarning(arg, " Only applicable in compress mode.", verbose);
                  continue;
               }

               fileReorder = false;
               continue;
           }

           if (arg.equals("--no-dot-file"))
           {
               if (ctx != -1)
                  printWarning(CMD_LINE_ARGS[ctx], " with no value.", verbose);

               ctx = -1;
               noDotFiles = true;
               continue;
           }

           if (arg.equals("--no-link"))
           {
               if (ctx != -1)
                  printWarning(CMD_LINE_ARGS[ctx], " with no value.", verbose);

               ctx = -1;
               noLinks = true;
               continue;
           }

           if (ctx == -1)
           {
               int idx = -1;

               for (int i=0; i<CMD_LINE_ARGS.length; i++)
               {
                  if (CMD_LINE_ARGS[i].equals(arg))
                  {
                     idx = i;
                     break;
                  }
               }

               if (idx != -1)
               {
                  ctx = idx;
                  continue;
               }
           }

           if (arg.startsWith("--output=") || (ctx == ARG_IDX_OUTPUT))
           {
               String name = arg.startsWith("--output=") ? arg.substring(9).trim() : arg;

               if (!outputName.isEmpty())
               {
                  String msg = (ctx == ARG_IDX_OUTPUT) ? CMD_LINE_ARGS[ctx] : "--output";
                  printWarning(msg, " (duplicate output name).", verbose);
               }
               else
               {
                  outputName = name;
               }

               ctx = -1;
               continue;
           }

           if (arg.startsWith("--input=") || (ctx == ARG_IDX_INPUT))
           {
               String name = arg.startsWith("--input=") ? arg.substring(8).trim() : arg;

               if (!inputName.isEmpty())
               {
                  String msg = (ctx == ARG_IDX_INPUT) ? CMD_LINE_ARGS[ctx] : "--input";
                  printWarning(msg, " (duplicate input name).", verbose);
               }
               else
               {
                  inputName = name;
               }

               ctx = -1;
               continue;
           }

           if (arg.startsWith("--entropy=") || (ctx == ARG_IDX_ENTROPY))
           {
               String name = arg.startsWith("--entropy=") ? arg.substring(10).trim().toUpperCase() :
                 arg.toUpperCase();

               if (codec != null)
               {
                  String msg = (ctx == ARG_IDX_ENTROPY) ? CMD_LINE_ARGS[ctx] : "--entropy";
                  printWarning(msg, " (duplicate entropy).", verbose);
               }
               else
               {
                  codec = name;
               }

               if (codec.isEmpty())
               {
                  System.err.println("Invalid empty entropy provided on command line");
                  return io.github.flanglet.kanzi.Error.ERR_INVALID_PARAM;
               }

               ctx = -1;
               continue;
           }

           if (arg.startsWith("--transform=") || (ctx == ARG_IDX_TRANSFORM))
           {
               String name = arg.startsWith("--transform=") ? arg.substring(12).trim().toUpperCase() :
                 arg.toUpperCase();

               if (transform != null)
               {
                  String msg = (ctx == ARG_IDX_TRANSFORM) ? CMD_LINE_ARGS[ctx] : "--transform";
                  printWarning(msg, " (duplicate transform).", verbose);
               }
               else
               {
                  transform = name;
               }

               while ((!transform.isEmpty()) && (transform.charAt(0) == '+'))
                  transform = transform.substring(1);

               while ((!transform.isEmpty()) && (transform.charAt(transform.length()-1) == '+'))
                  transform = transform.substring(0, transform.length()-1);

               if (transform.isEmpty())
               {
                  System.err.println("Invalid empty transform provided on command line");
                  return io.github.flanglet.kanzi.Error.ERR_INVALID_PARAM;
               }

               ctx = -1;
               continue;
           }

           if (arg.startsWith("--level=") || (ctx == ARG_IDX_LEVEL))
           {
               String name = arg.startsWith("--level=") ? arg.substring(8).trim().toUpperCase() :
                 arg.toUpperCase();

               if (level != -1)
               {
                  String msg = (ctx == ARG_IDX_LEVEL) ? CMD_LINE_ARGS[ctx] : "--level";
                  printWarning(msg, " (duplicate level).", verbose);
                  ctx = -1;
                  continue;
               }

               try
               {
                  level = Integer.parseInt(name);
               }
               catch (NumberFormatException e)
               {
                  System.err.println("Invalid compression level provided on command line: "+arg);
                  return io.github.flanglet.kanzi.Error.ERR_INVALID_PARAM;
               }

               if ((level < 0) || (level > 9))
               {
                  System.err.println("Invalid compression level provided on command line: "+arg);
                  return io.github.flanglet.kanzi.Error.ERR_INVALID_PARAM;
               }

               ctx = -1;
               continue;
           }

           if (arg.startsWith("--checksum="))
           {
               String name = arg.startsWith("--checksum=") ? arg.substring(11).trim().toUpperCase() :
                 arg.toUpperCase();

               if (checksum != 0)
               {
                  printWarning("--checksum", " (duplicate checksum).", verbose);
                  ctx = -1;
                  continue;
               }

               try
               {
                  checksum = Integer.parseInt(name);
               }
               catch (NumberFormatException e)
               {
                  System.err.println("Invalid block checksum size provided on command line: "+arg);
                  return io.github.flanglet.kanzi.Error.ERR_INVALID_PARAM;
               }

               if ((checksum != 32) && (checksum != 64))
               {
                  System.err.println("Invalid block checksum size provided on command line: "+arg);
                  return io.github.flanglet.kanzi.Error.ERR_INVALID_PARAM;
               }

               ctx = -1;
               continue;
           }

           if (arg.startsWith("--block=") || (ctx == ARG_IDX_BLOCK))
           {
               String name = arg.startsWith("--block=") ? arg.substring(8).toUpperCase().trim() :
                  arg.toUpperCase();

               if ((blockSize != -1) || (autoBlockSize == true))
               {
                  String msg = (ctx == ARG_IDX_BLOCK) ? CMD_LINE_ARGS[ctx] : "--block";
                  printWarning(msg, " (duplicate block size).", verbose);
                  ctx = -1;
                  continue;
               }

               if (name.equals("AUTO"))
               {
                   autoBlockSize = true;
               }
               else
               {
                   char lastChar = (name.length() == 0) ? ' ' : name.charAt(name.length()-1);
                   int scale = 1;

                   try
                   {
                       // Process K or M or G suffix
                       switch (lastChar)
                       {
                          case 'K':
                             scale = 1024;
                             name = name.substring(0, name.length()-1);
                             break;
                          case 'M':
                             scale = 1024 * 1024;
                             name = name.substring(0, name.length()-1);
                             break;
                          case 'G':
                             scale = 1024 * 1024 * 1024;
                             name = name.substring(0, name.length()-1);
                             break;
                          default:
                             break;
                       }

                       blockSize = scale * Integer.parseInt(name);
                       ctx = -1;
                       continue;
                   }
                   catch (NumberFormatException e)
                   {
                       System.err.println("Invalid block size provided on command line: "+arg);
                       return io.github.flanglet.kanzi.Error.ERR_INVALID_PARAM;
                   }
               }
           }

           if (arg.startsWith("--jobs=") || (ctx == ARG_IDX_JOBS))
           {
               String name = arg.startsWith("--jobs=") ? arg.substring(7).trim() : arg;

               if (tasks != -1)
               {
                  String msg = (ctx == ARG_IDX_JOBS) ? CMD_LINE_ARGS[ctx] : "--jobs";
                  printWarning(msg, " (duplicate jobs).", verbose);
                  ctx = -1;
                  continue;
               }

               try
               {
                  tasks = Integer.parseInt(name);

                  if (tasks < 0)
                     throw new NumberFormatException();

                  ctx = -1;
                  continue;
              }
              catch (NumberFormatException e)
              {
                  System.err.println("Invalid number of jobs provided on command line: "+arg);
                  return io.github.flanglet.kanzi.Error.ERR_INVALID_PARAM;
              }
           }

           if (arg.startsWith("--from=") && (ctx == -1))
           {
               String name = arg.startsWith("--from=") ? arg.substring(7).trim() : arg;

               if (from != -1)
               {
                  printWarning("--from", " (duplicate start block).", verbose);
                  ctx = -1;
                  continue;
               }

               try
               {
                  from = Integer.parseInt(name);

                  if (from <= 0)
                     throw new NumberFormatException(String.valueOf(from));

                  continue;
              }
              catch (NumberFormatException e)
              {
                  System.err.println("Invalid start block provided on command line: "+arg);

                  if ("0".equals(e.getMessage()))
                     System.err.println("The first block ID is 1.");

                  return io.github.flanglet.kanzi.Error.ERR_INVALID_PARAM;
              }
           }

           if (arg.startsWith("--to=") && (ctx == -1))
           {
               String name = arg.startsWith("--to=") ? arg.substring(5).trim() : arg;

               if (to != -1)
               {
                  printWarning("--to", " (duplicate end block).", verbose);
                  ctx = -1;
                  continue;
               }

               try
               {
                  to = Integer.parseInt(name);

                  if (to <= 0) // Must be > 0 (0 means nothing to do)
                     throw new NumberFormatException();

                  continue;
              }
              catch (NumberFormatException e)
              {
                  System.err.println("Invalid start block provided on command line: "+arg);
                  return io.github.flanglet.kanzi.Error.ERR_INVALID_PARAM;
              }
           }

           if (!arg.startsWith("--verbose=") && (ctx == -1) && !arg.startsWith("--output="))
           {
               printWarning(arg, " (unknown option).", verbose);
           }

           ctx = -1;
        }

        if (ctx != -1)
        {
           printWarning(CMD_LINE_ARGS[ctx], " (missing value).", verbose);
        }

        if (mode != 'd')
        {
            if (from >= 0)
               printWarning("from", "(only valid for decompression).", verbose);

            if (to >= 0)
               printWarning("to", "(only valid for decompression).", verbose);
        }
        else
        {
           if (from >= 0)
              map.put("from", from);

           if (to >= 0)
              map.put("to", to);
        }

        if (mode != 'c')
        {
           if (blockSize != -1)
               printWarning("blockSize", "(only valid for compression).", verbose);

           if (level != -1)
               printWarning("level", "(only valid for compression).", verbose);

           if (codec != null)
               printWarning("entropy", "(only valid for compression).", verbose);

           if (transform != null)
               printWarning("transform", "(only valid for compression).", verbose);

           if (checksum != 0)
               printWarning("checksum", "(only valid for compression).", verbose);

           if (skip == true)
               printWarning("skip", "(only valid for compression).", verbose);

           if (fileReorder == false)
               printWarning("fileReorder", "(only valid for compression).", verbose);
        }
        else
        {
           if (blockSize != -1)
              map.put("block", blockSize);

           if (autoBlockSize == true)
               map.put("autoBlock", true);

           if (level != -1)
              map.put("level", level);

           if (codec != null)
              map.put("entropy", codec);

           if (transform != null)
              map.put("transform", transform);

           if (checksum != 0)
              map.put("checksum", checksum);

           if (skip == true)
              map.put("skipBlocks", true);

           if (fileReorder == false)
              map.put("fileReorder", false);
        }

        map.put("verbose", verbose);
        map.put("mode", mode);

        if (overwrite == true)
           map.put("overwrite", true);

        if (remove == true)
           map.put("remove", true);

        map.put("inputName", inputName);
        map.put("outputName", outputName);

        if (noDotFiles == true)
           map.put("noDotFiles", true);

        if (noLinks == true)
           map.put("noLinks", true);

        if (tasks >= 0)
           map.put("jobs", tasks);

        return 0;
    }


    /**
     * Prints help information for using the Kanzi application.
     *
     * @param mode the mode of operation (compress or decompress)
     * @param showHeader whether to show the application header
     */
    private static void printHelp(char mode, boolean showHeader)
    {
      if (showHeader == true)
      {
         printOut("", true);
         printOut(APP_HEADER+"\n", true);
         printOut(APP_SUB_HEADER, true);
         printOut(APP_USAGE, true);
      }

      printOut("", true);
      printOut("Credits: Matt Mahoney, Yann Collet, Jan Ondrus, Yuta Mori, Ilya Muravyov,", true);
      printOut("         Neal Burns, Fabian Giesen, Jarek Duda, Ilya Grebnov", true);
      printOut("", true);
      printOut("   -h, --help", true);
      printOut("        Display this message\n", true);

      if ((mode != 'c') && (mode != 'd'))
      {
         printOut("   -c, --compress", true);
         printOut("        Compress mode\n", true);
         printOut("   -d, --decompress", true);
         printOut("        Decompress mode\n", true);
      }

      printOut("   -i, --input=<inputName>", true);
      printOut("        Name of the input file or directory or 'stdin'.", true);
      printOut("        When the source is a directory, all files in it will be processed.", true);
      printOut("        Provide " + File.separator + ". at the end of the directory name to avoid recursion", true);
      printOut("        (EG: myDir" + File.separator + ". => no recursion)", true);
      printOut("        If this option is not provided, kanzi reads data from stdin.\n", true);
      printOut("   -o, --output=<outputName>", true);

      if (mode == 'c')
      {
         printOut("        Optional name of the output file or directory (defaults to", true);
         printOut("        <inputName.knz>) or 'none' or 'stdout'. 'stdout' is not valid", true);
         printOut("        when the number of jobs is greater than 1.\n", true);
      }
      else if (mode == 'd')
      {
         printOut("        Optional name of the output file or directory (defaults to", true);
         printOut("        <inputName.bak>) or 'none' or 'stdout'. 'stdout' is not valid", true);
         printOut("        when the number of jobs is greater than 1.\n", true);
      }
      else
      {
         printOut("        Optional name of the output file or 'none' or 'stdout'.\n", true);
      }

      if (mode == 'c')
      {
         printOut("   -b, --block=<size>", true);
         printOut("        Size of blocks (default 4|8|16|32 MiB based on level, max 1 GiB, min 1 KiB).", true);
         printOut("        'auto' means that the compressor derives the best value'", true);
         printOut("        based on input size (when available) and number of jobs.\n", true);
         printOut("   -l, --level=<compression>", true);
         printOut("        Set the compression level [0..9]", true);
         printOut("        Providing this option forces entropy and transform.", true);
         printOut("        0=None&None (store)", true);
         printOut("        1=PACK+LZ&NONE", true);
         printOut("        2=DNA+LZ&HUFFMAN", true);
         printOut("        3=TEXT+UTF+PACK+MM+LZX&HUFFMAN", true);
         printOut("        4=TEXT+UTF+EXE+PACK+MM+ROLZ&NONE", true);
         printOut("        5=TEXT+UTF+BWT+RANK+ZRLT&ANS0", true);
         printOut("        6=TEXT+UTF+BWT+SRT+ZRLT&FPAQ", true);
         printOut("        7=LZP+TEXT+UTF+BWT+LZP&CM", true);
         printOut("        8=EXE+RLT+TEXT+UTF+DNA&TPAQ", true);
         printOut("        9=EXE+RLT+TEXT+UTF+DNA&TPAQX\n", true);
         printOut("   -e, --entropy=<codec>", true);
         printOut("        Entropy codec [None|Huffman|ANS0|ANS1|Range|FPAQ|TPAQ|TPAQX|CM]", true);
         printOut("        (default is ANS0)\n", true);
         printOut("   -t, --transform=<codec>", true);
         printOut("        Transform [None|BWT|BWTS|LZ|LZX|LZP|ROLZ|ROLZX|RLT|ZRLT]", true);
         printOut("                  [MTFT|RANK|SRT|TEXT|MM|EXE|UTF|PACK]", true);
         printOut("        EG: BWT+RANK or BWTS+MTFT (default is BWT+RANK+ZRLT)\n", true);
         printOut("   -x, -x32, -x64, --checksum=<size>", true);
         printOut("        Enable block checksum (32 or 64 bits).", true);
         printOut("        -x is equivalent to -x32\n", true);
         printOut("   -s, --skip", true);
         printOut("        Copy blocks with high entropy instead of compressing them.\n", true);
      }

      printOut("   -j, --jobs=<jobs>", true);
      printOut("        Maximum number of jobs the program may start concurrently", true);
      printOut("        If 0 is provided, use all available cores (maximum is 64).", true);
      printOut("        (default is half of available cores).\n", true);
      printOut("   -v, --verbose=<level>", true);
      printOut("        0=silent, 1=default, 2=display details, 3=display configuration,", true);
      printOut("        4=display block size and timings, 5=display extra information", true);
      printOut("        Verbosity is reduced to 1 when files are processed concurrently", true);
      printOut("        Verbosity is reduced to 0 when the output is 'stdout'\n", true);
      printOut("   -f, --force", true);
      printOut("        Overwrite the output file if it already exists\n", true);
      printOut("   --rm", true);
      printOut("        Remove the input file after successful (de)compression.", true);
      printOut("        If the input is a folder, all processed files under the folder are removed.\n", true);
      printOut("   --no-link", true);
      printOut("        Skip links\n", true);
      printOut("   --no-dot-file", true);
      printOut("        Skip dot files\n", true);

      if (mode == 'd')
      {
         printOut("   --from=blockID", true);
         printOut("        Decompress starting at the provided block (included).", true);
         printOut("        The first block ID is 1.\n", true);
         printOut("   --to=blockID", true);
         printOut("        Decompress ending at the provided block (excluded).\n", true);
         printOut("", true);
         printOut("EG. java -jar kanzi.jar -d -i foo.knz -f -v 2 -j 2\n", true);
         printOut("EG. java -jar kanzi.jar --decompress --input=foo.knz --force --verbose=2 --jobs=2\n", true);
      }

      if (mode == 'c')
      {
         printOut("", true);
         printOut("EG. java -jar kanzi.jar -c -i foo.txt -o none -b 4m -l 4 -v 3\n", true);
         printOut("EG. java -jar kanzi.jar -c -i foo.txt -f -t BWT+MTFT+ZRLT -b 4m -e FPAQ -j 4\n", true);
         printOut("EG. java -jar kanzi.jar --compress --input=foo.txt --force --jobs=4", true);
         printOut("    --output=foo.knz --transform=BWT+MTFT+ZRLT --block=4m --entropy=FPAQ\n", true);
      }
    }


    /**
     * Prints a warning message to the console.
     *
     * @param val the value associated with the warning
     * @param reason the reason for the warning
     * @param verbose verbosity level controlling the output
     */
    private static void printWarning(String val, String reason, int verbose)
    {
         String msg = String.format("Warning: Ignoring option [%s] %s", val, reason);
         printOut(msg, verbose>0);
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
}

