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
import io.github.flanglet.kanzi.SliceByteArray;

/**
 * Implementation of an escaped RLE Run length encoding: RUN_LEN_ENCODE1 = 224 =&gt; RUN_LEN_ENCODE2
 * = 31*224 = 6944 4 &lt;= runLen &lt; 224+4 -&gt; 1 byte 228 &lt;= runLen &lt; 6944+228 -&gt; 2
 * bytes 7172 &lt;= runLen &lt; 65535+7172 -&gt; 3 bytes
 */
public class RLT implements ByteTransform {
  private static final int RUN_LEN_ENCODE1 = 224; // used to encode run length
  private static final int RUN_LEN_ENCODE2 = (255 - RUN_LEN_ENCODE1) << 8; // used to encode run
                                                                           // length
  private static final int RUN_THRESHOLD = 3;
  private static final int MAX_RUN = 0xFFFF + RUN_LEN_ENCODE2 + RUN_THRESHOLD - 1;
  private static final int MAX_RUN4 = MAX_RUN - 4;
  private static final byte DEFAULT_ESCAPE = (byte) 0xFB;

  private final int[] freqs;
  private final Map<String, Object> ctx;

  /**
   * Default constructor.
   */
  public RLT() {
    this.freqs = new int[256];
    this.ctx = null;
  }

  /**
   * Constructor with a context map.
   *
   * @param ctx the context map
   */
  public RLT(Map<String, Object> ctx) {
    this.freqs = new int[256];
    this.ctx = ctx;
  }

  /**
   * Performs the forward transform, encoding the input data.
   *
   * @param input the input byte array
   * @param output the output byte array
   * @return true if the transform was successful, false otherwise
   */
  @Override
  public boolean forward(SliceByteArray input, SliceByteArray output) {
    if (input.length == 0)
      return true;

    if (input.length < 16)
      return false;

    if (input.array == output.array)
      return false;

    final int count = input.length;

    if (output.length - output.index < getMaxEncodedLength(count))
      return false;

    final byte[] src = input.array;
    final byte[] dst = output.array;
    Global.DataType dt = Global.DataType.UNDEFINED;
    boolean findBestEscape = true;

    if (this.ctx != null) {
      dt = (Global.DataType) this.ctx.getOrDefault("dataType", Global.DataType.UNDEFINED);

      if ((dt == Global.DataType.DNA) || (dt == Global.DataType.BASE64)
          || (dt == Global.DataType.UTF8))
        return false;

      String entropyType = (String) this.ctx.getOrDefault("entropy", "NONE");
      entropyType = entropyType.toUpperCase();

      // Fast track if entropy coder is used
      if (entropyType.equals("NONE") || entropyType.equals("ANS0") || entropyType.equals("HUFFMAN")
          || entropyType.equals("RANGE"))
        findBestEscape = false;
    }

    byte escape = DEFAULT_ESCAPE;
    int srcIdx = input.index;
    int dstIdx = output.index;
    final int srcEnd = srcIdx + count;
    final int srcEnd4 = srcEnd - 4;
    final int dstEnd = dst.length;

    if (findBestEscape == true) {
      for (int i = 0; i < 256; i++)
        this.freqs[i] = 0;

      Global.computeHistogramOrder0(src, srcIdx, srcEnd, this.freqs, false);

      if (dt == Global.DataType.UNDEFINED) {
        dt = Global.detectSimpleType(count, this.freqs);

        if ((this.ctx != null) && (dt != Global.DataType.UNDEFINED))
          this.ctx.put("dataType", dt);

        if ((dt == Global.DataType.DNA) || (dt == Global.DataType.BASE64)
            || (dt == Global.DataType.UTF8))
          return false;
      }

      int minIdx = 0;

      // Select escape symbol
      if (this.freqs[minIdx] > 0) {
        for (int i = 1; i < 256; i++) {
          if (this.freqs[i] < this.freqs[minIdx]) {
            minIdx = i;

            if (this.freqs[i] == 0)
              break;
          }
        }
      }

      escape = (byte) minIdx;
    }

    boolean res = true;
    int run = 0;
    byte prev = src[srcIdx++];
    dst[dstIdx++] = escape;
    dst[dstIdx++] = prev;

    if (prev == escape)
      dst[dstIdx++] = 0;

    // Main loop
    while (true) {
      if (prev == src[srcIdx]) {
        srcIdx++;
        run++;

        if (prev == src[srcIdx]) {
          srcIdx++;
          run++;

          if (prev == src[srcIdx]) {
            srcIdx++;
            run++;

            if (prev == src[srcIdx]) {
              srcIdx++;
              run++;

              if ((run < MAX_RUN4) && (srcIdx < srcEnd4))
                continue;
            }
          }
        }
      }

      if (run > RUN_THRESHOLD) {
        if (dstIdx + 6 >= dstEnd) {
          res = false;
          break;
        }

        dst[dstIdx++] = prev;

        if (prev == escape)
          dst[dstIdx++] = (byte) 0;

        dst[dstIdx++] = escape;
        dstIdx = emitRunLength(dst, dstIdx, run);
      } else if (prev != escape) {
        if (dstIdx + run >= dstEnd) {
          res = false;
          break;
        }

        while (run-- > 0)
          dst[dstIdx++] = prev;
      } else { // escape literal
        if (dstIdx + 2 * run >= dstEnd) {
          res = false;
          break;
        }

        while (run-- > 0) {
          dst[dstIdx++] = escape;
          dst[dstIdx++] = 0;
        }
      }

      prev = src[srcIdx];
      srcIdx++;
      run = 1;

      if (srcIdx >= srcEnd4)
        break;
    }

    if (res == true) {
      // run == 1
      if (prev != escape) {
        if (dstIdx + run < dstEnd) {
          while (run-- > 0)
            dst[dstIdx++] = prev;
        }
      } else { // escape literal
        if (dstIdx + 2 * run < dstEnd) {
          while (run-- > 0) {
            dst[dstIdx++] = escape;
            dst[dstIdx++] = 0;
          }
        }
      }

      // Emit the last few bytes
      while ((srcIdx < srcEnd) && (dstIdx < dstEnd)) {
        if (src[srcIdx] == escape) {
          if (dstIdx + 2 >= dstEnd) {
            res = false;
            break;
          }

          dst[dstIdx++] = escape;
          dst[dstIdx++] = 0;
          srcIdx++;
          continue;
        }

        dst[dstIdx++] = src[srcIdx++];
      }

      res &= (srcIdx == srcEnd);
    }

    res &= ((dstIdx - output.index) < (srcIdx - input.index));
    input.index = srcIdx;
    output.index = dstIdx;
    return res;
  }

