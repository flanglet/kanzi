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

package io.github.flanglet.kanzi.util.hash;


import io.github.flanglet.kanzi.Memory;

/**
 * XXHash32 is an implementation of the 32-bit variant of the XXHash algorithm,
 * which is a fast non-cryptographic hash function. It is designed for high-speed
 * hashing, commonly used in applications where performance is critical, such as 
 * checksums, hash tables, and data integrity verification.
 * Port to Java of the original source code: https://github.com/Cyan4973/xxHash
 *
 * <p>XXHash32 uses a sequence of rounds with constant mixing primes to process 
 * the input data and produce a 32-bit hash value. This class allows for an 
 * optional user-defined seed, providing a degree of variability in the output.
 */
public class XXHash32 {

    // Constants used in the hashing algorithm
    private static final int PRIME32_1 = -1640531535;
    private static final int PRIME32_2 = -2048144777;
    private static final int PRIME32_3 = -1028477379;
    private static final int PRIME32_4 = 668265263;
    private static final int PRIME32_5 = 374761393;

    // The seed used for hashing
    private int seed;

    /**
     * Default constructor that initializes the hash function with a seed based on
     * the current system time in nanoseconds.
     */
    public XXHash32() {
        this((int) (System.nanoTime()));
    }

    /**
     * Constructs an XXHash32 instance with a specified seed.
     * 
     * @param seed The seed value to be used in the hash computation.
     */
    public XXHash32(int seed) {
        this.seed = seed;
    }

    /**
     * Sets the seed value for the hash computation. This allows for custom seed values
     * to modify the output hash.
     * 
     * @param seed The new seed value.
     */
    public void setSeed(int seed) {
        this.seed = seed;
    }

    /**
     * Computes the 32-bit hash of the provided byte array.
     * This method uses the entire byte array, starting from index 0.
     * 
     * @param data The byte array to be hashed.
     * @return The 32-bit hash value of the input data.
     */
    public int hash(byte[] data) {
        return this.hash(data, 0, data.length);
    }

    /**
     * Computes the 32-bit hash of the provided byte array, with the option to specify
     * an offset and length of the data to be used.
     * 
     * @param data The byte array to be hashed.
     * @param offset The starting index within the byte array.
     * @param length The number of bytes to hash.
     * @return The 32-bit hash value of the input data.
     */
    public int hash(byte[] data, int offset, int length) {
        final int end = offset + length;
        int h32;
        int idx = offset;

        if (length >= 16) {
            final int end16 = end - 16;
            int v1 = this.seed + PRIME32_1 + PRIME32_2;
            int v2 = this.seed + PRIME32_2;
            int v3 = this.seed;
            int v4 = this.seed - PRIME32_1;

            // Process 16-byte blocks
            do {
                v1 = round(v1, Memory.LittleEndian.readInt32(data, idx));
                v2 = round(v2, Memory.LittleEndian.readInt32(data, idx + 4));
                v3 = round(v3, Memory.LittleEndian.readInt32(data, idx + 8));
                v4 = round(v4, Memory.LittleEndian.readInt32(data, idx + 12));
                idx += 16;
            } while (idx <= end16);

            h32 = ((v1 << 1) | (v1 >>> 31)) + ((v2 << 7) | (v2 >>> 25)) +
                  ((v3 << 12) | (v3 >>> 20)) + ((v4 << 18) | (v4 >>> 14));
        } else {
            h32 = this.seed + PRIME32_5;
        }

        h32 += length;

        // Process remaining data (less than 16 bytes)
        while (idx <= end - 4) {
            h32 += (Memory.LittleEndian.readInt32(data, idx) * PRIME32_3);
            h32 = ((h32 << 17) | (h32 >>> 15)) * PRIME32_4;
            idx += 4;
        }

        while (idx < end) {
            h32 += ((data[idx] & 0xFF) * PRIME32_5);
            h32 = ((h32 << 11) | (h32 >>> 21)) * PRIME32_1;
            idx++;
        }

        // Finalization step
        h32 ^= (h32 >>> 15);
        h32 *= PRIME32_2;
        h32 ^= (h32 >>> 13);
        h32 *= PRIME32_3;
        return h32 ^ (h32 >>> 16);
    }

    /**
     * Performs a single round of mixing for the hash value.
     * 
     * @param acc The accumulator value to be mixed.
     * @param val The value to be mixed with the accumulator.
     * @return The new mixed accumulator value.
     */
    private static int round(int acc, int val) {
        acc += (val * PRIME32_2);
        return ((acc << 13) | (acc >>> 19)) * PRIME32_1;
    }
}

