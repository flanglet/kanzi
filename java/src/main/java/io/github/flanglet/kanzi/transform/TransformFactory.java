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

/**
 * Factory class to create instances of ByteTransform based on their type.
 * Supports up to 64 different transform types.
 */
public class TransformFactory {
    private static final int ONE_SHIFT = 6; // bits per transform
    private static final int MAX_SHIFT = (8 - 1) * ONE_SHIFT; // 8 transforms
    private static final int MASK = (1 << ONE_SHIFT) - 1;

    /**
     * Up to 64 transforms can be declared (6 bit index). Represents the 'none' or
     * 'copy' transform.
     */
    public static final short NONE_TYPE = 0; // copy
    /**
     * Represents the Burrows-Wheeler Transform.
     */
    public static final short BWT_TYPE = 1;
    /**
     * Represents the Burrows-Wheeler Transform (Scott variant).
     */
    public static final short BWTS_TYPE = 2;
    /**
     * Represents the Lempel-Ziv Transform.
     */
    public static final short LZ_TYPE = 3;
    /**
     * Represents the Snappy Transform (obsolete).
     */
    public static final short SNAPPY_TYPE = 4;
    /**
     * Represents the Run Length Encoding Transform.
     */
    public static final short RLT_TYPE = 5;
    /**
     * Represents the Zero Run Length Encoding Transform.
     */
    public static final short ZRLT_TYPE = 6;
    /**
     * Represents the Move To Front Transform.
     */
    public static final short MTFT_TYPE = 7;
    /**
     * Represents the Rank Transform.
     */
    public static final short RANK_TYPE = 8;
    /**
     * Represents the Executable Codec Transform.
     */
    public static final short EXE_TYPE = 9;
    /**
     * Represents the Text Codec Transform (Dictionary based).
     */
    public static final short DICT_TYPE = 10;
    /**
     * Represents the ROLZ Codec Transform.
     */
    public static final short ROLZ_TYPE = 11;
    /**
     * Represents the ROLZ Extra Codec Transform.
     */
    public static final short ROLZX_TYPE = 12;
    /**
     * Represents the Sorted Rank Transform.
     */
    public static final short SRT_TYPE = 13;
    /**
     * Represents the Lempel-Ziv Predict Transform.
     */
    public static final short LZP_TYPE = 14;
    /**
     * Represents the Multimedia (FSD) Codec Transform.
     */
    public static final short MM_TYPE = 15;
    /**
     * Represents the Lempel-Ziv Extra Transform.
     */
    public static final short LZX_TYPE = 16;
    /**
     * Represents the UTF Codec Transform.
     */
    public static final short UTF_TYPE = 17;
    /**
     * Represents the Alias Codec Transform (general purpose packing).
     */
    public static final short PACK_TYPE = 18;
    /**
     * Represents the DNA Alias Codec Transform (specialized for DNA sequences).
     */
    public static final short DNA_TYPE = 19;
    /**
     * Reserved for future use.
     */
    public static final short RESERVED3 = 20;
    /**
     * Reserved for future use.
     */
    public static final short RESERVED4 = 21;
    /**
     * Reserved for future use.
     */
    public static final short RESERVED5 = 22;

    /**
     * Get the type of a transform based on its name.
     *
     * @param name
     *            the name of the transform
     * @return the type of the transform
     */
    public long getType(String name) {
        if (name.indexOf('+') < 0)
            return this.getTypeToken(name) << MAX_SHIFT;

        String[] tokens = name.split("\\+");

        if (tokens.length == 0)
            throw new IllegalArgumentException("Unknown transform type: " + name);

        if (tokens.length > 8)
            throw new IllegalArgumentException("Only 8 transforms allowed: " + name);

        long res = 0;
        int shift = MAX_SHIFT;

        for (String token : tokens) {
            final long typeTk = this.getTypeToken(token);

            // Skip null transform
            if (typeTk != NONE_TYPE) {
                res |= (typeTk << shift);
                shift -= ONE_SHIFT;
            }
        }

        return res;
    }

    /**
     * Get the type token of a transform based on its name.
     *
     * @param name
     *            the name of the transform
     * @return the type token of the transform
     */
    private long getTypeToken(String name) {
        // Strings in switch not supported in JDK 6
        name = String.valueOf(name).toUpperCase();

        switch (name) {
            case "TEXT" :
                return DICT_TYPE;

            case "BWT" :
                return BWT_TYPE;

            case "BWTS" :
                return BWTS_TYPE;

            case "LZ" :
                return LZ_TYPE;

            case "LZX" :
                return LZX_TYPE;

            case "LZP" :
                return LZP_TYPE;

            case "ROLZ" :
                return ROLZ_TYPE;

            case "ROLZX" :
                return ROLZX_TYPE;

            case "SRT" :
                return SRT_TYPE;

            case "RANK" :
                return RANK_TYPE;

            case "MTFT" :
                return MTFT_TYPE;

            case "ZRLT" :
                return ZRLT_TYPE;

            case "UTF" :
                return UTF_TYPE;

            case "RLT" :
                return RLT_TYPE;

            case "EXE" :
                return EXE_TYPE;

            case "MM" :
                return MM_TYPE;

            case "PACK" :
                return PACK_TYPE;

            case "DNA" :
                return DNA_TYPE;

            case "NONE" :
                return NONE_TYPE;

            default :
                throw new IllegalArgumentException("Unknown transform type: '" + name + "'");
        }
    }

