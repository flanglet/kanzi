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
import io.github.flanglet.kanzi.EntropyDecoder;

/**
 * <p>
 * Implementation of a range decoder based on the algorithm described by G.N.N.
 * Martin in his seminal article in 1979, and optimized for speed. This
 * implementation is derived from Dmitry Subbotin's Order 0 range coder.
 * </p>
 *
 * <p>
 * A range decoder reads a compressed stream of data and reconstructs the
 * original symbols based on their probabilities. It maintains a current range
 * and a code value, narrowing the range with each decoded symbol and
 * normalizing it by reading more bits from the bitstream when necessary.
 * </p>
 *
 * <p>
 * This class is not thread-safe.
 * </p>
 *
 * @see RangeEncoder
 * @see EntropyDecoder
 */
public final class RangeDecoder implements EntropyDecoder {
    /**
     * The top value for the range, used in range coding. This value defines the
     * maximum possible range.
     */
    private static final long TOP_RANGE = 0x0FFFFFFFFFFFFFFFL;
    /**
     * The bottom value for the range, used in range coding. When the range falls
     * below this value, normalization occurs.
     */
    private static final long BOTTOM_RANGE = 0x000000000000FFFFL;
    /**
     * A mask used to check if the most significant bits of the low and (low +
     * range) values are the same, indicating that bits can be shifted out.
     */
    private static final long RANGE_MASK = 0x0FFFFFFF00000000L;
    /**
     * The default chunk size for processing data. This value determines how many
     * bytes are processed before frequency statistics are reset.
     */
    private static final int DEFAULT_CHUNK_SIZE = 1 << 15; // 32 KB by default
    /**
     * The maximum allowed chunk size.
     */
    private static final int MAX_CHUNK_SIZE = 1 << 30;
    /**
     * The current code value read from the bitstream, representing a point within
     * the range.
     */
    private long code;
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
     * The cumulative frequencies of symbols, used to determine the sub-range for
     * each symbol.
     */
    private final long[] cumFreqs;
    /**
     * A mapping from frequency count to symbol, used for efficient decoding.
     */
    private short[] f2s; // mapping frequency -> symbol
    /**
     * The input bitstream from which compressed data is read.
     */
    private final InputBitStream bitstream;
    /**
     * The chunk size for processing data.
     */
    private final int chunkSize;
    /**
     * The current shift value, derived from the log range, used in range
     * calculations.
     */
    private int shift;

    /**
     * Creates a new {@code RangeDecoder} with the specified input bitstream and a
     * default chunk size.
     *
     * @param bitstream
     *            The {@link InputBitStream} to read compressed data from.
     * @throws NullPointerException
     *             if {@code bitstream} is {@code null}.
     */
    public RangeDecoder(InputBitStream bitstream) {
        this(bitstream, DEFAULT_CHUNK_SIZE);
    }

    /**
     * Creates a new {@code RangeDecoder} with the specified input bitstream and
     * chunk size. The chunk size indicates how many bytes are decoded (per block)
     * before resetting the frequency statistics.
     *
     * @param bitstream
     *            The {@link InputBitStream} to read compressed data from.
     * @param chunkSize
     *            The size of data chunks to process. Must be at least 1024 and at
     *            most {@link #MAX_CHUNK_SIZE}.
     * @throws NullPointerException
     *             if {@code bitstream} is {@code null}.
     * @throws IllegalArgumentException
     *             if {@code chunkSize} is out of range.
     */
    public RangeDecoder(InputBitStream bitstream, int chunkSize) {
        if (bitstream == null)
            throw new NullPointerException("Range codec: Invalid null bitstream parameter");

        if (chunkSize < 1024)
            throw new IllegalArgumentException("Range codec: The chunk size must be at least 1024");

        if (chunkSize > MAX_CHUNK_SIZE)
            throw new IllegalArgumentException("Range codec: The chunk size must be at most " + MAX_CHUNK_SIZE);

        this.range = TOP_RANGE;
        this.bitstream = bitstream;
        this.chunkSize = chunkSize;
        this.cumFreqs = new long[257];
        this.freqs = new int[256];
        this.alphabet = new int[256];
        this.f2s = new short[0];
    }

