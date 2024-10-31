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

package io.github.flanglet.kanzi.entropy;

import java.util.Map;
import io.github.flanglet.kanzi.BitStreamException;
import io.github.flanglet.kanzi.EntropyDecoder;
import io.github.flanglet.kanzi.InputBitStream;
import io.github.flanglet.kanzi.Memory;


// Uses tables to decode symbols
public class HuffmanDecoder implements EntropyDecoder
{
   private static final int TABLE_MASK_V3 = (1<<HuffmanCommon.MAX_SYMBOL_SIZE_V3) - 1;
   private static final int TABLE_MASK_V4 = (1<<HuffmanCommon.MAX_SYMBOL_SIZE_V4) - 1;

   private final InputBitStream bitstream;
   private final int[] codes;
   private final int[] alphabet;
   private final short[] sizes;
   private final short[] table; // decoding table: code -> size, symbol
   private byte[] buffer;
   private final int chunkSize;
   private final int maxSymbolSize;
   private final boolean isBsVersion3;


   public HuffmanDecoder(InputBitStream bitstream, Map<String, Object> ctx) throws BitStreamException
   {
      this(bitstream, HuffmanCommon.MAX_CHUNK_SIZE, ctx);
   }


   // The chunk size indicates how many bytes are encoded (per block) before
   // resetting the frequency stats.
   public HuffmanDecoder(InputBitStream bitstream, int chunkSize, Map<String, Object> ctx) throws BitStreamException
   {
      if (bitstream == null)
          throw new NullPointerException("Huffman codec: Invalid null bitstream parameter");

      if (chunkSize < HuffmanCommon.MIN_CHUNK_SIZE)
         throw new IllegalArgumentException("Huffman codec: The chunk size must be at least 1024");

      if (chunkSize > HuffmanCommon.MAX_CHUNK_SIZE)
         throw new IllegalArgumentException("Huffman codec: The chunk size must be at most "+HuffmanCommon.MAX_CHUNK_SIZE);

      final int bsVersion = (ctx == null) ? 4 : (Integer) ctx.getOrDefault("bsVersion", 4);
      this.bitstream = bitstream;
      this.sizes = new short[256];
      this.alphabet = new int[256];
      this.codes = new int[256];
      this.buffer = new byte[0];
      this.chunkSize = chunkSize;
      this.isBsVersion3 = bsVersion < 4; // old encoding
      this.maxSymbolSize = (this.isBsVersion3 == true) ? HuffmanCommon.MAX_SYMBOL_SIZE_V3 :
              HuffmanCommon.MAX_SYMBOL_SIZE_V4;
      this.table = new short[1<<this.maxSymbolSize];

      // Default lengths & canonical codes
      for (int i=0; i<256; i++)
      {
         this.sizes[i] = 8;
         this.codes[i] = i;
      }
   }


   // readLengths decodes the code lengths from the bitstream and generates
   // the Huffman codes for decoding.
   public int readLengths() throws BitStreamException
   {
      final int count = EntropyUtils.decodeAlphabet(this.bitstream, this.alphabet);

      if (count == 0)
         return 0;

      ExpGolombDecoder egdec = new ExpGolombDecoder(this.bitstream, true);
      int curSize = 2;

      // Decode lengths
      for (int i=0; i<count; i++)
      {
         final int s = this.alphabet[i];

         if ((s&0xFF) != s)
         {
            throw new BitStreamException("Invalid bitstream: incorrect Huffman symbol " + s,
               BitStreamException.INVALID_STREAM);
         }

         this.codes[s] = 0;
         curSize += egdec.decodeByte();

         if ((curSize <= 0) || (curSize > this.maxSymbolSize))
         {
            throw new BitStreamException("Invalid bitstream: incorrect size " + curSize +
                    " for Huffman symbol " + s, BitStreamException.INVALID_STREAM);
         }

         this.sizes[s] = (short) curSize;
      }

      // Create canonical codes
      if (HuffmanCommon.generateCanonicalCodes(this.sizes, this.codes, this.alphabet, count, this.maxSymbolSize) < 0)
      {
         throw new BitStreamException("Could not generate Huffman codes: max code length (" +
                 this.maxSymbolSize + " bits) exceeded", BitStreamException.INVALID_STREAM);
      }

      egdec.dispose();
      return count;
   }


