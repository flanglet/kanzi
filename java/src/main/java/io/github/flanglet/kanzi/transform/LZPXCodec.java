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

package io.github.flanglet.kanzi.transform;

import java.util.Arrays;
import io.github.flanglet.kanzi.ByteTransform;
import io.github.flanglet.kanzi.Memory;
import io.github.flanglet.kanzi.SliceByteArray;

/** Lempel-Ziv Predict Extra transform. */
final class LZPXCodec implements ByteTransform {
  private static final int HASH_LOG = 17;
  private static final int HASH_SHIFT = 15;
  private static final int CHUNK_SIZE = 65536;
  private static final int MIN_CHUNK_SIZE = 33; // bitmap plus end-of-block token
  private static final int MIN_ABSENT_CODES = 16;

  private int[] hashes;

  LZPXCodec() {
    this.hashes = new int[0];
  }

  @Override
  public int getMaxEncodedLength(int srcLength) {
    return Math.max(0, srcLength);
  }

  @Override
  public boolean forward(SliceByteArray src, SliceByteArray dst) {
    if (src.length == 0)
      return true;

    if (!SliceByteArray.isValid(src) || !SliceByteArray.isValid(dst))
      return false;

    final int count = src.length;

    if ((count < 4) || (src.array == dst.array) || (dst.length - dst.index < count))
      return false;

    if (this.hashes.length == 0)
      this.hashes = new int[1 << HASH_LOG];
    else
      Arrays.fill(this.hashes, 0);

    final byte[] input = src.array;
    final byte[] output = dst.array;
    final int srcEnd = src.index + count;
    final int dstStart = dst.index;
    final int dstEnd = dstStart + count;
    final int minMatch = 2;
    int srcIdx = src.index;
    int dstIdx = dstStart;

    // The minimum match applies to the whole transform block. Each chunk
    // carries only its own literal alphabet.
    output[dstIdx++] = (byte) (minMatch - 2);

    while (srcIdx < srcEnd) {
      if (dstEnd - dstIdx < MIN_CHUNK_SIZE)
        return false;

      int size = Math.min(srcEnd - srcIdx, CHUNK_SIZE);
      final int[] freqs = new int[256];
      int alphabet = 0;

      for (int i = 0; i < size; i++) {
        final int val = input[srcIdx + i] & 0xFF;

        if (freqs[val]++ == 0)
          alphabet++;
      }

      // Keep at least MIN_ABSENT_CODES absent values: one for the end marker
      // and the rest for match lengths. Dense alphabets need smaller chunks.
      if (alphabet > 256 - MIN_ABSENT_CODES) {
        Arrays.fill(freqs, 0);
        alphabet = 0;
        size = 0;

        while (alphabet < 256 - MIN_ABSENT_CODES) {
          final int val = input[srcIdx + size++] & 0xFF;

          if (freqs[val]++ == 0)
            alphabet++;
        }
      }

      final int bitmapIdx = dstIdx;
      Arrays.fill(output, dstIdx, dstIdx + 32, (byte) 0);
      final byte[] codes = new byte[256];
      int nbCodes = 0;

      for (int i = 0; i < 256; i++) {
        if (freqs[i] == 0) {
          codes[nbCodes++] = (byte) i;
        } else {
          output[bitmapIdx + (i >>> 3)] |= (byte) (1 << (i & 7));
        }
      }

      dstIdx += 32;
      final int end = srcIdx + size;
      final int maxMatch = minMatch + nbCodes - 2;

      if (srcIdx == src.index) {
        if (dstEnd - dstIdx < 4)
          return false;

        System.arraycopy(input, srcIdx, output, dstIdx, 4);
        dstIdx += 4;
        srcIdx += 4;
      }

      while (srcIdx < end) {
        final int hash = hash(Memory.LittleEndian.readInt32(input, srcIdx - 4));
        final int ref = this.hashes[hash];
        int length = 0;

        if (ref != 0)
          length = findMatch(input, srcIdx, ref, Math.min(maxMatch, end - srcIdx));

        if (dstIdx >= dstEnd)
          return false;

        if (length < minMatch) {
          output[dstIdx++] = input[srcIdx];
          this.hashes[hash(Memory.LittleEndian.readInt32(input, srcIdx - 4))] = srcIdx;
          srcIdx++;
        } else {
          output[dstIdx++] = codes[length - minMatch + 1];
          final int next = srcIdx + length;

          while (srcIdx < next) {
            this.hashes[hash(Memory.LittleEndian.readInt32(input, srcIdx - 4))] = srcIdx;
            srcIdx++;
          }
        }
      }

      if (dstIdx >= dstEnd)
        return false;

      output[dstIdx++] = codes[0];
    }

    if (dstIdx - dstStart > count - count / 100)
      return false;

    src.index = srcEnd;
    dst.index = dstIdx;
    return true;
  }

