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

package io.github.flanglet.kanzi.transform;

import java.util.Map;
import io.github.flanglet.kanzi.ByteTransform;
import io.github.flanglet.kanzi.Global;
import io.github.flanglet.kanzi.Memory;
import io.github.flanglet.kanzi.SliceByteArray;

/**
 * <p>
 * Simple byte oriented LZ77 implementation.
 * </p>
 */
public final class LZCodec implements ByteTransform {
  private final ByteTransform delegate;

  /**
   * <p>
   * Creates a new {@link LZCodec} instance. The default implementation uses LZXCodec.
   * </p>
   *
   * @see LZXCodec
   */
  public LZCodec() {
    this.delegate = new LZXCodec();
  }


  /**
   * <p>
   * Creates a new {@link LZCodec} instance with the given context.
   * </p>
   *
   * @param ctx A map of parameters to configure the LZ codec.
   * @see LZXCodec
   */
  public LZCodec(Map<String, Object> ctx) {
    // Encode the word indexes as varints with a token or with a mask
    final short lzType = (short) ctx.getOrDefault("lz", TransformFactory.LZ_TYPE);
    this.delegate = (lzType == TransformFactory.LZP_TYPE) ? new LZPCodec(ctx) : new LZXCodec(ctx);
  }

  @Override
  /**
   * <p>
   * Returns the maximum length of the encoded data for a given source length.
   * </p>
   *
   * @param srcLength The length of the source data.
   * @return The maximum length of the encoded data.
   */
  public int getMaxEncodedLength(int srcLength) {
    return this.delegate.getMaxEncodedLength(srcLength);
  }

  @Override
  /**
   * <p>
   * Encodes the provided data in the source slice and puts the result in the destination slice.
   * </p>
   *
   * @param src The source slice of bytes.
   * @param dst The destination slice of bytes.
   * @return {@code true} if the encoding was successful, {@code false} otherwise.
   */
  public boolean forward(SliceByteArray src, SliceByteArray dst) {
    if (src.length == 0)
      return true;

    if (src.array == dst.array)
      return false;

    return this.delegate.forward(src, dst);
  }

  @Override
  /**
   * <p>
   * Decodes the provided data in the source slice and puts the result in the destination slice.
   * </p>
   *
   * @param src The source slice of bytes.
   * @param dst The destination slice of bytes.
   * @return {@code true} if the decoding was successful, {@code false} otherwise.
   */
  public boolean inverse(SliceByteArray src, SliceByteArray dst) {
    if (src.length == 0)
      return true;

    if (src.array == dst.array)
      return false;

    return this.delegate.inverse(src, dst);
  }

  /**
   * <p>
   * Checks if two 4-byte sequences are different.
   * </p>
   *
   * @param array The byte array containing the sequences.
   * @param srcIdx The starting index of the first sequence.
   * @param dstIdx The starting index of the second sequence.
   * @return {@code true} if the sequences are different, {@code false} otherwise.
   */
  private static boolean differentInts(byte[] array, int srcIdx, int dstIdx) {
    return ((array[srcIdx] != array[dstIdx]) || (array[srcIdx + 1] != array[dstIdx + 1])
        || (array[srcIdx + 2] != array[dstIdx + 2]) || (array[srcIdx + 3] != array[dstIdx + 3]));
  }

  /**
   * <p>
   * Implementation of a byte oriented LZ77 codec.
   * </p>
   */
  static final class LZXCodec implements ByteTransform {
    private static final int HASH_SEED = 0x1E35A7BD;
    private static final int HASH_LOG1 = 16;
    private static final int HASH_RSHIFT1 = 64 - HASH_LOG1;
    private static final int HASH_LSHIFT1 = 24;
    private static final int HASH_LOG2 = 19;
    private static final int HASH_RSHIFT2 = 64 - HASH_LOG2;
    private static final int HASH_LSHIFT2 = 24;
    private static final int MAX_DISTANCE1 = (1 << 16) - 2;
    private static final int MAX_DISTANCE2 = (1 << 24) - 2;
    private static final int MIN_MATCH4 = 4;
    private static final int MIN_MATCH6 = 6;
    private static final int MIN_MATCH9 = 9;
    private static final int MAX_MATCH = 65535 + 254 + MIN_MATCH4;
    private static final int MIN_BLOCK_LENGTH = 24;

    private int[] hashes;
    private byte[] mBuf;
    private byte[] mLenBuf;
    private byte[] tkBuf;
    private final boolean extra;
    private final Map<String, Object> ctx;
    private int bsVersion;

    /**
     * <p>
     * Creates a new {@link LZXCodec} instance.
     * </p>
     */
    public LZXCodec() {
      this.hashes = new int[0];
      this.mBuf = new byte[0];
      this.mLenBuf = new byte[0];
      this.tkBuf = new byte[0];
      this.extra = false;
      this.ctx = null;
      this.bsVersion = 6;
    }

