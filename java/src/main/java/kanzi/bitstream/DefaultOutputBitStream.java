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

package kanzi.bitstream;

import kanzi.BitStreamException;
import java.io.IOException;
import java.io.OutputStream;
import kanzi.Memory;
import kanzi.OutputBitStream;


public final class DefaultOutputBitStream implements OutputBitStream
{
   private final OutputStream os;
   private byte[] buffer;
   private boolean closed;
   private int position;  // index of current byte in buffer
   private int availBits; // bits not consumed in current
   private long written;
   private long current;  // cached bits


   public DefaultOutputBitStream(OutputStream os, int bufferSize)
   {
      if (os == null)
         throw new NullPointerException("Invalid null output stream parameter");

      if (bufferSize < 1024)
         throw new IllegalArgumentException("Invalid buffer size (must be at least 1024)");

      if (bufferSize > 1<<28)
         throw new IllegalArgumentException("Invalid buffer size (must be at most 268435456)");

      if ((bufferSize & 7) != 0)
         throw new IllegalArgumentException("Invalid buffer size (must be a multiple of 8)");

      this.os = os;
      this.buffer = new byte[bufferSize];
      this.availBits = 64;
   }


   // Write least significant bit of the input integer. Trigger exception if stream is closed
   @Override
   public void writeBit(int bit)
   {
      if (this.availBits <= 1) // availBits = 0 if stream is closed => force pushCurrent()
      {
         this.current |= (bit & 1);
         this.pushCurrent();
      }
      else
      {
         this.availBits--;
         this.current |= ((long) (bit & 1) << this.availBits);
      }
   }


   // Write 'count' (in [1..64]) bits. Trigger exception if stream is closed
   @Override
   public int writeBits(long value, int count)
   {
      if (count == 0)
         return 0;

      if (count > 64)
         throw new IllegalArgumentException("Invalid bit count: "+count+" (must be in [1..64])");

      this.current |= ((value << (64 - count)) >>> (64 - this.availBits));
      int remaining = count;

      if (count >= this.availBits) {
         remaining -= this.availBits;
         pushCurrent();

         if (remaining != 0)
            this.current = value << (64 - remaining);
      }

      this.availBits -= remaining;
      return count;
   }


   @Override
   public int writeBits(byte[] bits, int start, int count)
   {
      if (this.isClosed() == true)
         throw new BitStreamException("Stream closed", BitStreamException.STREAM_CLOSED);

      if ((count>>3) > bits.length-start)
         throw new IllegalArgumentException("Invalid length: "+count+" (must be in [1.." +
            (((long)(bits.length-start))<<3) + "])");

      int remaining = count;

      // Byte aligned cursor ?
      if ((this.availBits & 7) == 0)
      {
         // Fill up this.current
         while ((this.availBits != 64) && (remaining >= 8))
         {
            this.writeBits((long) bits[start], 8);
            start++;
            remaining -= 8;
         }

         // Copy bits array to internal buffer
         while ((remaining>>3) >= this.buffer.length-this.position)
         {
            System.arraycopy(bits, start, this.buffer, this.position, this.buffer.length-this.position);
            start += (this.buffer.length-this.position);
            remaining -= ((this.buffer.length-this.position)<<3);
            this.position = this.buffer.length;
            this.flush();
         }

         final int r = (remaining>>6) << 3;

         if (r > 0)
         {
            System.arraycopy(bits, start, this.buffer, this.position, r);
            this.position += r;
            start += r;
            remaining -= (r<<3);
         }
      }
      else
      {
         // Not byte aligned
         if (remaining >= 64)
         {
            final int r = 64 - this.availBits;

            while (remaining >= 64)
            {
               final long value = Memory.BigEndian.readLong64(bits, start);
               this.current |= (value >>> r);
               this.pushCurrent();
               this.current = (value << -r);
               start += 8;
               remaining -= 64;
            }

            this.availBits -= r;
         }
      }

      // Last bytes
      while (remaining >= 8)
      {
         this.writeBits((long) (bits[start]&0xFF), 8);
         start++;
         remaining -= 8;
      }

      if (remaining > 0)
         this.writeBits((long) (bits[start]>>>(8-remaining)), remaining);

      return count;
   }


   // Push 64 bits of current value into buffer.
   private void pushCurrent()
   {
      Memory.BigEndian.writeLong64(this.buffer, this.position, this.current);
      this.availBits = 64;
      this.current = 0;
      this.position += 8;

      if (this.position >= this.buffer.length)
         this.flush();
   }


   // Write buffer to underlying stream
   private void flush() throws BitStreamException
   {
      if (this.isClosed() == true)
         throw new BitStreamException("Stream closed", BitStreamException.STREAM_CLOSED);

      try
      {
         if (this.position > 0)
         {
            this.os.write(this.buffer, 0, this.position);
            this.written += (this.position << 3);
            this.position = 0;
         }
      }
      catch (IOException e)
      {
         throw new BitStreamException(e.getMessage(), BitStreamException.INPUT_OUTPUT);
      }
   }


   @Override
   public void close()
   {
      if (this.isClosed() == true)
         return;

      final int savedBitIndex = this.availBits;
      final int savedPosition = this.position;
      final long savedCurrent = this.current;

      try
      {
         // Push last bytes (the very last byte may be incomplete)
         for (int shift=56; this.availBits<64; shift-=8)
         {
            this.buffer[this.position++] = (byte) (this.current>>shift);
            this.availBits += 8;
         }

         this.written -= (this.availBits-64);
         this.availBits = 64;
         this.flush();
      }
      catch (BitStreamException e)
      {
         // Revert fields to allow subsequent attempts in case of transient failure
         this.position = savedPosition;
         this.availBits = savedBitIndex;
         this.current = savedCurrent;
         throw e;
      }

      try
      {
         this.os.flush();
      }
      catch (IOException e)
      {
         throw new BitStreamException(e, BitStreamException.INPUT_OUTPUT);
      }

      this.closed = true;
      this.position = 0;
      this.availBits = 0;
      this.written -= 64; // adjust because this.availBits = 0

      // Reset fields to force a flush() and trigger an exception
      // on writeBit() or writeBits()
      this.buffer = new byte[8];
   }


   // Return number of bits written so far
   @Override
   public long written()
   {
      // Number of bits flushed + bytes written in memory + bits written in memory
      return this.written + (this.position<<3) + (64-this.availBits);
   }


   public boolean isClosed()
   {
      return this.closed;
   }
}