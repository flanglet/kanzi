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

import java.util.Arrays;
import java.util.Map;
import io.github.flanglet.kanzi.EntropyDecoder;
import io.github.flanglet.kanzi.InputBitStream;
import io.github.flanglet.kanzi.Memory;
import io.github.flanglet.kanzi.SliceByteArray;

/**
 * <p>
 * Implementation of an FPAQ decoder. This class is derived from fpaq0r by
 * Matt Mahoney and Alexander Ratushnyak, and is a simple (and fast) adaptive
 * entropy bit coder.
 * </p>
 *
 * <p>
 * It uses a range coding approach where the current range is updated based on
 * the predicted probability of the next bit. The prediction is based on a
 * context formed by previous bits.
 * </p>
 *
 * <p>
 * The decoding process involves reading bits from an {@link InputBitStream},
 * updating the range, and normalizing the range by reading more bits when
 * the range becomes too small.
 * </p>
 *
 * @see <a href="http://mattmahoney.net/dc/#fpaq0">fpaq0 by Matt Mahoney</a>
 */
public class FPAQDecoder implements EntropyDecoder {
   /**
    * The top value for the range, used in range coding.
    * This value defines the maximum possible range.
    */
   private static final long TOP = 0x00FFFFFFFFFFFFFFL;
   /**
    * A mask used to check if the most significant bits of the low and
    * (low + range) values are the same, indicating that bits can be
    * shifted out.
    */
   private static final long MASK_24_56 = 0x00FFFFFFFF000000L;
   /**
    * A mask used to keep the lower 56 bits of a long.
    */
   private static final long MASK_0_56 = 0x00FFFFFFFFFFFFFFL;
   /**
    * A mask used to keep the lower 32 bits of a long.
    */
   private static final long MASK_0_32 = 0x00000000FFFFFFFFL;
   /**
    * The default chunk size for processing data.
    */
   private static final int DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024;
   /**
    * The maximum allowed block size.
    */
   private static final int MAX_BLOCK_SIZE = 1 << 30;
   /**
    * The scaling factor for probabilities.
    */
   private static final int PSCALE = 65536;

   /**
    * The lower bound of the current range.
    */
   private long low;
   /**
    * The upper bound of the current range.
    */
   private long high;
   /**
    * The current value read from the bitstream, representing a point within the
    * range.
    */
   private long current;
   /**
    * The input bitstream from which compressed data is read.
    */
   private final InputBitStream bitstream;
   /**
    * A {@link SliceByteArray} used as a buffer for reading data from the
    * bitstream.
    */
   private SliceByteArray sba;
   /**
    * A 2D array storing probability models for different contexts.
    * `probs[i][j]` represents the probability of the next bit being 1
    * in context `j` for a specific context level `i`.
    */
   private final int[][] probs; // probability of bit=1
   /**
    * A pointer to the current probability array being used, based on the context.
    */
   private int[] p; // pointer to current prob
   /**
    * The current context, formed by previous bits.
    */
   private int ctx; // previous bits
   /**
    * Flag indicating if the bitstream version is 3 or older.
    */
   private final boolean isBsVersion3;

   /**
    * Creates a new {@code FPAQDecoder}.
    *
    * @param bitstream The {@link InputBitStream} to read compressed data from.
    * @param ctx       A map containing additional context information for
    *                  decompression.
    * @throws NullPointerException if {@code bitstream} is {@code null}.
    */
   public FPAQDecoder(InputBitStream bitstream, Map<String, Object> ctx) {
      if (bitstream == null)
         throw new NullPointerException("FPAQ codec: Invalid null bitstream parameter");

      // Defer stream reading. We are creating the object, we should not do any I/O
      this.low = 0L;
      this.high = TOP;
      this.bitstream = bitstream;
      this.sba = new SliceByteArray(new byte[0], 0);
      this.ctx = 1;
      this.probs = new int[4][256];
      this.p = this.probs[0];

      for (int i = 0; i < 4; i++)
         Arrays.fill(this.probs[i], PSCALE >> 1);

      int bsVersion = 4;

      if (ctx != null)
         bsVersion = (Integer) ctx.getOrDefault("bsVersion", 4);

      this.isBsVersion3 = bsVersion < 4;
   }

