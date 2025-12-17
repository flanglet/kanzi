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

package io.github.flanglet.kanzi;

/**
 * The {@code OutputBitStream} interface defines methods for writing bits to a
 * bit stream.
 */
public interface OutputBitStream {

    /**
     * Writes the least significant bit of the input integer to the bit stream.
     *
     * @param bit
     *            the bit to write (0 or 1)
     * @throws BitStreamException
     *             if the stream is closed or if an error occurs
     */
    public void writeBit(int bit) throws BitStreamException;

    /**
     * Writes a specified number of bits from the input long value to the bit
     * stream.
     *
     * @param bits
     *            the long value containing the bits to write
     * @param length
     *            the number of bits to write (must be between 1 and 64)
     * @return the number of bits written
     * @throws BitStreamException
     *             if the stream is closed or if an error occurs
     */
    public int writeBits(long bits, int length) throws BitStreamException;

    /**
     * Writes bits from a byte array to the bit stream starting at the specified
     * index.
     *
     * @param bits
     *            the byte array containing the bits to write
     * @param start
     *            the starting index in the byte array
     * @param nbBits
     *            the number of bits to write
     * @return the number of bits written
     * @throws BitStreamException
     *             if the stream is closed or if an error occurs
     */
    public int writeBits(byte[] bits, int start, int nbBits) throws BitStreamException;

    /**
     * Closes the bit stream and releases any resources associated with it.
     *
     * @throws BitStreamException
     *             if an error occurs while closing the stream
     */
    public void close() throws BitStreamException;

    /**
     * Returns the total number of bits that have been written to the stream.
     *
     * @return the number of bits written
     */
    public long written();
}
