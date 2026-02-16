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

import java.util.Map;
import io.github.flanglet.kanzi.ByteTransform;
import io.github.flanglet.kanzi.Global;
import io.github.flanglet.kanzi.SliceByteArray;

/**
 * Zero Run Length Encoding is a simple encoding algorithm by Wheeler closely related to Run Length
 * Encoding. The main difference is that only runs of 0 values are processed. Also, the length is
 * encoded in a different way (each digit in a different byte). This algorithm is well adapted to
 * process post BWT/MTFT data.
 */
public final class ZRLT implements ByteTransform {

  /**
   * Default constructor.
   */
  public ZRLT() {}

  /**
   * Constructor with a context map.
   *
   * @param ctx the context map
   */
  public ZRLT(Map<String, Object> ctx) {}

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

    if ((input.index < 0) || (output.index < 0) || (input.length < 0)
        || ((long) input.index + input.length > input.array.length)
        || (output.index > output.array.length))
      return false;

    if (input.array == output.array)
      return false;

    final int count = input.length;

    if (output.length - output.index < getMaxEncodedLength(count))
      return false;

    final byte[] src = input.array;
    final byte[] dst = output.array;
    int srcIdx = input.index;
    int dstIdx = output.index;
    final int srcEnd = srcIdx + count;
    final int dstEnd = dstIdx + count; // do not expand
    boolean res = true;

    if (dstIdx < dstEnd) {
      while (srcIdx < srcEnd) {
        if (src[srcIdx] == 0) {
          int runLength = 1;

          while ((srcIdx + runLength < srcEnd) && (src[srcIdx + runLength] == src[srcIdx]))
            runLength++;

          srcIdx += runLength;

          // Encode length
          runLength++;
          int log2 = (runLength <= 256) ? Global.LOG2_VALUES[runLength - 1]
              : 31 - Integer.numberOfLeadingZeros(runLength);

          if (dstIdx >= dstEnd - log2) {
            res = false;
            break;
          }

          // Write every bit as a byte except the most significant one
          while (log2 > 0) {
            log2--;
            dst[dstIdx++] = (byte) ((runLength >> log2) & 1);
          }

          continue;
        }

        final int val = src[srcIdx] & 0xFF;

        if (val >= 0xFE) {
          if (dstIdx >= dstEnd - 1) {
            res = false;
            break;
          }

          dst[dstIdx] = (byte) 0xFF;
          dst[dstIdx + 1] = (byte) (val - 0xFE);
          dstIdx += 2;
        } else {
          if (dstIdx >= dstEnd) {
            res = false;
            break;
          }

          dst[dstIdx] = (byte) (val + 1);
          dstIdx++;
        }

        srcIdx++;
      }
    }

    input.index = srcIdx;
    output.index = dstIdx;
    return res && (srcIdx == srcEnd);
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

    if ((input.index < 0) || (output.index < 0) || (input.length < 0)
        || ((long) input.index + input.length > input.array.length)
        || (output.index > output.array.length))
      return false;

    if (input.array == output.array)
      return false;

    final int count = input.length;
    int srcIdx = input.index;
    int dstIdx = output.index;
    final byte[] src = input.array;
    final byte[] dst = output.array;
    final int srcEnd = srcIdx + count;
    final int dstEnd = output.length;
    int runLength = 0;

    mainLoop: while (true) {
      int val = src[srcIdx] & 0xFF;

      if (val <= 1) {
        // Generate the run length bit by bit (but force MSB)
        runLength = 1;

        do {
          runLength += (runLength + val);
          srcIdx++;

          if (srcIdx >= srcEnd)
            break mainLoop;

          val = src[srcIdx] & 0xFF;
        } while (val <= 1);

        runLength--;

        if (runLength > 0) {
          if (dstIdx + runLength >= dstEnd)
            break;

          while (runLength > 0) {
            runLength--;
            dst[dstIdx++] = 0;
          }
        }
      }

      // Regular data processing
      if (val == 0xFF) {
        srcIdx++;

        if (srcIdx >= srcEnd)
          break;

        dst[dstIdx] = (byte) (0xFE + src[srcIdx]);
      } else {
        dst[dstIdx] = (byte) (val - 1);
      }

      srcIdx++;
      dstIdx++;

      if ((srcIdx >= srcEnd) || (dstIdx >= dstEnd))
        break;
    }

    // If runLength is not 1, add trailing 0s
    if (runLength > 0) {
      runLength--;

      if (dstIdx + runLength > dstEnd)
        return false;

      while (runLength > 0) {
        runLength--;
        dst[dstIdx++] = 0;
      }

    }

    input.index = srcIdx;
    output.index = dstIdx;
    return srcIdx == srcEnd;
  }

  /**
   * Required encoding output buffer size unknown, so we guess.
   *
   * @param srcLen the source length
   * @return the maximum encoded length
   */
  @Override
  public int getMaxEncodedLength(int srcLen) {
    return srcLen;
  }
}
