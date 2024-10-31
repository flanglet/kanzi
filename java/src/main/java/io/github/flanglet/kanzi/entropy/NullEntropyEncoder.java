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

import io.github.flanglet.kanzi.EntropyEncoder;
import io.github.flanglet.kanzi.OutputBitStream;


// Null entropy encoder and decoder
// Pass through that writes the data directly to the bitstream
public final class NullEntropyEncoder implements EntropyEncoder
{
   private final OutputBitStream bitstream;


   public NullEntropyEncoder(OutputBitStream bitstream)
   {
      if (bitstream == null)
         throw new NullPointerException("Invalid null bitstream parameter");

      this.bitstream = bitstream;
   }


   @Override
   public int encode(byte[] block, int blkptr, int count)
   {
      if ((block == null) || (blkptr+count > block.length) || (blkptr < 0) || (count < 0))
         return -1;

      int res = 0;

      while (count > 0)
      {
         final int ckSize = (count < 1<<23) ? count : 1<<23;
         res += (this.bitstream.writeBits(block, blkptr, 8*ckSize) >> 3);
         blkptr += ckSize;
         count -= ckSize;
      }

      return res;
   }


   public void encodeByte(byte val)
   {
      this.bitstream.writeBits(val, 8);
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
