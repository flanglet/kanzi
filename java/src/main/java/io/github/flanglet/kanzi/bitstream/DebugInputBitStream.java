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

package io.github.flanglet.kanzi.bitstream;

import io.github.flanglet.kanzi.BitStreamException;
import io.github.flanglet.kanzi.InputBitStream;
import java.io.PrintStream;


// Very util little wrapper used to print the bits written to the delegate
// bitstream (uses the decorator design pattern)
public final class DebugInputBitStream implements InputBitStream
{
   private final InputBitStream delegate;
   private final PrintStream out;
   private final int width;
   private int lineIndex;
   private boolean mark;
   private boolean hexa;
   private byte current;


   public DebugInputBitStream(InputBitStream bitstream)
   {
      this(bitstream, System.out, 80);
   }


   public DebugInputBitStream(InputBitStream bitstream, PrintStream out)
   {
      this(bitstream, out, 80);
   }


   public DebugInputBitStream(InputBitStream bitstream, PrintStream out, int width)
   {
      if (bitstream == null)
         throw new NullPointerException("Invalid null bitstream parameter");

      if (out == null)
         throw new NullPointerException("Invalid null print stream parameter");

      if ((width != -1) && (width < 8))
         width = 8;

      if (width != -1)
         width &= 0xFFFFFFF8;

      this.width = width;
      this.delegate = bitstream;
      this.out = out;
   }


   public synchronized void setMark(boolean mark)
   {
      this.mark = mark;
   }


   public synchronized boolean mark()
   {
      return this.mark;
   }


   public synchronized void showByte(boolean hex)
   {
      this.hexa = hex;
   }


   public synchronized boolean showByte()
   {
      return this.hexa;
   }


   protected synchronized void printByte(byte val)
   {
       String s;

       if (this.hexa == true)
       {
           s = "0x" + Integer.toHexString(val&0xFF);
       }
       else
       {
           if ((val >= 0) && (val < 10))
               s = "00" + Integer.toString(val&0xFF);
           else if ((val >= 0) && (val < 100))
                s = "0" + Integer.toString(val&0xFF);
           else
                s = Integer.toString(val&0xFF);
       }

       this.out.print(" [" + s + "] ");
   }


   // Returns 1 or 0
   @Override
   public synchronized int readBit() throws BitStreamException
   {
      int res = this.delegate.readBit();
      this.current <<= 1;
      this.current |= res;
      this.out.print((res & 1) == 1 ? "1" : "0");
      this.lineIndex++;

      if (this.mark == true)
         this.out.print("r");

      if (this.width != -1)
      {
         if ((this.lineIndex-1) % this.width == this.width-1)
         {
            if (this.showByte())
               this.printByte(this.current);

            this.out.println();
            this.lineIndex = 0;
         }
         else if ((this.lineIndex & 7) == 0)
         {
            if (this.showByte())
               this.printByte(this.current);
            else
               this.out.print(" ");
         }
      }
      else if ((this.lineIndex & 7) == 0)
      {
         if (this.showByte())
            this.printByte(this.current);
         else
            this.out.print(" ");
      }

      return res;
   }


   @Override
   public synchronized long readBits(int length) throws BitStreamException
   {
      long res = this.delegate.readBits(length);

      for (int i=1; i<=length; i++)
      {
         long bit = (res >> (length-i)) & 1;
         this.lineIndex++;
         this.current <<= 1;
         this.current |= bit;
         this.out.print((bit == 1) ? "1" : "0");

         if ((this.mark == true) && (i == length))
            this.out.print("r");

         if (this.width != -1)
         {
            if (this.lineIndex % this.width == 0)
            {
               if (this.showByte())
                  this.printByte(this.current);

               this.out.println();
               this.lineIndex = 0;
            }
            else if ((this.lineIndex & 7) == 0)
            {
               if (this.showByte())
                  this.printByte(this.current);
               else
                  this.out.print(" ");
            }
         }
         else if ((this.lineIndex & 7) == 0)
         {
            if (this.showByte())
               this.printByte(this.current);
            else
               this.out.print(" ");
         }
      }

      return res;
   }


   @Override
   public synchronized int readBits(byte[] bits, int start, int count) throws BitStreamException
   {
      count = this.delegate.readBits(bits, start, count);

      for (int i=start; i<start+(count>>3); i++)
      {
         for (int j=7; j>=0; j--)
         {
            byte bit = (byte) ((bits[i] >>> j) & 1);
            this.lineIndex++;
            this.current <<= 1;
            this.current |= bit;
            this.out.print((bit == 1) ? "1" : "0");

            if ((this.mark == true) && (j == count))
               this.out.print("r");

            if (this.width != -1)
            {
               if (this.lineIndex % this.width == 0)
               {
                  if (this.showByte())
                     this.printByte(this.current);

                  this.out.println();
                  this.lineIndex = 0;
               }
               else if ((this.lineIndex & 7) == 0)
               {
                  if (this.showByte())
                     this.printByte(this.current);
                  else
                     this.out.print(" ");
               }
            }
            else if ((this.lineIndex & 7) == 0)
            {
               if (this.showByte())
                  this.printByte(this.current);
               else
                  this.out.print(" ");
            }
         }
      }

      return count;
   }


   @Override
   public void close() throws BitStreamException
   {
      this.delegate.close();
   }


   @Override
   public long read()
   {
      return this.delegate.read();
   }


   @Override
   public boolean hasMoreToRead()
   {
      return this.delegate.hasMoreToRead();
   }
}
