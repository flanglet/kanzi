/*                                                                                                                                       Copyright 2011-2024 Frederic Langlet
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

package io.github.flanglet.kanzi.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;


public class IOUtil
{
    public static void createFileList(String target, List<Path> files, boolean isRecursive,
       boolean ignoreLinks, boolean ignoreDotFiles) throws IOException
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
          if ((ignoreLinks == false) ||(Files.isSymbolicLink(root) == false))
              files.add(root);

          return;
       }

       // If not a regular file and not a directory (a link ?), fail
       if (Files.isDirectory(root) == false)
          throw new IOException("Invalid file type '"+root+"'");

       if (ignoreDotFiles == true)
       {
          String name = root.toString();
          int idx = name.lastIndexOf(File.separator);

          if (idx > 0)
          {
             name = name.substring(idx+1);

             if (name.charAt(0) == '.')
                return;
          }
       }

       try (DirectoryStream<Path> stream = Files.newDirectoryStream(root))
       {
          for (Path entry: stream)
          {
             if (Files.exists(entry) == false)
                continue;

             if (Files.isRegularFile(entry) == true)
             {
                if (ignoreDotFiles == true)
                {
                   String name = entry.toString();
                   int idx = name.lastIndexOf(File.separator);

                   if (idx > 0)
                   {
                      name = name.substring(idx+1);

                      if (name.charAt(0) == '.')
                         continue;
                   }
                }

                if ((ignoreLinks == false) ||(Files.isSymbolicLink(entry) == false))
                   files.add(entry);
             }
             else if ((isRecursive == true) && (Files.isDirectory(entry) == true))
             {
                createFileList(entry.toString(), files, isRecursive, ignoreLinks, ignoreDotFiles);
             }
          }
       }
       catch (DirectoryIteratorException e)
       {
         throw e.getCause();
       }
    }
}
