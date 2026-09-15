/*
 * Kanzi is a modern, modular, portable, and efficient lossless data compressor.
 *
 * Copyright (C) 2026 Frederic Langlet
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
   * The maximum symbol size (number of bits) for Huffman codes in bitstream version 3.
   */
  public static final int MAX_SYMBOL_SIZE_V3 = 14;

  /**
   * The maximum symbol size (number of bits) for Huffman codes in bitstream version 4.
   */
  public static final int MAX_SYMBOL_SIZE_V4 = 12;

  /**
   * Generates canonical Huffman codes based on the provided symbol sizes. Symbols are sorted first
   * by increasing size, then by increasing value.
   *
   * @param sizes An array where `sizes[symbol]` stores the bit length of the Huffman code for that
   *        symbol.
   * @param codes An array where the generated canonical code for each symbol will be stored.
   * @param symbols An array containing the symbols to be processed. This array will be sorted in
   *        place.
   * @param count The number of symbols to process.
   * @param maxSymbolSize The maximum allowed bit length for any symbol's Huffman code.
   * @return The number of codes generated (which should be equal to `count`), or -1 if an error
   *         occurs (e.g., invalid symbol or code size).
   */
  public static int generateCanonicalCodes(short[] sizes, int[] codes, int[] symbols, int count,
      final int maxSymbolSize) {
    if (count == 0)
      return 0;

    if ((count < 0) || (count > 256))
      return -1;

    // Sort symbols by increasing size (first key) and increasing value (second key)
    if (count > 1) {
      final byte[] present = new byte[256];
      final short[] offsets = new short[MAX_SYMBOL_SIZE_V4 + 1];

      for (int i = 0; i < count; i++) {
        final int s = symbols[i];

        if ((s < 0) || (s > 255))
          return -1;

        final int len = sizes[s];

        if (len <= 0)
          return -1;

        if ((len > maxSymbolSize) || (len > MAX_SYMBOL_SIZE_V4))
          return -1;

        if (present[s] != 0)
          return -1;

        present[s] = 1;
        offsets[len]++;
      }

      short offset = 0;

      for (int len = 1; len <= MAX_SYMBOL_SIZE_V4; len++) {
        final short n = offsets[len];
        offsets[len] = offset;
        offset += n;
      }

      // Scanning symbols in value order preserves the previous tie break.
      for (int s = 0; s < 256; s++) {
        if (present[s] != 0) {
          final int len = sizes[s];
          symbols[offsets[len]++] = s;
        }
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
