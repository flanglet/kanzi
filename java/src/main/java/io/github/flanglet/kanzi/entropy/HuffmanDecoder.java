/*
 * Kanzi is a modern, modular, portable, and efficient lossless data compressor.
 *
 * Copyright (C) 2025 Frederic Langlet
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
import io.github.flanglet.kanzi.BitStreamException;
import io.github.flanglet.kanzi.EntropyDecoder;
import io.github.flanglet.kanzi.InputBitStream;
import io.github.flanglet.kanzi.Memory;

/**
 * <p>
 * Implementation of a Huffman decoder. This class decodes symbols from a bitstream using Huffman
 * codes, which are variable-length codes optimized for symbol frequencies.
 * </p>
 *
 * <p>
 * The decoder uses a lookup table to efficiently map incoming bits to symbols. It supports
 * different bitstream versions and handles chunk-based decoding.
 * </p>
 *
 * <p>
 * This class is not thread-safe.
 * </p>
 */
public class HuffmanDecoder implements EntropyDecoder {
  /**
   * A mask used to extract the relevant bits for table lookup in bitstream version 4.
   */
  private static final int TABLE_MASK_V4 = (1 << HuffmanCommon.MAX_SYMBOL_SIZE_V4) - 1;

  private final InputBitStream bitstream;
  private final int[] codes;
  private final int[] alphabet;
  private final short[] sizes;
  private final short[] table; // decoding table: code -> size, symbol
  private byte[] buffer;
  private final int chunkSize;
  private final int maxSymbolSize;
  private final int bsVersion;

  /**
   * Creates a new {@code HuffmanDecoder}.
   *
   * @param bitstream The {@link InputBitStream} to read compressed data from.
   * @param ctx A map containing additional context information for decompression.
   * @throws BitStreamException if an error occurs during bitstream operations.
   */
  public HuffmanDecoder(InputBitStream bitstream, Map<String, Object> ctx)
      throws BitStreamException {
    this(bitstream, HuffmanCommon.MAX_CHUNK_SIZE, ctx);
  }

  /**
   * Creates a new {@code HuffmanDecoder} with the specified chunk size.
   *
   * @param bitstream The {@link InputBitStream} to read compressed data from.
   * @param chunkSize The size of data chunks to process.
   * @param ctx A map containing additional context information for decompression.
   * @throws BitStreamException if an error occurs during bitstream operations.
   * @throws IllegalArgumentException if {@code chunkSize} is out of range.
   */
  public HuffmanDecoder(InputBitStream bitstream, int chunkSize, Map<String, Object> ctx)
      throws BitStreamException {
    if (bitstream == null)
      throw new NullPointerException("Huffman codec: Invalid null bitstream parameter");

    if (chunkSize < HuffmanCommon.MIN_CHUNK_SIZE)
      throw new IllegalArgumentException("Huffman codec: The chunk size must be at least 1024");

    if (chunkSize > HuffmanCommon.MAX_CHUNK_SIZE)
      throw new IllegalArgumentException(
          "Huffman codec: The chunk size must be at most " + HuffmanCommon.MAX_CHUNK_SIZE);

    this.bsVersion = (ctx == null) ? 6 : (Integer) ctx.getOrDefault("bsVersion", 6);
    this.bitstream = bitstream;
    this.sizes = new short[256];
    this.alphabet = new int[256];
    this.codes = new int[256];
    this.buffer = new byte[0];
    this.chunkSize = chunkSize;
    this.maxSymbolSize = HuffmanCommon.MAX_SYMBOL_SIZE_V4;
    this.table = new short[1 << this.maxSymbolSize];

    // Default lengths & canonical codes
    for (int i = 0; i < 256; i++) {
      this.sizes[i] = 8;
      this.codes[i] = i;
    }
  }

