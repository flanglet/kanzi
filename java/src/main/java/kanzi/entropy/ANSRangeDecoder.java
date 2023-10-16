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

import java.util.Map;
import kanzi.BitStreamException;
import kanzi.EntropyDecoder;
import kanzi.InputBitStream;

// Implementation of an Asymmetric Numeral System decoder.
// See "Asymmetric Numeral System" by Jarek Duda at http://arxiv.org/abs/0902.0271
// Some code has been ported from https://github.com/rygorous/ryg_rans
// For an alternate C implementation example, see https://github.com/Cyan4973/FiniteStateEntropy

public class ANSRangeDecoder implements EntropyDecoder
{
   private static final int ANS_TOP = 1 << 15; // max possible for ANS_TOP=1<23
   private static final int DEFAULT_ANS0_CHUNK_SIZE = 1 << 15; // 32 KB by default
   private static final int DEFAULT_LOG_RANGE = 12;
   private static final int MIN_CHUNK_SIZE = 1024;
   private static final int MAX_CHUNK_SIZE = 1 << 27; // 8*MAX_CHUNK_SIZE must not overflow

   private final InputBitStream bitstream;
   private final int[][] freqs;
   private final byte[][] f2s; // mapping frequency -> symbol
   private final Symbol[][] symbols;
   private byte[] buffer;
   private final int chunkSize;
   private final int order;
   private int logRange;
   private boolean isBsVersion1;


   public ANSRangeDecoder(InputBitStream bs)
   {
      this(bs, 0, DEFAULT_ANS0_CHUNK_SIZE);
   }


   public ANSRangeDecoder(InputBitStream bs, int order)
   {
      this(bs, order, DEFAULT_ANS0_CHUNK_SIZE);
   }


   // The chunk size indicates how many bytes are encoded (per block) before
   // resetting the frequency stats.
   public ANSRangeDecoder(InputBitStream bs, int order, int chunkSize)
   {
      if (bs == null)
         throw new NullPointerException("ANS Codec: Invalid null bitstream parameter");

      if ((order != 0) && (order != 1))
         throw new IllegalArgumentException("ANS Codec: The order must be 0 or 1");

      if (chunkSize < MIN_CHUNK_SIZE)
         throw new IllegalArgumentException("ANS Codec: The chunk size must be at least "+MIN_CHUNK_SIZE);

      if (chunkSize > MAX_CHUNK_SIZE)
         throw new IllegalArgumentException("ANS Codec: The chunk size must be at most "+MAX_CHUNK_SIZE);

      this.bitstream = bs;
      this.chunkSize = Math.min(chunkSize << (8*order), MAX_CHUNK_SIZE);
      this.order = order;
      final int dim = 255*order + 1;
      this.freqs = new int[dim][256];
      this.f2s = new byte[dim][256];
      this.symbols = new Symbol[dim][256];
      this.buffer = new byte[0];
      this.logRange = DEFAULT_LOG_RANGE;
      this.isBsVersion1 = false; // old encoding

      for (int i=0; i<dim; i++)
      {
         this.freqs[i] = new int[256];
         this.f2s[i] = new byte[0];
         this.symbols[i] = new Symbol[256];
      }
   }

