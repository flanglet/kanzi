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

package io.github.flanglet.kanzi.entropy;

import java.util.Map;
import io.github.flanglet.kanzi.EntropyEncoder;
import io.github.flanglet.kanzi.Global;
import io.github.flanglet.kanzi.OutputBitStream;

// Implementation of an Asymmetric Numeral System encoder.
// See "Asymmetric Numeral System" by Jarek Duda at http://arxiv.org/abs/0902.0271
// Some code has been ported from https://github.com/rygorous/ryg_rans
// For an alternate C implementation example, see https://github.com/Cyan4973/FiniteStateEntropy

public class ANSRangeEncoder implements EntropyEncoder
{
   private static final int ANS_TOP = 1 << 15; // max possible for ANS_TOP=1<<23
   private static final int DEFAULT_ANS0_CHUNK_SIZE = 16384;
   private static final int DEFAULT_LOG_RANGE = 12;
   private static final int MIN_CHUNK_SIZE = 1024;
   private static final int MAX_CHUNK_SIZE = 1 << 27; // 8*MAX_CHUNK_SIZE must not overflow

   private final OutputBitStream bitstream;
   private final int[][] freqs;
   private final Symbol[][] symbols;
   private byte[] buffer;
   private final int chunkSize;
   private final int order;
   private final int logRange;


   public ANSRangeEncoder(OutputBitStream bs)
   {
      this(bs, 0, DEFAULT_ANS0_CHUNK_SIZE, DEFAULT_LOG_RANGE);
   }


   public ANSRangeEncoder(OutputBitStream bs, int order)
   {
      this(bs, order, DEFAULT_ANS0_CHUNK_SIZE, DEFAULT_LOG_RANGE);
   }


   public ANSRangeEncoder(OutputBitStream bs, int order, int chunkSize)
   {
      this(bs, order, chunkSize, DEFAULT_LOG_RANGE);
   }


   // The chunk size indicates how many bytes are encoded (per block) before
   // resetting the frequency stats.
   public ANSRangeEncoder(OutputBitStream bs, int order, int chunkSize, int logRange)
   {
      if (bs == null)
         throw new NullPointerException("ANS Codec: Invalid null bitstream parameter");

      if ((order != 0) && (order != 1))
         throw new IllegalArgumentException("ANS Codec: The order must be 0 or 1");

      if (chunkSize < MIN_CHUNK_SIZE)
         throw new IllegalArgumentException("ANS Codec: The chunk size must be at least "+MIN_CHUNK_SIZE);

      if (chunkSize > MAX_CHUNK_SIZE)
         throw new IllegalArgumentException("ANS Codec: The chunk size must be at most "+MAX_CHUNK_SIZE);

      if ((logRange < 8) || (logRange > 16))
         throw new IllegalArgumentException("ANS Codec: Invalid range: "+logRange+" (must be in [8..16])");

      this.bitstream = bs;
      this.order = order;
      final int dim = 255*order + 1;
      this.freqs = new int[dim][257]; // freqs[x][256] = total(freqs[x][0..255])
      this.symbols = new Symbol[dim][256];
      this.buffer = new byte[0];
      this.logRange = (order == 0) ? logRange : logRange - 1;
      this.chunkSize = Math.min(chunkSize << (8*order), MAX_CHUNK_SIZE);

      for (int i=0; i<dim; i++)
      {
         this.freqs[i] = new int[257];
         this.symbols[i] = new Symbol[256];
      }
   }


   public ANSRangeEncoder(OutputBitStream bs, Map<String, Object> ctx, int order)
   {
      if (bs == null)
         throw new NullPointerException("ANS Codec: Invalid null bitstream parameter");

      if ((order != 0) && (order != 1))
         throw new IllegalArgumentException("ANS Codec: The order must be 0 or 1");

      this.bitstream = bs;
      this.order = order;
      final int dim = 255*order + 1;
      this.freqs = new int[dim][257]; // freqs[x][256] = total(freqs[x][0..255])
      this.symbols = new Symbol[dim][256];
      this.buffer = new byte[0];
      this.logRange = (order == 0) ? DEFAULT_LOG_RANGE : DEFAULT_LOG_RANGE - 1;
      this.chunkSize = Math.min(DEFAULT_ANS0_CHUNK_SIZE << (8*order), MAX_CHUNK_SIZE);

      for (int i=0; i<dim; i++)
      {
         this.freqs[i] = new int[257];
         this.symbols[i] = new Symbol[256];
      }
   }


