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

package kanzi.entropy;

import kanzi.ArrayComparator;
import kanzi.OutputBitStream;
import kanzi.BitStreamException;
import kanzi.EntropyEncoder;
import kanzi.Global;
import kanzi.util.sort.QuickSort;


// Implementation of a static Huffman encoder.
// Uses in place generation of canonical codes instead of a tree
public class HuffmanEncoder implements EntropyEncoder
{
   private final OutputBitStream bs;
   private final int[] freqs;
   private final int[] codes;
   private final int[] alphabet;
   private final int[] sranks;  // sorted ranks
   private final int[] buffer;  // temporary data
   private final short[] sizes; // Cache for speed purpose
   private final int chunkSize;


   public HuffmanEncoder(OutputBitStream bitstream) throws BitStreamException
   {
      this(bitstream, HuffmanCommon.MAX_CHUNK_SIZE);
   }


    // The chunk size indicates how many bytes are encoded (per block) before
    // resetting the frequency stats. 
   public HuffmanEncoder(OutputBitStream bitstream, int chunkSize) throws BitStreamException
   {
      if (bitstream == null)
         throw new NullPointerException("Invalid null bitstream parameter");

      if (chunkSize < 1024)
         throw new IllegalArgumentException("The chunk size must be at least 1024");

      if (chunkSize > HuffmanCommon.MAX_CHUNK_SIZE)
         throw new IllegalArgumentException("The chunk size must be at most "+HuffmanCommon.MAX_CHUNK_SIZE);

      this.bs = bitstream;
      this.freqs = new int[256];
      this.sizes = new short[256];
      this.alphabet = new int[256];
      this.sranks = new int[256];
      this.buffer = new int[256];
      this.codes = new int[256];
      this.chunkSize = chunkSize;

      // Default frequencies, sizes and codes
      for (int i=0; i<256; i++)
      {
         this.freqs[i] = 1;
         this.sizes[i] = 8;
         this.codes[i] = i;
      }
   }

    
   // Rebuild Huffman codes
   private int updateFrequencies(int[] frequencies) throws BitStreamException
   {
      if ((frequencies == null) || (frequencies.length != 256))
         return -1;

      int count = 0;

      for (int i=0; i<256; i++)
      {
         this.sizes[i] = 0;
         this.codes[i] = 0;

         if (frequencies[i] > 0)
            this.alphabet[count++] = i;
      }

      EntropyUtils.encodeAlphabet(this.bs, this.alphabet, count);

      // Transmit code lengths only, frequencies and codes do not matter
      // Unary encode the length difference
      this.computeCodeLengths(frequencies, count);     
      ExpGolombEncoder egenc = new ExpGolombEncoder(this.bs, true);
      short prevSize = 2;

      for (int i=0; i<count; i++)
      {
         final short currSize = this.sizes[this.alphabet[i]];
         egenc.encodeByte((byte) (currSize - prevSize));
         prevSize = currSize;
      }

      // Create canonical codes 
      if (HuffmanCommon.generateCanonicalCodes(this.sizes, this.codes, this.alphabet, count) < 0)
         throw new BitStreamException("Could not generate codes: max code length (" +
            HuffmanCommon.MAX_CHUNK_SIZE + ") exceeded",
            BitStreamException.INVALID_STREAM);

      // Pack size and code (size <= MAX_SYMBOL_SIZE bits)
      for (int i=0; i<count; i++)
      {
         final int r = this.alphabet[i];
         this.codes[r] |= (this.sizes[r] << 24);           
      }

      return count;
   }


