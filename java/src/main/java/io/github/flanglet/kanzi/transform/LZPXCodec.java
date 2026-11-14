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
import java.util.Map;
import io.github.flanglet.kanzi.ByteTransform;
import io.github.flanglet.kanzi.Memory;
import io.github.flanglet.kanzi.SliceByteArray;

/** Lempel-Ziv Predict Extra transform. */
final class LZPXCodec implements ByteTransform {
  private static final int HASH_LOG = 18;
  private static final int HASH_SHIFT = 14;
  private static final int CHUNK_SIZE = 4194304;
  private static final int MIN_CHUNK_SIZE = 33; // bitmap plus end-of-block token
  private static final int MIN_MATCH = 2;
  private static final int MIN_ABSENT_CODES = 16;

  private int[] hashes;
  private long[] hashes64;
  private int hashStride;

  LZPXCodec() {
    this(null);
  }

  LZPXCodec(Map<String, Object> ctx) {
    this.hashes = new int[0];
    this.hashes64 = new long[0];
    final Object lzpxType = (ctx == null) ? null : ctx.get("lzpxType");
    this.hashStride = (lzpxType instanceof Number) &&
        (((Number) lzpxType).intValue() == 2) ? 8 : 1;
  }

  @Override
  public int getMaxEncodedLength(int srcLength) {
    return Math.max(0, srcLength);
  }

