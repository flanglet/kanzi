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

import java.util.Map;
import io.github.flanglet.kanzi.EntropyDecoder;
import io.github.flanglet.kanzi.EntropyEncoder;
import io.github.flanglet.kanzi.InputBitStream;
import io.github.flanglet.kanzi.OutputBitStream;

/**
 * A factory for creating entropy encoders and decoders.
 * <p>
 * This class provides static methods to instantiate various entropy coding
 * algorithms based on a given type identifier or name.
 * </p>
 */
public class EntropyCodecFactory {
    /**
     * Type identifier for no compression (raw data).
     */
    public static final byte NONE_TYPE = 0; // No compression
    /**
     * Type identifier for Huffman coding.
     */
    public static final byte HUFFMAN_TYPE = 1; // Huffman
    /**
     * Type identifier for Fast PAQ (order 0).
     */
    public static final byte FPAQ_TYPE = 2; // Fast PAQ (order 0)
    /**
     * Type identifier for obsolete PAQ.
     */
    public static final byte PAQ_TYPE = 3; // Obsolete
    /**
     * Type identifier for Range coding.
     */
    public static final byte RANGE_TYPE = 4; // Range
    /**
     * Type identifier for Asymmetric Numerical System (ANS) order 0.
     */
    public static final byte ANS0_TYPE = 5; // Asymmetric Numerical System order 0
    /**
     * Type identifier for Context Model (CM) coding.
     */
    public static final byte CM_TYPE = 6; // Context Model
    /**
     * Type identifier for Tangelo PAQ.
     */
    public static final byte TPAQ_TYPE = 7; // Tangelo PAQ
    /**
     * Type identifier for Asymmetric Numerical System (ANS) order 1.
     */
    public static final byte ANS1_TYPE = 8; // Asymmetric Numerical System order 1
    /**
     * Type identifier for Tangelo PAQ Extra.
     */
    public static final byte TPAQX_TYPE = 9; // Tangelo PAQ Extra
    /**
     * Reserved type identifier.
     */
    public static final byte RESERVED1 = 10; // Reserved
    /**
     * Reserved type identifier.
     */
    public static final byte RESERVED2 = 11; // Reserved
    /**
     * Reserved type identifier.
     */
    public static final byte RESERVED3 = 12; // Reserved
    /**
     * Reserved type identifier.
     */
    public static final byte RESERVED4 = 13; // Reserved
    /**
     * Reserved type identifier.
     */
    public static final byte RESERVED5 = 14; // Reserved
    /**
     * Reserved type identifier.
     */
    public static final byte RESERVED6 = 15; // Reserved

    /**
     * Creates a new {@link EntropyDecoder} based on the provided type.
     * <p>
     * Each block is decoded separately, and the entropy decoder is rebuilt to reset
     * block statistics.
     * </p>
     *
     * @param ibs
     *            The {@link InputBitStream} to read compressed data from.
     * @param ctx
     *            A map containing context information for the decoder.
     * @param entropyType
     *            The type of entropy codec to create.
     * @return A new {@link EntropyDecoder} instance.
     * @throws NullPointerException
     *             if {@code ibs} is {@code null}.
     * @throws IllegalArgumentException
     *             if the {@code entropyType} is unsupported.
     */
    public static EntropyDecoder newDecoder(InputBitStream ibs, Map<String, Object> ctx, int entropyType) {
        if (ibs == null)
            throw new NullPointerException("Invalid null input bitstream parameter");

        switch (entropyType) {
            // Each block is decoded separately
            // Rebuild the entropy decoder to reset block statistics
            case HUFFMAN_TYPE :
                return new HuffmanDecoder(ibs, ctx);

            case ANS0_TYPE :
                return new ANSRangeDecoder(ibs, ctx, 0);

            case ANS1_TYPE :
                return new ANSRangeDecoder(ibs, ctx, 1);

            case RANGE_TYPE :
                return new RangeDecoder(ibs);

            case FPAQ_TYPE :
                return new FPAQDecoder(ibs, ctx);

            case CM_TYPE :
                return new BinaryEntropyDecoder(ibs, new CMPredictor(ctx));

            case TPAQ_TYPE :
                return new BinaryEntropyDecoder(ibs, new TPAQPredictor(ctx));

            case TPAQX_TYPE :
                return new BinaryEntropyDecoder(ibs, new TPAQPredictor(ctx));

            case NONE_TYPE :
                return new NullEntropyDecoder(ibs);

            default :
                throw new IllegalArgumentException("Unsupported entropy codec type: " + (char) entropyType);
        }
    }

