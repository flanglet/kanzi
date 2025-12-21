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
 * Implementation of the Dual-Pivot Quicksort algorithm by Vladimir Yaroslavskiy, Jon Bentley,
 * and Josh Bloch. This algorithm is a variation of the classic Quicksort that uses two pivots
 * instead of one, providing improved performance on certain types of data.
 *
 * This implementation follows the principles outlined in:
 * <ul>
 *   <li><a href="http://cr.openjdk.java.net/~alanb/DualPivotSortUpdate/webrev.01/raw_files/new/src/java.base/share/classes/java/util/DualPivotQuicksort.java">
 *       Dual-Pivot Quicksort (OpenJDK source)</a></li>
 * </ul>
 *
 * <p>The algorithm is designed to be highly efficient, with performance that is generally O(n log n)
 * for average cases, and O(n²) for worst cases. Various optimizations (like heap sort and insertion
 * sort) are used when the partition size becomes small enough to improve performance.</p>
 *
 * <p>The class implements the {@link IntSorter} interface and allows sorting of an integer array.</p>
 *
 * Threshold constants are defined for various optimizations:
 * <ul>
 *   <li>{@link #HEAP_SORT_THRESHOLD} - The size threshold at which heap sort is used instead of quicksort.</li>
 *   <li>{@link #NANO_INSERTION_SORT_THRESHOLD} - The threshold for switching to insertion sort for small partitions.</li>
 *   <li>{@link #PAIR_INSERTION_SORT_THRESHOLD} - The threshold for switching to a paired insertion sort.</li>
 *   <li>{@link #MERGING_SORT_THRESHOLD} - The threshold for switching to a merging sort for very large partitions.</li>
 *   <li>{@link #MAX_RECURSION_DEPTH} - Limits recursion depth to prevent stack overflow in the worst case.</li>
 * </ul>
 */
public class QuickSort implements IntSorter {

    /**
     * Threshold for switching to heap sort from quicksort (default value 69).
     */
    private static final int HEAP_SORT_THRESHOLD = 69;

    /**
     * Threshold for switching to nano insertion sort for small partitions (default value 36).
     */
    private static final int NANO_INSERTION_SORT_THRESHOLD = 36;

    /**
     * Threshold for switching to paired insertion sort for small partitions (default value 88).
     */
    private static final int PAIR_INSERTION_SORT_THRESHOLD = 88;

    /**
     * Threshold for switching to merge sort for very large partitions (default value 2048).
     */
    private static final int MERGING_SORT_THRESHOLD = 2048;

    /**
     * The maximum allowed recursion depth, beyond which stack overflow risks are mitigated.
     * Default value is 100.
     */
    private static final int MAX_RECURSION_DEPTH = 100;

    /**
     * A constant that represents the leftmost bits to be used during recursion.
     * This is related to the maximum recursion depth and used in controlling the sorting behavior.
     */
    private static final int LEFTMOST_BITS = MAX_RECURSION_DEPTH << 1;

    /**
     * Comparator used for custom array comparisons. If {@code null}, natural ordering is used.
     */
    private final ArrayComparator cmp;

    /**
     * Creates a QuickSort instance with the default comparator (natural ordering).
     */
    public QuickSort() {
        this(null);
    }

    /**
     * Creates a QuickSort instance with a custom comparator.
     *
     * @param cmp the comparator to use for sorting.
     */
    public QuickSort(ArrayComparator cmp) {
        this.cmp = cmp;
    }

    /**
     * Returns the comparator used for sorting, or {@code null} if the default natural ordering is used.
     *
     * @return the comparator used for sorting.
     */
    protected ArrayComparator getComparator() {
        return this.cmp;
    }

    /**
     * Sorts the specified subarray of the input array using Dual-Pivot Quicksort.
     *
     * @param input   the array to sort.
     * @param blkptr  the starting index of the subarray to sort.
     * @param len     the number of elements in the subarray to sort.
     * @return {@code true} if the sort was successful, {@code false} if the input parameters are invalid.
     */
    @Override
    public boolean sort(int[] input, int blkptr, int len) {
        if ((blkptr < 0) || (len <= 0) || (blkptr + len > input.length)) {
            return false;
        }

        if (len == 1) {
            return true; // Nothing to sort
        }

        if (this.cmp == null) {
            recursiveSort(input, LEFTMOST_BITS, blkptr, blkptr + len);
        } else {
            recursiveSort(input, LEFTMOST_BITS, blkptr, blkptr + len, this.cmp);
        }

        return true;
    }

    /**
     * Recursively sorts the specified subarray of the input array using the Dual-Pivot Quicksort.
     *
     * @param block  the array to sort.
     * @param bits   the leftmost bits used for the recursive sorting.
     * @param low    the start index of the subarray to sort.
     * @param high   the end index (exclusive) of the subarray to sort.
     */
    private static void recursiveSort(int[] block, int bits, int low, int high) {
        final int end = high - 1;
        final int length = high - low;

        if ((bits & 1) != 0) {
           if (length < NANO_INSERTION_SORT_THRESHOLD) {
              if (length > 1)
                 nanoInsertionSort(block, low, high);

              return;
           }

           if (length < PAIR_INSERTION_SORT_THRESHOLD) {
              pairInsertionSort(block, low, end);
              return;
           }
        }

        bits -= 2;

        // Switch to heap sort on the leftmost part or
        // if the execution time is becoming quadratic
        if ((length < HEAP_SORT_THRESHOLD) || (bits < 0)) {
           if (length > 1)
              heapSort(block, low, end);

           return;
        }

        // Check if the array is nearly sorted
        if (mergingSort(block, low, high))
           return;

        // Splitting step using approximation of the golden ratio
        final int step = (length >> 3) * 3 + 3;

        // Use 5 elements for pivot selection
        final int e1 = low + step;
        final int e5 = end - step;
        final int e3 = (e1 + e5) >>> 1;
        final int e2 = (e1 + e3) >>> 1;
        final int e4 = (e3 + e5) >>> 1;

        // Sort these elements in place by the combination of 5-element
        // sorting network and insertion sort.
        if (block[e5] < block[e3])
           swap(block, e3, e5);

        if (block[e4] < block[e2])
           swap(block, e2, e4);

        if (block[e5] < block[e4])
           swap(block, e4, e5);

        if (block[e3] < block[e2])
           swap(block, e2, e3);

        if (block[e4] < block[e3])
            swap(block, e3, e4);

        if (block[e1] > block[e2]) {
           final int t = block[e1];
           block[e1] = block[e2];
           block[e2] = t;

           if (t > block[e3]) {
              block[e2] = block[e3];
              block[e3] = t;

              if (t > block[e4]) {
                 block[e3] = block[e4];
                 block[e4] = t;

                 if (t > block[e5]) {
                    block[e4] = block[e5];
                    block[e5] = t;
                 }
              }
           }
        }

        // Index of the last element of the left part
        int lower = low;

        // Index of the first element of the right part
        int upper = end;

        if ((block[e1] < block[e2]) && (block[e2] < block[e3]) &&
           (block[e3] < block[e4]) && (block[e4] < block[e5])) {
           // Partitioning with two pivots
           // Use the first and the fifth elements as the pivots.
           final int pivot1 = block[e1];
           final int pivot2 = block[e5];

           // The first and the last elements to be sorted are moved to the
           // locations formerly occupied by the pivots. When partitioning
           // is completed, the pivots are swapped back into their final
           // positions, and excluded from subsequent sorting.
           block[e1] = block[lower];
           block[e5] = block[upper];

           // Skip elements, which are less or greater than the pivots.
           lower++;
           upper--;

           while (block[lower] < pivot1)
              lower++;

           while (block[upper] > pivot2)
              upper--;

           lower--;
           upper++;

           for (int k=upper; --k>lower; ) {
              final int ak = block[k];

              if (ak < pivot1) {
                 // Move block[k] to the left side
                 while (block[++lower] < pivot1) {}

                 if (lower > k) {
                    lower = k;
                    break;
                 }

                 if (block[lower] > pivot2) {
                    // block[lower] >= pivot1
                    upper--;
                    block[k] = block[upper];
                    block[upper] = block[lower];
                 }
                 else {
                    // pivot1 <= block[lower] <= pivot2
                    block[k] = block[lower];
                 }

                 block[lower] = ak;
              }
              else if (ak > pivot2) {
                 // Move block[k] to the right side
                 upper--;
                 block[k] = block[upper];
                 block[upper] = ak;
              }
           }

           // Swap the pivots back into their final positions
           block[low] = block[lower];
           block[lower] = pivot1;
           block[end] = block[upper];
           block[upper] = pivot2;

           // Recursion
           recursiveSort(block, bits|1, upper+1, high);
           recursiveSort(block, bits, low, lower);
           recursiveSort(block, bits|1, lower+1, upper);
       }
       else {
           // Partitioning with one pivot

           // Use the third element as the pivot as an approximation of the median.
           final int pivot = block[e3];

           // The first element to be sorted is moved to the location
           // formerly occupied by the pivot. When partitioning is
           // completed, the pivot is swapped back into its final
           // position, and excluded from subsequent sorting.
           block[e3] = block[lower];
           upper++;

           for (int k=upper-1; k>lower; k--) {
              if (block[k] == pivot)
                  continue;

              final int ak = block[k];

              if (ak < pivot) {
                 // Move block[k] to the left side
                 lower++;

                 while (block[lower] < pivot)
                    lower++;

                 if (lower > k) {
                    lower = k;
                    break;
                 }

                 block[k] = pivot;

                 if (block[lower] > pivot) {
                    upper--;
                    block[upper] = block[lower];
                 }

                 block[lower] = ak;
              }
              else {
                 // Move block[k] to the right side
                 block[k] = pivot;
                 upper--;
                 block[upper] = ak;
              }
           }

           // Swap the pivot into its final position.
           block[low] = block[lower];
           block[lower] = pivot;

           // Recursion
           recursiveSort(block, bits|1, upper, high);
           recursiveSort(block, bits, low, lower);
        }
     }

     /**
      * Swaps two elements in the given array.
      *
      * @param block the array where the elements will be swapped
      * @param idx0 the index of the first element to swap
      * @param idx1 the index of the second element to swap
      */
     private static void swap(int[] block, int idx0, int idx1) {
         final int t = block[idx0];
         block[idx0] = block[idx1];
         block[idx1] = t;
     }

     /**
      * Performs an optimized version of insertion sort for small arrays or partitions.
      * This method is designed to handle small partitions during the Dual-Pivot Quicksort process.
      * The elements from the left part of the array act as sentinels, which helps to skip expensive
      * checks on the left boundary during the sorting process.
      *
      * @param block the array to sort
      * @param low the starting index of the subarray to sort
      * @param high the ending index (exclusive) of the subarray to sort
      */
     private static void nanoInsertionSort(int[] block, int low, final int high) {
         // In the context of Quicksort, the elements from the left part
         // play the role of sentinels. Therefore expensive check of the
         // left range on each iteration can be skipped.
         while (low < high) {
             int k = low;
             final int ak = block[k];
             k--;

             while (ak < block[k]) {
                 block[k + 1] = block[k];
                 k--;
             }

             block[k + 1] = ak;
             low++;
         }
     }

     /**
      * Performs an optimized paired insertion sort for small partitions.
      * In this method, two elements are inserted at once on each iteration.
      * The method first inserts the greater element, and then inserts the smaller
      * element from the position where the greater element was inserted.
      * It also takes advantage of the sentinel elements, which reduces boundary checks.
      *
      * @param block the array to sort
      * @param left the starting index of the subarray to sort
      * @param right the ending index (exclusive) of the subarray to sort
      */
     private static void pairInsertionSort(int[] block, int left, final int right) {
         // Align left boundary
         left -= ((left ^ right) & 1);

         // Two elements are inserted at once on each iteration.
         // At first, we insert the greater element (a2) and then
         // insert the less element (a1), but from position where
         // the greater element was inserted. In the context of a
         // Dual-Pivot Quicksort, the elements from the left part
         // play the role of sentinels. Therefore expensive check
         // of the left range on each iteration can be skipped.
         left++;

         while (left < right) {
             left++;
             int k = left;
             int a1 = block[k];

             if (block[k - 2] > block[k - 1]) {
                 k--;
                 int a2 = block[k];

                 if (a1 > a2) {
                     a2 = a1;
                     a1 = block[k];
                 }

                 k--;

                 while (a2 < block[k]) {
                     block[k + 2] = block[k];
                     k--;
                 }

                 k++;
                 block[k + 1] = a2;
             }

             k--;

             while (a1 < block[k]) {
                 block[k + 1] = block[k];
                 k--;
             }

             block[k + 1] = a1;
             left++;
         }
     }

     /**
      * Performs HeapSort on a specified subarray of the given array.
      * HeapSort is used when the partition size becomes large enough that the
      * efficiency of quicksort begins to degrade. This method builds a heap and
      * performs heapification to sort the array in-place.
      *
      * @param block the array to sort
      * @param left the starting index of the subarray to sort
      * @param right the ending index (exclusive) of the subarray to sort
      */
     private static void heapSort(int[] block, int left, int right) {
         for (int k = (left + 1 + right) >>> 1; k > left; ) {
             k--;
             pushDown(block, k, block[k], left, right);
         }

         for (int k = right; k > left; k--) {
             final int max = block[left];
             pushDown(block, left, block[k], left, k);
             block[k] = max;
         }
     }

     /**
      * Performs the "push down" operation for heapification during HeapSort.
      * This ensures that the heap property is maintained by moving the element at
      * index {@code p} down the heap to its correct position.
      *
      * @param block the array representing the heap
      * @param p the index of the element to "push down"
      * @param value the value of the element to "push down"
      * @param left the starting index of the heap
      * @param right the ending index (exclusive) of the heap
      */
     private static void pushDown(int[] block, int p, int value, int left, int right) {
         while (true) {
             int k = (p << 1) - left + 2;

             if ((k > right) || (block[k - 1] > block[k]))
                 k--;

             if ((k > right) || (block[k] <= value)) {
                 block[p] = value;
                 return;
             }

             block[p] = block[k];
             p = k;
         }
     }


     /**
      * Performs the merging sort algorithm on the specified subarray of the given array.
      * Merging Sort is used when the size of the subarray exceeds a certain threshold.
      * It tries to identify ascending and descending sequences in the array and merge them efficiently.
      *
      * @param block the array to sort
      * @param low the starting index of the subarray to sort
      * @param high the ending index (exclusive) of the subarray to sort
      * @return {@code true} if the array is considered "highly structured" and merging was done;
      *         {@code false} otherwise
      */
     private static boolean mergingSort(int[] block, int low, int high) {
         final int length = high - low;

         if (length < MERGING_SORT_THRESHOLD)
             return false;

         final int max = (length > 2048000) ? 2000 : (length >> 10) | 5;
         final int[] run = new int[max + 1];
         int count = 0;
         int last = low;
         run[0] = low;

         // Check if the array is highly structured.
         for (int k = low + 1; (k < high) && (count < max); ) {
             if (block[k - 1] < block[k]) {
                 // Identify ascending sequence
                 while (++k < high) {
                     if (block[k - 1] > block[k])
                         break;
                 }
             } else if (block[k - 1] > block[k]) {
                 // Identify descending sequence
                 while (++k < high) {
                     if (block[k - 1] < block[k])
                         break;
                 }

                 // Reverse the run into ascending order
                 for (int i = last - 1, j = k; ((++i < --j) && (block[i] > block[j])); )
                     swap(block, i, j);
             } else {
                 // Sequence with equal elements
                 final int ak = block[k];

                 while (++k < high) {
                     if (ak != block[k])
                         break;
                 }

                 if (k < high)
                     continue;
             }

             if ((count == 0) || (block[last - 1] > block[last]))
                 count++;

             last = k;
             run[count] = k;
         }

         // The array is highly structured => merge all runs
         if ((count < max) && (count > 1))
             merge(block, new int[length], true, low, run, 0, count);

         return count < max;
     }

     /**
      * Merges two subarrays from the specified array into a single sorted array.
      * The method works recursively and splits the array into smaller parts to efficiently merge them.
      *
      * @param block1 the first array to merge
      * @param block2 the second array to merge
      * @param isSource flag indicating which array is the source of the merge
      * @param offset the offset for the merging operation
      * @param run the array of runs that define the segments to merge
      * @param lo the starting index of the range to merge
      * @param hi the ending index of the range to merge
      * @return the merged array after the merge operation is complete
      */
     private static int[] merge(int[] block1, int[] block2, boolean isSource, int offset, int[] run, int lo, int hi) {
         if (hi - lo == 1) {
             if (isSource == true)
                 return block1;

             for (int i = run[hi], j = i - offset, low = run[lo]; i > low; i--, j--)
                 block2[j] = block1[i];

             return block2;
         }

         final int mi = (lo + hi) >>> 1;
         final int[] a1 = merge(block1, block2, !isSource, offset, run, lo, mi);
         final int[] a2 = merge(block1, block2, true, offset, run, mi, hi);

         return merge((a1 == block1) ? block2 : block1,
                      (a1 == block1) ? run[lo] - offset : run[lo],
                      a1,
                      (a1 == block2) ? run[lo] - offset : run[lo],
                      (a1 == block2) ? run[mi] - offset : run[mi],
                      a2,
                      (a2 == block2) ? run[mi] - offset : run[mi],
                      (a2 == block2) ? run[hi] - offset : run[hi]);
     }

     /**
      * Merges two sorted subarrays into a destination array.
      * This method assumes that both subarrays are sorted and merges them into one sorted array.
      *
      * @param dst the destination array to hold the merged result
      * @param k the starting index of the destination array
      * @param block1 the first sorted array to merge
      * @param i the starting index of the first array
      * @param hi the ending index (exclusive) of the first array
      * @param block2 the second sorted array to merge
      * @param j the starting index of the second array
      * @param hj the ending index (exclusive) of the second array
      * @return the destination array after merging
      */
     private static int[] merge(int[] dst, int k,
              int[] block1, int i, int hi, int[] block2, int j, int hj) {
         while (true) {
             dst[k++] = (block1[i] < block2[j]) ? block1[i++] : block2[j++];

             if (i == hi) {
                 while (j < hj)
                     dst[k++] = block2[j++];

                 return dst;
             }

             if (j == hj) {
                 while (i < hi)
                     dst[k++] = block1[i++];

                 return dst;
             }
         }
     }

     /**
      * Recursively sorts the specified subarray using a specified comparison function.
      * This method is typically used to implement sorting algorithms that need to recursively
      * divide the array into smaller parts and apply sorting to each part.
      *
      * @param block the array to sort
      * @param bits the number of bits to consider for the sorting
      * @param low the starting index of the subarray to sort
      * @param high the ending index (exclusive) of the subarray to sort
      * @param cmp the comparator to use for sorting
      */
     private static void recursiveSort(int[] block, int bits, int low, int high, ArrayComparator cmp) {
        final int end = high - 1;
        final int length = high - low;

        if ((bits & 1) != 0) {
           if (length < NANO_INSERTION_SORT_THRESHOLD) {
              if (length > 1)
                 nanoInsertionSort(block, low, high, cmp);

              return;
           }

           if (length < PAIR_INSERTION_SORT_THRESHOLD) {
              pairInsertionSort(block, low, end, cmp);
              return;
           }
        }

        bits -= 2;

        // Switch to heap sort on the leftmost part or
        // if the execution time is becoming quadratic
        if ((length < HEAP_SORT_THRESHOLD) || (bits < 0)) {
           if (length > 1)
              heapSort(block, low, end, cmp);

           return;
        }

        // Check if the array is nearly sorted
        if (mergingSort(block, low, high, cmp))
           return;

        // Splitting step using approximation of the golden ratio
        final int step = (length >> 3) * 3 + 3;

        // Use 5 elements for pivot selection
        final int e1 = low + step;
        final int e5 = end - step;
        final int e3 = (e1 + e5) >>> 1;
        final int e2 = (e1 + e3) >>> 1;
        final int e4 = (e3 + e5) >>> 1;

        // Sort these elements in place by the combination of 5-element
        // sorting network and insertion sort.
        if (cmp.compare(block[e5], block[e3]) < 0)
           swap(block, e3, e5);

        if (cmp.compare(block[e4], block[e2]) < 0)
           swap(block, e2, e4);

        if (cmp.compare(block[e5], block[e4]) < 0)
           swap(block, e4, e5);

        if (cmp.compare(block[e3], block[e2]) < 0)
           swap(block, e2, e3);

        if (cmp.compare(block[e4], block[e3]) < 0)
            swap(block, e3, e4);

        if (cmp.compare(block[e1], block[e2]) > 0) {
           final int t = block[e1];
           block[e1] = block[e2];
           block[e2] = t;

           if (cmp.compare(t, block[e3]) > 0) {
              block[e2] = block[e3];
              block[e3] = t;

              if (cmp.compare(t, block[e4]) > 0) {
                 block[e3] = block[e4];
                 block[e4] = t;

                 if (cmp.compare(t, block[e5]) > 0) {
                    block[e4] = block[e5];
                    block[e5] = t;
                 }
              }
           }
        }

        // Index of the last element of the left part
        int lower = low;

        // Index of the first element of the right part
        int upper = end;

        if ((cmp.compare(block[e1], block[e2]) < 0) && (cmp.compare(block[e2], block[e3]) < 0) &&
            (cmp.compare(block[e3], block[e4]) < 0) && (cmp.compare(block[e4], block[e5]) < 0)) {
           // Partitioning with two pivots
           // Use the first and the fifth elements as the pivots.
           final int pivot1 = block[e1];
           final int pivot2 = block[e5];

           // The first and the last elements to be sorted are moved to the
           // locations formerly occupied by the pivots. When partitioning
           // is completed, the pivots are swapped back into their final
           // positions, and excluded from subsequent sorting.
           block[e1] = block[lower];
           block[e5] = block[upper];

           // Skip elements, which are less or greater than the pivots.
           lower++;
           upper--;

           while (cmp.compare(block[lower], pivot1) < 0)
              lower++;

           while (cmp.compare(block[upper], pivot2) > 0)
              upper--;

           lower--;
           upper++;

           for (int k=upper; --k>lower; ) {
              final int ak = block[k];

              if (cmp.compare(ak, pivot1) < 0) {
                 // Move block[k] to the left side
                 while (cmp.compare(block[++lower], pivot1) < 0) {}

                 if (lower > k) {
                    lower = k;
                    break;
                 }

                 if (cmp.compare(block[lower], pivot2) > 0) {
                    // block[lower] >= pivot1
                    upper--;
                    block[k] = block[upper];
                    block[upper] = block[lower];
                 }
                 else {
                    // pivot1 <= block[lower] <= pivot2
                    block[k] = block[lower];
                 }

                 block[lower] = ak;
              }
              else if (cmp.compare(ak, pivot2) > 0) {
                 // Move block[k] to the right side
                 upper--;
                 block[k] = block[upper];
                 block[upper] = ak;
              }
           }

           // Swap the pivots back into their final positions
           block[low] = block[lower];
           block[lower] = pivot1;
           block[end] = block[upper];
           block[upper] = pivot2;

           // Recursion
           recursiveSort(block, bits|1, upper+1, high, cmp);
           recursiveSort(block, bits, low, lower, cmp);
           recursiveSort(block, bits|1, lower+1, upper, cmp);
       }
       else {
           // Partitioning with one pivot

           // Use the third element as the pivotas an approximation of the median.
           final int pivot = block[e3];

           // The first element to be sorted is moved to the location
           // formerly occupied by the pivot. When partitioning is
           // completed, the pivot is swapped back into its final
           // position, and excluded from subsequent sorting.
           block[e3] = block[lower];
           upper++;

           for (int k=upper-1; k>lower; k--) {
              if (cmp.compare(block[k], pivot) == 0)
                  continue;

              final int ak = block[k];

              if (cmp.compare(ak, pivot) < 0) {
                 // Move block[k] to the left side
                 lower++;

                 while (cmp.compare(block[lower], pivot) < 0)
                    lower++;

                 if (lower > k) {
                    lower = k;
                    break;
                 }

                 block[k] = pivot;

                 if (cmp.compare(block[lower], pivot) > 0) {
                    upper--;
                    block[upper] = block[lower];
                 }

                 block[lower] = ak;
              }
              else {
                 // Move block[k] to the right side
                 block[k] = pivot;
                 upper--;
                 block[upper] = ak;
              }
           }

           // Swap the pivot into its final position.
           block[low] = block[lower];
           block[lower] = pivot;

           // Recursion
           recursiveSort(block, bits|1, upper, high, cmp);
           recursiveSort(block, bits, low, lower, cmp);
        }
     }

     /**
      * Performs a nano-insertion sort on the specified subarray of the given array.
      * This method is an optimization of the regular insertion sort used in the context of Quicksort.
      * Elements are inserted in order while minimizing the number of checks by skipping expensive checks
      * for elements in the left part of the array that play the role of sentinels.
      *
      * @param block the array to sort
      * @param low the starting index of the subarray to sort
      * @param high the ending index (exclusive) of the subarray to sort
      * @param cmp the comparator to use for comparing elements
      */
     private static void nanoInsertionSort(int[] block, int low, final int high, ArrayComparator cmp) {
         // In the context of Quicksort, the elements from the left part
         // play the role of sentinels. Therefore expensive check of the
         // left range on each iteration can be skipped.
         while (low < high) {
             int k = low;
             final int ak = block[k];
             k--;

             while (cmp.compare(ak, block[k]) < 0) {
                 block[k + 1] = block[k];
                 k--;
             }

             block[k + 1] = ak;
             low++;
         }
     }

     /**
      * Performs a pair-insertion sort on the specified subarray of the given array.
      * This method sorts pairs of elements on each iteration, ensuring that the larger element
      * is inserted first, followed by the smaller element. In the context of Dual-Pivot Quicksort,
      * elements from the left part play the role of sentinels.
      *
      * @param block the array to sort
      * @param left the starting index of the subarray to sort
      * @param right the ending index (exclusive) of the subarray to sort
      * @param cmp the comparator to use for comparing elements
      */
     private static void pairInsertionSort(int[] block, int left, final int right, ArrayComparator cmp) {
         // Align left boundary
         left -= ((left ^ right) & 1);

         // Two elements are inserted at once on each iteration.
         // At first, we insert the greater element (a2) and then
         // insert the less element (a1), but from position where
         // the greater element was inserted. In the context of a
         // Dual-Pivot Quicksort, the elements from the left part
         // play the role of sentinels. Therefore expensive check
         // of the left range on each iteration can be skipped.
         left++;

         while (left < right) {
             left++;
             int k = left;
             int a1 = block[k];

             if (cmp.compare(block[k - 2], block[k - 1]) > 0) {
                 k--;
                 int a2 = block[k];

                 if (cmp.compare(a1, a2) > 0) {
                     a2 = a1;
                     a1 = block[k];
                 }

                 k--;

                 while (cmp.compare(a2, block[k]) < 0) {
                     block[k + 2] = block[k];
                     k--;
                 }

                 k++;
                 block[k + 1] = a2;
             }

             k--;

             while (cmp.compare(a1, block[k]) < 0) {
                 block[k + 1] = block[k];
                 k--;
             }

             block[k + 1] = a1;
             left++;
         }
     }

     /**
      * Performs a heap sort on the specified subarray of the given array.
      * Heap Sort builds a max-heap and repeatedly extracts the largest element,
      * placing it at the correct position in the array.
      *
      * @param block the array to sort
      * @param left the starting index of the subarray to sort
      * @param right the ending index (exclusive) of the subarray to sort
      * @param cmp the comparator to use for comparing elements
      */
     private static void heapSort(int[] block, int left, int right, ArrayComparator cmp) {
         for (int k = (left + 1 + right) >>> 1; k > left; ) {
             k--;
             pushDown(block, k, block[k], left, right, cmp);
         }

         for (int k = right; k > left; k--) {
             final int max = block[left];
             pushDown(block, left, block[k], left, k, cmp);
             block[k] = max;
         }
     }

     /**
      * Performs the push down operation for heap sorting.
      * This ensures that the heap property is maintained by pushing a value down
      * the heap until it reaches a position where the heap property holds.
      *
      * @param block the array representing the heap
      * @param p the index of the element to push down
      * @param value the value to push down
      * @param left the left boundary of the subarray
      * @param right the right boundary of the subarray
      * @param cmp the comparator to use for comparing elements
      */
     private static void pushDown(int[] block, int p, int value, int left, int right, ArrayComparator cmp) {
         while (true) {
             int k = (p << 1) - left + 2;

             if ((k > right) || (cmp.compare(block[k - 1], block[k]) > 0))
                 k--;

             if ((k > right) || (cmp.compare(block[k], value) <= 0)) {
                 block[p] = value;
                 return;
             }

             block[p] = block[k];
             p = k;
         }
     }


     /**
      * Performs a merging sort on the provided subarray.
      * This method checks if the array is highly structured and attempts to merge runs
      * efficiently if the array is already partially sorted.
      *
      * @param block the array to sort
      * @param low the starting index of the subarray to sort
      * @param high the ending index (exclusive) of the subarray to sort
      * @param cmp the comparator to use for comparing elements
      * @return true if the array is already sorted or partially sorted, false otherwise
      */
     private static boolean mergingSort(int[] block, int low, int high, ArrayComparator cmp) {
         final int length = high - low;

         if (length < MERGING_SORT_THRESHOLD)
             return false;

         final int max = (length > 2048000) ? 2000 : (length >> 10) | 5;
         final int[] run = new int[max + 1];
         int count = 0;
         int last = low;
         run[0] = low;

         // Check if the array is highly structured.
         for (int k = low + 1; (k < high) && (count < max); ) {
             if (cmp.compare(block[k - 1], block[k]) < 0) {
                 // Identify ascending sequence
                 while (++k < high) {
                     if (cmp.compare(block[k - 1], block[k]) > 0)
                         break;
                 }
             } else if (cmp.compare(block[k - 1], block[k]) > 0) {
                 // Identify descending sequence
                 while (++k < high) {
                     if (cmp.compare(block[k - 1], block[k]) < 0)
                         break;
                 }

                 // Reverse the run into ascending order
                 for (int i = last - 1, j = k; ((++i < --j) && (cmp.compare(block[i], block[j]) > 0)); )
                     swap(block, i, j);
             } else {
                 // Sequence with equal elements
                 final int ak = block[k];

                 while (++k < high) {
                     if (cmp.compare(ak, block[k]) != 0)
                         break;
                 }

                 if (k < high)
                     continue;
             }

             if ((count == 0) || (cmp.compare(block[last - 1], block[last]) > 0))
                 count++;

             last = k;
             run[count] = k;
         }

         // The array is highly structured => merge all runs
         if ((count < max) && (count > 1))
             merge(block, new int[length], true, low, run, 0, count, cmp);

         return count < max;
     }

     /**
      * Merges two sorted subarrays from the given array into one sorted array.
      * This method is used in a recursive merge process to merge portions of the array.
      *
      * @param block1 the first array to merge
      * @param block2 the second array to merge
      * @param isSource indicates whether the source array is block1 or block2
      * @param offset the offset to account for the difference in array indexing
      * @param run an array containing the run indices
      * @param lo the lower bound of the subarray to merge
      * @param hi the upper bound (exclusive) of the subarray to merge
      * @param cmp the comparator to use for comparing elements
      * @return the merged array
      */
     private static int[] merge(int[] block1, int[] block2, boolean isSource,
             int offset, int[] run, int lo, int hi, ArrayComparator cmp) {
         if (hi - lo == 1) {
             if (isSource == true)
                 return block1;

             for (int i = run[hi], j = i - offset, low = run[lo]; i > low; i--, j--)
                 block2[j] = block1[i];

             return block2;
         }

         final int mi = (lo + hi) >>> 1;
         final int[] a1 = merge(block1, block2, !isSource, offset, run, lo, mi, cmp);
         final int[] a2 = merge(block1, block2, true, offset, run, mi, hi, cmp);

         return merge((a1 == block1) ? block2 : block1,
                      (a1 == block1) ? run[lo] - offset : run[lo],
                      a1,
                      (a1 == block2) ? run[lo] - offset : run[lo],
                      (a1 == block2) ? run[mi] - offset : run[mi],
                      a2,
                      (a2 == block2) ? run[mi] - offset : run[mi],
                      (a2 == block2) ? run[hi] - offset : run[hi],
                      cmp);
     }

     /**
      * Merges two sorted subarrays into a destination array.
      * This is used during the merge process to combine the elements of the two subarrays into one sorted array.
      *
      * @param dst the destination array to store the merged result
      * @param k the index to start inserting the merged elements
      * @param block1 the first sorted array to merge
      * @param i the current index in the first array
      * @param hi the upper bound (exclusive) of the first array
      * @param block2 the second sorted array to merge
      * @param j the current index in the second array
      * @param hj the upper bound (exclusive) of the second array
      * @param cmp the comparator to use for comparing elements
      * @return the destination array with the merged elements
      */
     private static int[] merge(int[] dst, int k,
             int[] block1, int i, int hi, int[] block2, int j, int hj, ArrayComparator cmp) {
         while (true) {
             dst[k++] = (cmp.compare(block1[i], block2[j]) < 0) ? block1[i++] : block2[j++];

             if (i == hi) {
                 while (j < hj)
                     dst[k++] = block2[j++];

                 return dst;
             }

             if (j == hj) {
                 while (i < hi)
                     dst[k++] = block1[i++];

                 return dst;
             }
         }
     }

}