  // The block header stores minMatch-2 in its low 6 bits; its high bit selects
  // stride 8. Each chunk starts with a 256-bit literal bitmap (least significant bit first).
  // Absent bytes are split into:
  // - codes[0]: end-of-chunk marker
  // - codes[1..nbCodes-3]: 1-byte short matches [minMatch .. maxShort]
  // - codes[nbCodes-2]: matches [maxShort+1 .. maxShort+256] followed by 1 byte offset (0..255)
  // - codes[nbCodes-1]: matches >= maxShort+257 followed by unsigned LEB128 (7 bits/byte, 0x80=more)
  // The predictor table spans all chunks.
  @Override
  public boolean forward(SliceByteArray src, SliceByteArray dst) {
    if (src.length == 0)
      return true;

    if (!SliceByteArray.isValid(src) || !SliceByteArray.isValid(dst))
      return false;

    final int count = src.length;

    if ((count < 4) || (src.array == dst.array) || (dst.length - dst.index < count)
        || ((long) src.index + count > src.array.length) || (dst.length > dst.array.length))
      return false;

    if (this.hashes64.length == 0)
      this.hashes64 = new long[1 << HASH_LOG];
    else
      Arrays.fill(this.hashes64, 0L);

    final byte[] input = src.array;
    final byte[] output = dst.array;
    final int srcStart = src.index;
    final int srcEnd = srcStart + count;
    final int dstStart = dst.index;
    final int dstEnd = dstStart + count;
    final long[] hashes = this.hashes64;
    final int stride = this.hashStride;
    int srcIdx = srcStart;
    int dstIdx = dstStart;

    output[dstIdx++] = (byte) (((MIN_MATCH - 2) & 0x3F) | ((stride == 8) ? 0x80 : 0));

    // Chunks need their own token alphabet: absent byte values encode the
    // end marker, short match lengths, and the extended-length prefix.
    // Limit chunks to CHUNK_SIZE and adapt the alphabet cap for long chunks.
    while (srcIdx < srcEnd) {
      if (dstEnd - dstIdx < MIN_CHUNK_SIZE)
        return false;

      int size = Math.min(srcEnd - srcIdx, CHUNK_SIZE);
      final long[] seen = new long[4];
      int alphabet = 0;

      for (int i = 0; i < size; i++) {
        final int val = input[srcIdx + i] & 0xFF;
        final long bit = 1L << (val & 63);
        final int wIdx = val >>> 6;

        if ((seen[wIdx] & bit) != 0)
          continue;

        final int maxAlphabet = (i < 4096) ? (256 - MIN_ABSENT_CODES) : 216;

        if (alphabet >= maxAlphabet) {
          size = i;
          break;
        }

        seen[wIdx] |= bit;
        alphabet++;
      }

      final byte[] codes = new byte[256];
      int nbCodes = 0;

      // Mark present bytes as literals; absent bytes become end and match codes.
      for (int k = 0; k < 4; k++) {
        Memory.LittleEndian.writeLong64(output, dstIdx + (k << 3), seen[k]);
        long absent = ~seen[k];

        while (absent != 0) {
          codes[nbCodes++] = (byte) ((k << 6) | Long.numberOfTrailingZeros(absent));
          absent &= absent - 1;
        }
      }

      dstIdx += 32;
      final int end = srcIdx + size;

      // Reserve 3 control codes (EOB, 1-byte long offset, LEB128 long offset),
      // leaving nbCodes - 3 absent bytes for 1-byte short lengths [MIN_MATCH .. maxShort].
      final int maxShort = MIN_MATCH + nbCodes - 4;

      if (srcIdx == srcStart) {
        // The first four bytes have no preceding context, so emit them as literals.
        if (dstEnd - dstIdx < 4)
          return false;

        System.arraycopy(input, srcIdx, output, dstIdx, 4);
        dstIdx += 4;
        srcIdx += 4;
      }

      // Main loop
      while (srcIdx < end) {
        int length = 0;
        final int h = hash(Memory.LittleEndian.readInt32(input, srcIdx - 4));
        final long entry = hashes[h];
        final int refRel = (int) (entry >>> 16);
        final int tag = (srcIdx + 2 <= srcEnd) ? Memory.LittleEndian.readInt16(input, srcIdx) : 0;

        if ((refRel != 0) && (end - srcIdx >= MIN_MATCH) && (((int) entry & 0xFFFF) == tag)) {
          final int ref = srcStart + refRel;
          final int limit = end - srcIdx;
          length = findMatch(input, srcIdx, ref, limit);

          if ((stride == 8) && (length >= MIN_MATCH) && (srcIdx + length + 1 < end)) {
            final int h2 = hash(Memory.LittleEndian.readInt32(input, srcIdx - 3));
            final long entry2 = hashes[h2];
            final int ref2Rel = (int) (entry2 >>> 16);

            if ((ref2Rel != 0)
                && (((int) entry2 & 0xFFFF) == Memory.LittleEndian.readInt16(input, srcIdx + 1))) {
              final int ref2 = srcStart + ref2Rel;
              final int limit2 = end - srcIdx - 1;
              final int length2 = findMatch(input, srcIdx + 1, ref2, limit2);

              if (length2 > length)
                length = 0;
            }
          }
        }

        if (dstIdx >= dstEnd)
          return false;

        hashes[h] = ((long) (srcIdx - srcStart) << 16) | (tag & 0xFFFFL);

        if (length < MIN_MATCH) {
          output[dstIdx++] = input[srcIdx++];
        } else {
          if (length <= maxShort) {
            output[dstIdx++] = codes[length - MIN_MATCH + 1];
          } else {
            int rem = length - (maxShort + 1);

            if (rem < 256) {
              // Medium match [maxShort+1 .. maxShort+256]: prefix code + 1 byte (0..255).
              output[dstIdx++] = codes[nbCodes - 2];

              if (dstIdx >= dstEnd)
                return false;

              output[dstIdx++] = (byte) rem;
            } else {
              // Long match >= maxShort+257: prefix code + LEB128 of (length - maxShort - 257).
              // Write 7 bits per byte (LSB first); set bit 7 (0x80) on all but the last byte.
              output[dstIdx++] = codes[nbCodes - 1];
              rem -= 256;

              while (rem >= 128) {
                if (dstIdx >= dstEnd)
                  return false;

                output[dstIdx++] = (byte) ((rem & 0x7F) | 0x80);
                rem >>>= 7;
              }

              if (dstIdx >= dstEnd)
                return false;

              output[dstIdx++] = (byte) rem;
            }
          }

          final int next = srcIdx + length;

          // Sample hash positions at a fixed stride within this match.
          for (int hashPos = srcIdx + stride; hashPos < next; hashPos += stride) {
            final int t =
                (hashPos + 2 <= srcEnd) ? Memory.LittleEndian.readInt16(input, hashPos) : 0;
            hashes[hash(Memory.LittleEndian.readInt32(input, hashPos - 4))] =
                ((long) (hashPos - srcStart) << 16) | (t & 0xFFFFL);
          }

          srcIdx = next;
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

    if ((count < MIN_CHUNK_SIZE + 1) || (src.array == dst.array)
        || ((long) src.index + count > src.array.length) || (dst.length > dst.array.length))
      return false;

    if (this.hashes.length == 0)
      this.hashes = new int[1 << HASH_LOG];
    else
      Arrays.fill(this.hashes, 0);

    final byte[] input = src.array;
    final byte[] output = dst.array;
    final int srcEnd = src.index + count;
    final int dstStart = dst.index;
    final int dstEnd = dst.length;
    final int[] hashes = this.hashes;
    int srcIdx = src.index;
    int dstIdx = dstStart;

    // The transform header carries the hash stride and minimum match length.
    final int header = input[srcIdx++] & 0xFF;
    final int stride = ((header & 0x80) != 0) ? 8 : 1;
    this.hashStride = stride;
    final int minMatch = (header & 0x3F) + 2;

    // The first four output bytes seed the hash context. Validate them
    // against the first chunk alphabet before entering the chunk loop.
    final int firstChunk = srcIdx;

    if (srcEnd - firstChunk < 32 + 4)
      return false;

    final int firstTokens = firstChunk + 32;

    for (int i = 0; i < 4; i++) {
      final int val = input[firstTokens + i] & 0xFF;

      if (((input[firstChunk + (val >>> 3)] & 0xFF) & (1 << (val & 7))) == 0)
        return false;
    }

    // Maintain the 4-byte hash context in a register (little-endian order)
    // to avoid store-to-load forwarding stalls on output[dstIdx - 4].
    int ctx = 0;
    final int[] lengths = new int[256];

    while (srcIdx < srcEnd) {
      if (srcEnd - srcIdx < MIN_CHUNK_SIZE)
        return false;

      Arrays.fill(lengths, -1);
      int nbCodes = 0;
      int firstCode = 0;

      // Extract absent bytes (0 bits in the 256-bit bitmap) in ascending order.
      // Assign consecutive match lengths starting at (minMatch - 1) so that:
      // - 0th code (overwritten to 0 below) = EOB
      // - next codes = short matches [minMatch .. maxShort]
      // - second-to-last code = maxShort + 1 (base for 1-byte offset 0..255)
      // - last code = maxShort + 2 (marker for base maxShort + 257 + LEB128)
      for (int k = 0; k < 4; k++) {
        long absent = ~Memory.LittleEndian.readLong64(input, srcIdx + (k << 3));

        while (absent != 0) {
          final int code = (k << 6) | Long.numberOfTrailingZeros(absent);
          lengths[code] = minMatch + nbCodes - 1;

          if (nbCodes == 0)
            firstCode = code;

          nbCodes++;
          absent &= absent - 1;
        }
      }

      // Need EOB, at least 1 short length, and both extended-length codes.
      if (nbCodes < 4)
        return false;

      lengths[firstCode] = 0;
      final int maxShort = minMatch + nbCodes - 4;
      srcIdx += 32;
      final int blockStart = dstIdx;
      boolean endBlock = false;

      // Main loop
      while (srcIdx < srcEnd) {
        final byte val = input[srcIdx++];
        int length = lengths[val & 0xFF];

        if (length == 0) {
          endBlock = true;
          break;
        }

        if (length < 0) {
          if ((dstIdx >= dstEnd) || (dstIdx - blockStart >= CHUNK_SIZE))
            return false;

          if (dstIdx - dstStart >= 4)
            hashes[hash(ctx)] = dstIdx - dstStart;

          output[dstIdx++] = val;
          ctx = (ctx >>> 8) | ((val & 0xFF) << 24);
          continue;
        }

        // Decode lengths longer than maxShort:
        // - length == maxShort + 1: read 1 unsigned byte offset (adds 0..255).
        // - length == maxShort + 2: base is maxShort + 257, plus 7-bit LEB128 varint
        //   (at most 4 varint bytes, since CHUNK_SIZE < 2^28).
        if (length > maxShort) {
          if (length == maxShort + 1) {
            if (srcIdx >= srcEnd)
              return false;

            length += input[srcIdx++] & 0xFF;

            if (length > CHUNK_SIZE)
              return false;
          } else {
            length = maxShort + 1 + 256;
            int shift = 0;
            int b;

            do {
              if ((srcIdx >= srcEnd) || (shift > 21))
                return false;

              b = input[srcIdx++] & 0xFF;
              length += (b & 0x7F) << shift;

              if (length > CHUNK_SIZE)
                return false;

              shift += 7;
            } while (b >= 128);
          }
        }

        final int h = hash(ctx);
        final int refRelative = hashes[h];
        final int ref = dstStart + refRelative;

        if ((refRelative == 0) || (ref >= dstIdx))
          return false;

        if ((length > dstEnd - dstIdx) || (length > CHUNK_SIZE - (dstIdx - blockStart)))
          return false;

        hashes[h] = dstIdx - dstStart;
        final int dist = dstIdx - ref;
        final int next = dstIdx + length;

        if (dist >= length) {
          System.arraycopy(output, ref, output, dstIdx, length);
        } else if ((dist >= 8) && (next + 7 <= dstEnd)) {
          int n = 0;

          do {
            Memory.LittleEndian.writeLong64(
                output, dstIdx + n, Memory.LittleEndian.readLong64(output, ref + n));
            n += 8;
          } while (n < length);
        } else {
          for (int n = 0; n < length; n++)
            output[dstIdx + n] = output[ref + n];
        }

        // Update sampled hash entries and advance rolling ctx. When dist >= length,
        // read from the source ref rather than output to avoid store-to-load stalls.
        if (stride > 1) {
          for (int p = dstIdx + stride; p < next; p += stride)
            hashes[hash(Memory.LittleEndian.readInt32(output, p - 4))] = p - dstStart;

          if (dist >= length) {
            if (length >= 4)
              ctx = Memory.LittleEndian.readInt32(output, ref + length - 4);
            else if (length == 2)
              ctx = (ctx >>> 16) | (Memory.LittleEndian.readInt16(output, ref) << 16);
            else if (length == 3)
              ctx = (ctx >>> 24) | (Memory.LittleEndian.readInt32(output, ref) << 8);
            else
              ctx = Memory.LittleEndian.readInt32(output, next - 4);
          } else {
            ctx = Memory.LittleEndian.readInt32(output, next - 4);
          }
        } else {
          // hashes[h] for dstIdx was already updated above; advance n from 0 to
          // feed ctx before overwriting dstIdx + n for n >= 1.
          if (dist >= length) {
            for (int n = 0; n < length; n++) {
              hashes[hash(ctx)] = dstIdx - dstStart + n;
              ctx = (ctx >>> 8) | ((output[ref + n] & 0xFF) << 24);
            }
          } else {
            for (int p = dstIdx + 1; p < next; p++)
              hashes[hash(Memory.LittleEndian.readInt32(output, p - 4))] = p - dstStart;

            ctx = Memory.LittleEndian.readInt32(output, next - 4);
          }
        }

        dstIdx = next;
      }

      if (!endBlock || (dstIdx == blockStart))
        return false;
    }

    src.index = srcEnd;
    dst.index = dstIdx;
    return true;
  }

  private static int hash(int ctx) {
    return (0x7FEB352D * (ctx ^ (ctx >>> 16))) >>> HASH_SHIFT;
  }

  private static int findMatch(byte[] src, int srcIdx, int ref, int maxMatch) {
    int n = 0;

    while (n + 8 <= maxMatch) {
      final long diff = Memory.LittleEndian.readLong64(src, srcIdx + n)
          ^ Memory.LittleEndian.readLong64(src, ref + n);

      if (diff != 0)
        return n + (Long.numberOfTrailingZeros(diff) >>> 3);

      n += 8;
    }

    if (n + 4 <= maxMatch) {
      final int diff = Memory.LittleEndian.readInt32(src, srcIdx + n)
          ^ Memory.LittleEndian.readInt32(src, ref + n);

      if (diff != 0)
        return n + (Integer.numberOfTrailingZeros(diff) >>> 3);

      n += 4;
    }

    while ((n < maxMatch) && (src[srcIdx + n] == src[ref + n]))
      n++;

    return n;
  }
}
