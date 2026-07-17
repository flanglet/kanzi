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

package io.github.flanglet.kanzi.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import io.github.flanglet.kanzi.io.IOUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


public class TestIOUtil {

  @Test
  void testDirectoryLinksAndCycles(@TempDir Path tempDir) throws IOException {
    Path realDirectory = Files.createDirectory(tempDir.resolve("real"));
    Path payload = Files.write(realDirectory.resolve("payload"), new byte[] {1});
    Path alias = tempDir.resolve("alias");
    Path cycle = realDirectory.resolve("back");

    try {
      Files.createSymbolicLink(alias, Path.of("real"));
      Files.createSymbolicLink(cycle, Path.of(".."));
    } catch (UnsupportedOperationException | SecurityException | IOException e) {
      Assumptions.assumeTrue(false, "Symbolic links are not available: " + e.getMessage());
      return;
    }

    List<Path> files = new ArrayList<>();
    IOUtil.createFileList(tempDir.toString(), files, true, true, false);
    Assertions.assertEquals(List.of(payload), files);

    files.clear();
    IOUtil.createFileList(alias.toString(), files, true, true, false);
    Assertions.assertTrue(files.isEmpty());

    IOUtil.createFileList(tempDir.toString(), files, true, false, false);
    Assertions.assertEquals(1, files.size());
    Assertions.assertTrue(Files.isSameFile(payload, files.get(0)));
  }
}
