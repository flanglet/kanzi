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

package kanzi.entropy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import kanzi.BitStreamException;
import kanzi.InputBitStream;
import kanzi.OutputBitStream;


public class EntropyUtils
{
   public static final int INCOMPRESSIBLE_THRESHOLD = 973; // 0.95*1024
   private static final int FULL_ALPHABET = 0;
   private static final int PARTIAL_ALPHABET = 1;
   private static final int ALPHABET_256 = 0;
   private static final int ALPHABET_0 = 1;


   // alphabet must be sorted in increasing order
   // alphabet length must be a power of 2 up to 256
   public static int encodeAlphabet(OutputBitStream obs, int[] alphabet, int count)
   {
      // Alphabet length must be a power of 2
      if ((alphabet.length & (alphabet.length-1)) != 0)
         return -1;

      if ((alphabet.length > 256) || (count > alphabet.length))
         return -1;

      switch (count)
      {
         case 0:
            obs.writeBit(FULL_ALPHABET);
            obs.writeBit(ALPHABET_0);
            break;

         case 256:
            obs.writeBit(FULL_ALPHABET);
            obs.writeBit(ALPHABET_256);
            break;

         default:
            // Partial alphabet
            obs.writeBit(PARTIAL_ALPHABET);
            byte[] masks = new byte[32];

            for (int i=0; i<count; i++)
               masks[alphabet[i]>>3] |= (1 << (alphabet[i]&7));

            final int lastMask = alphabet[count-1] >> 3;
            obs.writeBits(lastMask, 5);

            for (int i=0; i<=lastMask; i++)
               obs.writeBits(masks[i], 8);

            break;
      }

      return count;
   }


   public static int decodeAlphabet(InputBitStream ibs, int[] alphabet) throws BitStreamException
   {
      // Read encoding mode from bitstream
      final int alphabetType = ibs.readBit();

      if (alphabetType == FULL_ALPHABET)
      {
         if (ibs.readBit() == ALPHABET_0)
            return 0;
         
         final int alphabetSize = 256;

         if (alphabetSize > alphabet.length)
            throw new BitStreamException("Invalid bitstream: incorrect alphabet size: " + alphabetSize,
               BitStreamException.INVALID_STREAM);

         // Full alphabet
         for (int i=0; i<alphabetSize; i++)
            alphabet[i] = i;

         return alphabetSize;
      }

      // Partial alphabet
      final int lastMask = (int) ibs.readBits(5);
      int count = 0;

      // Decode presence flags
      for (int i=0; i<=lastMask; i++)
      {
         final int mask = (int) ibs.readBits(8);

         for (int j=0; j<8; j++)
         {
            if ((mask & (1<<j)) != 0)
               alphabet[count++] = (i<<3) + j;
         }
      }

      return count;
   }