  /**
   * Reads the code lengths from the bitstream and generates the Huffman codes for decoding.
   *
   * @return The number of symbols in the alphabet, or 0 if the alphabet is empty.
   * @throws BitStreamException if the bitstream contains invalid data, such as incorrect Huffman
   *         symbol or code size.
   */
  public int readLengths() throws BitStreamException {
    final int count = EntropyUtils.decodeAlphabet(this.bitstream, this.alphabet);

    if (count == 0)
      return 0;

    ExpGolombDecoder egdec = new ExpGolombDecoder(this.bitstream, true);
    int curSize = 2;

    // Decode lengths
    for (int i = 0; i < count; i++) {
      final int s = this.alphabet[i];

      if ((s & 0xFF) != s) {
        throw new BitStreamException("Invalid bitstream: incorrect Huffman symbol " + s,
            BitStreamException.INVALID_STREAM);
      }

      this.codes[s] = 0;
      curSize += egdec.decodeByte();

      if ((curSize <= 0) || (curSize > this.maxSymbolSize)) {
        throw new BitStreamException(
            "Invalid bitstream: incorrect size " + curSize + " for Huffman symbol " + s,
            BitStreamException.INVALID_STREAM);
      }

      this.sizes[s] = (short) curSize;
    }

    // Create canonical codes
    if (HuffmanCommon.generateCanonicalCodes(this.sizes, this.codes, this.alphabet, count,
        this.maxSymbolSize) < 0) {
      throw new BitStreamException("Could not generate Huffman codes: max code length ("
          + this.maxSymbolSize + " bits) exceeded", BitStreamException.INVALID_STREAM);
    }

    egdec.dispose();
    return count;
  }

  /**
   * Builds the decoding tables used for efficient symbol lookup. The table maps prefixes of Huffman
   * codes to their corresponding symbol and code length.
   *
   * @param count The number of symbols in the alphabet.
   */
  private void buildDecodingTables(int count) {
    // Initialize table with non zero values.
    // If the bitstream is altered, the decoder may access these default table
    // values.
    // The number of consumed bits cannot be 0.
    for (int i = 0; i < this.table.length; i++)
      this.table[i] = 7;

    int length = 0;
    final int shift = this.maxSymbolSize;

    for (int i = 0; i < count; i++) {
      final int s = this.alphabet[i];

      if (this.sizes[s] > length)
        length = this.sizes[s];

      // code -> size, symbol
      final short val = (short) ((this.sizes[s] << 8) | s);
      final int code = this.codes[s];

      // All this.maxSymbolSize bit values read from the bit stream and
      // starting with the same prefix point to symbol s
      int idx = code << (shift - length);
      final int end = idx + (1 << (shift - length));

      while (idx < end)
        this.table[idx++] = val;
    }
  }

  /**
   * Decodes a block of data.
   * <p>
   * This method reads compressed data from the internal bitstream and decodes it into the provided
   * byte array. It handles different bitstream versions.
   * </p>
   *
   * @param block The byte array to decode into.
   * @param blkptr The starting position in the block.
   * @param count The number of bytes to decode.
   * @return The number of bytes decoded, or -1 if an error occurs (e.g., invalid parameters).
   */
  @Override
  public int decode(byte[] block, int blkptr, int count) {
    if ((block == null) || (blkptr + count > block.length) || (blkptr < 0) || (count < 0))
      return -1;

    if (count == 0)
      return 0;

    if (this.bsVersion < 6)
      return decodeV5(block, blkptr, count);

    return decodeV6(block, blkptr, count);
  }

