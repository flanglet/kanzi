/*
 * Kanzi is a modern, modular, portable, and efficient lossless data compressor.
 *
 * Copyright (C) 2026 Frederic Langlet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
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
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import io.github.flanglet.kanzi.Global;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


public class TestGlobal {

  @Test
  void testSortFilesByPathAndSize(@TempDir Path tempDir) throws IOException {
    Path small = tempDir.resolve("small");
    Path large = tempDir.resolve("large");

    try (RandomAccessFile file = new RandomAccessFile(small.toFile(), "rw")) {
      file.setLength(1);
    }

    try (RandomAccessFile file = new RandomAccessFile(large.toFile(), "rw")) {
      file.setLength((long) Integer.MAX_VALUE + 2);
    }

    List<Path> files = new ArrayList<>(Arrays.asList(small, large));
    Global.sortFilesByPathAndSize(files, true);
    Assertions.assertEquals(large, files.get(0));
    Assertions.assertEquals(small, files.get(1));

    Path parentless = Path.of("parentless");
    files = new ArrayList<>(Arrays.asList(small, parentless));
    Global.sortFilesByPathAndSize(files, true);
    Assertions.assertEquals(parentless, files.get(0));
    Assertions.assertEquals(small, files.get(1));
  }
}