   // Not thread safe
   // Return the size of the alphabet
   // The alphabet and freqs parameters are updated
   public static int normalizeFrequencies(int[] freqs, int[] alphabet, int totalFreq, int scale)
   {
      if (alphabet.length > 1<<8)
         throw new IllegalArgumentException("Invalid alphabet size parameter: "+ alphabet.length +
                 " (must be less than or equal to 256)");

      if ((scale < 1<<8) || (scale > 1<<16))
         throw new IllegalArgumentException("Invalid scale parameter: "+ scale +
                 " (must be in [256..65536])");

      if ((alphabet.length == 0) || (totalFreq == 0))
         return 0;

      int alphabetSize = 0;

      // shortcut
      if (totalFreq == scale)
      {
         for (int i=0; i<256; i++)
         {
            if (freqs[i] != 0)
               alphabet[alphabetSize++] = i;
         }

         return alphabetSize;
      }

      int sumScaledFreq = 0;
      int freqMax = 0;
      int idxMax = -1;

      // Scale frequencies by stretching distribution over complete range
      for (int i=0; i<alphabet.length; i++)
      {
         alphabet[i] = 0;
         final int f = freqs[i];

         if (f == 0)
            continue;

         if (f > freqMax)
         {
            freqMax = f;
            idxMax = i;
         }

         long sf = (long) freqs[i] * scale;
         int scaledFreq;

         if (sf <= totalFreq)
         {
            // Quantum of frequency
            scaledFreq = 1;
         }
         else
         {
            // Find best frequency rounding value
            scaledFreq = (int) (sf / totalFreq);
            long errCeiling = ((scaledFreq+1) * (long) totalFreq) - sf;
            long errFloor = sf - (scaledFreq * (long) totalFreq);

            if (errCeiling < errFloor)
               scaledFreq++;
         }

         alphabet[alphabetSize++] = i;
         sumScaledFreq += scaledFreq;
         freqs[i] = scaledFreq;
      }

      if (alphabetSize == 0)
         return 0;

      if (alphabetSize == 1)
      {
         freqs[alphabet[0]] = scale;
         return 1;
      }

      if (sumScaledFreq != scale)
      {
         final int delta = (int) (sumScaledFreq-scale);

         if (Math.abs(delta) * 100 < freqs[idxMax] * 5)
         {
            // Fast path: just adjust the max frequency (or do nothing)
            if (freqs[idxMax] > delta)
               freqs[idxMax] -= delta;
         }
         else
         {
            // Slow path: spread error across frequencies
            final int inc = (sumScaledFreq > scale) ? -1 : 1;
            ArrayList<FreqSortData> list = new ArrayList<>(alphabetSize);

            for (int i=0; i<alphabetSize; i++)
               list.add(new FreqSortData(freqs, alphabet[i]));
            
            Collections.sort(list);
            Deque<FreqSortData> queue = new ArrayDeque<>(list);

            while ((sumScaledFreq != scale) && (queue.size() > 0))
            {
               // Remove symbol with highest frequency
               FreqSortData fsd = queue.removeFirst();

               // Do not zero out any frequency
               if (freqs[fsd.symbol] == -inc)
                  continue;

               // Distort frequency
               freqs[fsd.symbol] += inc;
               sumScaledFreq += inc;
               queue.addLast(fsd);
            }
         }
      }

      return alphabetSize;
   }


   public static int writeVarInt(OutputBitStream bs, int value)
   {
      int res = 0;

      if ((value>=128) || (value<0))
      {
         bs.writeBits(0x80|(value&0x7F), 8);
         value >>>= 7;
         res++;

         while (value >= 128)
         {
            bs.writeBits(0x80|(value&0x7F), 8);
            value >>>= 7;
            res++;
         }
      }

      bs.writeBits(value, 8);
      return res;
   }


   public static int readVarInt(InputBitStream bs)
   {
      int value = (int) bs.readBits(8);
      int res = value & 0x7F;
      int shift = 7;

      while ((value >= 128) && (shift <= 28))
      {
         value = (int) bs.readBits(8);
         res |= ((value&0x7F)<<shift);
         shift += 7;
      }

      return res;
   }


   private static class FreqSortData implements Comparable<FreqSortData>
   {
      final int symbol;
      final int[] frequencies;


      public FreqSortData(int[] frequencies, int symbol)
      {
         this.frequencies = frequencies;
         this.symbol = symbol & 0xFFFF;
      }


      @Override
      public boolean equals(Object o)
      {
         if (o == null)
            return false;

         if (this == o)
            return true;

         try
         {
            return ((FreqSortData) o).symbol == this.symbol;
         }
         catch (ClassCastException e)
         {
            return false;
         }
      }


      @Override
      public int hashCode()
      {
         return this.symbol;
      }


      @Override
      public int compareTo(FreqSortData sd)
      {
         // Decreasing frequency
         final int res = sd.frequencies[sd.symbol] - this.frequencies[this.symbol];

         // Decreasing symbol
         return (res == 0) ? sd.symbol - this.symbol : res;
      }
   }
}