   public ANSRangeDecoder(InputBitStream bs, int order, Map<String, Object> ctx)
   {
      if (bs == null)
         throw new NullPointerException("ANS Codec: Invalid null bitstream parameter");

      if ((order != 0) && (order != 1))
         throw new IllegalArgumentException("ANS Codec: The order must be 0 or 1");

      final int bsVersion = (ctx == null) ? -1 : (Integer) ctx.getOrDefault("bsVersion", -1);
      this.bitstream = bs;
      this.chunkSize = Math.min(DEFAULT_ANS0_CHUNK_SIZE << (8*order), MAX_CHUNK_SIZE);
      this.order = order;
      final int dim = 255*order + 1;
      this.freqs = new int[dim][256];
      this.f2s = new byte[dim][256];
      this.symbols = new Symbol[dim][256];
      this.buffer = new byte[0];
      this.logRange = DEFAULT_LOG_RANGE;
      this.isBsVersion1 = bsVersion == 1; // old encoding

      for (int i=0; i<dim; i++)
      {
         this.freqs[i] = new int[256];
         this.f2s[i] = new byte[0];
         this.symbols[i] = new Symbol[256];
      }
   }

   
   @Override
   public int decode(byte[] block, int blkptr, int count)
   {
      if ((block == null) || (blkptr+count > block.length) || (blkptr < 0) || (count < 0))
         return -1;

      if (count == 0)
         return 0;

      final int end = blkptr + count;
      int sizeChunk = this.chunkSize;
      int startChunk = blkptr;
      final int endk = 255*this.order + 1;

      for (int k=0; k<endk; k++)
      {
         Symbol[] syms = this.symbols[k];

         for (int i=0; i<256; i++)
            syms[i] = new Symbol();
      }

      final int[] alphabet = new int[256];

      while (startChunk < end)
      {
         final int endChunk = Math.min(startChunk+sizeChunk, end);
         final int alphabetSize = this.decodeHeader(this.freqs, alphabet);

         if (alphabetSize == 0)
            return startChunk - blkptr;

         if ((this.order == 0) && (alphabetSize == 1))
         {
            // Shortcut for chunks with only one symbol
            for (int i=startChunk; i<endChunk; i++)
               block[i] = (byte) alphabet[0];
         }
         else
         {
            if (this.isBsVersion1)
               this.decodeChunkV1(block, startChunk, endChunk);
            else
               this.decodeChunkV2(block, startChunk, endChunk);
         }

         startChunk = endChunk;
      }

      return count;
   }


