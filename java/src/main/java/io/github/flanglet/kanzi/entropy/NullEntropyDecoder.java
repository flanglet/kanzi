/*
 * Kanzi is a modern, modular, portable, and efficient lossless data compressor.
 *
 * Copyright (C) 2011-2025 Frederic Langlet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.flanglet.kanzi.entropy;

import io.github.flanglet.kanzi.EntropyDecoder;
import io.github.flanglet.kanzi.InputBitStream;


/**
 * <p>Null entropy decoder.
 * This decoder does not perform any actual decompression; it simply reads
 * the data directly from the provided {@link InputBitStream}.</p>
 *
 * <p>It acts as a pass-through mechanism, useful when no entropy coding
 * is applied to the data, or when the data is already in its final form.</p>
 */
public final class NullEntropyDecoder implements EntropyDecoder
{
   private final InputBitStream bitstream;


   /**
    * Creates a new {@code NullEntropyDecoder}.
    *
    * @param bitstream The {@link InputBitStream} to read data from.
    * @throws NullPointerException if {@code bitstream} is {@code null}.
    */
   public NullEntropyDecoder(InputBitStream bitstream)
   {
      if (bitstream == null)
         throw new NullPointerException("Invalid null bitstream parameter");

      this.bitstream = bitstream;
   }

   /**
    * Decodes a block of data by reading it directly from the bitstream.
    * <p>
    * This method reads {@code count} bytes from the bitstream into the provided {@code block} array.
    * </p>
    * @param block The byte array to decode into.
    * @param blkptr The starting position in the block.
    * @param count The number of bytes to decode.
    * @return The number of bytes decoded, or -1 if an error occurs (e.g., invalid parameters).
    */
   @Override
   public int decode(byte[] block, int blkptr, int count)
   {
      if ((block == null) || (blkptr + count > block.length) || (blkptr < 0) || (count < 0))
         return -1;

      int res = 0;

      while (count > 0)
      {
         final int ckSize = (count < 1<<23) ? count : 1<<23;
         res += (this.bitstream.readBits(block, blkptr, 8*ckSize) >> 3);
         blkptr += ckSize;
         count -= ckSize;
      }

      return res;
   }

   /**
    * Decodes a single byte by reading it directly from the bitstream.
    * @return The decoded byte.
    */
   public byte decodeByte()
   {
     return (byte) this.bitstream.readBits(8);
   }

   /**
    * Returns the {@link InputBitStream} used by this decoder.
    * @return The {@link InputBitStream}.
    */
   @Override
   public InputBitStream getBitStream()
   {
     return this.bitstream;
   }

   /**
    * Disposes of any resources used by the decoder.
    * This method currently does nothing as there are no specific resources to release.
    */
   @Override
   public void dispose()
   {
   }
}
