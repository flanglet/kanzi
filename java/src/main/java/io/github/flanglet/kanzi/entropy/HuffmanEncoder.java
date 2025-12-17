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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import io.github.flanglet.kanzi.OutputBitStream;
import io.github.flanglet.kanzi.BitStreamException;
import io.github.flanglet.kanzi.EntropyEncoder;
import io.github.flanglet.kanzi.Error;
import io.github.flanglet.kanzi.Global;
import io.github.flanglet.kanzi.Memory.BigEndian;

/**
 * <p>
 * Implementation of a static Huffman encoder. This class encodes symbols into a
 * bitstream using Huffman codes, which are variable-length codes optimized for
 * symbol frequencies.
 * </p>
 *
 * <p>
 * The encoder uses an in-place generation of canonical codes rather than a
 * traditional Huffman tree, which can be more memory-efficient. It supports
 * chunk-based encoding, where frequency statistics are recomputed for each
 * chunk of data.
 * </p>
 */
public class HuffmanEncoder implements EntropyEncoder {
    private final OutputBitStream bitstream;
    private final int[] alphabet;
    private final int[] codes;
    private final int chunkSize;
    private byte[] buffer;

    /**
     * Creates a new {@code HuffmanEncoder} with the specified output bitstream and
     * a default chunk size.
     *
     * @param bitstream
     *            The {@link OutputBitStream} to write compressed data to.
     * @throws BitStreamException
     *             if an error occurs during bitstream operations.
     */
    public HuffmanEncoder(OutputBitStream bitstream) throws BitStreamException {
        this(bitstream, HuffmanCommon.MAX_CHUNK_SIZE);
    }

    // The chunk size indicates how many bytes are encoded (per block) before
    // resetting the frequency stats.
    /**
     * Creates a new {@code HuffmanEncoder} with the specified output bitstream and
     * chunk size.
     *
     * @param bitstream
     *            The {@link OutputBitStream} to write compressed data to.
     * @param chunkSize
     *            The size of data chunks to process.
     * @throws BitStreamException
     *             if an error occurs during bitstream operations.
     * @throws IllegalArgumentException
     *             if {@code chunkSize} is out of range.
     */
    public HuffmanEncoder(OutputBitStream bitstream, int chunkSize) throws BitStreamException {
        if (bitstream == null)
            throw new NullPointerException("Huffman codec: Invalid null bitstream parameter");

        if (chunkSize < HuffmanCommon.MIN_CHUNK_SIZE)
            throw new IllegalArgumentException(
                    "Huffman codec: The chunk size must be at least " + HuffmanCommon.MIN_CHUNK_SIZE);

        if (chunkSize > HuffmanCommon.MAX_CHUNK_SIZE)
            throw new IllegalArgumentException(
                    "Huffman codec: The chunk size must be at most " + HuffmanCommon.MAX_CHUNK_SIZE);

        this.bitstream = bitstream;
        this.buffer = new byte[0];
        this.codes = new int[256];
        this.alphabet = new int[256];
        this.chunkSize = chunkSize;

        // Default frequencies, sizes and codes
        for (int i = 0; i < 256; i++)
            this.codes[i] = i;
    }

