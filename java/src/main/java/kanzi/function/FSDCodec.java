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
import kanzi.Global;
import kanzi.SliceByteArray;

// Fixed Step Delta codec
// Decorrelate values separated by a constant distance (step) and encode residuals
public class FSDCodec implements ByteFunction
{
   private static final int MIN_LENGTH = 4096;
   private static final byte ESCAPE_TOKEN = (byte) 255;
   
   private final boolean isFast;
   
   
   public FSDCodec()      
   {      
      this.isFast = true;
   }

   
   public FSDCodec(Map<String, Object> ctx)      
   {  
      this.isFast = (Boolean) ctx.getOrDefault("fullFSD", false);
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
      
      final byte[] src = input.array;
      final byte[] dst = output.array;
      int srcIdx = input.index;
      int dstIdx = output.index;
      final int srcEnd = srcIdx + count;
      final int dstEnd = dstIdx + this.getMaxEncodedLength(count);
      final int count5 = count / 5;
      final int count10 = count / 10;
      int idx1 = 0;
      int idx2 = count5 * 1;
      int idx3 = count5 * 2;
      int idx4 = count5 * 3;
      int idx8 = count5 * 4;
      
      // Check several step values on a sub-block (no memory allocation)
      // Sample 2 sub-blocks
      for (int i=3*count5; i<(3*count5)+count10; i++)
      {
         final int b = src[i];
         dst[idx1++] = (byte) (b ^ src[i-1]);
         dst[idx2++] = (byte) (b ^ src[i-2]);
         dst[idx3++] = (byte) (b ^ src[i-3]);
         dst[idx4++] = (byte) (b ^ src[i-4]);
         dst[idx8++] = (byte) (b ^ src[i-8]);
      }
      
      for (int i=1*count5+count10; i<2*count5; i++)
      {
         final int b = src[i];
         dst[idx1++] = (byte) (b ^ src[i-1]);
         dst[idx2++] = (byte) (b ^ src[i-2]);
         dst[idx3++] = (byte) (b ^ src[i-3]);
         dst[idx4++] = (byte) (b ^ src[i-4]);
         dst[idx8++] = (byte) (b ^ src[i-8]);
      }
      
      // Find if entropy is lower post transform 
      int[] histo = new int[256];
      int[] ent = new int[6];
      ent[0] = Global.computeFirstOrderEntropy1024(src, count/3, count/3, histo);
      ent[1] = Global.computeFirstOrderEntropy1024(dst,        0, count5, histo);
      ent[2] = Global.computeFirstOrderEntropy1024(dst, 1*count5, count5, histo);
      ent[3] = Global.computeFirstOrderEntropy1024(dst, 2*count5, count5, histo);
      ent[4] = Global.computeFirstOrderEntropy1024(dst, 3*count5, count5, histo);
      ent[5] = Global.computeFirstOrderEntropy1024(dst, 4*count5, count5, histo);
      
      int minIdx = 0;

      for (int i=1; i<ent.length; i++)
      {
         if (ent[i] < ent[minIdx])
            minIdx = i;
      }

      // If not 'better enough', quick exit
      if ((this.isFast == true) && (ent[minIdx] >= ((123*ent[0])>>7)))
         return false;

      // Emit step value
      final int dist = (minIdx <= 4) ? minIdx : 8;      
      dst[dstIdx++] = (byte) dist;
 
      // Emit first bytes
      for (int i=0; i<dist; i++)
         dst[dstIdx++] = src[srcIdx++];
       
      // Emit modified bytes
      while ((srcIdx < srcEnd) && (dstIdx < dstEnd))
      {
         final int delta = (src[srcIdx]&0xFF) - (src[srcIdx-dist]&0xFF); 
         
         if ((delta < -127) || (delta > 127)) 
         {
            if (dstIdx == dstEnd-1)
               break;
               
            // Skip delta, direct encode
            dst[dstIdx++] = ESCAPE_TOKEN;   
            dst[dstIdx++] = (byte) src[srcIdx];
            srcIdx++;
            continue;
         }

         dst[dstIdx++] = (byte) ((delta>>31) ^ (delta<<1)); // zigzag encode delta
         srcIdx++;
      }

      if (srcIdx != srcEnd)
         return false;

      // Extra check that the transform makes sense
      final int length = (this.isFast == true) ? dstIdx>>1 : dstIdx;
      final int entropy = Global.computeFirstOrderEntropy1024(dst, (dstIdx-length)>>1, length, histo);

      if (entropy >= ent[0])
         return false;
    
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
      final byte[] src = input.array;
      final byte[] dst = output.array;
      int srcIdx = input.index;
      int dstIdx = output.index; 
      final int srcEnd = srcIdx + count;
      
      // Retrieve step value
      final int dist = src[srcIdx++];
      
      // Sanity check
      if ((dist < 1) || ((dist > 4) && (dist != 8)))
         return false;
      
      // Copy first bytes
      for (int i=0; i<dist; i++)
         dst[dstIdx++] = src[srcIdx++];

      // Invert bytes
      while (srcIdx < srcEnd)
      {
         final byte b = src[srcIdx];
         
         if (b == ESCAPE_TOKEN)
         {
            if (srcIdx == srcEnd-1)
               break;

            dst[dstIdx++] = src[srcIdx+1];
            srcIdx += 2;
            continue;
         }
         
         final int diff = (src[srcIdx]>>>1) ^ -(src[srcIdx]&1); // zigzag decode
         dst[dstIdx] = (byte) ((dst[dstIdx-dist]&0xFF) + diff);
         srcIdx++;
         dstIdx++;
      }
      
      input.index = srcIdx;
      output.index = dstIdx;
      return srcIdx == srcEnd;         
   }

   
   @Override
   public int getMaxEncodedLength(int srcLength)
   {
      return srcLength + Math.max(64, (srcLength>>4)); // limit expansion
   } 
}