   /**
    * Decodes a block of data.
    * <p>
    * This method reads compressed data from the internal bitstream and decodes it
    * into the provided byte array.
    * </p>
    *
    * @param block  The byte array to decode into.
    * @param blkptr The starting position in the block.
    * @param count  The number of bytes to decode.
    * @return The number of bytes decoded, or -1 if an error occurs (e.g., invalid
    *         parameters).
    */
   @Override
   public int decode(byte[] block, int blkptr, int count) {
      if ((block == null) || (blkptr + count > block.length) || (blkptr < 0) || (count < 0) || (count > MAX_BLOCK_SIZE))
         return -1;

      if (count == 0)
         return 0;

      int startChunk = blkptr;
      final int end = blkptr + count;

      // Read bit array from bitstream and decode chunk
      while (startChunk < end) {
         final int szBytes = EntropyUtils.readVarInt(this.bitstream);

         // Sanity check
         if (szBytes >= 2 * count)
            return 0;

         final int bufSize = Math.max(szBytes + (szBytes >> 2), 1024);

         if (this.sba.array.length < bufSize)
            this.sba.array = new byte[bufSize];

         this.current = this.bitstream.readBits(56);

         if (bufSize > szBytes)
            Arrays.fill(this.sba.array, szBytes, bufSize, (byte) 0);

         this.bitstream.readBits(this.sba.array, 0, 8 * szBytes);
         this.sba.index = 0;
         final int chunkSize = Math.min(DEFAULT_CHUNK_SIZE, end - startChunk);
         final int endChunk = startChunk + chunkSize;
         this.p = this.probs[0];

         if (this.isBsVersion3 == true) {
            for (int i = startChunk; i < endChunk; i++) {
               this.ctx = 1;
               this.decodeBitV1(this.p[this.ctx] >>> 4);
               this.decodeBitV1(this.p[this.ctx] >>> 4);
               this.decodeBitV1(this.p[this.ctx] >>> 4);
               this.decodeBitV1(this.p[this.ctx] >>> 4);
               this.decodeBitV1(this.p[this.ctx] >>> 4);
               this.decodeBitV1(this.p[this.ctx] >>> 4);
               this.decodeBitV1(this.p[this.ctx] >>> 4);
               this.decodeBitV1(this.p[this.ctx] >>> 4);
               block[i] = (byte) this.ctx;
               this.p = this.probs[(this.ctx & 0xFF) >>> 6];
            }
         } else {
            for (int i = startChunk; i < endChunk; i++) {
               this.ctx = 1;
               this.decodeBitV2(this.p[this.ctx]);
               this.decodeBitV2(this.p[this.ctx]);
               this.decodeBitV2(this.p[this.ctx]);
               this.decodeBitV2(this.p[this.ctx]);
               this.decodeBitV2(this.p[this.ctx]);
               this.decodeBitV2(this.p[this.ctx]);
               this.decodeBitV2(this.p[this.ctx]);
               this.decodeBitV2(this.p[this.ctx]);
               block[i] = (byte) this.ctx;
               this.p = this.probs[(this.ctx & 0xFF) >>> 6];
            }
         }

         startChunk = endChunk;
      }

      return count;
   }

   /**
    * Decodes a single bit based on a given prediction using the V1 algorithm.
    * <p>
    * The range is split according to the prediction, and the bit is determined by
    * the current value.
    * The probability model for the current context is then updated based on the
    * decoded bit.
    * </p>
    *
    * @param pred The prediction value (probability) for the bit, typically in the
    *             range [0, PSCALE].
    * @return The decoded bit (0 or 1).
    */
   private int decodeBitV1(int pred) {
      // Calculate interval split
      // Written in a way to maximize accuracy of multiplication/division
      final long split = ((((this.high - this.low) >>> 4) * pred) >>> 8) + this.low;
      int bit;

      // Update probabilities
      if (split >= this.current) {
         bit = 1;
         this.high = split;
         this.p[this.ctx] -= ((this.p[this.ctx] - PSCALE + 64) >> 6);
         this.ctx = (this.ctx << 1) + 1;
      } else {
         bit = 0;
         this.low = -~split;
         this.p[this.ctx] -= (this.p[this.ctx] >> 6);
         this.ctx = this.ctx << 1;
      }

      // Read 32 bits from bitstream
      while (((this.low ^ this.high) & MASK_24_56) == 0)
         this.read();

      return bit;
   }

   /**
    * Decodes a single bit based on a given prediction using the V2 algorithm.
    * <p>
    * The range is split according to the prediction, and the bit is determined by
    * the current value.
    * The probability model for the current context is then updated based on the
    * decoded bit.
    * </p>
    *
    * @param pred The prediction value (probability) for the bit, typically in the
    *             range [0, PSCALE].
    * @return The decoded bit (0 or 1).
    */
   private int decodeBitV2(int pred) {
      // Calculate interval split
      // Written in a way to maximize accuracy of multiplication/division
      final long split = ((((this.high - this.low) >>> 8) * pred) >>> 8) + this.low;
      int bit;

      // Update probabilities
      if (split >= this.current) {
         bit = 1;
         this.high = split;
         this.p[this.ctx] -= ((this.p[this.ctx] - PSCALE + 64) >> 6);
         this.ctx = (this.ctx << 1) + 1;
      } else {
         bit = 0;
         this.low = -~split;
         this.p[this.ctx] -= (this.p[this.ctx] >> 6);
         this.ctx = this.ctx << 1;
      }

      // Read 32 bits from bitstream
      while (((this.low ^ this.high) & MASK_24_56) == 0)
         this.read();

      return bit;
   }

   /**
    * Reads 32 bits from the internal buffer and updates the range.
    * <p>
    * This method is called when the range becomes too small and needs to be
    * normalized.
    * </p>
    */
   protected void read() {
      this.low = (this.low << 32) & MASK_0_56;
      this.high = ((this.high << 32) | MASK_0_32) & MASK_0_56;
      final long val = Memory.BigEndian.readInt32(this.sba.array, this.sba.index) & 0xFFFFFFFFL;
      this.current = ((this.current << 32) | val) & MASK_0_56;
      this.sba.index += 4;
   }

   /**
    * Returns the {@link InputBitStream} used by this decoder.
    *
    * @return The {@link InputBitStream}.
    */
   @Override
   public InputBitStream getBitStream() {
      return this.bitstream;
   }

   /**
    * Disposes of any resources used by the decoder.
    * This method currently does nothing as there are no specific resources to
    * release.
    */
   @Override
   public void dispose() {
   }
}