  /**
   * Decodes a block of data using the Huffman V5 algorithm.
   * <p>
   * This method processes data in chunks, reading code lengths, rebuilding codes, and then decoding
   * the compressed data.
   * </p>
   *
   * @param block The byte array to decode into.
   * @param blkptr The starting position in the block.
   * @param count The number of bytes to decode.
   * @return The number of bytes decoded, or -1 if an error occurs.
   */
  private int decodeV5(byte[] block, int blkptr, int count) {
    int startChunk = blkptr;
    final int end = blkptr + count;

    while (startChunk < end) {
      final int endChunk = Math.min(startChunk + this.chunkSize, end);

      // For each chunk, read code lengths, rebuild codes, rebuild decoding table
      final int alphabetSize = this.readLengths();

      if (alphabetSize <= 0)
        return startChunk - blkptr;

      if (alphabetSize == 1) {
        // Shortcut for chunks with only one symbol
        for (int i = startChunk; i < endChunk; i++)
          block[i] = (byte) this.alphabet[0];

        startChunk = endChunk;
        continue;
      }

      this.buildDecodingTables(alphabetSize);

      // Read number of streams. Only 1 stream supported for now
      if (this.bitstream.readBits(2) != 0) {
        throw new BitStreamException(
            "Invalid bitstream: number streams not supported in this version",
            BitStreamException.INVALID_STREAM);
      }

      // Read chunk size
      final int szBits = EntropyUtils.readVarInt(this.bitstream);

      // Read compressed data from the bitstream
      if (szBits != 0) {
        final int sz = (szBits + 7) >>> 3;
        final int minLenBuf = Math.max(sz + (sz >> 3), 1024);

        if (this.buffer.length < minLenBuf)
          this.buffer = new byte[minLenBuf];

        this.bitstream.readBits(this.buffer, 0, szBits);
        long state = 0; // holds bits read from bitstream
        int bits = 0; // number of available bits in state
        int idx = 0;
        int n = startChunk;

        while (idx < sz - 8) {
          final int shift = (56 - bits) & -8;
          state = (state << shift)
              | (Memory.BigEndian.readLong64(this.buffer, idx) >>> (63 - shift) >>> 1); // handle
                                                                                        // shift
                                                                                        // = 0
          int bs = bits + shift - HuffmanCommon.MAX_SYMBOL_SIZE_V4;
          idx += (shift >>> 3);
          final int idx0 = (int) ((state >> bs) & TABLE_MASK_V4);
          final int val0 = this.table[idx0];
          bs -= (val0 >>> 8);
          final int idx1 = (int) ((state >> bs) & TABLE_MASK_V4);
          final int val1 = this.table[idx1];
          bs -= (val1 >>> 8);
          final int idx2 = (int) ((state >> bs) & TABLE_MASK_V4);
          final int val2 = this.table[idx2];
          bs -= (val2 >>> 8);
          final int idx3 = (int) ((state >> bs) & TABLE_MASK_V4);
          final int val3 = this.table[idx3];
          bs -= (val3 >>> 8);
          block[n + 0] = (byte) val0;
          block[n + 1] = (byte) val1;
          block[n + 2] = (byte) val2;
          block[n + 3] = (byte) val3;
          n += 4;
          bits = bs + HuffmanCommon.MAX_SYMBOL_SIZE_V4;
        }

        // Last bytes
        int nbBits = idx * 8;

        while (n < endChunk) {
          while ((bits < HuffmanCommon.MAX_SYMBOL_SIZE_V4) && (idx < sz)) {
            state = (state << 8) | (this.buffer[idx] & 0xFF);
            idx++;
            nbBits = (idx == sz) ? szBits : nbBits + 8;

            // 'bits' may overshoot when idx == sz due to padding state bits
            // It is necessary to compute proper table indexes
            // and has no consequences (except bits != 0 at the end of chunk)
            bits += 8;
          }

          short val;
          int iidx;

          if (bits >= HuffmanCommon.MAX_SYMBOL_SIZE_V4)
            iidx = (int) ((state >> (bits - HuffmanCommon.MAX_SYMBOL_SIZE_V4)) & TABLE_MASK_V4);
          else
            iidx = (int) ((state << (HuffmanCommon.MAX_SYMBOL_SIZE_V4 - bits)) & TABLE_MASK_V4);

          val = this.table[iidx];
          bits -= (val >>> 8);
          block[n++] = (byte) val;
        }
      }

      startChunk = endChunk;
    }

    return count;
  }

