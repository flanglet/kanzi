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


import io.github.flanglet.kanzi.Predictor;
import io.github.flanglet.kanzi.EntropyDecoder;
import io.github.flanglet.kanzi.InputBitStream;
import io.github.flanglet.kanzi.Memory;
import io.github.flanglet.kanzi.SliceByteArray;


// This class is a generic implementation of a boolean entropy decoder
public class BinaryEntropyDecoder implements EntropyDecoder
{
   private static final long TOP        = 0x00FFFFFFFFFFFFFFL;
   private static final long MASK_24_56 = 0x00FFFFFFFF000000L;
   private static final long MASK_0_56  = 0x00FFFFFFFFFFFFFFL;
   private static final long MASK_0_32  = 0x00000000FFFFFFFFL;
   private static final int MAX_BLOCK_SIZE = 1 << 30;
   private static final int MAX_CHUNK_SIZE = 1 << 26;

   private final Predictor predictor;
   private long low;
   private long high;
   private long current;
   private final InputBitStream bitstream;
   private SliceByteArray sba;


   public BinaryEntropyDecoder(InputBitStream bitstream, Predictor predictor)
   {
      if (bitstream == null)
         throw new NullPointerException("BinaryEntropy codec: Invalid null bitstream parameter");

      if (predictor == null)
         throw new NullPointerException("BinaryEntropy codec: Invalid null predictor parameter");

      // Defer stream reading. We are creating the object, we should not do any I/O
      this.low = 0L;
      this.high = TOP;
      this.bitstream = bitstream;
      this.predictor = predictor;
      this.sba = new SliceByteArray(new byte[0], 0);
   }


   @Override
   public int decode(byte[] block, int blkptr, int count)
   {
      if ((block == null) || (blkptr+count > block.length) || (blkptr < 0) || (count < 0) || (count > MAX_BLOCK_SIZE))
         return -1;

      if (count == 0)
         return 0;

      int startChunk = blkptr;
      final int end = blkptr + count;
      int length = (count < 64) ? 64 : count;

      if (count >= MAX_CHUNK_SIZE)
      {
         // If the block is big (>=64MB), split the decoding to avoid allocating
         // too much memory.
         length = (count < 8*MAX_CHUNK_SIZE) ? count>>3 : count>>4;
      }

      // Split block into chunks, read bit array from bitstream and decode chunk
      while (startChunk < end)
      {
         final int chunkSize = Math.min(length, end-startChunk);

         if (this.sba.array.length < (chunkSize+(chunkSize>>3)))
            this.sba.array = new byte[chunkSize+(chunkSize>>3)];

         final int szBytes = EntropyUtils.readVarInt(this.bitstream);
         this.current = this.bitstream.readBits(56);

         if (szBytes != 0)
            this.bitstream.readBits(this.sba.array, 0, 8*szBytes);

         this.sba.index = 0;
         final int endChunk = startChunk + chunkSize;

         for (int i=startChunk; i<endChunk; i++)
            block[i] = this.decodeByte();

         startChunk = endChunk;
      }

      return count;
   }


   public final byte decodeByte()
   {
      return (byte) ((this.decodeBit(this.predictor.get()) << 7)
            | (this.decodeBit(this.predictor.get()) << 6)
            | (this.decodeBit(this.predictor.get()) << 5)
            | (this.decodeBit(this.predictor.get()) << 4)
            | (this.decodeBit(this.predictor.get()) << 3)
            | (this.decodeBit(this.predictor.get()) << 2)
            | (this.decodeBit(this.predictor.get()) << 1)
            |  this.decodeBit(this.predictor.get()));
   }


   public int decodeBit(int pred)
   {
      // Calculate interval split
      // Written in a way to maximize accuracy of multiplication/division
      final long split = ((((this.high - this.low) >>> 4) * pred) >>> 8) + this.low;
      int bit;

      if (split >= this.current)
      {
         bit = 1;
         this.high = split;
      }
      else
      {
         bit = 0;
         this.low = -~split;
      }

       // Update predictor
      this.predictor.update(bit);

      // Read 32 bits from bitstream
      while (((this.low ^ this.high) & MASK_24_56) == 0)
         this.read();

      return bit;
   }


   protected void read()
   {
      this.low = (this.low<<32) & MASK_0_56;
      this.high = ((this.high<<32) | MASK_0_32) & MASK_0_56;
      final long val = Memory.BigEndian.readInt32(this.sba.array, this.sba.index) & 0xFFFFFFFFL;
      this.current = ((this.current<<32) | val) & MASK_0_56;
      this.sba.index += 4;
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