    /**
     * Updates the frequencies of symbols and rebuilds the Huffman codes based on
     * the new frequencies.
     *
     * @param freqs
     *            An array containing the frequencies of symbols.
     * @return The number of symbols in the alphabet.
     * @throws BitStreamException
     *             if an error occurs during bitstream operations or if code
     *             generation fails.
     */
    private int updateFrequencies(int[] freqs) throws BitStreamException {
        if ((freqs == null) || (freqs.length != 256))
            return -1;

        int count = 0;
        short[] sizes = new short[256];

        for (int i = 0; i < 256; i++) {
            this.codes[i] = 0;

            if (freqs[i] > 0)
                this.alphabet[count++] = i;
        }

        EntropyUtils.encodeAlphabet(this.bitstream, this.alphabet, count);

        if (count == 0)
            return 0;

        if (count == 1) {
            this.codes[this.alphabet[0]] = 1 << 24;
            sizes[this.alphabet[0]] = 1;
        } else {
            int[] ranks = new int[256];

            for (int i = 0; i < count; i++)
                ranks[i] = (freqs[this.alphabet[i]] << 8) | this.alphabet[i];

            int maxCodeLen = this.computeCodeLengths(sizes, ranks, count);

            if (maxCodeLen == 0)
                throw new BitStreamException("Could not generate Huffman codes: invalid code length 0",
                        Error.ERR_PROCESS_BLOCK);

            if (maxCodeLen > HuffmanCommon.MAX_SYMBOL_SIZE_V4) {
                // Attempt to limit codes max width
                maxCodeLen = this.limitCodeLengths(alphabet, freqs, sizes, ranks, count);

                if (maxCodeLen == 0)
                    throw new BitStreamException("Could not generate Huffman codes: invalid code length 0",
                            Error.ERR_PROCESS_BLOCK);
            }

            if (maxCodeLen > HuffmanCommon.MAX_SYMBOL_SIZE_V4) {
                // Unlikely branch when no codes could be found that fit within
                // MAX_SYMBOL_SIZE_V4 width
                int n = 0;

                for (int i = 0; i < count; i++) {
                    this.codes[alphabet[i]] = n;
                    sizes[alphabet[i]] = 8;
                    n++;
                }
            } else {
                HuffmanCommon.generateCanonicalCodes(sizes, this.codes, ranks, count, HuffmanCommon.MAX_SYMBOL_SIZE_V4);
            }
        }

        // Transmit code lengths only, frequencies and codes do not matter
        ExpGolombEncoder egenc = new ExpGolombEncoder(this.bitstream, true);
        short prevSize = 2;

        // Pack size and code (size <= MAX_SYMBOL_SIZE bits)
        // Unary encode the length differences
        for (int i = 0; i < count; i++) {
            final int s = this.alphabet[i];
            final short currSize = sizes[s];
            this.codes[s] |= (currSize << 24);
            egenc.encodeByte((byte) (currSize - prevSize));
            prevSize = currSize;
        }

        egenc.dispose();
        return count;
    }

    /**
     * Limits the code lengths to {@code HuffmanCommon.MAX_SYMBOL_SIZE_V4} by
     * adjusting the code lengths and repaying any bit debt.
     *
     * @param alphabet
     *            The array of symbols in the alphabet.
     * @param freqs
     *            The frequencies of the symbols.
     * @param sizes
     *            The array to store the adjusted code lengths.
     * @param ranks
     *            The array of symbols sorted by frequency.
     * @param count
     *            The number of symbols in the alphabet.
     * @return The maximum code length after adjustment, or 0 if an error occurs.
     */
    private int limitCodeLengths(int[] alphabet, int[] freqs, short[] sizes, int[] ranks, int count) {
        int n = 0;
        int debt = 0;

        // Fold over-the-limit sizes, skip at-the-limit sizes => incur bit debt
        while (sizes[ranks[n]] >= HuffmanCommon.MAX_SYMBOL_SIZE_V4) {
            debt += (sizes[ranks[n]] - HuffmanCommon.MAX_SYMBOL_SIZE_V4);
            sizes[ranks[n]] = HuffmanCommon.MAX_SYMBOL_SIZE_V4;
            n++;
        }

        // Check (up to) 6 levels; one list per size delta
        List<LinkedList<Integer>> ll = new ArrayList<>(10);

        for (int i = 0; i < 6; i++)
            ll.add(new LinkedList<>());

        while (n < count) {
            final int idx = HuffmanCommon.MAX_SYMBOL_SIZE_V4 - 1 - sizes[ranks[n]];

            if ((idx >= ll.size()) || (debt < (1 << idx)))
                break;

            ll.get(idx).add(ranks[n]);
            n++;
        }

        int idx = ll.size() - 1;

        // Repay bit debt in a "semi optimized" way
        while ((debt > 0) && (idx >= 0)) {
            LinkedList<Integer> l = ll.get(idx);

            if ((l.isEmpty() == true) || (debt < (1 << idx))) {
                idx--;
                continue;
            }

            final int r = l.removeFirst();
            sizes[r]++;
            debt -= (1 << idx);
        }

        idx = 0;

        // Adjust if necessary
        while ((debt > 0) && (idx < ll.size())) {
            LinkedList<Integer> l = ll.get(idx);

            if (l.isEmpty() == true) {
                idx++;
                continue;
            }

            final int r = l.removeFirst();
            sizes[r]++;
            debt -= (1 << idx);
        }

        if (debt > 0) {
            // Fallback to slow (more accurate) path if fast path failed to repay the debt
            int[] f = new int[count];
            int[] symbols = new int[count];
            int totalFreq = 0;

            for (int i = 0; i < count; i++) {
                f[i] = freqs[this.alphabet[i]];
                totalFreq += f[i];
            }

            // Renormalize to a smaller scale
            EntropyUtils.normalizeFrequencies(f, symbols, totalFreq, HuffmanCommon.MAX_CHUNK_SIZE >> 3);

            for (int i = 0; i < count; i++) {
                freqs[alphabet[i]] = f[i];
                ranks[i] = (f[i] << 8) | alphabet[i];
            }

            return this.computeCodeLengths(sizes, ranks, count);
        }

        return HuffmanCommon.MAX_SYMBOL_SIZE_V4;
    }

