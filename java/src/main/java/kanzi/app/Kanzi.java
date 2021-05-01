/*
Copyright 2011-2021 Frederic Langlet
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
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;



public class Kanzi
{
   private static final String[] CMD_LINE_ARGS = new String[]
   {
      "-c", "-d", "-i", "-o", "-b", "-t", "-e", "-j", "-v", "-l", "-s", "-x", "-f", "-h"
   };

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
   // private static final int ARG_IDX_FROM = 10;
   //private static final int ARG_IDX_TO = 11;

   private static final String APP_HEADER = "Kanzi 1.9 (C) 2021,  Frederic Langlet";


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
            System.exit(kanzi.Error.ERR_CREATE_COMPRESSOR);
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
            code = kanzi.Error.ERR_WRITE_FILE;
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
            System.exit(kanzi.Error.ERR_CREATE_DECOMPRESSOR);
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
            code = kanzi.Error.ERR_WRITE_FILE;
         }

         System.exit(code);
      }

      System.out.println("Missing arguments: try --help or -h");
      System.exit(1);
   }


    private static int processCommandLine(String args[], Map<String, Object> map)
    {
        int blockSize = -1;
        int verbose = 1;
        boolean overwrite = false;
        boolean checksum = false;
        boolean skip = false;
        String inputName = null;
        String outputName = null;
        String codec = null;
        String transform = null;
        int from = -1;
        int to = -1;
        int tasks = 0;
        int ctx = -1;
        int level = -1;
        char mode = ' ';

        for (String arg : args)
        {
           arg = arg.trim();

           if (arg.equals("-o"))
           {
              ctx = ARG_IDX_OUTPUT;
              continue;
           }

           if (arg.equals("-v"))
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
                  return kanzi.Error.ERR_INVALID_PARAM;
              }

              mode = 'c';
              continue;
           }

           if (arg.equals("--decompress") || (arg.equals("-d")))
           {
              if (mode == 'c')
              {
                  System.err.println("Both compression and decompression options were provided.");
                  return kanzi.Error.ERR_INVALID_PARAM;
              }

              mode = 'd';
              continue;
           }

           if (arg.startsWith("--verbose=") || (ctx == ARG_IDX_VERBOSE))
           {
               String verboseLevel = arg.startsWith("--verbose=") ? arg.substring(10).trim() : arg;

               try
               {
                   verbose = Integer.parseInt(verboseLevel);

                   if ((verbose < 0) || (verbose > 5))
                      throw new NumberFormatException();
               }
               catch (NumberFormatException e)
               {
                  System.err.println("Invalid verbosity level provided on command line: "+arg);
                  return kanzi.Error.ERR_INVALID_PARAM;
               }
           }
           else if (arg.startsWith("--output=") || (ctx == ARG_IDX_OUTPUT))
           {
               outputName = arg.startsWith("--output=") ? arg.substring(9).trim() : arg;
           }

           ctx = -1;
        }

        // Overwrite verbosity if the output goes to stdout
        if ("STDOUT".equalsIgnoreCase(outputName))
           verbose = 0;

        if (verbose >= 1)
            printOut("\n" + APP_HEADER +"\n", true);

        outputName = null;
        ctx = -1;

        for (String arg : args)
        {
           arg = arg.trim();

           if (arg.equals("--help") || arg.equals("-h"))
           {
               printOut("", true);
               printOut("Credits: Matt Mahoney, Yann Collet, Jan Ondrus, Yuta Mori, Ilya Muravyov,", true);
               printOut("         Neal Burns, Fabian Giesen, Jarek Duda, Ilya Grebnov", true);
               printOut("", true);
               printOut("   -h, --help", true);
               printOut("        display this message\n", true);
               printOut("   -v, --verbose=<level>", true);
               printOut("        0=silent, 1=default, 2=display details, 3=display configuration,", true);
               printOut("        4=display block size and timings, 5=display extra information", true);
               printOut("        Verbosity is reduced to 1 when files are processed concurrently", true);
               printOut("        Verbosity is silently reduced to 0 when the output is 'stdout'", true);
               printOut("        (EG: The source is a directory and the number of jobs > 1).\n", true);
               printOut("   -f, --force", true);
               printOut("        overwrite the output file if it already exists\n", true);
               printOut("   -i, --input=<inputName>", true);
               printOut("        mandatory name of the input file or directory or 'stdin'", true);
               printOut("        When the source is a directory, all files in it will be processed.", true);
               printOut("        Provide " + File.separator + ". at the end of the directory name to avoid recursion", true);
               printOut("        (EG: myDir" + File.separator + ". => no recursion)\n", true);
               printOut("   -o, --output=<outputName>", true);

               if (mode == 'c')
               {
                  printOut("        optional name of the output file or directory (defaults to", true);
                  printOut("        <inputName.knz>) or 'none' or 'stdout'. 'stdout' is not valid", true);
                  printOut("        when the number of jobs is greater than 1.\n", true);
               }
               else if (mode == 'd')
               {
                  printOut("        optional name of the output file or directory (defaults to", true);
                  printOut("        <inputName.bak>) or 'none' or 'stdout'. 'stdout' is not valid", true);
                  printOut("        when the number of jobs is greater than 1.\n", true);
               }
               else
               {
                  printOut("        optional name of the output file or 'none' or 'stdout'.\n", true);
               }

               if (mode == 'c')
               {
                  printOut("   -b, --block=<size>", true);
                  printOut("        size of blocks (default 4 MB, max 1 GB, min 1 KB).\n", true);
                  printOut("   -l, --level=<compression>", true);
                  printOut("        set the compression level [0..9]", true);
                  printOut("        Providing this option forces entropy and transform.", true);
                  printOut("        0=None&None (store), 1=TEXT+LZ&HUFFMAN, 2=TEXT+FSD+LZX&HUFFMAN", true);
                  printOut("        3=TEXT+FSD+ROLZ, 4=TEXT+FSD+ROLZX, 5=TEXT+BWT+RANK+ZRLT&ANS0", true);
                  printOut("        6=TEXT+BWT+SRT+ZRLT&FPAQ, 7=LZP+TEXT+BWT+LZP&CM, 8=X86+RLT+TEXT&TPAQ", true);
                  printOut("        9=X86+RLT+TEXT&TPAQX\n", true);
                  printOut("   -e, --entropy=<codec>", true);
                  printOut("        entropy codec [None|Huffman|ANS0|ANS1|Range|FPAQ|TPAQ|TPAQX|CM]", true);
                  printOut("        (default is ANS0)\n", true);
                  printOut("   -t, --transform=<codec>", true);
                  printOut("        transform [None|BWT|BWTS|LZ|LZX|LZP|ROLZ|ROLZX|RLT|ZRLT]", true);
                  printOut("                  [MTFT|RANK|SRT|TEXT|X86]", true);
                  printOut("        EG: BWT+RANK or BWTS+MTFT (default is BWT+RANK+ZRLT)\n", true);
                  printOut("   -x, --checksum", true);
                  printOut("        enable block checksum\n", true);
                  printOut("   -s, --skip", true);
                  printOut("        copy blocks with high entropy instead of compressing them.\n", true);
               }

               printOut("   -j, --jobs=<jobs>", true);
               printOut("        maximum number of jobs the program may start concurrently", true);
               printOut("        (default is 1, maximum is 64).\n", true);

               if (mode == 'c')
               {
                  printOut("", true);
                  printOut("EG. java -cp kanzi.jar -c -i foo.txt -o none -b 4m -l 4 -v 3\n", true);
                  printOut("EG. java -cp kanzi.jar -c -i foo.txt -f ", true);
                  printOut("    -t BWT+MTFT+ZRLT -b 4m -e FPAQ -v 3 -j 4\n", true);
                  printOut("EG. java -cp kanzi.jar --compress --input=foo.txt --force ", true);
                  printOut("    --output=foo.knz --transform=BWT+MTFT+ZRLT --block=4m --entropy=FPAQ ", true);
                  printOut("    --verbose=3 --jobs=4\n", true);
               }

               if (mode == 'd')
               {
                  printOut("   --from=blockID", true);
                  printOut("        Decompress starting from the provided block (included).", true);
                  printOut("        The first block ID is 1.\n", true);
                  printOut("   --to=blockID", true);
                  printOut("        Decompress ending at the provided block (excluded).\n", true);
                  printOut("", true);
                  printOut("EG. java -cp kanzi.jar -d -i foo.knz -f -v 2 -j 2\n", true);
                  printOut("EG. java -cp kanzi.jar --decompress --input=foo.knz --force --verbose=2 --jobs=2\n", true);
               }

               return 0;
           }

           if (arg.equals("--compress") || arg.equals("-c") || arg.equals("--decompress") || arg.equals("-d"))
           {
               if (ctx != -1)
                  printOut("Warning: ignoring option [" + CMD_LINE_ARGS[ctx] + "] with no value.", verbose>0);

               ctx = -1;
               continue;
           }

           if (arg.equals("--force") || arg.equals("-f"))
           {
               if (ctx != -1)
                  printOut("Warning: ignoring option [" + CMD_LINE_ARGS[ctx] + "] with no value.", verbose>0);

               overwrite = true;
               ctx = -1;
               continue;
           }

           if (arg.equals("--skip") || arg.equals("-s"))
           {
               if (ctx != -1)
                  printOut("Warning: ignoring option [" + CMD_LINE_ARGS[ctx] + "] with no value.", verbose>0);

               skip = true;
               ctx = -1;
               continue;
           }

           if (arg.equals("--checksum") || arg.equals("-x"))
           {
               if (ctx != -1)
                  printOut("Warning: ignoring option [" + CMD_LINE_ARGS[ctx] + "] with no value.", verbose>0);

               checksum = true;
               ctx = -1;
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

               if (outputName != null)
                  System.err.println("Warning: ignoring duplicate output name: "+name);
               else
                  outputName = name;

               ctx = -1;
               continue;
           }

           if (arg.startsWith("--input=") || (ctx == ARG_IDX_INPUT))
           {
               String name = arg.startsWith("--input=") ? arg.substring(8).trim() : arg;

               if (inputName != null)
                  System.err.println("Warning: ignoring duplicate input name: "+name);
               else
                  inputName = name;

               ctx = -1;
               continue;
           }

           if (arg.startsWith("--entropy=") || (ctx == ARG_IDX_ENTROPY))
           {
               String name = arg.startsWith("--entropy=") ? arg.substring(10).trim().toUpperCase() :
                 arg.toUpperCase();

               if (codec != null)
                  System.err.println("Warning: ignoring duplicate entropy: "+name);
               else
                  codec = name;

               ctx = -1;
               continue;
           }

           if (arg.startsWith("--transform=") || (ctx == ARG_IDX_TRANSFORM))
           {
               String name = arg.startsWith("--transform=") ? arg.substring(12).trim().toUpperCase() :
                 arg.toUpperCase();

               if (transform != null)
                  System.err.println("Warning: ignoring duplicate transform: "+name);
               else
                  transform = name;

               while ((transform.length()>0) && (transform.charAt(0) == '+'))
                  transform = transform.substring(1);

               while ((transform.length()>0) && (transform.charAt(transform.length()-1) == '+'))
                  transform = transform.substring(0, transform.length()-1);

               ctx = -1;
               continue;
           }

           if (arg.startsWith("--level=") || (ctx == ARG_IDX_LEVEL))
           {
               String name = arg.startsWith("--level=") ? arg.substring(8).trim().toUpperCase() :
                 arg.toUpperCase();

               if (level != -1)
               {
                  System.err.println("Warning: ignoring duplicate level: "+name);
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
                  return kanzi.Error.ERR_INVALID_PARAM;
               }

               if ((level < 0) || (level > 9))
               {
                  System.err.println("Invalid compression level provided on command line: "+arg);
                  return kanzi.Error.ERR_INVALID_PARAM;
               }

               ctx = -1;
               continue;
           }

           if (arg.startsWith("--block=") || (ctx == ARG_IDX_BLOCK))
           {
               String name = arg.startsWith("--block=") ? arg.substring(8).toUpperCase().trim() :
                  arg.toUpperCase();

               if (blockSize != -1)
               {
                  System.err.println("Warning: ignoring duplicate block size: "+name);
                  ctx = -1;
                  continue;
               }

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
                 return kanzi.Error.ERR_INVALID_PARAM;
              }
           }

           if (arg.startsWith("--jobs=") || (ctx == ARG_IDX_JOBS))
           {
               String name = arg.startsWith("--jobs=") ? arg.substring(7).trim() : arg;

               if (tasks != 0)
               {
                  System.err.println("Warning: ignoring duplicate jobs: "+name);
                  ctx = -1;
                  continue;
               }

               try
               {
                  tasks = Integer.parseInt(name);

                  if (tasks < 1)
                     throw new NumberFormatException();

                  ctx = -1;
                  continue;
              }
              catch (NumberFormatException e)
              {
                  System.err.println("Invalid number of jobs provided on command line: "+arg);
                  return kanzi.Error.ERR_INVALID_PARAM;
              }
           }

           if (arg.startsWith("--from=") && (ctx == -1))
           {
               String name = arg.startsWith("--from=") ? arg.substring(7).trim() : arg;

               if (from != -1)
               {
                  System.err.println("Warning: ignoring duplicate start block: "+name);
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

                  return kanzi.Error.ERR_INVALID_PARAM;
              }
           }

           if (arg.startsWith("--to=") && (ctx == -1))
           {
               String name = arg.startsWith("--to=") ? arg.substring(5).trim() : arg;

               if (to != -1)
               {
                  System.err.println("Warning: ignoring duplicate end block: "+name);
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
                  return kanzi.Error.ERR_INVALID_PARAM;
              }
           }

           if (!arg.startsWith("--verbose=") && (ctx == -1) && !arg.startsWith("--output="))
           {
               printOut("Warning: ignoring unknown option ["+ arg + "]", verbose>0);
           }

           ctx = -1;
        }

        if (inputName == null)
        {
           System.err.println("Missing input file name, exiting ...");
           return kanzi.Error.ERR_MISSING_PARAM;
        }

        if (ctx != -1)
        {
           printOut("Warning: ignoring option with missing value ["+ CMD_LINE_ARGS[ctx] + "]", verbose>0);
        }

        if (level >= 0)
        {
           if (codec != null)
              printOut("Warning: providing the 'level' option forces the entropy codec. Ignoring ["+ codec + "]", verbose>0);

           if (transform != null)
              printOut("Warning: providing the 'level' option forces the transform. Ignoring ["+ transform + "]", verbose>0);
        }

        if ((from >= 0) || (to >= 0))
        {
           if (mode != 'd')
           {
               printOut("Warning: ignoring start/end block (only valid for decompression)", verbose>0);
               from = -1;
               to = -1;
           }
         }

        if (blockSize != -1)
           map.put("block", blockSize);

        map.put("verbose", verbose);
        map.put("mode", mode);

        if ((mode == 'c') || (level != -1))
           map.put("level", level);

        if (overwrite == true)
           map.put("overwrite", overwrite);

        map.put("inputName", inputName);
        map.put("outputName", outputName);

        if (codec != null)
           map.put("entropy", codec);

        if (transform != null)
           map.put("transform", transform);

        if (checksum == true)
           map.put("checksum", checksum);

        if (skip == true)
           map.put("skipBlocks", skip);

        if (from >= 0)
           map.put("from", from);

        if (to >= 0)
           map.put("to", to);

        map.put("jobs", tasks);
        return 0;
    }


    private static void printOut(String msg, boolean print)
    {
       if ((print == true) && (msg != null))
          System.out.println(msg);
    }


    public static void createFileList(String target, List<Path> files) throws IOException
    {
       if (target == null)
          return;

       Path root = Paths.get(target);

       if (Files.exists(root) == false)
          throw new IOException("Cannot access input file '"+root+"'");

       if ((Files.isRegularFile(root) == true) && (Files.isHidden(root) == true))
          throw new IOException("Cannot access input file '"+root+"'");

       if (Files.isRegularFile(root) == true)
       {
          if (target.charAt(0) != '.')
             files.add(root);

          return;
       }

       // If not a regular file and not a directory (a link ?), fail
       if (Files.isDirectory(root) == false)
          throw new IOException("Invalid file type '"+root+"'");

       String suffix = File.separator + ".";
       String strRoot = root.toString();
       boolean isRecursive = !strRoot.endsWith(suffix);

       if (isRecursive == true)
       {
          if (strRoot.endsWith(File.separator) == false)
             root = Paths.get(strRoot+File.separator);
       }
       else
       {
          // Remove suffix
          root = Paths.get(strRoot.substring(0, strRoot.length()-1));
       }

       try (DirectoryStream<Path> stream = Files.newDirectoryStream(root))
       {
          for (Path entry: stream)
          {
             if ((Files.exists(entry) == false) || (Files.isHidden(entry) == true))
                continue;

             if ((Files.isRegularFile(entry) == true) && (String.valueOf(entry.getFileName()).startsWith(".") == false))
                files.add(entry);
             else if ((isRecursive == true) && (Files.isDirectory(entry) == true))
                createFileList(entry.toString(), files);
          }
       }
       catch (DirectoryIteratorException e)
       {
         throw e.getCause();
       }
    }
}