    /**
     * Create a sequence of ByteTransform instances based on the function type.
     *
     * @param ctx
     *            the context map
     * @param functionType
     *            the type of the function
     * @return the sequence of ByteTransform instances
     */
    public Sequence newFunction(Map<String, Object> ctx, long functionType) {
        int nbtr = 0;

        // Several transforms
        for (int i = 0; i < 8; i++) {
            if (((functionType >>> (MAX_SHIFT - ONE_SHIFT * i)) & MASK) != NONE_TYPE)
                nbtr++;
        }

        // Only null transforms? Keep first.
        if (nbtr == 0)
            nbtr = 1;

        ByteTransform[] transforms = new ByteTransform[nbtr];
        nbtr = 0;

        for (int i = 0; i < transforms.length; i++) {
            final int t = (int) ((functionType >>> (MAX_SHIFT - ONE_SHIFT * i)) & MASK);

            if ((t != NONE_TYPE) || (i == 0))
                transforms[nbtr++] = newFunctionToken(ctx, t);
        }

        return new Sequence(transforms);
    }

    /**
     * Create a ByteTransform instance based on the function type token.
     *
     * @param ctx
     *            the context map
     * @param functionType
     *            the type token of the function
     * @return the ByteTransform instance
     */
    private static ByteTransform newFunctionToken(Map<String, Object> ctx, int functionType) {
        switch (functionType) {
            case DICT_TYPE :
                String entropyType = (String) ctx.getOrDefault("entropy", "NONE");
                entropyType = entropyType.toUpperCase();
                int textCodecType = 1;

                // Select text encoding based on entropy codec.
                if (entropyType.equals("NONE") || entropyType.equals("ANS0") || entropyType.equals("HUFFMAN")
                        || entropyType.equals("RANGE"))
                    textCodecType = 2;

                ctx.put("textcodec", textCodecType);
                return new TextCodec(ctx);

            case ROLZ_TYPE :
                return new ROLZCodec(ctx);

            case ROLZX_TYPE :
                return new ROLZCodec(ctx);

            case BWT_TYPE :
                return new BWTBlockCodec(ctx);

            case BWTS_TYPE :
                return new BWTS(ctx);

            case RANK_TYPE :
                ctx.put("sbrt", SBRT.MODE_RANK);
                return new SBRT(ctx);

            case SRT_TYPE :
                return new SRT(ctx);

            case MTFT_TYPE :
                ctx.put("sbrt", SBRT.MODE_MTF);
                return new SBRT(ctx);

            case ZRLT_TYPE :
                return new ZRLT(ctx);

            case UTF_TYPE :
                return new UTFCodec(ctx);

            case RLT_TYPE :
                return new RLT(ctx);

            case LZ_TYPE :
                ctx.put("lz", LZ_TYPE);
                return new LZCodec(ctx);

            case LZX_TYPE :
                ctx.put("lz", LZX_TYPE);
                return new LZCodec(ctx);

            case LZP_TYPE :
                ctx.put("lz", LZP_TYPE);
                return new LZCodec(ctx);

            case EXE_TYPE :
                return new EXECodec(ctx);

            case MM_TYPE :
                return new FSDCodec(ctx);

            case PACK_TYPE :
                return new AliasCodec(ctx);

            case DNA_TYPE :
                ctx.put("packOnlyDNA", true);
                return new AliasCodec(ctx);

            case NONE_TYPE :
                return new NullTransform(ctx);

            default :
                throw new IllegalArgumentException("Unknown transform type: '" + functionType + "'");
        }
    }

    /**
     * Get the name of a transform based on its type.
     *
     * @param functionType
     *            the type of the transform
     * @return the name of the transform
     */
    public String getName(long functionType) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < 8; i++) {
            final int t = (int) (functionType >>> (MAX_SHIFT - ONE_SHIFT * i)) & MASK;

            if (t == NONE_TYPE)
                continue;

            String name = getNameToken(t);

            if (sb.length() != 0)
                sb.append('+');

            sb.append(name);
        }

        if (sb.length() == 0)
            sb.append(getNameToken(NONE_TYPE));

        return sb.toString();
    }

    /**
     * Get the name token of a transform based on its type.
     *
     * @param functionType
     *            the type of the transform
     * @return the name token of the transform
     */
    private static String getNameToken(int functionType) {
        switch (functionType) {
            case DICT_TYPE :
                return "TEXT";

            case ROLZ_TYPE :
                return "ROLZ";

            case ROLZX_TYPE :
                return "ROLZX";

            case BWT_TYPE :
                return "BWT";

            case BWTS_TYPE :
                return "BWTS";

            case SRT_TYPE :
                return "SRT";

            case UTF_TYPE :
                return "UTF";

            case RANK_TYPE :
                return "RANK";

            case MTFT_TYPE :
                return "MTFT";

            case ZRLT_TYPE :
                return "ZRLT";

            case RLT_TYPE :
                return "RLT";

            case EXE_TYPE :
                return "EXE";

            case LZ_TYPE :
                return "LZ";

            case LZX_TYPE :
                return "LZX";

            case LZP_TYPE :
                return "LZP";

            case MM_TYPE :
                return "MM";

            case PACK_TYPE :
                return "PACK";

            case DNA_TYPE :
                return "DNA";

            case NONE_TYPE :
                return "NONE";

            default :
                throw new IllegalArgumentException("Unknown transform type: '" + functionType + "'");
        }
    }
}