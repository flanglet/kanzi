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

package io.github.flanglet.kanzi.entropy;

import io.github.flanglet.kanzi.EntropyDecoder;
import io.github.flanglet.kanzi.InputBitStream;

// Exponential Golomb Coder
public final class ExpGolombDecoder implements EntropyDecoder {
  private final boolean signed;
  private final InputBitStream bitstream;

  public ExpGolombDecoder(InputBitStream bitstream, boolean signed) {
    if (bitstream == null)
      throw new NullPointerException("ExpGolomb codec: Invalid null bitstream parameter");

    this.signed = signed;
    this.bitstream = bitstream;
  }

  public boolean isSigned() {
    return this.signed;
  }

  public byte decodeByte() {
    if (this.bitstream.readBit() == 1)
      return 0;

    int log2 = 1;

    while (this.bitstream.readBit() == 0)
      log2++;

    if (this.signed == true) {
      // Decode signed: read value + sign
      long res = this.bitstream.readBits(log2 + 1);
      final long sgn = res & 1;
      res = (res >>> 1) + (1 << log2) - 1;
      return (byte) ((res - sgn) ^ -sgn); // res or -res
    }

    // Decode unsigned
    return (byte) ((1 << log2) - 1 + this.bitstream.readBits(log2));
  }

  @Override
  public InputBitStream getBitStream() {
    return this.bitstream;
  }

  @Override
  /**
   * Decodes a block of data by reading it directly from the bitstream.
   * <p>
   * This method reads {@code count} bytes from the bitstream into the provided {@code block} array.
   * </p>
   *
   * @param block The byte array to decode into.
   * @param blkptr The starting position in the block.
   * @param count The number of bytes to decode.
   * @return The number of bytes decoded, or -1 if an error occurs (e.g., invalid parameters).
   */
  public int decode(byte[] block, int blkptr, int count) {
    if ((block == null) || (blkptr + count > block.length) || (blkptr < 0) || (count < 0))
      return -1;

    final int end = blkptr + count;

    for (int i = blkptr; i < end; i++)
      block[i] = this.decodeByte();

    return count;
  }

  @Override
  public void dispose() {}
}
