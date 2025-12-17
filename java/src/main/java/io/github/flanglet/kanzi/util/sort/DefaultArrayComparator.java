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

/**
 * A comparator for comparing elements in an integer array. This class implements the {@link ArrayComparator} interface
 * and provides a mechanism to compare two elements based on their values. The comparison also accounts for stable sorting
 * by considering their indices when the values are equal.
 *
 * <p>This class is immutable and thread-safe as it holds a reference to the input array but does not modify it.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * int[] array = { 5, 2, 8, 1 };
 * DefaultArrayComparator comparator = new DefaultArrayComparator(array);
 * int result = comparator.compare(0, 1); // Compares array[0] (5) and array[1] (2)
 * </pre>
 *
 * @see ArrayComparator
 */
public final class DefaultArrayComparator implements ArrayComparator {

    private final int[] array;

    /**
     * Constructs a new {@code DefaultArrayComparator} using the specified integer array.
     *
     * @param array the array to compare elements in; must not be {@code null}
     * @throws NullPointerException if the provided array is {@code null}
     */
    public DefaultArrayComparator(int[] array) {
        if (array == null)
            throw new NullPointerException("Invalid null array parameter");

        this.array = array;
    }

    /**
     * Compares two elements of the array at the specified indices.
     * <p>
     * The comparison is based on the values of the elements at the provided indices. If the values are equal,
     * the method returns a comparison based on their indices to maintain stability in sorting.
     * </p>
     *
     * @param lidx the index of the first element to compare
     * @param ridx the index of the second element to compare
     * @return a negative integer if the element at {@code lidx} is less than the element at {@code ridx},
     *         a positive integer if the element at {@code lidx} is greater than the element at {@code ridx},
     *         or zero if they are equal
     */
    @Override
    public int compare(int lidx, int ridx) {
        int res = this.array[lidx] - this.array[ridx];

        // Make the sort stable
        if (res == 0)
            res = lidx - ridx;

        return res;
    }
}

