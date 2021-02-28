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

package kanzi.transform;

import java.util.Map;
import kanzi.ByteTransform;
import kanzi.Memory;
import kanzi.SliceByteArray;


// Simple byte oriented LZ77 implementation.
// It is a based on a heavily modified LZ4 with a bigger window, a bigger
// hash map, 3+n*8 bit literal lengths, repetition distance and 17 or 24 bit
// match lengths.
public final class LZCodec implements ByteTransform
{
   private final ByteTransform delegate;


   public LZCodec()
   {
      this.delegate = new LZXCodec();
   }


   public LZCodec(Map<String, Object> ctx)
   {
      // Encode the word indexes as varints with a token or with a mask
      final short lzType = (short) ctx.getOrDefault("lz", TransformFactory.LZ_TYPE);
      this.delegate = (lzType == TransformFactory.LZP_TYPE) ? new LZPCodec(ctx) : new LZXCodec(ctx);
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



   static final class LZXCodec implements ByteTransform
   {
      private static final int HASH_SEED          = 0x1E35A7BD;
      private static final int HASH_LOG1          = 16;
      private static final int HASH_SHIFT1        = 40 - HASH_LOG1;
      private static final int HASH_MASK1         = (1<<HASH_LOG1) - 1;
      private static final int HASH_LOG2          = 21;
      private static final int HASH_SHIFT2        = 48 - HASH_LOG2;
      private static final int HASH_MASK2         = (1<<HASH_LOG2) - 1;
      private static final int MAX_DISTANCE1      = (1<<17) - 2;
      private static final int MAX_DISTANCE2      = (1<<24) - 2;
      private static final int MIN_MATCH          = 5;
      private static final int MAX_MATCH          = 65535 + 254 + 15 + MIN_MATCH;
      private static final int MIN_BLOCK_LENGTH   = 24;
      private static final int MIN_MATCH_MIN_DIST = 1 << 16;

      private int[] hashes;
      private byte[] mBuf;
      private byte[] tkBuf;
      private final boolean extra;


      public LZXCodec()
      {
         this.hashes = new int[0];
         this.mBuf = new byte[0];
         this.tkBuf = new byte[0];
         this.extra = false;
      }


      public LZXCodec(Map<String, Object> ctx)
      {
         this.hashes = new int[0];
         this.mBuf = new byte[0];
         this.tkBuf = new byte[0];
         short lzType = (short) ctx.getOrDefault("lz", TransformFactory.LZ_TYPE);
         this.extra = lzType == TransformFactory.LZX_TYPE;
      }


      private static int emitLength(byte[] block, int idx, int length)
      {
         if (length < 254)
         {
            block[idx] = (byte) length;
            return idx+1;
         }

         if (length < 65536 + 254)
         {
            length -= 254;
            block[idx] = (byte) 254;
            block[idx+1] = (byte) (length>>8);
            block[idx+2] = (byte) (length);
            return idx+3;
         }

         length -= 255;
         block[idx] = (byte) 255;
         block[idx+1] = (byte) (length>>16);
         block[idx+2] = (byte) (length>>8);
         block[idx+3] = (byte) (length);
         return idx+4;
      }


      private static int readLength(SliceByteArray sba)
      {
         int res = sba.array[sba.index++] & 0xFF;

         if (res < 254)
            return res;

         if (res == 254)
         {
            res += ((sba.array[sba.index++] & 0xFF) << 8);
            res +=  (sba.array[sba.index++] & 0xFF);
            return res;
         }

         res += ((sba.array[sba.index] & 0xFF) << 16);
         res += ((sba.array[sba.index+1] & 0xFF) << 8);
         res +=  (sba.array[sba.index+2] & 0xFF);
         sba.index += 3;
         return res;
      }


      private static int findMatch(byte[] src, int srcIdx, int ref, int maxMatch)
      {
         int bestLen = 0;

         if (differentInts(src, ref, srcIdx) == false) {
            bestLen = 4;

            while ((bestLen+4 < maxMatch) && (differentInts(src, ref+bestLen, srcIdx+bestLen) == false))
               bestLen += 4;

            while ((bestLen < maxMatch) && (src[ref+bestLen] == src[srcIdx+bestLen]))
               bestLen++;
          }

         return bestLen;
      }


      @Override
      public boolean forward(SliceByteArray input, SliceByteArray output)
      {
         if (input.length == 0)
            return true;

         final int count = input.length;

         if (output.length - output.index < this.getMaxEncodedLength(count))
            return false;

         // If too small, skip
         if (count < MIN_BLOCK_LENGTH)
             return false;

         if (this.hashes.length == 0)
         {
            this.hashes = (this.extra == true) ? new int[1<<HASH_LOG2] : new int[1<<HASH_LOG1];
         }
         else
         {
            for (int i=0; i<this.hashes.length; i++)
               this.hashes[i] = 0;
         }

         if (this.mBuf.length < Math.max(count/5, 256))
            this.mBuf = new byte[Math.max(count/5, 256)];

         if (this.tkBuf.length < Math.max(count/5, 256))
            this.tkBuf = new byte[Math.max(count/5, 256)];

         final int srcIdx0 = input.index;
         final int dstIdx0 = output.index;
         final byte[] src = input.array;
         final byte[] dst = output.array;
         final int srcEnd = srcIdx0 + count - 16 - 1;
         final int maxDist = (srcEnd < 4*MAX_DISTANCE1) ? MAX_DISTANCE1 : MAX_DISTANCE2;
         dst[dstIdx0+8] = (maxDist == MAX_DISTANCE1) ? (byte) 0 : (byte) 1;
         int srcIdx = srcIdx0;
         int anchor = srcIdx0;
         int dstIdx = dstIdx0 + 9;
         int mIdx = 0;
         int tkIdx = 0;
         int repd = 0;

         while (srcIdx < srcEnd)
         {
            final int minRef = Math.max(srcIdx-maxDist, srcIdx0);
            int h = hash(src, srcIdx);
            int ref = this.hashes[h];
            this.hashes[h] = srcIdx;
            int bestLen = 0;

            // Find a match
            if (ref > minRef)
            {
               final int maxMatch = Math.min(srcEnd-srcIdx, MAX_MATCH);
               bestLen = findMatch(src, srcIdx, ref, maxMatch);
            }

            // No good match ?
            if ((bestLen < MIN_MATCH) || ((bestLen == MIN_MATCH) && (srcIdx-ref >= MIN_MATCH_MIN_DIST)))
            {
               srcIdx++;
               continue;
            }

            // Check if better match at next position
            final int h2 = hash(src, srcIdx+1);
            final int ref2 = this.hashes[h2];
            this.hashes[h2] = srcIdx + 1;
            int bestLen2 = 0;

            if (ref2 > minRef + 1)
            {
               final int maxMatch = Math.min(srcEnd-srcIdx-1, MAX_MATCH);
               bestLen2 = findMatch(src, srcIdx+1, ref2, maxMatch);
            }

            // Select best match
            if ((bestLen2 > bestLen) || ((bestLen2 == bestLen) && ((srcIdx-ref2) < (srcIdx-ref)))) 
            {
               ref = ref2;
               bestLen = bestLen2;
               srcIdx++;
            }

            // Emit token
            // Token: 3 bits litLen + 1 bit flag + 4 bits mLen (LLLFMMMM)
            // flag = if maxDist = MAX_DISTANCE1, then highest bit of distance
            //        else 1 if dist needs 3 bytes (> 0xFFFF) and 0 otherwise
            final int mLen = bestLen - MIN_MATCH;
            final int d = srcIdx - ref;
            final int dist = (d == repd) ? 0 : d + 1;
            repd = d;
            final int token = ((dist>0xFFFF) ? 0x10 : 0) | Math.min(mLen, 0x0F);

            // Literals to process ?
            if (anchor == srcIdx)
            {
               this.tkBuf[tkIdx++] = (byte) token;
            }
            else
            {
               // Process literals
               final int litLen = srcIdx - anchor;

               // Emit literal length
               if (litLen >= 7) {
                  if (litLen >= (1 << 24))
                     return false;

                  this.tkBuf[tkIdx++] = (byte) ((7<<5)|token);
                  dstIdx = emitLength(dst, dstIdx, litLen-7);
               }
               else
               {
                  this.tkBuf[tkIdx++] = (byte) ((litLen<<5)|token);
               }

               // Emit literals
               emitLiterals(src, anchor, dst, dstIdx, litLen);
               dstIdx += litLen;
            }

            // Emit match length
            if (mLen >= 15)
               mIdx = emitLength(this.mBuf, mIdx, mLen-15);

            // Emit distance
            if ((maxDist == MAX_DISTANCE2) && (dist > 0xFFFF))
               this.mBuf[mIdx++] = (byte) (dist>>16);

            this.mBuf[mIdx++] = (byte) (dist>>8);
            this.mBuf[mIdx++] = (byte) (dist);

            if (mIdx >= this.mBuf.length-4)
            {
               // Expand match buffer
               byte[] buf = new byte[this.mBuf.length<<1];
               System.arraycopy(this.mBuf, 0, buf, 0, this.mBuf.length);
               this.mBuf = buf;
            }

            // Fill this.hashes and update positions
            anchor = srcIdx + bestLen;
            srcIdx++;

            while (srcIdx < anchor)
            {
               this.hashes[hash(src, srcIdx)] = srcIdx;
               srcIdx++;
            }
         }

         // Emit last literals
         final int litLen = count - anchor;

         if (dstIdx+litLen+tkIdx+mIdx >= output.index+count)
            return false;

         if (litLen >= 7)
         {
            this.tkBuf[tkIdx++] = (byte) (7<<5);
            dstIdx = emitLength(dst, dstIdx, litLen-7);
         }
         else
         {
            this.tkBuf[tkIdx++] = (byte) (litLen<<5);
         }

         System.arraycopy(src, anchor, dst, dstIdx, litLen);
         dstIdx += litLen;

         // Emit buffers: literals + tokens + matches
         Memory.LittleEndian.writeInt32(dst, dstIdx0, dstIdx);
         Memory.LittleEndian.writeInt32(dst, dstIdx0+4, tkIdx);
         System.arraycopy(this.tkBuf, 0, dst, dstIdx, tkIdx);
         dstIdx += tkIdx;
         System.arraycopy(this.mBuf, 0, dst, dstIdx, mIdx);
         dstIdx += mIdx;
         input.index = count;
         output.index = dstIdx;
         return true;
      }


      @Override
      public boolean inverse(SliceByteArray input, SliceByteArray output)
      {
         if (input.length == 0)
            return true;

         final int count = input.length;
         final int srcIdx0 = input.index;
         final int dstIdx0 = output.index;
         final byte[] src = input.array;
         final byte[] dst = output.array;
         final int dstEnd = dst.length - 16;
         int tkIdx = Memory.LittleEndian.readInt32(src, srcIdx0);
         int mIdx = tkIdx + Memory.LittleEndian.readInt32(src, srcIdx0+4);

         if ((tkIdx < srcIdx0) || (mIdx < srcIdx0) || (tkIdx > srcIdx0+count) || (mIdx > srcIdx0+count))
            return false;

         final int srcEnd = srcIdx0 + tkIdx - 9;
         final int maxDist = (src[srcIdx0+8] == 1) ? MAX_DISTANCE2 : MAX_DISTANCE1;
         int srcIdx = srcIdx0 + 9;
         int dstIdx = dstIdx0;
         int repd = 0;
         SliceByteArray sba1 = new SliceByteArray(src, srcIdx);
         SliceByteArray sba2 = new SliceByteArray(src, mIdx);

         while (true)
         {
            final int token = src[tkIdx++] & 0xFF;

            if (token >= 32)
            {
               // Get literal length
               int litLen = token >> 5;

               if (litLen == 7)
               {
                  sba1.index = srcIdx;
                  litLen += readLength(sba1);
                  srcIdx = sba1.index;
               }

               // Emit literals
               if (dstIdx+litLen >= dstEnd)
               {
                  System.arraycopy(src, srcIdx, dst, dstIdx, litLen);
               }
               else
               {
                  emitLiterals(src, srcIdx, dst, dstIdx, litLen);
               }

               srcIdx += litLen;
               dstIdx += litLen;

               if (srcIdx >= srcEnd)
                  break;
            }

            // Get match length
            int mLen = token & 0x0F;

            if (mLen == 15)
            {
               sba2.index = mIdx;
               mLen += readLength(sba2);
               mIdx = sba2.index;
            }

            mLen += MIN_MATCH;
            final int mEnd = dstIdx + mLen;

            // Get distance
            int d = ((src[mIdx]&0xFF) << 8) | (src[mIdx+1]&0xFF);
            mIdx += 2;

            if ((token&0x10) != 0)
            {
               d = (maxDist==MAX_DISTANCE1) ? d+65536 : (d<<8) | (src[mIdx++]&0xFF);
            }

            final int dist = (d == 0) ? repd : d - 1;
            repd = dist;

            // Sanity check
            if ((dstIdx-dist < dstIdx0) || (dist > maxDist) || (mEnd > dstEnd+16))
            {
               input.index = srcIdx;
               output.index = dstIdx;
               return false;
            }

            // Copy match
            if (dist >= 16)
            {
               int ref = dstIdx - dist;

               do
               {
                  // No overlap
                  System.arraycopy(dst, ref, dst, dstIdx, 16);
                  ref += 16;
                  dstIdx += 16;
               }
               while (dstIdx < mEnd);
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
         input.index = mIdx;
         return srcIdx == srcEnd + 9;
      }


      private int hash(byte[] block, int idx)
      {
         if (this.extra == true)
            return (int) ((Memory.LittleEndian.readLong64(block, idx)*HASH_SEED) >> HASH_SHIFT2) & HASH_MASK2;

         return (int) ((Memory.LittleEndian.readLong64(block, idx)*HASH_SEED) >> HASH_SHIFT1) & HASH_MASK1;
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



   static final class LZPCodec implements ByteTransform
   {
      private static final int HASH_SEED        = 0x7FEB352D;
      private static final int HASH_LOG         = 16;
      private static final int HASH_SHIFT       = 32 - HASH_LOG;
      private static final int MIN_MATCH        = 96;
      private static final int MIN_BLOCK_LENGTH = 128;
      private static final int MATCH_FLAG       = 0xFC;

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

         final int count = input.length;

         if (output.length - output.index < this.getMaxEncodedLength(count))
            return false;

         // If too small, skip
         if (count < MIN_BLOCK_LENGTH)
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