   // See [In-Place Calculation of Minimum-Redundancy Codes]
   // by Alistair Moffat & Jyrki Katajainen
   private void computeCodeLengths(int[] frequencies, int count) 
   {  
      if (count == 1)
      {
         this.sranks[0] = this.alphabet[0];
         this.sizes[this.alphabet[0]] = 1;
         return;
      }

      // Sort ranks by increasing frequency
      System.arraycopy(this.alphabet, 0, this.sranks, 0, count);

      // Sort by increasing frequencies (first key) and increasing value (second key)
      new QuickSort(new FrequencyArrayComparator(frequencies)).sort(this.sranks, 0, count);

      for (int i=0; i<count; i++)               
         this.buffer[i] = frequencies[this.sranks[i]];

      computeInPlaceSizesPhase1(this.buffer, count);
      computeInPlaceSizesPhase2(this.buffer, count);

      for (int i=0; i<count; i++) 
      {
         short codeLen = (short) this.buffer[i];

         if ((codeLen <= 0) || (codeLen > HuffmanCommon.MAX_CHUNK_SIZE))
            throw new IllegalArgumentException("Could not generate codes: max code " +
               "length (" + HuffmanCommon.MAX_CHUNK_SIZE + " bits) exceeded");

         this.sizes[this.sranks[i]] = codeLen;
      }
   }
    
    
   static void computeInPlaceSizesPhase1(int[] data, int n) 
   {
      for (int s=0, r=0, t=0; t<n-1; t++) 
      {
         int sum = 0;

         for (int i=0; i<2; i++) 
         {
            if ((s>=n) || ((r<t) && (data[r]<data[s]))) 
            {
               sum += data[r];
               data[r] = t;
               r++;
            }
            else 
            {
               sum += data[s];

               if (s > t) 
                  data[s] = 0;

               s++;
            }
         }

         data[t] = sum;
      }
   }

    
   static void computeInPlaceSizesPhase2(int[] data, int n) 
   {
      int levelTop = n - 2; //root
      int depth = 1;
      int i = n;
      int totalNodesAtLevel =  2;

      while (i > 0) 
      {
         int k = levelTop;

         while ((k>0) && (data[k-1]>=levelTop))
            k--;

         final int internalNodesAtLevel = levelTop - k;
         final int leavesAtLevel = totalNodesAtLevel - internalNodesAtLevel;

         for (int j=0; j<leavesAtLevel; j++)
            data[--i] = depth;

         totalNodesAtLevel = internalNodesAtLevel << 1;
         levelTop = k;
         depth++;
      }
   }


   // Dynamically compute the frequencies for every chunk of data in the block   
   @Override
   public int encode(byte[] array, int blkptr, int length)
   {
      if ((array == null) || (blkptr+length > array.length) || (blkptr < 0) || (length < 0))
         return -1;

      if (length == 0)
         return 0;

      final int[] frequencies = this.freqs;
      final int end = blkptr + length;
      final int sz = (this.chunkSize == 0) ? length : this.chunkSize;
      int startChunk = blkptr;

      while (startChunk < end)
      {
         // Rebuild Huffman codes
         final int endChunk = (startChunk+sz < end) ? startChunk+sz : end;
         Global.computeHistogramOrder0(array, startChunk, endChunk, this.freqs, false);
         this.updateFrequencies(frequencies);

         final int[] c = this.codes;
         final OutputBitStream bitstream = this.bs;
         final int endChunk3 = 3*((endChunk-startChunk)/3) + startChunk;

         for (int i=startChunk; i<endChunk3; i+=3)
         {
            // Pack 3 codes into 1 long
            final int code1 = c[array[i]&0xFF];
            final int codeLen1 = code1 >>> 24;
            final int code2 = c[array[i+1]&0xFF];
            final int codeLen2 = code2 >>> 24;
            final int code3 = c[array[i+2]&0xFF];
            final int codeLen3 = code3 >>> 24;
            final long st = ((((long) code1)&0xFFFFFF)<<(codeLen2+codeLen3) | 
               (((long) code2)&((1<<codeLen2)-1))<<codeLen3)| 
               (((long) code3)&((1<<codeLen3)-1));
            bitstream.writeBits(st, codeLen1+codeLen2+codeLen3);
         }

         for (int i=endChunk3; i<endChunk; i++)
         {
            final int code = c[array[i]&0xFF];
            bitstream.writeBits(code, code>>>24);
         }

         startChunk = endChunk;
      }

      return length;
   }


   @Override
   public OutputBitStream getBitStream()
   {
      return this.bs;
   }

   
   @Override
   public void dispose() 
   {
   }
   
   
   
   public static class FrequencyArrayComparator implements ArrayComparator
   {
      private final int[] array;


      public FrequencyArrayComparator(int[] frequencies)
      {
         if (frequencies == null)
            throw new NullPointerException("Invalid null array parameter");

        this.array = frequencies;
      }


      @Override
      public int compare(int lidx, int ridx)
      {
         // Check size (natural order) as first key
         final int res = this.array[lidx] - this.array[ridx];

         // Check index (natural order) as second key
         return (res != 0) ? res : lidx - ridx;
      }
   }    
}