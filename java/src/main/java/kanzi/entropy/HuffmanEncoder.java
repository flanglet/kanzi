/*
Copyright 2011-2024 Frederic Langlet
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

import java.util.Arrays;
import kanzi.OutputBitStream;
import kanzi.BitStreamException;
import kanzi.EntropyEncoder;
import kanzi.Error;
import kanzi.Global;
import kanzi.Memory.BigEndian;


// Implementation of a static Huffman encoder.
// Uses in place generation of canonical codes instead of a tree
public class HuffmanEncoder implements EntropyEncoder
{
   private final OutputBitStream bitstream;
   private final int[] alphabet;
   private final int[] codes;
   private final int chunkSize;
   private byte[] buffer;


   public HuffmanEncoder(OutputBitStream bitstream) throws BitStreamException
   {
      this(bitstream, HuffmanCommon.MAX_CHUNK_SIZE);
   }


    // The chunk size indicates how many bytes are encoded (per block) before
    // resetting the frequency stats.
   public HuffmanEncoder(OutputBitStream bitstream, int chunkSize) throws BitStreamException
   {
      if (bitstream == null)
         throw new NullPointerException("Huffman codec: Invalid null bitstream parameter");

      if (chunkSize < HuffmanCommon.MIN_CHUNK_SIZE)
         throw new IllegalArgumentException("Huffman codec: The chunk size must be at least "+HuffmanCommon.MIN_CHUNK_SIZE);

      if (chunkSize > HuffmanCommon.MAX_CHUNK_SIZE)
         throw new IllegalArgumentException("Huffman codec: The chunk size must be at most "+HuffmanCommon.MAX_CHUNK_SIZE);

      this.bitstream = bitstream;
      this.buffer = new byte[0];
      this.codes = new int[256];
      this.alphabet = new int[256];
      this.chunkSize = chunkSize;

      // Default frequencies, sizes and codes
      for (int i=0; i<256; i++)
         this.codes[i] = i;
   }


   // Rebuild Huffman codes
   private int updateFrequencies(int[] freqs) throws BitStreamException
   {
      if ((freqs == null) || (freqs.length != 256))
         return -1;

      int count = 0;
      short[] sizes = new short[256];

      for (int i=0; i<256; i++)
      {
         this.codes[i] = 0;

         if (freqs[i] > 0)
            this.alphabet[count++] = i;
      }

      EntropyUtils.encodeAlphabet(this.bitstream, this.alphabet, count);

      if (count == 0)
          return 0;

      if (count == 1)
      {
          this.codes[this.alphabet[0]] = 1<<24;
          sizes[this.alphabet[0]] = 1;
      }
      else
      {
          int retries = 0;
          int[] ranks = new int[256];

          while (true)
          {
              for (int i=0; i<count; i++)
                  ranks[i] = (freqs[this.alphabet[i]] << 8) | this.alphabet[i];

              int maxCodeLen = this.computeCodeLengths(sizes, ranks, count);

              if (maxCodeLen == 0)
                  throw new BitStreamException("Could not generate Huffman codes: invalid code length 0", Error.ERR_PROCESS_BLOCK);

              if (maxCodeLen <= HuffmanCommon.MAX_SYMBOL_SIZE_V4)
              {
                 // Usual case
                 HuffmanCommon.generateCanonicalCodes(sizes, this.codes, ranks, count, HuffmanCommon.MAX_SYMBOL_SIZE_V4);
                 break;
              }

              // Sometimes, codes exceed the budget for the max code length => normalize
              // frequencies (boost the smallest frequencies) and try once more.
              if (retries > 2)
                 throw new IllegalArgumentException("Could not generate Huffman codes: max code length (" +
                    HuffmanCommon.MAX_SYMBOL_SIZE_V4 + " bits) exceeded");

              retries++;
              int[] f = new int[count];
              int[] symbols = new int[count];
              int totalFreq = 0;

              for (int i=0; i<count; i++)
              {
                 f[i] = freqs[this.alphabet[i]];
                 totalFreq += f[i];
              }

              // Normalize to a smaller scale
              EntropyUtils.normalizeFrequencies(f, symbols, totalFreq,
                   HuffmanCommon.MAX_CHUNK_SIZE>>(retries+1));

              for (int i=0; i<count; i++)
                 freqs[this.alphabet[i]] = f[i];
          }
      }

      // Transmit code lengths only, frequencies and codes do not matter
      ExpGolombEncoder egenc = new ExpGolombEncoder(this.bitstream, true);
      short prevSize = 2;

      // Pack size and code (size <= MAX_SYMBOL_SIZE bits)
      // Unary encode the length differences
      for (int i=0; i<count; i++)
      {
         final int s = this.alphabet[i];
         final short currSize = sizes[s];
         this.codes[s] |= (currSize<<24);
         egenc.encodeByte((byte) (currSize - prevSize));
         prevSize = currSize;
      }

      return count;
   }


   private int computeCodeLengths(short[] sizes, int[] ranks, int count)
   {
      // Sort ranks by increasing frequencies (first key) and increasing value (second key)
      Arrays.sort(ranks, 0, count);
      int[] freqs = new int[256];

      for (int i=0; i<count; i++)
      {
         freqs[i] = ranks[i] >>> 8;
         ranks[i] &= 0xFF;

         if (freqs[i] == 0)
             return 0;
      }

      // See [In-Place Calculation of Minimum-Redundancy Codes]
      // by Alistair Moffat & Jyrki Katajainen
      computeInPlaceSizesPhase1(freqs, count);
      int maxCodeLen = computeInPlaceSizesPhase2(freqs, count);

      if (maxCodeLen <= HuffmanCommon.MAX_SYMBOL_SIZE_V4)
      {
         for (int i=0; i<count; i++)
            sizes[ranks[i]] = (short) freqs[i];
      }

      return maxCodeLen;
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
               continue;
            }

            sum += data[s];

            if (s > t)
               data[s] = 0;

            s++;
         }

         data[t] = sum;
      }
   }


   // n must be at least 2
   static int computeInPlaceSizesPhase2(int[] data, int n)
   {
      if (n < 2)
          return 0;

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

      return depth - 1;
   }


   // Dynamically compute the frequencies for every chunk of data in the block
   @Override
   public int encode(byte[] block, int blkptr, int count)
   {
      if ((block == null) || (blkptr+count > block.length) || (blkptr < 0) || (count < 0))
         return -1;

      if (count == 0)
         return 0;

      final int end = blkptr + count;
      int startChunk = blkptr;
      int minLenBuf = Math.min(Math.min(this.chunkSize+(this.chunkSize>>3), 2*count), 65536);

      if (this.buffer.length < minLenBuf)
          this.buffer = new byte[minLenBuf];

      int[] freqs = new int[256];

      while (startChunk < end)
      {
         // Update frequencies and rebuild Huffman codes
         final int endChunk = Math.min(startChunk+this.chunkSize, end);
         Global.computeHistogramOrder0(block, startChunk, endChunk, freqs, false);

         if (this.updateFrequencies(freqs) <= 1)
         {
            // Skip chunk if only one symbol
            startChunk = endChunk;
            continue;
         }

         final int[] c = this.codes;
         final int endChunk4 = ((endChunk-startChunk) & -4) + startChunk;
         int idx = 0;
         long st = 0;
         int bits = 0; // accumulated bits

         for (int i=startChunk; i<endChunk4; i+=4)
         {
            int code;
            code = c[block[i]&0xFF];
            final int codeLen0 = code >>> 24;
            st = (st << codeLen0) | (code & 0xFFFFFF);
            code = c[block[i+1]&0xFF];
            final int codeLen1 = code >>> 24;
            st = (st<<codeLen1) | (code&0xFFFFFF);
            code = c[block[i+2]&0xFF];
            final int codeLen2 = code >>> 24;
            st = (st<<codeLen2) | (code&0xFFFFFF);
            code = c[block[i+3]&0xFF];
            final int codeLen3 = code >>> 24;
            st = (st<<codeLen3) | (code&0xFFFFFF);
            bits += (codeLen0+codeLen1+codeLen2+codeLen3);
            int shift = bits & -8;
            BigEndian.writeLong64(this.buffer, idx, st << (64 - bits));
            bits -= shift;
            idx += (shift>>3);
         }

         for (int i=endChunk4; i<endChunk; i++)
         {
            final int code = c[block[i]&0xFF];
            final int codeLen = code >>> 24;
            st = (st<<codeLen) | (code&0xFFFFFF);
            bits += codeLen;
         }

         int nbBits = (idx * 8) + bits;

         while (bits >= 8)
         {
             bits -= 8;
             this.buffer[idx++] = (byte) (st>>bits);
         }

         if (bits > 0)
             this.buffer[idx++] = (byte) (st<<(8-bits));

         // Write number of streams (0->1, 1->4, 2->8, 3->32)
         this.bitstream.writeBits(0, 2);

         // Write chunk size in bits
         EntropyUtils.writeVarInt(this.bitstream, nbBits);

         // Write compressed data to bitstream
         this.bitstream.writeBits(this.buffer, 0, nbBits);

         startChunk = endChunk;
      }

      return count;
   }


   @Override
   public OutputBitStream getBitStream()
   {
      return this.bitstream;
   }


   @Override
   public void dispose()
   {
   }
}