    /**
     * <p>
     * Creates a new {@link LZXCodec} instance with the given context.
     * </p>
     *
     * @param ctx A map of parameters to configure the LZX codec.
     */
    public LZXCodec(Map<String, Object> ctx) {
      this.hashes = new int[0];
      this.mBuf = new byte[0];
      this.mLenBuf = new byte[0];
      this.tkBuf = new byte[0];
      this.extra = (ctx == null) ? false
          : (short) ctx.getOrDefault("lz", TransformFactory.LZ_TYPE) == TransformFactory.LZX_TYPE;
      this.ctx = ctx;
      this.bsVersion = (ctx == null) ? 6 : (int) ctx.getOrDefault("bsVersion", 6);
    }

    /**
     * <p>
     * Emits a length value into the block.
     * </p>
     *
     * @param block The byte array to write the length to.
     * @param idx The starting index in the block.
     * @param length The length to emit.
     * @return The next available index in the block after emitting the length.
     */
    private static int emitLength(byte[] block, int idx, int length) {
      if (length < 254) {
        block[idx] = (byte) length;
        return idx + 1;
      }

      if (length < 65536 + 254) {
        length -= 254;
        block[idx] = (byte) 254;
        block[idx + 1] = (byte) (length >> 8);
        block[idx + 2] = (byte) (length);
        return idx + 3;
      }

      length -= 255;
      block[idx] = (byte) 255;
      block[idx + 1] = (byte) (length >> 16);
      block[idx + 2] = (byte) (length >> 8);
      block[idx + 3] = (byte) (length);
      return idx + 4;
    }

    /**
     * <p>
     * Reads a length value from the slice.
     * </p>
     *
     * @param sba The slice byte array to read the length from.
     * @return The read length.
     */
    private static int readLength(SliceByteArray sba) {
      int res = sba.array[sba.index++] & 0xFF;

      if (res < 254)
        return res;

      if (res == 254) {
        res += ((sba.array[sba.index++] & 0xFF) << 8);
        res += (sba.array[sba.index++] & 0xFF);
        return res;
      }

      res += ((sba.array[sba.index] & 0xFF) << 16);
      res += ((sba.array[sba.index + 1] & 0xFF) << 8);
      res += (sba.array[sba.index + 2] & 0xFF);
      sba.index += 3;
      return res;
    }

    /**
     * <p>
     * Finds the length of the longest match between two sequences.
     * </p>
     *
     * @param src The source byte array.
     * @param srcIdx The starting index of the first sequence.
     * @param ref The starting index of the second sequence.
     * @param maxMatch The maximum length to check for a match.
     * @return The length of the longest match.
     */
    private static int findMatch(byte[] src, final int srcIdx, final int ref, final int maxMatch) {
      int bestLen = 0;

      while (bestLen + 4 <= maxMatch) {
        final int diff = Memory.LittleEndian.readInt32(src, srcIdx + bestLen)
            ^ Memory.LittleEndian.readInt32(src, ref + bestLen);

        if (diff != 0) {
          bestLen += (Long.numberOfTrailingZeros(diff) >> 3);
          break;
        }

        bestLen += 4;
      }

      return bestLen;
    }

