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

import kanzi.ByteTransform;
import kanzi.SliceByteArray;


// Encapsulates a sequence of transforms or functions in a function
public class Sequence implements ByteTransform
{
   private static final int SKIP_MASK = 0xFF;

   private final ByteTransform[] transforms; // transforms or functions
   private byte skipFlags; // skip transforms


   public Sequence(ByteTransform[] transforms)
   {
      if (transforms == null)
         throw new NullPointerException("Invalid null transforms parameter");

      if ((transforms.length == 0) || (transforms.length > 8))
         throw new NullPointerException("Only 1 to 8 transforms allowed");

      this.transforms = transforms;
   }


   @Override
   public boolean forward(SliceByteArray src, SliceByteArray dst)
   {
      int count = src.length;

      if ((count < 0) || (count+src.index > src.array.length))
         return false;

      this.skipFlags = (byte) SKIP_MASK;

      if (src.length == 0)
         return true;

      final int blockSize = count;
      final int requiredSize = this.getMaxEncodedLength(count);
      SliceByteArray[] sa = new SliceByteArray[] { src, dst };
      SliceByteArray sa1 = sa[0];
      SliceByteArray sa2 = sa[1];
      int saIdx = 0;

      // Process transforms sequentially
      for (int i=0; i<this.transforms.length; i++)
      {
         // Check that the output buffer has enough room. If not, allocate a new one.
         if (sa2.length < requiredSize)
         {
            sa2.length = requiredSize;

            if (sa2.array.length < sa2.length)
               sa2.array = new byte[sa2.length];
         }

         final int savedIIdx = sa1.index;
         final int savedOIdx = sa2.index;
         final int savedLength = sa1.length;
         sa1.length = count;

         // Apply forward transform
         if (this.transforms[i].forward(sa1, sa2) == false)
         {
            // Transform failed. Either it does not apply to this type
            // of data or a recoverable error occurred => revert
            if (sa1.array != sa2.array)
               System.arraycopy(sa1.array, savedIIdx, sa2.array, savedOIdx, count);

            sa1.index = savedIIdx;
            sa2.index = savedOIdx;
            sa1.length = savedLength;
            continue;
         }

         this.skipFlags &= ~(1<<(7-i));
         count = sa2.index - savedOIdx;
         sa1.index = savedIIdx;
         sa2.index = savedOIdx;
         sa1.length = savedLength;
         saIdx ^= 1;
         sa1 = sa[saIdx];
         sa2 = sa[saIdx^1];
      }

      if (saIdx != 1)
         System.arraycopy(sa[0].array, sa[0].index, sa[1].array, sa[1].index, count);

      src.index += blockSize;
      dst.index += count;
      return this.skipFlags != SKIP_MASK;
   }


   @Override
   public boolean inverse(SliceByteArray src, SliceByteArray dst)
   {
      if (src.length == 0)
         return true;

      int count = src.length;

      if ((count < 0) || (count+src.index > src.array.length))
         return false;

      if (this.skipFlags == SKIP_MASK)
      {
         if (src.array != dst.array)
            System.arraycopy(src.array, src.index, dst.array, dst.index, count);

         src.index += count;
         dst.index += count;
         return true;
      }

      final int blockSize = count;
      boolean res = true;
      SliceByteArray[] sa = new SliceByteArray[] { src, dst };
      int saIdx = 0;

      // Process transforms sequentially in reverse order
      for (int i=this.transforms.length-1; i>=0; i--)
      {
         if ((this.skipFlags & (1<<(7-i))) != 0)
            continue;

         SliceByteArray sa1 = sa[saIdx];
         saIdx ^= 1;
         SliceByteArray sa2 = sa[saIdx];
         final int savedIIdx = sa1.index;
         final int savedOIdx = sa2.index;
         final int savedILen = sa1.length;
         final int savedOLen = sa2.length;

         // Apply inverse transform
         sa1.length = count;
         sa2.length = dst.length;

         if (sa2.array.length < sa2.length)
            sa2.array = new byte[sa2.length];

         res = this.transforms[i].inverse(sa1, sa2);
         count = sa2.index - savedOIdx;
         sa1.index = savedIIdx;
         sa2.index = savedOIdx;
         sa1.length = savedILen;
         sa2.length = savedOLen;

         // All inverse transforms must succeed
         if (res == false)
            break;
      }

      if (saIdx != 1)
         System.arraycopy(sa[0].array, sa[0].index, sa[1].array, sa[1].index, count);

      if (count > dst.length)
         return false;

      src.index += blockSize;
      dst.index += count;
      return res;
   }


   @Override
   public int getMaxEncodedLength(int srcLength)
   {
      int requiredSize = srcLength;

      for (ByteTransform transform : this.transforms)
      {
         final int reqSize = transform.getMaxEncodedLength(requiredSize);

         if (reqSize > requiredSize)
            requiredSize = reqSize;
      }

      return requiredSize;
   }


   public int getNbFunctions()
   {
      return this.transforms.length;
   }


   public byte getSkipFlags()
   {
      return this.skipFlags;
   }


   public boolean setSkipFlags(byte flags)
   {
      this.skipFlags = flags;
      return true;
   }

}