   protected void decodeChunkV1(byte[] block, final int start, final int end)
   {
      // Read chunk size
      final int sz = EntropyUtils.readVarInt(this.bitstream) & (MAX_CHUNK_SIZE-1);

      // Read initial ANS state
      int st0 = (int) this.bitstream.readBits(32);
      int st1 = (this.order == 0) ? (int) this.bitstream.readBits(32) : 0;

      if (sz != 0)
      {
         // Add some padding
         if (this.buffer.length < sz)
            this.buffer = new byte[sz+(sz>>3)];

         this.bitstream.readBits(this.buffer, 0, 8*sz);
      }

      int n = 0;
      final int mask = (1<<this.logRange) - 1;

      if (this.order == 0)
      {
         final byte[] freq2sym = this.f2s[0];
         final Symbol[] symb = this.symbols[0];
         final int end2 = (end & -2) - 1;

         for (int i=start; i<end2; i+=2)
         {
            final byte cur1 = freq2sym[st1&mask];
            block[i] = cur1;
            final byte cur0 = freq2sym[st0&mask];
            block[i+1] = cur0;
            final Symbol sym1 = symb[cur1&0xFF];
            final Symbol sym0 = symb[cur0&0xFF];

            // Compute next ANS state
            // D(x) = (s, q_s (x/M) + mod(x,M) - b_s) where s is such b_s <= x mod M < b_{s+1}
            st1 = sym1.freq * (st1>>>this.logRange) + (st1&mask) - sym1.cumFreq;
            st0 = sym0.freq * (st0>>>this.logRange) + (st0&mask) - sym0.cumFreq;

            // Normalize
            while (st1 < ANS_TOP)
            {
               st1 = (st1<<8) | (this.buffer[n] & 0xFF);
               st1 = (st1<<8) | (this.buffer[n+1] & 0xFF);
               n += 2;
            }

            while (st0 < ANS_TOP)
            {
               st0 = (st0<<8) | (this.buffer[n] & 0xFF);
               st0 = (st0<<8) | (this.buffer[n+1] & 0xFF);
               n += 2;
            }
         }

         if ((end & 1) != 0)
            block[end - 1] = this.buffer[sz-1];
      }
      else
      {
         int prv = 0;

         for (int i=start; i<end; i++)
         {
            final int cur = this.f2s[prv][st0&mask] & 0xFF;
            block[i] = (byte) cur;
            final Symbol sym = this.symbols[prv][cur];

            // Compute next ANS state
            // D(x) = (s, q_s (x/M) + mod(x,M) - b_s) where s is such b_s <= x mod M < b_{s+1}
            st0 = sym.freq * (st0>>>this.logRange) + (st0&mask) - sym.cumFreq;

            // Normalize
            while (st0 < ANS_TOP)
            {
               st0 = (st0<<8) | (this.buffer[n] & 0xFF);
               st0 = (st0<<8) | (this.buffer[n+1] & 0xFF);
               n += 2;
            }

            prv = cur;
         }
      }
   }
   
   
   private int decodeSymbol(int[] idx, int st, Symbol sym, final int mask)
   {
      // Compute next ANS state
      // D(x) = (s, q_s (x/M) + mod(x,M) - b_s) where s is such b_s <= x mod M < b_{s+1}
      st = sym.freq * (st>>>this.logRange) + (st&mask) - sym.cumFreq;

      // Normalize
      if (st < ANS_TOP)
      {
         st = (st<<8) | (this.buffer[idx[0]] & 0xFF);
         st = (st<<8) | (this.buffer[idx[0]+1] & 0xFF);
         idx[0] += 2;
      }   
      
      return st;
   }

   
   protected void decodeChunkV2(byte[] block, final int start, final int end)
   {
      // Read chunk size
      final int sz = EntropyUtils.readVarInt(this.bitstream) & (MAX_CHUNK_SIZE-1);

      // Read initial ANS states
      int st0 = (int) this.bitstream.readBits(32);
      int st1 = (int) this.bitstream.readBits(32);
      int st2 = (int) this.bitstream.readBits(32);
      int st3 = (int) this.bitstream.readBits(32);

      // Read encoded data from bitstream
      if (sz != 0)
      {
         // Add some padding
         if (this.buffer.length < sz)
            this.buffer = new byte[sz+(sz>>3)];

         this.bitstream.readBits(this.buffer, 0, 8*sz);
      }

      int n = 0;      
      final int mask = (1<<this.logRange) - 1;
      final int end4 = start + ((end-start) & -4);
      final int[] idx = new int[] { n } ;

      if (this.order == 0) 
      {
         final byte[] freq2sym = this.f2s[0];
         final Symbol[] symb = this.symbols[0];

         for (int i=start; i<end4; i+=4) 
         {
            final int cur3 = freq2sym[st3 & mask] & 0xFF;
            block[i] = (byte) cur3;
            st3 = decodeSymbol(idx, st3, symb[cur3], mask);
            final int cur2 = freq2sym[st2 & mask] & 0xFF;
            block[i+1] = (byte) cur2;
            st2 = decodeSymbol(idx, st2, symb[cur2], mask);
            final int cur1 = freq2sym[st1 & mask] & 0xFF;
            block[i+2] = (byte) cur1;
            st1 = decodeSymbol(idx, st1, symb[cur1], mask);
            final int cur0 = freq2sym[st0 & mask] & 0xFF;
            block[i+3] = (byte) cur0;
            st0 = decodeSymbol(idx, st0, symb[cur0], mask);
         }
      }
      else 
      {
         final int quarter = (end4-start) >> 2;
         int i0 = start;
         int i1 = start + 1*quarter;
         int i2 = start + 2*quarter;
         int i3 = start + 3*quarter;
         int prv0 = 0, prv1 = 0, prv2 = 0, prv3 = 0;

         for ( ; i0 < start+quarter; i0++, i1++, i2++, i3++) 
         {
            final int cur3 = this.f2s[prv3][st3&mask] & 0xFF;
            block[i3] = (byte) cur3;
            st3 = decodeSymbol(idx, st3, this.symbols[prv3][cur3], mask);
            final int cur2 = this.f2s[prv2][st2&mask] & 0xFF;
            block[i2] = (byte) cur2;
            st2 = decodeSymbol(idx, st2, this.symbols[prv2][cur2], mask);
            final int cur1 = this.f2s[prv1][st1&mask] & 0xFF;
            block[i1] = (byte) cur1;
            st1 = decodeSymbol(idx, st1, this.symbols[prv1][cur1], mask);
            final int cur0 = this.f2s[prv0][st0&mask] & 0xFF;
            block[i0] = (byte) cur0;
            st0 = decodeSymbol(idx, st0, this.symbols[prv0][cur0], mask);
            prv3 = cur3;
            prv2 = cur2;
            prv1 = cur1;
            prv0 = cur0;
         }
      }

      n = idx[0];
      
      for (int i=end4; i<end; i++)
         block[i] = this.buffer[n++];
   }

   
   // Decode alphabet and frequencies
   protected int decodeHeader(int[][] frequencies, int[] alphabet)
   {
      this.logRange = (int) (8 + this.bitstream.readBits(3));

      if ((this.logRange < 8) || (this.logRange > 16))
         throw new BitStreamException("Invalid bitstream: range = "+this.logRange+
            " (must be in [8..16])", BitStreamException.INVALID_STREAM);

      int res = 0;
      final int dim = 255*this.order + 1;
      final int scale = 1 << this.logRange;

      for (int k=0; k<dim; k++)
      {
         int alphabetSize = EntropyUtils.decodeAlphabet(this.bitstream, alphabet);

         if (alphabetSize == 0)
            continue;

         int llr = 3;

         while (1<<llr <= this.logRange)
            llr++;

         final int[] f = frequencies[k];

         if (alphabetSize != f.length)
         {
            for (int i=f.length-1; i>=0; i--)
               f[i] = 0;
         }

         if (this.f2s[k].length < scale)
            this.f2s[k] = new byte[scale];

         final int chkSize = (alphabetSize >= 64) ? 8 : 6;
         int sum = 0;

         // Decode all frequencies (but the first one) by chunks
         for (int i=1; i<alphabetSize; i+=chkSize)
         {
            // Read frequencies size for current chunk
            final int logMax = (int) this.bitstream.readBits(llr);

            if (1<<logMax > scale)
            {
               throw new BitStreamException("Invalid bitstream: incorrect frequency size " +
                       logMax + " in ANS range decoder", BitStreamException.INVALID_STREAM);
            }

            final int endj = (i+chkSize < alphabetSize) ? i + chkSize : alphabetSize;

            // Read frequencies
            for (int j=i; j<endj; j++)
            {
               final int freq = (logMax == 0) ? 1 : (int) (1+this.bitstream.readBits(logMax));

               if ((freq <= 0) || (freq >= scale))
               {
                  throw new BitStreamException("Invalid bitstream: incorrect frequency " +
                          freq + " for symbol '" + alphabet[j] + "' in ANS range decoder",
                          BitStreamException.INVALID_STREAM);
               }

               f[alphabet[j]] = freq;
               sum += freq;
            }
         }

         // Infer first frequency
         if (scale <= sum)
         {
            throw new BitStreamException("Invalid bitstream: incorrect frequency " +
                    f[alphabet[0]] + " for symbol '" + alphabet[0] +
                    "' in ANS range decoder", BitStreamException.INVALID_STREAM);
         }

         f[alphabet[0]] = scale - sum;
         sum = 0;
         final Symbol[] symb = this.symbols[k];
         final byte[] freq2sym = this.f2s[k];

         // Create reverse mapping
         for (int i=0; i<f.length; i++)
         {
            if (f[i] == 0)
               continue;

            for (int j=f[i]-1; j>=0; j--)
               freq2sym[sum+j] = (byte) i;

            symb[i].reset(sum, f[i], this.logRange);
            sum += f[i];
         }

         res += alphabetSize;
      }

      return res;
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


   static class Symbol
   {
      int cumFreq;
      int freq;


      public void reset(int cumFreq, int freq, int logRange)
      {
         this.cumFreq = cumFreq;
         this.freq = (freq >= 1<<logRange) ? (1<<logRange) - 1 : freq; // Mirror encoder
      }
   }
}