   // max(CodeLen) must be <= MAX_SYMBOL_SIZE
   private void buildDecodingTables(int count)
   {
      for (int i=0; i<this.table.length; i++)
         this.table[i] = 0;

      int length = 0;
      final int shift = this.maxSymbolSize;

      for (int i=0; i<count; i++)
      {
         final int s = this.alphabet[i];

         if (this.sizes[s] > length)
            length = this.sizes[s];

         // code -> size, symbol
         final short val = (short) ((this.sizes[s]<<8) | s);
         final int code = this.codes[s];

         // All this.maxSymbolSize bit values read from the bit stream and
         // starting with the same prefix point to symbol s
         int idx = code << (shift-length);
         final int end = idx + (1<<(shift-length));

         while (idx < end)
            this.table[idx++] = val;
      }
   }


   @Override
   public int decode(byte[] block, int blkptr, int count)
   {
      if ((block == null) || (blkptr+count > block.length) || (blkptr < 0) || (count < 0))
        return -1;

      if (count == 0)
         return 0;

      int startChunk = blkptr;
      final int end = blkptr + count;

      while (startChunk < end)
      {
         final int endChunk = Math.min(startChunk+this.chunkSize, end);

         // For each chunk, read code lengths, rebuild codes, rebuild decoding table
         final int alphabetSize = this.readLengths();

         if (alphabetSize <= 0)
            return startChunk - blkptr;

         if (alphabetSize == 1)
         {
            // Shortcut for chunks with only one symbol
            for (int i=startChunk; i<endChunk; i++)
               block[i] = (byte) this.alphabet[0];

            startChunk = endChunk;
            continue;
         }

         this.buildDecodingTables(alphabetSize);

         if (this.isBsVersion3 == true)
         {
            // Compute minimum number of bits required in bitstream for fast decoding
            final int minCodeLen = this.sizes[this.alphabet[0]]; // not 0
            int padding = 64 / minCodeLen;

            if (minCodeLen * padding != 64)
               padding++;

            final int endChunk4 = startChunk + Math.max(((endChunk-startChunk-padding)&-4), 0);
            long st = 0;
            int b = 0;

            for (int i=startChunk; i<endChunk4; i+=4)
            {
               st = (st << -b) | this.bitstream.readBits(64-b);
               b = 64;
               final int idx0 = (int) (st >>> (b-HuffmanCommon.MAX_SYMBOL_SIZE_V3));
               final int val0 = this.table[idx0&TABLE_MASK_V3];
               b -= (val0 >>> 8);
               final int idx1 = (int) (st >>> (b-HuffmanCommon.MAX_SYMBOL_SIZE_V3));
               final int val1 = this.table[idx1&TABLE_MASK_V3];
               b -= (val1 >>> 8);
               final int idx2 = (int) (st >>> (b-HuffmanCommon.MAX_SYMBOL_SIZE_V3));
               final int val2 = this.table[idx2&TABLE_MASK_V3];
               b -= (val2 >>> 8);
               final int idx3 = (int) (st >>> (b-HuffmanCommon.MAX_SYMBOL_SIZE_V3));
               final int val3 = this.table[idx3&TABLE_MASK_V3];
               b -= (val3 >>> 8);
               block[i]   = (byte) val0;
               block[i+1] = (byte) val1;
               block[i+2] = (byte) val2;
               block[i+3] = (byte) val3;
            }

            // Fallback to regular decoding
            for (int i=endChunk4; i<endChunk; i++)
            {
               int code = 0;
               int codeLen = 0;

               while (true)
               {
                  codeLen++;
                  code <<= 1;

                  if (b == 0)
                  {
                     code |= this.bitstream.readBit();
                  }
                  else
                  {
                     b--;
                     code |= ((st >>> b) & 1);
                  }

                  final int idx = code << (HuffmanCommon.MAX_SYMBOL_SIZE_V3-codeLen);

                  if ((this.table[idx] >>> 8) == codeLen)
                  {
                     block[i] = (byte) this.table[idx];
                     break;
                  }

                  if (codeLen >= HuffmanCommon.MAX_SYMBOL_SIZE_V3)
                     throw new BitStreamException("Invalid bitstream: incorrect Huffman code",
                        BitStreamException.INVALID_STREAM);
               }
            }
         }
         else
         {
            // bsVersion >= 4
            // Read number of streams. Only 1 stream supported for now
            if (this.bitstream.readBits(2) != 0)
                throw new BitStreamException("Invalid bitstream: number streams not supported in this version",
                    BitStreamException.INVALID_STREAM);

            // Read chunk size
            final int szBits = EntropyUtils.readVarInt(this.bitstream);

            // Read compressed data from the bitstream
            if (szBits != 0)
            {
               final int sz = (szBits+7) >>> 3;
               final int minLenBuf = Math.max(sz+(sz>>3), 1024);

               if (this.buffer.length < minLenBuf)
                   this.buffer = new byte[minLenBuf];

               this.bitstream.readBits(this.buffer, 0, szBits);
               long state = 0; // holds bits read from bitstream
               int bits = 0; // number of available bits in state
               int idx = 0;
               int n = startChunk;

               while (idx < sz-8)
               {
                   final int shift = (56 - bits) & -8;
                   state = (state << shift) | (Memory.BigEndian.readLong64(this.buffer, idx) >>> (63-shift) >>> 1); // handle shift = 0
                   int bs = bits + shift - HuffmanCommon.MAX_SYMBOL_SIZE_V4;
                   idx += (shift >>> 3);
                   final int idx0 = (int) ((state>>bs) & TABLE_MASK_V4);
                   final int val0 = this.table[idx0];
                   bs -= (val0 >>> 8);
                   final int idx1 = (int) ((state>>bs) & TABLE_MASK_V4);
                   final int val1 = this.table[idx1];
                   bs -= (val1 >>> 8);
                   final int idx2 = (int) ((state>>bs) & TABLE_MASK_V4);
                   final int val2 = this.table[idx2];
                   bs -= (val2 >>> 8);
                   final int idx3 = (int) ((state>>bs) & TABLE_MASK_V4);
                   final int val3 = this.table[idx3];
                   bs -= (val3 >>> 8);
                   block[n+0] = (byte) val0;
                   block[n+1] = (byte) val1;
                   block[n+2] = (byte) val2;
                   block[n+3] = (byte) val3;
                   n += 4;
                   bits = bs + HuffmanCommon.MAX_SYMBOL_SIZE_V4;
                }

                // Last bytes
                int nbBits = idx * 8;

                while (n < endChunk)
                {
                   while ((bits < HuffmanCommon.MAX_SYMBOL_SIZE_V4) && (idx < sz))
                   {
                       state = (state << 8) | (this.buffer[idx]&0xFF);
                       idx++;
                       nbBits = (idx == sz) ? szBits : nbBits+8;

                       // 'bits' may overshoot when idx == sz due to padding state bits
                       // It is necessary to compute proper table indexes
                       // and has no consequences (except bits != 0 at the end of chunk)
                       bits += 8;
                    }

                    short val;
                    int iidx;

                    if (bits >= HuffmanCommon.MAX_SYMBOL_SIZE_V4)
                       iidx = (int) ((state>>(bits-HuffmanCommon.MAX_SYMBOL_SIZE_V4))&TABLE_MASK_V4);
                    else
                       iidx = (int) ((state<<(HuffmanCommon.MAX_SYMBOL_SIZE_V4-bits))&TABLE_MASK_V4);

                    val = this.table[iidx];
                    bits -= (val >>> 8);
                    block[n++] = (byte) val;
                 }
             }
         }

         startChunk = endChunk;
      }

      return count;
   }


   @Override
   public InputBitStream getBitStream()
   {
      return this.bitstream;
   }


   @Override
   public void dispose()
   {
   }
}
