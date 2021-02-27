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

import java.io.IOException;
import java.io.InputStream;
import kanzi.Memory;
import kanzi.BitStreamException;
import kanzi.InputBitStream;


public final class DefaultInputBitStream implements InputBitStream
{
   private final InputStream is;
   private final byte[] buffer;
   private int position;  // index of current byte (consumed if bitIndex == -1)
   private int availBits; // bits not consumed in current
   private long read;
   private boolean closed;
   private int maxPosition;
   private long current;


   public DefaultInputBitStream(InputStream is, int bufferSize)
   {
      if (is == null)
         throw new NullPointerException("Invalid null input stream parameter");

      if (bufferSize < 1024)
         throw new IllegalArgumentException("Invalid buffer size (must be at least 1024)");

      if (bufferSize > 1<<28)
         throw new IllegalArgumentException("Invalid buffer size (must be at most 268435456)");

      if ((bufferSize & 7) != 0)
         throw new IllegalArgumentException("Invalid buffer size (must be a multiple of 8)");

      this.is = is;
      this.buffer = new byte[bufferSize];
      this.availBits = 0;
      this.maxPosition = -1;
   }


   // Return 1 or 0. Trigger exception if stream is closed
   @Override
   public int readBit() throws BitStreamException
   {
      if (this.availBits == 0)
         this.pullCurrent(); // Triggers an exception if stream is closed

      this.availBits--;
      return (int) (this.current >> this.availBits) & 1;
   }


   private int readFromInputStream(int count) throws BitStreamException
   {
      if (this.isClosed() == true)
         throw new BitStreamException("Stream closed", BitStreamException.STREAM_CLOSED);

      if (count == 0)
         return 0;

      int size = -1;

      try
      {
         size = this.is.read(this.buffer, 0, count);

         if (size <= 0)
         {
            throw new BitStreamException("No more data to read in the bitstream",
                    BitStreamException.END_OF_STREAM);
         }

         return size;
      }
      catch (IOException e)
      {
         throw new BitStreamException(e.getMessage(), BitStreamException.INPUT_OUTPUT);
      }
      finally
      {
         this.position = 0;
         this.read += (((long) this.maxPosition+1) << 3);
         this.maxPosition = (size <= 0) ? -1 : size - 1;
      }
   }


   // Return value of 'count' next bits as a long. Trigger exception if stream is closed
   @Override
   public long readBits(int count) throws BitStreamException
   {
      if (((count-1) & -64) != 0)
         throw new IllegalArgumentException("Invalid bit count: "+count+" (must be in [1..64])");

      if (count <= this.availBits)
      {
         // Enough spots available in 'current'
         this.availBits -= count;
         return (this.current >>> this.availBits) & (-1L >>> -count);
      }

      // Not enough spots available in 'current'
      count -= this.availBits;
      final long res = this.current & ((1L << this.availBits) - 1);
      this.pullCurrent();
      this.availBits -= count;
      return (res << count) | (this.current >>> this.availBits);
   }


   @Override
   public int readBits(byte[] bits, int start, int count) throws BitStreamException
   {
      if (this.isClosed() == true)
         throw new BitStreamException("Stream closed", BitStreamException.STREAM_CLOSED);

      if ((count < 0) || ((count>>3) > bits.length-start))
         throw new IllegalArgumentException("Invalid bit count: "+count+" (must be in [1.." +
           (((long)(bits.length-start))<<3) + "])");

      if (count == 0)
         return 0;

      int remaining = count;

      // Byte aligned cursor ?
      if ((this.availBits & 7) == 0)
      {
         if (this.availBits == 0)
            this.pullCurrent();

         // Empty this.current
         while ((this.availBits > 0) && (remaining >= 8))
         {
            bits[start] = (byte) this.readBits(8);
            start++;
            remaining -= 8;
         }

         // Copy internal buffer to bits array
         while ((remaining>>3) > this.maxPosition+1-this.position)
         {
            System.arraycopy(this.buffer, this.position, bits, start, this.maxPosition+1-this.position);
            start += (this.maxPosition+1-this.position);
            remaining -= ((this.maxPosition+1-this.position)<<3);
            this.readFromInputStream(this.buffer.length);
         }

         final int r = (remaining>>6) << 3;

         if (r > 0)
         {
            System.arraycopy(this.buffer, this.position, bits, start, r);
            this.position += r;
            start += r;
            remaining -= (r<<3);
         }
      }
      else
      {
         // Not byte aligned
         final int r = 64 - this.availBits;

         while (remaining >= 64)
         {
            final long v = this.current & ((1L<<this.availBits)-1);
            this.pullCurrent();
            this.availBits -= r;
            Memory.BigEndian.writeLong64(bits, start, (v<<r) | (this.current>>>this.availBits));
            start += 8;
            remaining -= 64;
         }
      }

      // Last bytes
      while (remaining >= 8)
      {
         bits[start] = (byte) this.readBits(8);
         start++;
         remaining -= 8;
      }

      if (remaining > 0)
         bits[start] = (byte) (this.readBits(remaining)<<(8-remaining));

      return count;
   }


   // Pull 64 bits of current value from buffer.
   private void pullCurrent()
   {
      if (this.position > this.maxPosition)
         this.readFromInputStream(this.buffer.length);

      if (this.position+7 > this.maxPosition)
      {
         // End of stream: overshoot max position => adjust bit index
         int shift = (this.maxPosition - this.position) << 3;
         this.availBits = shift + 8;
         long val = 0;

         while (this.position <= this.maxPosition)
         {
            val |= (((long) (this.buffer[this.position++] & 0xFF)) << shift);
            shift -= 8;
         }

         this.current = val;
      }
      else
      {
         // Regular processing, buffer length is multiple of 8
         this.current = Memory.BigEndian.readLong64(this.buffer, this.position);
         this.availBits = 64;
         this.position += 8;
      }
   }


   @Override
   public void close()
   {
      if (this.isClosed() == true)
         return;

      this.closed = true;

      // Reset fields to force a readFromInputStream() and trigger an exception
      // on readBit() or readBits()
      this.read -= this.availBits;
      this.availBits = 0;
      this.maxPosition = -1;
   }


   // Return number of bits read so far
   @Override
   public long read()
   {
      return this.read + (this.position<<3) - this.availBits;
   }


   @Override
   public boolean hasMoreToRead()
   {
      if (this.isClosed() == true)
         return false;

      if ((this.position < this.maxPosition) || (this.availBits > 0))
         return true;

      try
      {
         this.readFromInputStream(this.buffer.length);
      }
      catch (BitStreamException e)
      {
         return false;
      }

      return true;
   }


   public boolean isClosed()
   {
      return this.closed;
   }
}