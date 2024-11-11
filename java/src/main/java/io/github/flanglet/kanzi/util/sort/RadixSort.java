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

package io.github.flanglet.kanzi.util.sort;

import io.github.flanglet.kanzi.ByteSorter;
import io.github.flanglet.kanzi.IntSorter;

/**
 * A fast implementation of the Radix Sort algorithm, which sorts integer or byte arrays
 * using multiple buckets based on radix (binary digit groupings). This implementation uses
 * lists of buckets per radix to efficiently sort data with O(kn) time complexity, where
 * n is the number of keys and k is the number of digits per key.
 *
 * Radix Sort works by distributing the numbers into different buckets based on individual
 * digits (or groups of bits) and then reassembling the numbers in sorted order. This
 * process is repeated for each digit until all the numbers are sorted.
 *
 * <p>Supported radix values are:
 * <ul>
 *   <li>4 bits per radix (radix of 16)</li>
 *   <li>8 bits per radix (radix of 256)</li>
 * </ul>
 *
 * This class implements both the {@link IntSorter} and {@link ByteSorter} interfaces.
 *
 * @see <a href="http://en.wikipedia.org/wiki/Radix_sort">Wikipedia: Radix Sort</a>
 *
 * <p>Complexity: O(kn), where n is the number of elements and k is the number of digits
 * in each element (logarithmic to the maximum element size).
 */
public final class RadixSort implements IntSorter, ByteSorter {

    /**
     * The number of bits used to represent each radix (either 4 or 8 bits).
     */
    private final int bitsRadix;

    /**
     * Buffers used to hold the buckets for sorting.
     * Array structure: [number of digits][bucket size]
     */
    private final int[][] buffers;

    /**
     * Constructs a RadixSort with a default radix of 4 bits (radix 16).
     * The radix can be changed later by creating a new instance.
     */
    public RadixSort() {
        this.bitsRadix = 4; // radix of 16
        this.buffers = new int[8][256];
    }

    /**
     * Constructs a RadixSort with the specified number of bits per radix.
     * Only values of 4 or 8 bits are supported.
     *
     * @param bitsRadix the number of bits per radix (either 4 or 8).
     * @throws IllegalArgumentException if the number of bits is not 4 or 8.
     */
    public RadixSort(int bitsRadix) {
        if ((bitsRadix != 4) && (bitsRadix != 8)) {
            throw new IllegalArgumentException("Invalid radix value (must be 4 or 8 bits)");
        }

        this.bitsRadix = bitsRadix;
        this.buffers = new int[8][256];
    }

    /**
     * Sorts the specified range of integers in the input array using Radix Sort.
     *
     * @param input   the input array to sort.
     * @param blkptr  the starting index for the range to sort.
     * @param count   the number of elements to sort.
     * @return {@code true} if the sort was successful, {@code false} if the input
     *         parameters are invalid (e.g., out-of-bounds indices).
     */
    @Override
    public boolean sort(int[] input, int blkptr, int count) {
        if ((blkptr < 0) || (count <= 0) || (blkptr + count > input.length)) {
            return false;
        }

        if (count == 1) {
            return true;
        }

        return (this.bitsRadix == 4) ? this.sort16(input, blkptr, count) :
               this.sort256(input, blkptr, count);
    }

    /**
     * Sorts the specified range of integers in the input array using Radix Sort
     * with a 4-bit radix (radix of 16).
     *
     * @param input   the input array to sort.
     * @param blkptr  the starting index for the range to sort.
     * @param count   the number of elements to sort.
     * @return {@code true} if the sort was successful.
     */
    private boolean sort16(int[] input, int blkptr, int count) {
        // Implementation omitted for brevity (same as provided in your code)
        return true;
    }

    /**
     * Sorts the specified range of integers in the input array using Radix Sort
     * with an 8-bit radix (radix of 256).
     *
     * @param input   the input array to sort.
     * @param blkptr  the starting index for the range to sort.
     * @param count   the number of elements to sort.
     * @return {@code true} if the sort was successful.
     */
    private boolean sort256(int[] input, int blkptr, int count) {
        // Implementation omitted for brevity (same as provided in your code)
        return true;
    }

    /**
     * Sorts the specified range of bytes in the input array using Radix Sort.
     *
     * @param input   the input array to sort.
     * @param blkptr  the starting index for the range to sort.
     * @param count   the number of elements to sort.
     * @return {@code true} if the sort was successful, {@code false} if the input
     *         parameters are invalid (e.g., out-of-bounds indices).
     */
    @Override
    public boolean sort(byte[] input, int blkptr, int count) {
        if ((blkptr < 0) || (count <= 0) || (blkptr + count > input.length)) {
            return false;
        }

        if (count == 1) {
            return true;
        }

        return (this.bitsRadix == 4) ? this.sort16(input, blkptr, count) :
               this.sort256(input, blkptr, count);
    }

    /**
     * Sorts the specified range of bytes in the input array using Radix Sort
     * with a 4-bit radix (radix of 16).
     *
     * @param input   the input array to sort.
     * @param blkptr  the starting index for the range to sort.
     * @param count   the number of elements to sort.
     * @return {@code true} if the sort was successful.
     */
    private boolean sort16(byte[] input, int blkptr, int count) {
        // Implementation omitted for brevity (same as provided in your code)
        return true;
    }

    /**
     * Sorts the specified range of bytes in the input array using a BucketSort
     * for radix 8-bit (radix of 256).
     *
     * @param input   the input array to sort.
     * @param blkptr  the starting index for the range to sort.
     * @param count   the number of elements to sort.
     * @return {@code true} if the sort was successful.
     */
    private boolean sort256(byte[] input, int blkptr, int count) {
        return new BucketSort().sort(input, blkptr, count);
    }
}

