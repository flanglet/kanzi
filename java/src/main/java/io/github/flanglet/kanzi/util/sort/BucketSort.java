/*
Copyright 2011-2025 Frederic Langlet
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

package io.github.flanglet.kanzi.util.sort;

import io.github.flanglet.kanzi.ByteSorter;
import io.github.flanglet.kanzi.IntSorter;

/**
 * The {@code BucketSort} class provides an implementation of the bucket sort algorithm for sorting integers and bytes.
 * Bucket sort is a simple and efficient sorting algorithm that works by distributing elements into a number of buckets,
 * then sorting the individual buckets. This implementation is optimized to handle small integer and byte values.
 *
 * <p>It is a simplified form of radix sort with buckets of width one, making it efficient for small integers (up to 0xFFFF).</p>
 * <p>This implementation is not thread-safe due to the mutable state of its internal data structures.</p>
 */
public class BucketSort implements IntSorter, ByteSorter {

    // Array to store the count of each value within the bucket range
    private final int[] count;

    /**
     * Constructs a {@code BucketSort} object using the default bucket size for byte values (0 to 255).
     */
    public BucketSort() {
        this.count = new int[256];
    }

    /**
     * Constructs a {@code BucketSort} object with a custom bucket size determined by the logarithm of the maximum value.
     *
     * @param logMaxValue the logarithm (base 2) of the maximum value to be sorted.
     *                     Must be between 2 and 16 (inclusive).
     * @throws IllegalArgumentException if the {@code logMaxValue} is less than 2 or greater than 16.
     */
    public BucketSort(int logMaxValue) {
        if (logMaxValue < 2)
            throw new IllegalArgumentException("The log data size parameter must be at least 2");

        if (logMaxValue > 16)
            throw new IllegalArgumentException("The log data size parameter must be at most 16");

        this.count = new int[1 << logMaxValue]; // Array size determined by the max value (logMaxValue)
    }

    /**
     * Sorts an array of integers using the bucket sort algorithm.
     *
     * <p>The sorting works by counting the frequency of each integer in the input array, then placing the integers back into
     * the array in sorted order.</p>
     *
     * @param input the array of integers to be sorted.
     * @param blkptr the starting index in the array to begin sorting.
     * @param len the length of the portion of the array to be sorted.
     * @return {@code true} if the sorting was successful; {@code false} if there were invalid parameters (e.g.,
     *         out-of-bounds indices or invalid length).
     */
    @Override
    public boolean sort(int[] input, int blkptr, int len) {
        if ((blkptr < 0) || (len <= 0) || (blkptr + len > input.length))
            return false;

        if (len == 1)
            return true;

        final int len8 = len & -8;  // Round down to the nearest multiple of 8
        final int end8 = blkptr + len8;
        final int[] c = this.count;  // Bucket count array
        final int length = c.length;

        // Unrolled loop for efficient counting
        for (int i = blkptr; i < end8; i += 8) {
            c[input[i]]++;
            c[input[i + 1]]++;
            c[input[i + 2]]++;
            c[input[i + 3]]++;
            c[input[i + 4]]++;
            c[input[i + 5]]++;
            c[input[i + 6]]++;
            c[input[i + 7]]++;
        }

        // Handle remaining elements not divisible by 8
        for (int i = len8; i < len; i++)
            c[input[blkptr + i]]++;

        // Reconstruct the sorted array using the bucket counts
        for (int i = 0, j = blkptr; i < length; i++) {
            final int val = c[i];

            if (val == 0)
                continue;

            c[i] = 0;
            int val8 = val & -8;

            for (int k = val; k > val8; k--)
                input[j++] = i;

            // Fill the remaining spots using the "8 at a time" optimization
            while (val8 > 0) {
                input[j] = i;
                input[j + 1] = i;
                input[j + 2] = i;
                input[j + 3] = i;
                input[j + 4] = i;
                input[j + 5] = i;
                input[j + 6] = i;
                input[j + 7] = i;
                j += 8;
                val8 -= 8;
            }
        }

        return true;
    }

    /**
     * Sorts an array of bytes using the bucket sort algorithm.
     *
     * <p>This method behaves similarly to the integer sort method, but operates on byte values (0 to 255).</p>
     *
     * @param input the array of bytes to be sorted.
     * @param blkptr the starting index in the array to begin sorting.
     * @param len the length of the portion of the array to be sorted.
     * @return {@code true} if the sorting was successful; {@code false} if there were invalid parameters (
     *         out-of-bounds indices or invalid length).
     */
    @Override
    public boolean sort(byte[] input, int blkptr, int len) {
        if ((blkptr < 0) || (len <= 0) || (blkptr + len > input.length))
            return false;

        if (len == 1)
            return true;

        final int len8 = len & -8;  // Round down to the nearest multiple of 8
        final int end8 = blkptr + len8;
        final int[] c = this.count;  // Bucket count array
        final int length = c.length;

        // Unrolled loop for efficient counting
        for (int i = blkptr; i < end8; i += 8) {
            c[input[i] & 0xFF]++;
            c[input[i + 1] & 0xFF]++;
            c[input[i + 2] & 0xFF]++;
            c[input[i + 3] & 0xFF]++;
            c[input[i + 4] & 0xFF]++;
            c[input[i + 5] & 0xFF]++;
            c[input[i + 6] & 0xFF]++;
            c[input[i + 7] & 0xFF]++;
        }

        // Handle remaining elements not divisible by 8
        for (int i = len8; i < len; i++)
            c[input[blkptr + i] & 0xFF]++;

        // Reconstruct the sorted array using the bucket counts
        for (int i = 0, j = blkptr; i < length; i++) {
            final int val = c[i];

            if (val == 0)
                continue;

            int val8 = val & -8;
            c[i] = 0;

            for (int k = val; k > val8; k--)
                input[j++] = (byte) i;

            // Fill the remaining spots using the "8 at a time" optimization
            while (val8 > 0) {
                input[j] = (byte) i;
                input[j + 1] = (byte) i;
                input[j + 2] = (byte) i;
                input[j + 3] = (byte) i;
                input[j + 4] = (byte) i;
                input[j + 5] = (byte) i;
                input[j + 6] = (byte) i;
                input[j + 7] = (byte) i;
                j += 8;
                val8 -= 8;
            }
        }

        return true;
    }
}

