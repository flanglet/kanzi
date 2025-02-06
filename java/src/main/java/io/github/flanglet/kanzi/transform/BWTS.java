/*
Copyright 2011-2024 Frederic Langlet
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
you may obtain a copy of the License at

                http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package io.github.flanglet.kanzi.transform;

import java.util.Map;
import io.github.flanglet.kanzi.ByteTransform;
import io.github.flanglet.kanzi.SliceByteArray;



/**
 * Bijective version of the Burrows-Wheeler Transform.
 * The main advantage over the regular BWT is that there is no need for a primary index (hence the bijectivity).
 * BWTS is about 10% slower than BWT.
 * Forward transform based on the code at https://code.google.com/p/mk-bwts/ by Neal Burns and DivSufSort (port of libDivSufSort by Yuta Mori).
 */
public class BWTS implements ByteTransform {
    private static final int MAX_BLOCK_SIZE = 1024 * 1024 * 1024; // 1 GB

    private int[] buffer1;
    private int[] buffer2;
    private final int[] buckets;
    private DivSufSort saAlgo;

    /**
     * Default constructor.
     */
    public BWTS() {
        this.buffer1 = new int[0];
        this.buffer2 = new int[0];
        this.buckets = new int[256];
    }

    /**
     * Constructor with a context map.
     *
     * @param ctx the context map
     */
    public BWTS(Map<String, Object> ctx) {
        this.buffer1 = new int[0];
        this.buffer2 = new int[0];
        this.buckets = new int[256];
    }

    /**
     * Performs the forward transform, encoding the input data.
     *
     * @param src   the source byte array
     * @param dst   the destination byte array
     * @return true if the transform was successful, false otherwise
     * @throws IllegalArgumentException if the source length exceeds the maximum block size
     */
    @Override
    public boolean forward(SliceByteArray src, SliceByteArray dst) {
        if (src.length == 0)
            return true;

        if (src.array == dst.array)
            return false;

        final int count = src.length;

        // Not a recoverable error: instead of silently fail the transform,
        // issue a fatal error.
        if (count > maxBlockSize())
            throw new IllegalArgumentException("The max BWTS block size is " + maxBlockSize() + ", got " + count);

        if (dst.index + count > dst.array.length)
            return false;

        final byte[] input = src.array;
        final byte[] output = dst.array;
        final int srcIdx = src.index;
        final int dstIdx = dst.index;

        if (count < 2) {
            if (count == 1)
                output[dst.index++] = input[src.index++];
            return true;
        }

        if (this.saAlgo == null)
            this.saAlgo = new DivSufSort();

        // Lazy dynamic memory allocations
        if (this.buffer1.length < count)
            this.buffer1 = new int[count];

        if (this.buffer2.length < count)
            this.buffer2 = new int[count];

        // Aliasing
        final int[] sa = this.buffer1;
        final int[] isa = this.buffer2;

        this.saAlgo.computeSuffixArray(input, sa, srcIdx, count);

        for (int i = 0; i < count; i++)
            isa[sa[i]] = i;

        int min = isa[0];
        int idxMin = 0;

        for (int i = 1; ((i < count) && (min > 0)); i++) {
            if (isa[i] >= min)
                continue;

            int refRank = moveLyndonWordHead(sa, isa, input, count, srcIdx, idxMin, i - idxMin, min);

            for (int j = i - 1; j > idxMin; j--) {
                // iterate through the new lyndon word from end to start
                int testRank = isa[j];
                int startRank = testRank;

                while (testRank < count - 1) {
                    int nextRankStart = sa[testRank + 1];

                    if ((j > nextRankStart) || (input[srcIdx + j] != input[srcIdx + nextRankStart])
                            || (refRank < isa[nextRankStart + 1]))
                        break;

                    sa[testRank] = nextRankStart;
                    isa[nextRankStart] = testRank;
                    testRank++;
                }

                sa[testRank] = j;
                isa[j] = testRank;
                refRank = testRank;

                if (startRank == testRank)
                    break;
            }

            min = isa[i];
            idxMin = i;
        }

        min = count;
        final int srcIdx2 = srcIdx - 1;

        for (int i = 0; i < count; i++) {
            if (isa[i] >= min) {
                output[dstIdx + isa[i]] = input[srcIdx2 + i];
                continue;
            }

            if (min < count)
                output[dstIdx + min] = input[srcIdx2 + i];

            min = isa[i];
        }

        output[dstIdx] = input[srcIdx2 + count];
        src.index += count;
        dst.index += count;
        return true;
    }

