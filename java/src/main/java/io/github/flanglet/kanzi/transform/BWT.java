/*
 * Kanzi is a modern, modular, portable, and efficient lossless data compressor.
 *
 * Copyright (C) 2011-2025 Frederic Langlet
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import io.github.flanglet.kanzi.ByteTransform;
import io.github.flanglet.kanzi.Global;
import io.github.flanglet.kanzi.SliceByteArray;

/**
 * The Burrows-Wheeler Transform (BWT) is a reversible transform based on
 * permutation of the data in the original message to reduce the entropy.
 * <p>
 * The initial text can be found here:
 * Burrows M and Wheeler D, [A block sorting lossless data compression algorithm]
 * Technical Report 124, Digital Equipment Corporation, 1994
 * <p>
 * See also Peter Fenwick, [Block sorting text compression - final report]
 * Technical Report 130, 1996
 * <p>
 * This implementation replaces the 'slow' sorting of permutation strings
 * with the construction of a suffix array (faster but more complex).
 * The suffix array contains the indexes of the sorted suffixes.
 * <p>
 * E.G.    0123456789A
 * Source: mississippi\0
 * Suffixes:    rank  sorted
 * mississippi\0  0  -&gt; 4             i\0
 *  ississippi\0  1  -&gt; 3          ippi\0
 *   ssissippi\0  2  -&gt; 10      issippi\0
 *    sissippi\0  3  -&gt; 8    ississippi\0
 *     issippi\0  4  -&gt; 2   mississippi\0
 *      ssippi\0  5  -&gt; 9            pi\0
 *       sippi\0  6  -&gt; 7           ppi\0
 *        ippi\0  7  -&gt; 1         sippi\0
 *         ppi\0  8  -&gt; 6      sissippi\0
 *          pi\0  9  -&gt; 5        ssippi\0
 *           i\0  10 -&gt; 0     ssissippi\0
 * Suffix array SA: 10 7 4 1 0 9 8 6 3 5 2
 * BWT[i] = input[SA[i]-1] =&gt; BWT(input) = ipssmpissii (+ primary index 5)
 * The suffix array and permutation vector are equal when the input is 0 terminated
 * The insertion of a guard is done internally and is entirely transparent.
 * <p>
 * This implementation extends the canonical algorithm to use up to MAX_CHUNKS primary
 * indexes (based on input block size). Each primary index corresponds to a data chunk.
 * Chunks may be inverted concurrently.
 */
public class BWT implements ByteTransform {

    private static final int MAX_BLOCK_SIZE = 1024 * 1024 * 1024; // 1 GB
    private static final int NB_FASTBITS = 17;
    private static final int MASK_FASTBITS = (1 << NB_FASTBITS) - 1;
    private static final int BLOCK_SIZE_THRESHOLD1 = 256;
    private static final int BLOCK_SIZE_THRESHOLD2 = 8 * 1024 * 1024;

    private int[] buffer1;
    private short[] buffer2;
    private int[] buckets;
    private int[] freqs;
    private final int[] primaryIndexes;
    private DivSufSort saAlgo;
    private final ExecutorService pool;
    private final int jobs;

    /**
     * Static allocation of memory.
     */
    public BWT() {
        this.buffer1 = new int[0];
        this.buffer2 = new short[0];
        this.buckets = new int[256];
        this.freqs = new int[256];
        this.primaryIndexes = new int[8];
        this.pool = null;
        this.jobs = 1;
    }

    /**
     * Number of jobs provided in the context.
     *
     * @param ctx the context containing the number of jobs and the thread pool
     * @throws IllegalArgumentException if the number of jobs is not positive or the thread pool is null when the number of jobs is greater than 1
     */
    public BWT(Map<String, Object> ctx) {
        final int tasks = (ctx == null) ? 1 : (Integer) ctx.get("jobs");

        if (tasks <= 0)
            throw new IllegalArgumentException("The number of jobs must be positive");

        ExecutorService threadPool = (ctx == null) ? null : (ExecutorService) ctx.get("pool");

        if ((tasks > 1) && (threadPool == null))
            throw new IllegalArgumentException("The thread pool cannot be null when the number of jobs is " + tasks);

        this.buffer1 = new int[0];
        this.buffer2 = new short[0];
        this.buckets = new int[256];
        this.freqs = new int[256];
        this.primaryIndexes = new int[8];
        this.pool = (tasks == 1) ? null : threadPool;
        this.jobs = tasks;
    }