    @Override
    /**
     * <p>
     * Encodes the provided data in the source slice and puts the result in the destination slice.
     * </p>
     *
     * @param input The source slice of bytes.
     * @param output The destination slice of bytes.
     * @return {@code true} if the encoding was successful, {@code false} otherwise.
     */
    public boolean forward(SliceByteArray input, SliceByteArray output) {
      if (input.length == 0)
        return true;

      final int count = input.length;

      if (output.length - output.index < this.getMaxEncodedLength(count))
        return false;

      // If too small, skip
      if (count < MIN_BLOCK_LENGTH)
        return false;

      if (this.hashes.length == 0) {
        this.hashes = (this.extra == true) ? new int[1 << HASH_LOG2] : new int[1 << HASH_LOG1];
      } else {
        for (int i = 0; i < this.hashes.length; i++)
          this.hashes[i] = 0;
      }

      final int minBufSize = Math.max(count / 5, 256);

      if (this.mBuf.length < minBufSize)
        this.mBuf = new byte[minBufSize];

      if (this.mLenBuf.length < minBufSize)
        this.mLenBuf = new byte[minBufSize];

      if (this.tkBuf.length < minBufSize)
        this.tkBuf = new byte[minBufSize];

      final int srcIdx0 = input.index;
      final int dstIdx0 = output.index;
      final byte[] src = input.array;
      final byte[] dst = output.array;
      final int srcEnd = srcIdx0 + count - 16 - 1;
      final int maxDist = (srcEnd < 4 * MAX_DISTANCE1) ? MAX_DISTANCE1 : MAX_DISTANCE2;
      dst[dstIdx0 + 12] = (maxDist == MAX_DISTANCE1) ? (byte) 0 : (byte) 1;
      int mm = MIN_MATCH4;

      if (this.ctx != null) {
        Global.DataType dt =
            (Global.DataType) this.ctx.getOrDefault("dataType", Global.DataType.UNDEFINED);

        if (dt == Global.DataType.DNA) {
          mm = MIN_MATCH6;
        } else if (dt == Global.DataType.SMALL_ALPHABET) {
          return false;
        }
      }

      // dst[12] = 0000MMMD (4 bits + 3 bits minMatch + 1 bit max distance)
      dst[12] |= (byte) (((mm - 2) & 0x07) << 1); // minMatch in [2..9]
      final int minMatch = mm;
      int srcIdx = srcIdx0;
      int anchor = srcIdx0;
      int dstIdx = dstIdx0 + 13;
      int mIdx = 0;
      int mLenIdx = 0;
      int tkIdx = 0;
      int[] repd = new int[] {count, count};
      int repIdx = 0;
      int srcInc = 0;

      while (srcIdx < srcEnd) {
        int bestLen = 0;
        final int h0 = hash(src, srcIdx);
        final int ref0 = this.hashes[h0];
        this.hashes[h0] = srcIdx;
        final int srcIdx1 = srcIdx + 1;
        int ref = srcIdx1 - repd[repIdx];
        final int minRef = Math.max(srcIdx - maxDist, srcIdx0);

        // Check repd first
        if ((ref > minRef) && (differentInts(src, ref, srcIdx1) == false)) {
          bestLen = findMatch(src, srcIdx1, ref, Math.min(srcEnd - srcIdx1, MAX_MATCH));
        }
        else {
          ref = srcIdx1 - repd[repIdx];

          if ((ref > minRef) && (differentInts(src, ref, srcIdx1) == false)) {
            bestLen = findMatch(src, srcIdx1, ref, Math.min(srcEnd - srcIdx1, MAX_MATCH));
          }
        }

        if (bestLen < minMatch) {
          // Check match at position in hash table
          ref = ref0;

          if ((ref > minRef) && (differentInts(src, ref, srcIdx) == false)) {
            bestLen = findMatch(src, srcIdx, ref, Math.min(srcEnd - srcIdx, MAX_MATCH));
          }

          // No good match ?
          if (bestLen < minMatch) {
            srcIdx = srcIdx1 + (srcInc >> 6);
            srcInc++;
            repIdx = 0;
            continue;
          }

          if ((ref != srcIdx - repd[0]) && (ref != srcIdx - repd[1])) {
            // Check if better match at next position
            final int h1 = hash(src, srcIdx1);
            final int ref1 = this.hashes[h1];
            this.hashes[h1] = srcIdx1;

            if ((ref1 > minRef + 1)
                && (differentInts(src, ref1 + bestLen - 3, srcIdx1 + bestLen - 3) == false)) {
              final int maxMatch = Math.min(srcEnd - srcIdx1, MAX_MATCH);
              final int bestLen1 = findMatch(src, srcIdx1, ref1, maxMatch);

              // Select best match
              if (bestLen1 >= bestLen) {
                ref = ref1;
                bestLen = bestLen1;
                srcIdx = srcIdx1;
              }
            }

            if (this.extra == true) {
              final int srcIdx2 = srcIdx1 + 1;
              final int h2 = hash(src, srcIdx2);
              final int ref2 = this.hashes[h2];
              this.hashes[h2] = srcIdx2;

              if ((ref2 > minRef + 2)
                  && (differentInts(src, ref2 + bestLen - 3, srcIdx2 + bestLen - 3) == false)) {
                final int maxMatch = Math.min(srcEnd - srcIdx2, MAX_MATCH);
                final int bestLen2 = findMatch(src, srcIdx2, ref2, maxMatch);

                // Select best match
                if (bestLen2 >= bestLen) {
                  ref = ref2;
                  bestLen = bestLen2;
                  srcIdx = srcIdx2;
                }
              }
            }
          }

          // Extend backwards
          while ((srcIdx > anchor) && (ref > minRef) && (src[srcIdx - 1] == src[ref - 1])) {
            bestLen++;
            ref--;
            srcIdx--;
          }

          if (bestLen > MAX_MATCH) {
            ref += (bestLen - MAX_MATCH);
            srcIdx += (bestLen - MAX_MATCH);
            bestLen = MAX_MATCH;
          }
        } else {
          if ((bestLen >= MAX_MATCH) || (src[srcIdx] != src[ref - 1])) {
            srcIdx++;
            final int h1 = hash(src, srcIdx);
            this.hashes[h1] = srcIdx;
          } else {
            bestLen++;
            ref--;
          }
        }

        // Emit match
        srcInc = 0;

        // Token: 3 bits litLen + 2 bits flag + 3 bits mLen (LLLFFMMM)
        // or 3 bits litLen + 3 bits flag + 2 bits mLen (LLLFFFMM)
        // LLL : <= 7 --> LLL == literal length (if 7, remainder encoded outside of
        // token)
        // MMM : <= 7 --> MMM == match length (if 7, remainder encoded outside of token)
        // MM : <= 3 --> MM == match length (if 3, remainder encoded outside of token)
        // FF = 01 --> 1 byte dist
        // FF = 10 --> 2 byte dist
        // FF = 11 --> 3 byte dist
        // FFF = 000 --> dist == repd0
        // FFF = 001 --> dist == repd1
        final int dist = srcIdx - ref;
        int token, mLenTh;

        if (dist == repd[0]) {
          token = 0x00;
          mLenTh = 3;
        } else if (dist == repd[1]) {
          token = 0x04;
          mLenTh = 3;
        } else {
          // Emit distance (since not repeat)
          this.mBuf[mIdx] = (byte) (dist >> 16);
          final int inc1 = dist >= 65536 ? 1 : 0;
          mIdx += inc1;
          this.mBuf[mIdx] = (byte) (dist >> 8);
          final int inc2 = dist >= 256 ? 1 : 0;
          mIdx += inc2;
          this.mBuf[mIdx++] = (byte) dist;
          token = (inc1 + inc2 + 1) << 3;
          mLenTh = 7;
        }

        // Emit match length
        final int mLen = bestLen - minMatch;

        if (mLen >= mLenTh) {
          token += mLenTh;
          mLenIdx = emitLength(this.mLenBuf, mLenIdx, mLen - mLenTh);
        } else {
          token += mLen;
        }

        repd[1] = repd[0];
        repd[0] = dist;
        repIdx = 1;
        final int litLen = srcIdx - anchor;

        // Emit token
        // Literals to process ?
        if (litLen == 0) {
          this.tkBuf[tkIdx++] = (byte) token;
        } else {
          // Emit literal length
          if (litLen >= 7) {
            if (litLen >= (1 << 24))
              return false;

            this.tkBuf[tkIdx++] = (byte) ((7 << 5) | token);
            dstIdx = emitLength(dst, dstIdx, litLen - 7);
          } else {
            this.tkBuf[tkIdx++] = (byte) ((litLen << 5) | token);
          }

          // Emit literals
          emitLiterals(src, anchor, dst, dstIdx, litLen);
          dstIdx += litLen;
        }

        if (mIdx >= this.mBuf.length - 8) {
          // Expand match buffer
          byte[] buf1 = new byte[(this.mBuf.length * 3) / 2];
          System.arraycopy(this.mBuf, 0, buf1, 0, this.mBuf.length);
          this.mBuf = buf1;

          if (mLenIdx >= this.mLenBuf.length - 4) {
            byte[] buf2 = new byte[(this.mLenBuf.length * 3) / 2];
            System.arraycopy(this.mLenBuf, 0, buf2, 0, this.mLenBuf.length);
            this.mLenBuf = buf2;
          }
        }

        // Fill this.hashes and update positions
        anchor = srcIdx + bestLen;

        while (srcIdx + 4 < anchor) {
          srcIdx += 4;
          this.hashes[hash(src, srcIdx - 3)] = srcIdx - 3;
          this.hashes[hash(src, srcIdx - 2)] = srcIdx - 2;
          this.hashes[hash(src, srcIdx - 1)] = srcIdx - 1;
          this.hashes[hash(src, srcIdx - 0)] = srcIdx;
        }

        while (++srcIdx < anchor)
          this.hashes[hash(src, srcIdx)] = srcIdx;
      }

      // Emit last literals
      final int litLen = count - anchor;

      if (dstIdx + litLen + tkIdx + mIdx >= output.index + count)
        return false;

      if (litLen >= 7) {
        this.tkBuf[tkIdx++] = (byte) (7 << 5);
        dstIdx = emitLength(dst, dstIdx, litLen - 7);
      } else {
        this.tkBuf[tkIdx++] = (byte) (litLen << 5);
      }

      System.arraycopy(src, anchor, dst, dstIdx, litLen);
      dstIdx += litLen;

      // Emit buffers: literals + tokens + matches
      Memory.LittleEndian.writeInt32(dst, dstIdx0, dstIdx);
      Memory.LittleEndian.writeInt32(dst, dstIdx0 + 4, tkIdx);
      Memory.LittleEndian.writeInt32(dst, dstIdx0 + 8, mIdx);
      System.arraycopy(this.tkBuf, 0, dst, dstIdx, tkIdx);
      dstIdx += tkIdx;
      System.arraycopy(this.mBuf, 0, dst, dstIdx, mIdx);
      dstIdx += mIdx;
      System.arraycopy(this.mLenBuf, 0, dst, dstIdx, mLenIdx);
      dstIdx += mLenIdx;
      input.index = count;
      output.index = dstIdx;
      return dstIdx <= count - (count / 100);
    }

