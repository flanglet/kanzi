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

package io.github.flanglet.kanzi.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


public class TestFileTaskCleanup {
  @Test
  void testFileTasksRoundTrip(@TempDir Path tempDir) throws Exception {
    byte[] input = new byte[65536];

    for (int i = 0; i < input.length; i++)
      input[i] = (byte) i;

    Path source = Files.write(tempDir.resolve("input.bin"), input);
    Path compressed = tempDir.resolve("output.knz");
    HashMap<String, Object> compressionCtx = createContext(source, compressed);
    compressionCtx.put("transform", "NONE");
    compressionCtx.put("entropy", "NONE");
    compressionCtx.put("blockSize", 1024);
    BlockCompressor.FileCompressTask compressionTask =
        new BlockCompressor.FileCompressTask(compressionCtx, new ArrayList<>());
    Assertions.assertEquals(0, compressionTask.call().code);

    Path restored = tempDir.resolve("restored.bin");
    HashMap<String, Object> decompressionCtx = createContext(compressed, restored);
    BlockDecompressor.FileDecompressTask decompressionTask =
        new BlockDecompressor.FileDecompressTask(decompressionCtx, new ArrayList<>());
    Assertions.assertEquals(0, decompressionTask.call().code);
    Assertions.assertArrayEquals(input, Files.readAllBytes(restored));
  }

  @Test
  void testCompressorDoesNotCreateOutputWhenInputOpenFails(@TempDir Path tempDir)
      throws Exception {
    Path output = tempDir.resolve("output.knz");
    HashMap<String, Object> ctx = createContext(tempDir.resolve("missing.bin"), output);
    BlockCompressor.FileCompressTask task =
        new BlockCompressor.FileCompressTask(ctx, new ArrayList<>());

    Assertions.assertNotEquals(0, task.call().code);
    Assertions.assertFalse(Files.exists(output));
  }

  @Test
  void testCompressorRemovesNewOutputWhenInitializationFails(@TempDir Path tempDir)
      throws Exception {
    Path input = Files.write(tempDir.resolve("input.bin"), new byte[] {1, 2, 3});
    Path output = tempDir.resolve("output.knz");
    HashMap<String, Object> ctx = createContext(input, output);
    ctx.put("transform", "NONE");
    ctx.put("entropy", "NONE");
    ctx.put("blockSize", 1);
    BlockCompressor.FileCompressTask task =
        new BlockCompressor.FileCompressTask(ctx, new ArrayList<>());

    Assertions.assertNotEquals(0, task.call().code);
    Assertions.assertFalse(Files.exists(output));
  }

  @Test
  void testDecompressorDoesNotCreateOutputWhenInputOpenFails(@TempDir Path tempDir)
      throws Exception {
    Path output = tempDir.resolve("output.bin");
    HashMap<String, Object> ctx = createContext(tempDir.resolve("missing.knz"), output);
    BlockDecompressor.FileDecompressTask task =
        new BlockDecompressor.FileDecompressTask(ctx, new ArrayList<>());

    Assertions.assertNotEquals(0, task.call().code);
    Assertions.assertFalse(Files.exists(output));
  }

  @Test
  void testDecompressorRemovesNewOutputWhenDecodingFails(@TempDir Path tempDir)
      throws Exception {
    Path input = Files.write(tempDir.resolve("invalid.knz"), new byte[] {1, 2, 3});
    Path output = tempDir.resolve("output.bin");
    HashMap<String, Object> ctx = createContext(input, output);
    BlockDecompressor.FileDecompressTask task =
        new BlockDecompressor.FileDecompressTask(ctx, new ArrayList<>());

    Assertions.assertNotEquals(0, task.call().code);
    Assertions.assertFalse(Files.exists(output));
  }

  private static HashMap<String, Object> createContext(Path input, Path output) {
    HashMap<String, Object> ctx = new HashMap<>();
    ctx.put("inputName", input.toString());
    ctx.put("outputName", output.toString());
    ctx.put("verbosity", 0);
    ctx.put("overwrite", false);
    ctx.put("remove", false);
    ctx.put("jobs", 1);
    return ctx;
  }
}
