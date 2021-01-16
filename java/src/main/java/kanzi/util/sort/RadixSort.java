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

package kanzi.util.sort;

import kanzi.ByteSorter;
import kanzi.IntSorter;


// Fast implementation based on lists of buckets per radix
// See http://en.wikipedia.org/wiki/Radix_sort
// Radix sort complexity is O(kn) for n keys with (max) k digits per key
public final class RadixSort implements IntSorter, ByteSorter
{
   private final int bitsRadix;
   private final int[][] buffers;


   public RadixSort()
   {
      this.bitsRadix = 4; // radix of 16
      this.buffers = new int[8][256];
   }


   public RadixSort(int bitsRadix)
   {
      if ((bitsRadix != 4) && (bitsRadix != 8))
         throw new IllegalArgumentException("Invalid radix value (must be 4 or 8 bits)");

      this.bitsRadix = bitsRadix;
      this.buffers = new int[8][256];
   }


   @Override
   public boolean sort(int[] input, int blkptr, int count)
   {
      if ((blkptr < 0) || (count <= 0) || (blkptr+count > input.length))
          return false;

      if (count == 1)
         return true;

      return (this.bitsRadix == 4) ? this.sort16(input, blkptr, count) :
         this.sort256(input, blkptr, count);
   }


   private boolean sort16(int[] input, int blkptr, int count)
   {
      final int end = blkptr + count;
      final int[][] buf = this.buffers;
      int max = input[blkptr];

      for (int i=0; i<256; i++)
      {
         buf[0][i] = 0;
         buf[1][i] = 0;
         buf[2][i] = 0;
         buf[3][i] = 0;
         buf[4][i] = 0;
         buf[5][i] = 0;
         buf[6][i] = 0;
         buf[7][i] = 0;
      }

      // Generate histograms
      for (int i=blkptr; i<end; i++)
      {
         int val = input[i];

         if (val > max)
            max = val;

         buf[0][val&0x0F]++;
         val >>= 4;
         buf[1][val&0x0F]++;
         val >>= 4;
         buf[2][val&0x0F]++;
         val >>= 4;
         buf[3][val&0x0F]++;
         val >>= 4;
         buf[4][val&0x0F]++;
         val >>= 4;
         buf[5][val&0x0F]++;
         val >>= 4;
         buf[6][val&0x0F]++;
         val >>= 4;
         buf[7][val&0x0F]++;
      }

      int iter = 1;

      while ((iter < 8) && (max>>(4*iter) > 0))
         iter++;

      // Convert to indices
      for (int j=0; j<iter; j++)
      {
         int sum = 0;
         final int[] buckets = buf[j];

         for (int i=0; i<256; i++)
         {
            final int tmp = buckets[i];
            buckets[i] = sum;
            sum += tmp;
         }
      }

      int[] ptr1 = input;
      int[] ptr2 = new int[count];

      // Sort by current LSB
      for (int j=0; j<iter; j++)
      {
         final int[] buckets = buf[j];
         final int shift = j << 2;

         for (int i=blkptr; i<end; i++)
         {
            final int val = ptr1[i];
            ptr2[buckets[(val>>shift)&0x0F]++] = val;
         }

         final int[] t = ptr1;
         ptr1 = ptr2;
         ptr2 = t;
      }

      if ((iter & 1) == 1)
         System.arraycopy(ptr1, 0, input, blkptr, count);

      return true;
   }


   private boolean sort256(int[] input, int blkptr, int count)
   {
      final int end = blkptr + count;
      final int[][] buf = this.buffers;
      int max = input[blkptr];

      for (int i=0; i<256; i++)
      {
         buf[0][i] = 0;
         buf[1][i] = 0;
         buf[2][i] = 0;
         buf[3][i] = 0;
      }

      // Generate histograms
      for (int i=blkptr; i<end; i++)
      {
         int val = input[i];

         if (val > max)
            max = val;

         buf[0][val&0xFF]++;
         val >>= 8;
         buf[1][val&0xFF]++;
         val >>= 8;
         buf[2][val&0xFF]++;
         val >>= 8;
         buf[3][val&0xFF]++;
      }

      int iter = 1;

      while ((iter < 4) && (max>>(8*iter) > 0))
         iter++;

      // Convert to indices
      for (int j=0; j<iter; j++)
      {
         int sum = 0;
         final int[] buckets = buf[j];

         for (int i=0; i<256; i++)
         {
            final int tmp = buckets[i];
            buckets[i] = sum;
            sum += tmp;
         }
      }

      int[] ptr1 = input;
      int[] ptr2 = new int[count];

      // Sort by current LSB
      for (int j=0; j<iter; j++)
      {
         final int[] buckets = buf[j];
         final int shift = j << 3;

         for (int i=blkptr; i<end; i++)
         {
            final int val = ptr1[i];
            ptr2[buckets[(val>>shift)&0xFF]++] = val;
         }

         final int[] t = ptr1;
         ptr1 = ptr2;
         ptr2 = t;
      }

      if ((iter & 1) == 1)
         System.arraycopy(ptr1, 0, input, blkptr, count);

      return true;
   }


   @Override
   public boolean sort(byte[] input, int blkptr, int count)
   {
      if ((blkptr < 0) || (count <= 0) || (blkptr+count > input.length))
          return false;

      if (count == 1)
         return true;

      return (this.bitsRadix == 4) ? this.sort16(input, blkptr, count) :
         this.sort256(input, blkptr, count);
   }


   private boolean sort16(byte[] input, int blkptr, int count)
   {
      final int end = blkptr + count;
      final int[] buf0 = this.buffers[0];
      final int[] buf1 = this.buffers[1];

      for (int i=0; i<256; i++)
      {
         buf0[i] = 0;
         buf1[i] = 0;
      }

      // Generate histograms
      for (int i=blkptr; i<end; i++)
      {
         int val = input[i];
         buf0[val&0x0F]++;
         val >>= 4;
         buf1[val&0x0F]++;
      }

      // Convert to indices
      for (int j=0; j<2; j++)
      {
         int sum = 0;
         final int[] buckets = this.buffers[j];

         for (int i=0; i<256; i++)
         {
            final int tmp = buckets[i];
            buckets[i] = sum;
            sum += tmp;
         }
      }

      byte[] ptr1 = input;
      byte[] ptr2 = new byte[count];

      // Sort by current LSB
      for (int j=0; j<2; j++)
      {
         final int[] buckets = this.buffers[j];
         final int shift = j << 2;

         for (int i=blkptr; i<end; i++)
         {
            final int val = ptr1[i];
            ptr2[buckets[(val>>shift)&0x0F]++] = (byte) val;
         }

         final byte[] t = ptr1;
         ptr1 = ptr2;
         ptr2 = t;
      }

      return true;
   }


   // Similar to bucket sort
   private boolean sort256(byte[] input, int blkptr, int count)
   {
      return new BucketSort().sort(input, blkptr, count);
   }

}
