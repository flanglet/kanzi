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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import kanzi.ByteTransform;
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
   private static final int LOG_POS_CHECKS1 = 4;
   private static final int LOG_POS_CHECKS2 = 5;
   private static final int CHUNK_SIZE = 1 << 26;
   private static final int MATCH_FLAG = 0;
   private static final int LITERAL_FLAG = 1;
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
      String transform = (String) ctx.getOrDefault("transform", "NONE");
      this.delegate = (transform.contains("ROLZX")) ? new ROLZCodec2() : new ROLZCodec1();
   }


   private static short getKey(final byte[] buf, final int idx)
   {
      return (short) (Memory.LittleEndian.readInt16(buf, idx) & 0x7FFFFFFF);
   }


   private static int hash(final byte[] buf, final int idx)
   {
      return ((Memory.LittleEndian.readInt32(buf, idx)<<8) * HASH) & HASH_MASK;
   }


   private static int emitCopy(byte[] dst, int dstIdx, int ref, int matchLen)
   {
      dst[dstIdx]   = dst[ref];
      dst[dstIdx+1] = dst[ref+1];
      dst[dstIdx+2] = dst[ref+2];
      dstIdx += 3;
      ref += 3;

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
      private static final int MIN_MATCH = 3;
      private static final int MAX_MATCH = MIN_MATCH + 65535;

      private final int logPosChecks;
      private final int maskChecks;
      private final int posChecks;
      private final int[] matches;
      private final int[] counters;


      public ROLZCodec1()
      {
         this(LOG_POS_CHECKS1);
      }


      public ROLZCodec1(int logPosChecks)
      {
         if ((logPosChecks < 2) || (logPosChecks > 8))
            throw new IllegalArgumentException("ROLZ codec: Invalid logPosChecks parameter " +
               "(must be in [2..8])");

         this.logPosChecks = logPosChecks;
         this.posChecks = 1 << logPosChecks;
         this.maskChecks = this.posChecks - 1;
         this.counters = new int[1<<16];
         this.matches = new int[HASH_SIZE<<this.logPosChecks];
      }


      // return position index (LOG_POS_CHECKS bits) + length (16 bits) or -1
      private int findMatch(final SliceByteArray sba, final int pos)
      {
         final byte[] buf = sba.array;
         final int key = getKey(buf, pos-2) & 0xFFFF;
         final int base = key << this.logPosChecks;
         final int hash32 = hash(buf, pos);
         final int counter = this.counters[key];
         int bestLen = MIN_MATCH - 1;
         int bestIdx = -1;
         byte first = buf[pos];
         final int maxMatch = (sba.length-pos >= MAX_MATCH) ? MAX_MATCH : sba.length-pos;

         // Check all recorded positions
         for (int i=counter; i>counter-this.posChecks; i--)
         {
            int ref = this.matches[base+(i&this.maskChecks)];

            // Hash check may save a memory access ...
            if ((ref & HASH_MASK) != hash32)
               continue;

            ref = (ref & ~HASH_MASK) + sba.index;

            if (buf[ref] != first)
               continue;

            int n = 1;

            while ((n < maxMatch) && (buf[ref+n] == buf[pos+n]))
               n++;

            if (n > bestLen)
            {
               bestIdx = counter - i;
               bestLen = n;

               if (bestLen == maxMatch)
                  break;
            }
         }

         // Register current position
         this.counters[key]++;
         this.matches[base+(this.counters[key]&this.maskChecks)] = hash32 | (pos-sba.index);
         return (bestLen < MIN_MATCH) ? -1 : (bestIdx<<16) | (bestLen-MIN_MATCH);
      }


      @Override
      public boolean forward(SliceByteArray input, SliceByteArray output)
      {
         final int count = input.length;

         if (output.length - output.index < this.getMaxEncodedLength(count))
            return false;

         int srcIdx = input.index;
         int dstIdx = output.index;
         final byte[] src = input.array;
         final byte[] dst = output.array;
         final int srcEnd = srcIdx + count - 4;
         Memory.BigEndian.writeInt32(dst, dstIdx, count);
         dstIdx += 4;
         int sizeChunk = (count <= CHUNK_SIZE) ? count : CHUNK_SIZE;
         int startChunk = srcIdx;
         final SliceByteArray litBuf = new SliceByteArray(new byte[this.getMaxEncodedLength(sizeChunk)], 0);
         final SliceByteArray lenBuf = new SliceByteArray(new byte[sizeChunk/5], 0);
         final SliceByteArray mIdxBuf = new SliceByteArray(new byte[sizeChunk/4], 0);
         final SliceByteArray tkBuf = new SliceByteArray(new byte[sizeChunk/5], 0);
         ByteArrayOutputStream baos = new ByteArrayOutputStream(this.getMaxEncodedLength(sizeChunk));

         for (int i=0; i<this.counters.length; i++)
            this.counters[i] = 0;

         final int litOrder = (count < 1<<17) ? 0 : 1;
         dst[dstIdx++] = (byte) litOrder;

         // Main loop
         while (startChunk < srcEnd)
         {
            litBuf.index = 0;
            lenBuf.index = 0;
            mIdxBuf.index = 0;
            tkBuf.index = 0;

            for (int i=0; i<this.matches.length; i++)
               this.matches[i] = 0;

            final int endChunk = (startChunk+sizeChunk < srcEnd) ? startChunk+sizeChunk : srcEnd;
            sizeChunk = endChunk - startChunk;
            srcIdx = startChunk;
            final SliceByteArray sba = new SliceByteArray(src, endChunk, startChunk);
            litBuf.array[litBuf.index++] = src[srcIdx++];

            if (startChunk+1 < srcEnd)
               litBuf.array[litBuf.index++] = src[srcIdx++];

            int firstLitIdx = srcIdx;

            // Next chunk
            while (srcIdx < endChunk)
            {
               final int match = findMatch(sba, srcIdx);

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
               srcIdx += ((match&0xFFFF) + MIN_MATCH);
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
         int srcIdx = input.index;
         final int srcEnd = srcIdx + count;
         final int dstEnd = output.index + Memory.BigEndian.readInt32(src, srcIdx) - 4;
         srcIdx += 4;
         int sizeChunk = (dstEnd <= CHUNK_SIZE) ? dstEnd : CHUNK_SIZE;
         int startChunk = output.index;
         final SliceByteArray litBuf  = new SliceByteArray(new byte[this.getMaxEncodedLength(sizeChunk)], 0);
         final SliceByteArray lenBuf = new SliceByteArray(new byte[sizeChunk/5], 0);
         final SliceByteArray mIdxBuf = new SliceByteArray(new byte[sizeChunk/4], 0);
         final SliceByteArray tkBuf = new SliceByteArray(new byte[sizeChunk/5], 0);

         for (int i=0; i<this.counters.length; i++)
            this.counters[i] = 0;

         final int litOrder = src[srcIdx++];

         // Main loop
         while (startChunk < dstEnd)
         {
            litBuf.index = 0;
            lenBuf.index = 0;
            mIdxBuf.index = 0;
            tkBuf.index = 0;

            for (int i=0; i<this.matches.length; i++)
               this.matches[i] = 0;

            final int endChunk = (startChunk+sizeChunk < dstEnd) ? startChunk+sizeChunk : dstEnd;
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

               ANSRangeDecoder litDec = new ANSRangeDecoder(ibs, litOrder);
               litDec.decode(litBuf.array, 0, litLen);
               litDec.dispose();
               ANSRangeDecoder mDec = new ANSRangeDecoder(ibs, 0);
               mDec.decode(tkBuf.array, 0, tkLen);
               mDec.decode(lenBuf.array, 0, mLenLen);
               mDec.decode(mIdxBuf.array, 0, mIdxLen);
               mDec.dispose();

               srcIdx += (int) ((ibs.read()+7)>>>3);
               ibs.close();
            }

            dst[dstIdx++] = litBuf.array[litBuf.index++];

            if (dstIdx+1 < dstEnd)
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
               this.emitLiterals(litBuf, dst, dstIdx, output.index, litLen);
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
               if (dstIdx+matchLen+MIN_MATCH > dstEnd)
               {
                  output.index = dstIdx;
                  input.index = srcIdx;
                  return false;
               }

               final int key = getKey(dst, dstIdx-2) & 0xFFFF;
               final int base = key << this.logPosChecks;
               final int matchIdx = mIdxBuf.array[mIdxBuf.index++] & 0xFF;
               final int ref = output.index + this.matches[base+((this.counters[key]-matchIdx)&this.maskChecks)];
               final int savedIdx = dstIdx;
               dstIdx = emitCopy(dst, dstIdx, ref, matchLen);
               this.counters[key]++;
               this.matches[base+(this.counters[key]&this.maskChecks)] = savedIdx - output.index;
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


      private int emitLiterals(SliceByteArray litBuf, byte[] dst, int dstIdx, int startIdx, final int length)
      {
         final int n0 = dstIdx - startIdx;

         for (int n=0; n<length; n++)
         {
            final int key = getKey(dst, dstIdx+n-2) & 0xFFFF;
            final int base = key << this.logPosChecks;
            dst[dstIdx+n] = litBuf.array[litBuf.index+n];
            this.counters[key]++;
            this.matches[base+(this.counters[key]&this.maskChecks)] = n0 + n;
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
      private static final int MIN_MATCH = 3;
      private static final int MAX_MATCH = MIN_MATCH + 255;

      private final int logPosChecks;
      private final int maskChecks;
      private final int posChecks;
      private final int[] matches;
      private final int[] counters;


      public ROLZCodec2()
      {
         this(LOG_POS_CHECKS2);
      }


      public ROLZCodec2(int logPosChecks)
      {
         if ((logPosChecks < 2) || (logPosChecks > 8))
            throw new IllegalArgumentException("ROLZX codec: Invalid logPosChecks parameter " +
               "(must be in [2..8])");

         this.logPosChecks = logPosChecks;
         this.posChecks = 1 << logPosChecks;
         this.maskChecks = this.posChecks - 1;
         this.counters = new int[1<<16];
         this.matches = new int[HASH_SIZE<<this.logPosChecks];
      }


      // return position index (LOG_POS_CHECKS bits) + length (16 bits) or -1
      private int findMatch(final SliceByteArray sba, final int pos)
      {
         final byte[] buf = sba.array;
         final int key = getKey(buf, pos-2) & 0xFFFF;
         final int base = key << this.logPosChecks;
         final int hash32 = hash(buf, pos);
         final int counter = this.counters[key];
         int bestLen = MIN_MATCH - 1;
         int bestIdx = -1;
         byte first = buf[pos];
         final int maxMatch = (sba.length-pos >= MAX_MATCH) ? MAX_MATCH : sba.length-pos;

         // Check all recorded positions
         for (int i=counter; i>counter-this.posChecks; i--)
         {
            int ref = this.matches[base+(i&this.maskChecks)];

            // Hash check may save a memory access ...
            if ((ref & HASH_MASK) != hash32)
               continue;

            ref = (ref & ~HASH_MASK) + sba.index;

            if (buf[ref] != first)
               continue;

            int n = 1;

            while ((n < maxMatch) && (buf[ref+n] == buf[pos+n]))
               n++;

            if (n > bestLen)
            {
               bestIdx = counter - i;
               bestLen = n;

               if (bestLen == maxMatch)
                  break;
            }
         }

         // Register current position
         this.counters[key]++;
         this.matches[base+(this.counters[key]&this.maskChecks)] = hash32 | (pos-sba.index);
         return (bestLen < MIN_MATCH) ? -1 : (bestIdx<<16) | (bestLen-MIN_MATCH);
      }


      @Override
      public boolean forward(SliceByteArray input, SliceByteArray output)
      {
         final int count = input.length;

         if (output.length - output.index < this.getMaxEncodedLength(count))
            return false;

         int srcIdx = input.index;
         int dstIdx = output.index;
         final byte[] src = input.array;
         final byte[] dst = output.array;
         final int srcEnd = srcIdx + count - 4;
         Memory.BigEndian.writeInt32(dst, dstIdx, count);
         dstIdx += 4;
         int sizeChunk = (count <= CHUNK_SIZE) ? count : CHUNK_SIZE;
         int startChunk = srcIdx;
         SliceByteArray sba1 = new SliceByteArray(dst, dstIdx);
         ROLZEncoder re = new ROLZEncoder(9, this.logPosChecks, sba1);

         for (int i=0; i<this.counters.length; i++)
            this.counters[i] = 0;

         // Main loop
         while (startChunk < srcEnd)
         {
            for (int i=0; i<this.matches.length; i++)
               this.matches[i] = 0;

            final int endChunk = (startChunk+sizeChunk < srcEnd) ? startChunk+sizeChunk : srcEnd;
            final SliceByteArray sba2 = new SliceByteArray(src, endChunk, startChunk);
            srcIdx = startChunk;

            // First literals
            re.setMode(LITERAL_FLAG);
            re.setContext((byte) 0);
            re.encodeBits((LITERAL_FLAG<<8)|(src[srcIdx]&0xFF), 9);
            srcIdx++;

            if (startChunk+1 < srcEnd)
            {
               re.encodeBits((LITERAL_FLAG<<8)|(src[srcIdx]&0xFF), 9);
               srcIdx++;
            }

            // Next chunk
            while (srcIdx < endChunk)
            {
               re.setContext(src[srcIdx-1]);
               final int match = findMatch(sba2, srcIdx);

               if (match < 0)
               {
                  // Emit one literal
                  re.encodeBits((LITERAL_FLAG<<8)|(src[srcIdx]&0xFF), 9);
                  srcIdx++;
                  continue;
               }

               // Emit one match length and index
               final int matchLen = match & 0xFFFF;
               re.encodeBits((MATCH_FLAG<<8)|matchLen, 9);
               final int matchIdx = match >>> 16;
               re.setMode(MATCH_FLAG);
               re.setContext(src[srcIdx-1]);
               re.encodeBits(matchIdx, this.logPosChecks);
               re.setMode(LITERAL_FLAG);
               srcIdx += (matchLen+MIN_MATCH);
            }

            startChunk = endChunk;
         }

         // Emit last literals
         re.setMode(LITERAL_FLAG);

         for (int i=0; i<4; i++, srcIdx++)
         {
            re.setContext(src[srcIdx-1]);
            re.encodeBits((LITERAL_FLAG<<8)|(src[srcIdx]&0xFF), 9);
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
         int srcIdx = input.index;
         final int srcEnd = srcIdx + count;
         final int dstEnd = output.index + Memory.BigEndian.readInt32(src, srcIdx);
         srcIdx += 4;
         int sizeChunk = (dstEnd < CHUNK_SIZE) ? dstEnd : CHUNK_SIZE;
         int startChunk = output.index;
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
            rd.setMode(LITERAL_FLAG);
            rd.setContext((byte) 0);
            int val1 = rd.decodeBits(9);

            // Sanity check
            if ((val1>>>8) == MATCH_FLAG)
            {
               output.index = dstIdx;
               break;
            }

            dst[dstIdx++] = (byte) val1;

            if (dstIdx < dstEnd)
            {
               val1 = rd.decodeBits(9);

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
               final int key = getKey(dst, dstIdx-2) & 0xFFFF;
               final int base = key << this.logPosChecks;
               rd.setMode(LITERAL_FLAG);
               rd.setContext(dst[dstIdx-1]);
               final int val = rd.decodeBits(9);

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

                  rd.setMode(MATCH_FLAG);
                  rd.setContext(dst[dstIdx-1]);
                  final int matchIdx = rd.decodeBits(this.logPosChecks);
                  final int ref = output.index + this.matches[base+((this.counters[key]-matchIdx)&this.maskChecks)];
                  dstIdx = emitCopy(dst, dstIdx, ref, matchLen);
               }

               // Update map
               this.counters[key]++;
               this.matches[base+(this.counters[key]&this.maskChecks)] = savedIdx - output.index;
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
      private static final int MATCH_FLAG   = 0;
      private static final int LITERAL_FLAG = 1;


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
         this.probs[MATCH_FLAG] = new int[256<<mLogSize];
         this.probs[LITERAL_FLAG] = new int[256<<litLogSize];
         this.logSizes = new int[2];
         this.logSizes[MATCH_FLAG] = mLogSize;
         this.logSizes[LITERAL_FLAG] = litLogSize;
         this.reset();
      }

      private void reset()
      {
         final int mLogSize = this.logSizes[MATCH_FLAG];

         for (int i=0; i<(256<<mLogSize); i++)
            this.probs[MATCH_FLAG][i] = PSCALE>>1;

         final int litLogSize = this.logSizes[LITERAL_FLAG];

         for (int i=0; i<(256<<litLogSize); i++)
            this.probs[LITERAL_FLAG][i] = PSCALE>>1;
      }

      public void setMode(int n)
      {
         this.pIdx = n;
      }

      public void setContext(byte ctx)
      {
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
      private static final int MATCH_FLAG   = 0;
      private static final int LITERAL_FLAG = 1;

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
         this.pIdx = LITERAL_FLAG;
         this.c1 = 1;
         this.probs = new int[2][];
         this.probs[MATCH_FLAG] = new int[256<<mLogSize];
         this.probs[LITERAL_FLAG] = new int[256<<litLogSize];
         this.logSizes = new int[2];
         this.logSizes[MATCH_FLAG] = mLogSize;
         this.logSizes[LITERAL_FLAG] = litLogSize;
         this.reset();
      }

      private void reset()
      {
         final int mLogSize = this.logSizes[MATCH_FLAG];

         for (int i=0; i<(256<<mLogSize); i++)
            this.probs[MATCH_FLAG][i] = PSCALE>>1;

         final int litLogSize = this.logSizes[LITERAL_FLAG];

         for (int i=0; i<(256<<litLogSize); i++)
            this.probs[LITERAL_FLAG][i] = PSCALE>>1;
      }

      public void setMode(int n)
      {
         this.pIdx = n;
      }

      public void setContext(byte ctx)
      {
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
