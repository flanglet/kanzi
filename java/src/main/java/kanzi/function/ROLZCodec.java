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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import kanzi.ByteFunction;
import kanzi.InputBitStream;
import kanzi.Memory;
import kanzi.OutputBitStream;
import kanzi.Predictor;
import kanzi.SliceByteArray;
import kanzi.bitstream.DefaultInputBitStream;
import kanzi.bitstream.DefaultOutputBitStream;
import kanzi.entropy.ANSRangeDecoder;
import kanzi.entropy.ANSRangeEncoder;


// Implementation of a Reduced Offset Lempel Ziv transform
// Code loosely based on 'balz' by Ilya Muravyov
// More information about ROLZ at http://ezcodesample.com/rolz/rolz_article.html

public class ROLZCodec implements ByteFunction
{
   private static final int HASH_SIZE = 1 << 16;
   private static final int MIN_MATCH = 3;
   private static final int MAX_MATCH = MIN_MATCH + 255;
   private static final int LOG_POS_CHECKS = 5;
   private static final int CHUNK_SIZE = 1 << 26;
   private static final int LITERAL_FLAG = 0;
   private static final int MATCH_FLAG = 1;
   private static final int HASH = 200002979;
   private static final int HASH_MASK = ~(CHUNK_SIZE - 1);
   private static final int MAX_BLOCK_SIZE = 1 << 27; // 128 MB


   private final ByteFunction delegate;


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


   private static int getKey(final byte[] buf, final int idx)
   {
      return Memory.LittleEndian.readInt16(buf, idx) & 0x7FFFFFFF;
   }


   private static int hash(final byte[] buf, final int idx)
   {
      return ((Memory.LittleEndian.readInt32(buf, idx)&0x00FFFFFF) * HASH) & HASH_MASK;
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

      if (src.array == dst.array)
         return false;

      if (src.length > MAX_BLOCK_SIZE)
         throw new IllegalArgumentException("The max ROLZCodec block size is "+MAX_BLOCK_SIZE+", got "+src.length);

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
         throw new IllegalArgumentException("The max ROLZCodec block size is "+MAX_BLOCK_SIZE+", got "+src.length);

      return this.delegate.inverse(src, dst);
   }


   // Use ANS to encode/decode literals and matches
   static class ROLZCodec1 implements ByteFunction
   {
      private final int logPosChecks;
      private final int maskChecks;
      private final int posChecks;
      private final int[] matches;
      private final int[] counters;


      public ROLZCodec1()
      {
         this(LOG_POS_CHECKS-1);
      }


      public ROLZCodec1(int logPosChecks)
      {
         if ((logPosChecks < 2) || (logPosChecks > 8))
            throw new IllegalArgumentException("Invalid logPosChecks parameter " +
               "(must be in [2..8])");

         this.logPosChecks = logPosChecks;
         this.posChecks = 1 << logPosChecks;
         this.maskChecks = this.posChecks - 1;
         this.counters = new int[1<<16];
         this.matches = new int[HASH_SIZE<<this.logPosChecks];
      }


