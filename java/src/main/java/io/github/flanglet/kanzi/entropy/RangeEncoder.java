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

package io.github.flanglet.kanzi.entropy;

import io.github.flanglet.kanzi.EntropyEncoder;
import io.github.flanglet.kanzi.Global;
import io.github.flanglet.kanzi.OutputBitStream;

/**
 * <p>
 * Implementation of a range encoder based on the algorithm described by G.N.N.
 * Martin in his seminal article in 1979, and optimized for speed. This implementation
 * is derived from Dmitry Subbotin's Order 0 range coder.
 * </p>
 *
 * <p>
 * A range encoder writes a compressed stream of data by maintaining a current
 * range and a low value. For each symbol, it narrows the range based on the
 * symbol's probability and normalizes the range by writing bits to the
 * bitstream when necessary.
 * </p>
 *
 * <p>
 * This class is not thread-safe.
 * </p>
 *
 * @see RangeDecoder
 * @see EntropyEncoder
 */
public final class RangeEncoder implements EntropyEncoder {
   /**
    * The top value for the range, used in range coding.
    * This value defines the maximum possible range.
    */
   private static final long TOP_RANGE = 0x0FFFFFFFFFFFFFFFL;
   /**
    * The bottom value for the range, used in range coding.
    * When the range falls below this value, normalization occurs.
    */
   private static final long BOTTOM_RANGE = 0x000000000000FFFFL;
   /**
    * A mask used to check if the most significant bits of the low and
    * (low + range) values are the same, indicating that bits can be
    * shifted out.
    */
   private static final long RANGE_MASK = 0x0FFFFFFF00000000L;
   /**
    * The default chunk size for processing data.
    * This value determines how many bytes are processed before
    * frequency statistics are reset.
    */
   private static final int DEFAULT_CHUNK_SIZE = 1 << 15; // 32 KB by default
   /**
    * The default log range for the range encoder.
    * This value determines the precision of the frequency table.
    */
   private static final int DEFAULT_LOG_RANGE = 12;
   /**
    * The maximum allowed chunk size.
    */
   private static final int MAX_CHUNK_SIZE = 1 << 30;

   /**
    * The lower bound of the current range.
    */
   private long low;
   /**
    * The current range value.
    */
   private long range;
   /**
    * The alphabet of symbols, mapping internal indices to actual byte values.
    */
   private final int[] alphabet;
   /**
    * The frequencies of symbols in the current chunk.
    */
   private final int[] freqs;
   /**
    * The cumulative frequencies of symbols, used to determine the sub-range
    * for each symbol.
    */
   private final long[] cumFreqs;
   /**
    * The output bitstream to which compressed data is written.
    */
   private final OutputBitStream bitstream;
   private final int chunkSize;
   private final int logRange;
   private int shift;

   /**
    * Creates a new {@code RangeEncoder} with the specified output bitstream
    * and a default chunk size and log range.
    *
    * @param bitstream The {@link OutputBitStream} to write compressed data to.
    */
   public RangeEncoder(OutputBitStream bitstream) {
      this(bitstream, DEFAULT_CHUNK_SIZE, DEFAULT_LOG_RANGE);
   }

   /**
    * Creates a new {@code RangeEncoder} with the specified output bitstream,
    * chunk size, and log range. The chunk size indicates how many bytes are
    * encoded (per block) before resetting the frequency statistics.
    *
    * @param bs        The {@link OutputBitStream} to write compressed data to.
    * @param chunkSize The size of data chunks to process. Must be at least 1024
    *                  and at most {@link #MAX_CHUNK_SIZE}.
    * @param logRange  The log range for the range encoder. This value determines
    *                  the precision of the frequency table. Must be in the range
    *                  [8, 16].
    * @throws NullPointerException     if {@code bs} is {@code null}.
    * @throws IllegalArgumentException if {@code chunkSize} or {@code logRange}
    *                                  is out of range.
    */
   public RangeEncoder(OutputBitStream bs, int chunkSize, int logRange) {
      if (bs == null)
         throw new NullPointerException("Range codec: Invalid null bitstream parameter");

      if (chunkSize < 1024)
         throw new IllegalArgumentException("Range codec: The chunk size must be at least 1024");

      if (chunkSize > MAX_CHUNK_SIZE)
         throw new IllegalArgumentException("Range codec: The chunk size must be at most " + MAX_CHUNK_SIZE);

      if ((logRange < 8) || (logRange > 16))
         throw new IllegalArgumentException("Range codec: Invalid range parameter: " +
               logRange + " (must be in [8..16])");

      this.bitstream = bs;
      this.alphabet = new int[256];
      this.freqs = new int[256];
      this.cumFreqs = new long[257];
      this.logRange = logRange;
      this.chunkSize = chunkSize;
   }

   /**
    * Updates the frequencies and cumulative frequencies for the current chunk,
    * and encodes the header into the bitstream.
    *
    * @param frequencies An array containing the frequencies of symbols.
    * @param size        The total number of symbols in the chunk.
    * @param lr          The log range for the current chunk.
    * @return The size of the alphabet (number of unique symbols) in the chunk.
    */
   private int updateFrequencies(int[] frequencies, int size, int lr) {
      if ((frequencies == null) || (frequencies.length != 256))
         return -1;

      int alphabetSize = EntropyUtils.normalizeFrequencies(frequencies, this.alphabet, size, 1 << lr);

      if (alphabetSize > 0) {
         this.cumFreqs[0] = 0;

         // Create histogram of frequencies scaled to 'range'
         for (int i = 0; i < 256; i++)
            this.cumFreqs[i + 1] = this.cumFreqs[i] + frequencies[i];
      }

      this.encodeHeader(alphabetSize, this.alphabet, frequencies, lr);
      return alphabetSize;
   }

