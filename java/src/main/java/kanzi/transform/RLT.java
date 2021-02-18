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
import kanzi.Global;
import kanzi.SliceByteArray;


// Implementation of an escaped RLE
// Run length encoding:
// RUN_LEN_ENCODE1 = 224 => RUN_LEN_ENCODE2 = 31*224 = 6944
// 4    <= runLen < 224+4      -> 1 byte
// 228  <= runLen < 6944+228   -> 2 bytes
// 7172 <= runLen < 65535+7172 -> 3 bytes

public class RLT implements ByteTransform
{
   private static final int RUN_LEN_ENCODE1 = 224; // used to encode run length
   private static final int RUN_LEN_ENCODE2 = (255-RUN_LEN_ENCODE1) << 8; // used to encode run length
   private static final int RUN_THRESHOLD = 3;
   private static final int MAX_RUN = 0xFFFF + RUN_LEN_ENCODE2 + RUN_THRESHOLD - 1;
   private static final int MAX_RUN4 = MAX_RUN - 4;

   private final int[] freqs;


   public RLT()
   {
      this.freqs = new int[256];
   }


   public RLT(Map<String, Object> ctx)
   {
      this.freqs = new int[256];
   }


   @Override
   public boolean forward(SliceByteArray input, SliceByteArray output)
   {
      if (input.length == 0)
         return true;

      if (input.length < 16)
         return false;

      if (input.array == output.array)
         return false;

      final int count = input.length;

      if (output.length - output.index < getMaxEncodedLength(count))
         return false;

      final byte[] src = input.array;
      final byte[] dst = output.array;

      // Find escape symbol
      int srcIdx = input.index;
      int dstIdx = output.index;
      final int srcEnd = srcIdx + count;
      final int srcEnd4 = srcEnd - 4;
      final int dstEnd = dst.length;

      for (int i=1; i<256; i++)
         this.freqs[i] = 0;

      Global.computeHistogramOrder0(src, srcIdx, srcEnd, this.freqs, false);
      int minIdx = 0;

      // Select escape symbol
      if (this.freqs[minIdx] > 0)
      {
         for (int i=1; i<256; i++)
         {
            if (this.freqs[i] < this.freqs[minIdx])
            {
               minIdx = i;

               if (this.freqs[i] == 0)
                  break;
            }
         }
      }

      boolean res = true;
      byte escape = (byte) minIdx;
      int run = 0;
      byte prev = src[srcIdx++];
      dst[dstIdx++] = escape;
      dst[dstIdx++] = prev;

      if (prev == escape)
         dst[dstIdx++] = 0;

      // Main loop
      while (true)
      {
         if (prev == src[srcIdx])
         {
            srcIdx++; run++;

            if (prev == src[srcIdx])
            {
               srcIdx++; run++;

               if (prev == src[srcIdx])
               {
                  srcIdx++; run++;

                  if (prev == src[srcIdx])
                  {
                     srcIdx++; run++;

                     if ((run < MAX_RUN4) && (srcIdx < srcEnd4))
                        continue;
                  }
               }
            }
         }

         if (run > RUN_THRESHOLD)
         {
            final int dIdx = emitRunLength(dst, dstIdx, dstEnd, run, escape, prev);

            if (dIdx < 0)
            {
               res = false;
               break;
            }

            dstIdx = dIdx;
         }
         else if (prev != escape)
         {
            if (dstIdx+run >= dstEnd)
            {
               res = false;
               break;
            }

            if (run-- > 0)
               dst[dstIdx++] = prev;

            while (run-- > 0)
               dst[dstIdx++] = prev;
         }
         else // escape literal
         {
            if (dstIdx+2*run >= dstEnd)
            {
               res = false;
               break;
            }

            while (run-- > 0)
            {
               dst[dstIdx++] = escape;
               dst[dstIdx++] = 0;
            }
         }

         prev = src[srcIdx];
         srcIdx++;
         run = 1;

         if (srcIdx >= srcEnd4)
            break;
      }

      if (res == true)
      {
         // run == 1
         if (prev != escape)
         {
            if (dstIdx+run < dstEnd)
            {
               while (run-- > 0)
                  dst[dstIdx++] = prev;
            }
         }
         else // escape literal
         {
            if (dstIdx+2*run < dstEnd)
            {
               while (run-- > 0)
               {
                  dst[dstIdx++] = escape;
                  dst[dstIdx++] = 0;
               }
            }
         }

         // Emit the last few bytes
         while ((srcIdx < srcEnd) && (dstIdx < dstEnd))
         {
            if (src[srcIdx] == escape)
            {
               if (dstIdx+2 >= dstEnd)
               {
                  res = false;
                  break;
               }

               dst[dstIdx++] = escape;
               dst[dstIdx++] = 0;
               srcIdx++;
               continue;
            }

            dst[dstIdx++] = src[srcIdx++];
         }

         res &= (srcIdx == srcEnd);
      }

      res &= ((dstIdx-output.index) < (srcIdx-input.index));
      input.index = srcIdx;
      output.index = dstIdx;
      return res;
   }