      // return position index (LOG_POS_CHECKS bits) + length (8 bits) or -1
      private int findMatch(final SliceByteArray sba, final int pos)
      {
         final byte[] buf = sba.array;
         final int key = getKey(buf, pos-2);
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

            if (ref == 0)
               break;

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
         return (bestLen < MIN_MATCH) ? -1 : (bestIdx<<8) | (bestLen-MIN_MATCH);
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
         final SliceByteArray litBuf  = new SliceByteArray(new byte[this.getMaxEncodedLength(sizeChunk)], 0);
         final SliceByteArray mLenBuf = new SliceByteArray(new byte[sizeChunk/2], 0);
         final SliceByteArray mIdxBuf = new SliceByteArray(new byte[sizeChunk/2], 0);
         ByteArrayOutputStream baos = new ByteArrayOutputStream(this.getMaxEncodedLength(sizeChunk));

         for (int i=0; i<this.counters.length; i++)
            this.counters[i] = 0;

         // Main loop
         while (startChunk < srcEnd)
         {
            litBuf.index = 0;
            mLenBuf.index = 0;
            mIdxBuf.index = 0;

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

               final int length = srcIdx - firstLitIdx;
               emitLiteralLength(litBuf, length);

               // Emit literals
               if (length >= 16)
               {
                  System.arraycopy(src, firstLitIdx, litBuf.array, litBuf.index, length);
               }
               else
               {
                  for (int i=0; i<length; i++)
                     litBuf.array[litBuf.index+i] = src[firstLitIdx+i];
               }

               litBuf.index += length;

               // Emit match
               mLenBuf.array[mLenBuf.index++] = (byte) match;
               mIdxBuf.array[mIdxBuf.index++] = (byte) (match>>8);
               srcIdx += ((match & 0xFF) + MIN_MATCH);
               firstLitIdx = srcIdx;
            }

            // Emit last chunk literals
            final int length = srcIdx - firstLitIdx;
            emitLiteralLength(litBuf, length);

            for (int i=0; i<length; i++)
               litBuf.array[litBuf.index+i] = src[firstLitIdx+i];

            litBuf.index += length;

            // Scope to deallocate resources early
            {
               // Encode literal, match length and match index buffers
               baos.reset();
               OutputBitStream obs = new DefaultOutputBitStream(baos, 65536);
               obs.writeBits(litBuf.index, 32);
               ANSRangeEncoder litEnc = new ANSRangeEncoder(obs, 1);
               litEnc.encode(litBuf.array, 0, litBuf.index);
               litEnc.dispose();
               obs.writeBits(mLenBuf.index, 32);
               ANSRangeEncoder mLenEnc = new ANSRangeEncoder(obs, 0);
               mLenEnc.encode(mLenBuf.array, 0, mLenBuf.index);
               mLenEnc.dispose();
               //obs.writeBits(mIdxBuf.index, 32);
               ANSRangeEncoder mIdxEnc = new ANSRangeEncoder(obs, 0);
               mIdxEnc.encode(mIdxBuf.array, 0, mIdxBuf.index);
               mIdxEnc.dispose();
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

         // Emit last literals
         dst[dstIdx++] = src[srcIdx];
         dst[dstIdx++] = src[srcIdx+1];
         dst[dstIdx++] = src[srcIdx+2];
         dst[dstIdx++] = src[srcIdx+3];
    
         input.index = srcIdx + 4;
         output.index = dstIdx;
         return srcIdx == srcEnd;
      }


      private static void emitLiteralLength(SliceByteArray litBuf, final int length)
      {
         if (length >= 1<<7)
         {
            if (length >= 1<<21)
               litBuf.array[litBuf.index++] = (byte) (0x80|((length>>21)&0x7F));

            if (length >= 1<<14)
               litBuf.array[litBuf.index++] = (byte) (0x80|((length>>14)&0x7F));

            litBuf.array[litBuf.index++] = (byte) (0x80|((length>>7)&0x7F));
         }

         litBuf.array[litBuf.index++] = (byte) (length&0x7F);
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
         final SliceByteArray mLenBuf = new SliceByteArray(new byte[sizeChunk/2], 0);
         final SliceByteArray mIdxBuf = new SliceByteArray(new byte[sizeChunk/2], 0);

         for (int i=0; i<this.counters.length; i++)
            this.counters[i] = 0;

         // Main loop
         while (startChunk < dstEnd)
         {
            litBuf.index = 0;
            mLenBuf.index = 0;
            mIdxBuf.index = 0;

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
               int length = (int) ibs.readBits(32);

               if (length <= sizeChunk)
               {
                  ANSRangeDecoder litDec = new ANSRangeDecoder(ibs, 1);
                  litDec.decode(litBuf.array, 0, length);
                  litDec.dispose();
                  length = (int) ibs.readBits(32);
               }

               if (length <= sizeChunk)
               {
                  ANSRangeDecoder mLenDec = new ANSRangeDecoder(ibs, 0);
                  mLenDec.decode(mLenBuf.array, 0, length);
                  mLenDec.dispose();
                  ANSRangeDecoder mIdxDec = new ANSRangeDecoder(ibs, 0);
                  mIdxDec.decode(mIdxBuf.array, 0, length);
                  mIdxDec.dispose();
               }

               srcIdx += ((ibs.read()+7)>>>3);
               ibs.close();

               if (length > sizeChunk)
               {
                  input.index = srcIdx;
                  output.index = dstIdx;
                  return false;
               }
            }

            dst[dstIdx++] = litBuf.array[litBuf.index++];

            if (dstIdx+1 < dstEnd)
               dst[dstIdx++] = litBuf.array[litBuf.index++];

            // Next chunk
            while (dstIdx < endChunk)
            {
               final int length = emitLiterals(litBuf, dst, dstIdx, output.index);
               litBuf.index += length;
               dstIdx += length;

               if (dstIdx >= endChunk)
               {
                  // Last chunk literals not followed by match
                  if (dstIdx == endChunk)
                     break;
                  
                  output.index = dstIdx;
                  input.index = srcIdx;
                  return false;
               }

               final int matchLen = mLenBuf.array[mLenBuf.index++] & 0xFF;

               // Sanity check
               if (dstIdx+matchLen+3 > dstEnd)
               {
                  output.index = dstIdx;
                  input.index = srcIdx;
                  return false;
               }

               final int key = getKey(dst, dstIdx-2);
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


      private int emitLiterals(SliceByteArray litBuf, byte[] dst, int dstIdx, int startIdx)
      {
         // Read length
         int litLen = litBuf.array[litBuf.index++];
         int length = litLen & 0x7F;

         if ((litLen & 0x80) != 0)
         {
            litLen = litBuf.array[litBuf.index++];
            length = (length<<7) | (litLen&0x7F);

            if ((litLen & 0x80) != 0)
            {
               litLen = litBuf.array[litBuf.index++];
               length = (length<<7) | (litLen&0x7F);

               if ((litLen & 0x80) != 0)
               {
                  litLen = litBuf.array[litBuf.index++];
                  length = (length<<7) | (litLen&0x7F);
               }
            }
         }

         // Emit literal bytes
         for (int n=0; n<length; n++)
         {
            final int key = getKey(dst, dstIdx+n-2);
            final int base = key << this.logPosChecks;
            dst[dstIdx+n] = litBuf.array[litBuf.index+n];
            this.counters[key]++;
            this.matches[base+(this.counters[key]&this.maskChecks)] = dstIdx+n - startIdx;
         }

         return length;
      }


      @Override
      public int getMaxEncodedLength(int srcLen)
      {
         return (srcLen <= 512) ? srcLen+32 : srcLen;
      }
   }


   // Use CM (ROLZEncoder/ROLZDecoder) to encode/decode literals and matches
   static class ROLZCodec2 implements ByteFunction
   {
      private final int logPosChecks;
      private final int maskChecks;
      private final int posChecks;
      private final int[] matches;
      private final int[] counters;
      private final ROLZPredictor litPredictor;
      private final ROLZPredictor matchPredictor;


      public ROLZCodec2()
      {
         this(LOG_POS_CHECKS);
      }


      public ROLZCodec2(int logPosChecks)
      {
         if ((logPosChecks < 2) || (logPosChecks > 8))
            throw new IllegalArgumentException("Invalid logPosChecks parameter " +
               "(must be in [2..8])");

         this.logPosChecks = logPosChecks;
         this.posChecks = 1 << logPosChecks;
         this.maskChecks = this.posChecks - 1;
         this.counters = new int[1<<16];
         this.matches = new int[HASH_SIZE<<this.logPosChecks];
         this.litPredictor = new ROLZPredictor(9);
         this.matchPredictor = new ROLZPredictor(LOG_POS_CHECKS);
      }


      // return position index (LOG_POS_CHECKS bits) + length (8 bits) or -1
      private int findMatch(final SliceByteArray sba, final int pos)
      {
         final byte[] buf = sba.array;
         final int key = getKey(buf, pos-2);
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

            if (ref == 0)
               break;

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
         return (bestLen < MIN_MATCH) ? -1 : (bestIdx<<8) | (bestLen-MIN_MATCH);
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
         this.litPredictor.reset();
         this.matchPredictor.reset();
         final Predictor[] predictors = new Predictor[] { this.litPredictor, this.matchPredictor };
         ROLZEncoder re = new ROLZEncoder(predictors, sba1);

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
            this.litPredictor.setContext((byte) 0);
            re.setContext(LITERAL_FLAG);
            re.encodeBit(LITERAL_FLAG);
            re.encodeByte(src[srcIdx++]);

            if (startChunk+1 < srcEnd)
            {
               re.encodeBit(LITERAL_FLAG);
               re.encodeByte(src[srcIdx++]);
            }

            // Next chunk
            while (srcIdx < endChunk)
            {
               this.litPredictor.setContext(src[srcIdx-1]);
               re.setContext(LITERAL_FLAG);
               final int match = findMatch(sba2, srcIdx);

               if (match == -1)
               {
                  re.encodeBit(LITERAL_FLAG);
                  re.encodeByte(src[srcIdx]);
                  srcIdx++;
               }
               else
               {
                  final int matchLen = match & 0xFF;
                  re.encodeBit(MATCH_FLAG);
                  re.encodeByte((byte) matchLen);
                  final int matchIdx = match >> 8;
                  this.matchPredictor.setContext(src[srcIdx-1]);
                  re.setContext(MATCH_FLAG);

                  for (int shift=this.logPosChecks-1; shift>=0; shift--)
                     re.encodeBit((matchIdx>>shift) & 1);

                  srcIdx += (matchLen + MIN_MATCH);
               }
            }

            startChunk = endChunk;
         }

         // Emit last literals
         re.setContext(LITERAL_FLAG);

         for (int i=0; i<4; i++, srcIdx++)
         {
            this.litPredictor.setContext(src[srcIdx-1]);
            re.encodeBit(LITERAL_FLAG);
            re.encodeByte(src[srcIdx]);
         }

         re.dispose();
         input.index = srcIdx;
         output.index = sba1.index;
         return input.index == srcEnd + 4;
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
         this.litPredictor.reset();
         this.matchPredictor.reset();
         final Predictor[] predictors = new Predictor[] { this.litPredictor, this.matchPredictor };
         ROLZDecoder rd = new ROLZDecoder(predictors, sba);

         for (int i=0; i<this.counters.length; i++)
            this.counters[i] = 0;

         // Main loop
         while (startChunk < dstEnd)
         {
            for (int i=0; i<this.matches.length; i++)
               this.matches[i] = 0;

            final int endChunk = (startChunk+sizeChunk < dstEnd) ? startChunk+sizeChunk : dstEnd;
            int dstIdx = output.index;
            this.litPredictor.setContext((byte) 0);
            rd.setContext(LITERAL_FLAG);
            int bit = rd.decodeBit();

            if (bit == LITERAL_FLAG)
            {
               dst[dstIdx++] = rd.decodeByte();

               if (dstIdx < dstEnd)
               {
                  bit = rd.decodeBit();

                  if (bit == LITERAL_FLAG)
                     dst[dstIdx++] = rd.decodeByte();
               }
            }

            // Sanity check
            if (bit == MATCH_FLAG)
            {
               output.index = dstIdx;
               break;
            }

            // Next chunk
            while (dstIdx < endChunk)
            {
               final int savedIdx = dstIdx;
               final int key = getKey(dst, dstIdx-2);
               final int base = key << this.logPosChecks;
               this.litPredictor.setContext(dst[dstIdx-1]);
               rd.setContext(LITERAL_FLAG);

               if (rd.decodeBit() == MATCH_FLAG)
               {
                  // Match flag
                  final int matchLen = rd.decodeByte() & 0xFF;

                  // Sanity check
                  if (dstIdx+matchLen+3 > dstEnd)
                  {
                     output.index = dstIdx;
                     break;
                  }

                  this.matchPredictor.setContext(dst[dstIdx-1]);
                  rd.setContext(MATCH_FLAG);
                  int matchIdx = 0;

                  for (int shift=this.logPosChecks-1; shift>=0; shift--)
                     matchIdx |= (rd.decodeBit()<<shift);

                  final int ref = output.index + this.matches[base+((this.counters[key]-matchIdx)&this.maskChecks)];
                  dstIdx = emitCopy(dst, dstIdx, ref, matchLen);
               }
               else
               {
                  // Literal flag
                  dst[dstIdx++] = rd.decodeByte();
               }

               // Update
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
         if (srcLen >= CHUNK_SIZE)
            return srcLen;
         
         return (srcLen <= 512) ? srcLen+32 : srcLen+srcLen/8;
      }
   }



   static class ROLZEncoder
   {
      private static final long TOP        = 0x00FFFFFFFFFFFFFFL;
      private static final long MASK_0_32  = 0x00000000FFFFFFFFL;

      private final Predictor[] predictors;
      private final SliceByteArray sba;
      private Predictor predictor;
      private long low;
      private long high;


      public ROLZEncoder(Predictor[] predictors, SliceByteArray sba)
      {
         this.low = 0L;
         this.high = TOP;
         this.sba = sba;
         this.predictors = predictors;
         this.predictor = this.predictors[0];
      }

      public void setContext(int n)
      {
         this.predictor = this.predictors[n];
      }

      public final void encodeByte(byte val)
      {
         this.encodeBit((val >> 7) & 1);
         this.encodeBit((val >> 6) & 1);
         this.encodeBit((val >> 5) & 1);
         this.encodeBit((val >> 4) & 1);
         this.encodeBit((val >> 3) & 1);
         this.encodeBit((val >> 2) & 1);
         this.encodeBit((val >> 1) & 1);
         this.encodeBit(val & 1);
      }

      public void encodeBit(int bit)
      {
         // Calculate interval split
         final long split = (((this.high-this.low) >>> 4) * this.predictor.get()) >>> 8;

         // Update fields with new interval bounds
         this.high -= (-bit & (this.high - this.low - split));
         this.low += (~-bit & -~split);

         // Update predictor
         this.predictor.update(bit);

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
      private static final long TOP        = 0x00FFFFFFFFFFFFFFL;
      private static final long MASK_0_56  = 0x00FFFFFFFFFFFFFFL;
      private static final long MASK_0_32  = 0x00000000FFFFFFFFL;

      private final Predictor[] predictors;
      private final SliceByteArray sba;
      private Predictor predictor;
      private long low;
      private long high;
      private long current;


      public ROLZDecoder(Predictor[] predictors, SliceByteArray sba)
      {
         this.low = 0L;
         this.high = TOP;
         this.sba = sba;
         this.current  = 0;

         for (int i=0; i<8; i++)
            this.current = (this.current<<8) | (long) (this.sba.array[this.sba.index+i] &0xFF);

         this.sba.index += 8;
         this.predictors = predictors;
         this.predictor = this.predictors[0];
      }

      public void setContext(int n)
      {
         this.predictor = this.predictors[n];
      }

      public byte decodeByte()
      {
         return (byte) ((this.decodeBit() << 7)
               | (this.decodeBit() << 6)
               | (this.decodeBit() << 5)
               | (this.decodeBit() << 4)
               | (this.decodeBit() << 3)
               | (this.decodeBit() << 2)
               | (this.decodeBit() << 1)
               |  this.decodeBit());
      }

      public int decodeBit()
      {
         // Calculate interval split
         final long mid = this.low + ((((this.high-this.low) >>> 4) * this.predictor.get()) >>> 8);
         int bit;

         if (mid >= this.current)
         {
            bit = 1;
            this.high = mid;
         }
         else
         {
            bit = 0;
            this.low = -~mid;
         }

          // Update predictor
         this.predictor.update(bit);

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


   static class ROLZPredictor implements Predictor
   {
      private final int[] p1;
      private final int[] p2;
      private final int size;
      private final int logSize;
      private int c1;
      private int ctx;

      ROLZPredictor(int logPosChecks)
      {
         this.logSize = logPosChecks;
         this.size = 1 << logPosChecks;
         this.p1 = new int[256*this.size];
         this.p2 = new int[256*this.size];
         this.reset();
      }

      private void reset()
      {
         this.c1 = 1;
         this.ctx = 0;

         for (int i=0; i<this.p1.length; i++)
         {
            this.p1[i] = 1 << 15;
            this.p2[i] = 1 << 15;
         }
      }

      void setContext(byte ctx)
      {
         this.ctx = (ctx&0xFF) << this.logSize;
      }

      @Override
      public void update(int bit)
      {
         final int idx = this.ctx + this.c1;
         this.p1[idx] -= (((this.p1[idx] - (-bit&0xFFFF)) >> 3) + bit);
         this.p2[idx] -= (((this.p2[idx] - (-bit&0xFFFF)) >> 6) + bit);
         this.c1 = (this.c1<<1) + bit;

         if (this.c1 >= this.size)
            this.c1 = 1;
      }

      @Override
      public int get()
      {
         final int idx = this.ctx + this.c1;
         return (this.p1[idx] + this.p2[idx]) >>> 5;
      }
   }
}