    /**
     * Creates a new {@link EntropyEncoder} based on the provided type.
     * <p>
     * Each block is encoded separately, and the entropy encoder is rebuilt to reset
     * block statistics.
     * </p>
     *
     * @param obs
     *            The {@link OutputBitStream} to write compressed data to.
     * @param ctx
     *            A map containing context information for the encoder.
     * @param entropyType
     *            The type of entropy codec to create.
     * @return A new {@link EntropyEncoder} instance.
     * @throws NullPointerException
     *             if {@code obs} is {@code null}.
     * @throws IllegalArgumentException
     *             if the {@code entropyType} is unknown.
     */
    public static EntropyEncoder newEncoder(OutputBitStream obs, Map<String, Object> ctx, int entropyType) {
        if (obs == null)
            throw new NullPointerException("Invalid null output bitstream parameter");

        switch (entropyType) {
            case HUFFMAN_TYPE :
                return new HuffmanEncoder(obs);

            case ANS0_TYPE :
                return new ANSRangeEncoder(obs, ctx, 0);

            case ANS1_TYPE :
                return new ANSRangeEncoder(obs, ctx, 1);

            case RANGE_TYPE :
                return new RangeEncoder(obs);

            case FPAQ_TYPE :
                return new FPAQEncoder(obs);

            case CM_TYPE :
                return new BinaryEntropyEncoder(obs, new CMPredictor(ctx));

            case TPAQ_TYPE :
                return new BinaryEntropyEncoder(obs, new TPAQPredictor(ctx));

            case TPAQX_TYPE :
                return new BinaryEntropyEncoder(obs, new TPAQPredictor(ctx));

            case NONE_TYPE :
                return new NullEntropyEncoder(obs);

            default :
                throw new IllegalArgumentException("Unknown entropy codec type: '" + (char) entropyType + "'");
        }
    }

    /**
     * Returns the name of the entropy codec for a given type identifier.
     *
     * @param entropyType
     *            The type identifier of the entropy codec.
     * @return The name of the entropy codec.
     * @throws IllegalArgumentException
     *             if the {@code entropyType} is unknown.
     */
    public static String getName(int entropyType) {
        switch (entropyType) {
            case HUFFMAN_TYPE :
                return "HUFFMAN";

            case ANS0_TYPE :
                return "ANS0";

            case ANS1_TYPE :
                return "ANS1";

            case RANGE_TYPE :
                return "RANGE";

            case FPAQ_TYPE :
                return "FPAQ";

            case CM_TYPE :
                return "CM";

            case TPAQ_TYPE :
                return "TPAQ";

            case TPAQX_TYPE :
                return "TPAQX";

            case NONE_TYPE :
                return "NONE";

            default :
                throw new IllegalArgumentException("Unknown entropy codec type: '" + (char) entropyType + "''");
        }
    }

    /**
     * Returns the type identifier of the entropy codec for a given name.
     *
     * @param name
     *            The name of the entropy codec (case-insensitive).
     * @return The type identifier of the entropy codec.
     * @throws IllegalArgumentException
     *             if the {@code name} is unsupported.
     */
    public static int getType(String name) {
        // Strings in switch not supported in JDK 6
        name = String.valueOf(name).toUpperCase();

        switch (name) {
            case "HUFFMAN" :
                return HUFFMAN_TYPE;

            case "ANS0" :
                return ANS0_TYPE;

            case "ANS1" :
                return ANS1_TYPE;

            case "FPAQ" :
                return FPAQ_TYPE;

            case "RANGE" :
                return RANGE_TYPE;

            case "CM" :
                return CM_TYPE;

            case "NONE" :
                return NONE_TYPE;

            case "TPAQ" :
                return TPAQ_TYPE;

            case "TPAQX" :
                return TPAQX_TYPE;

            default :
                throw new IllegalArgumentException("Unsupported entropy codec type: '" + name + "'");
        }
    }

}
