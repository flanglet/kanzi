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

package io.github.flanglet.kanzi.transform;

import java.util.Map;
import io.github.flanglet.kanzi.ArrayComparator;
import io.github.flanglet.kanzi.ByteTransform;
import io.github.flanglet.kanzi.Global;
import io.github.flanglet.kanzi.SliceByteArray;
import io.github.flanglet.kanzi.Memory.LittleEndian;
import io.github.flanglet.kanzi.util.sort.QuickSort;


// A simple one-pass UTF8 codec that replaces code points with indexes
public class UTFCodec implements ByteTransform
{
   private static final int[] SIZES = { 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 2, 2, 3, 4 };

   private static final int[] LEN_SEQ = {
      1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
      1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
      1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
      1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
      1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
      1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
      1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
      1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
      2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
      3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
      4, 4, 4, 4, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
   };

   private static final int MIN_BLOCK_SIZE = 1024;

   private final Map<String, Object> ctx;
   private final boolean isBsVersion3;


   public UTFCodec()
   {
      this.ctx = null;
      this.isBsVersion3 = false;
   }


   public UTFCodec(Map<String, Object> ctx)
   {
      this.ctx = ctx;
      int bsVersion = 6;

      if (ctx != null)
        bsVersion = (Integer) ctx.getOrDefault("bsVersion", 6);

      this.isBsVersion3 = bsVersion < 4;
   }


   @Override
   public boolean forward(SliceByteArray input, SliceByteArray output)
   {
      if (input.length == 0)
         return true;

      if (input.length < MIN_BLOCK_SIZE)
         return false;

      if (input.array == output.array)
         return false;

      final int count = input.length;

      if (output.length - output.index < this.getMaxEncodedLength(count))
         return false;

      final byte[] src = input.array;
      final byte[] dst = output.array;
      int srcIdx = input.index;
      boolean mustValidate = true;

      if (this.ctx != null)
      {
         Global.DataType dt = (Global.DataType) this.ctx.getOrDefault("dataType",
            Global.DataType.UNDEFINED);

         if ((dt != Global.DataType.UNDEFINED) && (dt != Global.DataType.UTF8))
            return false;

         mustValidate = dt != Global.DataType.UTF8;
      }

      final int srcEnd = input.index + count - 4;
      int start = 0;

      if (((src[srcIdx+start]&0xFF) == 0xEF) && ((src[srcIdx+start+1]&0xFF) == 0xBB) &&
          ((src[srcIdx+start+2]&0xFF) == 0xBF))
      {
         // Byte Order Mark (BOM) 0xEFBBBF
         start = 3;
      }
      else
      {
         // First (possibly) invalid symbols (due to block truncation)
         while ((start < 4) && (LEN_SEQ[src[srcIdx+start]&0xFF] == 0))
            start++;
      }

      if ((mustValidate == true) && (validate(src, srcIdx+start, srcEnd)) == false)
         return false;

      if (this.ctx != null)
          this.ctx.put("dataType", Global.DataType.UTF8);

      // 1-3 bit size + (7 or 11 or 16 or 21) bit payload
      // 3 MSBs indicate symbol size (limit map size to 22 bits)
      // 000 -> 7 bits
      // 001 -> 11 bits
      // 010 -> 16 bits
      // 1xx -> 21 bits
      int[] aliasMap = new int[1<<22];
      SymbolData[] symb = new SymbolData[32768];
      int[] ranks = new int[32768];
      int n = 0;
      boolean res = true;
      int[] val = new int[1];

      for (int i=srcIdx+start; i<srcEnd; )
      {
         final int s = pack(src, i, val);
         res = s != 0;

         // Validation of longer sequences
         // Third byte in [0x80..0xBF]
         res &= ((s != 3) || (((src[i+2]&0xFF) >= 0x80) && ((src[i+2]&0xFF) <= 0xBF)));
         // Combine third and fourth bytes
         int val2 = ((src[i+2]&0xFF) << 8) | (src[i+3]&0xFF);
         // Third and fourth bytes in [0x80..0xBF]
         res &= ((s != 4) || ((val2 & 0xC0C0) == 0x8080));

         if (aliasMap[val[0]] == 0)
         {
            ranks[n] = n;
            SymbolData sb = new SymbolData();
            sb.sym = val[0];
            symb[n] = sb;
            n++;
            res &= (n < 32768);
         }

         if (res == false)
            break;

         aliasMap[val[0]]++;
         i += s;
      }

      final int maxTarget = count - (count/10);

      if ((res == false) || (n == 0) || ((3*n+6) >= maxTarget))
         return false;

      for (int i=0; i<n; i++)
         symb[i].freq = aliasMap[symb[i].sym];

      // Sort ranks by increasing frequencies
      new QuickSort(new SymbolComparator(symb)).sort(ranks, 0, n);
      int dstIdx = output.index + 2;

      // Emit map length then map data
      dst[dstIdx++] = (byte) (n>>8);
      dst[dstIdx++] = (byte) n;

      int estimate = dstIdx + 6;

      for (int i=0; i<n; i++)
      {
         final int r = ranks[n-1-i];
         final int s = symb[r].sym;
         dst[dstIdx] = (byte) (s>>16);
         dst[dstIdx + 1] = (byte) (s>>8);
         dst[dstIdx + 2] = (byte) s;
         dstIdx += 3;
         estimate += ((i<128) ? symb[r].freq : 2*symb[r].freq);
         aliasMap[s] = (i<128) ? i : 0x10080 | ((i << 1) & 0xFF00) | (i & 0x7F);
      }

      if (estimate >= maxTarget)
         return false;

      // Emit first (possibly) invalid symbols (due to block truncation)
      for (int i=0; i<start; i++)
         dst[dstIdx++] = src[srcIdx+i];

      srcIdx += start;

      // Emit aliases
      while (srcIdx < srcEnd)
      {
         srcIdx += pack(src, srcIdx, val);
         final int alias = aliasMap[val[0]];
         dst[dstIdx++] = (byte) (alias);
         dst[dstIdx] = (byte) (alias>>>8);
         dstIdx += (alias>>>16);
      }

      dst[0] = (byte) start;
      dst[1] = (byte) (srcIdx-srcEnd);

      // Emit last (possibly) invalid symbols (due to block truncation)
      while ((srcIdx < srcEnd+4) && (dstIdx < maxTarget))
         dst[dstIdx++] = src[srcIdx++];

      input.index += srcIdx;
      output.index += dstIdx;
      return dstIdx < maxTarget;
   }


