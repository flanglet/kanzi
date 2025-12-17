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

import io.github.flanglet.kanzi.BitStreamException;
import io.github.flanglet.kanzi.InputBitStream;
import io.github.flanglet.kanzi.OutputBitStream;

/**
 * Utility class for entropy coding operations, including alphabet
 * encoding/decoding,
 * frequency normalization, and variable-length integer encoding/decoding.
 */
public class EntropyUtils {
   public static final int INCOMPRESSIBLE_THRESHOLD = 973; // 0.95*1024
   private static final int FULL_ALPHABET = 0;
   private static final int PARTIAL_ALPHABET = 1;
   private static final int ALPHABET_256 = 0;
   private static final int ALPHABET_0 = 1;

   // alphabet must be sorted in increasing order
   // alphabet length must be a power of 2 up to 256
   public static int encodeAlphabet(OutputBitStream obs, int[] alphabet, int count) {
      // Alphabet length must be a power of 2
      if ((alphabet.length & (alphabet.length - 1)) != 0)
         return -1;

      if ((alphabet.length > 256) || (count > alphabet.length))
         return -1;

      switch (count) {
         case 0:
            obs.writeBit(FULL_ALPHABET);
            obs.writeBit(ALPHABET_0);
            break;

         case 256:
            obs.writeBit(FULL_ALPHABET);
            obs.writeBit(ALPHABET_256);
            break;

         default:
            // Partial alphabet
            obs.writeBit(PARTIAL_ALPHABET);
            byte[] masks = new byte[32];

            for (int i = 0; i < count; i++)
               masks[alphabet[i] >> 3] |= (1 << (alphabet[i] & 7));

            final int lastMask = alphabet[count - 1] >> 3;
            obs.writeBits(lastMask, 5);

            for (int i = 0; i <= lastMask; i++)
               obs.writeBits(masks[i], 8);

            break;
      }

      return count;
   }

   /**
    * Decodes an alphabet from the input bitstream.
    * The alphabet can be full (256 symbols), empty (0 symbols), or partial.
    * For partial alphabets, a bitmask is read to reconstruct the symbols.
    *
    * @param ibs      The input bitstream to read the encoded alphabet from.
    * @param alphabet An array to store the decoded symbols.
    * @return The number of symbols decoded.
    * @throws BitStreamException If the bitstream is invalid (e.g., incorrect
    *                            alphabet size).
    */
   public static int decodeAlphabet(InputBitStream ibs, int[] alphabet) throws BitStreamException {
      // Read encoding mode from bitstream
      final int alphabetType = ibs.readBit();

      if (alphabetType == FULL_ALPHABET) {
         if (ibs.readBit() == ALPHABET_0)
            return 0;

         final int alphabetSize = 256;

         if (alphabetSize > alphabet.length)
            throw new BitStreamException("Invalid bitstream: incorrect alphabet size: " + alphabetSize,
                  BitStreamException.INVALID_STREAM);

         // Full alphabet
         for (int i = 0; i < alphabetSize; i++)
            alphabet[i] = i;

         return alphabetSize;
      }

      // Partial alphabet
      final int lastMask = (int) ibs.readBits(5);
      int count = 0;

      // Decode presence flags
      for (int i = 0; i <= lastMask; i++) {
         final int mask = (int) ibs.readBits(8);

         for (int j = 0; j < 8; j++) {
            if ((mask & (1 << j)) != 0)
               alphabet[count++] = (i << 3) + j;
         }
      }

      return count;
   }

