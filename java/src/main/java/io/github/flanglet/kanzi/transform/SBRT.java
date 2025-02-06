/*
 * Copyright 2011-2024 Frederic Langlet
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *                 http://www.apache.org/licenses/LICENSE-2.0
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
import io.github.flanglet.kanzi.SliceByteArray;

/**
 * Sort By Rank Transform is a family of transforms typically used after
 * a BWT to reduce the variance of the data prior to entropy coding.
 * SBR(alpha) is defined by sbr(x, alpha) = (1-alpha)*(t-w1(x,t)) + alpha*(t-w2(x,t))
 * where x is an item in the data list, t is the current access time and wk(x,t) is
 * the k-th access time to x at time t (with alpha in [0..1]).
 * See [Two new families of list update algorithms] by Frank Schulz for details.
 * SBR(0)= Move to Front Transform
 * SBR(1)= Time Stamp Transform
 * This code implements SBR(0), SBR(1/2) and SBR(1). Code derived from openBWT.
 */
public class SBRT implements ByteTransform {
    public static final int MODE_MTF = 1;       // alpha = 0
    public static final int MODE_RANK = 2;      // alpha = 1/2
    public static final int MODE_TIMESTAMP = 3; // alpha = 1

    private final int[] prev;
    private final int[] curr;
    private final int[] symbols;
    private final int[] ranks;
    private final int mode;

    /**
     * Default constructor.
     */
    public SBRT() {
        this(MODE_RANK);
    }

    /**
     * Constructor with mode parameter.
     *
     * @param mode the mode of the transform
     */
    public SBRT(int mode) {
        if ((mode != MODE_MTF) && (mode != MODE_RANK) && (mode != MODE_TIMESTAMP))
            throw new IllegalArgumentException("Invalid mode parameter");

        this.prev = new int[256];
        this.curr = new int[256];
        this.symbols = new int[256];
        this.ranks = new int[256];
        this.mode = mode;
    }

    /**
     * Constructor with a context map.
     *
     * @param ctx the context map
     */
    public SBRT(Map<String, Object> ctx) {
        final int m = (Integer) ctx.getOrDefault("sbrt", MODE_MTF);

        if ((m != MODE_MTF) && (m != MODE_RANK) && (m != MODE_TIMESTAMP))
            throw new IllegalArgumentException("Invalid mode parameter");

        this.prev = new int[256];
        this.curr = new int[256];
        this.symbols = new int[256];
        this.ranks = new int[256];
        this.mode = m;
    }

    @Override
    public boolean forward(SliceByteArray input, SliceByteArray output) {
        if (input.length == 0)
            return true;

        if (input.array == output.array)
            return false;

        final int count = input.length;

        if (output.length < count)
            return false;

        if (output.index + count > output.array.length)
            return false;

        // Aliasing
        final byte[] src = input.array;
        final byte[] dst = output.array;
        final int srcIdx = input.index;
        final int dstIdx = output.index;
        final int[] p = this.prev;
        final int[] q = this.curr;
        final int[] s2r = this.symbols;
        final int[] r2s = this.ranks;

        final int m1 = (this.mode == MODE_TIMESTAMP) ? 0 : -1;
        final int m2 = (this.mode == MODE_MTF) ? 0 : -1;
        final int s = (this.mode == MODE_RANK) ? 1 : 0;

        for (int i = 0; i < 256; i++) {
            p[i] = 0;
            q[i] = 0;
            s2r[i] = i;
            r2s[i] = i;
        }

        for (int i = 0; i < count; i++) {
            final int c = src[srcIdx + i] & 0xFF;
            int r = s2r[c];
            dst[dstIdx + i] = (byte) r;
            final int qc = ((i & m1) + (p[c] & m2)) >> s;
            p[c] = i;
            q[c] = qc;

            // Move up symbol to correct rank
            while ((r > 0) && (q[r2s[r - 1]] <= qc)) {
                r2s[r] = r2s[r - 1];
                s2r[r2s[r]] = r;
                r--;
            }

            r2s[r] = c;
            s2r[c] = r;
        }

        input.index += count;
        output.index += count;
        return true;
    }

    @Override
    public boolean inverse(SliceByteArray input, SliceByteArray output) {
        if (input.length == 0)
            return true;

        if (input.array == output.array)
            return false;

        final int count = input.length;

        if (output.length < count)
            return false;

        if (output.index + count > output.array.length)
            return false;

        // Aliasing
        final byte[] src = input.array;
        final byte[] dst = output.array;
        final int srcIdx = input.index;
        final int dstIdx = output.index;
        final int[] p = this.prev;
        final int[] q = this.curr;
        final int[] r2s = this.ranks;

        final int m1 = (this.mode == MODE_TIMESTAMP) ? 0 : -1;
        final int m2 = (this.mode == MODE_MTF) ? 0 : -1;
        final int s = (this.mode == MODE_RANK) ? 1 : 0;

        for (int i = 0; i < 256; i++) {
            p[i] = 0;
            q[i] = 0;
            r2s[i] = i;
        }

        for (int i = 0; i < count; i++) {
            int r = src[srcIdx + i] & 0xFF;
            final int c = r2s[r];
            dst[dstIdx + i] = (byte) c;
            final int qc = ((i & m1) + (p[c] & m2)) >> s;
            p[c] = i;
            q[c] = qc;

            // Move up symbol to correct rank
            while ((r > 0) && (q[r2s[r - 1]] <= qc)) {
                r2s[r] = r2s[r - 1];
                r--;
            }

            r2s[r] = c;
        }

        input.index += count;
        output.index += count;
        return true;
    }

    /**
     * Return the max size required for the encoding output buffer.
     *
     * @param srcLength the source length
     * @return the max encoded length
     */
    @Override
    public int getMaxEncodedLength(int srcLength) {
        return srcLength;
    }
}