    /**
     * Moves the head of a Lyndon word.
     *
     * @param sa      the suffix array
     * @param isa     the inverse suffix array
     * @param data    the data array
     * @param count   the count
     * @param srcIdx  the source index
     * @param start   the start index
     * @param size    the size
     * @param rank    the rank
     * @return the new rank
     */
    private static int moveLyndonWordHead(int[] sa, int[] isa, byte[] data, int count, int srcIdx, int start, int size, int rank) {
        final int end = start + size;
        final int startIdx = srcIdx + start;

        while (rank + 1 < count) {
            final int nextStart0 = sa[rank + 1];

            if (nextStart0 <= end)
                break;

            int nextStart = nextStart0;
            int k = 0;

            while ((k < size) && (nextStart < count) && (data[startIdx + k] == data[srcIdx + nextStart])) {
                k++;
                nextStart++;
            }

            if ((k == size) && (rank < isa[nextStart]))
                break;

            if ((k < size) && (nextStart < count) && ((data[startIdx + k] & 0xFF) < (data[srcIdx + nextStart] & 0xFF)))
                break;

            sa[rank] = nextStart0;
            isa[nextStart0] = rank;
            rank++;
        }

        sa[rank] = start;
        isa[start] = rank;
        return rank;
    }

    /**
     * Performs the inverse transform, decoding the input data.
     *
     * @param src   the source byte array
     * @param dst   the destination byte array
     * @return true if the transform was successful, false otherwise
     * @throws IllegalArgumentException if the source length exceeds the maximum block size
     */
    @Override
    public boolean inverse(SliceByteArray src, SliceByteArray dst) {
        if (src.length == 0)
            return true;

        if (src.array == dst.array)
            return false;

        final int count = src.length;

        // Not a recoverable error: instead of silently fail the transform,
        // issue a fatal error.
        if (count > maxBlockSize())
            throw new IllegalArgumentException("The max BWTS block size is " + maxBlockSize() + ", got " + count);

        if (dst.index + count > dst.array.length)
            return false;

        if (count < 2) {
            if (count == 1)
                dst.array[dst.index++] = src.array[src.index++];
            return true;
        }

        final byte[] input = src.array;
        final byte[] output = dst.array;
        final int srcIdx = src.index;
        final int dstIdx = dst.index;

        // Lazy dynamic memory allocation
        if (this.buffer1.length < count)
            this.buffer1 = new int[count];

        // Aliasing
        final int[] buckets_ = this.buckets;
        final int[] lf = this.buffer1;

        // Initialize histogram
        for (int i = 0; i < 256; i++)
            buckets_[i] = 0;

        for (int i = 0; i < count; i++)
            buckets_[input[srcIdx + i] & 0xFF]++;

        // Histogram
        for (int i = 0, sum = 0; i < 256; i++) {
            sum += buckets_[i];
            buckets_[i] = sum - buckets_[i];
        }

        for (int i = 0; i < count; i++)
            lf[i] = buckets_[input[srcIdx + i] & 0xFF]++;

        // Build inverse
        for (int i = 0, j = dstIdx + count - 1; j >= dstIdx; i++) {
            if (lf[i] < 0)
                continue;

            int p = i;

            do {
                output[j] = input[srcIdx + p];
                j--;
                final int t = lf[p];
                lf[p] = -1;
                p = t;
            } while (lf[p] >= 0);
        }

        src.index += count;
        dst.index += count;
        return true;
    }

    /**
     * Returns the maximum block size for the BWTS.
     *
     * @return the maximum block size
     */
    public static int maxBlockSize() {
        return MAX_BLOCK_SIZE;
    }

    /**
     * Returns the maximum size required for the encoding output buffer.
     *
     * @param srcLength the source length
     * @return the maximum encoded length
     */
    @Override
    public int getMaxEncodedLength(int srcLength) {
        return srcLength;
    }
}