    @Override
    /**
     * <p>
     * Decodes the provided data in the source slice and puts the result in the destination slice.
     * </p>
     *
     * @param input The source slice of bytes.
     * @param output The destination slice of bytes.
     * @return {@code true} if the decoding was successful, {@code false} otherwise.
     */
    public boolean inverse(SliceByteArray input, SliceByteArray output) {
      if (this.bsVersion < 6)
        return inverseV5(input, output); // old encoding bitstream version < 6

      return inverseV6(input, output);
    }

    /**
     * <p>
     * Decodes the provided data in the source slice and puts the result in the destination slice
     * (version 6).
     * </p>
     *
     * @param input The source slice of bytes.
     * @param output The destination slice of bytes.
     * @return {@code true} if the decoding was successful, {@code false} otherwise.
     */
    public boolean inverseV6(SliceByteArray input, SliceByteArray output) {
      if (input.length == 0)
        return true;

      if (input.length < 13)
        return false;

      final int count = input.length;
      final int srcIdx0 = input.index;
      final int dstIdx0 = output.index;
      final byte[] src = input.array;
      final byte[] dst = output.array;
      final int dstEnd = dst.length;
      int tkIdx = Memory.LittleEndian.readInt32(src, srcIdx0);
      int mIdx = Memory.LittleEndian.readInt32(src, srcIdx0 + 4);
      int mLenIdx = Memory.LittleEndian.readInt32(src, srcIdx0 + 8);

      if ((tkIdx < srcIdx0) || (mIdx < srcIdx0) || (mLenIdx < srcIdx0))
        return false;

      mIdx += tkIdx;
      mLenIdx += mIdx;

      if ((tkIdx > srcIdx0 + count) || (mIdx > srcIdx0 + count) || (mLenIdx > srcIdx0 + count))
        return false;

      final int srcEnd = srcIdx0 + tkIdx - 13;
      final int maxDist = ((src[srcIdx0 + 12] & 1) == 0) ? MAX_DISTANCE1 : MAX_DISTANCE2;
      final int minMatch = ((src[srcIdx0 + 12] >> 1) & 0x07) + 2;
      int srcIdx = srcIdx0 + 13;
      int dstIdx = dstIdx0;
      int repd0 = 0;
      int repd1 = 0;
      SliceByteArray sba1 = new SliceByteArray(src, srcIdx);
      SliceByteArray sba2 = new SliceByteArray(src, mLenIdx);

      while (true) {
        final int token = src[tkIdx++] & 0xFF;

        if (token >= 32) {
          // Get literal length
          sba1.index = srcIdx;
          final int litLen = (token >= 0xE0) ? 7 + readLength(sba1) : token >> 5;
          srcIdx = sba1.index;

          // Emit literals
          if (srcIdx + litLen >= srcEnd) {
            System.arraycopy(src, srcIdx, dst, dstIdx, litLen);
          } else {
            emitLiterals(src, srcIdx, dst, dstIdx, litLen);
          }

          srcIdx += litLen;
          dstIdx += litLen;

          if (srcIdx >= srcEnd)
            break;
        }

        // Get match length and distance
        int mLen, dist;
        final int f = token & 0x18;

        if (f == 0) {
          // Repetition distance, read mLen fully outside of token
          mLen = token & 0x03;
          sba2.index = mLenIdx;
          mLen += (mLen == 3) ? minMatch + readLength(sba2) : minMatch;
          mLenIdx = sba2.index;
          dist = ((token & 0x04) == 0) ? repd0 : repd1;
        } else {
          // Read mLen remainder (if any) outside of token
          mLen = token & 0x07;
          sba2.index = mLenIdx;
          mLen += (mLen == 7 ? minMatch + readLength(sba2) : minMatch);
          mLenIdx = sba2.index;
          dist = src[mIdx++] & 0xFF;

          if (f == 0x18) {
            dist = (dist << 8) | (src[mIdx++] & 0xFF);
            dist = (dist << 8) | (src[mIdx++] & 0xFF);
          } else if (f == 0x10) {
            dist = (dist << 8) | (src[mIdx++] & 0xFF);
          }
        }

        repd1 = repd0;
        repd0 = dist;
        final int mEnd = dstIdx + mLen;
        int ref = dstIdx - dist;

        // Sanity check
        if ((ref < dstIdx0) || (dist > maxDist) || (mEnd > dstEnd)) {
          input.index = srcIdx;
          output.index = dstIdx;
          return false;
        }

        // Copy match
        if (dist >= 16) {
          do {
            // No overlap
            System.arraycopy(dst, ref, dst, dstIdx, 16);
            ref += 16;
            dstIdx += 16;
          } while (dstIdx < mEnd);
        } else {
          for (int i = 0; i < mLen; i++)
            dst[dstIdx + i] = dst[ref + i];
        }

        dstIdx = mEnd;
      }

      output.index = dstIdx;
      input.index = mIdx;
      return srcIdx == srcEnd + 13;
    }