  /**
   * Decodes a block of data using the Huffman V6 algorithm.
   * <p>
   * This method processes data in chunks, reading code lengths, rebuilding codes, and then decoding
   * the compressed data using a parallel approach for larger chunks.
   * </p>
   *
   * @param block The byte array to decode into.
   * @param blkptr The starting position in the block.
   * @param count The number of bytes to decode.
   * @return The number of bytes decoded, or -1 if an error occurs.
   */
  private int decodeV6(byte[] block, int blkptr, int count) {
    if (this.buffer.length < 2 * this.chunkSize)
      this.buffer = new byte[2 * this.chunkSize];

    int startChunk = blkptr;
    final int end = blkptr + count;

    while (startChunk < end) {
      final int sizeChunk = Math.min(this.chunkSize, end - startChunk);
      final int endChunk = startChunk + sizeChunk;

      if (sizeChunk < 32) {
        // Special case for small chunks
        this.bitstream.readBits(block, startChunk, 8 * sizeChunk);
      } else {
        // For each chunk, read code lengths, rebuild codes, rebuild decoding table
        final int alphabetSize = this.readLengths();

        if (alphabetSize <= 0)
          return startChunk - blkptr;

        if (alphabetSize == 1) {
          // Shortcut for chunks with only one symbol
          for (int i = startChunk; i < endChunk; i++)
            block[i] = (byte) this.alphabet[0];
        } else {
          this.buildDecodingTables(alphabetSize);

          if (decodeChunk(block, startChunk, endChunk - startChunk) == false)
            return startChunk - blkptr;
        }
      }

      startChunk = endChunk;
    }

    return count;
  }