   /**
    * Normalizes frequencies of symbols in an alphabet to a given scale.
    * This method adjusts the frequencies so that their sum equals the specified
    * scale,
    * while preserving the relative proportions as much as possible.
    * It also populates the alphabet array with the present symbols.
    * <p>
    * This method is not thread safe.
    * </p>
    *
    * @param freqs     An array containing the frequencies of symbols. This array
    *                  will be updated with normalized frequencies.
    * @param alphabet  An array to store the symbols present in the alphabet. This
    *                  array will be updated.
    * @param totalFreq The sum of the original frequencies.
    * @param scale     The target sum for the normalized frequencies.
    * @return The size of the alphabet (number of unique symbols with non-zero
    *         frequency).
    * @throws IllegalArgumentException If the alphabet size or scale parameter is
    *                                  invalid.
    */
   public static int normalizeFrequencies(int[] freqs, int[] alphabet, int totalFreq, int scale) {
      if (alphabet.length > 256)
         throw new IllegalArgumentException("Invalid alphabet size parameter: " + alphabet.length +
               " (must be less than or equal to 256)");

      if ((scale < 1 << 8) || (scale > 1 << 16))
         throw new IllegalArgumentException("Invalid scale parameter: " + scale +
               " (must be in [256..65536])");

      if ((alphabet.length == 0) || (totalFreq == 0))
         return 0;

      int alphabetSize = 0;

      // shortcut
      if (totalFreq == scale) {
         for (int i = 0; i < 256; i++) {
            if (freqs[i] != 0)
               alphabet[alphabetSize++] = i;
         }

         return alphabetSize;
      }

      int sumScaledFreq = 0;
      int idxMax = 0;

      // Scale frequencies by suqeezing/stretching distribution over complete range
      for (int i = 0; i < alphabet.length; i++) {
         alphabet[i] = 0;
         final int f = freqs[i];

         if (f == 0)
            continue;

         long sf = (long) freqs[i] * scale;
         int scaledFreq;

         if (sf <= totalFreq) {
            // Quantum of frequency
            scaledFreq = 1;
         } else {
            // Find best frequency rounding value
            scaledFreq = (int) (sf / totalFreq);
            long errCeiling = ((scaledFreq + 1) * (long) totalFreq) - sf;
            long errFloor = sf - (scaledFreq * (long) totalFreq);

            if (errCeiling < errFloor)
               scaledFreq++;
         }

         alphabet[alphabetSize++] = i;
         sumScaledFreq += scaledFreq;
         freqs[i] = scaledFreq;

         if (scaledFreq > freqs[idxMax])
            idxMax = i;
      }

      if (alphabetSize == 0)
         return 0;

      if (alphabetSize == 1) {
         freqs[alphabet[0]] = scale;
         return 1;
      }

      if (sumScaledFreq == scale)
         return alphabetSize;

      int delta = sumScaledFreq - scale;
      final int errThr = freqs[idxMax] >> 4;

      if (Math.abs(delta) <= errThr) {
         // Fast path (small error): just adjust the max frequency
         freqs[idxMax] -= delta;
         return alphabetSize;
      }

      if (delta < 0) {
         delta += errThr;
         freqs[idxMax] += errThr;
      } else {
         delta -= errThr;
         freqs[idxMax] -= errThr;
      }

      // Slow path: spread error across frequencies
      final int inc = (delta > 0) ? -1 : 1;
      delta = Math.abs(delta);
      int round = 0;

      while ((++round < 6) && (delta > 0)) {
         int adjustments = 0;

         for (int i = 0; i < alphabetSize; i++) {
            final int idx = alphabet[i];

            // Skip small frequencies to avoid big distortion
            // Do not zero out frequencies
            if (freqs[idx] <= 2)
               continue;

            // Adjust frequency
            freqs[idx] += inc;
            adjustments++;
            delta--;

            if (delta == 0)
               break;
         }

         if (adjustments == 0)
            break;
      }

      freqs[idxMax] = Math.max(freqs[idxMax] - delta, 1);
      return alphabetSize;
   }

   /**
    * Writes an integer value to the output bitstream using a variable-length
    * encoding.
    *
    * @param bs    The output bitstream to write to.
    * @param value The integer value to encode.
    * @return The number of bytes written to the bitstream.
    */
   public static int writeVarInt(OutputBitStream bs, int value) {
      int res = 0;

      if ((value >= 128) || (value < 0)) {
         bs.writeBits(0x80 | (value & 0x7F), 8);
         value >>>= 7;
         res++;

         while (value >= 128) {
            bs.writeBits(0x80 | (value & 0x7F), 8);
            value >>>= 7;
            res++;
         }
      }

      bs.writeBits(value, 8);
      return res;
   }

   /**
    * Reads a variable-length encoded integer from the input bitstream.
    *
    * @param bs The input bitstream to read from.
    * @return The decoded integer value.
    */
   public static int readVarInt(InputBitStream bs) {
      int value = (int) bs.readBits(8);
      int res = value & 0x7F;
      int shift = 7;

      while (value >= 128) {
         value = (int) bs.readBits(8);
         res |= ((value & 0x7F) << shift);

         if (shift == 28)
            break;

         shift += 7;
      }

      return res;
   }

}