   @Override
   public boolean inverse(SliceByteArray input, SliceByteArray output)
   {
      if (input.length == 0)
         return true;

      if (input.length < 4)
         return false;

      if (input.array == output.array)
         return false;

      final int count = input.length;
      final byte[] src = input.array;
      final byte[] dst = output.array;
      int srcIdx = input.index;
      int dstIdx = output.index;
      final int start = src[srcIdx] & 0x03;
      final int adjust = src[srcIdx+1] & 0x03; // adjust end of regular processing
      final int n = ((src[srcIdx+2]&0xFF) << 8) + (src[srcIdx+3]&0xFF);
      final int srcEnd = input.index + count - 4 + adjust;
      final int dstEnd = output.length - 4;

      // Protect against invalid map size value
      if ((n == 0) || (n >= 32768) || (3*n >= count))
         return false;

      // Fill map with invalid value
      UTFSymbol[] m = new UTFSymbol[32768];
      srcIdx += 4;

      // Build inverse mapping
      for (int i=0; i<n; i++)
      {
         m[i] = new UTFSymbol();
         final int s = ((src[srcIdx]&0xFF) << 16) | ((src[srcIdx+1]&0xFF) << 8) | (src[srcIdx+2]&0xFF);
         final int sl = (isBsVersion3 == true) ? unpackV0(s, m[i]) : unpackV1(s, m[i]);

         if (sl == 0)
            return false;

         m[i].length = sl;
         srcIdx += 3;
      }

      boolean res = true;

      if (dstEnd < 0)
         return false;

      for (int i=0; i<start; i++)
         dst[dstIdx++] = src[srcIdx++];

      // Emit data
      while ((srcIdx < srcEnd) && (dstIdx < dstEnd))
      {
         int alias = src[srcIdx++] & 0xFF;

         if (alias >= 128)
            alias = ((src[srcIdx++] & 0xFF) << 7) + (alias & 0x7F);

         UTFSymbol s = m[alias];
         LittleEndian.writeInt32(dst, dstIdx, s.value);
         dstIdx += s.length;
      }

      if (res == true)
      {
         if ((srcIdx < srcEnd) || (dstIdx >= dstEnd-count+srcEnd))
         {
            res = false;
         }
         else
         {
            for (int i=srcEnd; i<count; i++)
               dst[dstIdx++] = src[srcIdx++];
         }
      }

      input.index += srcIdx;
      output.index += dstIdx;
      return res;
   }