    /**
     * Decodes the header of a data chunk, which includes the alphabet, frequencies,
     * and log range. This information is used to set up the decoding tables for the
     * current chunk.
     *
     * @param frequencies
     *            An array to store the decoded frequencies of symbols.
     * @return The size of the alphabet (number of unique symbols) in the chunk.
     * @throws BitStreamException
     *             if the bitstream contains invalid data, such as incorrect Huffman
     *             symbol, frequency size, or frequency value, or if the bitstream
     *             is corrupted.
     */
    protected int decodeHeader(int[] frequencies) {
        int alphabetSize = EntropyUtils.decodeAlphabet(this.bitstream, this.alphabet);

        if (alphabetSize == 0)
            return 0;

        if (alphabetSize != 256) {
            for (int i = 0; i < 256; i++)
                frequencies[i] = 0;
        }

        final int logRange = (int) (8 + this.bitstream.readBits(3));
        final int scale = 1 << logRange;
        this.shift = logRange;
        int sum = 0;
        final int chkSize = (alphabetSize >= 64) ? 8 : 6;
        int llr = 3;

        while (1 << llr <= logRange)
            llr++;

        // Decode all frequencies (but the first one) by chunks of size 'inc'
        for (int i = 1; i < alphabetSize; i += chkSize) {
            final int logMax = (int) this.bitstream.readBits(llr);

            if (1 << logMax > scale) {
                throw new BitStreamException(
                        "Invalid bitstream: incorrect frequency size " + logMax + " in range decoder",
                        BitStreamException.INVALID_STREAM);
            }

            final int endj = (i + chkSize < alphabetSize) ? i + chkSize : alphabetSize;

            // Read frequencies
            for (int j = i; j < endj; j++) {
                int freq = (logMax == 0) ? 1 : (int) (1 + this.bitstream.readBits(logMax));

                if ((freq <= 0) || (freq >= scale)) {
                    throw new BitStreamException("Invalid bitstream: incorrect frequency " + freq + " for symbol '"
                            + this.alphabet[j] + "' in range decoder", BitStreamException.INVALID_STREAM);
                }

                frequencies[this.alphabet[j]] = freq;
                sum += freq;
            }
        }

        // Infer first frequency
        if (scale <= sum) {
            throw new BitStreamException("Invalid bitstream: incorrect frequency " + frequencies[this.alphabet[0]]
                    + " for symbol '" + this.alphabet[0] + "' in range decoder", BitStreamException.INVALID_STREAM);
        }

        frequencies[this.alphabet[0]] = scale - sum;
        this.cumFreqs[0] = 0;

        if (this.f2s.length < scale)
            this.f2s = new short[scale];

        // Create histogram of frequencies scaled to 'range' and reverse mapping
        for (int i = 0; i < 256; i++) {
            this.cumFreqs[i + 1] = this.cumFreqs[i] + frequencies[i];
            final int base = (int) this.cumFreqs[i];

            for (int j = frequencies[i] - 1; j >= 0; j--)
                this.f2s[base + j] = (short) i;
        }

        return alphabetSize;
    }

    /**
     * Decodes a block of data.
     * <p>
     * This method reads compressed data from the internal bitstream and decodes it
     * into the provided byte array. It processes data in chunks, resetting
     * frequency statistics for each chunk.
     * </p>
     *
     * @param block
     *            The byte array to decode into.
     * @param blkptr
     *            The starting position in the block.
     * @param count
     *            The number of bytes to decode.
     * @return The number of bytes decoded, or -1 if an error occurs (e.g., invalid
     *         parameters).
     */
    @Override
    public int decode(byte[] block, int blkptr, int count) {
        if ((block == null) || (blkptr + count > block.length) || (blkptr < 0) || (count < 0))
            return -1;

        if (count == 0)
            return 0;

        final int end = blkptr + count;
        final int sz = this.chunkSize;
        int startChunk = blkptr;

        while (startChunk < end) {
            final int endChunk = (startChunk + sz < end) ? startChunk + sz : end;
            final int alphabetSize = this.decodeHeader(this.freqs);

            if (alphabetSize == 0)
                return startChunk - blkptr;

            if (alphabetSize == 1) {
                // Shortcut for chunks with only one symbol
                for (int i = startChunk; i < endChunk; i++)
                    block[i] = (byte) this.alphabet[0];

                startChunk = endChunk;
                continue;
            }

            this.range = TOP_RANGE;
            this.low = 0;
            this.code = this.bitstream.readBits(60);

            for (int i = startChunk; i < endChunk; i++)
                block[i] = this.decodeByte();

            startChunk = endChunk;
        }

        return count;
    }

    /**
     * Decodes a single byte from the bitstream. This method uses the current range,
     * code, and frequency tables to determine the next symbol.
     *
     * @return The decoded byte.
     */
    protected byte decodeByte() {
        // Compute next low and range
        this.range >>>= this.shift;
        final int count = (int) ((this.code - this.low) / this.range);
        final int symbol = this.f2s[count];
        final long cumFreq = this.cumFreqs[symbol];
        final long freq = this.cumFreqs[symbol + 1] - cumFreq;
        this.low += (cumFreq * this.range);
        this.range *= freq;

        // If the left-most digits are the same throughout the range, read bits from
        // bitstream
        while (true) {
            if (((this.low ^ (this.low + this.range)) & RANGE_MASK) != 0) {
                if (this.range > BOTTOM_RANGE)
                    break;

                // Normalize
                this.range = -this.low & BOTTOM_RANGE;
            }

            this.code = (this.code << 28) | this.bitstream.readBits(28);
            this.range <<= 28;
            this.low <<= 28;
        }

        return (byte) symbol;
    }

    /**
     * Returns the {@link InputBitStream} used by this decoder.
     *
     * @return The {@link InputBitStream}.
     */
    @Override
    public InputBitStream getBitStream() {
        return this.bitstream;
    }

    /**
     * Disposes of any resources used by the decoder. This method currently does
     * nothing as there are no specific resources to release.
     */
    @Override
    public void dispose() {
    }
}