  /**
   * Emits the run length.
   *
   * @param dst the destination byte array
   * @param dstIdx the current index in the destination array
   * @param run the run length to emit
   * @return the updated index in the destination array
   */
  private static int emitRunLength(byte[] dst, int dstIdx, int run) {
    run -= RUN_THRESHOLD;

    if (run >= RUN_LEN_ENCODE1) {
      if (run < RUN_LEN_ENCODE2) {
        run -= RUN_LEN_ENCODE1;
        dst[dstIdx++] = (byte) (RUN_LEN_ENCODE1 + (run >> 8));
      } else {
        run -= RUN_LEN_ENCODE2;
        dst[dstIdx++] = (byte) 0xFF;
        dst[dstIdx++] = (byte) (run >> 8);
      }
    }

    dst[dstIdx] = (byte) run;
    return dstIdx + 1;
  }

  /**
   * Performs the inverse transform, decoding the input data.
   *
   * @param input the input byte array
   * @param output the output byte array
   * @return true if the transform was successful, false otherwise
   */
  @Override
  public boolean inverse(SliceByteArray input, SliceByteArray output) {
    if (input.length == 0)
      return true;

    if (input.array == output.array)
      return false;

    final int count = input.length;
    int srcIdx = input.index;
    int dstIdx = output.index;
    final byte[] src = input.array;
    final byte[] dst = output.array;
    final int srcEnd = srcIdx + count;
    final int dstEnd = dst.length;
    boolean res = true;
    byte escape = src[srcIdx++];

    if (src[srcIdx] == escape) {
      srcIdx++;

      // The data cannot start with a run but may start with an escape literal
      if ((srcIdx < srcEnd) && (src[srcIdx] != 0))
        return false;

      dst[dstIdx++] = escape;
      srcIdx++;
    }

    // Main loop
    while (srcIdx < srcEnd) {
      if (src[srcIdx] != escape) {
        // Literal
        if (dstIdx >= dstEnd)
          break;

        dst[dstIdx++] = src[srcIdx++];
        continue;
      }

      srcIdx++;

      if (srcIdx >= srcEnd) {
        res = false;
        break;
      }

      final byte val = dst[dstIdx - 1];
      int run = src[srcIdx++] & 0xFF;

      if (run == 0) {
        // Just an escape literal, not a run
        if (dstIdx >= dstEnd)
          break;

        dst[dstIdx++] = escape;
        continue;
      }

      // Decode run length
      if (run == 0xFF) {
        if (srcIdx >= srcEnd - 1) {
          res = false;
          break;
        }

        run = ((src[srcIdx] & 0xFF) << 8) | (src[srcIdx + 1] & 0xFF);
        srcIdx += 2;
        run += RUN_LEN_ENCODE2;
      } else if (run >= RUN_LEN_ENCODE1) {
        if (srcIdx >= srcEnd) {
          res = false;
          break;
        }

        run = ((run - RUN_LEN_ENCODE1) << 8) | (src[srcIdx++] & 0xFF);
        run += RUN_LEN_ENCODE1;
      }

      run += (RUN_THRESHOLD - 1);

      if ((dstIdx + run >= dstEnd) || (run > MAX_RUN)) {
        res = false;
        break;
      }

      // Emit 'run' times the previous byte
      while (run >= 4) {
        dst[dstIdx] = val;
        dst[dstIdx + 1] = val;
        dst[dstIdx + 2] = val;
        dst[dstIdx + 3] = val;
        dstIdx += 4;
        run -= 4;
      }

      while (run-- > 0)
        dst[dstIdx++] = val;
    }

    res &= (srcIdx == srcEnd);
    input.index = srcIdx;
    output.index = dstIdx;
    return res;
  }

  /**
   * Returns the maximum encoded length, which includes some extra buffer for incompressible data.
   *
   * @param srcLen the source length
   * @return the maximum encoded length
   */
  @Override
  public int getMaxEncodedLength(int srcLen) {
    return (srcLen <= 512) ? srcLen + 32 : srcLen;
  }
}
