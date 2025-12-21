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

import io.github.flanglet.kanzi.IntSorter;

/**
 * The {@code MergeSort} class implements the merge sort algorithm, which is a divide-and-conquer comparison-based sorting
 * algorithm. Merge sort divides the input array into smaller sub-arrays, recursively sorts each sub-array, and then merges
 * the sorted sub-arrays back together. While conceptually simple, it is usually not very performant for smaller arrays due
 * to its recursive nature. However, merge sort is known for its stable sorting and predictable O(n log n) time complexity.
 *
 * <p>Merge sort is efficient for large datasets and nearly sorted data, but it can require significant memory overhead
 * due to the need for auxiliary space to store the merged sub-arrays. This implementation uses insertion sort for small
 * sub-arrays to improve performance on small or nearly sorted datasets.</p>
 *
 * <p>This class implements the {@code IntSorter} interface, which defines the {@code sort} method for sorting integer arrays.</p>
 */
public class MergeSort implements IntSorter {

   // Threshold for switching to insertion sort on small arrays
   private static final int SMALL_ARRAY_THRESHOLD = 32;

   // Temporary buffer for merging
   private int[] buffer;

   // Insertion sort used for small arrays
   private final IntSorter insertionSort;

   /**
    * Constructs a new {@code MergeSort} instance. This constructor initializes an empty buffer for merging and
    * uses an {@code InsertionSort} instance for sorting small arrays.
    */
   public MergeSort() {
      this.buffer = new int[0];
      this.insertionSort = new InsertionSort();
   }

   /**
    * Sorts the specified portion of the input array using the merge sort algorithm.
    *
    * <p>This method divides the array into smaller sub-arrays, recursively sorts them using merge sort, and then
    * merges the sorted sub-arrays back together. For small sub-arrays (less than {@code SMALL_ARRAY_THRESHOLD}), insertion
    * sort is used for efficiency.</p>
    *
    * @param data the array to be sorted.
    * @param start the starting index of the portion to be sorted.
    * @param count the number of elements to sort.
    * @return {@code true} if the sorting was successful, {@code false} if invalid parameters were provided (out-of-bounds indices).
    */
   @Override
   public boolean sort(int[] data, int start, int count) {
      if ((data == null) || (count < 0) || (start < 0))
         return false;

      if (start + count > data.length)
         return false;

      if (count < 2)
         return true;

      // Ensure buffer is large enough to hold the array
      if (this.buffer.length < count)
          this.buffer = new int[count];

      return this.mergesort(data, start, start + count - 1);
   }

   /**
    * Recursively performs merge sort on the specified sub-array.
    *
    * <p>This method splits the array into two halves and recursively sorts each half. Once the sub-arrays are sorted,
    * they are merged together using the {@code merge} method.</p>
    *
    * @param data the array to be sorted.
    * @param low the starting index of the sub-array to sort.
    * @param high the ending index of the sub-array to sort.
    * @return {@code true} if the sorting was successful.
    */
   private boolean mergesort(int[] data, int low, int high) {
      if (low < high) {
         int count = high - low + 1;

         // Use insertion sort for small sub-arrays
         if (count < SMALL_ARRAY_THRESHOLD)
            return this.insertionSort.sort(data, low, count);

         int middle = low + count / 2;
         this.mergesort(data, low, middle);
         this.mergesort(data, middle + 1, high);
         this.merge(data, low, middle, high);
      }

      return true;
   }

   /**
    * Merges two sorted sub-arrays into one sorted array.
    *
    * <p>This method performs the merging step of merge sort. It copies the sorted elements from the left and right halves
    * of the sub-array into a temporary buffer and then merges them back into the original array.</p>
    *
    * @param data the array containing the sub-arrays to merge.
    * @param low the starting index of the left sub-array.
    * @param middle the ending index of the left sub-array.
    * @param high the ending index of the right sub-array.
    */
   private void merge(int[] data, int low, int middle, int high) {
      int count = high - low + 1;

      // For small sub-arrays, copy the elements into the buffer
      if (count < 16) {
         for (int ii = low; ii <= high; ii++)
            this.buffer[ii] = data[ii];
      } else {
         // For larger sub-arrays, use System.arraycopy for efficiency
         System.arraycopy(data, low, this.buffer, low, count);
      }

      int i = low;
      int j = middle + 1;
      int k = low;

      // Merge the two sorted sub-arrays
      while ((i <= middle) && (j <= high)) {
         if (this.buffer[i] <= this.buffer[j])
            data[k] = this.buffer[i++];
         else
            data[k] = this.buffer[j++];

         k++;
      }

      count = middle - i + 1;

      // Copy the remaining elements of the left sub-array, if any
      if (count < 16) {
         while (i <= middle)
            data[k++] = this.buffer[i++];
      } else {
         // Use System.arraycopy for efficiency
         System.arraycopy(this.buffer, i, data, k, count);
      }
   }
}