    /**
     * Computes the code lengths for the Huffman codes based on symbol frequencies.
     *
     * @param sizes
     *            The array to store the computed code lengths.
     * @param ranks
     *            The array of symbols sorted by frequency (will be modified).
     * @param count
     *            The number of symbols in the alphabet.
     * @return The maximum code length, or 0 if an error occurs (e.g., zero
     *         frequency).
     * @see #computeInPlaceSizesPhase1(int[], int)
     * @see #computeInPlaceSizesPhase2(int[], int)
     */
    private int computeCodeLengths(short[] sizes, int[] ranks, int count) {
        // Sort ranks by increasing frequencies (first key) and increasing value (second
        // key)
        Arrays.sort(ranks, 0, count);
        int[] freqs = new int[256];

        for (int i = 0; i < count; i++) {
            freqs[i] = ranks[i] >>> 8;
            ranks[i] &= 0xFF;

            if (freqs[i] == 0)
                return 0;
        }

        // See [In-Place Calculation of Minimum-Redundancy Codes]
        // by Alistair Moffat & Jyrki Katajainen
        computeInPlaceSizesPhase1(freqs, count);
        final int maxCodeLen = computeInPlaceSizesPhase2(freqs, count);

        for (int i = 0; i < count; i++)
            sizes[ranks[i]] = (short) freqs[i];

        return maxCodeLen;
    }

    /**
     * First phase of the in-place calculation of minimum-redundancy codes. This
     * method computes the sums of frequencies for internal nodes.
     *
     * @param data
     *            An array containing frequencies (will be modified).
     * @param n
     *            The number of symbols.
     */
    static void computeInPlaceSizesPhase1(int[] data, int n) {
        for (int s = 0, r = 0, t = 0; t < n - 1; t++) {
            int sum = 0;

            for (int i = 0; i < 2; i++) {
                if ((s >= n) || ((r < t) && (data[r] < data[s]))) {
                    sum += data[r];
                    data[r] = t;
                    r++;
                    continue;
                }

                sum += data[s];

                if (s > t)
                    data[s] = 0;

                s++;
            }

            data[t] = sum;
        }
    }

    /**
     * Second phase of the in-place calculation of minimum-redundancy codes. This
     * method assigns code lengths to symbols based on the results of phase 1.
     *
     * @param data
     *            An array containing intermediate results from phase 1 (will be
     *            modified).
     * @param n
     *            The number of symbols (must be at least 2).
     * @return The maximum code length generated.
     */
    static int computeInPlaceSizesPhase2(int[] data, int n) {
        if (n < 2)
            return 0;

        int levelTop = n - 2; // root
        int depth = 1;
        int i = n;
        int totalNodesAtLevel = 2;

        while (i > 0) {
            int k = levelTop;

            while ((k > 0) && (data[k - 1] >= levelTop))
                k--;

            final int internalNodesAtLevel = levelTop - k;
            final int leavesAtLevel = totalNodesAtLevel - internalNodesAtLevel;

            for (int j = 0; j < leavesAtLevel; j++)
                data[--i] = depth;

            totalNodesAtLevel = internalNodesAtLevel << 1;
            levelTop = k;
            depth++;
        }

        return depth - 1;
    }

