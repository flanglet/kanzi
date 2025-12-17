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

import io.github.flanglet.kanzi.Predictor;
import io.github.flanglet.kanzi.EntropyDecoder;
import io.github.flanglet.kanzi.InputBitStream;
import io.github.flanglet.kanzi.Memory;
import io.github.flanglet.kanzi.SliceByteArray;

/**
 * This class is a generic implementation of a boolean entropy decoder.
 * <p>
 * It uses a range coding approach where the current range is updated based on
 * the predicted probability of the next bit. The predictor provides the
 * probability for each bit, and the decoder uses this to narrow down the range.
 * </p>
 * <p>
 * The decoding process involves reading bits from an {@link InputBitStream},
 * updating the range, and normalizing the range by reading more bits when the
 * range becomes too small.
 * </p>
 * <p>
 * This decoder is designed to work with a {@link Predictor} to adaptively
 * decode binary data.
 * </p>
 */
public class BinaryEntropyDecoder implements EntropyDecoder {
    /**
     * The top value for the range, used in range coding. This value defines the
     * maximum possible range.
     */
    private static final long TOP = 0x00FFFFFFFFFFFFFFL;
    private static final long MASK_24_56 = 0x00FFFFFFFF000000L;
    private static final long MASK_0_56 = 0x00FFFFFFFFFFFFFFL;
    private static final long MASK_0_32 = 0x00000000FFFFFFFFL;
    private static final int MAX_BLOCK_SIZE = 1 << 30;
    private static final int MAX_CHUNK_SIZE = 1 << 26;

    private final Predictor predictor;
    /**
     * The lower bound of the current range.
     */
    private long low;
    /**
     * The upper bound of the current range.
     */
    private long high;
    /**
     * The current value read from the bitstream, representing a point within the
     * range.
     */
    private long current;
    /**
     * The input bitstream from which compressed data is read.
     */
    private final InputBitStream bitstream;
    /**
     * A {@link SliceByteArray} used as a buffer for reading data from the
     * bitstream.
     */
    private SliceByteArray sba;

    /**
     * Creates a new {@code BinaryEntropyDecoder}.
     * <p>
     * The decoder is initialized with an {@link InputBitStream} to read compressed
     * data and a {@link Predictor} to provide probability estimates for decoding
     * bits.
     * </p>
     *
     * @param bitstream
     *            The {@link InputBitStream} to read compressed data from.
     * @param predictor
     *            The {@link Predictor} to use for probability estimation.
     * @throws NullPointerException
     *             if {@code bitstream} or {@code predictor} is {@code null}.
     */
    public BinaryEntropyDecoder(InputBitStream bitstream, Predictor predictor) {
        if (bitstream == null)
            throw new NullPointerException("BinaryEntropy codec: Invalid null bitstream parameter");

        if (predictor == null)
            throw new NullPointerException("BinaryEntropy codec: Invalid null predictor parameter");

        // Defer stream reading. We are creating the object, we should not do any I/O
        this.low = 0L;
        this.high = TOP;
        this.bitstream = bitstream;
        this.predictor = predictor;
        this.sba = new SliceByteArray(new byte[0], 0);
    }

    /**
     * Decodes a block of data.
     * <p>
     * This method reads compressed data from the internal bitstream and decodes it
     * into the provided byte array.
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
        if ((block == null) || (blkptr + count > block.length) || (blkptr < 0) || (count < 0)
                || (count > MAX_BLOCK_SIZE))
            return -1;

        if (count == 0)
            return 0;

        int startChunk = blkptr;
        final int end = blkptr + count;
        int length = (count < 64) ? 64 : count;

        if (count >= MAX_CHUNK_SIZE) {
            // If the block is big (>=64MB), split the decoding to avoid allocating
            // too much memory.
            length = (count < 8 * MAX_CHUNK_SIZE) ? count >> 3 : count >> 4;
        }

        // Split block into chunks, read bit array from bitstream and decode chunk
        while (startChunk < end) {
            final int chunkSize = Math.min(length, end - startChunk);

            if (this.sba.array.length < (chunkSize + (chunkSize >> 3)))
                this.sba.array = new byte[chunkSize + (chunkSize >> 3)];

            final int szBytes = EntropyUtils.readVarInt(this.bitstream);
            this.current = this.bitstream.readBits(56);

            if (szBytes != 0)
                this.bitstream.readBits(this.sba.array, 0, 8 * szBytes);

            this.sba.index = 0;
            final int endChunk = startChunk + chunkSize;

            for (int i = startChunk; i < endChunk; i++)
                block[i] = this.decodeByte();

            startChunk = endChunk;
        }

        return count;
    }

    /**
     * Decodes a single byte from the bitstream.
     * <p>
     * This method decodes 8 bits sequentially, using the predictor for each bit, to
     * reconstruct a byte.
     * </p>
     *
     * @return The decoded byte.
     */
    public final byte decodeByte() {
        return (byte) ((this.decodeBit(this.predictor.get()) << 7) | (this.decodeBit(this.predictor.get()) << 6)
                | (this.decodeBit(this.predictor.get()) << 5) | (this.decodeBit(this.predictor.get()) << 4)
                | (this.decodeBit(this.predictor.get()) << 3) | (this.decodeBit(this.predictor.get()) << 2)
                | (this.decodeBit(this.predictor.get()) << 1) | this.decodeBit(this.predictor.get()));
    }

    /**
     * Decodes a single bit based on a given prediction.
     * <p>
     * The range is split according to the prediction, and the bit is determined by
     * the current value. The predictor is then updated with the decoded bit.
     * </p>
     *
     * @param pred
     *            The prediction value (probability) for the bit, typically in the
     *            range [0, 256].
     * @return The decoded bit (0 or 1).
     */
    public int decodeBit(int pred) {
        // Calculate interval split
        // Written in a way to maximize accuracy of multiplication/division
        final long split = ((((this.high - this.low) >>> 4) * pred) >>> 8) + this.low;
        int bit;

        if (split >= this.current) {
            bit = 1;
            this.high = split;
        } else {
            bit = 0;
            this.low = -~split;
        }

        // Update predictor
        this.predictor.update(bit);

        // Read 32 bits from bitstream
        while (((this.low ^ this.high) & MASK_24_56) == 0)
            this.read();

        return bit;
    }

    /**
     * Reads 32 bits from the internal buffer and updates the range.
     * <p>
     * This method is called when the range becomes too small and needs to be
     * normalized.
     * </p>
     */
    protected void read() {
        this.low = (this.low << 32) & MASK_0_56;
        this.high = ((this.high << 32) | MASK_0_32) & MASK_0_56;
        final long val = Memory.BigEndian.readInt32(this.sba.array, this.sba.index) & 0xFFFFFFFFL;
        this.current = ((this.current << 32) | val) & MASK_0_56;
        this.sba.index += 4;
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
