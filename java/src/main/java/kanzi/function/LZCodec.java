/*
Copyright 2011-2017 Frederic Langlet
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

package kanzi.function;

import java.util.Map;
import kanzi.ByteFunction;
import kanzi.Memory;
import kanzi.SliceByteArray;


// Simple byte oriented LZ77 implementation.
// It is a modified LZ4 with a bigger window, a bigger hash map, 3+n*8 bit 
// literal lengths and 17 or 24 bit match lengths.
public final class LZCodec implements ByteFunction
{
   private final ByteFunction delegate;
  
   
   public LZCodec()
   {
      this.delegate = new LZXCodec();
   }
  
   
   public LZCodec(Map<String, Object> ctx)
   {
      // Encode the word indexes as varints with a token or with a mask
      final short lzType = (short) ctx.getOrDefault("lz", ByteFunctionFactory.LZ_TYPE);
      this.delegate = (lzType == ByteFunctionFactory.LZ_TYPE) ? new LZXCodec(ctx) : new LZPCodec(ctx);
   }
   
   
   @Override
   public int getMaxEncodedLength(int srcLength)
   {
      return this.delegate.getMaxEncodedLength(srcLength);
   }


   @Override
   public boolean forward(SliceByteArray src, SliceByteArray dst)
   {
      if (src.length == 0)
         return true;

      if (src.array == dst.array)
         return false;
  
      return this.delegate.forward(src, dst);   
   }
      

   @Override
   public boolean inverse(SliceByteArray src, SliceByteArray dst)
   {
      if (src.length == 0)
         return true;

      if (src.array == dst.array)
         return false;

      return this.delegate.inverse(src, dst);
   }
   

   private static boolean differentInts(byte[] array, int srcIdx, int dstIdx)
   {
      return ((array[srcIdx] != array[dstIdx])     ||
              (array[srcIdx+1] != array[dstIdx+1]) ||
              (array[srcIdx+2] != array[dstIdx+2]) ||
              (array[srcIdx+3] != array[dstIdx+3]));
   }   
   


   static final class LZXCodec implements ByteFunction
   {
      private static final int HASH_SEED      = 0x7FEB352D;
      private static final int HASH_LOG       = 18;
      private static final int HASH_SHIFT     = 32 - HASH_LOG;
      private static final int MAX_DISTANCE1  = (1<<17) - 1;
      private static final int MAX_DISTANCE2  = (1<<24) - 1;
      private static final int MIN_MATCH      = 4;
      private static final int MIN_LENGTH     = 16;

      private int[] hashes;


      public LZXCodec()
      {
         this.hashes = new int[0];
      }


      public LZXCodec(Map<String, Object> ctx)
      {
         this.hashes = new int[0];
      }


      private static int emitLength(byte[] block, int idx, int length)
      {
         while (length >= 0xFF)
         {
            block[idx++] = (byte) 0xFF;
            length -= 0xFF;
         }

         block[idx] = (byte) length;
         return idx+1;
      }


      private static int emitLastLiterals(byte[] src, int srcIdx, byte[] dst, int dstIdx, int litLen)
      {
         if (litLen >= 7)
         {
            dst[dstIdx++] = (byte) (7<<5);
            dstIdx = emitLength(dst, dstIdx, litLen-7);               
         }
         else
         {
            dst[dstIdx++] = (byte) (litLen<<5);
         } 

         System.arraycopy(src, srcIdx, dst, dstIdx, litLen);
         return dstIdx+litLen;
      }


      @Override
      public boolean forward(SliceByteArray input, SliceByteArray output)
      {
         if (input.length == 0)
            return true;

         if (input.array == output.array)
            return false;

         final int count = input.length;

         if (output.length - output.index < this.getMaxEncodedLength(count))
            return false;

         // If too small, skip
         if (count < MIN_LENGTH)
             return false;

         if (this.hashes.length == 0) 
         {
            this.hashes = new int[1<<HASH_LOG];
         } 
         else 
         {
            for (int i=0; i<(1<<HASH_LOG); i++)
               this.hashes[i] = 0;
         }

         final int srcIdx0 = input.index;
         final int dstIdx0 = output.index;
         final byte[] src = input.array;
         final byte[] dst = output.array;
         final int srcEnd = srcIdx0 + count - 8;
         int srcIdx = srcIdx0;
         int anchor = srcIdx0;
         int dstIdx = dstIdx0;
         final int maxDist = (srcEnd < 4*MAX_DISTANCE1) ? MAX_DISTANCE1 : MAX_DISTANCE2;
         dst[dstIdx++] = (maxDist == MAX_DISTANCE1) ? (byte) 0 : (byte) 1;

         while (srcIdx < srcEnd) 
         {
            final int minRef = Math.max(srcIdx-maxDist, srcIdx0);
            final int h = hash(src, srcIdx);
            final int ref = this.hashes[h];
            int bestLen = 0;

            // Find a match
            if ((ref > minRef) && (differentInts(src, ref, srcIdx) == false)) {
               final int maxMatch = srcEnd - srcIdx;
               bestLen = 4;

               while ((bestLen+4 < maxMatch) && (differentInts(src, ref+bestLen, srcIdx+bestLen) == false))
                  bestLen += 4;

               while ((bestLen < maxMatch) && (src[ref+bestLen] == src[srcIdx+bestLen]))
                  bestLen++;
            }

            // No good match ?
            if (bestLen < MIN_MATCH) 
            {
               this.hashes[h] = srcIdx;
               srcIdx++;
               continue;
            }

            // Emit token
            // Token: 3 bits litLen + 1 bit flag + 4 bits mLen
            // flag = if maxDist = (1<<17)-1, then highest bit of distance
            //        else 1 if dist needs 3 bytes (> 0xFFFF) and 0 otherwise
            final int mLen = bestLen - MIN_MATCH;
            final int dist = srcIdx - ref;
            final int token = ((dist>0xFFFF) ? 0x10 : 0) | Math.min(mLen, 0x0F);

            // Literals to process ?
            if (anchor == srcIdx) 
            {
               dst[dstIdx++] = (byte) token;
            }
            else 
            {
               // Process match
               final int litLen = srcIdx - anchor;

               // Emit literal length
               if (litLen >= 7) {
                  dst[dstIdx++] = (byte) ((7<<5)|token);
                  dstIdx = emitLength(dst, dstIdx, litLen-7);
               }
               else 
               {
                  dst[dstIdx++] = (byte) ((litLen<<5)|token);
               }

               // Emit literals
               emitLiterals(src, anchor, dst, dstIdx, litLen);
               dstIdx += litLen;
            }

            // Emit match length
            if (mLen >= 0x0F)
               dstIdx = emitLength(dst, dstIdx, mLen-0x0F);

            // Emit distance
            if ((maxDist == MAX_DISTANCE2) && (dist > 0xFFFF))
               dst[dstIdx++] = (byte) (dist>>>16);

            dst[dstIdx++] = (byte) (dist>>>8);
            dst[dstIdx++] = (byte) (dist);

            // Fill _hashes and update positions
            anchor = srcIdx + bestLen;
            this.hashes[h] = srcIdx;
            srcIdx++;

            while (srcIdx < anchor) 
            {
               this.hashes[hash(src, srcIdx)] = srcIdx;
               srcIdx++;
            }
         }

         // Emit last literals
         dstIdx = emitLastLiterals(src, anchor, dst, dstIdx, srcEnd+8-anchor);
         input.index = srcEnd + 8;
         output.index = dstIdx;
         return true;
      }


      @Override
      public boolean inverse(SliceByteArray input, SliceByteArray output)
      {
         if (input.length == 0)
            return true;

         if (input.array == output.array)
            return false;

         final int count = input.length;     
         final int srcIdx0 = input.index;
         final int dstIdx0 = output.index;
         final byte[] src = input.array;
         final byte[] dst = output.array;
         final int srcEnd = srcIdx0 + count - 8;
         final int dstEnd = dst.length - 8;
         final int maxDist = (src[srcIdx0] == 1) ? MAX_DISTANCE2 : MAX_DISTANCE1;
         int dstIdx = dstIdx0;
         int srcIdx = srcIdx0 + 1;


         while (true) {
            final int token = src[srcIdx++] & 0xFF;

            if (token >= 32) {
               // Get literal length
               int litLen = token >> 5;

               if (litLen == 7) {
                   while ((srcIdx < srcEnd) && (src[srcIdx] == -1)) {
                       srcIdx++;
                       litLen += 0xFF;
                   }

                   if (srcIdx >= srcEnd + 8) {
                       input.index = srcIdx;
                       output.index = dstIdx;
                       return false;
                   }

                   litLen += (src[srcIdx++] & 0xFF);
               }

               // Copy literals and exit ?
               if ((dstIdx + litLen > dstEnd) || (srcIdx + litLen > srcEnd)) {
                   System.arraycopy(src, srcIdx, dst, dstIdx, litLen);
                   srcIdx += litLen;
                   dstIdx += litLen;
                   break;
               }

               // Emit literals
               emitLiterals(src, srcIdx, dst, dstIdx, litLen);
               srcIdx += litLen;
               dstIdx += litLen;
            }

            // Get match length
            int mLen = token & 0x0F;

            if (mLen == 15) 
            {
               while ((srcIdx < srcEnd) && (src[srcIdx] == -1)) 
               {
                   srcIdx++;
                   mLen += 0xFF;
               }

               if (srcIdx < srcEnd)
                   mLen += (src[srcIdx++] & 0xFF);
            }

            mLen += MIN_MATCH;
            final int mEnd = dstIdx + mLen;

            // Sanity check
            if (mEnd > dstEnd + 8) 
            {
               input.index = srcIdx;
               output.index = dstIdx;
               return false;
            }

            // Get distance
            int dist = ((src[srcIdx]&0xFF) << 8) | (src[srcIdx+1]&0xFF);
            srcIdx += 2;

            if ((token&0x10) != 0) 
            {
               dist = (maxDist==MAX_DISTANCE1) ? dist+65536 : (dist<<8) | (src[srcIdx++]&0xFF);
            }

            // Sanity check
            if ((dstIdx < dist) || (dist > maxDist)) 
            {
               input.index = srcIdx;
               output.index = dstIdx;
               return false;
            }

            // Copy match
            if (dist > 8) 
            {
               int ref = dstIdx - dist;

               do 
               {
                   // No overlap
                   System.arraycopy(dst, ref, dst, dstIdx, 8);
                   ref += 8;
                   dstIdx += 8;
               } while (dstIdx < mEnd);
            }
            else 
            {
               final int ref = dstIdx - dist;

               for (int i=0; i<mLen; i++)
                   dst[dstIdx+i] = dst[ref+i];
            }

            dstIdx = mEnd;
         }

         output.index = dstIdx;
         input.index = srcIdx;
         return srcIdx == srcEnd + 8;
      }


      private static int hash(byte[] block, int idx)
      {
         return (Memory.LittleEndian.readInt32(block, idx)*HASH_SEED) >>> HASH_SHIFT;      
      }


      private static void arrayChunkCopy(byte[] src, int srcIdx, byte[] dst, int dstIdx)
      {
         dst[dstIdx]   = src[srcIdx];
         dst[dstIdx+1] = src[srcIdx+1];
         dst[dstIdx+2] = src[srcIdx+2];
         dst[dstIdx+3] = src[srcIdx+3];
         dst[dstIdx+4] = src[srcIdx+4];
         dst[dstIdx+5] = src[srcIdx+5];
         dst[dstIdx+6] = src[srcIdx+6];
         dst[dstIdx+7] = src[srcIdx+7];  
      }


      private static void emitLiterals(byte[] src, int srcIdx, byte[] dst, int dstIdx, int len)
      {
         for (int i=0; i<len; i+=8)
            arrayChunkCopy(src, srcIdx+i, dst, dstIdx+i);
      }


      @Override
      public int getMaxEncodedLength(int srcLen)
      {
         return (srcLen <= 1024) ? srcLen+16 : srcLen+(srcLen/64);
      }
   }
   
   
   
   static final class LZPCodec implements ByteFunction
   {
      private static final int HASH_SEED  = 0x7FEB352D;
      private static final int HASH_LOG   = 16;
      private static final int HASH_SHIFT = 32 - HASH_LOG;
      private static final int MIN_MATCH  = 64;
      private static final int MIN_LENGTH = 128;
      private static final int MATCH_FLAG = 0xFC;

      private int[] hashes;


      public LZPCodec()
      {
         this.hashes = new int[0];
      }


      public LZPCodec(Map<String, Object> ctx)
      {
         this.hashes = new int[0];
      }


      @Override
      public boolean forward(SliceByteArray input, SliceByteArray output)
      {
         if (input.length == 0)
            return true;

         if (input.array == output.array)
            return false;

         final int count = input.length;

         if (output.length - output.index < this.getMaxEncodedLength(count))
            return false;

         // If too small, skip
         if (count < MIN_LENGTH)
             return false;

         if (this.hashes.length == 0) 
         {
            this.hashes = new int[1<<HASH_LOG];
         } 
         else 
         {
            for (int i=0; i<(1<<HASH_LOG); i++)
               this.hashes[i] = 0;
         }

         final int srcIdx0 = input.index;
         final int dstIdx0 = output.index;
         final byte[] src = input.array;
         final byte[] dst = output.array;
         final int srcEnd = srcIdx0 + count - 8;
         final int dstEnd = dstIdx0 + count - 4;
         int srcIdx = srcIdx0;
         int dstIdx = dstIdx0;

         // If too small, skip
         if (count < MIN_LENGTH)
             return false;

         dst[dstIdx]   = src[srcIdx];
         dst[dstIdx+1] = src[srcIdx+1];
         dst[dstIdx+2] = src[srcIdx+2];
         dst[dstIdx+3] = src[srcIdx+3];
         int ctx = Memory.LittleEndian.readInt32(src, srcIdx);
         srcIdx += 4;
         dstIdx += 4;
         int minRef = 4;

         while ((srcIdx < srcEnd) && (dstIdx < dstEnd)) {
            final int h = (HASH_SEED*ctx) >>> HASH_SHIFT;
            final int ref = this.hashes[h];
            this.hashes[h] = srcIdx;
            int bestLen = 0;

            // Find a match
            if ((ref > minRef) && (LZCodec.differentInts(src, ref, srcIdx) == false)) 
            {
               final int maxMatch = srcEnd - srcIdx;
               bestLen = 4;

               while ((bestLen < maxMatch) && (LZCodec.differentInts(src, ref+bestLen, srcIdx+bestLen) == false))
                  bestLen += 4;

               while ((bestLen < maxMatch) && (src[ref+bestLen] == src[srcIdx+bestLen]))
                  bestLen++;
            }

            // No good match ?
            if (bestLen < MIN_MATCH) 
            {
               final int val = src[srcIdx] & 0xFF;
               ctx = (ctx<<8) | val;
               dst[dstIdx++] = src[srcIdx++];

               if (ref != 0) 
               {
                  if (val == MATCH_FLAG)
                     dst[dstIdx++] = (byte) 0xFF;

                  if (minRef < bestLen)
                     minRef = srcIdx + bestLen;
               }

               continue;
            }

            srcIdx += bestLen;
            ctx = Memory.LittleEndian.readInt32(src, srcIdx-4);
            dst[dstIdx++] = (byte) MATCH_FLAG;
            bestLen -= MIN_MATCH;

            // Emit match length
            while (bestLen >= 254)
            {
               bestLen -= 254;
               dst[dstIdx++] = (byte) 0xFE;

               if (dstIdx >= dstEnd)
                  break;
            }

            dst[dstIdx++] = (byte) bestLen;
         }

         while ((srcIdx < srcEnd+8) && (dstIdx < dstEnd)) 
         {
            final int h = (HASH_SEED*ctx) >>> HASH_SHIFT;
            final int ref = this.hashes[h];
            this.hashes[h] = srcIdx;
            final int val = src[srcIdx] & 0xFF;
            ctx = (ctx<<8) | val;
            dst[dstIdx++] = src[srcIdx++];

            if ((ref != 0) && (val == MATCH_FLAG) && (dstIdx < dstEnd))
               dst[dstIdx++] = (byte) 0xFF;
         }

         input.index = srcIdx;
         output.index = dstIdx;
         return (srcIdx == count) && (dstIdx < (count-(count>>6))); 
      }


      @Override
      public boolean inverse(SliceByteArray input, SliceByteArray output)
      {
         if (input.length == 0)
            return true;

         if (input.array == output.array)
            return false;

         final int count = input.length;     
         final byte[] src = input.array;
         final byte[] dst = output.array;
         final int srcEnd = input.index + count;
         int srcIdx = input.index;
         int dstIdx = output.index;
         
         if (this.hashes.length == 0) 
         {
            this.hashes = new int[1<<HASH_LOG];
         } 
         else 
         {
            for (int i=0; i<(1<<HASH_LOG); i++)
               this.hashes[i] = 0;
         }

         dst[dstIdx]   = src[srcIdx];
         dst[dstIdx+1] = src[srcIdx+1];
         dst[dstIdx+2] = src[srcIdx+2];
         dst[dstIdx+3] = src[srcIdx+3];
         int ctx = Memory.LittleEndian.readInt32(dst, dstIdx);
         srcIdx += 4;
         dstIdx += 4;

         while (srcIdx < srcEnd) 
         {
            final int h = (HASH_SEED*ctx) >>> HASH_SHIFT;
            final int ref = this.hashes[h];
            this.hashes[h] = dstIdx;

            if ((ref == 0) || (src[srcIdx] != (byte) MATCH_FLAG)) 
            {
               dst[dstIdx] = src[srcIdx];
               ctx = (ctx<<8) | (dst[dstIdx]&0xFF);
               srcIdx++;
               dstIdx++;
               continue;
            }

            srcIdx++;

            if (src[srcIdx] == (byte) 0xFF) 
            {
               dst[dstIdx] = (byte) MATCH_FLAG;
               ctx = (ctx<<8) | MATCH_FLAG;
               srcIdx++;
               dstIdx++;
               continue;
            }

            int mLen = MIN_MATCH;

            while ((srcIdx < srcEnd) && (src[srcIdx] == (byte) 0xFE)) 
            {
               srcIdx++;
               mLen += 254;
            }

            if (srcIdx >= srcEnd)
               break;

            mLen += (src[srcIdx++]&0xFF);

            for (int i=0; i<mLen; i++)
               dst[dstIdx+i] = dst[ref+i];

            dstIdx += mLen;
            ctx = Memory.LittleEndian.readInt32(dst, dstIdx-4);
        }

        input.index = srcIdx;
        output.index = dstIdx;
        return srcIdx == srcEnd;
      }
          
      
      @Override
      public int getMaxEncodedLength(int srcLen)
      {
         return (srcLen <= 1024) ? srcLen+16 : srcLen+(srcLen/64);
      }   
   }   
}