  /**
   * Decodes a chunk of data using a parallel approach.
   * <p>
   * This method is optimized for larger chunks (count >= 32) and processes four parallel streams of
   * compressed data.
   * </p>
   *
   * @param block The byte array to decode into.
   * @param blkptr The starting position in the block.
   * @param count The number of bytes to decode in this chunk.
   * @return {@code true} if the chunk was decoded successfully, {@code false} otherwise.
   */
  public boolean decodeChunk(byte[] block, int blkptr, int count) {
    // Read fragment sizes
    final int szBits0 = EntropyUtils.readVarInt(this.bitstream);
    final int szBits1 = EntropyUtils.readVarInt(this.bitstream);
    final int szBits2 = EntropyUtils.readVarInt(this.bitstream);
    final int szBits3 = EntropyUtils.readVarInt(this.bitstream);

    if ((szBits0 < 0) || (szBits1 < 0) || (szBits2 < 0) || (szBits3 < 0))
      return false;

    for (int i = 0; i < this.buffer.length; i++)
      this.buffer[i] = 0;

    int idx0 = 0 * (this.buffer.length / 4);
    int idx1 = 1 * (this.buffer.length / 4);
    int idx2 = 2 * (this.buffer.length / 4);
    int idx3 = 3 * (this.buffer.length / 4);

    // Read all compressed data from bitstream
    this.bitstream.readBits(this.buffer, idx0, szBits0);
    this.bitstream.readBits(this.buffer, idx1, szBits1);
    this.bitstream.readBits(this.buffer, idx2, szBits2);
    this.bitstream.readBits(this.buffer, idx3, szBits3);

    // State variables for each of the four parallel streams
    long state0 = 0, state1 = 0, state2 = 0, state3 = 0; // bits read from bitstream
    int bits0 = 0, bits1 = 0, bits2 = 0, bits3 = 0; // number of available bits in state

    final int szFrag = count / 4;
    int blockIdx0 = blkptr + 0 * szFrag;
    int blockIdx1 = blkptr + 1 * szFrag;
    int blockIdx2 = blkptr + 2 * szFrag;
    int blockIdx3 = blkptr + 3 * szFrag;
    int n = 0;

    while (n < szFrag - 4) {
      // Fill 64 bits of state from the bitstream for each stream
      final int shift0 = (56 - bits0) & -8;
      final int shift1 = (56 - bits1) & -8;
      final int shift2 = (56 - bits2) & -8;
      final int shift3 = (56 - bits3) & -8;
      state0 = (state0 << shift0)
          | (Memory.BigEndian.readLong64(this.buffer, idx0) >>> (63 - shift0) >>> 1); // handle
                                                                                      // shift = 0
      state1 = (state1 << shift1)
          | (Memory.BigEndian.readLong64(this.buffer, idx1) >>> (63 - shift1) >>> 1); // handle
                                                                                      // shift = 0
      state2 = (state2 << shift2)
          | (Memory.BigEndian.readLong64(this.buffer, idx2) >>> (63 - shift2) >>> 1); // handle
                                                                                      // shift = 0
      state3 = (state3 << shift3)
          | (Memory.BigEndian.readLong64(this.buffer, idx3) >>> (63 - shift3) >>> 1); // handle
                                                                                      // shift = 0
      int bs0 = bits0 + shift0 - HuffmanCommon.MAX_SYMBOL_SIZE_V4;
      int bs1 = bits1 + shift1 - HuffmanCommon.MAX_SYMBOL_SIZE_V4;
      int bs2 = bits2 + shift2 - HuffmanCommon.MAX_SYMBOL_SIZE_V4;
      int bs3 = bits3 + shift3 - HuffmanCommon.MAX_SYMBOL_SIZE_V4;
      idx0 += (shift0 >>> 3);
      idx1 += (shift1 >>> 3);
      idx2 += (shift2 >>> 3);
      idx3 += (shift3 >>> 3);

      // Decompress 4 symbols per stream
      final int val00 = this.table[(int) (state0 >> bs0) & TABLE_MASK_V4];
      bs0 -= (val00 >>> 8);
      final int val10 = this.table[(int) (state1 >> bs1) & TABLE_MASK_V4];
      bs1 -= (val10 >>> 8);
      final int val20 = this.table[(int) (state2 >> bs2) & TABLE_MASK_V4];
      bs2 -= (val20 >>> 8);
      final int val30 = this.table[(int) (state3 >> bs3) & TABLE_MASK_V4];
      bs3 -= (val30 >>> 8);
      final int val01 = this.table[(int) (state0 >> bs0) & TABLE_MASK_V4];
      bs0 -= (val01 >>> 8);
      final int val11 = this.table[(int) (state1 >> bs1) & TABLE_MASK_V4];
      bs1 -= (val11 >>> 8);
      final int val21 = this.table[(int) (state2 >> bs2) & TABLE_MASK_V4];
      bs2 -= (val21 >>> 8);
      final int val31 = this.table[(int) (state3 >> bs3) & TABLE_MASK_V4];
      bs3 -= (val31 >>> 8);
      final int val02 = this.table[(int) (state0 >> bs0) & TABLE_MASK_V4];
      bs0 -= (val02 >>> 8);
      final int val12 = this.table[(int) (state1 >> bs1) & TABLE_MASK_V4];
      bs1 -= (val12 >>> 8);
      final int val22 = this.table[(int) (state2 >> bs2) & TABLE_MASK_V4];
      bs2 -= (val22 >>> 8);
      final int val32 = this.table[(int) (state3 >> bs3) & TABLE_MASK_V4];
      bs3 -= (val32 >>> 8);
      final int val03 = this.table[(int) (state0 >> bs0) & TABLE_MASK_V4];
      bs0 -= (val03 >>> 8);
      final int val13 = this.table[(int) (state1 >> bs1) & TABLE_MASK_V4];
      bs1 -= (val13 >>> 8);
      final int val23 = this.table[(int) (state2 >> bs2) & TABLE_MASK_V4];
      bs2 -= (val23 >>> 8);
      final int val33 = this.table[(int) (state3 >> bs3) & TABLE_MASK_V4];
      bs3 -= (val33 >>> 8);

      bits0 = bs0 + HuffmanCommon.MAX_SYMBOL_SIZE_V4;
      bits1 = bs1 + HuffmanCommon.MAX_SYMBOL_SIZE_V4;
      bits2 = bs2 + HuffmanCommon.MAX_SYMBOL_SIZE_V4;
      bits3 = bs3 + HuffmanCommon.MAX_SYMBOL_SIZE_V4;

      block[blockIdx0 + 0] = (byte) val00;
      block[blockIdx1 + 0] = (byte) val10;
      block[blockIdx2 + 0] = (byte) val20;
      block[blockIdx3 + 0] = (byte) val30;
      block[blockIdx0 + 1] = (byte) val01;
      block[blockIdx1 + 1] = (byte) val11;
      block[blockIdx2 + 1] = (byte) val21;
      block[blockIdx3 + 1] = (byte) val31;
      block[blockIdx0 + 2] = (byte) val02;
      block[blockIdx1 + 2] = (byte) val12;
      block[blockIdx2 + 2] = (byte) val22;
      block[blockIdx3 + 2] = (byte) val32;
      block[blockIdx0 + 3] = (byte) val03;
      block[blockIdx1 + 3] = (byte) val13;
      block[blockIdx2 + 3] = (byte) val23;
      block[blockIdx3 + 3] = (byte) val33;
      n += 4;
      blockIdx0 += 4;
      blockIdx1 += 4;
      blockIdx2 += 4;
      blockIdx3 += 4;
    }

    // Fill 64 bits of state from the bitstream for each stream
    final int shift0 = (56 - bits0) & -8;
    final int shift1 = (56 - bits1) & -8;
    final int shift2 = (56 - bits2) & -8;
    final int shift3 = (56 - bits3) & -8;
    state0 = (state0 << shift0)
        | (Memory.BigEndian.readLong64(this.buffer, idx0) >>> (63 - shift0) >>> 1); // handle shift
                                                                                    // = 0
    state1 = (state1 << shift1)
        | (Memory.BigEndian.readLong64(this.buffer, idx1) >>> (63 - shift1) >>> 1); // handle shift
                                                                                    // = 0
    state2 = (state2 << shift2)
        | (Memory.BigEndian.readLong64(this.buffer, idx2) >>> (63 - shift2) >>> 1); // handle shift
                                                                                    // = 0
    state3 = (state3 << shift3)
        | (Memory.BigEndian.readLong64(this.buffer, idx3) >>> (63 - shift3) >>> 1); // handle shift
                                                                                    // = 0
    int bs0 = bits0 + shift0 - HuffmanCommon.MAX_SYMBOL_SIZE_V4;
    int bs1 = bits1 + shift1 - HuffmanCommon.MAX_SYMBOL_SIZE_V4;
    int bs2 = bits2 + shift2 - HuffmanCommon.MAX_SYMBOL_SIZE_V4;
    int bs3 = bits3 + shift3 - HuffmanCommon.MAX_SYMBOL_SIZE_V4;

    while (n < szFrag) {
      // Decompress 1 symbol per stream
      final int val0 = this.table[(int) (state0 >> bs0) & TABLE_MASK_V4];
      bs0 -= (val0 >>> 8);
      final int val1 = this.table[(int) (state1 >> bs1) & TABLE_MASK_V4];
      bs1 -= (val1 >>> 8);
      final int val2 = this.table[(int) (state2 >> bs2) & TABLE_MASK_V4];
      bs2 -= (val2 >>> 8);
      final int val3 = this.table[(int) (state3 >> bs3) & TABLE_MASK_V4];
      bs3 -= (val3 >>> 8);

      block[blockIdx0++] = (byte) val0;
      block[blockIdx1++] = (byte) val1;
      block[blockIdx2++] = (byte) val2;
      block[blockIdx3++] = (byte) val3;
      n++;
    }

    // Process any remaining bytes at the end of the whole chunk
    final int count4 = 4 * szFrag;

    for (int i = count4; i < count; i++)
      block[blkptr + i] = (byte) this.bitstream.readBits(8);

    return true;
  }

  /**
   * Returns the {@link InputBitStream} used by this decoder.
   *
   * @return The {@link InputBitStream}.
   */
  @Override
  public InputBitStream getBitStream() {
    return this.bitstream;
  }

  /**
   * Disposes of any resources used by the decoder. This method currently does nothing as there are
   * no specific resources to release.
   */
  @Override
  public void dispose() {}
}
