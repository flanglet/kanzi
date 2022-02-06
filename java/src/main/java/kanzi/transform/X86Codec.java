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


// Adapted from MCM: https://github.com/mathieuchartier/mcm/blob/master/X86Binary.hpp
public class X86Codec implements ByteTransform
{
   private static final int MASK_JUMP = 0xFE;
   private static final int INSTRUCTION_JUMP = 0xE8;
   private static final int INSTRUCTION_JCC = 0x80;
   private static final int PREFIX_JCC = 0x0F;
   private static final int MASK_JCC = 0xF0;
   private static final int MASK_ADDRESS = 0xD5;
   private static final byte ESCAPE = (byte) 0xF5;

   private Map<String, Object> ctx;


   public X86Codec()
   {
   }


   public X86Codec(Map<String, Object> ctx)
   {
      this.ctx = ctx;
   }


   @Override
   public boolean forward(SliceByteArray input, SliceByteArray output)
   {
      if (input.length == 0)
         return true;

      if (input.array == output.array)
         return false;

      final int count = input.length;

      if (output.length - output.index < getMaxEncodedLength(count))
         return false;

      // Aliasing
      final byte[] src = input.array;
      final byte[] dst = output.array;
      final int end = count - 8;

      if (this.ctx != null)
      {
         Global.DataType dt = (Global.DataType) this.ctx.getOrDefault("dataType",
            Global.DataType.UNDEFINED);

         if ((dt != Global.DataType.UNDEFINED) && (dt != Global.DataType.X86))
            return false;
      }

      if (this.isExeBlock(src, input.index, input.index+end, count) == false)
         return false;

      if (this.ctx != null)
         this.ctx.put("dataType", Global.DataType.X86);

      int srcIdx = input.index;
      int dstIdx = output.index;

      while (srcIdx < end)
      {
         dst[dstIdx++] = src[srcIdx++];

         // Relative jump ?
         if ((src[srcIdx-1] & MASK_JUMP) != INSTRUCTION_JUMP)
            continue;

         final byte cur = src[srcIdx];

         if ((cur == 0) || (cur == 1) || (cur == ESCAPE))
         {
            // Conflict prevents encoding the address. Emit escape symbol
            dst[dstIdx]   = ESCAPE;
            dst[dstIdx+1] = cur;
            srcIdx++;
            dstIdx += 2;
            continue;
         }

         final byte sgn = src[srcIdx+3];

         // Invalid sign of jump address difference => false positive ?
         if ((sgn != 0) && (sgn != -1))
            continue;

         int addr = (0xFF & src[srcIdx]) | ((0xFF & src[srcIdx+1]) << 8) |
                   ((0xFF & src[srcIdx+2]) << 16) | ((0xFF & sgn) << 24);

         addr += srcIdx;
         dst[dstIdx]   = (byte) (sgn+1);
         dst[dstIdx+1] = (byte) (MASK_ADDRESS ^ (0xFF & (addr >> 16)));
         dst[dstIdx+2] = (byte) (MASK_ADDRESS ^ (0xFF & (addr >>  8)));
         dst[dstIdx+3] = (byte) (MASK_ADDRESS ^ (0xFF &  addr));
         srcIdx += 4;
         dstIdx += 4;
      }

      while (srcIdx < count)
         dst[dstIdx++] = src[srcIdx++];

      input.index = srcIdx;
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

      if (input.index + count > input.array.length)
         return false;

      // Aliasing
      final byte[] src = input.array;
      final byte[] dst = output.array;
      int srcIdx = input.index;
      int dstIdx = output.index;
      final int end = count - 8;

      while (srcIdx < end)
      {
         dst[dstIdx++] = src[srcIdx++];

         // Relative jump ?
         if ((src[srcIdx-1] & MASK_JUMP) != INSTRUCTION_JUMP)
            continue;

         if (src[srcIdx] == ESCAPE)
         {
            // Not an encoded address. Skip escape symbol
            srcIdx++;
            continue;
         }

         final int sgn = src[srcIdx] - 1;

         // Invalid sign of jump address difference => false positive ?
         if ((sgn != 0) && (sgn != -1))
            continue;

         int addr = (0xFF & (MASK_ADDRESS ^ src[srcIdx+3]))        |
                   ((0xFF & (MASK_ADDRESS ^ src[srcIdx+2])) <<  8) |
                   ((0xFF & (MASK_ADDRESS ^ src[srcIdx+1])) << 16) |
                   ((0xFF & sgn) << 24);

         addr -= dstIdx;
         dst[dstIdx]   = (byte)  addr;
         dst[dstIdx+1] = (byte) (addr >>  8);
         dst[dstIdx+2] = (byte) (addr >> 16);
         dst[dstIdx+3] = (byte)  sgn;
         srcIdx += 4;
         dstIdx += 4;
      }

      while (srcIdx < count)
         dst[dstIdx++] = src[srcIdx++];

      input.index = srcIdx;
      output.index = dstIdx;
      return true;
   }


   @Override
   public int getMaxEncodedLength(int srcLen)
   {
      // Since we do not check the dst index for each byte (for speed purpose)
      // allocate some extra buffer for incompressible data.
      if (srcLen >= 1<<30)
         return srcLen;

      return (srcLen <= 512) ? srcLen+32 : srcLen+srcLen/16;
   }


   private boolean isExeBlock(byte[] src, int start, int end, int count)
   {
      int jumps = 0;

      for (int i=start; i<end; i++)
      {
         if ((src[i] & MASK_JUMP) == INSTRUCTION_JUMP)
         {
            if ((src[i+4] == 0) || (src[i+4] == -1))
            {
               // Count valid relative jumps (E8/E9 .. .. .. 00/FF)
               jumps++;
            }
         }
         else if ((src[i] == PREFIX_JCC) && ((src[i+1] & MASK_JCC) == INSTRUCTION_JCC))
         {
            // Count relative conditional jumps (0x0F 0x8.)
            jumps++;
         }
      }

      // Number of jump instructions too small => either not a binary
      // or not worth the change => skip. Very crude filter obviously.
      // Also, binaries usually have a lot of 0x88..0x8C (MOV) instructions.
      return jumps >= (count>>7);
   }
}