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
 * The {@code InputBitStream} interface defines methods for reading bits from a
 * bit stream.
 */
public interface InputBitStream {

    /**
     * Reads a single bit from the bitstream.
     *
     * @return the bit read (0 or 1)
     * @throws BitStreamException
     *             if an error occurs or the stream is closed
     */
    public int readBit() throws BitStreamException;

    /**
     * Reads a specified number of bits from the bitstream and returns them as a
     * long.
     *
     * @param length
     *            the number of bits to read (between 1 and 64)
     * @return the bits read as a long
     * @throws BitStreamException
     *             if an error occurs or the stream is closed
     */
    public long readBits(int length) throws BitStreamException;

    /**
     * Reads bits from the bitstream and stores them in the specified byte array.
     *
     * @param bits
     *            the byte array to store the read bits
     * @param start
     *            the starting index in the array
     * @param length
     *            the number of bits to read
     * @return the number of bits read
     * @throws BitStreamException
     *             if an error occurs or the stream is closed
     */
    public int readBits(byte[] bits, int start, int length) throws BitStreamException;

    /**
     * Closes the bitstream and releases any associated resources.
     *
     * @throws BitStreamException
     *             if an error occurs while closing the stream
     */
    public void close() throws BitStreamException;

    /**
     * Returns the total number of bits read from the bitstream.
     *
     * @return the total number of bits read
     */
    public long read();

    /**
     * Checks if there are more bits to read in the bitstream.
     *
     * @return {@code false} if the bitstream is closed or the end of the stream has
     *         been reached, {@code true} otherwise
     */
    public boolean hasMoreToRead();
}
