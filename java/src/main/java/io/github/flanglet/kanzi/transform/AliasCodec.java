/*
Copyright 2011-2024 Frederic Langlet
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
you may obtain a copy of the License at

                http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package io.github.flanglet.kanzi.transform;

import java.util.Map;
import java.util.TreeSet;
import io.github.flanglet.kanzi.ByteTransform;
import io.github.flanglet.kanzi.Global;
import io.github.flanglet.kanzi.Memory;
import io.github.flanglet.kanzi.SliceByteArray;

/**
 * The AliasCodec class implements a byte transform that aims to compress data
 * by substituting frequently occurring patterns with shorter representations.
 * It supports both forward and inverse transformations.
 * <p>
 * It can be configured to operate in a mode optimized for DNA sequences.
 */
public class AliasCodec implements ByteTransform
{
    private static final int MIN_BLOCK_SIZE = 1024;
    private final Map<String, Object> ctx;
    private final boolean onlyDNA;

    /**
     * Default constructor.
     * Creates an AliasCodec instance with no specific context and not restricted to DNA data.
     */
    public AliasCodec()
    {
        this.ctx = null;
        this.onlyDNA = false;
    }

    /**
     * Constructor with DNA restriction flag.
     * Creates an AliasCodec instance with no specific context and optionally restricted to DNA data.
     *
     * @param onlyDNA if true, restricts the transform to operate on DNA sequences only
     */
    public AliasCodec(boolean onlyDNA)
    {
        this.ctx = null;
        this.onlyDNA = onlyDNA;
    }

    /**
     * Constructor with context.
     * Creates an AliasCodec instance with a specific context.
     *
     * @param ctx the context containing configuration parameters
     */
    public AliasCodec(Map<String, Object> ctx)
    {
        this.ctx = ctx;
        this.onlyDNA = (Boolean) this.ctx.getOrDefault("packOnlyDNA", false);
    }