    /**
     * Returns the primary index for the given chunk.
     *
     * @param n the chunk number
     * @return the primary index for the given chunk
     */
    public int getPrimaryIndex(int n) {
        return this.primaryIndexes[n];
    }

    /**
     * Sets the primary index for the given chunk.
     *
     * @param n            the chunk number
     * @param primaryIndex the primary index to set
     * @return true if the primary index was set successfully, false otherwise
     */
    public boolean setPrimaryIndex(int n, int primaryIndex) {
        if ((primaryIndex < 0) || (n < 0) || (n >= this.primaryIndexes.length))
            return false;

        this.primaryIndexes[n] = primaryIndex;
        return true;
    }

    /**
     * Forward transform.
     *
     * @param src the source byte array
     * @param dst the destination byte array
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
            throw new IllegalArgumentException("The max BWT block size is " + maxBlockSize() + ", got " + count);

        if (dst.index + count > dst.array.length)
            return false;

        if (count == 1) {
            dst.array[dst.index++] = src.array[src.index++];
            return true;
        }

        // Lazy dynamic memory allocation
        if (this.saAlgo == null)
            this.saAlgo = new DivSufSort();

        if (this.buffer1.length < count)
            this.buffer1 = new int[count];

        this.saAlgo.computeBWT(src.array, dst.array, this.buffer1, src.index, dst.index, count,
                this.primaryIndexes, getBWTChunks(count));
        src.index += count;
        dst.index += count;
        return true;
    }

    /**
     * Inverse transform.
     *
     * @param src the source byte array
     * @param dst the destination byte array
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
            throw new IllegalArgumentException("The max BWT block size is " + maxBlockSize() + ", got " + count);

        if (dst.index + count > dst.array.length)
            return false;

        if (count == 1) {
            dst.array[dst.index++] = src.array[src.index++];
            return true;
        }

        // Find the fastest way to implement inverse based on block size
        if (count <= BLOCK_SIZE_THRESHOLD2)
            return inverseMergeTPSI(src, dst, count);

        return inverseBiPSIv2(src, dst, count);
    }

    /**
     * Inverse transform using the mergeTPSI algorithm.
     *
     * @param src   the source byte array
     * @param dst   the destination byte array
     * @param count the number of bytes to process
     * @return true if the transform was successful, false otherwise
     */
    private boolean inverseMergeTPSI(SliceByteArray src, SliceByteArray dst, int count) {
        // Lazy dynamic memory allocation
        if (this.buffer1.length < count)
            this.buffer1 = new int[Math.max(count, 64)];

        // Aliasing
        final byte[] input = src.array;
        final byte[] output = dst.array;
        final int srcIdx = src.index;
        final int dstIdx = dst.index;
        final int[] b = this.buckets;
        final int[] data = this.buffer1;

        // Build array of packed index + value (assumes block size < 1<<24)
        int pIdx = this.getPrimaryIndex(0);

        if ((pIdx <= 0) || (pIdx > count))
            return false;

        Global.computeHistogramOrder0(input, srcIdx, srcIdx + count, this.buckets, false);

        for (int i = 0, sum = 0; i < 256; i++) {
            final int tmp = b[i];
            b[i] = sum;
            sum += tmp;
        }

        final int val0 = input[srcIdx] & 0xFF;
        data[b[val0]] = 0xFF00 | val0;
        b[val0]++;

        for (int i = 1; i < pIdx; i++) {
            final int val = input[srcIdx + i] & 0xFF;
            data[b[val]] = ((i - 1) << 8) | val;
            b[val]++;
        }

        for (int i = pIdx; i < count; i++) {
            final int val = input[srcIdx + i] & 0xFF;
            data[b[val]] = (i << 8) | val;
            b[val]++;
        }

        if (getBWTChunks(count) != 8) {
            for (int i = 0, t = pIdx - 1; i < count; i++) {
                final int ptr = data[t];
                output[dstIdx + i] = (byte) ptr;
                t = ptr >>> 8;
            }
        } else {
            final int ckSize = ((count & 7) == 0) ? count >> 3 : (count >> 3) + 1;
            int t0 = this.getPrimaryIndex(0) - 1;
            int t1 = this.getPrimaryIndex(1) - 1;
            int t2 = this.getPrimaryIndex(2) - 1;
            int t3 = this.getPrimaryIndex(3) - 1;
            int t4 = this.getPrimaryIndex(4) - 1;
            int t5 = this.getPrimaryIndex(5) - 1;
            int t6 = this.getPrimaryIndex(6) - 1;
            int t7 = this.getPrimaryIndex(7) - 1;

            if ((t0 < 0) || (t1 < 0) || (t2 < 0) || (t3 < 0) || (t4 < 0) || (t5 < 0) || (t6 < 0) || (t7 < 0))
                return false;

            if ((t0 >= count) || (t1 >= count) || (t2 >= count) || (t3 >= count) ||
                    (t4 >= count) || (t5 >= count) || (t6 >= count) || (t7 >= count))
                return false;

            // Last interval [7*chunk:count] smaller when 8*ckSize != count
            final int end = count - ckSize * 7;
            int n = 0;

            while (n < end) {
                final int ptr0 = data[t0];
                output[dstIdx + n] = (byte) ptr0;
                t0 = ptr0 >>> 8;
                final int ptr1 = data[t1];
                output[dstIdx + n + ckSize] = (byte) ptr1;
                t1 = ptr1 >>> 8;
                final int ptr2 = data[t2];
                output[dstIdx + n + ckSize * 2] = (byte) ptr2;
                t2 = ptr2 >>> 8;
                final int ptr3 = data[t3];
                output[dstIdx + n + ckSize * 3] = (byte) ptr3;
                t3 = ptr3 >>> 8;
                final int ptr4 = data[t4];
                output[dstIdx + n + ckSize * 4] = (byte) ptr4;
                t4 = ptr4 >>> 8;
                final int ptr5 = data[t5];
                output[dstIdx + n + ckSize * 5] = (byte) ptr5;
                t5 = ptr5 >>> 8;
                final int ptr6 = data[t6];
                output[dstIdx + n + ckSize * 6] = (byte) ptr6;
                t6 = ptr6 >>> 8;
                final int ptr7 = data[t7];
                output[dstIdx + n + ckSize * 7] = (byte) ptr7;
                t7 = ptr7 >>> 8;
                n++;
            }

            while (n < ckSize) {
                final int ptr0 = data[t0];
                output[dstIdx + n] = (byte) ptr0;
                t0 = ptr0 >>> 8;
                final int ptr1 = data[t1];
                output[dstIdx + n + ckSize] = (byte) ptr1;
                t1 = ptr1 >>> 8;
                final int ptr2 = data[t2];
                output[dstIdx + n + ckSize * 2] = (byte) ptr2;
                t2 = ptr2 >>> 8;
                final int ptr3 = data[t3];
                output[dstIdx + n + ckSize * 3] = (byte) ptr3;
                t3 = ptr3 >>> 8;
                final int ptr4 = data[t4];
                output[dstIdx + n + ckSize * 4] = (byte) ptr4;
                t4 = ptr4 >>> 8;
                final int ptr5 = data[t5];
                output[dstIdx + n + ckSize * 5] = (byte) ptr5;
                t5 = ptr5 >>> 8;
                final int ptr6 = data[t6];
                output[dstIdx + n + ckSize * 6] = (byte) ptr6;
                t6 = ptr6 >>> 8;
                n++;
            }
        }

        src.index += count;
        dst.index += count;
        return true;
    }