   @Override
   public int getMaxEncodedLength(int srcLength)
   {
      return srcLength + 8192;
   }


   // A quick partial validation
   // A more complete validation is done during processing for the remaining cases
   // (rules for 3 and 4 byte sequences)
   private static boolean validate(byte[] block, int start, int end)
   {
      int[] freqs0 = new int[256];
      final int[][] freqs1 = new int[256][256];

      for (int i=0; i<256; i++)
         freqs1[i] = new int[256];

      int prv = 0;
      final int count = end - start;
      final int end4 = start + (count & -4);

      // Unroll loop
      for (int i=start; i<end4; i+=4)
      {
         final int cur0 = block[i]   & 0xFF;
         final int cur1 = block[i+1] & 0xFF;
         final int cur2 = block[i+2] & 0xFF;
         final int cur3 = block[i+3] & 0xFF;
         freqs0[cur0]++;
         freqs0[cur1]++;
         freqs0[cur2]++;
         freqs0[cur3]++;
         freqs1[prv][cur0]++;
         freqs1[cur0][cur1]++;
         freqs1[cur1][cur2]++;
         freqs1[cur2][cur3]++;
         prv = cur3;

         if ((i & 0xFFF) == 0)
         {
            // Early check rules for 1 byte
            int sum = freqs0[0xC0] + freqs0[0xC1];

            for (int j = 0xF5; j <= 0xFF; j++)
                sum += freqs0[j];

            if (sum != 0)
                return false;
         }
      }

      if (end4 != end)
      {
         for (int i=end4; i<end; i++)
         {
            final int cur = block[i] & 0xFF;
            freqs0[cur]++;
            freqs1[prv][cur]++;
            prv = cur;
         }

         // Check rules for 1 byte
         int sum = freqs0[0xC0] + freqs0[0xC1];

         for (int i = 0xF5; i <= 0xFF; i++)
             sum += freqs0[i];

         if (sum != 0)
             return false;
      }

      // Valid UTF-8 sequences
      // See Unicode 16 Standard - UTF-8 Table 3.7
      // U+0000..U+007F          00..7F
      // U+0080..U+07FF          C2..DF 80..BF
      // U+0800..U+0FFF          E0 A0..BF 80..BF
      // U+1000..U+CFFF          E1..EC 80..BF 80..BF
      // U+D000..U+D7FF          ED 80..9F 80..BF 80..BF
      // U+E000..U+FFFF          EE..EF 80..BF 80..BF
      // U+10000..U+3FFFF        F0 90..BF 80..BF 80..BF
      // U+40000..U+FFFFF        F1..F3 80..BF 80..BF 80..BF
      // U+100000..U+10FFFF      F4 80..8F 80..BF 80..BF
      int sum1 = 0;
      int sum2 = 0;

      // Check rules for first 2 bytes
      for (int i = 0; i < 256; i++)
      {
         // Exclude < 0xE0A0 || > 0xE0BF
         if ((i < 0xA0) || (i > 0xBF))
             sum1 += freqs1[0xE0][i];

         // Exclude < 0xED80 || > 0xEDE9F
         if ((i < 0x80) || (i > 0x9F))
             sum1 += freqs1[0xED][i];

         // Exclude < 0xF090 || > 0xF0BF
         if ((i < 0x90) || (i > 0xBF))
             sum1 += freqs1[0xF0][i];

         // Exclude < 0xF480 || > 0xF48F
         if ((i < 0x80) || (i > 0x8F))
             sum1 += freqs1[0xF4][i];

         if ((i < 0x80) || (i > 0xBF)) {
            // Exclude < 0x??80 || > 0x??BF with ?? in [C2..DF]
            for (int j = 0xC2; j <= 0xDF; j++)
               sum1 += freqs1[j][i];

            // Exclude < 0x??80 || > 0x??BF with ?? in [E1..EC]
            for (int j = 0xE1; j <= 0xEC; j++)
               sum1 += freqs1[j][i];

            // Exclude < 0x??80 || > 0x??BF with ?? in [F1..F3]
            sum1 += freqs1[0xF1][i];
            sum1 += freqs1[0xF2][i];
            sum1 += freqs1[0xF3][i];
            // Exclude < 0xEE80 || > 0xEEBF
            sum1 += freqs1[0xEE][i];
            // Exclude < 0xEF80 || > 0xEFBF
            sum1 += freqs1[0xEF][i];
        }
        else
        {
            // Count non-primary bytes
            sum2 += freqs0[i];
        }

        if (sum1 != 0)
            return false;
      }

      return sum2 >= (count / 8);
   }


