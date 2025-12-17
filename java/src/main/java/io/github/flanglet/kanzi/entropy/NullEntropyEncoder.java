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

import io.github.flanglet.kanzi.EntropyEncoder;
import io.github.flanglet.kanzi.OutputBitStream;

/**
 * <p>
 * Null entropy encoder. This encoder does not perform any actual compression;
 * it simply writes the data directly to the provided {@link OutputBitStream}.
 * </p>
 *
 * <p>
 * It acts as a pass-through mechanism, useful when no entropy coding is applied
 * to the data, or when the data is already in its final form.
 * </p>
 */
public final class NullEntropyEncoder implements EntropyEncoder {
    private final OutputBitStream bitstream;

    /**
     * Creates a new {@code NullEntropyEncoder}.
     * 
     * @param bitstream
     *            The {@link OutputBitStream} to write data to.
     * @throws NullPointerException
     *             if {@code bitstream} is {@code null}.
     */
    public NullEntropyEncoder(OutputBitStream bitstream) {
        if (bitstream == null)
            throw new NullPointerException("Invalid null bitstream parameter");

        this.bitstream = bitstream;
    }

    /**
     * Encodes a block of data by writing it directly to the bitstream.
     * <p>
     * This method writes {@code count} bytes from the provided {@code block} array
     * to the bitstream.
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
        if ((block == null) || (blkptr + count > block.length) || (blkptr < 0) || (count < 0))
            return -1;

        int res = 0;

        while (count > 0) {
            final int ckSize = (count < 1 << 23) ? count : 1 << 23;
            res += (this.bitstream.writeBits(block, blkptr, 8 * ckSize) >> 3);
            blkptr += ckSize;
            count -= ckSize;
        }

        return res;
    }

    /**
     * Encodes a single byte by writing it directly to the bitstream.
     * 
     * @param val
     *            The byte to encode.
     */
    public void encodeByte(byte val) {
        this.bitstream.writeBits(val, 8);
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
     * Disposes of any resources used by the encoder. This method currently does
     * nothing as there are no specific resources to release.
     */
    @Override
    public void dispose() {
    }
}