    /**
     * <p>
     * Decodes the provided data in the source slice and puts the result in the destination slice
     * (version 5).
     * </p>
     *
     * @param input The source slice of bytes.
     * @param output The destination slice of bytes.
     * @return {@code true} if the decoding was successful, {@code false} otherwise.
     */
    public boolean inverseV5(SliceByteArray input, SliceByteArray output) {
      if (input.length == 0)
        return true;

      if (input.length < 13)
        return false;

      final int count = input.length;
      final int srcIdx0 = input.index;
      final int dstIdx0 = output.index;
      final byte[] src = input.array;
      final byte[] dst = output.array;
      final int dstEnd = dst.length;
      int tkIdx = Memory.LittleEndian.readInt32(src, srcIdx0);
      int mIdx = Memory.LittleEndian.readInt32(src, srcIdx0 + 4);
      int mLenIdx = Memory.LittleEndian.readInt32(src, srcIdx0 + 8);

      if ((tkIdx < srcIdx0) || (mIdx < srcIdx0) || (mLenIdx < srcIdx0))
        return false;

      mIdx += tkIdx;
      mLenIdx += mIdx;

      if ((tkIdx > srcIdx0 + count) || (mIdx > srcIdx0 + count) || (mLenIdx > srcIdx0 + count))
        return false;

      final int srcEnd = srcIdx0 + tkIdx - 13;
      final int mFlag = src[srcIdx0 + 12] & 1;
      final int maxDist = (mFlag == 0) ? MAX_DISTANCE1 : MAX_DISTANCE2;
      final int mmIdx = (src[srcIdx0 + 12] >> 1) & 0x03;
      final int[] MIN_MATCHES = {MIN_MATCH4, MIN_MATCH9, MIN_MATCH6, MIN_MATCH6};
      final int minMatch = MIN_MATCHES[mmIdx];
      int srcIdx = srcIdx0 + 13;
      int dstIdx = dstIdx0;
      int repd0 = 0;
      int repd1 = 0;
      SliceByteArray sba1 = new SliceByteArray(src, srcIdx);
      SliceByteArray sba2 = new SliceByteArray(src, mLenIdx);

      while (true) {
        final int token = src[tkIdx++] & 0xFF;

        if (token >= 32) {
          // Get literal length
          sba1.index = srcIdx;
          final int litLen = (token >= 0xE0) ? 7 + readLength(sba1) : token >> 5;
          srcIdx = sba1.index;

          // Emit literals
          if (dstIdx + litLen >= dstEnd) {
            System.arraycopy(src, srcIdx, dst, dstIdx, litLen);
          } else {
            emitLiterals(src, srcIdx, dst, dstIdx, litLen);
          }

          srcIdx += litLen;
          dstIdx += litLen;

          if (srcIdx >= srcEnd)
            break;
        }

        // Get match length and distance
        int mLen = token & 0x0F;
        int dist;

        if (mLen == 15) {
          // Repetition distance, read mLen fully outside of token
          sba2.index = mLenIdx;
          mLen = minMatch + readLength(sba2);
          mLenIdx = sba2.index;
          dist = ((token & 0x10) == 0) ? repd0 : repd1;
        } else {
          if (mLen == 14) {
            // Read mLen remainder (if any) outside of token
            sba2.index = mLenIdx;
            mLen = 14 + readLength(sba2);
            mLenIdx = sba2.index;
          }

          mLen += minMatch;
          dist = src[mIdx++] & 0xFF;

          if (mFlag != 0)
            dist = (dist << 8) | (src[mIdx++] & 0xFF);

          if ((token & 0x10) != 0)
            dist = (dist << 8) | (src[mIdx++] & 0xFF);
        }

        repd1 = repd0;
        repd0 = dist;
        final int mEnd = dstIdx + mLen;
        int ref = dstIdx - dist;

        // Sanity check
        if ((ref < dstIdx0) || (dist > maxDist) || (mEnd > dstEnd)) {
          input.index = srcIdx;
          output.index = dstIdx;
          return false;
        }

        // Copy match
        if (dist >= 16) {
          do {
            // No overlap
            System.arraycopy(dst, ref, dst, dstIdx, 16);
            ref += 16;
            dstIdx += 16;
          } while (dstIdx < mEnd);
        } else {
          for (int i = 0; i < mLen; i++)
            dst[dstIdx + i] = dst[ref + i];
        }

        dstIdx = mEnd;
      }

      output.index = dstIdx;
      input.index = mIdx;
      return srcIdx == srcEnd + 13;
    }

