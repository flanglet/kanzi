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

import io.github.flanglet.kanzi.ByteTransform;
import io.github.flanglet.kanzi.SliceByteArray;

/**
 * Encapsulates a sequence of transforms in a transform.
 */
public class Sequence implements ByteTransform {
  private static final byte SKIP_MASK = -1;

  private final ByteTransform[] transforms; // transforms or functions
  private byte skipFlags; // skip transforms

  /**
   * Constructor with an array of transforms.
   *
   * @param transforms the array of transforms
   */
  public Sequence(ByteTransform[] transforms) {
    if (transforms == null)
      throw new NullPointerException("Invalid null transforms parameter");

    if ((transforms.length < 1) || (transforms.length > 8))
      throw new IllegalArgumentException("Only 1 to 8 transforms allowed");

    this.transforms = transforms;
  }

  /**
   * Performs the forward transform, encoding the input data.
   *
   * @param src the input byte array
   * @param dst the output byte array
   * @return true if the transform was successful, false otherwise
   */
  @Override
  public boolean forward(SliceByteArray src, SliceByteArray dst) {
    int count = src.length;

    if ((count < 0) || (count + src.index > src.array.length))
      return false;

    this.skipFlags = SKIP_MASK;

    if (src.length == 0)
      return true;

    if ((src.index < 0) || (dst.index < 0) || (src.length < 0)
        || ((long) src.index + src.length > src.array.length)
        || (dst.index > dst.array.length))
      return false;

    final int blockSize = count;
    final int requiredSize = this.getMaxEncodedLength(count);
    SliceByteArray[] sa = new SliceByteArray[] {src, dst};
    SliceByteArray sa1 = sa[0];
    SliceByteArray sa2 = sa[1];
    int saIdx = 0;

    // Process transforms sequentially
    for (int i = 0; i < this.transforms.length; i++) {
      // Check that the output buffer has enough room. If not, allocate a new one.
      if (sa2.length < requiredSize) {
        sa2.length = requiredSize;

        if (sa2.array.length < sa2.length)
          sa2.array = new byte[sa2.length];
      }

      final int savedIIdx = sa1.index;
      final int savedOIdx = sa2.index;
      final int savedLength = sa1.length;
      sa1.length = count;

      // Apply forward transform
      if (this.transforms[i].forward(sa1, sa2) == false) {
        // Transform failed. Either it does not apply to this type
        // of data or a recoverable error occurred => revert
        if (sa1.array != sa2.array)
          System.arraycopy(sa1.array, savedIIdx, sa2.array, savedOIdx, count);

        sa1.index = savedIIdx;
        sa2.index = savedOIdx;
        sa1.length = savedLength;
        continue;
      }

      this.skipFlags &= ~(1 << (7 - i));
      count = sa2.index - savedOIdx;
      sa1.index = savedIIdx;
      sa2.index = savedOIdx;
      sa1.length = savedLength;
      saIdx ^= 1;
      sa1 = sa[saIdx];
      sa2 = sa[saIdx ^ 1];
    }

    if (saIdx != 1) {
      if (sa[1].index + count > sa[1].array.length)
        this.skipFlags = SKIP_MASK;
      else
        System.arraycopy(sa[0].array, sa[0].index, sa[1].array, sa[1].index, count);
    }

    src.index += blockSize;
    dst.index += count;
    return this.skipFlags != SKIP_MASK;
  }

  /**
   * Performs the inverse transform, decoding the input data.
   *
   * @param src the input byte array
   * @param dst the output byte array
   * @return true if the transform was successful, false otherwise
   */
  @Override
  public boolean inverse(SliceByteArray src, SliceByteArray dst) {
    if (src.length == 0)
      return true;

    if ((src.index < 0) || (dst.index < 0) || (src.length < 0)
        || ((long) src.index + src.length > src.array.length)
        || (dst.index > dst.array.length))
      return false;

    int count = src.length;

    if (this.skipFlags == SKIP_MASK) {
      if (src.array != dst.array)
        System.arraycopy(src.array, src.index, dst.array, dst.index, count);

      src.index += count;
      dst.index += count;
      return true;
    }

    final int blockSize = count;
    boolean res = true;
    SliceByteArray[] sa = new SliceByteArray[] {src, dst};
    int saIdx = 0;

    // Process transforms sequentially in reverse order
    for (int i = this.transforms.length - 1; i >= 0; i--) {
      if ((this.skipFlags & (1 << (7 - i))) != 0)
        continue;

      SliceByteArray sa1 = sa[saIdx];
      saIdx ^= 1;
      SliceByteArray sa2 = sa[saIdx];
      final int savedIIdx = sa1.index;
      final int savedOIdx = sa2.index;
      final int savedILen = sa1.length;
      final int savedOLen = sa2.length;

      // Apply inverse transform
      sa1.length = count;
      sa2.length = dst.array.length;

      if (sa2.array.length < sa2.length)
        sa2.array = new byte[sa2.length];

      res = this.transforms[i].inverse(sa1, sa2);
      count = sa2.index - savedOIdx;
      sa1.index = savedIIdx;
      sa2.index = savedOIdx;
      sa1.length = savedILen;
      sa2.length = savedOLen;

      // All inverse transforms must succeed
      if (res == false)
        break;
    }

    if ((res == true) && (saIdx != 1)) {
      if (sa[1].index + count > sa[1].array.length)
        res = false;
      else
        System.arraycopy(sa[0].array, sa[0].index, sa[1].array, sa[1].index, count);
    }

    if (count > dst.length)
      return false;

    src.index += blockSize;
    dst.index += count;
    return res;
  }

  /**
   * Returns the maximum encoded length, which includes some extra buffer for incompressible data.
   *
   * @param srcLength the source length
   * @return the maximum encoded length
   */
  @Override
  public int getMaxEncodedLength(int srcLength) {
    int requiredSize = srcLength;

    for (ByteTransform t : this.transforms) {
      if (t == null)
        continue;

      requiredSize = Math.max(requiredSize, t.getMaxEncodedLength(requiredSize));
    }

    return requiredSize;
  }

  /**
   * Returns the number of functions in the sequence.
   *
   * @return the number of functions
   */
  public int getNbFunctions() {
    return this.transforms.length;
  }

  /**
   * Returns the skip flags indicating which transforms to skip.
   *
   * @return the skip flags
   */
  public byte getSkipFlags() {
    return this.skipFlags;
  }

  /**
   * Sets the skip flags indicating which transforms to skip.
   *
   * @param flags the skip flags
   * @return true if the flags were set successfully, false otherwise
   */
  public boolean setSkipFlags(byte flags) {
    this.skipFlags = flags;
    return true;
  }
}