   public static int pack(byte[] in, int idx, int[] out)
   {
      int s = SIZES[(in[idx]>>>4)&0x0F];

      switch (s) {
      case 1:
         out[0] = in[idx] & 0xFF;
         break;

      case 2:
         out[0] = (1 << 19) | ((in[idx+0] & 0xFF) << 8) | (in[idx+1] & 0xFF);
         break;

      case 3:
         out[0] = (2 << 19) | ((in[idx+0] & 0x0F) << 12) | ((in[idx+1] & 0x3F) << 6) |
                  (in[idx+2] & 0x3F);
         break;

      case 4:
         out[0] = (4 << 19) | ((in[idx+0] & 0x07) << 18) | ((in[idx+1] & 0x3F) << 12) |
                  ((in[idx+2] & 0x3F) << 6) | (in[idx+3] & 0x3F);
          break;

      default:
         out[0] = 0;
         s = 0; // signal invalid value
         break;
      }

      return s;
   }


   // Set the value in the UTFSymbol in little endian order to optimize the copy in the main loop
   public static int unpackV0(int in, UTFSymbol out)
   {
      int s = (in>>>21) + 1;

      switch (s) {
      case 1:
         out.value = in;
         break;

      case 2:
         out.value = ((in & 0xFF) << 8) | ((in >> 8) & 0xFF);
         break;

      case 3:
         out.value = (((in >> 12) & 0x0F) | 0xE0) | ((((in >>  6) & 0x3F) | 0x80) << 8) | (((in & 0x3F) | 0x80) << 16);
         break;

      case 4:
         out.value = (((in >> 18) & 0x07) | 0xF0) | ((((in >> 12) & 0x3F) | 0x80) << 8) |
                     ((((in >>  6) & 0x3F) | 0x80) << 16)  | (((in & 0x3F) | 0x80) << 24);
         break;

      default:
         s = 0; // signal invalid value
         break;
      }

      return s;
   }


   // Set the value in the UTFSymbol in little endian order to optimize the copy in the main loop
   public static int unpackV1(int in, UTFSymbol out)
   {
      int s;

      switch (in >>> 19) {
      case 0:
         out.value = in;
         s = 1;
         break;

      case 1:
         out.value = ((in & 0xFF) << 8) | ((in >> 8) & 0xFF);
         s = 2;
         break;

      case 2:
         out.value = (((in >> 12) & 0x0F) | 0xE0) | ((((in >> 6) & 0x3F) | 0x80) << 8) | (((in & 0x3F) | 0x80) << 16);
         s = 3;
         break;

      case 4:
      case 5:
      case 6:
      case 7:
         out.value = (((in >> 18) & 0x07) | 0xF0) | ((((in >> 12) & 0x3F) | 0x80) << 8) |
               ((((in >>  6) & 0x3F) | 0x80) << 16) | (((in & 0x3F) | 0x80) << 24);

         s = 4;
         break;

      default:
         s = 0; // signal invalid value
         break;
      }

      return s;
   }


   static class UTFSymbol
   {
      int value;
      int length;
   }


   static class SymbolData
   {
      int sym;
      int freq;
   }


   static class SymbolComparator implements ArrayComparator
   {
      private final SymbolData[] data;


      public SymbolComparator(SymbolData[] data)
      {
         this.data = data;
      }

      @Override
      public int compare(int lidx, int ridx)
      {
         final int res = this.data[lidx].freq - this.data[ridx].freq;

         return (res != 0) ? res :
            this.data[lidx].sym - this.data[ridx].sym;
      }
   }
}