    private int hash(byte[] block, int idx) {
      if (this.extra == true)
        return (int) (((Memory.LittleEndian.readLong64(block, idx) << HASH_LSHIFT2)
            * HASH_SEED) >>> HASH_RSHIFT2);

      return (int) (((Memory.LittleEndian.readLong64(block, idx) << HASH_LSHIFT1)
          * HASH_SEED) >>> HASH_RSHIFT1);
    }

    /**
     * <p>
     * Copies 8 bytes from source to destination.
     * </p>
     *
     * @param src The source byte array.
     * @param srcIdx The starting index in the source array.
     * @param dst The destination byte array.
     * @param dstIdx The starting index in the destination array.
     */
    private static void arrayChunkCopy(byte[] src, int srcIdx, byte[] dst, int dstIdx) {
      dst[dstIdx] = src[srcIdx];
      dst[dstIdx + 1] = src[srcIdx + 1];
      dst[dstIdx + 2] = src[srcIdx + 2];
      dst[dstIdx + 3] = src[srcIdx + 3];
      dst[dstIdx + 4] = src[srcIdx + 4];
      dst[dstIdx + 5] = src[srcIdx + 5];
      dst[dstIdx + 6] = src[srcIdx + 6];
      dst[dstIdx + 7] = src[srcIdx + 7];
    }

