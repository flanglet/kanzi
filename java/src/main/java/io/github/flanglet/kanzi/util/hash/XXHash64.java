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

package io.github.flanglet.kanzi.util.hash;

import io.github.flanglet.kanzi.Memory;

/**
 * XXHash64 is an implementation of the 64-bit variant of the XXHash algorithm,
 * which is a fast non-cryptographic hash function. It is designed for
 * high-speed hashing, and is widely used for checksums and hashing large
 * amounts of data. This class allows for a configurable seed value, and
 * provides methods for hashing byte arrays of various lengths. Port to Java of
 * the original source code: https://github.com/Cyan4973/xxHash
 * 
 * <p>
 * The algorithm processes the input data in blocks and uses a combination of
 * mix functions and bitwise operations to produce a hash value. It is optimized
 * for 64-bit platforms and can be used for general-purpose hashing where
 * cryptographic security is not a concern.
 * 
 */
public class XXHash64 {

    // Constants used in the hashing algorithm
    private static final long PRIME64_1 = 0x9E3779B185EBCA87L;
    private static final long PRIME64_2 = 0xC2B2AE3D27D4EB4FL;
    private static final long PRIME64_3 = 0x165667B19E3779F9L;
    private static final long PRIME64_4 = 0x85EBCA77C2B2AE63L;
    private static final long PRIME64_5 = 0x27D4EB2F165667C5L;

    // The seed used for hashing
    private long seed;

    /**
     * Default constructor that initializes the hash function with a seed based on
     * the current system time in nanoseconds.
     */
    public XXHash64() {
        this(System.nanoTime());
    }

    /**
     * Constructs an XXHash64 instance with a specified seed.
     * 
     * @param seed
     *            The seed value to be used in the hash computation.
     */
    public XXHash64(long seed) {
        this.seed = seed;
    }

    /**
     * Sets the seed value for the hash computation. This allows for custom seed
     * values to modify the output hash.
     * 
     * @param seed
     *            The new seed value.
     */
    public void setSeed(long seed) {
        this.seed = seed;
    }

    /**
     * Computes the 64-bit hash of the provided byte array. This method uses the
     * entire byte array, starting from index 0.
     * 
     * @param data
     *            The byte array to be hashed.
     * @return The 64-bit hash value of the input data.
     */
    public long hash(byte[] data) {
        return this.hash(data, 0, data.length);
    }

    /**
     * Computes the 64-bit hash of the provided byte array, with the option to
     * specify an offset and length of the data to be used.
     * 
     * @param data
     *            The byte array to be hashed.
     * @param offset
     *            The starting index within the byte array.
     * @param length
     *            The number of bytes to hash.
     * @return The 64-bit hash value of the input data.
     */
    public long hash(byte[] data, int offset, int length) {
        final int end = offset + length;
        long h64;
        int idx = offset;

        if (length >= 32) {
            final int end32 = end - 32;
            long v1 = this.seed + PRIME64_1 + PRIME64_2;
            long v2 = this.seed + PRIME64_2;
            long v3 = this.seed;
            long v4 = this.seed - PRIME64_1;

            // Process 32-byte blocks
            do {
                v1 = round(v1, Memory.LittleEndian.readLong64(data, idx));
                v2 = round(v2, Memory.LittleEndian.readLong64(data, idx + 8));
                v3 = round(v3, Memory.LittleEndian.readLong64(data, idx + 16));
                v4 = round(v4, Memory.LittleEndian.readLong64(data, idx + 24));
                idx += 32;
            } while (idx <= end32);

            h64 = ((v1 << 1) | (v1 >>> 31)) + ((v2 << 7) | (v2 >>> 25)) + ((v3 << 12) | (v3 >>> 20))
                    + ((v4 << 18) | (v4 >>> 14));

            // Finalization
            h64 = mergeRound(h64, v1);
            h64 = mergeRound(h64, v2);
            h64 = mergeRound(h64, v3);
            h64 = mergeRound(h64, v4);
        } else {
            h64 = this.seed + PRIME64_5;
        }

        h64 += length;

        // Process remaining data (less than 32 bytes)
        while (idx + 8 <= end) {
            h64 ^= round(0, Memory.LittleEndian.readLong64(data, idx));
            h64 = ((h64 << 27) | (h64 >>> 37)) * PRIME64_1 + PRIME64_4;
            idx += 8;
        }

        while (idx + 4 <= end) {
            h64 ^= (Memory.LittleEndian.readInt32(data, idx) * PRIME64_1);
            h64 = ((h64 << 23) | (h64 >>> 41)) * PRIME64_2 + PRIME64_3;
            idx += 4;
        }

        while (idx < end) {
            h64 ^= ((data[idx] & 0xFF) * PRIME64_5);
            h64 = ((h64 << 11) | (h64 >>> 53)) * PRIME64_1;
            idx++;
        }

        // Finalization step
        h64 ^= (h64 >>> 33);
        h64 *= PRIME64_2;
        h64 ^= (h64 >>> 29);
        h64 *= PRIME64_3;
        return h64 ^ (h64 >>> 32);
    }

    /**
     * Performs a single round of mixing for the hash value.
     * 
     * @param acc
     *            The accumulator value to be mixed.
     * @param val
     *            The value to be mixed with the accumulator.
     * @return The new mixed accumulator value.
     */
    private static long round(long acc, long val) {
        acc += (val * PRIME64_2);
        return ((acc << 31) | (acc >>> 33)) * PRIME64_1;
    }

    /**
     * Merges an additional value into the accumulator during the finalization
     * phase.
     * 
     * @param acc
     *            The current accumulator value.
     * @param val
     *            The value to be merged into the accumulator.
     * @return The updated accumulator value.
     */
    private static long mergeRound(long acc, long val) {
        acc ^= round(0, val);
        return acc * PRIME64_1 + PRIME64_4;
    }
}
