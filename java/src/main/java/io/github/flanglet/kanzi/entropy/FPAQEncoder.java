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

import java.util.Arrays;
import io.github.flanglet.kanzi.EntropyEncoder;
import io.github.flanglet.kanzi.Memory;
import io.github.flanglet.kanzi.OutputBitStream;
import io.github.flanglet.kanzi.SliceByteArray;

/**
 * <p>
 * Implementation of an FPAQ encoder. This class is derived from fpaq0r by Matt
 * Mahoney and Alexander Ratushnyak, and is a simple (and fast) adaptive entropy
 * bit coder.
 * </p>
 *
 * <p>
 * It uses a range coding approach where the current range is updated based on
 * the predicted probability of the next bit. The prediction is based on a
 * context formed by previous bits.
 * </p>
 *
 * <p>
 * The encoding process involves updating the range and normalizing it by
 * writing bits to an {@link OutputBitStream} when the range becomes too small.
 * </p>
 *
 * @see <a href="http://mattmahoney.net/dc/#fpaq0">fpaq0 by Matt Mahoney</a>
 */
public class FPAQEncoder implements EntropyEncoder {
    /**
     * The top value for the range, used in range coding. This value defines the
     * maximum possible range.
     */
    private static final long TOP = 0x00FFFFFFFFFFFFFFL;
    /**
     * A mask used to check if the most significant bits of the low and (low +
     * range) values are the same, indicating that bits can be shifted out.
     */
    private static final long MASK_24_56 = 0x00FFFFFFFF000000L;
    /**
     * A mask used to keep the lower 24 bits of a long.
     */
    private static final long MASK_0_24 = 0x0000000000FFFFFFL;
    /**
     * A mask used to keep the lower 32 bits of a long.
     */
    private static final long MASK_0_32 = 0x00000000FFFFFFFFL;
    /**
     * The default chunk size for processing data.
     */
    private static final int DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024;
    /**
     * The maximum allowed block size.
     */
    private static final int MAX_BLOCK_SIZE = 1 << 30;
    /**
     * The scaling factor for probabilities.
     */
    private static final int PSCALE = 65536;

    /**
     * The lower bound of the current range.
     */
    private long low;
    /**
     * The upper bound of the current range.
     */
    private long high;
    /**
     * The output bitstream to which compressed data is written.
     */
    private final OutputBitStream bitstream;
    private boolean disposed;
    private SliceByteArray sba;
    private final int[][] probs; // probability of bit=1
    private int[] p; // pointer to current prob

    /**
     * Creates a new {@code FPAQEncoder}.
     *
     * @param bitstream
     *            The {@link OutputBitStream} to write compressed data to.
     * @throws NullPointerException
     *             if {@code bitstream} is {@code null}.
     */
    public FPAQEncoder(OutputBitStream bitstream) {
        if (bitstream == null)
            throw new NullPointerException("FPAQ codec: Invalid null bitstream parameter");

        this.low = 0L;
        this.high = TOP;
        this.bitstream = bitstream;
        this.sba = new SliceByteArray(new byte[0], 0);
        this.probs = new int[4][256];
        this.p = this.probs[0];

        for (int i = 0; i < 4; i++)
            Arrays.fill(this.probs[i], PSCALE >> 1);
    }

    /**
     * Encodes a block of data.
     * <p>
     * This method reads data from the provided byte array, encodes it using the
     * FPAQ model, and writes the compressed data to the internal bitstream.
     * </p>
     *
     * @param block
     *            The byte array containing the data to encode.
     * @param blkptr
     *            The starting position in the block.
     * @param count
     *            The number of bytes to encode.
     * @return The number of bytes encoded, or -1 if an error occurs (e.g., invalid
     *         parameters).
     */
    @Override
    public int encode(byte[] block, int blkptr, int count) {
        if ((block == null) || (blkptr + count > block.length) || (blkptr < 0) || (count < 0)
                || (count > MAX_BLOCK_SIZE))
            return -1;

        if (count == 0)
            return 0;

        int startChunk = blkptr;
        final int end = blkptr + count;

        // Split block into chunks, encode chunk and write bit array to bitstream
        while (startChunk < end) {
            final int chunkSize = Math.min(DEFAULT_CHUNK_SIZE, end - startChunk);

            if (this.sba.array.length < (chunkSize + (chunkSize >> 3)))
                this.sba.array = new byte[chunkSize + (chunkSize >> 3)];

            this.sba.index = 0;
            final int endChunk = startChunk + chunkSize;
            this.p = this.probs[0];

            for (int i = startChunk; i < endChunk; i++) {
                final byte val = block[i];
                final int bits = (val & 0xFF) + 256;
                this.encodeBit(val & 0x80, 1);
                this.encodeBit(val & 0x40, bits >> 7);
                this.encodeBit(val & 0x20, bits >> 6);
                this.encodeBit(val & 0x10, bits >> 5);
                this.encodeBit(val & 0x08, bits >> 4);
                this.encodeBit(val & 0x04, bits >> 3);
                this.encodeBit(val & 0x02, bits >> 2);
                this.encodeBit(val & 0x01, bits >> 1);
                this.p = this.probs[(val & 0xFF) >>> 6];
            }

            EntropyUtils.writeVarInt(this.bitstream, this.sba.index);
            this.bitstream.writeBits(this.sba.array, 0, 8 * this.sba.index);
            startChunk += chunkSize;

            if (startChunk < end)
                this.bitstream.writeBits(this.low | MASK_0_24, 56);
        }

        return count;
    }

    /**
     * Encodes a single bit based on a given prediction.
     * <p>
     * The range is split according to the prediction, and the bit is encoded by
     * updating the range. The probability model for the current context is then
     * updated based on the encoded bit.
     * </p>
     */
    private void encodeBit(int bit, int pIdx) {
        // Calculate interval split
        // Written in a way to maximize accuracy of multiplication/division
        final long split = (((this.high - this.low) >>> 8) * this.p[pIdx]) >>> 8;

        // Update probabilities
        if (bit == 0) {
            this.low += (split + 1);
            this.p[pIdx] -= (this.p[pIdx] >> 6);
        } else {
            this.high = this.low + split;
            this.p[pIdx] -= ((this.p[pIdx] - PSCALE + 64) >> 6);
        }

        // Write unchanged first 32 bits to bitstream
        while (((this.low ^ this.high) & MASK_24_56) == 0)
            this.flush();
    }

    /**
     * Flushes the current range to the bitstream.
     * <p>
     * This method is called when the range becomes too small and needs to be
     * normalized. It writes the most significant bits of the range to the
     * bitstream.
     * </p>
     */
    private void flush() {
        Memory.BigEndian.writeInt32(this.sba.array, this.sba.index, (int) (this.high >>> 24));
        this.sba.index += 4;
        this.low <<= 32;
        this.high = (this.high << 32) | MASK_0_32;
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
     * <p>
     * This method flushes any remaining bits in the range to the bitstream.
     * </p>
     */
    @Override
    public void dispose() {
        if (this.disposed == true)
            return;

        this.disposed = true;
        this.bitstream.writeBits(this.low | MASK_0_24, 56);
    }
}