    /**
     * <p>
     * Emits literals from source to destination.
     * </p>
     *
     * @param src The source byte array.
     * @param srcIdx The starting index in the source array.
     * @param dst The destination byte array.
     * @param dstIdx The starting index in the destination array.
     * @param len The number of literals to emit.
     */
    private static void emitLiterals(byte[] src, int srcIdx, byte[] dst, int dstIdx, int len) {
      for (int i = 0; i < len; i += 8)
        arrayChunkCopy(src, srcIdx + i, dst, dstIdx + i);
    }

    @Override
    /**
     * <p>
     * Returns the maximum length of the encoded data for a given source length.
     * </p>
     *
     * @param srcLen The length of the source data.
     * @return The maximum length of the encoded data.
     */
    public int getMaxEncodedLength(int srcLen) {
      return (srcLen <= 1024) ? srcLen + 16 : srcLen + (srcLen / 64);
    }
  }

  /**
   * <p>
   * Implementation of a byte oriented LZP codec.
   * </p>
   */

  static final class LZPCodec implements ByteTransform {
    private static final int HASH_SEED = 0x7FEB352D;
    private static final int HASH_LOG = 16;
    private static final int HASH_SHIFT = 32 - HASH_LOG;
    private static final int MIN_MATCH96 = 96;
    private static final int MIN_MATCH64 = 64;
    private static final int MIN_BLOCK_LENGTH = 128;
    private static final int MATCH_FLAG = 0xFC;

    private int[] hashes;
    private final boolean isBsVersion3;

    /**
     * <p>
     * Creates a new {@link LZPCodec} instance.
     * </p>
     */
    public LZPCodec() {
      this.hashes = new int[0];
      this.isBsVersion3 = false;
    }

    public LZPCodec(Map<String, Object> ctx)
    /**
     * <p>
     * Creates a new {@link LZPCodec} instance with the given context.
     * </p>
     *
     * @param ctx A map of parameters to configure the LZP codec.
     */
    {
      this.hashes = new int[0];
      int bsVersion = 6;

      if (ctx != null)
        bsVersion = (Integer) ctx.getOrDefault("bsVersion", 6);

      this.isBsVersion3 = bsVersion < 4;
    }

    @Override
    /**
     * <p>
     * Encodes the provided data in the source slice and puts the result in the destination slice.
     * </p>
     *
     * @param input The source slice of bytes.
     * @param output The destination slice of bytes.
     * @return {@code true} if the encoding was successful, {@code false} otherwise.
     */
    public boolean forward(SliceByteArray input, SliceByteArray output) {
      if (input.length == 0)
        return true;

      final int count = input.length;

      if (output.length - output.index < this.getMaxEncodedLength(count))
        return false;

      // If too small, skip
      if (count < MIN_BLOCK_LENGTH)
        return false;

      if (this.hashes.length == 0) {
        this.hashes = new int[1 << HASH_LOG];
      } else {
        for (int i = 0; i < (1 << HASH_LOG); i++)
          this.hashes[i] = 0;
      }

      final int srcIdx0 = input.index;
      final int dstIdx0 = output.index;
      final byte[] src = input.array;
      final byte[] dst = output.array;
      final int srcEnd = srcIdx0 + count;
      final int dstEnd = dstIdx0 + count - (count >> 6);
      int srcIdx = srcIdx0;
      int dstIdx = dstIdx0;

      dst[dstIdx] = src[srcIdx];
      dst[dstIdx + 1] = src[srcIdx + 1];
      dst[dstIdx + 2] = src[srcIdx + 2];
      dst[dstIdx + 3] = src[srcIdx + 3];
      int ctx = Memory.LittleEndian.readInt32(src, srcIdx);
      srcIdx += 4;
      dstIdx += 4;
      final int minMatch = MIN_MATCH64;

      while ((srcIdx < srcEnd - minMatch) && (dstIdx < dstEnd)) {
        final int h = (HASH_SEED * ctx) >>> HASH_SHIFT;
        final int ref = this.hashes[h];
        this.hashes[h] = srcIdx;
        int bestLen = 0;

        // Find a match
        if ((ref != 0)
            && (LZCodec.differentInts(src, ref + minMatch - 4, srcIdx + minMatch - 4) == false)) {
          bestLen = findMatch(src, srcIdx, ref, srcEnd - srcIdx);
        }

        // No good match ?
        if (bestLen < minMatch) {
          final int val = src[srcIdx] & 0xFF;
          ctx = (ctx << 8) | val;
          dst[dstIdx++] = src[srcIdx++];

          if ((ref != 0) && (val == MATCH_FLAG))
            dst[dstIdx++] = (byte) 0xFF;

          continue;
        }

        srcIdx += bestLen;
        ctx = Memory.LittleEndian.readInt32(src, srcIdx - 4);
        dst[dstIdx++] = (byte) MATCH_FLAG;
        bestLen -= minMatch;

        // Emit match length
        while (bestLen >= 254) {
          bestLen -= 254;
          dst[dstIdx++] = (byte) 0xFE;

          if (dstIdx >= dstEnd)
            break;
        }

        dst[dstIdx++] = (byte) bestLen;
      }

      while ((srcIdx < srcEnd) && (dstIdx < dstEnd)) {
        final int h = (HASH_SEED * ctx) >>> HASH_SHIFT;
        final int ref = this.hashes[h];
        this.hashes[h] = srcIdx;
        final int val = src[srcIdx] & 0xFF;
        ctx = (ctx << 8) | val;
        dst[dstIdx++] = src[srcIdx++];

        if ((ref != 0) && (val == MATCH_FLAG))
          dst[dstIdx++] = (byte) 0xFF;
      }

      input.index = srcIdx;
      output.index = dstIdx;
      return (srcIdx == count) && (dstIdx < dstEnd);
    }