   private static int emitRunLength(byte[] dst, int dstIdx, int dstEnd, int run, byte escape, byte val)
   {
      dst[dstIdx++] = val;

      if (val == escape)
         dst[dstIdx++] = (byte) 0;

      dst[dstIdx++] = escape;
      run -= RUN_THRESHOLD;

      // Encode run length
      if (run >= RUN_LEN_ENCODE1)
      {
         if (run < RUN_LEN_ENCODE2)
         {
            if (dstIdx >= dstEnd-2)
               return -1;

            run -= RUN_LEN_ENCODE1;
            dst[dstIdx++] = (byte) (RUN_LEN_ENCODE1 + (run>>8));
         }
         else
         {
            if (dstIdx >= dstEnd-3)
               return -1;

            run -= RUN_LEN_ENCODE2;
            dst[dstIdx++] = (byte) 0xFF;
            dst[dstIdx++] = (byte) (run>>8);
         }
      }

      dst[dstIdx] = (byte) run;
      return dstIdx+1;
   }


   @Override
   public boolean inverse(SliceByteArray input, SliceByteArray output)
   {
      if (input.length == 0)
         return true;

      if (input.array == output.array)
         return false;

      final int count = input.length;
      int srcIdx = input.index;
      int dstIdx = output.index;
      final byte[] src = input.array;
      final byte[] dst = output.array;
      final int srcEnd = srcIdx + count;
      final int dstEnd = dst.length;
      boolean res = true;
      byte escape = src[srcIdx++];

      if (src[srcIdx] == escape)
      {
         srcIdx++;

         // The data cannot start with a run but may start with an escape literal
         if ((srcIdx < srcEnd) && (src[srcIdx] != 0))
            return false;

         dst[dstIdx++] = escape;
         srcIdx++;
      }

      // Main loop
      while (srcIdx < srcEnd)
      {
         if (src[srcIdx] != escape)
         {
            // Literal
            if (dstIdx >= dstEnd)
               break;

            dst[dstIdx++] = src[srcIdx++];
            continue;
         }

         srcIdx++;

         if (srcIdx >= srcEnd)
         {
            res = false;
            break;
         }

         final byte val = dst[dstIdx-1];
         int run = src[srcIdx++] & 0xFF;

         if (run == 0)
         {
            // Just an escape literal, not a run
            if (dstIdx >= dstEnd)
               break;

            dst[dstIdx++] = escape;
            continue;
         }

         // Decode run length
         if (run == 0xFF)
         {
            if (srcIdx >= srcEnd-1)
            {
               res = false;
               break;
            }

            run = (((src[srcIdx]&0xFF))<<8) | (src[srcIdx+1]&0xFF);
            srcIdx += 2;
            run += RUN_LEN_ENCODE2;
         }
         else if (run >= RUN_LEN_ENCODE1)
         {
            if (srcIdx >= srcEnd)
            {
               res = false;
               break;
            }

            run = ((run-RUN_LEN_ENCODE1)<<8) | (src[srcIdx++]&0xFF);
            run += RUN_LEN_ENCODE1;
         }

         run += (RUN_THRESHOLD-1);

         if ((dstIdx+run >= dstEnd) || (run > MAX_RUN))
         {
            res = false;
            break;
         }

         // Emit 'run' times the previous byte
         while (run >= 4)
         {
            dst[dstIdx]   = val;
            dst[dstIdx+1] = val;
            dst[dstIdx+2] = val;
            dst[dstIdx+3] = val;
            dstIdx += 4;
            run -= 4;
         }

         while (run-- > 0)
            dst[dstIdx++] = val;
      }

      res &= (srcIdx == srcEnd);
      input.index = srcIdx;
      output.index = dstIdx;
      return res;
   }


   @Override
   public int getMaxEncodedLength(int srcLen)
   {
      return (srcLen <= 512) ? srcLen + 32 : srcLen;
   }
}