    /**
     * Performs the forward transformation on the input data.
     *
     * @param input the input data to be transformed
     * @param output the buffer to store the transformed data
     * @return true if the transformation was successful, false otherwise
     */
    @Override
    public boolean forward(SliceByteArray input, SliceByteArray output)
    {
        if (input.length == 0)
            return true;

        if (input.length < MIN_BLOCK_SIZE)
            return false;

        if (input.array == output.array)
            return false;

        final int count = input.length;

        if (output.length - output.index < getMaxEncodedLength(count))
            return false;

        Global.DataType dt = Global.DataType.UNDEFINED;

        if (this.ctx != null)
        {
            dt = (Global.DataType) this.ctx.getOrDefault("dataType", Global.DataType.UNDEFINED);

            if ((dt == Global.DataType.MULTIMEDIA) || (dt == Global.DataType.UTF8))
                return false;

            if ((dt == Global.DataType.EXE) || (dt == Global.DataType.BIN))
                return false;

            if ((this.onlyDNA == true) && (dt != Global.DataType.UNDEFINED) && (dt != Global.DataType.DNA))
                return false;
        }

        int srcIdx = input.index;
        int dstIdx = output.index;
        final byte[] src = input.array;
        final byte[] dst = output.array;

        // Find missing 1-byte symbols
        int[] freqs0 = new int[256];
        Global.computeHistogramOrder0(src, srcIdx, count, freqs0, false);
        int n0 = 0;
        int[] absent = new int[256];

        for (int i = 0; i < 256; i++)
        {
            if (freqs0[i] == 0)
                absent[n0++] = i;
        }

        if (n0 < 16)
            return false;

        if (dt == Global.DataType.UNDEFINED)
        {
            dt = Global.detectSimpleType(count, freqs0);

            if ((this.ctx != null) && (dt != Global.DataType.UNDEFINED))
                this.ctx.put("dataType", dt);

            if ((dt != Global.DataType.DNA) && (this.onlyDNA == true))
                return false;
        }

        if (n0 >= 240)
        {
            // Small alphabet => pack bits
            dst[dstIdx++] = (byte) n0;

            if (n0 == 255)
            {
                // One symbol
                dst[dstIdx++] = src[srcIdx];
                Memory.LittleEndian.writeInt32(dst, dstIdx, count);
                dstIdx += 4;
                srcIdx += count;
            }
            else
            {
                int[] map8 = new int[256];

                for (int i = 0, j = 0; i < 256; i++)
                {
                    if (freqs0[i] != 0)
                    {
                        dst[dstIdx++] = (byte) i;
                        map8[i] = j;
                        j++;
                    }
                }

                if (n0 >= 252)
                {
                    // 4 symbols or less
                    dst[dstIdx++] = (byte) (count & 3);

                    if ((count & 3) > 2)
                        dst[dstIdx++] = src[srcIdx++];

                    if ((count & 3) > 1)
                        dst[dstIdx++] = src[srcIdx++];

                    if ((count & 3) > 0)
                        dst[dstIdx++] = src[srcIdx++];

                    while (srcIdx < count)
                    {
                        dst[dstIdx++] = (byte) ((map8[src[srcIdx + 0] & 0xFF] << 6) | (map8[src[srcIdx + 1] & 0xFF] << 4) |
                                                (map8[src[srcIdx + 2] & 0xFF] << 2) |  map8[src[srcIdx + 3] & 0xFF]);
                        srcIdx += 4;
                    }
                }
                else
                {
                    // 16 symbols or less
                    dst[dstIdx++] = (byte) (count & 1);

                    if ((count & 1) != 0)
                        dst[dstIdx++] = src[srcIdx++];

                    while (srcIdx < count)
                    {
                        dst[dstIdx++] = (byte) ((map8[src[srcIdx] & 0xFF] << 4) | map8[src[srcIdx + 1] & 0xFF]);
                        srcIdx += 2;
                    }
                }
            }
        }
        else
        {
            // Digram encoding
            TreeSet<Alias> t = new TreeSet<>();

            {
                // Find missing 2-byte symbols
                int[][] freqs1 = new int[256][256];
                Global.computeHistogramOrder1(src, srcIdx, count, freqs1, false);
                int n1 = 0;

                for (int i = 0; i < 65536; i++)
                {
                    if (freqs1[i>>8][i&0xFF] == 0)
                        continue;

                    t.add(new Alias(i, freqs1[i>>8][i&0xFF]));
                    n1++;
                }

                if (n1 < n0)
                {
                    // Fewer distinct 2-byte symbols than 1-byte symbols
                    n0 = n1;

                    if (n0 < 16)
                        return false;
                }
            }

            int[] map16 = new int[65536];

            // Build map symbol -> alias
            for (int i = 0; i < 65536; i++)
                map16[i] = (i>>8) | 0x100;

            int savings = 0;
            dst[output.index] = (byte) n0;
            dst[output.index+1] = (byte) 0;
            dstIdx += 2;

            // Header: emit map data
            for (int i = 0; i < n0; i++)
            {
                Alias sd = t.pollFirst();
                savings += sd.freq; // ignore factor 2
                final int idx = sd.val;
                map16[idx] = absent[i] | 0x200;
                dst[dstIdx] = (byte) (idx >> 8);
                dst[dstIdx + 1] = (byte) idx;
                dst[dstIdx + 2] = (byte) absent[i];
                dstIdx += 3;
            }

            // Worth it?
            if (savings * 20 < count)
                return false;

            final int srcEnd = input.index + count - 1;

            // Emit aliased data
            while (srcIdx < srcEnd)
            {
                final int alias = map16[((src[srcIdx] & 0xFF) << 8) | (src[srcIdx + 1] & 0xFF)];
                dst[dstIdx++] = (byte) alias;
                srcIdx += (alias >>> 8);
            }

            if (srcIdx != count)
            {
                dst[output.index+1] = (byte) 1;
                dst[dstIdx++] = src[srcIdx++];
            }
        }

        boolean res = (dstIdx - output.index) < count;
        input.index = srcIdx;
        output.index = dstIdx;
        return res;
    }

