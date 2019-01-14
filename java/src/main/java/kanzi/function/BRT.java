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


import kanzi.ByteFunction;
import kanzi.SliceByteArray;

// Behemoth Rank Transform
// A strong rank transform based on https://github.com/loxxous/Behemoth-Rank-Coding
// by Lucas Marsh. Typically used post BWT to reduce the variance of the data 
// prior to entropy coding.

public final class BRT implements ByteFunction
{
   private static final int HEADER_SIZE = 1024 + 1; // 4*256 freqs + 1 nbSymbols
   
   private final int[] buckets;
   private final int[] bucketEnds;
   private final int[] freqs;
   private final int[] ranks;
   private final byte[] sortedMap;

   
   public BRT()
   {
      this.buckets = new int[256];
      this.bucketEnds = new int[256];
      this.freqs = new int[256];
      this.ranks = new int[256];
      this.sortedMap = new byte[256];
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

      final byte[] src = input.array;
      final byte[] dst = output.array;
      
      for (int i=0; i<256; i++) 
      {
         this.freqs[i] = 0;
         this.buckets[i] = 0;
         this.ranks[i] = i;
      }
      
      int nbSymbols = this.computeFrequencies(input);
      final int idx = encodeHeader(new Header(dst, output.index, this.freqs, count, nbSymbols));
      
      if (idx < 0)
         return false;
      
      output.index = idx;
      sortMap(this.freqs, this.sortedMap);

      for (int i=0, bucketPos=0; i<nbSymbols; i++) 
      {
         final int val = this.sortedMap[i] & 0xFF;
         this.buckets[val] = bucketPos;
         bucketPos += this.freqs[val];
      }

      final int end = input.index + count;
       
      for (int i=input.index; i<end; i++) 
      {
         final int s = src[i] & 0xFF; 
         final int r = this.ranks[s]; 
         dst[output.index+this.buckets[s]] = (byte) r;
         this.buckets[s]++;
         
         if (r != 0) 
         {
            for (int j=0; j<256; j+=8)
            {
               this.ranks[j]   -= ((this.ranks[j]   - r) >> 31);
               this.ranks[j+1] -= ((this.ranks[j+1] - r) >> 31);
               this.ranks[j+2] -= ((this.ranks[j+2] - r) >> 31);
               this.ranks[j+3] -= ((this.ranks[j+3] - r) >> 31);
               this.ranks[j+4] -= ((this.ranks[j+4] - r) >> 31);
               this.ranks[j+5] -= ((this.ranks[j+5] - r) >> 31);
               this.ranks[j+6] -= ((this.ranks[j+6] - r) >> 31);
               this.ranks[j+7] -= ((this.ranks[j+7] - r) >> 31);
            }
            
            this.ranks[s] = 0;
         }
      }
   
      input.index += count;
      output.index += count;
      return true;
   }

   
   private int computeFrequencies(SliceByteArray input)
   {
      int n = input.index;
      int count = input.length;
      byte[] src = input.array;
      int nbSymbols = 0;

      // Slow loop
      for ( ; n<count; n++) 
      {
         final int s = src[n] & 0xFF;
         
         if (this.freqs[s] == 0)
         {
            this.ranks[s] = nbSymbols;
            nbSymbols++;
            
            if (nbSymbols == 256)
               break;
         }
         
         this.freqs[s]++;
      }
      
      // Fast loop
      for ( ; n<count; n++) 
         this.freqs[src[n]&0xFF]++;
      
      return nbSymbols;
   }

   
   private static void sortMap(int[] freqs, byte[] map) 
   {
      int[] newFreqs = new int[256];
      System.arraycopy(freqs, 0, newFreqs, 0, 256);

      for (int j=0; j<256; j++) 
      {
         int max = newFreqs[0];
         int bsym = 0;
         
         for (int i=1; i<256; i++) 
         {
            if (newFreqs[i] <= max) 
               continue;
           
            bsym = i;
            max = newFreqs[i];
         }

         if (max == 0) 
            break;
            
         map[j] = (byte) bsym;      
         newFreqs[bsym] = 0;
      }
   }

  
   @Override
   public boolean inverse(SliceByteArray input, SliceByteArray output)
    {
      if (input.length == 0)
         return true;
      
      if (input.array == output.array)
         return false;
   
      final byte[] src = input.array;
      final byte[] dst = output.array;
      
      for (int i=0; i<256; i++) 
      {
         this.freqs[i] = 0;
         this.buckets[i] = 0;
         this.bucketEnds[i] = 0;
         this.ranks[i] = i;
      }
      
      Header h = new Header(src, input.index, this.freqs, 0, 0);
      final int idx = decodeHeader(h);     
      
      if (idx < 0)
         return false;
      
      input.index = idx;
      final int count = input.length - idx;      
	   int total = h.total[0];

      if (total != count) 
         return false;
	
      int nbSymbols = h.nbSymbols[0];

      if (nbSymbols == 0)
         return true;

      sortMap(this.freqs, this.sortedMap);

      for (int i=0, bucketPos=0; i<nbSymbols; i++) 
      {
         final int s = this.sortedMap[i] & 0xFF;
         this.ranks[src[input.index+bucketPos]&0xFF] = s;
         this.buckets[s] = bucketPos + 1;
         bucketPos += this.freqs[s];
         this.bucketEnds[s] = bucketPos;
      }

      int s = this.ranks[0] & 0xFF;
      final int end = output.index + count;
      
      for (int i=output.index; i<end; i++) 
      {
         dst[i] = (byte) s;
         int r = 0xFF;

         if (this.buckets[s] < this.bucketEnds[s]) 
         {
            r = src[input.index+this.buckets[s]] & 0xFF;
            this.buckets[s]++;
            
            if (r == 0)
               continue;
         }

         this.ranks[0] = this.ranks[1];
            
         for (int j=1; j<r; j++)
            this.ranks[j] = this.ranks[j+1];

         this.ranks[r] = s;
         s = this.ranks[0];
      }

      input.index += count;
      output.index += count;
      return true;
   }
   
   
   // Varint encode the frequencies to block
   private static int encodeHeader(Header h)
   {
      final byte[] block = h.block;
      int blkptr = h.blkptr[0];
      final int[] freqs = h.freqs;
      int nbSymbols = h.nbSymbols[0];

      // Require enough space in output block
      if (block.length < blkptr+4*freqs.length+1)
         return -1;
      
      block[blkptr++] = (byte) (nbSymbols-1);
      
      for (int i=0; i<freqs.length; i++)
      {
         int f = freqs[i];
         
         while (f >= 0x80) 
         {
            block[blkptr++] = (byte) (0x80|(f&0x7F));
            f >>= 7;
         }

         block[blkptr++] = (byte) f;
         
         if (freqs[i] > 0)
         {
            nbSymbols--;
            
            if (nbSymbols == 0)
               break;
         }         
      }

      h.blkptr[0] = blkptr;
      return blkptr;
   }
   
   
   // Varint decode the frequencies from block
   private static int decodeHeader(Header h)
   {
      final byte[] block = h.block;
      int blkptr = h.blkptr[0];
      final int[] freqs = h.freqs;
      
      // Require enough space in arrays
      if ((freqs.length < 256) || (block.length == 0))
         return -1;
      
      int nbSymbols = 1 + (block[blkptr++]&0xFF);
      h.nbSymbols[0] = nbSymbols;
      int total = 0;
      
      for (int i=0; i<256; i++)
      {
         int f = block[blkptr++] & 0xFF;
         int res = f & 0x7F;
         int shift = 7;
         
         while ((f >= 0x80) && (shift <= 28))
         {
            f = block[blkptr++] & 0xFF;
            res |= ((f&0x7F) << shift);
            shift += 7;
         }

         if ((freqs[i] == 0) && (res != 0))
         {
            nbSymbols--;
            
            if (nbSymbols == 0)
            {
               freqs[i] = res;         
               total += res;
               break;
            }
         }
         
         freqs[i] = res;         
         total += res;
      }
      
      h.total[0] = total;
      h.blkptr[0] = blkptr;
      return blkptr;
   }

   
   @Override
   public int getMaxEncodedLength(int srcLen)
   {
      return srcLen + HEADER_SIZE;
   }
   
   
   static final class Header
   {
      byte[] block;
      int[] freqs;
      int[] blkptr;
      int[] total;
      int[] nbSymbols;


      public Header(byte[] block, int blkptr, int[] freqs, int total, int nbSymbols)
      {
         this.block = block;
         this.blkptr = new int[] { blkptr };
         this.freqs = freqs;
         this.total = new int[] { total };
         this.nbSymbols = new int[] { nbSymbols };
      }            
   }
}