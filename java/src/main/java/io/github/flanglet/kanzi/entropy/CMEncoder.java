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

package io.github.flanglet.kanzi.entropy;


import io.github.flanglet.kanzi.EntropyEncoder;
import io.github.flanglet.kanzi.Memory;
import io.github.flanglet.kanzi.OutputBitStream;
import io.github.flanglet.kanzi.Predictor;
import io.github.flanglet.kanzi.SliceByteArray;


public class CMEncoder implements EntropyEncoder
{
   private static final long TOP        = 0x00FFFFFFFFFFFFFFL;
   private static final long MASK_24_56 = 0x00FFFFFFFF000000L;
   private static final long MASK_0_24  = 0x0000000000FFFFFFL;
   private static final long MASK_0_32  = 0x00000000FFFFFFFFL;

   private long low;
   private long high;
   private final OutputBitStream bitstream;
   private boolean disposed;
   private SliceByteArray sba;
   private CMPredictor[] preds;
   private Predictor predictor;


   public CMEncoder(OutputBitStream bitstream)
   {
      if (bitstream == null)
         throw new NullPointerException("CM codec: Invalid null bitstream parameter");

      this.low = 0L;
      this.high = TOP;
      this.bitstream = bitstream;
      this.sba = new SliceByteArray(new byte[0], 0);
      this.preds = new CMPredictor[] { new CMPredictor(null), new CMPredictor(null) };
      this.predictor = this.preds[0];
   }


   @Override
   public int encode(byte[] block, int blkptr, int count)
   {
      if ((block == null) || (blkptr+count > block.length) || (blkptr < 0) || (count < 0) || (count > 1<<30))
         return -1;

      if (count == 0)
         return 0;

      int startChunk = blkptr;
      final int end = blkptr + count;
      int length = (count < 64) ? 64 : count;

      if (count >= 1<<26)
      {
         // If the block is big (>=64MB), split the encoding to avoid allocating
         // too much memory.
         length = (count < (1<<29)) ? count >> 3 : count >> 4;
      }

      // Split block into chunks, encode chunk and write bit array to bitstream
      while (startChunk < end)
      {
         final int chunkSize = startChunk+length < end ? length : end-startChunk;

         if (this.sba.array.length < (chunkSize+(chunkSize>>3)))
            this.sba.array = new byte[chunkSize+(chunkSize>>3)];

         this.sba.index = 0;
         final int endChunk = startChunk + chunkSize;
         int run = 0;
         this.predictor = this.preds[0];
         int prv = 0;

         for (int i=startChunk; i<endChunk; i++)
         {            
            if ((block[i] == 0) & (run < 254))
            {
               run++;
               continue;
            }
            
            if (run > 0)
            {
               this.encodeBit(0, this.predictor.get());
               this.encodeBit(0, this.predictor.get());
               this.encodeBit(0, this.predictor.get());
               this.encodeBit(0, this.predictor.get());
               this.encodeBit(0, this.predictor.get());
               this.encodeBit(0, this.predictor.get());
               this.encodeBit(0, this.predictor.get());
               this.encodeBit(0, this.predictor.get());
               this.predictor = this.preds[1];
               
               this.encodeBit((run >> 7) & 1, this.predictor.get());
               this.encodeBit((run >> 6) & 1, this.predictor.get());
               this.encodeBit((run >> 5) & 1, this.predictor.get());
               this.encodeBit((run >> 4) & 1, this.predictor.get());
               this.encodeBit((run >> 3) & 1, this.predictor.get());
               this.encodeBit((run >> 2) & 1, this.predictor.get());
               this.encodeBit((run >> 1) & 1, this.predictor.get());
               this.encodeBit(run & 1, this.predictor.get()); 
               run = 0;
            }
            
            this.predictor = this.preds[0];
            int val = (block[i] & 0xFF);           
            this.encodeBit((val >> 7) & 1, this.predictor.get());
            this.encodeBit((val >> 6) & 1, this.predictor.get());
            this.encodeBit((val >> 5) & 1, this.predictor.get());
            this.encodeBit((val >> 4) & 1, this.predictor.get());
            this.encodeBit((val >> 3) & 1, this.predictor.get());
            this.encodeBit((val >> 2) & 1, this.predictor.get());
            this.encodeBit((val >> 1) & 1, this.predictor.get());
            this.encodeBit(val & 1, this.predictor.get());
         }

         EntropyUtils.writeVarInt(this.bitstream, this.sba.index);
         this.bitstream.writeBits(this.sba.array, 0, 8*this.sba.index);
         startChunk += chunkSize;

         if (startChunk < end)
            this.bitstream.writeBits(this.low | MASK_0_24, 56);
      }

      return count;
   }


   public void encodeBit(int bit, int pred)
   {
      // Calculate interval split
      // Written in a way to maximize accuracy of multiplication/division
      final long split = (((this.high - this.low) >>> 4) * pred) >>> 8;

      // Update fields with new interval bounds
      if (bit == 0)
         this.low += (split + 1);
      else
         this.high = this.low + split;
	
      // Update predictor
      this.predictor.update(bit);

      // Write unchanged first 32 bits to bitstream
      while (((this.low ^ this.high) & MASK_24_56) == 0)
         this.flush();
   }


   private void flush()
   {
      Memory.BigEndian.writeInt32(this.sba.array, this.sba.index, (int) (this.high>>>24));
      this.sba.index += 4;
      this.low <<= 32;
      this.high = (this.high<<32) | MASK_0_32;
   }


   @Override
   public OutputBitStream getBitStream()
   {
      return this.bitstream;
   }


   @Override
   public void dispose()
   {
      if (this.disposed == true)
         return;

      this.disposed = true;
      this.bitstream.writeBits(this.low | MASK_0_24, 56);
   }
}