    @Override
    /**
     * <p>
     * Decodes the provided data in the source slice and puts the result in the destination slice.
     * </p>
     *
     * @param input The source slice of bytes.
     * @param output The destination slice of bytes.
     * @return {@code true} if the decoding was successful, {@code false} otherwise.
     */
    public boolean inverse(SliceByteArray input, SliceByteArray output) {
      if (input.length == 0)
        return true;

      final int count = input.length;
      final byte[] src = input.array;
      final byte[] dst = output.array;
      final int srcEnd = input.index + count;
      final int dstEnd = output.length;
      int srcIdx = input.index;
      int dstIdx = output.index;
      final int minMatch = (this.isBsVersion3 == true) ? MIN_MATCH96 : MIN_MATCH64;

      if (this.hashes.length == 0) {
        this.hashes = new int[1 << HASH_LOG];
      } else {
        for (int i = 0; i < (1 << HASH_LOG); i++)
          this.hashes[i] = 0;
      }

      dst[dstIdx] = src[srcIdx];
      dst[dstIdx + 1] = src[srcIdx + 1];
      dst[dstIdx + 2] = src[srcIdx + 2];
      dst[dstIdx + 3] = src[srcIdx + 3];
      int ctx = Memory.LittleEndian.readInt32(dst, dstIdx);
      srcIdx += 4;
      dstIdx += 4;

      while (srcIdx < srcEnd) {
        final int h = (HASH_SEED * ctx) >>> HASH_SHIFT;
        final int ref = this.hashes[h];
        this.hashes[h] = dstIdx;

        if ((ref == 0) || (src[srcIdx] != (byte) MATCH_FLAG)) {
          dst[dstIdx] = src[srcIdx];
          ctx = (ctx << 8) | (dst[dstIdx] & 0xFF);
          srcIdx++;
          dstIdx++;
          continue;
        }

        srcIdx++;

        if (src[srcIdx] == (byte) 0xFF) {
          dst[dstIdx] = (byte) MATCH_FLAG;
          ctx = (ctx << 8) | MATCH_FLAG;
          srcIdx++;
          dstIdx++;
          continue;
        }

        int mLen = minMatch;

        if (src[srcIdx] == (byte) 0xFE) {
          while ((srcIdx < srcEnd) && (src[srcIdx] == (byte) 0xFE)) {
             srcIdx++;
             mLen += 254;
          }
 
          if (srcIdx >= srcEnd)
            return false;
        }

        mLen += (src[srcIdx++] & 0xFF);

        if (dstIdx + mLen > dstEnd)
          return false;

        if (ref + mLen < dstIdx) {
          System.arraycopy(dst, ref, dst, dstIdx, mLen);
        } else {
          for (int i = 0; i < mLen; i++)
            dst[dstIdx + i] = dst[ref + i];
        }

        dstIdx += mLen;
        ctx = Memory.LittleEndian.readInt32(dst, dstIdx - 4);
      }

      input.index = srcIdx;
      output.index = dstIdx;
      return srcIdx == srcEnd;
    }

    /**
     * <p>
     * Finds the length of the longest match between two sequences.
     * </p>
     *
     * @param src The source byte array.
     * @param srcIdx The starting index of the first sequence.
     * @param ref The starting index of the second sequence.
     * @param maxMatch The maximum length to check for a match.
     * @return The length of the longest match.
     */
    private static int findMatch(byte[] src, final int srcIdx, final int ref, final int maxMatch) {
      int bestLen = 0;

      while (bestLen + 8 <= maxMatch) {
        final long diff = Memory.LittleEndian.readLong64(src, srcIdx + bestLen)
            ^ Memory.LittleEndian.readLong64(src, ref + bestLen);

        if (diff != 0) {
          bestLen += (Long.numberOfTrailingZeros(diff) >> 3);
          break;
        }

        bestLen += 8;
      }

      return bestLen;
    }

    @Override
    /**
     * <p>
     * Returns the maximum length of the encoded data for a given source length.
     * </p>
     *
     * @param srcLen The length of the source data.
     * @return The maximum length of the encoded data.
     */
    public int getMaxEncodedLength(int srcLen) {
      return (srcLen <= 1024) ? srcLen + 16 : srcLen + (srcLen / 64);
    }
  }
}
