/*
 * Kanzi is a modern, modular, portable, and efficient lossless data compressor.
 *
 * Copyright (C) 2025 Frederic Langlet
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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.github.flanglet.kanzi.io.CompressedInputStream;
import io.github.flanglet.kanzi.io.CompressedOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;


public class TestCompressedStream {
  private static final ExecutorService pool = Executors.newFixedThreadPool(4);
  private final static Random RANDOM = new Random(Long.MAX_VALUE);

  @AfterAll
  static void shutDown() {
    pool.shutdown();
  }

  @Test
  void testCorrectness() {
    // Test correctness (byte aligned)
    byte[] values = new byte[65536 << 6];
    byte[] incompressible = new byte[65536 << 6];

    for (int test = 1; test <= 40; test++) {
      final int length = 65536 << (test % 7);

      for (int i = 0; i < length; i++) {
        values[i] = (byte) RANDOM.nextInt(4 * test + 1);
        incompressible[i] = (byte) (RANDOM.nextInt());
      }

      System.out.println("\nIteration " + test + " (size " + length + ")");

      Assertions.assertEquals(0, compress1(values, length),
          "Method `compress1()` failed on test=" + test);
      Assertions.assertEquals(0, compress2(values, length),
          "Method `compress2()` failed on test=" + test);
      Assertions.assertEquals(0, compress3(incompressible, length),
          "Method `compress3()` failed on test=" + test);

      // Test for IOExceptions due to R/W after close only one time
      if (test == 1) {
        Assertions.assertThrows(IOException.class, new Executable() {
          @Override
          public void execute() throws Throwable {
            compress4(values, length);
          }
        }, "Method `compress4()` failed on test=" + test);
        Assertions.assertThrows(IOException.class, new Executable() {
          @Override
          public void execute() throws Throwable {
            compress5(values, length);
          }
        }, "Method `compress5()` failed on test=" + test);
      }
    }
  }

  public static long compress1(byte[] block, int length) {
    try {
      System.out.println("Test - regular");
      final int blockSize = (length / (1 + RANDOM.nextInt(3))) & -16;
      byte[] buf = new byte[length];
      System.arraycopy(block, 0, buf, 0, length);
      ByteArrayOutputStream baos = new ByteArrayOutputStream(2 * block.length);
      OutputStream os = new BufferedOutputStream(baos);
      HashMap<String, Object> ctx1 = new HashMap<>();
      ctx1.put("transform", "NONE");
      ctx1.put("entropy", "HUFFMAN");
      ctx1.put("blockSize", blockSize);
      ctx1.put("checksum", 0);
      CompressedOutputStream cos = new CompressedOutputStream(os, ctx1);
      cos.write(block, 0, length);
      cos.close();
      os.close();
      long written = cos.getWritten();

      byte[] output = baos.toByteArray();
      ByteArrayInputStream bais = new ByteArrayInputStream(output);
      InputStream is = new BufferedInputStream(bais);
      HashMap<String, Object> ctx2 = new HashMap<>();
      CompressedInputStream cis = new CompressedInputStream(is, ctx2);

      for (int i = 0; i < length; i++)
        block[i] = 0;

      while (cis.read(block, 0, length) == length) {
      }

      cis.close();
      is.close();
      long read = cis.getRead();
      int res = check(block, buf, length);

      return (res == 0) ? read ^ written : res;
    } catch (IOException e) {
      throw new RuntimeException("NONE&HUFFMAN", e);
    }
  }

  public static long compress2(byte[] block, int length) {
    try {
      final int blockSize = (length / (1 + RANDOM.nextInt(3))) & -16;
      int checksum = RANDOM.nextInt(3) * 32;
      int jobs = 1 + RANDOM.nextInt(4);
      System.out.println("Test - " + jobs + " job(s) " + ((checksum == 0) ? "checksum" : ""));
      byte[] buf = new byte[length];
      System.arraycopy(block, 0, buf, 0, length);
      ByteArrayOutputStream baos = new ByteArrayOutputStream(2 * block.length);
      OutputStream os = new BufferedOutputStream(baos);
      HashMap<String, Object> ctx1 = new HashMap<>();
      ctx1.put("transform", "LZX");
      ctx1.put("entropy", "FPAQ");
      ctx1.put("blockSize", blockSize);
      ctx1.put("checksum", checksum);
      ctx1.put("pool", pool);
      ctx1.put("jobs", jobs);
      CompressedOutputStream cos = new CompressedOutputStream(os, ctx1);
      cos.write(block, 0, length);
      cos.close();
      os.close();
      long written = cos.getWritten();

      byte[] output = baos.toByteArray();
      ByteArrayInputStream bais = new ByteArrayInputStream(output);
      InputStream is = new BufferedInputStream(bais);
      HashMap<String, Object> ctx2 = new HashMap<>();
      ctx2.put("pool", pool);
      ctx2.put("jobs", jobs);
      CompressedInputStream cis = new CompressedInputStream(is, ctx2);

      for (int i = 0; i < length; i++)
        block[i] = 0;

      while (cis.read(block, 0, length) == length) {
      }

      cis.close();
      is.close();
      long read = cis.getRead();

      int res = check(block, buf, length);

      if (res != 0)
        return res;

      return read ^ written;
    } catch (IOException e) {
      throw new RuntimeException("LZX&FPAQ", e);
    }
  }

  public static long compress3(byte[] block, int length) {
    try {
      System.out.println("Test - incompressible data");
      final int blockSize = (length / (1 + RANDOM.nextInt(3))) & -16;
      byte[] buf = new byte[length];
      System.arraycopy(block, 0, buf, 0, length);
      ByteArrayOutputStream baos = new ByteArrayOutputStream(2 * block.length);
      OutputStream os = new BufferedOutputStream(baos);
      HashMap<String, Object> ctx1 = new HashMap<>();
      ctx1.put("transform", "LZP+ZRLT");
      ctx1.put("entropy", "ANS0");
      ctx1.put("blockSize", blockSize);
      ctx1.put("checksum", 0);
      ctx1.put("pool", pool);
      ctx1.put("jobs", 1);
      CompressedOutputStream cos = new CompressedOutputStream(os, ctx1);
      cos.write(block, 0, length);
      cos.close();
      os.close();
      long written = cos.getWritten();

      byte[] output = baos.toByteArray();
      ByteArrayInputStream bais = new ByteArrayInputStream(output);
      InputStream is = new BufferedInputStream(bais);
      HashMap<String, Object> ctx2 = new HashMap<>();
      CompressedInputStream cis = new CompressedInputStream(is, ctx2);

      for (int i = 0; i < length; i++)
        block[i] = 0;

      while (cis.read(block, 0, length) == length) {
      }

      cis.close();
      is.close();
      long read = cis.getRead();

      int res = check(block, buf, length);

      if (res != 0)
        return res;

      return read ^ written;
    } catch (IOException e) {
      throw new RuntimeException("LZP+ZRLT&ANS0", e);
    }
  }

  // expected to throw an exception due to WRITE after CLOSE
  void compress4(byte[] block, int length) throws IOException {
    System.out.println("Test - write after close");
    ByteArrayOutputStream baos = new ByteArrayOutputStream(2 * block.length);
    OutputStream os = new BufferedOutputStream(baos);
    HashMap<String, Object> ctx1 = new HashMap<>();
    ctx1.put("transform", "NONE");
    ctx1.put("entropy", "HUFFMAN");
    ctx1.put("blockSize", length);
    ctx1.put("checksum", 0);
    ctx1.put("pool", pool);
    ctx1.put("jobs", 1);
    CompressedOutputStream cos = new CompressedOutputStream(os, ctx1);
    cos.write(block, 0, length);
    cos.close();

    // Write after close should throw IOException
    cos.write(123);
    os.close();
  }

  // expected to throw an exception due to READ after CLOSE
  void compress5(byte[] block, int length) throws IOException {
    System.out.println("Test - read after close");
    ByteArrayOutputStream baos = new ByteArrayOutputStream(2 * block.length);
    OutputStream os = new BufferedOutputStream(baos);
    HashMap<String, Object> ctx1 = new HashMap<>();
    ctx1.put("transform", "NONE");
    ctx1.put("entropy", "HUFFMAN");
    ctx1.put("blockSize", 4 * 1024 * 1024);
    ctx1.put("checksum", 0);
    ctx1.put("pool", pool);
    ctx1.put("jobs", 1);
    CompressedOutputStream cos = new CompressedOutputStream(os, ctx1);
    cos.write(block, 0, length);
    cos.close();
    os.close();

    byte[] output = baos.toByteArray();
    ByteArrayInputStream bais = new ByteArrayInputStream(output);
    InputStream is = new BufferedInputStream(bais);
    HashMap<String, Object> ctx2 = new HashMap<>();
    CompressedInputStream cis = new CompressedInputStream(is, ctx2);

    while (cis.read(block, 0, length) == length) {
    }
    cis.close();

    // Read after close should throw IOException
    cis.read();
    is.close();
  }


  private static int check(byte[] data1, byte[] data2, int length) {
    for (int i = 0; i < length; i++)
      if (data1[i] != data2[i])
        return 3;

    return 0;
  }
}
