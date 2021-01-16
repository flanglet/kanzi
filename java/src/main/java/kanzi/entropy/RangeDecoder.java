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

import kanzi.InputBitStream;
import kanzi.BitStreamException;
import kanzi.EntropyDecoder;


// Based on Order 0 range coder by Dmitry Subbotin itself derived from the algorithm
// described by G.N.N Martin in his seminal article in 1979.
// [G.N.N. Martin on the Data Recording Conference, Southampton, 1979]
// Optimized for speed.

// Not thread safe
public final class RangeDecoder implements EntropyDecoder
{
    private static final long TOP_RANGE    = 0x0FFFFFFFFFFFFFFFL;
    private static final long BOTTOM_RANGE = 0x000000000000FFFFL;
    private static final long RANGE_MASK   = 0x0FFFFFFF00000000L;
    private static final int DEFAULT_CHUNK_SIZE = 1 << 15; // 32 KB by default
    private static final int MAX_CHUNK_SIZE = 1 << 30;

    private long code;
    private long low;
    private long range;
    private final int[] alphabet;
    private final int[] freqs;
    private final long[] cumFreqs;
    private short[] f2s; // mapping frequency -> symbol
    private final InputBitStream bitstream;
    private final int chunkSize;
    private int shift;


    public RangeDecoder(InputBitStream bitstream)
    {
       this(bitstream, DEFAULT_CHUNK_SIZE);
    }


    // The chunk size indicates how many bytes are encoded (per block) before
    // resetting the frequency stats.
    public RangeDecoder(InputBitStream bitstream, int chunkSize)
    {
        if (bitstream == null)
            throw new NullPointerException("Range codec: Invalid null bitstream parameter");

        if (chunkSize < 1024)
           throw new IllegalArgumentException("Range codec: The chunk size must be at least 1024");

        if (chunkSize > MAX_CHUNK_SIZE)
           throw new IllegalArgumentException("Range codec: The chunk size must be at most "+MAX_CHUNK_SIZE);

        this.range = TOP_RANGE;
        this.bitstream = bitstream;
        this.chunkSize = chunkSize;
        this.cumFreqs = new long[257];
        this.freqs = new int[256];
        this.alphabet = new int[256];
        this.f2s = new short[0];
    }


    protected int decodeHeader(int[] frequencies)
    {
      int alphabetSize = EntropyUtils.decodeAlphabet(this.bitstream, this.alphabet);

      if (alphabetSize == 0)
         return 0;

      if (alphabetSize != 256)
      {
         for (int i=0; i<256; i++)
            frequencies[i] = 0;
      }

      final int logRange = (int) (8 + this.bitstream.readBits(3));
      final int scale = 1 << logRange;
      this.shift = logRange;
      int sum = 0;
      final int chkSize = (alphabetSize >= 64) ? 8 : 6;
      int llr = 3;

      while (1<<llr <= logRange)
         llr++;

      // Decode all frequencies (but the first one) by chunks of size 'inc'
      for (int i=1; i<alphabetSize; i+=chkSize)
      {
         final int logMax = (int) this.bitstream.readBits(llr);

         if (1<<logMax > scale)
         {
            throw new BitStreamException("Invalid bitstream: incorrect frequency size " +
                    logMax + " in range decoder", BitStreamException.INVALID_STREAM);
         }

         final int endj = (i+chkSize < alphabetSize) ? i + chkSize : alphabetSize;

         // Read frequencies
         for (int j=i; j<endj; j++)
         {
            int freq = (logMax == 0) ? 1 : (int) (1+this.bitstream.readBits(logMax));

            if ((freq <= 0) || (freq >= scale))
            {
               throw new BitStreamException("Invalid bitstream: incorrect frequency " +
                       freq + " for symbol '" + this.alphabet[j] + "' in range decoder",
                       BitStreamException.INVALID_STREAM);
            }

            frequencies[this.alphabet[j]] = freq;
            sum += freq;
         }
      }

      // Infer first frequency
      if (scale <= sum)
      {
         throw new BitStreamException("Invalid bitstream: incorrect frequency " +
                 frequencies[this.alphabet[0]] + " for symbol '" + this.alphabet[0] +
                 "' in range decoder", BitStreamException.INVALID_STREAM);
      }

      frequencies[this.alphabet[0]] = scale - sum;
      this.cumFreqs[0] = 0;

      if (this.f2s.length < scale)
         this.f2s = new short[scale];

      // Create histogram of frequencies scaled to 'range' and reverse mapping
      for (int i=0; i<256; i++)
      {
         this.cumFreqs[i+1] = this.cumFreqs[i] + frequencies[i];
         final int base = (int) this.cumFreqs[i];

         for (int j=frequencies[i]-1; j>=0; j--)
            this.f2s[base+j] = (short) i;
      }

      return alphabetSize;
    }


    // Initialize once (if necessary) at the beginning, the use the faster decodeByte_()
    // Reset frequency stats for each chunk of data in the block
    @Override
    public int decode(byte[] block, int blkptr, int count)
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
         final int endChunk = (startChunk + sz < end) ? startChunk + sz : end;
         final int alphabetSize = this.decodeHeader(this.freqs);

        if (alphabetSize == 0)
            return startChunk - blkptr;

        if (alphabetSize == 1)
        {
            // Shortcut for chunks with only one symbol
            for (int i=startChunk; i<endChunk; i++)
               block[i] = (byte) this.alphabet[0];

            startChunk = endChunk;
            continue;
        }


         this.range = TOP_RANGE;
         this.low = 0;
         this.code = this.bitstream.readBits(60);

         for (int i=startChunk; i<endChunk; i++)
            block[i] = this.decodeByte();

         startChunk = endChunk;
      }

      return count;
    }


    protected byte decodeByte()
    {
       // Compute next low and range
       this.range >>>= this.shift;
       final int count = (int) ((this.code - this.low) / this.range);
       final int symbol = this.f2s[count];
       final long cumFreq = this.cumFreqs[symbol];
       final long freq = this.cumFreqs[symbol+1] - cumFreq;
       this.low += (cumFreq * this.range);
       this.range *= freq;

       // If the left-most digits are the same throughout the range, read bits from bitstream
       while (true)
       {
          if (((this.low ^ (this.low + this.range)) & RANGE_MASK) != 0)
          {
             if (this.range > BOTTOM_RANGE)
                break;

             // Normalize
             this.range = -this.low & BOTTOM_RANGE;
          }

          this.code = (this.code << 28) | this.bitstream.readBits(28);
          this.range <<= 28;
          this.low <<= 28;
       }

       return (byte) symbol;
    }


    @Override
    public InputBitStream getBitStream()
    {
       return this.bitstream;
    }


    @Override
    public void dispose()
    {
    }
}
