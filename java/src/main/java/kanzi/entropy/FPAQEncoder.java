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


import java.util.Arrays;
import kanzi.EntropyEncoder;
import kanzi.Memory;
import kanzi.OutputBitStream;
import kanzi.SliceByteArray;


// Derived from fpaq0r by Matt Mahoney & Alexander Ratushnyak.
// See http://mattmahoney.net/dc/#fpaq0.
// Simple (and fast) adaptive entropy bit coder
public class FPAQEncoder implements EntropyEncoder
{
   private static final long TOP        = 0x00FFFFFFFFFFFFFFL;
   private static final long MASK_24_56 = 0x00FFFFFFFF000000L;
   private static final long MASK_0_24  = 0x0000000000FFFFFFL;
   private static final long MASK_0_32  = 0x00000000FFFFFFFFL;
   private static final int PSCALE = 65536;

   private long low;
   private long high;
   private final OutputBitStream bitstream;
   private boolean disposed;
   private SliceByteArray sba;
   private final int[][] probs; // probability of bit=1
   private int[] p; // pointer to current prob


   public FPAQEncoder(OutputBitStream bitstream)
   {
      if (bitstream == null)
         throw new NullPointerException("FPAQ codec: Invalid null bitstream parameter");

      this.low = 0L;
      this.high = TOP;
      this.bitstream = bitstream;
      this.sba = new SliceByteArray(new byte[0], 0);
      this.probs = new int[4][256];
      this.p = this.probs[0];

      for (int i=0; i<4; i++)
         Arrays.fill(this.probs[i], PSCALE>>1);
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
         this.p = this.probs[0];

         for (int i=startChunk; i<endChunk; i++)
         {
            final byte val = block[i];
            final int bits = (val&0xFF) + 256;
            this.encodeBit(val&0x80, 1);
            this.encodeBit(val&0x40, bits>>7);
            this.encodeBit(val&0x20, bits>>6);
            this.encodeBit(val&0x10, bits>>5);
            this.encodeBit(val&0x08, bits>>4);
            this.encodeBit(val&0x04, bits>>3);
            this.encodeBit(val&0x02, bits>>2);
            this.encodeBit(val&0x01, bits>>1);
            this.p = this.probs[(val&0xFF)>>>6];
         }

         EntropyUtils.writeVarInt(this.bitstream, this.sba.index);
         this.bitstream.writeBits(this.sba.array, 0, 8*this.sba.index);
         startChunk += chunkSize;

         if (startChunk < end)
            this.bitstream.writeBits(this.low | MASK_0_24, 56);
      }

      return count;
   }


   private void encodeBit(int bit, int pIdx)
   {
      // Calculate interval split
      // Written in a way to maximize accuracy of multiplication/division
      final long split = (((this.high-this.low) >>> 4) * (this.p[pIdx]>>>4)) >>> 8;

      // Update probabilities
      if (bit == 0)
      {
         this.low += (split + 1);
         this.p[pIdx] -= (this.p[pIdx] >> 6);
      }
      else
      {
         this.high = this.low + split;
         this.p[pIdx] -= ((this.p[pIdx]-PSCALE+64) >> 6);
      }

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
