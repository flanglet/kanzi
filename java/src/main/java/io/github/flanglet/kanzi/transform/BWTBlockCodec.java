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

package io.github.flanglet.kanzi.transform;

import java.util.Map;
import io.github.flanglet.kanzi.ByteTransform;
import io.github.flanglet.kanzi.Global;
import io.github.flanglet.kanzi.SliceByteArray;


/**
 * Utility class to encode and decode a BWT data block and its associated primary index(es).
 * <p>
 * BWT stream format: Header (mode + primary index(es)) | Data (n bytes)
 * <ul>
 *   <li>mode (8 bits): xxxyyyzz</li>
 *   <li>xxx: ignored</li>
 *   <li>yyy: log(chunks)</li>
 *   <li>zz: primary index size - 1 (in bytes)</li>
 *   <li>primary indexes (chunks * (8|16|24|32 bits))</li>
 * </ul>
 */
public class BWTBlockCodec implements ByteTransform {
    private static final int BWT_MAX_HEADER_SIZE = 8 * 4;

    private final BWT bwt;
    private final int bsVersion;

    /**
     * Default constructor.
     */
    public BWTBlockCodec() {
        this.bwt = new BWT();
        this.bsVersion = 6;
    }

    /**
     * Constructor with a context map.
     *
     * @param ctx the context map
     */
    public BWTBlockCodec(Map<String, Object> ctx) {
        this.bwt = new BWT(ctx);
        this.bsVersion = (ctx == null) ? 6 : (int) ctx.getOrDefault("bsVersion", 6);
    }

    /**
     * Performs the forward transform, encoding the input data.
     *
     * @param input  the input byte array
     * @param output the output byte array
     * @return true if the transform was successful, false otherwise
     */
    @Override
    public boolean forward(SliceByteArray input, SliceByteArray output) {
        if (input.length == 0)
            return true;

        if (input.array == output.array)
            return false;

        final int blockSize = input.length;

        if (output.length - output.index < getMaxEncodedLength(blockSize))
            return false;

        int logBlockSize = Global.log2(blockSize);

        if ((blockSize & (blockSize - 1)) != 0)
            logBlockSize++;

        final int pIndexSize = (logBlockSize + 7) >> 3;

        if ((pIndexSize <= 0) || (pIndexSize >= 5))
            return false;

        final int chunks = BWT.getBWTChunks(blockSize);
        final int logNbChunks = Global.log2(chunks);

        if (logNbChunks > 7)
            return false;

        int idx0 = output.index;
        output.index += (1 + chunks * pIndexSize);

        // Apply forward transform
        if (!this.bwt.forward(input, output))
            return false;

        final byte mode = (byte) ((logNbChunks << 2) | (pIndexSize - 1));

        // Emit header
        for (int i = 0, idx = idx0 + 1; i < chunks; i++) {
            final int primaryIndex = this.bwt.getPrimaryIndex(i) - 1;
            int shift = (pIndexSize - 1) << 3;

            while (shift >= 0) {
                output.array[idx++] = (byte) (primaryIndex >> shift);
                shift -= 8;
            }
        }

        output.array[idx0] = mode;
        return true;
    }

    /**
     * Performs the inverse transform, decoding the input data.
     *
     * @param input  the input byte array
     * @param output the output byte array
     * @return true if the transform was successful, false otherwise
     */
    @Override
    public boolean inverse(SliceByteArray input, SliceByteArray output) {
        if (input.length == 0)
            return true;

        if (input.array == output.array)
            return false;

        final int blockSize = input.length;

        if (this.bsVersion > 5) {
            // Number of chunks and primary index size in bitstream since bsVersion 6
            byte mode = input.array[input.index++];
            final int logNbChunks = (mode >> 2) & 0x07;
            final int pIndexSize = (mode & 0x03) + 1;
            final int chunks = 1 << logNbChunks;
            final int headerSize = 1 + chunks * pIndexSize;

            if (blockSize < headerSize)
                return false;

            if (chunks != BWT.getBWTChunks(blockSize-headerSize))
                return false;

            // Read header
            for (int i = 0; i < chunks; i++) {
                int shift = (pIndexSize - 1) << 3;
                int primaryIndex = 0;

                // Extract BWT primary index
                while (shift >= 0) {
                    primaryIndex = (primaryIndex << 8) | (input.array[input.index++] & 0xFF);
                    shift -= 8;
                }

                if (!this.bwt.setPrimaryIndex(i, primaryIndex + 1))
                    return false;
            }

            input.length = blockSize - headerSize;
        } else {
            final int chunks = BWT.getBWTChunks(blockSize);

            for (int i = 0; i < chunks; i++) {
                // Read block header (mode + primary index). See top of file for format
                final int blockMode = input.array[input.index++] & 0xFF;
                final int pIndexSizeBytes = 1 + ((blockMode >>> 6) & 0x03);

                if (input.length < pIndexSizeBytes)
                    return false;

                input.length -= pIndexSizeBytes;
                int shift = (pIndexSizeBytes - 1) << 3;
                int primaryIndex = (blockMode & 0x3F) << shift;

                // Extract BWT primary index
                for (int n = 1; n < pIndexSizeBytes; n++) {
                    shift -= 8;
                    primaryIndex |= ((input.array[input.index++] & 0xFF) << shift);
                }

                if (!this.bwt.setPrimaryIndex(i, primaryIndex))
                    return false;
            }
        }

        // Apply inverse transform
        return this.bwt.inverse(input, output);
    }

    /**
     * Returns the maximum encoded length, which includes the header size.
     *
     * @param srcLen the source length
     * @return the maximum encoded length
     */
    @Override
    public int getMaxEncodedLength(int srcLen) {
        return srcLen + BWT_MAX_HEADER_SIZE;
    }
}
