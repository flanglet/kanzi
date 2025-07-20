/*
Copyright 2011-2025 Frederic Langlet
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

package io.github.flanglet.kanzi.entropy;

import io.github.flanglet.kanzi.EntropyEncoder;
import io.github.flanglet.kanzi.Global;
import io.github.flanglet.kanzi.OutputBitStream;


// Based on Order 0 range coder by Dmitry Subbotin itself derived from the algorithm
// described by G.N.N Martin in his seminal article in 1979.
// [G.N.N. Martin on the Data Recording Conference, Southampton, 1979]
// Optimized for speed.

// Not thread safe
public final class RangeEncoder implements EntropyEncoder
{
    private static final long TOP_RANGE    = 0x0FFFFFFFFFFFFFFFL;
    private static final long BOTTOM_RANGE = 0x000000000000FFFFL;
    private static final long RANGE_MASK   = 0x0FFFFFFF00000000L;
    private static final int DEFAULT_CHUNK_SIZE = 1 << 15; // 32 KB by default
    private static final int DEFAULT_LOG_RANGE = 12;
    private static final int MAX_CHUNK_SIZE = 1 << 30;

    private long low;
    private long range;
    private final int[] alphabet;
    private final int[] freqs;
    private final long[] cumFreqs;
    private final OutputBitStream bitstream;
    private final int chunkSize;
    private final int logRange;
    private int shift;


    public RangeEncoder(OutputBitStream bitstream)
    {
       this(bitstream, DEFAULT_CHUNK_SIZE, DEFAULT_LOG_RANGE);
    }


    // The chunk size indicates how many bytes are encoded (per block) before
    // resetting the frequency stats.
    public RangeEncoder(OutputBitStream bs, int chunkSize, int logRange)
    {
      if (bs == null)
         throw new NullPointerException("Range codec: Invalid null bitstream parameter");

      if (chunkSize < 1024)
         throw new IllegalArgumentException("Range codec: The chunk size must be at least 1024");

      if (chunkSize > MAX_CHUNK_SIZE)
         throw new IllegalArgumentException("Range codec: The chunk size must be at most "+MAX_CHUNK_SIZE);

      if ((logRange < 8) || (logRange > 16))
         throw new IllegalArgumentException("Range codec: Invalid range parameter: "+
            logRange+" (must be in [8..16])");

      this.bitstream = bs;
      this.alphabet = new int[256];
      this.freqs = new int[256];
      this.cumFreqs = new long[257];
      this.logRange = logRange;
      this.chunkSize = chunkSize;
    }


   private int updateFrequencies(int[] frequencies, int size, int lr)
   {
      if ((frequencies == null) || (frequencies.length != 256))
         return -1;

      int alphabetSize = EntropyUtils.normalizeFrequencies(frequencies, this.alphabet, size, 1<<lr);

      if (alphabetSize > 0)
      {
         this.cumFreqs[0] = 0;

         // Create histogram of frequencies scaled to 'range'
         for (int i=0; i<256; i++)
            this.cumFreqs[i+1] = this.cumFreqs[i] + frequencies[i];
      }

      this.encodeHeader(alphabetSize, this.alphabet, frequencies, lr);
      return alphabetSize;
   }


   protected boolean encodeHeader(int alphabetSize, int[] alphabet, int[] frequencies, int lr)
   {
      final int encoded = EntropyUtils.encodeAlphabet(this.bitstream, alphabet, alphabetSize);

      if (encoded < 0)
         return false;

      if (encoded == 0)
         return true;

      this.bitstream.writeBits(lr-8, 3); // logRange
      final int chkSize = (alphabetSize >= 64) ? 8 : 6;
      int llr = 3;

      while (1<<llr <= lr)
         llr++;

      // Encode all frequencies (but the first one) by chunks of size 'inc'
      for (int i=1; i<alphabetSize; i+=chkSize)
      {
         int max = frequencies[alphabet[i]] - 1;
         int logMax = 0;
         final int endj = (i+chkSize < alphabetSize) ? i+chkSize : alphabetSize;

         // Search for max frequency log size in next chunk
         for (int j=i+1; j<endj; j++)
         {
            if (frequencies[alphabet[j]]-1 > max)
               max = frequencies[alphabet[j]]-1;
         }

         while (1<<logMax <= max)
            logMax++;

         this.bitstream.writeBits(logMax, llr);

         if (logMax == 0) // all frequencies equal one in this chunk
            continue;

         // Write frequencies
         for (int j=i; j<endj; j++)
            this.bitstream.writeBits(frequencies[alphabet[j]]-1, logMax);
      }

      return true;
   }


    // Reset frequency stats for each chunk of data in the block
    @Override
    public int encode(byte[] block, int blkptr, int count)
    {
       if ((block == null) || (blkptr+count > block.length) || (blkptr < 0) || (count < 0))
          return -1;

       if (count == 0)
          return 0;

       final int end = blkptr + count;
       final int sz = this.chunkSize;
       int startChunk = blkptr;

       while (startChunk < end)
       {
           final int endChunk = (startChunk+sz < end) ? startChunk+sz : end;
           this.range = TOP_RANGE;
           this.low = 0;
           int lr = this.logRange;

           // Lower log range if the size of the data chunk is small
           while ((lr > 8) && (1<<lr > endChunk-startChunk))
              lr--;

           if (this.rebuildStatistics(block, startChunk, endChunk, lr) <= 1)
           {
               // Skip chunk if only one symbol
               startChunk = endChunk;
               continue;
           }

           this.shift = lr;

           for (int i=startChunk; i<endChunk; i++)
              this.encodeByte(block[i]);

           // Flush 'low'
           this.bitstream.writeBits(this.low, 60);
           startChunk = endChunk;
       }

       return count;
    }


    protected void encodeByte(byte b)
    {
        // Compute next low and range
        final int symbol = b & 0xFF;
        final long cumFreq = this.cumFreqs[symbol];
        final long freq = this.cumFreqs[symbol+1] - cumFreq;
        this.range >>>= this.shift;
        this.low += (cumFreq * this.range);
        this.range *= freq;

        // If the left-most digits are the same throughout the range, write bits to bitstream
        while (true)
        {
            if (((this.low ^ (this.low + this.range)) & RANGE_MASK) != 0)
            {
               if (this.range > BOTTOM_RANGE)
                  break;

               // Normalize
               this.range = -this.low & BOTTOM_RANGE;
            }

            this.bitstream.writeBits(this.low >>> 32, 28);
            this.range <<= 28;
            this.low <<= 28;
        }
    }


   // Compute chunk frequencies, cumulated frequencies and encode chunk header
   private int rebuildStatistics(byte[] block, int start, int end, int lr)
   {
      Global.computeHistogramOrder0(block, start, end, this.freqs, false);
      return this.updateFrequencies(this.freqs, end-start, lr);
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