   /**
    * Encodes the header of a data chunk, which includes the alphabet,
    * frequencies, and log range.
    *
    * @param alphabetSize The size of the alphabet (number of unique symbols).
    * @param alphabet     An array containing the symbols in the alphabet.
    * @param frequencies  An array containing the frequencies of symbols.
    * @param lr           The log range used for the current chunk.
    * @return {@code true} if the header was encoded successfully, {@code false}
    *         otherwise.
    */
   protected boolean encodeHeader(int alphabetSize, int[] alphabet, int[] frequencies, int lr) {
      final int encoded = EntropyUtils.encodeAlphabet(this.bitstream, alphabet, alphabetSize);

      if (encoded < 0)
         return false;

      if (encoded == 0)
         return true;

      this.bitstream.writeBits(lr - 8, 3); // logRange
      final int chkSize = (alphabetSize >= 64) ? 8 : 6;
      int llr = 3;

      while (1 << llr <= lr)
         llr++;

      // Encode all frequencies (but the first one) by chunks of size 'inc'
      for (int i = 1; i < alphabetSize; i += chkSize) {
         int max = frequencies[alphabet[i]] - 1;
         int logMax = 0;
         final int endj = (i + chkSize < alphabetSize) ? i + chkSize : alphabetSize;

         // Search for max frequency log size in next chunk
         for (int j = i + 1; j < endj; j++) {
            if (frequencies[alphabet[j]] - 1 > max)
               max = frequencies[alphabet[j]] - 1;
         }

         while (1 << logMax <= max)
            logMax++;

         this.bitstream.writeBits(logMax, llr);

         if (logMax == 0) // all frequencies equal one in this chunk
            continue;

         // Write frequencies
         for (int j = i; j < endj; j++)
            this.bitstream.writeBits(frequencies[alphabet[j]] - 1, logMax);
      }

      return true;
   }

   /**
    * Encodes a block of data.
    * <p>
    * This method reads data from the provided byte array, encodes it using the
    * range coding algorithm, and writes the compressed data to the internal
    * bitstream. It processes data in chunks, resetting frequency statistics for
    * each chunk.
    * </p>
    *
    * @param block  The byte array containing the data to encode.
    * @param blkptr The starting position in the block.
    * @param count  The number of bytes to encode.
    * @return The number of bytes encoded, or -1 if an error occurs (e.g., invalid
    *         parameters).
    */
   @Override
   public int encode(byte[] block, int blkptr, int count) {
      if ((block == null) || (blkptr + count > block.length) || (blkptr < 0) || (count < 0))
         return -1;

      if (count == 0)
         return 0;

      final int end = blkptr + count;
      final int sz = this.chunkSize;
      int startChunk = blkptr;

      while (startChunk < end) {
         final int endChunk = (startChunk + sz < end) ? startChunk + sz : end;
         this.range = TOP_RANGE;
         this.low = 0;
         int lr = this.logRange;

         // Lower log range if the size of the data chunk is small
         while ((lr > 8) && (1 << lr > endChunk - startChunk))
            lr--;

         if (this.rebuildStatistics(block, startChunk, endChunk, lr) <= 1) {
            // Skip chunk if only one symbol
            startChunk = endChunk;
            continue;
         }

         this.shift = lr;

         for (int i = startChunk; i < endChunk; i++)
            this.encodeByte(block[i]);

         // Flush 'low'
         this.bitstream.writeBits(this.low, 60);
         startChunk = endChunk;
      }

      return count;
   }

   /**
    * Encodes a single byte into the bitstream.
    * <p>
    * This method updates the range and low value based on the symbol's frequency.
    * </p>
    *
    * @param b The byte to encode.
    */
   protected void encodeByte(byte b) {
      // Compute next low and range
      final int symbol = b & 0xFF;
      final long cumFreq = this.cumFreqs[symbol];
      final long freq = this.cumFreqs[symbol + 1] - cumFreq;
      this.range >>>= this.shift;
      this.low += (cumFreq * this.range);
      this.range *= freq;

      // If the left-most digits are the same throughout the range, write bits to
      // bitstream
      while (true) {
         if (((this.low ^ (this.low + this.range)) & RANGE_MASK) != 0) {
            if (this.range > BOTTOM_RANGE)
               break;

            // Normalize
            this.range = -this.low & BOTTOM_RANGE;
         }

         this.bitstream.writeBits(this.low >>> 32, 28);
         this.range <<= 28;
         this.low <<= 28;
      }
   }

   /**
    * Computes the frequencies for a chunk of data, updates the cumulative
    * frequencies, and encodes the chunk header.
    *
    * @param block The byte array containing the data.
    * @param start The starting position in the block.
    * @param end   The ending position in the block.
    * @param lr    The log range for the current chunk.
    * @return The size of the alphabet (number of unique symbols) in the chunk.
    */
   private int rebuildStatistics(byte[] block, int start, int end, int lr) {
      Global.computeHistogramOrder0(block, start, end, this.freqs, false);
      return this.updateFrequencies(this.freqs, end - start, lr);
   }

   /**
    * Returns the {@link OutputBitStream} used by this encoder.
    *
    * @return The {@link OutputBitStream}.
    */
   @Override
   public OutputBitStream getBitStream() {
      return this.bitstream;
   }

   /**
    * Disposes of any resources used by the encoder.
    * This method currently does nothing as there are no specific resources to
    * release.
    */
   @Override
   public void dispose() {
   }
}
