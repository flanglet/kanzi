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

import io.github.flanglet.kanzi.ArrayComparator;
import io.github.flanglet.kanzi.IntSorter;

/**
 * The {@code InsertionSort} class implements the insertion sort algorithm, a simple comparison-based sorting algorithm with
 * a worst-case time complexity of O(n²) and an average-case complexity of O(n+k), where k is the number of inversions.
 * This algorithm is efficient for small data sets or nearly sorted data, but is not suitable for large datasets due to its
 * quadratic time complexity.
 *
 * <p>Insertion sort works by iterating through the array and repeatedly inserting each element into its correct position
 * relative to the elements before it. The algorithm performs well when the data is already nearly sorted, making it ideal for
 * small datasets or nearly sorted data.</p>
 *
 * <p>This class implements the {@code IntSorter} interface, which defines the {@code sort} method for sorting integer arrays.</p>
 */
public class InsertionSort implements IntSorter {

    // Comparator used for comparing elements in the array
    private final ArrayComparator cmp;

    /**
     * Constructs an {@code InsertionSort} instance without a custom comparator.
     * This will use the natural ordering of the elements in the array.
     */
    public InsertionSort() {
        this(null);
    }

    /**
     * Constructs an {@code InsertionSort} instance with the specified comparator.
     * If {@code cmp} is {@code null}, the natural ordering of the elements will be used.
     *
     * @param cmp the comparator to use for element comparisons, or {@code null} to use natural ordering.
     */
    public InsertionSort(ArrayComparator cmp) {
        this.cmp = cmp;
    }

    /**
     * Returns the comparator used by this {@code InsertionSort} instance.
     *
     * @return the comparator used for element comparisons, or {@code null} if natural ordering is used.
     */
    protected ArrayComparator getComparator() {
        return this.cmp;
    }

    /**
     * Sorts the specified portion of the input array using the insertion sort algorithm.
     *
     * <p>The sorting begins at index {@code blkptr} and sorts {@code len} elements in the array. The array is rearranged
     * in-place, and the elements will be sorted in ascending order.</p>
     *
     * @param input the array to be sorted.
     * @param blkptr the starting index of the portion to be sorted.
     * @param len the number of elements to sort.
     * @return {@code true} if the sorting was successful, {@code false} if invalid parameters were provided (e.g., out-of-bounds indices).
     */
    @Override
    public boolean sort(int[] input, int blkptr, int len) {
        if ((blkptr < 0) || (len <= 0) || (blkptr + len > input.length))
            return false;

        if (len == 1)
            return true;

        // If no comparator is provided, sort using natural ordering
        if (this.cmp == null)
            sortNoComparator(input, blkptr, blkptr + len);
        else
            sortWithComparator(input, blkptr, blkptr + len, this.cmp);

        return true;
    }

    /**
     * Performs the insertion sort on the array using the provided comparator.
     * This method handles the sorting for small sub-arrays and larger arrays.
     *
     * @param array the array to be sorted.
     * @param blkptr the starting index of the portion to be sorted.
     * @param end the index where the sorting should end.
     * @param comp the comparator used for element comparisons.
     */
    private static void sortWithComparator(int[] array, int blkptr, int end, ArrayComparator comp) {
        // Shortcut for 2-element sub-array
        if (end == blkptr + 1) {
            if (comp.compare(array[blkptr], array[end]) > 0) {
                final int tmp = array[blkptr];
                array[blkptr] = array[end];
                array[end] = tmp;
            }
            return;
        }

        // Shortcut for 3-element sub-array
        if (end == blkptr + 2) {
            final int a1 = array[blkptr];
            final int a2 = array[blkptr + 1];
            final int a3 = array[end];

            if (comp.compare(a1, a2) <= 0) {
                if (comp.compare(a2, a3) <= 0)
                    return;

                if (comp.compare(a3, a1) <= 0) {
                    array[blkptr] = a3;
                    array[blkptr + 1] = a1;
                    array[end] = a2;
                    return;
                }

                array[blkptr + 1] = a3;
                array[end] = a2;
            } else {
                if (comp.compare(a1, a3) <= 0) {
                    array[blkptr] = a2;
                    array[blkptr + 1] = a1;
                    return;
                }

                if (comp.compare(a3, a2) <= 0) {
                    array[blkptr] = a3;
                    array[end] = a1;
                    return;
                }

                array[blkptr] = a2;
                array[blkptr + 1] = a3;
                array[end] = a1;
            }
            return;
        }

        // Regular case for arrays with more than 3 elements
        for (int i = blkptr; i < end; i++) {
            final int val = array[i];
            int j = i;

            while ((j > blkptr) && (comp.compare(array[j - 1], val) > 0)) {
                array[j] = array[j - 1];
                j--;
            }

            array[j] = val;
        }
    }

    /**
     * Performs the insertion sort on the array using natural ordering (i.e., no comparator).
     * This method handles the sorting for small sub-arrays and larger arrays without needing a custom comparator.
     *
     * @param array the array to be sorted.
     * @param blkptr the starting index of the portion to be sorted.
     * @param end the index where the sorting should end.
     */
    private static void sortNoComparator(int[] array, int blkptr, int end) {
        // Shortcut for 2-element sub-array
        if (end == blkptr + 1) {
            if (array[blkptr] > array[end]) {
                final int tmp = array[blkptr];
                array[blkptr] = array[end];
                array[end] = tmp;
            }
            return;
        }

        // Shortcut for 3-element sub-array
        if (end == blkptr + 2) {
            final int a1 = array[blkptr];
            final int a2 = array[blkptr + 1];
            final int a3 = array[end];

            if (a1 <= a2) {
                if (a2 <= a3)
                    return;

                if (a3 <= a1) {
                    array[blkptr] = a3;
                    array[blkptr + 1] = a1;
                    array[end] = a2;
                    return;
                }

                array[blkptr + 1] = a3;
                array[end] = a2;
            } else {
                if (a1 <= a3) {
                    array[blkptr] = a2;
                    array[blkptr + 1] = a1;
                    return;
                }

                if (a3 <= a2) {
                    array[blkptr] = a3;
                    array[end] = a1;
                    return;
                }

                array[blkptr] = a2;
                array[blkptr + 1] = a3;
                array[end] = a1;
            }
            return;
        }

        // Regular case for arrays with more than 3 elements
        for (int i = blkptr; i < end; i++) {
            final int val = array[i];
            int j = i;

            while ((j > blkptr) && (array[j - 1] > val)) {
                array[j] = array[j - 1];
                j--;
            }

            array[j] = val;
        }
    }
}

