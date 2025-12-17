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

package io.github.flanglet.kanzi;

import java.util.Arrays;
import java.util.Objects;

/**
 * A lightweight implementation of a byte array slice.
 *
 * <p>
 * This class provides a way to manage a portion of a byte array, allowing for
 * the representation of a subset of the array with a specified length and
 * starting index. This can be useful for handling byte data efficiently without
 * creating multiple copies.
 * </p>
 */
public final class SliceByteArray {
    public byte[] array; // array.length is the slice capacity
    public int length;
    public int index;

    /**
     * Constructs an empty {@code SliceByteArray} with a zero-length array.
     */
    public SliceByteArray() {
        this(new byte[0], 0, 0);
    }

    /**
     * Constructs a {@code SliceByteArray} with the specified array and index.
     *
     * @param array
     *            the byte array
     * @param idx
     *            the starting index of the slice
     * @throws NullPointerException
     *             if the provided array is null
     * @throws NullPointerException
     *             if the provided index is negative
     */
    public SliceByteArray(byte[] array, int idx) {
        if (array == null)
            throw new NullPointerException("The array cannot be null");
        if (idx < 0)
            throw new NullPointerException("The index cannot be negative");

        this.array = array;
        this.length = array.length;
        this.index = idx;
    }

    /**
     * Constructs a {@code SliceByteArray} with the specified array, length, and
     * index.
     *
     * @param array
     *            the byte array
     * @param length
     *            the length of the slice
     * @param idx
     *            the starting index of the slice
     * @throws NullPointerException
     *             if the provided array is null
     * @throws IllegalArgumentException
     *             if the provided length is negative
     * @throws NullPointerException
     *             if the provided index is negative
     */
    public SliceByteArray(byte[] array, int length, int idx) {
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

            SliceByteArray sa = (SliceByteArray) o;
            return (this.array == sa.array) && (this.length == sa.length) && (this.index == sa.index);
        } catch (ClassCastException e) {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(this.array);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(100);
        builder.append("[ data=");
        builder.append(Arrays.toString(this.array));
        builder.append(", len=");
        builder.append(this.length);
        builder.append(", idx=");
        builder.append(this.index);
        builder.append("]");
        return builder.toString();
    }

    /**
     * Validates the provided {@code SliceByteArray} instance.
     *
     * @param sa
     *            the {@code SliceByteArray} to validate
     * @return {@code true} if the instance is valid, {@code false} otherwise
     */
    public static boolean isValid(SliceByteArray sa) {
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
