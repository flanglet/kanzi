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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.github.flanglet.kanzi.Error;
import io.github.flanglet.kanzi.app.BlockDecompressor;
import io.github.flanglet.kanzi.bitstream.DefaultInputBitStream;
import io.github.flanglet.kanzi.io.CompressedInputStream;
import io.github.flanglet.kanzi.io.CompressedOutputStream;
import io.github.flanglet.kanzi.io.KanziIOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;


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

  @Test
  void testBulkReadEndOfStream() throws IOException {
    byte[] input = new byte[] {1, 2, 3};
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    HashMap<String, Object> ctx = new HashMap<>();
    ctx.put("transform", "NONE");
    ctx.put("entropy", "NONE");
    ctx.put("blockSize", 1024);

    try (CompressedOutputStream cos = new CompressedOutputStream(baos, ctx)) {
      cos.write(input);
    }

    try (CompressedInputStream cis =
        new CompressedInputStream(new ByteArrayInputStream(baos.toByteArray()), new HashMap<>())) {
      byte[] output = new byte[8];
      Assertions.assertEquals(input.length, cis.read(output));
      Assertions.assertArrayEquals(input, Arrays.copyOf(output, input.length));
      Assertions.assertEquals(-1, cis.read(output));
      Assertions.assertEquals(-1, cis.read(output));
      Assertions.assertEquals(0, cis.read(output, 0, 0));
    }
  }

  @Test
  void testReadAfterCloseErrorCode() throws IOException {
    CompressedInputStream cis =
        new CompressedInputStream(new ByteArrayInputStream(new byte[0]), new HashMap<>());
    cis.close();
    KanziIOException e = Assertions.assertThrows(KanziIOException.class, cis::read);
    Assertions.assertEquals(Error.ERR_READ_FILE, e.getErrorCode());
    e = Assertions.assertThrows(KanziIOException.class, () -> cis.read(new byte[1]));
    Assertions.assertEquals(Error.ERR_READ_FILE, e.getErrorCode());
  }

  @Test
  void testKanziIOExceptionRetainsCause() {
    IOException cause = new IOException("Source failure");
    KanziIOException e = new KanziIOException("Wrapped failure", cause, Error.ERR_READ_FILE);
    Assertions.assertEquals("Wrapped failure", e.getMessage());
    Assertions.assertEquals(Error.ERR_READ_FILE, e.getErrorCode());
    Assertions.assertSame(cause, e.getCause());
  }

  @Test
  void testBlockDecompressorAcceptsEndOfStream(@TempDir Path tempDir) throws IOException {
    byte[] input = new byte[65536];

    for (int i = 0; i < input.length; i++)
      input[i] = (byte) i;

    Path compressed = tempDir.resolve("input.knz");
    Path output = tempDir.resolve("output.bin");
    HashMap<String, Object> compressionCtx = new HashMap<>();
    compressionCtx.put("transform", "NONE");
    compressionCtx.put("entropy", "NONE");
    compressionCtx.put("blockSize", 1024);

    try (CompressedOutputStream cos =
        new CompressedOutputStream(Files.newOutputStream(compressed), compressionCtx)) {
      cos.write(input);
    }

    HashMap<String, Object> decompressionCtx = new HashMap<>();
    decompressionCtx.put("inputName", compressed.toString());
    decompressionCtx.put("outputName", output.toString());
    decompressionCtx.put("verbose", 0);
    decompressionCtx.put("jobs", 1);
    BlockDecompressor decompressor = new BlockDecompressor(decompressionCtx);

    try {
      Assertions.assertEquals(0, decompressor.call());
    } finally {
      decompressor.dispose();
    }

    Assertions.assertArrayEquals(input, Files.readAllBytes(output));
  }

  @Test
  void testBlockHeaderChecksumCheckedBeforePayloadRead() throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    HashMap<String, Object> ctx = new HashMap<>();
    ctx.put("transform", "NONE");
    ctx.put("entropy", "NONE");
    ctx.put("blockSize", 1024);

    try (CompressedOutputStream cos = new CompressedOutputStream(baos, ctx)) {
      cos.write(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
    }

    byte[] encoded = baos.toByteArray();
    long headerChecksumOffset;
    DefaultInputBitStream ibs = new DefaultInputBitStream(new ByteArrayInputStream(encoded), 1024);

    try {
      ibs.readBits(32); // Stream type
      ibs.readBits(4); // Bitstream version
      ibs.readBits(2); // Block checksum size
      ibs.readBits(5); // Entropy codec
      ibs.readBits(48); // Transform
      ibs.readBits(28); // Block size
      final int sizeMask = (int) ibs.readBits(2);

      if (sizeMask != 0)
        ibs.readBits(16 * sizeMask);

      ibs.readBits(15); // Padding
      ibs.readBits(24); // Stream header checksum
      final int lr = (int) ibs.readBits(5) + 3;
      ibs.readBits(lr); // Encoded block length
      final int mode = (int) ibs.readBits(8);
      Assertions.assertNotEquals(0, mode & 0x80); // Small blocks are copied
      final int dataSize = 1 + ((mode >> 5) & 0x03);
      ibs.readBits(dataSize << 3);
      headerChecksumOffset = ibs.read();
    } finally {
      ibs.close();
    }

    final int checksumByte = (int) (headerChecksumOffset >> 3);
    final int checksumBit = 7 - ((int) headerChecksumOffset & 7);
    encoded[checksumByte] ^= 1 << checksumBit;
    encoded = Arrays.copyOf(encoded, (int) ((headerChecksumOffset + 15) >> 3));

    try (CompressedInputStream cis =
        new CompressedInputStream(new ByteArrayInputStream(encoded), new HashMap<>())) {
      KanziIOException e =
          Assertions.assertThrows(KanziIOException.class, () -> cis.read(new byte[16]));
      Assertions.assertEquals(Error.ERR_CRC_CHECK, e.getErrorCode());
    }
  }

  @Test
  void testEncodedBlockLengthBoundCheckedBeforePayloadRead() throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    HashMap<String, Object> ctx = new HashMap<>();
    ctx.put("transform", "NONE");
    ctx.put("entropy", "NONE");
    ctx.put("blockSize", 1024);

    try (CompressedOutputStream cos = new CompressedOutputStream(baos, ctx)) {
      cos.write(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
    }

    byte[] encoded = baos.toByteArray();
    long encodedLengthOffset;
    long encodedBlockLength;
    long headerChecksumOffset;
    int lr;
    int mode;
    int preTransformLength;
    DefaultInputBitStream ibs = new DefaultInputBitStream(new ByteArrayInputStream(encoded), 1024);

    try {
      ibs.readBits(32); // Stream type
      ibs.readBits(4); // Bitstream version
      ibs.readBits(2); // Block checksum size
      ibs.readBits(5); // Entropy codec
      ibs.readBits(48); // Transform
      ibs.readBits(28); // Block size
      final int sizeMask = (int) ibs.readBits(2);

      if (sizeMask != 0)
        ibs.readBits(16 * sizeMask);

      ibs.readBits(15); // Padding
      ibs.readBits(24); // Stream header checksum
      lr = (int) ibs.readBits(5) + 3;
      encodedLengthOffset = ibs.read();
      encodedBlockLength = ibs.readBits(lr);
      mode = (int) ibs.readBits(8);
      Assertions.assertNotEquals(0, mode & 0x80); // Small blocks are copied
      final int dataSize = 1 + ((mode >> 5) & 0x03);
      preTransformLength = (int) ibs.readBits(dataSize << 3);
      headerChecksumOffset = ibs.read();
    } finally {
      ibs.close();
    }

    final long oversizedBlockLength = encodedBlockLength + 8;
    Assertions.assertTrue(oversizedBlockLength < (1L << lr));
    writeBits(encoded, encodedLengthOffset, oversizedBlockLength, lr);
    int checksum = computeBlockHeaderChecksum(mode, 0, preTransformLength, oversizedBlockLength);
    writeBits(encoded, headerChecksumOffset, checksum & 0xFF, 8);
    encoded = Arrays.copyOf(encoded, (int) ((headerChecksumOffset + 15) >> 3));

    try (CompressedInputStream cis =
        new CompressedInputStream(new ByteArrayInputStream(encoded), new HashMap<>())) {
      KanziIOException e =
          Assertions.assertThrows(KanziIOException.class, () -> cis.read(new byte[16]));
      Assertions.assertEquals(Error.ERR_BLOCK_SIZE, e.getErrorCode());
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

  private static int computeBlockHeaderChecksum(int mode, int skipFlags, int length,
      long encodedBlockLength) {
    final int hash = 0x1E35A7BD;
    int checksum = hash * 0x01030507;
    checksum = mix32(checksum, hash, mode);
    checksum = mix32(checksum, hash, skipFlags);
    checksum = mix32(checksum, hash, length);
    checksum = mix32(checksum, hash, (int) (encodedBlockLength >>> 32));
    checksum = mix32(checksum, hash, (int) encodedBlockLength);
    return (checksum >>> 23) ^ (checksum >>> 3);
  }

  private static int mix32(int checksum, int hash, int value) {
    checksum ^= hash * ~value;
    checksum = Integer.rotateLeft(checksum, 13);
    return checksum * 5 + 0x52DCE729;
  }

  private static void writeBits(byte[] data, long offset, long value, int length) {
    for (int i = 0; i < length; i++) {
      final int index = (int) offset + i;
      final int mask = 1 << (7 - (index & 7));

      if (((value >>> (length - 1 - i)) & 1) == 0)
        data[index >> 3] &= ~mask;
      else
        data[index >> 3] |= mask;
    }
  }


  private static int check(byte[] data1, byte[] data2, int length) {
    for (int i = 0; i < length; i++)
      if (data1[i] != data2[i])
        return 3;

    return 0;
  }
}