    // Dynamically compute the frequencies for every chunk of data in the block
    @Override
    public int encode(byte[] block, int blkptr, int count) {
        if ((block == null) || (blkptr + count > block.length) || (blkptr < 0) || (count < 0))
            return -1;

        if (count == 0)
            return 0;

        final int end = blkptr + count;
        int startChunk = blkptr;
        int minLenBuf = Math.max(Math.min(this.chunkSize + (this.chunkSize >> 3), 2 * count), 65536);

        if (this.buffer.length < minLenBuf)
            this.buffer = new byte[minLenBuf];

        int[] freqs = new int[256];

        while (startChunk < end) {
            // Update frequencies and rebuild Huffman codes
            final int sizeChunk = Math.min(this.chunkSize, end - startChunk);

            if (sizeChunk < 32) {
                // Special case for small chunks
                this.bitstream.writeBits(block, startChunk, 8 * sizeChunk);
            } else {
                final int endChunk = startChunk + sizeChunk;
                Global.computeHistogramOrder0(block, startChunk, endChunk, freqs, false);

                // Skip chunk if only one symbol
                if (updateFrequencies(freqs) > 1)
                    encodeChunk(block, startChunk, sizeChunk);
            }

            startChunk += sizeChunk;
        }

        return count;
    }

    // count is at least 32
    private void encodeChunk(byte[] block, int blkptr, int count) {
        int[] nbBits = new int[]{0, 0, 0, 0};
        final int szFrag = count / 4;
        final int szFrag4 = szFrag & ~3;
        final int szBuf = this.buffer.length / 4;

        // Encode chunk
        for (int j = 0; j < 4; j++) {
            final int[] c = this.codes;
            int idx = j * szBuf;
            int bits = 0; // accumulated bits
            long state = 0;
            final int start = blkptr + j * szFrag;
            final int end4 = start + szFrag4;

            // Encode fragments sequentially
            for (int i = start; i < end4; i += 4) {
                int code;
                code = c[block[i] & 0xFF];
                final int codeLen0 = code >>> 24;
                state = (state << codeLen0) | (code & 0xFFFFFF);
                code = c[block[i + 1] & 0xFF];
                final int codeLen1 = code >>> 24;
                state = (state << codeLen1) | (code & 0xFFFFFF);
                code = c[block[i + 2] & 0xFF];
                final int codeLen2 = code >>> 24;
                state = (state << codeLen2) | (code & 0xFFFFFF);
                code = c[block[i + 3] & 0xFF];
                final int codeLen3 = code >>> 24;
                state = (state << codeLen3) | (code & 0xFFFFFF);
                bits += (codeLen0 + codeLen1 + codeLen2 + codeLen3);
                BigEndian.writeLong64(this.buffer, idx, state << (64 - bits)); // bits cannot be 0
                idx += (bits >> 3);
                bits &= 7;
            }

            final int end = start + szFrag;

            // Fragment last bytes
            for (int i = end4; i < end; i++) {
                final int code = c[block[i] & 0xFF];
                final int codeLen = code >>> 24;
                state = (state << codeLen) | (code & 0xFFFFFF);
                bits += codeLen;
            }

            nbBits[j] = ((idx - j * szBuf) * 8) + bits;

            while (bits >= 8) {
                bits -= 8;
                this.buffer[idx++] = (byte) (state >> bits);
            }

            if (bits > 0)
                this.buffer[idx++] = (byte) (state << (8 - bits));
        }

        // Write chunk size in bits
        EntropyUtils.writeVarInt(this.bitstream, nbBits[0]);
        EntropyUtils.writeVarInt(this.bitstream, nbBits[1]);
        EntropyUtils.writeVarInt(this.bitstream, nbBits[2]);
        EntropyUtils.writeVarInt(this.bitstream, nbBits[3]);

        // Write compressed data to bitstream
        this.bitstream.writeBits(this.buffer, 0 * szBuf, nbBits[0]);
        this.bitstream.writeBits(this.buffer, 1 * szBuf, nbBits[1]);
        this.bitstream.writeBits(this.buffer, 2 * szBuf, nbBits[2]);
        this.bitstream.writeBits(this.buffer, 3 * szBuf, nbBits[3]);

        // Chunk last bytes
        final int count4 = 4 * szFrag;

        for (int i = count4; i < count; i++)
            this.bitstream.writeBits(block[blkptr + i], 8);
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