    /**
     * Inverse transform using the biPSIv2 algorithm.
     *
     * @param src   the source byte array
     * @param dst   the destination byte array
     * @param count the number of bytes to process
     * @return true if the transform was successful, false otherwise
     */
    private boolean inverseBiPSIv2(SliceByteArray src, SliceByteArray dst, int count) {
        // Lazy dynamic memory allocations
        if (this.buffer1.length < count + 1) {
            this.buffer1 = new int[Math.max(count + 1, 64)];
        } else {
            for (int i = 0; i < this.buffer1.length; i++)
                this.buffer1[i] = 0;
        }

        if (this.buckets.length < 65536)
            this.buckets = new int[65536];

        if (this.buffer2.length < MASK_FASTBITS + 1)
            this.buffer2 = new short[MASK_FASTBITS + 1];

        // Aliasing
        final byte[] input = src.array;
        final byte[] output = dst.array;
        final int srcIdx = src.index;
        final int dstIdx = dst.index;
        final int srcIdx2 = src.index - 1;

        int pIdx = this.getPrimaryIndex(0);

        if ((pIdx < 0) || (pIdx > count))
            return false;

        Global.computeHistogramOrder0(input, srcIdx, srcIdx + count, this.freqs, false);
        final int[] b = this.buckets;
        final int[] freqs_ = this.freqs;

        for (int sum = 1, c = 0; c < 256; c++) {
            final int f = sum;
            sum += freqs_[c];
            freqs_[c] = f;

            if (f != sum) {
                final int c256 = c << 8;
                final int hi = (sum < pIdx) ? sum : pIdx;

                for (int i = f; i < hi; i++)
                    b[c256 | (input[srcIdx + i] & 0xFF)]++;

                final int lo = (f - 1 > pIdx) ? f - 1 : pIdx;

                for (int i = lo; i < sum - 1; i++)
                    b[c256 | (input[srcIdx + i] & 0xFF)]++;
            }
        }

        final int lastc = input[srcIdx] & 0xFF;
        final short[] fastBits = this.buffer2;
        int shift = 0;

        while ((count >>> shift) > MASK_FASTBITS)
            shift++;

        for (int v = 0, sum = 1, c = 0; c < 256; c++) {
            if (c == lastc)
                sum++;

            for (int d = 0; d < 256; d++) {
                final int s = sum;
                sum += b[(d << 8) | c];
                b[(d << 8) | c] = s;

                if (s != sum) {
                    for (; v <= ((sum - 1) >> shift); v++)
                        fastBits[v] = (short) ((c << 8) | d);
                }
            }
        }

        final int[] data = this.buffer1;

        for (int i = 0; i < pIdx; ++i) {
            final int c = input[srcIdx + i] & 0xFF;
            final int p = freqs_[c];
            freqs_[c]++;
            if (p < pIdx) {
				final int idx = (c << 8) | (input[srcIdx + p] & 0xFF);
                data[b[idx]] = i;
                b[idx]++;
            } else if (p > pIdx) {
                final int idx = (c << 8) | (input[srcIdx2 + p] & 0xFF);
                data[b[idx]] = i;
                b[idx]++;
            }
        }

        for (int i = pIdx; i < count; i++) {
            final int c = input[srcIdx + i] & 0xFF;
            final int p = freqs_[c];
            freqs_[c]++;
            if (p < pIdx) {
                final int idx = (c << 8) | (input[srcIdx + p] & 0xFF);
                data[b[idx]] = i + 1;
                b[idx]++;
            } else if (p > pIdx) {
                final int idx = (c << 8) | (input[srcIdx2 + p] & 0xFF);
                data[b[idx]] = i + 1;
                b[idx]++;
            }
        }

        for (int c = 0; c < 256; c++) {
            final int c256 = c << 8;

            for (int d = 0; d < c; d++) {
                final int tmp = b[(d << 8) | c];
                b[(d << 8) | c] = b[c256 | d];
                b[c256 | d] = tmp;
            }
        }

        final int chunks = getBWTChunks(count);

        // Build inverse
        // Several chunks may be decoded concurrently (depending on the availability
        // of jobs in the pool).
        final int st = count / chunks;
        final int ckSize = (chunks * st == count) ? st : st + 1;
        final int nbTasks = (this.jobs <= chunks) ? this.jobs : chunks;
        List<Callable<Integer>> tasks = new ArrayList<>(nbTasks);
        final int[] jobsPerTask = Global.computeJobsPerTask(new int[nbTasks], chunks, nbTasks);

        // Create one task per job
        for (int j = 0, c = 0; j < nbTasks; j++) {
            // Each task decodes jobsPerTask[j] chunks
            final int start = dstIdx + c * ckSize;
            tasks.add(new InverseBiPSIv2Task(output, start, count, ckSize, c, c + jobsPerTask[j]));
            c += jobsPerTask[j];
        }

        try {
            if (this.jobs == 1) {
                tasks.get(0).call();
            } else {
                // Wait for completion of all concurrent tasks
                for (Future<Integer> result : this.pool.invokeAll(tasks))
                    result.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }

        output[dstIdx + count - 1] = (byte) lastc;
        src.index += count;
        dst.index += count;
        return true;
    }

    /**
     * Returns the maximum block size for the BWT.
     *
     * @return the maximum block size
     */
    public static int maxBlockSize() {
        return MAX_BLOCK_SIZE;
    }

    /**
     * Returns the number of BWT chunks based on the input size.
     *
     * @param size the input size
     * @return the number of BWT chunks
     */
    public static int getBWTChunks(int size) {
        return (size < BLOCK_SIZE_THRESHOLD1) ? 1 : 8;
    }

    /**
     * Callable task for the inverse biPSIv2 algorithm.
     */
    class InverseBiPSIv2Task implements Callable<Integer> {
        private final byte[] output;
        private final int dstIdx;     // initial offset
        private final int ckSize;     // chunk size, must be adjusted to not go over total
        private final int total;      // max number of bytes to process
        private final int firstChunk; // index first chunk
        private final int lastChunk;  // index last chunk

        /**
         * Constructs an InverseBiPSIv2Task.
         *
         * @param output      the output byte array
         * @param dstIdx      the initial offset
         * @param total       the maximum number of bytes to process
         * @param ckSize      the chunk size
         * @param firstChunk  the index of the first chunk
         * @param lastChunk   the index of the last chunk
         */
        public InverseBiPSIv2Task(byte[] output, int dstIdx, int total,
                                  int ckSize, int firstChunk, int lastChunk) {
            this.output = output;
            this.dstIdx = dstIdx;
            this.ckSize = ckSize;
            this.total = total;
            this.firstChunk = firstChunk;
            this.lastChunk = lastChunk;
        }

        @Override
        public Integer call() throws Exception {
            final int[] data = BWT.this.buffer1;
            final int[] b = BWT.this.buckets;
            final short[] fastBits = BWT.this.buffer2;
            int start = this.dstIdx;
            int shift = 0;

            while ((this.total >>> shift) > MASK_FASTBITS)
                shift++;

            int c = this.firstChunk;

            // Process each chunk sequentially
            if (start + 4 * this.ckSize < this.total) {
                for (; c + 3 < this.lastChunk; c += 4) {
                    final int end = start + this.ckSize;
                    int p0 = BWT.this.getPrimaryIndex(c);
                    int p1 = BWT.this.getPrimaryIndex(c + 1);
                    int p2 = BWT.this.getPrimaryIndex(c + 2);
                    int p3 = BWT.this.getPrimaryIndex(c + 3);

                    for (int i = start + 1; i <= end; i += 2) {
                        int s0 = fastBits[p0 >> shift] & 0xFFFF;
                        int s1 = fastBits[p1 >> shift] & 0xFFFF;
                        int s2 = fastBits[p2 >> shift] & 0xFFFF;
                        int s3 = fastBits[p3 >> shift] & 0xFFFF;

                        while (b[s0] <= p0)
                            s0++;

                        while (b[s1] <= p1)
                            s1++;

                        while (b[s2] <= p2)
                            s2++;

                        while (b[s3] <= p3)
                            s3++;

                        this.output[i - 1] = (byte) (s0 >>> 8);
                        this.output[i] = (byte) s0;
                        this.output[1 * this.ckSize + i - 1] = (byte) (s1 >>> 8);
                        this.output[1 * this.ckSize + i] = (byte) s1;
                        this.output[2 * this.ckSize + i - 1] = (byte) (s2 >>> 8);
                        this.output[2 * this.ckSize + i] = (byte) s2;
                        this.output[3 * this.ckSize + i - 1] = (byte) (s3 >>> 8);
                        this.output[3 * this.ckSize + i] = (byte) s3;
                        p0 = data[p0];
                        p1 = data[p1];
                        p2 = data[p2];
                        p3 = data[p3];
                    }

                    start = end + 3 * this.ckSize;
                }
            }

            for (; c < this.lastChunk; c++) {
                final int end = Math.min(start + this.ckSize, this.total - 1);
                int p = BWT.this.getPrimaryIndex(c);

                for (int i = start + 1; i <= end; i += 2) {
                    int s = fastBits[p >> shift] & 0xFFFF;

                    while (b[s] <= p)
                        s++;

                    this.output[i - 1] = (byte) (s >>> 8);
                    this.output[i] = (byte) s;
                    p = data[p];
                }

                start = end;
            }

            return 0;
        }
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
