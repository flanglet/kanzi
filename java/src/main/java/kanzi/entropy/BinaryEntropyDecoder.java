/*
Copyright 2011-2017 Frederic Langlet
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


import kanzi.Predictor;
import kanzi.EntropyDecoder;
import kanzi.InputBitStream;
import kanzi.Memory;
import kanzi.SliceByteArray;


// This class is a generic implementation of a boolean entropy decoder
public class BinaryEntropyDecoder implements EntropyDecoder
{
   private static final long TOP        = 0x00FFFFFFFFFFFFFFL;
   private static final long MASK_24_56 = 0x00FFFFFFFF000000L;
   private static final long MASK_0_56  = 0x00FFFFFFFFFFFFFFL;
   private static final long MASK_0_32  = 0x00000000FFFFFFFFL;
   
   private final Predictor predictor;
   private long low;
   private long high;
   private long current;
   private final InputBitStream bitstream;
   private boolean initialized;
   private SliceByteArray sba;


   public BinaryEntropyDecoder(InputBitStream bitstream, Predictor predictor)
   {
      if (bitstream == null)
         throw new NullPointerException("Invalid null bitstream parameter");

      if (predictor == null)
         throw new NullPointerException("Invalid null predictor parameter");

      // Defer stream reading. We are creating the object, we should not do any I/O
      this.low = 0L;
      this.high = TOP;
      this.bitstream = bitstream;
      this.predictor = predictor;      
      this.sba = new SliceByteArray(new byte[0], 0);
   }


   @Override
   public int decode(byte[] array, int blkptr, int count)
   {
      if ((array == null) || (blkptr + count > array.length) || (blkptr < 0) || (count < 0))
         return -1;

      int length = EntropyUtils.readVarInt(this.bitstream);
      int n = 0;

      // Add some margin to avoid reallocating each time a block is bigger
      // Pay attention to potential int overflow
      if (this.sba.array.length < length+(length>>4))
         this.sba.array = new byte[length+(length>>4)];
      
      // Deferred initialization: the bitstream may not be ready at build time
      // Initialize 'current' with bytes read from the bitstream
      if (this.isInitialized() == false)
         this.initialize();
      
      while (length > 0)
      {
         final int sz = Math.min(length, 1<<28);
         this.bitstream.readBits(this.sba.array, n, 8*sz);
         n += sz;
         length -= sz;
      }
      
      this.sba.index = 0;
      final int end = blkptr + count;
      
      for (int i=blkptr; i<end; i++)
         array[i] = this.decodeByte();

      return count;
   }
   

   public final byte decodeByte()
   {
      return (byte) ((this.decodeBit() << 7)
            | (this.decodeBit() << 6)
            | (this.decodeBit() << 5)
            | (this.decodeBit() << 4)
            | (this.decodeBit() << 3)
            | (this.decodeBit() << 2)
            | (this.decodeBit() << 1)
            | this.decodeBit());
   }

    
   // Not thread safe
   public boolean isInitialized()
   {
      return this.initialized;
   }


   // Not thread safe
   public void initialize()
   {
      if (this.initialized == true)
         return;

      this.current = this.bitstream.readBits(56);
      this.initialized = true;
   }


   public int decodeBit()
   {
      // Calculate interval split
      // Written in a way to maximize accuracy of multiplication/division
      final long split = ((((this.high - this.low) >>> 4) * this.predictor.get()) >>> 8) + this.low;
      int bit;

      if (split >= this.current)
      {
         bit = 1;
         this.high = split;
      }
      else
      {
         bit = 0;
         this.low = -~split;
      }

       // Update predictor
      this.predictor.update(bit);

      // Read 32 bits from bitstream
      while (((this.low ^ this.high) & MASK_24_56) == 0)
         this.read();

      return bit;
   }


   protected void read()
   {
      this.low = (this.low<<32) & MASK_0_56;           
      this.high = ((this.high<<32) | MASK_0_32) & MASK_0_56;        
      final long val = Memory.BigEndian.readInt32(this.sba.array, this.sba.index) & 0xFFFFFFFFL;
      this.current = ((this.current<<32) | val) & MASK_0_56;
      this.sba.index += 4;
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
