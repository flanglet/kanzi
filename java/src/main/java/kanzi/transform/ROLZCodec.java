/*
Copyright 2011-2022 Frederic Langlet
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import kanzi.ByteTransform;
import kanzi.Global;
import kanzi.InputBitStream;
import kanzi.Memory;
import kanzi.OutputBitStream;
import kanzi.SliceByteArray;
import kanzi.bitstream.DefaultInputBitStream;
import kanzi.bitstream.DefaultOutputBitStream;
import kanzi.entropy.ANSRangeDecoder;
import kanzi.entropy.ANSRangeEncoder;


// Implementation of a Reduced Offset Lempel Ziv transform
// More information about ROLZ at http://ezcodesample.com/rolz/rolz_article.html

public class ROLZCodec implements ByteTransform
{
   private static final int HASH_SIZE = 1 << 16;
   private static final int CHUNK_SIZE = 1 << 26;
   private static final int MATCH_FLAG = 0;
   private static final int LITERAL_FLAG = 1;
   private static final int LITERAL_CTX = 0;
   private static final int MATCH_CTX = 1;
   private static final int HASH = 200002979;
   private static final int HASH_MASK = ~(CHUNK_SIZE - 1);
   private static final int MAX_BLOCK_SIZE = 1 << 30; // 1 GB
   private static final int MIN_BLOCK_SIZE = 64;


   private final ByteTransform delegate;


   public ROLZCodec()
   {
      this.delegate = new ROLZCodec1(); // defaults to ANS
   }


   public ROLZCodec(boolean extra)
   {
      this.delegate = (extra == true) ? new ROLZCodec2() : new ROLZCodec1();
   }


   public ROLZCodec(Map<String, Object> ctx)
   {
      String transform = "NONE";
      
      if (ctx != null)
         transform = (String) ctx.getOrDefault("transform", "NONE");
      
      this.delegate = (transform.contains("ROLZX")) ? new ROLZCodec2(ctx) 
         : new ROLZCodec1(ctx);
   }
   
   
   private static int getKey1(final byte[] buf, final int idx)
   {
      return Memory.LittleEndian.readInt16(buf, idx) & 0xFFFF;
   }
   
   
   private static int getKey2(final byte[] buf, final int idx)
   {
      return (int) ((Memory.LittleEndian.readLong64(buf, idx) * HASH) >> 40) & 0xFFFF;
   }


   private static int hash(final byte[] buf, final int idx)
   {
      return ((Memory.LittleEndian.readInt32(buf, idx)<<8) * HASH) & HASH_MASK;
   }


   private static int emitCopy(byte[] dst, int dstIdx, int ref, int matchLen)
   {
      while (matchLen >= 4)
      {
         dst[dstIdx]   = dst[ref];
         dst[dstIdx+1] = dst[ref+1];
         dst[dstIdx+2] = dst[ref+2];
         dst[dstIdx+3] = dst[ref+3];
         dstIdx += 4;
         ref += 4;
         matchLen -= 4;
      }

      while (matchLen != 0)
      {
         dst[dstIdx++] = dst[ref++];
         matchLen--;
      }

      return dstIdx;
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

      if (src.length < MIN_BLOCK_SIZE)
         return false;

      if (src.array == dst.array)
         return false;

      if (src.length > MAX_BLOCK_SIZE)
         throw new IllegalArgumentException("The max ROLZ codec block size is "+MAX_BLOCK_SIZE+", got "+src.length);

      return this.delegate.forward(src, dst);
   }


   @Override
   public boolean inverse(SliceByteArray src, SliceByteArray dst)
   {
      if (src.length == 0)
         return true;

      if (src.array == dst.array)
         return false;

      if (src.length > MAX_BLOCK_SIZE)
         throw new IllegalArgumentException("The max ROLZ codec block size is "+MAX_BLOCK_SIZE+", got "+src.length);

      return this.delegate.inverse(src, dst);
   }


   // Use ANS to encode/decode literals and matches
   static class ROLZCodec1 implements ByteTransform
   {
      private static final int MIN_MATCH3 = 3;
      private static final int MIN_MATCH4 = 4;
      private static final int MIN_MATCH7 = 7;
      private static final int MAX_MATCH = MIN_MATCH3 + 65535;
      private static final int LOG_POS_CHECKS = 4;

      private final int logPosChecks;
      private final int maskChecks;
      private final int posChecks;
      private final int[] matches;
      private final int[] counters;
      private final Map<String, Object> ctx;
      private int minMatch;


      public ROLZCodec1()
      {
         this(LOG_POS_CHECKS, null);
      }


      public ROLZCodec1(int logPosChecks)
      {
         this(logPosChecks, null);
      }


      public ROLZCodec1(Map<String, Object> ctx)
      {
         this(LOG_POS_CHECKS, ctx);
      }

            
      protected ROLZCodec1(int logPosChecks, Map<String, Object> ctx)
      {
         if ((logPosChecks < 2) || (logPosChecks > 8))
            throw new IllegalArgumentException("ROLZ codec: Invalid logPosChecks parameter " +
               "(must be in [2..8])");

         this.logPosChecks = logPosChecks;
         this.posChecks = 1 << logPosChecks;
         this.maskChecks = this.posChecks - 1;
         this.counters = new int[1<<16];
         this.matches = new int[HASH_SIZE<<this.logPosChecks];
         this.ctx = ctx;
      }


      // return position index (LOG_POS_CHECKS bits) + length (16 bits) or -1
      private int findMatch(final SliceByteArray sba, final int pos, final int key)
      {
         final byte[] buf = sba.array;         
         final int base = key << this.logPosChecks;
         final int hash32 = hash(buf, pos);
         final int counter = this.counters[key];
         int bestLen = 0;
         int bestIdx = -1;
         final int maxMatch = Math.min(MAX_MATCH, sba.length-pos);

         // Check all recorded positions
         for (int i=counter; i>counter-this.posChecks; i--)
         {
            int ref = this.matches[base+(i&this.maskChecks)];

            // Hash check may save a memory access ...
            if ((ref & HASH_MASK) != hash32)
               continue;

            ref = (ref & ~HASH_MASK) + sba.index;

            if (buf[ref+bestLen] != buf[pos+bestLen])
               continue;

            int n = 0;

            while (n+4 < maxMatch) 
            {
               final int diff = Memory.LittleEndian.readInt32(buf, ref+n) ^ Memory.LittleEndian.readInt32(buf, pos+n);
               
               if (diff != 0)
               {
                  n += (Integer.numberOfTrailingZeros(diff) >> 3); 
                  break;
               }
               
               n += 4;
            }
               
            if (n > bestLen)
            {
               bestIdx = counter - i;
               bestLen = n;

               if (bestLen == maxMatch)
                  break;
            }
         }

         // Register current position        
         this.counters[key] = (this.counters[key]+1) & this.maskChecks;
         this.matches[base+this.counters[key]] = hash32 | (pos-sba.index);
         return (bestLen < this.minMatch) ? -1 : (bestIdx<<16) | (bestLen-this.minMatch);
      }


      @Override
      public boolean forward(SliceByteArray input, SliceByteArray output)
      {
         final int count = input.length;

         if (output.length - output.index < this.getMaxEncodedLength(count))
            return false;

         final byte[] src = input.array;
         final byte[] dst = output.array;
         final int srcEnd = input.index + count - 4;
         Memory.BigEndian.writeInt32(dst, output.index, count);
         int sizeChunk = Math.min(count, CHUNK_SIZE);
         int startChunk = input.index;
         final SliceByteArray litBuf = new SliceByteArray(new byte[this.getMaxEncodedLength(sizeChunk)], 0);
         final SliceByteArray lenBuf = new SliceByteArray(new byte[sizeChunk/5], 0);
         final SliceByteArray mIdxBuf = new SliceByteArray(new byte[sizeChunk/4], 0);
         final SliceByteArray tkBuf = new SliceByteArray(new byte[sizeChunk/4], 0);
         ByteArrayOutputStream baos = new ByteArrayOutputStream(this.getMaxEncodedLength(sizeChunk));

         for (int i=0; i<this.counters.length; i++)
            this.counters[i] = 0;

         final int litOrder = (count < 1<<17) ? 0 : 1;
         byte flags = (byte) litOrder;
         this.minMatch = MIN_MATCH3;
         
         if (this.ctx != null)
         {
            Global.DataType dt = (Global.DataType) this.ctx.getOrDefault("dataType",
               Global.DataType.UNDEFINED);

            if (dt == Global.DataType.MULTIMEDIA)
            {
               this.minMatch = MIN_MATCH4;
               flags |= 2;
            }
            else if (dt == Global.DataType.DNA)
            {
               this.minMatch = MIN_MATCH7;
               flags |= 4;
            }
         }

         final int mm = this.minMatch;
         dst[output.index+4] = flags;
         int srcIdx = input.index;
         int dstIdx = output.index + 5;
         
         // Main loop
         while (startChunk < srcEnd)
         {
            litBuf.index = 0;
            lenBuf.index = 0;
            mIdxBuf.index = 0;
            tkBuf.index = 0;

            for (int i=0; i<this.matches.length; i++)
               this.matches[i] = 0;

            final int endChunk = Math.min(startChunk+sizeChunk, srcEnd);
            sizeChunk = endChunk - startChunk;
            srcIdx = startChunk;
            final SliceByteArray sba = new SliceByteArray(src, endChunk, startChunk);
            final int n = Math.min(srcEnd-startChunk, 8);

            for (int j=0; j<n; j++)
               litBuf.array[litBuf.index++] = src[srcIdx++];

            int firstLitIdx = srcIdx;

            // Next chunk
            while (srcIdx < endChunk)
            {
               final int match = (mm == MIN_MATCH3) ? findMatch(sba, srcIdx, getKey1(src, srcIdx-2)) :
                   findMatch(sba, srcIdx, getKey2(src, srcIdx-8));

               if (match == -1)
               {
                  srcIdx++;
                  continue;
               }

               // mode LLLLLMMM -> L lit length, M match length
               final int litLen = srcIdx - firstLitIdx;
               final int mode = (litLen < 31) ? (litLen << 3) : 0xF8;
               final int mLen = match & 0xFFFF;

               if (mLen >= 7)
               {
                  tkBuf.array[tkBuf.index++] = (byte) (mode | 0x07);
                  emitLength(lenBuf, mLen - 7);
               }
               else
               {
                  tkBuf.array[tkBuf.index++] = (byte) (mode | mLen);
               }

               // Emit literals
               if (litLen >= 16)
               {
                  if (litLen >= 31)
                     emitLength(lenBuf, litLen - 31);

                  System.arraycopy(src, firstLitIdx, litBuf.array, litBuf.index, litLen);
               }
               else
               {
                  for (int i=0; i<litLen; i++)
                     litBuf.array[litBuf.index+i] = src[firstLitIdx+i];
               }

               litBuf.index += litLen;

               // Emit match index
               mIdxBuf.array[mIdxBuf.index++] = (byte) (match>>>16);
               srcIdx += (mLen + mm);
               firstLitIdx = srcIdx;
            }

            // Emit last chunk literals
            final int litLen = srcIdx - firstLitIdx;
            final int mode = (litLen < 31) ? (litLen << 3) : 0xF8;
            tkBuf.array[tkBuf.index++] = (byte) mode;

            if (litLen >= 31)
               emitLength(lenBuf, litLen - 31);

            for (int i=0; i<litLen; i++)
               litBuf.array[litBuf.index+i] = src[firstLitIdx+i];

            litBuf.index += litLen;

            // Scope to deallocate resources early
            {
               // Encode literal, length and match index buffers
               baos.reset();
               OutputBitStream obs = new DefaultOutputBitStream(baos, 65536);
               obs.writeBits(litBuf.index, 32);
               obs.writeBits(tkBuf.index, 32);
               obs.writeBits(lenBuf.index, 32);
               obs.writeBits(mIdxBuf.index, 32);

               ANSRangeEncoder litEnc = new ANSRangeEncoder(obs, litOrder);
               litEnc.encode(litBuf.array, 0, litBuf.index);
               litEnc.dispose();
               ANSRangeEncoder mEnc = new ANSRangeEncoder(obs, 0);
               mEnc.encode(tkBuf.array, 0, tkBuf.index);
               mEnc.encode(lenBuf.array, 0, lenBuf.index);
               mEnc.encode(mIdxBuf.array, 0, mIdxBuf.index);
               mEnc.dispose();
               obs.close();
            }

            // Copy bitstream array to output
            final byte[] buf = baos.toByteArray();

            if (dstIdx+buf.length > dst.length)
            {
               output.index = dstIdx;
               input.index = srcIdx;
               return false;
            }

            System.arraycopy(buf, 0, dst, dstIdx, buf.length);
            dstIdx += buf.length;
            startChunk = endChunk;
         }

         if (dstIdx+4 > dst.length)
         {
            output.index = dstIdx;
            input.index = srcIdx;
            return false;
         }

         // Emit last literals
         dst[dstIdx++] = src[srcIdx];
         dst[dstIdx++] = src[srcIdx+1];
         dst[dstIdx++] = src[srcIdx+2];
         dst[dstIdx++] = src[srcIdx+3];

         input.index = srcIdx + 4;
         output.index = dstIdx;
         return (srcIdx == srcEnd) && (dstIdx < count);
      }


      private static void emitLength(SliceByteArray lenBuf, int length)
      {
         if (length >= 1<<7)
         {
            if (length >= 1<<14)
            {
               if (length >= 1<<21)
                  lenBuf.array[lenBuf.index++] = (byte) (0x80|(length>>21));

               lenBuf.array[lenBuf.index++] = (byte) (0x80|(length>>14));
            }

            lenBuf.array[lenBuf.index++] = (byte) (0x80|(length>>7));
         }

         lenBuf.array[lenBuf.index++] = (byte) (length&0x7F);
      }


      @Override
      public boolean inverse(SliceByteArray input, SliceByteArray output)
      {
         final int count = input.length;
         final byte[] src = input.array;
         final byte[] dst = output.array;
         final int srcEnd = input.index + count;
         final int szBlock = Memory.BigEndian.readInt32(src, input.index) - 4;
         
         if ((szBlock <= 0) || (szBlock > output.length))
            return false;
         
         final int dstEnd = output.index + szBlock;
         int sizeChunk = Math.min(szBlock, CHUNK_SIZE);
         int startChunk = output.index;
         final SliceByteArray litBuf  = new SliceByteArray(new byte[this.getMaxEncodedLength(sizeChunk)], 0);
         final SliceByteArray lenBuf = new SliceByteArray(new byte[sizeChunk/5], 0);
         final SliceByteArray mIdxBuf = new SliceByteArray(new byte[sizeChunk/4], 0);
         final SliceByteArray tkBuf = new SliceByteArray(new byte[sizeChunk/4], 0);

         for (int i=0; i<this.counters.length; i++)
            this.counters[i] = 0;

         final int litOrder = src[input.index+4] & 0x01;
         this.minMatch = MIN_MATCH3;
         
         final int bsVersion = (this.ctx == null) ? 3 : (Integer) this.ctx.getOrDefault("bsVersion", 3);
         
         if (bsVersion >= 3) 
         {
            if ((src[input.index+4] & 0x06) == 0x02) 
               this.minMatch = MIN_MATCH4;
            else if ((src[input.index+4] & 0x06) == 0x04) 
               this.minMatch = MIN_MATCH7;
         }
         
         final int mm = this.minMatch;
         int srcIdx = input.index + 5;

         // Main loop
         while (startChunk < dstEnd)
         {
            litBuf.index = 0;
            lenBuf.index = 0;
            mIdxBuf.index = 0;
            tkBuf.index = 0;

            for (int i=0; i<this.matches.length; i++)
               this.matches[i] = 0;

            final int endChunk = Math.min(startChunk+sizeChunk, dstEnd);
            sizeChunk = endChunk - startChunk;
            int dstIdx = output.index;

            // Scope to deallocate resources early
            {
               // Decode literal, match length and match index buffers
               ByteArrayInputStream bais = new ByteArrayInputStream(src, srcIdx, count-srcIdx);
               InputBitStream ibs = new DefaultInputBitStream(bais, 65536);
               int litLen  = (int) ibs.readBits(32);
               int tkLen = (int) ibs.readBits(32);
               int mLenLen = (int) ibs.readBits(32);
               int mIdxLen = (int) ibs.readBits(32);

               if ((litLen>sizeChunk) || (tkLen>sizeChunk) || (mLenLen>sizeChunk) || (mIdxLen>sizeChunk))
               {
                  input.index = srcIdx;
                  output.index = dstIdx;
                  return false;
               }

               ANSRangeDecoder litDec = new ANSRangeDecoder(ibs, litOrder, this.ctx);
               litDec.decode(litBuf.array, 0, litLen);
               litDec.dispose();
               ANSRangeDecoder mDec = new ANSRangeDecoder(ibs, 0, this.ctx);
               mDec.decode(tkBuf.array, 0, tkLen);
               mDec.decode(lenBuf.array, 0, mLenLen);
               mDec.decode(mIdxBuf.array, 0, mIdxLen);
               mDec.dispose();

               srcIdx += (int) ((ibs.read()+7)>>>3);
               ibs.close();
            }

            final int n = Math.min(dstEnd-dstIdx, 8);

            for (int j=0; j<n; j++)
               dst[dstIdx++] = litBuf.array[litBuf.index++];

            // Next chunk
            while (dstIdx < endChunk)
            {
               // mode LLLLLMMM -> L lit length, M match length
               final int mode = tkBuf.array[tkBuf.index++] & 0xFF;
               int matchLen = mode & 0x07;

               if (matchLen == 7)
                  matchLen = readLength(lenBuf) + 7;

               final int litLen = (mode < 0xF8) ? mode >> 3 : readLength(lenBuf) + 31;
               final int n0 = dstIdx - output.index;

               for (int j=0; j<litLen; j++)
               {
                  final int key = (mm == MIN_MATCH3) ? getKey1(dst, dstIdx+j-2) : getKey2(dst, dstIdx+j-8);
                  final int base = key << this.logPosChecks;
                  dst[dstIdx+j] = litBuf.array[litBuf.index+j];
                  this.counters[key] = (this.counters[key]+1) & this.maskChecks;
                  this.matches[base+this.counters[key]] = n0 + j;
               }
               
               litBuf.index += litLen;
               dstIdx += litLen;

               if (dstIdx >= endChunk)
               {
                  // Last chunk literals not followed by match
                  if (dstIdx == endChunk)
                     break;

                  output.index = dstIdx;
                  input.index = srcIdx;
                  return false;
               }

               // Sanity check
               if (dstIdx+matchLen+mm > dstEnd)
               {
                  output.index = dstIdx;
                  input.index = srcIdx;
                  return false;
               }

               final int key = (mm == MIN_MATCH3) ? getKey1(dst, dstIdx-2) : getKey2(dst, dstIdx-8);
               final int base = key << this.logPosChecks;
               final int matchIdx = mIdxBuf.array[mIdxBuf.index++] & 0xFF;
               final int ref = output.index + this.matches[base+((this.counters[key]-matchIdx)&this.maskChecks)];
               final int savedIdx = dstIdx;
               dstIdx = emitCopy(dst, dstIdx, ref, matchLen+this.minMatch);
               this.counters[key] = (this.counters[key]+1) & this.maskChecks;
               this.matches[base+this.counters[key]] = savedIdx - output.index;
            }

            startChunk = endChunk;
            output.index = dstIdx;
         }

         // Emit last literals
         dst[output.index++] = src[srcIdx++];
         dst[output.index++] = src[srcIdx++];
         dst[output.index++] = src[srcIdx++];
         dst[output.index++] = src[srcIdx++];

         input.index = srcIdx;
         return input.index == srcEnd;
      }


      private static int readLength(SliceByteArray lenBuf)
      {
         int next = lenBuf.array[lenBuf.index++];
         int length = next & 0x7F;

         if ((next & 0x80) != 0)
         {
            next = lenBuf.array[lenBuf.index++];
            length = (length<<7) | (next&0x7F);

            if ((next & 0x80) != 0)
            {
               next = lenBuf.array[lenBuf.index++];
               length = (length<<7) | (next&0x7F);

               if ((next & 0x80) != 0)
               {
                  next = lenBuf.array[lenBuf.index++];
                  length = (length<<7) | (next&0x7F);
               }
            }
         }

         return length;
      }


      @Override
      public int getMaxEncodedLength(int srcLen)
      {
         return (srcLen <= 512) ? srcLen+64 : srcLen;
      }
   }


   // Use CM (ROLZEncoder/ROLZDecoder) to encode/decode literals and matches
   // Code loosely based on 'balz' by Ilya Muravyov
   static class ROLZCodec2 implements ByteTransform
   {
      private static final int MIN_MATCH3 = 3;
      private static final int MIN_MATCH7 = 7;
      private static final int MAX_MATCH = MIN_MATCH3 + 255;
      private static final int LOG_POS_CHECKS = 5;

      private final int logPosChecks;
      private final int maskChecks;
      private final int posChecks;
      private final int[] matches;
      private final int[] counters;
      private final Map<String, Object> ctx;
      private int minMatch;


      public ROLZCodec2()
      {
         this(LOG_POS_CHECKS, null);
      }


      public ROLZCodec2(int logPosChecks)
      {
         this(logPosChecks, null);
      }
         
      
      public ROLZCodec2(Map<String, Object> ctx)
      {
         this(LOG_POS_CHECKS, ctx);
      }
      
      
      protected ROLZCodec2(int logPosChecks, Map<String, Object> ctx)
      {         
         if ((logPosChecks < 2) || (logPosChecks > 8))
            throw new IllegalArgumentException("ROLZX codec: Invalid logPosChecks parameter " +
               "(must be in [2..8])");

         this.logPosChecks = logPosChecks;
         this.posChecks = 1 << logPosChecks;
         this.maskChecks = this.posChecks - 1;
         this.counters = new int[1<<16];
         this.matches = new int[HASH_SIZE<<this.logPosChecks];
         this.ctx = ctx;
      }


      // return position index (LOG_POS_CHECKS bits) + length (16 bits) or -1
      private int findMatch(final SliceByteArray sba, final int pos, final int key)
      {
         final byte[] buf = sba.array;
         final int base = key << this.logPosChecks;
         final int hash32 = hash(buf, pos);
         final int counter = this.counters[key];
         int bestLen = 0;
         int bestIdx = -1;
         final int maxMatch = Math.min(MAX_MATCH, sba.length-pos);

         // Check all recorded positions
         for (int i=counter; i>counter-this.posChecks; i--)
         {
            int ref = this.matches[base+(i&this.maskChecks)];

            // Hash check may save a memory access ...
            if ((ref & HASH_MASK) != hash32)
               continue;

            ref = (ref & ~HASH_MASK) + sba.index;

            if (buf[ref+bestLen] != buf[pos+bestLen])
               continue;

            int n = 0;

            while (n+4 < maxMatch) 
            {
               final int diff = Memory.LittleEndian.readInt32(buf, ref+n) ^ Memory.LittleEndian.readInt32(buf, pos+n);
               
               if (diff != 0)
               {
                  n += (Integer.numberOfTrailingZeros(diff) >> 3); 
                  break;
               }
               
               n += 4;
            }

            if (n > bestLen)
            {
               bestIdx = counter - i;
               bestLen = n;

               if (bestLen == maxMatch)
                  break;
            }
         }

         // Register current position
         this.counters[key] = (this.counters[key]+1) & this.maskChecks;
         this.matches[base+this.counters[key]] = hash32 | (pos-sba.index);
         return (bestLen < this.minMatch) ? -1 : (bestIdx<<16) | (bestLen-this.minMatch);
      }


      @Override
      public boolean forward(SliceByteArray input, SliceByteArray output)
      {
         final int count = input.length;

         if (output.length - output.index < this.getMaxEncodedLength(count))
            return false;

         final byte[] src = input.array;
         final byte[] dst = output.array;
         final int srcEnd = input.index + count - 4;
         Memory.BigEndian.writeInt32(dst, output.index, count);
         int sizeChunk = Math.min(count, CHUNK_SIZE);
         int startChunk = input.index;
         this.minMatch = MIN_MATCH3;
         byte flags = 0;
         
         if (this.ctx != null)
         {
            Global.DataType dt = (Global.DataType) this.ctx.getOrDefault("dataType",
               Global.DataType.UNDEFINED);

            if (dt == Global.DataType.DNA)
            {
               this.minMatch = MIN_MATCH7;
               flags = 1;
            }
         }

         final int mm = this.minMatch;
         dst[output.index+4] = flags;
         SliceByteArray sba1 = new SliceByteArray(dst, output.index+5);
         ROLZEncoder re = new ROLZEncoder(9, this.logPosChecks, sba1);
         int srcIdx = input.index;

         for (int i=0; i<this.counters.length; i++)
            this.counters[i] = 0;

         // Main loop
         while (startChunk < srcEnd)
         {
            for (int i=0; i<this.matches.length; i++)
               this.matches[i] = 0;

            final int endChunk = Math.min(startChunk+sizeChunk, srcEnd);
            final SliceByteArray sba2 = new SliceByteArray(src, endChunk, startChunk);
            srcIdx = startChunk;

            // First literals
            final int n = Math.min(srcEnd-startChunk, 8);
            re.setContext(LITERAL_CTX, (byte) 0);
            
            for (int j=0; j<n; j++)
            {
               re.encode9Bits((LITERAL_FLAG<<8)|(src[srcIdx]&0xFF));
               srcIdx++;
            }

            // Next chunk
            while (srcIdx < endChunk)
            {
               re.setContext(LITERAL_CTX, src[srcIdx-1]);
               final int match = (mm == MIN_MATCH3) ? findMatch(sba2, srcIdx, getKey1(src, srcIdx-2)) :
                  findMatch(sba2, srcIdx, getKey2(src, srcIdx-8));
                  
               if (match < 0)
               {
                  // Emit one literal
                  re.encode9Bits((LITERAL_FLAG<<8)|(src[srcIdx]&0xFF));
                  srcIdx++;
                  continue;
               }

               // Emit one match length and index
               final int matchLen = match & 0xFFFF;
               re.encode9Bits((MATCH_FLAG<<8)|matchLen);
               re.setContext(MATCH_CTX, src[srcIdx-1]);
               final int matchIdx = match >>> 16;
               re.encodeBits(matchIdx, this.logPosChecks);
               srcIdx += (matchLen+this.minMatch);
            }

            startChunk = endChunk;
         }

         // Emit last literals
         for (int i=0; i<4; i++, srcIdx++)
         {
            re.setContext(LITERAL_CTX, src[srcIdx-1]);
            re.encode9Bits((LITERAL_FLAG<<8)|(src[srcIdx]&0xFF));
         }

         re.dispose();
         input.index = srcIdx;
         output.index = sba1.index;
         return (input.index == srcEnd+4) && (output.index < count);
      }


      @Override
      public boolean inverse(SliceByteArray input, SliceByteArray output)
      {
         final int count = input.length;
         final byte[] src = input.array;
         final byte[] dst = output.array;
         final int srcEnd = input.index + count;
         final int szBlock = Memory.BigEndian.readInt32(src, input.index);
         
         if ((szBlock <= 0) || (szBlock > output.length))
            return false;
         
         final int dstEnd = output.index + szBlock;
         int sizeChunk = Math.min(szBlock, CHUNK_SIZE);
         int startChunk = output.index;
         this.minMatch = MIN_MATCH3;         
         final int bsVersion = (this.ctx == null) ? 3 : (Integer) this.ctx.getOrDefault("bsVersion", 3);

         if (bsVersion >= 3)
         {
            if (src[input.index+4] == 1)
               this.minMatch = MIN_MATCH7;
         }
              
         final int mm = this.minMatch;
         int srcIdx = input.index + 5;
         SliceByteArray sba = new SliceByteArray(src, srcIdx);
         ROLZDecoder rd = new ROLZDecoder(9, this.logPosChecks, sba);

         for (int i=0; i<this.counters.length; i++)
            this.counters[i] = 0;

         // Main loop
         while (startChunk < dstEnd)
         {
            for (int i=0; i<this.matches.length; i++)
               this.matches[i] = 0;

            final int endChunk = (startChunk+sizeChunk < dstEnd) ? startChunk+sizeChunk : dstEnd;
            int dstIdx = output.index;

            // First literals
            final int n = Math.min(dstEnd-startChunk, 8);
            rd.setContext(LITERAL_CTX, (byte) 0);
            
            for (int j=0; j<n; j++)
            {
               int val1 = rd.decode9Bits();

               // Sanity check
               if ((val1>>>8) == MATCH_FLAG)
               {
                  output.index = dstIdx;
                  break;
               }

               dst[dstIdx++] = (byte) val1;
            }

            // Next chunk
            while (dstIdx < endChunk)
            {
               final int savedIdx = dstIdx;
               final int key = (mm == MIN_MATCH3) ? getKey1(dst, dstIdx-2) : getKey2(dst, dstIdx-8);
               final int base = key << this.logPosChecks;
               rd.setContext(LITERAL_CTX, dst[dstIdx-1]);
               final int val = rd.decode9Bits();

               if ((val>>>8) == LITERAL_FLAG)
               {
                  // Read one literal
                  dst[dstIdx++] = (byte) val;
               }
               else
               {
                  // Read one match length and index
                  final int matchLen = val & 0xFF;

                  // Sanity check
                  if (dstIdx+matchLen+3 > dstEnd)
                  {
                     output.index = dstIdx;
                     break;
                  }

                  rd.setContext(MATCH_CTX, dst[dstIdx-1]);
                  final int matchIdx = rd.decodeBits(this.logPosChecks);
                  final int ref = output.index + this.matches[base+((this.counters[key]-matchIdx)&this.maskChecks)];
                  dstIdx = emitCopy(dst, dstIdx, ref, matchLen+mm);
               }

               // Update map
               this.counters[key] = (this.counters[key]+1) & this.maskChecks;
               this.matches[base+this.counters[key]] = savedIdx - output.index;
            }

            startChunk = endChunk;
            output.index = dstIdx;
         }

         rd.dispose();
         input.index = sba.index;
         return input.index == srcEnd;
      }


      @Override
      public int getMaxEncodedLength(int srcLen)
      {
         // Since we do not check the dst index for each byte (for speed purpose)
         // allocate some extra buffer for incompressible data.
         return (srcLen <= 16384) ? srcLen+1024 : srcLen+(srcLen/32);
      }
   }



   static class ROLZEncoder
   {
      private static final long TOP         = 0x00FFFFFFFFFFFFFFL;
      private static final long MASK_0_32   = 0x00000000FFFFFFFFL;
      private static final int PSCALE       = 0xFFFF;


      private final SliceByteArray sba;
      private long low;
      private long high;
      private final int[][] probs;
      private final int[] logSizes;
      private int c1;
      private int ctx;
      private int pIdx;


      public ROLZEncoder(int litLogSize, int mLogSize, SliceByteArray sba)
      {
         this.low = 0L;
         this.high = TOP;
         this.sba = sba;
         this.pIdx = LITERAL_FLAG;
         this.c1 = 1;
         this.probs = new int[2][];
         this.probs[MATCH_CTX] = new int[256<<mLogSize];
         this.probs[LITERAL_CTX] = new int[256<<litLogSize];
         this.logSizes = new int[2];
         this.logSizes[MATCH_CTX] = mLogSize;
         this.logSizes[LITERAL_CTX] = litLogSize;
         this.reset();
      }

      private void reset()
      {
         final int mLogSize = this.logSizes[MATCH_CTX];

         for (int i=0; i<(256<<mLogSize); i++)
            this.probs[MATCH_CTX][i] = PSCALE>>1;

         final int litLogSize = this.logSizes[LITERAL_CTX];

         for (int i=0; i<(256<<litLogSize); i++)
            this.probs[LITERAL_CTX][i] = PSCALE>>1;
      }

      public void setContext(int n, byte ctx)
      {
         this.pIdx = n;
         this.ctx = (ctx&0xFF) << this.logSizes[this.pIdx];
      }

      public final void encodeBits(int val, int n)
      {
         this.c1 = 1;

         do
         {
            n--;
            this.encodeBit(val & (1<<n));
         }
         while (n != 0);
      }
      
      public final void encode9Bits(int val) 
      {
         this.c1 = 1;
         this.encodeBit(val & 0x100);
         this.encodeBit(val & 0x80);
         this.encodeBit(val & 0x40);
         this.encodeBit(val & 0x20);
         this.encodeBit(val & 0x10);
         this.encodeBit(val & 0x08);
         this.encodeBit(val & 0x04);
         this.encodeBit(val & 0x02);
         this.encodeBit(val & 0x01);
      }

      public void encodeBit(int bit)
      {
         // Calculate interval split
         final long split = (((this.high-this.low)>>>4) * (this.probs[this.pIdx][this.ctx+this.c1]>>>4)) >>> 8;

         // Update fields with new interval bounds
         if (bit == 0)
         {
            this.low += (split+1);
            this.probs[this.pIdx][this.ctx+this.c1] -= (this.probs[this.pIdx][this.ctx+this.c1]>>5);
            this.c1 += this.c1;
         }
         else
         {
            this.high = this.low + split;
            this.probs[this.pIdx][this.ctx+this.c1] -= (((this.probs[this.pIdx][this.ctx+this.c1]-0xFFFF)>>5) + 1);
            this.c1 += (this.c1+1);
         }

         // Write unchanged first 32 bits to bitstream
         while (((this.low ^ this.high) >>> 24) == 0)
         {
            Memory.BigEndian.writeInt32(this.sba.array, this.sba.index, (int) (this.high>>>32));
            this.sba.index += 4;
            this.low <<= 32;
            this.high = (this.high << 32) | MASK_0_32;
         }
      }

      public void dispose()
      {
         for (int i=0; i<8; i++)
         {
            this.sba.array[this.sba.index+i] = (byte) (this.low>>56);
            this.low <<= 8;
         }

         this.sba.index += 8;
      }
   }


   static class ROLZDecoder
   {
      private static final long TOP         = 0x00FFFFFFFFFFFFFFL;
      private static final long MASK_0_56   = 0x00FFFFFFFFFFFFFFL;
      private static final long MASK_0_32   = 0x00000000FFFFFFFFL;
      private static final int PSCALE       = 0xFFFF;

      private final SliceByteArray sba;
      private long low;
      private long high;
      private long current;
      private final int[][] probs;
      private final int[] logSizes;
      private int c1;
      private int ctx;
      private int pIdx;


      public ROLZDecoder(int litLogSize, int mLogSize, SliceByteArray sba)
      {
         this.low = 0L;
         this.high = TOP;
         this.sba = sba;
         this.current  = 0;

         for (int i=0; i<8; i++)
            this.current = (this.current<<8) | (long) (this.sba.array[this.sba.index+i] &0xFF);

         this.sba.index += 8;
         this.pIdx = LITERAL_CTX;
         this.c1 = 1;
         this.probs = new int[2][];
         this.probs[MATCH_CTX] = new int[256<<mLogSize];
         this.probs[LITERAL_CTX] = new int[256<<litLogSize];
         this.logSizes = new int[2];
         this.logSizes[MATCH_CTX] = mLogSize;
         this.logSizes[LITERAL_CTX] = litLogSize;
         this.reset();
      }

      private void reset()
      {
         final int mLogSize = this.logSizes[MATCH_CTX];

         for (int i=0; i<(256<<mLogSize); i++)
            this.probs[MATCH_CTX][i] = PSCALE>>1;

         final int litLogSize = this.logSizes[LITERAL_CTX];

         for (int i=0; i<(256<<litLogSize); i++)
            this.probs[LITERAL_CTX][i] = PSCALE>>1;
      }

      public void setContext(int n, byte ctx)
      {
         this.pIdx = n;
         this.ctx = (ctx&0xFF) << this.logSizes[this.pIdx];
      }

      public int decodeBits(int n)
      {
         this.c1 = 1;
         final int mask = (1<<n) - 1;

         do
         {
            decodeBit();
            n--;
         }
         while (n != 0);

         return this.c1 & mask;
      }
 
      public int decode9Bits()
      {
         this.c1 = 1;
         this.decodeBit();
         this.decodeBit();
         this.decodeBit();
         this.decodeBit();
         this.decodeBit();
         this.decodeBit();
         this.decodeBit();
         this.decodeBit();
         this.decodeBit();
         return this.c1 & 0x1FF;
      }
      
      public int decodeBit()
      {
         // Calculate interval split
         final long mid = this.low + ((((this.high-this.low)>>>4) * (this.probs[this.pIdx][this.ctx+this.c1]>>>4)) >>> 8);
         int bit;

         // Update bounds and predictor
         if (mid >= this.current)
         {
            bit = 1;
            this.high = mid;
            this.probs[this.pIdx][this.ctx+this.c1] -= (((this.probs[this.pIdx][this.ctx+this.c1]-0xFFFF)>>5) + 1);
            this.c1 += (this.c1+1);
         }
         else
         {
            bit = 0;
            this.low = mid + 1;
            this.probs[this.pIdx][this.ctx+this.c1] -= (this.probs[this.pIdx][this.ctx+this.c1]>>5);
            this.c1 += this.c1;
         }

         // Read 32 bits from bitstream
         while (((this.low ^ this.high) >>> 24) == 0)
         {
            this.low = (this.low << 32) & MASK_0_56;
            this.high = ((this.high << 32) | MASK_0_32) & MASK_0_56;
            final long val = Memory.BigEndian.readInt32(this.sba.array, this.sba.index) & MASK_0_32;
            this.current = ((this.current << 32) | val) & MASK_0_56;
            this.sba.index += 4;
         }

         return bit;
      }

      public void dispose()
      {
      }
   }

}
