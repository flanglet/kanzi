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
 * This interface defines methods for decoding data from a bitstream.
 */
public interface EntropyDecoder {

    /**
     * Decodes the next chunk of data from the bitstream and returns it in the
     * provided buffer.
     *
     * @param buffer
     *            the buffer to store the decoded data
     * @param blkptr
     *            the starting index in the buffer
     * @param len
     *            the length of data to decode
     * @return the number of bytes decoded
     */
    public int decode(byte[] buffer, int blkptr, int len);

    /**
     * Releases any resources associated with this entropy decoder. This method
     * should be called before disposing of the entropy decoder.
     */
    public void dispose();

    /**
     * Returns the underlying bitstream associated with this entropy decoder.
     *
     * @return the underlying {@code InputBitStream}
     */
    public InputBitStream getBitStream();
}
