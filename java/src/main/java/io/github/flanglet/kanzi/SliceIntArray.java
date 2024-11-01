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

package io.github.flanglet.kanzi;

import java.util.Objects;

/**
 * A lightweight implementation of a slice for an integer array.
 *
 * <p>This class allows for managing a portion of an integer array, providing
 * a means to represent a subset of the array with a specified length and
 * starting index. This can be useful for efficiently handling integer data
 * without creating multiple copies.</p>
 */
public final class SliceIntArray {
    public int[] array; // array.length is the slice capacity
    public int index;
    public int length;

    /**
     * Constructs an empty {@code SliceIntArray} with a zero-length array.
     */
    public SliceIntArray() {
        this(new int[0], 0, 0);
    }

    /**
     * Constructs a {@code SliceIntArray} with the specified array and index.
     *
     * @param array the integer array
     * @param idx the starting index of the slice
     * @throws NullPointerException if the provided array is null
     * @throws NullPointerException if the provided index is negative
     */
    public SliceIntArray(int[] array, int idx) {
        if (array == null)
            throw new NullPointerException("The array cannot be null");
        if (idx < 0)
            throw new NullPointerException("The index cannot be negative");

        this.array = array;
        this.length = array.length;
        this.index = idx;
    }

    /**
     * Constructs a {@code SliceIntArray} with the specified array, length, and index.
     *
     * @param array the integer array
     * @param length the length of the slice
     * @param idx the starting index of the slice
     * @throws NullPointerException if the provided array is null
     * @throws IllegalArgumentException if the provided length is negative
     * @throws NullPointerException if the provided index is negative
     */
    public SliceIntArray(int[] array, int length, int idx) {
        if (array == null)
            throw new NullPointerException("The array cannot be null");
        if (length < 0)
            throw new IllegalArgumentException("The length cannot be negative");
        if (idx < 0)
            throw new NullPointerException("The index cannot be negative");

        this.array = array;
        this.length = length;
        this.index = idx;
    }

    @Override
    public boolean equals(Object o) {
        try {
            if (o == null)
                return false;
            if (this == o)
                return true;

            SliceIntArray sa = (SliceIntArray) o;
            return (this.array == sa.array) &&
                   (this.length == sa.length) &&
                   (this.index == sa.index);
        } catch (ClassCastException e) {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(this.array);
    }

    @Override
    @SuppressWarnings("lgtm [java/print-array]")
    public String toString() {
        StringBuilder builder = new StringBuilder(100);
        builder.append("[ data=");
        builder.append(String.valueOf(this.array));
        builder.append(", len=");
        builder.append(this.length);
        builder.append(", idx=");
        builder.append(this.index);
        builder.append("]");
        return builder.toString();
    }

    /**
     * Validates the provided {@code SliceIntArray} instance.
     *
     * @param sa the {@code SliceIntArray} to validate
     * @return {@code true} if the instance is valid, {@code false} otherwise
     */
    public static boolean isValid(SliceIntArray sa) {
        if (sa == null)
            return false;
        if (sa.array == null)
            return false;
        if (sa.index < 0)
            return false;
        if (sa.length < 0)
            return false;

        return (sa.index <= sa.array.length);
    }
}
