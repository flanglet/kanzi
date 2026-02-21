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
package io.github.flanglet.kanzi;

/**
 * This interface defines methods for encoding data into a bitstream.
 */
public interface EntropyEncoder {

  /**
   * Encodes the provided array into the bitstream and returns the number of bytes written to the
   * bitstream.
   *
   * @param array the array containing the data to be encoded
   * @param blkptr the starting index in the array
   * @param len the length of data to encode
   * @return the number of bytes written to the bitstream
   */
  public int encode(byte[] array, int blkptr, int len);

  /**
   * Returns the underlying bitstream associated with this entropy encoder.
   *
   * @return the underlying {@code OutputBitStream}
   */
  public OutputBitStream getBitStream();

  /**
   * Releases any resources associated with this entropy encoder. This method should be called
   * before disposing of the entropy encoder. Trying to encode after a call to dispose gives
   * undefined behavior.
   */
  public void dispose();
}
