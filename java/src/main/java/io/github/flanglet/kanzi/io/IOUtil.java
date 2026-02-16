/*
 * Kanzi is a modern, modular, portable, and efficient lossless data compressor.
 *
 * Copyright (C) 2026 Frederic Langlet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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


/**
 * Utility class for performing I/O operations related to file management.
 */
public class IOUtil {

  /**
   * Creates a list of files from the specified target path. The method can traverse directories
   * recursively and can ignore symbolic links and dot files based on the provided flags.
   *
   * @param target the target path from which to list files
   * @param files the list to populate with found file paths
   * @param isRecursive flag indicating whether to search directories recursively
   * @param ignoreLinks flag indicating whether to ignore symbolic links
   * @param ignoreDotFiles flag indicating whether to ignore dot files (files starting with a dot)
   * @throws IOException if an I/O error occurs or the target path is invalid
   */
  public static void createFileList(String target, List<Path> files, boolean isRecursive,
      boolean ignoreLinks, boolean ignoreDotFiles) throws IOException {
    if (target == null)
      return;

    Path root = Paths.get(target);

    if (!Files.exists(root))
      throw new IOException("Cannot access input file '" + root + "'");

    if (Files.isRegularFile(root) && Files.isHidden(root))
      throw new IOException("Cannot access input file '" + root + "'");

    if (Files.isRegularFile(root)) {
      if (!ignoreLinks || !Files.isSymbolicLink(root))
        files.add(root);
      return;
    }

    // If not a regular file and not a directory (possibly a link?), fail
    if (!Files.isDirectory(root))
      throw new IOException("Invalid file type '" + root + "'");

    if (ignoreDotFiles) {
      String name = root.toString();
      int idx = name.lastIndexOf(File.separator);

      if (idx > 0) {
        name = name.substring(idx + 1);
        if (name.charAt(0) == '.')
          return;
      }
    }

    try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
      for (Path entry : stream) {
        if (!Files.exists(entry))
          continue;

        if (Files.isRegularFile(entry)) {
          if (ignoreDotFiles) {
            String name = entry.toString();
            int idx = name.lastIndexOf(File.separator);

            if (idx > 0) {
              name = name.substring(idx + 1);
              if (name.charAt(0) == '.')
                continue;
            }
          }

          if (!ignoreLinks || !Files.isSymbolicLink(entry))
            files.add(entry);
        } else if (isRecursive && Files.isDirectory(entry)) {
          createFileList(entry.toString(), files, isRecursive, ignoreLinks, ignoreDotFiles);
        }
      }
    } catch (DirectoryIteratorException e) {
      throw e.getCause();
    }
  }
}