   // Compute cumulated frequencies and encode header
   private int updateFrequencies(int[][] frequencies, int lr)
   {
      int res = 0;
      final int endk = 255*this.order + 1;
      this.bitstream.writeBits(lr-8, 3); // logRange
      final int[] alphabet = new int[256];

      for (int k=0; k<endk; k++)
      {
         final int[] f = frequencies[k];
         final Symbol[] symb = this.symbols[k];
         final int alphabetSize = EntropyUtils.normalizeFrequencies(f, alphabet, f[256], 1<<lr);

         if (alphabetSize > 0)
         {
            int sum = 0;

            for (int i=0, count=0; (i<256) && (count<alphabetSize); i++)
            {
               if (f[i] == 0)
                  continue;

               symb[i].reset(sum, f[i], lr);
               sum += f[i];
               count++;
            }
         }

         this.encodeHeader(alphabetSize, alphabet, f, lr);
         res += alphabetSize;
      }

      return res;
   }


   // Encode alphabet and frequencies
   protected boolean encodeHeader(int alphabetSize, int[] alphabet, int[] frequencies, int lr)
   {
      final int encoded = EntropyUtils.encodeAlphabet(this.bitstream, alphabet, alphabetSize);

      if (encoded < 0)
         return false;

      if (encoded <= 1)
         return true;

      final int chkSize = (alphabetSize >= 64) ? 8 : 6;
      int llr = 3;

      while (1<<llr <= lr)
         llr++;

      // Encode all frequencies (but the first one) by chunks
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


   // Dynamically compute the frequencies for every chunk of data in the block
   @Override
   public int encode(byte[] block, int blkptr, int count)
   {
      if ((block == null) || (blkptr+count > block.length) || (blkptr < 0) || (count < 0))
         return -1;

      if (count <= 32)
      {
         this.bitstream.writeBits(block, blkptr, 8*count);
         return count;
      }

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

      final int size = Math.max(Math.min(sizeChunk+(sizeChunk>>3), 2*count), 65536);

      // Add some padding
      if (this.buffer.length < size)
         this.buffer = new byte[size];

      while (startChunk < end)
      {
         final int endChunk = Math.min(startChunk+sizeChunk, end);
         final int alphabetSize = this.rebuildStatistics(block, startChunk, endChunk, this.logRange);

         // Skip chunk if only one symbol
         if ((alphabetSize <= 1) && (this.order == 0))
         {
            startChunk = endChunk;
            continue;
         }

         this.encodeChunk(block, startChunk, endChunk);
         startChunk = endChunk;
      }

      return count;
   }

   private int encodeSymbol(int[] idx, int st, Symbol sym)
   {
      final int x = (st >= sym.xMax) ? 1 : 0;
      this.buffer[idx[0]] = (byte) st;
      idx[0] -= x;
      this.buffer[idx[0]] = (byte) (st>>8);
      idx[0] -= x;
      st >>= (-x & 16);

      // Compute next ANS state
      // C(s,x) = M floor(x/q_s) + mod(x,q_s) + b_s where b_s = q_0 + ... + q_{s-1}
      // st = ((st / freq) << lr) + (st % freq) + cumFreq[prv];
      final int q = (int)((st*sym.invFreq) >> sym.invShift);
      return st + sym.bias + q*sym.cmplFreq;
   }


   private void encodeChunk(byte[] block, int start, int end)
   {
      int st0 = ANS_TOP;
      int st1 = ANS_TOP;
      int st2 = ANS_TOP;
      int st3 = ANS_TOP;
      int n = this.buffer.length - 1;
      final int end4 = start + ((end-start) & -4);

      for (int i=end-1; i>=end4; i--)
         this.buffer[n--] = block[i];

      final int[] idx = new int[] { n };

      if (this.order == 0)
      {
         final Symbol[] symb = this.symbols[0];

         for (int i=end4-1; i>start; i-=4)
         {
            st0 = encodeSymbol(idx, st0, symb[block[i] & 0xFF]);
            st1 = encodeSymbol(idx, st1, symb[block[i-1] & 0xFF]);
            st2 = encodeSymbol(idx, st2, symb[block[i-2] & 0xFF]);
            st3 = encodeSymbol(idx, st3, symb[block[i-3] & 0xFF]);
         }
      }
      else
      {  // order 1
         final int quarter = (end4-start) >> 2;
         int i0 = start + 1*quarter - 2;
         int i1 = start + 2*quarter - 2;
         int i2 = start + 3*quarter - 2;
         int i3 = end4 - 2;
         int prv0 = block[i0+1] & 0xFF;
         int prv1 = block[i1+1] & 0xFF;
         int prv2 = block[i2+1] & 0xFF;
         int prv3 = block[i3+1] & 0xFF;

         for ( ; i0>=start; i0--, i1--, i2--, i3--)
         {
            final int cur0 = block[i0] & 0xFF;
            st0 = encodeSymbol(idx, st0, this.symbols[cur0][prv0]);
            final int cur1 = block[i1] & 0xFF;
            st1 = encodeSymbol(idx, st1, this.symbols[cur1][prv1]);
            final int cur2 = block[i2] & 0xFF;
            st2 = encodeSymbol(idx, st2, this.symbols[cur2][prv2]);
            final int cur3 = block[i3] & 0xFF;
            st3 = encodeSymbol(idx, st3, this.symbols[cur3][prv3]);
            prv0 = cur0;
            prv1 = cur1;
            prv2 = cur2;
            prv3 = cur3;
         }

         // Last symbols
         st0 = encodeSymbol(idx, st0, this.symbols[0][prv0]);
         st1 = encodeSymbol(idx, st1, this.symbols[0][prv1]);
         st2 = encodeSymbol(idx, st2, this.symbols[0][prv2]);
         st3 = encodeSymbol(idx, st3, this.symbols[0][prv3]);
      }

      n = idx[0];
      n++;

      // Write chunk size
      EntropyUtils.writeVarInt(this.bitstream, this.buffer.length-n);

      // Write final ANS state
      this.bitstream.writeBits(st0, 32);
      this.bitstream.writeBits(st1, 32);
      this.bitstream.writeBits(st2, 32);
      this.bitstream.writeBits(st3, 32);

      // Write encoded data to bitstream
      if (this.buffer.length != n)
         this.bitstream.writeBits(this.buffer, n, 8*(this.buffer.length-n));
   }


   // Compute chunk frequencies, cumulated frequencies and encode chunk header
   private int rebuildStatistics(byte[] block, int start, int end, int lr)
   {
      final int dim = 255*this.order + 1;

      for (int i=0; i<dim; i++)
      {
         final int[] f = this.freqs[i];

         for (int j=0; j<f.length; j++)
            f[j] = 0;
      }

      if (this.order == 0)
      {
         Global.computeHistogramOrder0(block, start, end, this.freqs[0], true);
      }
      else
      {
         final int quarter = (end-start) >> 2;

         if (quarter == 0)
         {
            Global.computeHistogramOrder1(block, start, end, this.freqs, true);
         }
         else
         {
            Global.computeHistogramOrder1(block, start+0*quarter, start+1*quarter, this.freqs, true);
            Global.computeHistogramOrder1(block, start+1*quarter, start+2*quarter, this.freqs, true);
            Global.computeHistogramOrder1(block, start+2*quarter, start+3*quarter, this.freqs, true);
            Global.computeHistogramOrder1(block, start+3*quarter, start+4*quarter, this.freqs, true);
         }
      }

      return this.updateFrequencies(this.freqs, lr);
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



   static class Symbol
   {
      int xMax; // (Exclusive) upper bound of pre-normalization interval
      int bias; // Bias
      int cmplFreq; // Complement of frequency: (1 << scale_bits) - freq
      int invShift; // Reciprocal shift
      long invFreq; // Fixed-point reciprocal frequency


      public void reset(int cumFreq, int freq, int logRange)
      {
         // Make sure xMax is a positive int32
         if (freq >= 1<<logRange)
            freq = (1<<logRange) - 1;

         this.xMax = ((ANS_TOP>>>logRange) << 16) * freq;
         this.cmplFreq = (1<<logRange) - freq;

         if (freq < 2)
         {
            this.invFreq = 0xFFFFFFFFL;
            this.invShift = 32;
            this.bias = cumFreq + (1<<logRange) - 1;
         }
         else
         {
            int shift = 0;

            while (freq > (1<<shift))
                shift++;

            // Alverson, "Integer Division using reciprocals"
            this.invFreq = (((1L<<(shift+31))+freq-1) / freq) & 0xFFFFFFFFL;
            this.invShift = 32 + shift - 1;
            this.bias = cumFreq;
         }
      }
   }
}
