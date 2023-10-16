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

package kanzi.entropy;


import java.util.Arrays;
import java.util.Map;
import kanzi.EntropyDecoder;
import kanzi.InputBitStream;
import kanzi.Memory;
import kanzi.SliceByteArray;


// Derived from fpaq0r by Matt Mahoney & Alexander Ratushnyak.
// See http://mattmahoney.net/dc/#fpaq0.
// Simple (and fast) adaptive entropy bit coder
public class FPAQDecoder implements EntropyDecoder
{
   private static final long TOP        = 0x00FFFFFFFFFFFFFFL;
   private static final long MASK_24_56 = 0x00FFFFFFFF000000L;
   private static final long MASK_0_56  = 0x00FFFFFFFFFFFFFFL;
   private static final long MASK_0_32  = 0x00000000FFFFFFFFL;
   private static final int DEFAULT_CHUNK_SIZE = 4*1024*1024;
   private static final int MAX_BLOCK_SIZE = 1 << 30;
   private static final int PSCALE = 65536;

   private long low;
   private long high;
   private long current;
   private final InputBitStream bitstream;
   private SliceByteArray sba;
   private final int[][] probs; // probability of bit=1
   private int[] p; // pointer to current prob
   private int ctx; // previous bits
   private final boolean isBsVersion3;
   

   public FPAQDecoder(InputBitStream bitstream, Map<String, Object> ctx)
   {
      if (bitstream == null)
         throw new NullPointerException("FPAQ codec: Invalid null bitstream parameter");

      // Defer stream reading. We are creating the object, we should not do any I/O
      this.low = 0L;
      this.high = TOP;
      this.bitstream = bitstream;
      this.sba = new SliceByteArray(new byte[0], 0);
      this.ctx = 1;
      this.probs = new int[4][256];
      this.p = this.probs[0];

      for (int i=0; i<4; i++)
         Arrays.fill(this.probs[i], PSCALE>>1);
      
      int bsVersion = 4;

      if (ctx != null)
         bsVersion = (Integer) ctx.getOrDefault("bsVersion", 4);

      this.isBsVersion3 = bsVersion < 4;      
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

      // Split block into chunks, read bit array from bitstream and decode chunk
      while (startChunk < end)
      {
         final int chunkSize = Math.min(DEFAULT_CHUNK_SIZE, end-startChunk);
         final int szBytes = EntropyUtils.readVarInt(this.bitstream);
         this.current = this.bitstream.readBits(56);

         if (szBytes == 0)
            break;

         if (this.sba.array.length < szBytes)
            this.sba.array = new byte[szBytes+(szBytes>>3)];

         this.bitstream.readBits(this.sba.array, 0, 8*szBytes);
         this.sba.index = 0;
         final int endChunk = startChunk + chunkSize;
         this.p = this.probs[0];

         if (this.isBsVersion3 == true)
         {
            for (int i=startChunk; i<endChunk; i++)
            {
               this.ctx = 1;
               this.decodeBitV1(this.p[this.ctx]>>>4);
               this.decodeBitV1(this.p[this.ctx]>>>4);
               this.decodeBitV1(this.p[this.ctx]>>>4);
               this.decodeBitV1(this.p[this.ctx]>>>4);
               this.decodeBitV1(this.p[this.ctx]>>>4);
               this.decodeBitV1(this.p[this.ctx]>>>4);
               this.decodeBitV1(this.p[this.ctx]>>>4);
               this.decodeBitV1(this.p[this.ctx]>>>4);
               block[i] = (byte) this.ctx;
               this.p = this.probs[(this.ctx&0xFF)>>>6];
            }
         }
         else 
         {
            for (int i=startChunk; i<endChunk; i++)
            {
               this.ctx = 1;
               this.decodeBitV2(this.p[this.ctx]);
               this.decodeBitV2(this.p[this.ctx]);
               this.decodeBitV2(this.p[this.ctx]);
               this.decodeBitV2(this.p[this.ctx]);
               this.decodeBitV2(this.p[this.ctx]);
               this.decodeBitV2(this.p[this.ctx]);
               this.decodeBitV2(this.p[this.ctx]);
               this.decodeBitV2(this.p[this.ctx]);
               block[i] = (byte) this.ctx;
               this.p = this.probs[(this.ctx&0xFF)>>>6];
            }                 
        }

         startChunk = endChunk;
      }

      return count;
   }


   private int decodeBitV1(int pred)
   {
      // Calculate interval split
      // Written in a way to maximize accuracy of multiplication/division
      final long split = ((((this.high-this.low) >>> 4) * pred) >>> 8) + this.low;
      int bit;

      // Update probabilities
      if (split >= this.current)
      {
         bit = 1;
         this.high = split;
         this.p[this.ctx] -= ((this.p[this.ctx]-PSCALE+64) >> 6);
         this.ctx = (this.ctx<<1) + 1;
      }
      else
      {
         bit = 0;
         this.low = -~split;
         this.p[this.ctx] -= (this.p[this.ctx] >> 6);
         this.ctx = this.ctx << 1;
      }

      // Read 32 bits from bitstream
      while (((this.low ^ this.high) & MASK_24_56) == 0)
         this.read();

      return bit;
   }

   
   private int decodeBitV2(int pred)
   {
      // Calculate interval split
      // Written in a way to maximize accuracy of multiplication/division
      final long split = ((((this.high-this.low) >>> 8) * pred) >>> 8) + this.low;
      int bit;

      // Update probabilities
      if (split >= this.current)
      {
         bit = 1;
         this.high = split;
         this.p[this.ctx] -= ((this.p[this.ctx]-PSCALE+64) >> 6);
         this.ctx = (this.ctx<<1) + 1;
      }
      else
      {
         bit = 0;
         this.low = -~split;
         this.p[this.ctx] -= (this.p[this.ctx] >> 6);
         this.ctx = this.ctx << 1;
      }

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