  @Override
  public boolean inverse(SliceByteArray src, SliceByteArray dst) {
    if (src.length == 0)
      return true;

    if (!SliceByteArray.isValid(src) || !SliceByteArray.isValid(dst))
      return false;

    final int count = src.length;

    if ((count < MIN_CHUNK_SIZE + 1) || (src.array == dst.array))
      return false;

    if (this.hashes.length == 0)
      this.hashes = new int[1 << HASH_LOG];
    else
      Arrays.fill(this.hashes, 0);

    final byte[] input = src.array;
    final byte[] output = dst.array;
    final int srcEnd = src.index + count;
    final int dstEnd = dst.length;
    int srcIdx = src.index;
    int dstIdx = dst.index;
    final int minMatch = (input[srcIdx++] & 0xFF) + 2;

    while (srcIdx < srcEnd) {
      if (srcEnd - srcIdx < MIN_CHUNK_SIZE)
        return false;

      final int[] lengths = new int[256];
      int nbCodes = 0;

      for (int i = 0; i < 256; i++) {
        if (((input[srcIdx + (i >>> 3)] & 0xFF) & (1 << (i & 7))) != 0) {
          lengths[i] = -1;
        } else if (nbCodes++ == 0) {
          lengths[i] = 0;
        } else {
          lengths[i] = minMatch + nbCodes - 2;
        }
      }

      srcIdx += 32;

      if (nbCodes < 2)
        return false;

      final int blockStart = dstIdx;
      boolean endBlock = false;

      // The first four output bytes seed the hash context and must be literals.
      if (dstIdx == dst.index) {
        if (srcEnd - srcIdx < 4)
          return false;

        for (int i = 0; i < 4; i++) {
          if (lengths[input[srcIdx + i] & 0xFF] != -1)
            return false;
        }
      }

      while (srcIdx < srcEnd) {
        final byte val = input[srcIdx++];
        final int length = lengths[val & 0xFF];

        if (length == 0) {
          endBlock = true;
          break;
        }

        if (length < 0) {
          if ((dstIdx >= dstEnd) || (dstIdx - blockStart >= CHUNK_SIZE))
            return false;

          if (dstIdx >= dst.index + 4)
            this.hashes[hash(Memory.LittleEndian.readInt32(output, dstIdx - 4))] = dstIdx - dst.index;

          output[dstIdx++] = val;
          continue;
        }

        final int refRelative = this.hashes[hash(Memory.LittleEndian.readInt32(output, dstIdx - 4))];
        int ref = dst.index + refRelative;

        if ((refRelative == 0) || (ref >= dstIdx))
          return false;

        if ((length > dstEnd - dstIdx) || (length > CHUNK_SIZE - (dstIdx - blockStart)))
          return false;

        final int next = dstIdx + length;

        while (dstIdx < next) {
          if (dstIdx >= dst.index + 4)
            this.hashes[hash(Memory.LittleEndian.readInt32(output, dstIdx - 4))] = dstIdx - dst.index;

          output[dstIdx++] = output[ref++];
        }
      }

      if (!endBlock || (dstIdx == blockStart))
        return false;
    }

    src.index = srcEnd;
    dst.index = dstIdx;
    return true;
  }

  private static int hash(int ctx) {
    return (0x7FEB352D * ctx) >>> HASH_SHIFT;
  }

  private static int findMatch(byte[] src, int srcIdx, int ref, int maxMatch) {
    int n = 0;

    while (n + 8 <= maxMatch) {
      final long diff = Memory.LittleEndian.readLong64(src, srcIdx + n)
          ^ Memory.LittleEndian.readLong64(src, ref + n);

      if (diff != 0) {
        n += Long.numberOfTrailingZeros(diff) >>> 3;
        break;
      }

      n += 8;
    }

    return n;
  }
}
