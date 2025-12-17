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
 * This interface defines methods for transforming integer arrays in forward and
 * inverse directions, and for obtaining the maximum encoded length.
 */
public interface IntTransform {

    /**
     * Processes the source array and writes the transformed data to the destination
     * array in the forward direction.
     *
     * @param src
     *            the source {@code SliceIntArray} containing the data to be
     *            processed
     * @param dst
     *            the destination {@code SliceIntArray} where the processed data
     *            will be written
     * @return {@code true} if the transformation was successful, {@code false}
     *         otherwise
     */
    public boolean forward(SliceIntArray src, SliceIntArray dst);

    /**
     * Processes the source array and writes the transformed data to the destination
     * array in the inverse direction.
     *
     * @param src
     *            the source {@code SliceIntArray} containing the data to be
     *            processed
     * @param dst
     *            the destination {@code SliceIntArray} where the processed data
     *            will be written
     * @return {@code true} if the transformation was successful, {@code false}
     *         otherwise
     */
    public boolean inverse(SliceIntArray src, SliceIntArray dst);

    /**
     * Returns the maximum size required for the output buffer given the length of
     * the source data.
     *
     * @param srcLength
     *            the length of the source data
     * @return the maximum size required for the output buffer
     */
    public int getMaxEncodedLength(int srcLength);
}
