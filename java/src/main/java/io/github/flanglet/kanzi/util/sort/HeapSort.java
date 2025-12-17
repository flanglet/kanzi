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

package io.github.flanglet.kanzi.util.sort;

import io.github.flanglet.kanzi.ArrayComparator;
import io.github.flanglet.kanzi.IntSorter;

/**
 * The {@code HeapSort} class implements the heap sort algorithm, a
 * comparison-based sorting algorithm with an average and worst-case time
 * complexity of O(n log n).
 *
 * <p>
 * Heap sort works by first building a binary heap from the input data, and then
 * repeatedly extracting the maximum (or minimum) element from the heap and
 * reconstructing the heap. Although heap sort has O(n log n) time complexity,
 * it is often slower in practice compared to other O(n log n) algorithms such
 * as QuickSort, due to larger constant factors.
 * </p>
 *
 * <p>
 * This implementation allows an optional custom comparator to be used for
 * comparing array elements. If no comparator is provided, the natural ordering
 * of the elements is used.
 * </p>
 *
 * <p>
 * This class implements the {@code IntSorter} interface, which defines the
 * {@code sort} method for sorting integer arrays.
 * </p>
 */
public final class HeapSort implements IntSorter {

    // Comparator used for comparing elements in the array
    private final ArrayComparator cmp;

    /**
     * Constructs a {@code HeapSort} instance without a custom comparator. This will
     * use the natural ordering of the elements in the array.
     */
    public HeapSort() {
        this(null);
    }

    /**
     * Constructs a {@code HeapSort} instance with the specified comparator. If
     * {@code cmp} is {@code null}, the natural ordering of the elements will be
     * used.
     *
     * @param cmp
     *            the comparator to use for element comparisons, or {@code null} to
     *            use natural ordering.
     */
    public HeapSort(ArrayComparator cmp) {
        this.cmp = cmp;
    }

    /**
     * Returns the comparator used by this {@code HeapSort} instance.
     *
     * @return the comparator used for element comparisons, or {@code null} if
     *         natural ordering is used.
     */
    protected ArrayComparator getComparator() {
        return this.cmp;
    }

    /**
     * Sorts the specified portion of the input array using the heap sort algorithm.
     *
     * <p>
     * The sorting begins at index {@code blkptr} and sorts {@code len} elements in
     * the array. The array is rearranged in-place, and the elements will be sorted
     * in ascending order.
     * </p>
     *
     * @param input
     *            the array to be sorted.
     * @param blkptr
     *            the starting index of the portion to be sorted.
     * @param len
     *            the number of elements to sort.
     * @return {@code true} if the sorting was successful, {@code false} if invalid
     *         parameters were provided (out-of-bounds indices).
     */
    @Override
    public boolean sort(int[] input, int blkptr, int len) {
        if ((blkptr < 0) || (len <= 0) || (blkptr + len > input.length))
            return false;

        if (len == 1)
            return true;

        // Build the heap by calling doSort on all non-leaf nodes
        for (int k = len >> 1; k > 0; k--) {
            doSort(input, blkptr, k, len, this.cmp);
        }

        // Repeatedly extract the maximum element and reconstruct the heap
        for (int i = len - 1; i > 0; i--) {
            final int temp = input[blkptr];
            input[blkptr] = input[blkptr + i];
            input[blkptr + i] = temp;
            doSort(input, blkptr, 1, i, this.cmp);
        }

        return true;
    }

    /**
     * Performs a single heap sort operation on the portion of the array specified
     * by {@code blkptr}, {@code idx}, and {@code count}. This method ensures that
     * the subtree rooted at {@code idx} is a valid heap.
     *
     * @param array
     *            the array to be sorted.
     * @param blkptr
     *            the starting index of the array to be sorted.
     * @param idx
     *            the index of the current node to heapify.
     * @param count
     *            the total number of elements in the heap.
     * @param cmp
     *            the comparator used for comparisons, or {@code null} to use
     *            natural ordering.
     */
    private static void doSort(int[] array, int blkptr, int idx, int count, ArrayComparator cmp) {
        int k = idx;
        final int temp = array[blkptr + k - 1];
        final int n = count >> 1; // Half the size of the heap

        // If a custom comparator is provided, use it for comparison
        if (cmp != null) {
            while (k <= n) {
                int j = k << 1; // Left child

                // If right child exists and is larger, use it instead
                if ((j < count) && (cmp.compare(array[blkptr + j - 1], array[blkptr + j]) < 0)) {
                    j++;
                }

                // If the current node is larger than its child, break out of the loop
                if (temp >= array[blkptr + j - 1]) {
                    break;
                }

                // Move the child up to the parent node
                array[blkptr + k - 1] = array[blkptr + j - 1];
                k = j;
            }
        }
        // If no comparator is provided, use natural ordering (ascending order)
        else {
            while (k <= n) {
                int j = k << 1; // Left child

                // If right child exists and is larger, use it instead
                if ((j < count) && (array[blkptr + j - 1] < array[blkptr + j])) {
                    j++;
                }

                // If the current node is larger than its child, break out of the loop
                if (temp >= array[blkptr + j - 1]) {
                    break;
                }

                // Move the child up to the parent node
                array[blkptr + k - 1] = array[blkptr + j - 1];
                k = j;
            }
        }

        // Place the original element in the correct position
        array[blkptr + k - 1] = temp;
    }
}