    /**
     * Performs the inverse transformation on the input data.
     *
     * @param input the transformed data to be reverted
     * @param output the buffer to store the original data
     * @return true if the transformation was successful, false otherwise
     */
    @Override
    public boolean inverse(SliceByteArray input, SliceByteArray output)
    {
        if (input.length == 0)
            return true;

        if (input.array == output.array)
            return false;

        final int count = input.length;
        int srcIdx = input.index;
        int dstIdx = output.index;
        final byte[] src = input.array;
        final byte[] dst = output.array;
        int n = src[srcIdx++] & 0xFF;

        if (n < 16)
            return false;

        if (n >= 240)
        {
            n = 256 - n;

            if (n == 1)
            {
                // One symbol
                byte val = src[srcIdx++];
                final int oSize = Memory.LittleEndian.readInt32(src, srcIdx);

                if (dstIdx + oSize > output.length)
                    return false;

                for (int i = 0; i < oSize; i++)
                    dst[i] = val;

                srcIdx += count;
                dstIdx += oSize;
            }
            else
            {
                byte[] idx2symb = new byte[16];

                // Rebuild map alias -> symbol
                for (int i = 0; i < n; i++)
                    idx2symb[i] = src[srcIdx++];

                final int adjust = src[srcIdx++] & 0xFF;

                if (adjust >= 4)
                    return false;

                if (n <= 4)
                {
                    // 4 symbols or less
                    int[] decodeMap = new int[256];

                    for (int i = 0; i < 256; i++)
                    {
                        int val;
                        val  = (idx2symb[(i >> 0) & 0x03] & 0xFF);
                        val <<= 8;
                        val |= (idx2symb[(i >> 2) & 0x03] & 0xFF);
                        val <<= 8;
                        val |= (idx2symb[(i >> 4) & 0x03] & 0xFF);
                        val <<= 8;
                        val |= (idx2symb[(i >> 6) & 0x03] & 0xFF);
                        decodeMap[i] = val;
                    }

                    if (adjust > 0)
                        dst[dstIdx++] = src[srcIdx++];

                    if (adjust > 1)
                        dst[dstIdx++] = src[srcIdx++];

                    if (adjust > 2)
                        dst[dstIdx++] = src[srcIdx++];

                    while (srcIdx < count)
                    {
                        Memory.LittleEndian.writeInt32(dst, dstIdx, decodeMap[src[srcIdx++] & 0xFF]);
                        dstIdx += 4;
                    }
                }
                else
                {
                    // 16 symbols or less
                    int[] decodeMap = new int[256];

                    for (int i = 0; i < 256; i++)
                    {
                        int val = (idx2symb[i & 0x0F] & 0xFF);
                        val <<= 8;
                        val |= (idx2symb[i >> 4] & 0xFF);
                        decodeMap[i] = val;
                    }

                    if (adjust != 0)
                        dst[dstIdx++] = src[srcIdx++];

                    while (srcIdx < count)
                    {
                        final int val = decodeMap[src[srcIdx++] & 0xFF];
                        Memory.LittleEndian.writeInt16(dst, dstIdx, val);
                        dstIdx += 2;
                    }
                }
            }
        }
        else
        {
            // Rebuild map alias -> symbol
            final int adjust = src[srcIdx++] & 0xFF;
            final int srcEnd = input.index + count - adjust;
            int[] map16 = new int[256];

            for (int i = 0; i < 256; i++)
                map16[i] = 0x10000 | i;

            for (int i = 0; i < n; i++)
            {
                map16[src[srcIdx + 2] & 0xFF] = 0x20000 | (src[srcIdx] & 0xFF) | ((src[srcIdx + 1] & 0xFF) << 8);
                srcIdx += 3;
            }

            while (srcIdx < srcEnd)
            {
                final int val = map16[src[srcIdx++] & 0xFF];
                dst[dstIdx] = (byte) val;
                dst[dstIdx + 1] = (byte) (val >>> 8);
                dstIdx += (val >>> 16);
            }

            if (adjust != 0)
                dst[dstIdx++] = src[srcIdx++];
        }

        output.index = dstIdx;
        input.index = srcIdx;
        return true;
    }

    /**
     * Returns the maximum length of the encoded data.
     *
     * @param srcLen the length of the source data
     * @return the maximum length of the encoded data
     */
    @Override
    public int getMaxEncodedLength(int srcLen)
    {
        return srcLen + 1024;
    }

    /**
     * The Alias class represents a mapping between a symbol and its frequency.
     * It is used to facilitate the aliasing process in the transformation.
     */
    static class Alias implements Comparable<Alias>
    {
        public final int val;
        public final int freq;

        /**
         * Constructs an Alias instance with the specified value and frequency.
         *
         * @param val the value of the symbol
         * @param freq the frequency of the symbol
         */
        public Alias(int val, int freq)
        {
            this.val = val;
            this.freq = freq;
        }

        /**
         * Compares this Alias object with another for order.
         *
         * @param other the other Alias object to be compared
         * @return a negative integer, zero, or a positive integer as this object is
         * less than, equal to, or greater than the specified object
         */
        @Override
        public int compareTo(Alias other)
        {
            if (other == this)
                return 0;

            if (other == null)
                return 1;

            final int r = other.freq - this.freq;

            if (r != 0)
                return r;

            return other.val - this.val;
        }

        /**
         * Indicates whether some other object is "equal to" this one.
         *
         * @param other the reference object with which to compare
         * @return true if this object is the same as the obj argument; false otherwise
         */
        @Override
        public boolean equals(Object other)
        {
            if (other == this)
                return true;

            if (other == null)
                return false;

            if (!(other instanceof Alias))
                return false;

            Alias alias = (Alias) other;
            return (this.freq == alias.freq) && (this.val == alias.val);
        }

        /**
         * Returns a hash code value for the object.
         *
         * @return a hash code value for this object
         */
        @Override
        public int hashCode()
        {
            int hash = 7;
            hash = 19 * hash + this.val;
            hash = 19 * hash + this.freq;
            return hash;
        }
    }
}