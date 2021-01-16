/*
Copyright 2011-2021 Frederic Langlet
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

package kanzi.entropy;

import kanzi.BitStreamException;
import kanzi.EntropyDecoder;
import kanzi.InputBitStream;


// Uses tables to decode symbols
public class HuffmanDecoder implements EntropyDecoder
{
   private static final int DECODING_BATCH_SIZE = 14; // ensures decoding table fits in L1 cache
   private static final int TABLE_MASK = (1<<DECODING_BATCH_SIZE) - 1;

   private final InputBitStream bs;
   private final int[] codes;
   private final int[] alphabet;
   private final short[] sizes;
   private final short[] table; // decoding table: code -> size, symbol
   private final int chunkSize;
   private long state; // holds bits read from bitstream
   private int bits; // holds number of unused bits in 'state'


   public HuffmanDecoder(InputBitStream bitstream) throws BitStreamException
   {
      this(bitstream, HuffmanCommon.MAX_CHUNK_SIZE);
   }


   // The chunk size indicates how many bytes are encoded (per block) before
   // resetting the frequency stats.
   public HuffmanDecoder(InputBitStream bitstream, int chunkSize) throws BitStreamException
   {
      if (bitstream == null)
          throw new NullPointerException("Huffman codec: Invalid null bitstream parameter");

      if (chunkSize < 1024)
         throw new IllegalArgumentException("Huffman codec: The chunk size must be at least 1024");

      if (chunkSize > HuffmanCommon.MAX_CHUNK_SIZE)
         throw new IllegalArgumentException("Huffman codec: The chunk size must be at most "+HuffmanCommon.MAX_CHUNK_SIZE);

      this.bs = bitstream;
      this.sizes = new short[256];
      this.alphabet = new int[256];
      this.codes = new int[256];
      this.table = new short[TABLE_MASK+1];
      this.chunkSize = chunkSize;

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
      final int count = EntropyUtils.decodeAlphabet(this.bs, this.alphabet);

      if (count == 0)
         return 0;

      ExpGolombDecoder egdec = new ExpGolombDecoder(this.bs, true);
      int currSize = 2;

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
         currSize += egdec.decodeByte();

         if ((currSize <= 0) || (currSize > HuffmanCommon.MAX_SYMBOL_SIZE))
         {
            throw new BitStreamException("Invalid bitstream: incorrect size " + currSize +
                    " for Huffman symbol " + s, BitStreamException.INVALID_STREAM);
         }

         this.sizes[s] = (short) currSize;
      }

      // Create canonical codes
      if (HuffmanCommon.generateCanonicalCodes(this.sizes, this.codes, this.alphabet, count) < 0)
      {
         throw new BitStreamException("Could not generate Huffman codes: max code length (" +
                 HuffmanCommon.MAX_SYMBOL_SIZE + " bits) exceeded", BitStreamException.INVALID_STREAM);
      }

      this.buildDecodingTables(count);
      return count;
   }


   // max(CodeLen) must be <= MAX_SYMBOL_SIZE
   private void buildDecodingTables(int count)
   {
      for (int i=0; i<this.table.length; i++)
         this.table[i] = 0;

      int length = 0;

      for (int i=0; i<count; i++)
      {
         final int s = this.alphabet[i];

         if (this.sizes[s] > length)
            length = this.sizes[s];

         // code -> size, symbol
         final short val = (short) ((this.sizes[s]<<8) | s);
         final int code = this.codes[s];

         // All DECODING_BATCH_SIZE bit values read from the bit stream and
         // starting with the same prefix point to symbol s
         int idx = code << (DECODING_BATCH_SIZE-length);
         final int end = (code+1) << (DECODING_BATCH_SIZE-length);

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
         final int endChunk = (startChunk+this.chunkSize < end) ? startChunk+this.chunkSize : end;

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

         // Compute minimum number of bits required in bitstream for fast decoding
         final int minCodeLen = this.sizes[this.alphabet[0]]; // not 0
         int padding = 64 / minCodeLen;

         if (minCodeLen * padding != 64)
            padding++;

         final int endChunk4 = startChunk + Math.max(((endChunk-startChunk-padding)&-4), 0);

         for (int i=startChunk; i<endChunk4; i+=4)
         {
            this.fetchBits();
            block[i]   = this.decodeByte();
            block[i+1] = this.decodeByte();
            block[i+2] = this.decodeByte();
            block[i+3] = this.decodeByte();
         }

         // Fallback to regular decoding
         for (int i=endChunk4; i<endChunk; i++)
            block[i] = this.slowDecodeByte();

         startChunk = endChunk;
      }

      return count;
   }


   private byte slowDecodeByte()
   {
      int code = 0;
      int codeLen = 0;

      while (codeLen < HuffmanCommon.MAX_SYMBOL_SIZE)
      {
         codeLen++;
         code <<= 1;

         if (this.bits == 0)
         {
            code |= this.bs.readBit();
         }
         else
         {
            this.bits--;
            code |= ((this.state >>> this.bits) & 1);
         }

         final int idx = code << (DECODING_BATCH_SIZE-codeLen);

         if ((this.table[idx] >>> 8) == codeLen)
            return (byte) this.table[idx];
      }

      throw new BitStreamException("Invalid bitstream: incorrect Huffman code",
         BitStreamException.INVALID_STREAM);
   }


   private void fetchBits()
   {
      this.state = (this.bits == 0) ? this.bs.readBits(64) :
         (this.state << -this.bits) | this.bs.readBits(64-this.bits);
      this.bits = 64;
   }


   private byte decodeByte()
   {
      final int idx = (int) (this.state >>> (this.bits-DECODING_BATCH_SIZE));
      final int val = this.table[idx&TABLE_MASK];
      this.bits -= (val >>> 8);
      return (byte) val;
   }


   @Override
   public InputBitStream getBitStream()
   {
      return this.bs;
   }


   @Override
   public void dispose()
   {
   }
}