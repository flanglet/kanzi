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

package io.github.flanglet.kanzi.entropy;

/**
 * <p>
 * Utility class for common Huffman coding operations.
 * </p>
 */
public final class HuffmanCommon {
    /**
     * The logarithm base 2 of the maximum chunk size.
     */
    public static final int LOG_MAX_CHUNK_SIZE = 14;

    /**
     * The minimum chunk size for Huffman encoding/decoding.
     */
    public static final int MIN_CHUNK_SIZE = 1024;

    /**
     * The maximum chunk size for Huffman encoding/decoding.
     */
    public static final int MAX_CHUNK_SIZE = 1 << LOG_MAX_CHUNK_SIZE;

    /**
     * The maximum symbol size (number of bits) for Huffman codes in bitstream
     * version 3.
     */
    public static final int MAX_SYMBOL_SIZE_V3 = 14;

    /**
     * The maximum symbol size (number of bits) for Huffman codes in bitstream
     * version 4.
     */
    public static final int MAX_SYMBOL_SIZE_V4 = 12;

    /**
     * The size of the internal buffer used for sorting symbols.
     */
    private static final int BUFFER_SIZE = (MAX_SYMBOL_SIZE_V3 << 8) + 256;

    /**
     * Generates canonical Huffman codes based on the provided symbol sizes. Symbols
     * are sorted first by increasing size, then by increasing value.
     *
     * @param sizes
     *            An array where `sizes[symbol]` stores the bit length of the
     *            Huffman code for that symbol.
     * @param codes
     *            An array where the generated canonical code for each symbol will
     *            be stored.
     * @param symbols
     *            An array containing the symbols to be processed. This array will
     *            be sorted in place.
     * @param count
     *            The number of symbols to process.
     * @param maxSymbolSize
     *            The maximum allowed bit length for any symbol's Huffman code.
     * @return The number of codes generated (which should be equal to `count`), or
     *         -1 if an error occurs (e.g., invalid symbol or code size).
     */
    public static int generateCanonicalCodes(short[] sizes, int[] codes, int[] symbols, int count,
            final int maxSymbolSize) {
        // Sort symbols by increasing size (first key) and increasing value (second key)
        if (count > 1) {
            byte[] buf = new byte[BUFFER_SIZE];

            for (int i = 0; i < count; i++) {
                final int s = symbols[i];

                if (((s & 0xFF) != s) || (sizes[s] > maxSymbolSize))
                    return -1;

                buf[((sizes[s] - 1) << 8) | s] = 1;
            }

            int n = 0;

            for (int i = 0; i < BUFFER_SIZE; i++) {
                if (buf[i] == 0)
                    continue;

                symbols[n++] = i & 0xFF;

                if (n == count)
                    break;
            }
        }

        int code = 0;
        int curLen = sizes[symbols[0]];

        for (int i = 0; i < count; i++) {
            final int s = symbols[i];
            code <<= (sizes[s] - curLen);
            curLen = sizes[s];
            codes[s] = code;
            code++;
        }

        return count;
    }
}
