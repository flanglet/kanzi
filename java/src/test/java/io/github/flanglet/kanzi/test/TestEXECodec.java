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

import java.util.Arrays;
import io.github.flanglet.kanzi.SliceByteArray;
import io.github.flanglet.kanzi.Memory.LittleEndian;
import io.github.flanglet.kanzi.transform.EXECodec;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


public class TestEXECodec {
  private static void writeInt16LE(byte[] buf, int idx, int value) {
    buf[idx] = (byte) value;
    buf[idx + 1] = (byte) (value >> 8);
  }

  private static void writeInt32LE(byte[] buf, int idx, int value) {
    buf[idx] = (byte) value;
    buf[idx + 1] = (byte) (value >> 8);
    buf[idx + 2] = (byte) (value >> 16);
    buf[idx + 3] = (byte) (value >> 24);
  }

  private static byte[] createPEBlock(int arch) {
    final int size = 8192;
    final int codeStart = 512;
    final int codeLen = 4096;
    final int posPE = 0x80;
    byte[] data = new byte[size];
    Arrays.fill(data, (byte) 0x90);
    data[0] = 'M';
    data[1] = 'Z';
    writeInt32LE(data, 60, posPE);
    data[posPE] = 'P';
    data[posPE + 1] = 'E';
    data[posPE + 2] = 0;
    data[posPE + 3] = 0;
    writeInt16LE(data, posPE + 4, arch);
    writeInt32LE(data, posPE + 28, codeLen);
    writeInt32LE(data, posPE + 44, codeStart);
    return data;
  }

  private static void setPECodeLength(byte[] data, int codeLen) {
    writeInt32LE(data, 0x80 + 28, codeLen);
  }

  private static void fillX86Code(byte[] data, int codeStart, int codeLen) {
    for (int i = codeStart; i + 5 <= codeStart + codeLen; i += 5) {
      data[i] = (byte) 0xE8;
      data[i + 1] = 0;
      data[i + 2] = 0;
      data[i + 3] = 0;
      data[i + 4] = 0;
    }
  }

  private static void fillX86ExpandedCode(byte[] data, int codeStart, int codeLen) {
    for (int i = codeStart; i + 8 <= codeStart + codeLen; i += 8) {
      boolean escaped = ((i - codeStart) >> 3) < 24;
      data[i] = (byte) 0xE8;
      data[i + 1] = 0;
      data[i + 2] = 0;
      data[i + 3] = 0;
      data[i + 4] = 0;
      data[i + 5] = escaped ? (byte) 0x9B : (byte) 0x90;
      data[i + 6] = (byte) 0x90;
      data[i + 7] = (byte) 0x90;
    }
  }

  private static void addX86BoundaryJCC(byte[] data, int codeStart, int codeLen) {
    int idx = codeStart + codeLen - 5;
    data[idx] = (byte) 0x0F;
    data[idx + 1] = (byte) 0x85;
    data[idx + 2] = 0;
    data[idx + 3] = 0;
    data[idx + 4] = 0;
    data[idx + 5] = 0;
  }

  private static byte[] createBoundaryBlock() {
    byte[] data = createPEBlock(0x014C);
    final int codeStart = 512;
    final int codeLen = 85;
    setPECodeLength(data, codeLen);
    fillX86Code(data, codeStart, 16 * 5);
    addX86BoundaryJCC(data, codeStart, codeLen);
    return data;
  }

  private static byte[] roundTrip(byte[] data) {
    EXECodec codec = new EXECodec();
    byte[] encoded = new byte[codec.getMaxEncodedLength(data.length)];
    byte[] decoded = new byte[data.length];
    SliceByteArray input = new SliceByteArray(data, 0);
    SliceByteArray output = new SliceByteArray(encoded, 0);
    SliceByteArray reverse = new SliceByteArray(decoded, 0);

    Assertions.assertTrue(codec.forward(input, output));
    int encodedSize = output.index;
    output.index = 0;
    output.length = encodedSize;
    Assertions.assertTrue(codec.inverse(output, reverse));
    Assertions.assertEquals(data.length, reverse.index);
    Assertions.assertArrayEquals(data, decoded);
    return Arrays.copyOf(encoded, encodedSize);
  }

  @Test
  void testExpandedRoundTrip() {
    byte[] data = createPEBlock(0x014C);
    fillX86ExpandedCode(data, 512, 4096);
    byte[] encoded = roundTrip(data);
    Assertions.assertTrue(encoded.length > data.length + 9);
  }

  @Test
  void testBoundaryJCCRoundTrip() {
    roundTrip(createBoundaryBlock());
  }

  @Test
  void testLegacyBoundaryJCCRoundTrip() {
    byte[] data = createBoundaryBlock();
    byte[] encoded = roundTrip(data);
    int codeEnd = LittleEndian.readInt32(encoded, 5);

    Assertions.assertTrue(codeEnd < encoded.length);
    Assertions.assertEquals(0x0F, encoded[codeEnd] & 0xFF);

    byte[] legacy = Arrays.copyOf(encoded, encoded.length);
    LittleEndian.writeInt32(legacy, 5, codeEnd + 1);
    EXECodec codec = new EXECodec();
    byte[] decoded = new byte[data.length];
    SliceByteArray input = new SliceByteArray(legacy, 0);
    SliceByteArray output = new SliceByteArray(decoded, 0);
    Assertions.assertTrue(codec.inverse(input, output));
    Assertions.assertEquals(data.length, output.index);
    Assertions.assertArrayEquals(data, decoded);
  }
}
