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

package io.github.flanglet.kanzi.test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;
import io.github.flanglet.kanzi.BitStreamException;
import io.github.flanglet.kanzi.InputBitStream;
import io.github.flanglet.kanzi.OutputBitStream;
import io.github.flanglet.kanzi.bitstream.DebugOutputBitStream;
import io.github.flanglet.kanzi.bitstream.DefaultInputBitStream;
import io.github.flanglet.kanzi.bitstream.DefaultOutputBitStream;
import org.junit.Assert;
import org.junit.Test;


public class TestDefaultBitStream
{
   public static void main(String[] args)
   {
      testCorrectnessAligned1();
      testCorrectnessAligned2();
      testCorrectnessMisaligned1();
      testCorrectnessMisaligned2();
      testSpeed1(args); // Writes big output.bin file to local dir (or specified file name) !!!
      testSpeed2(args); // Writes big output.bin file to local dir (or specified file name) !!!
   }


   @Test
   public void testDefaultBitStream()
   {
      Assert.assertTrue(testCorrectnessAligned1());
      Assert.assertTrue(testCorrectnessAligned2());
      Assert.assertTrue(testCorrectnessMisaligned1());
      Assert.assertTrue(testCorrectnessMisaligned2());
   }


   public static boolean testCorrectnessAligned1()
   {
      // Test correctness (byte aligned)
      System.out.println("Correctness Test - write long - byte aligned");
      int[] values = new int[100];
      Random rnd = new Random();
      System.out.println("\nInitial");

      try
      {
         // Check correctness of read() and written()
         for (int t=1; t<=32; t++)
         {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4*values.length);
            OutputStream os = new BufferedOutputStream(baos);
            OutputBitStream obs = new DefaultOutputBitStream(os, 16384);
            System.out.println();
            obs.writeBits(0x0123456789ABCDEFL, t);
            System.out.println("Written (before close): " + obs.written());
            // Close first to force flush()
            obs.close();
            System.out.println("Written (after close): " + obs.written());
            byte[] output = baos.toByteArray();
            ByteArrayInputStream bais = new ByteArrayInputStream(output);
            InputStream is = new BufferedInputStream(bais);
            InputBitStream ibs = new DefaultInputBitStream(is, 16384);
            ibs.readBits(t);
            System.out.println(ibs.read()==t?"OK":"KO");
            System.out.println("Read (before close): " + ibs.read());
            ibs.close();
            System.out.println("Read (after close): " + ibs.read());
            System.out.println();
         }

         for (int test=1; test<=10; test++)
         {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4*values.length);
            OutputStream os = new BufferedOutputStream(baos);
            OutputBitStream obs = new DefaultOutputBitStream(os, 16384);
            DebugOutputBitStream dbs = new DebugOutputBitStream(obs, System.out);
            dbs.showByte(true);

            for (int i=0; i<values.length; i++)
            {
               values[i] = (test<5) ? rnd.nextInt(test*1000+100) : rnd.nextInt();
               System.out.print(values[i]+" ");

               if ((i % 20) == 19)
                  System.out.println();
            }

            System.out.println();
            System.out.println();

            for (int i=0; i<values.length; i++)
            {
               dbs.writeBits(values[i], 32);
            }

            // Close first to force flush()
            dbs.close();
            byte[] output = baos.toByteArray();
            ByteArrayInputStream bais = new ByteArrayInputStream(output);
            InputStream is = new BufferedInputStream(bais);
            InputBitStream ibs = new DefaultInputBitStream(is, 16384);
            System.out.println("Read:");
            boolean ok = true;

            for (int i=0; i<values.length; i++)
            {
               int x = (int) ibs.readBits(32);
               System.out.print(x);
               System.out.print((x == values[i]) ? " ": "* ");
               ok &= (x == values[i]);

               if ((i % 20) == 19)
                  System.out.println();
            }

            ibs.close();
            System.out.println("\n");
            System.out.println("Bits written: "+dbs.written());
            System.out.println("Bits read: "+ibs.read());
            System.out.println("\n"+((ok)?"Success":"Failure"));
            System.out.println();
            System.out.println();
         }
      }
      catch (Exception e)
      {
         e.printStackTrace();
         return false;
      }

      return true;
   }


   public static boolean testCorrectnessMisaligned1()
   {
      // Test correctness (not byte aligned)
      System.out.println("Correctness Test - write long - not byte aligned");
      int[] values = new int[100];
      Random rnd = new Random();

      try
      {
         // Check correctness of read() and written()
         for (int t=1; t<32; t++)
         {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4*values.length);
            OutputStream os = new BufferedOutputStream(baos);
            OutputBitStream obs = new DefaultOutputBitStream(os, 16384);
            System.out.println();
            obs.writeBit(1);
            obs.writeBits(0x0123456789ABCDEFL, t);
            System.out.println("Written (before close): " + obs.written());
            // Close first to force flush()
            obs.close();
            System.out.println("Written (after close): " + obs.written());
            byte[] output = baos.toByteArray();
            ByteArrayInputStream bais = new ByteArrayInputStream(output);
            InputStream is = new BufferedInputStream(bais);
            InputBitStream ibs = new DefaultInputBitStream(is, 16384);
            ibs.readBit();
            ibs.readBits(t);
            System.out.println(ibs.read()==t+1?"OK":"KO");
            System.out.println("Read (before close): " + ibs.read());
            ibs.close();
            System.out.println("Read (after close): " + ibs.read());
            System.out.println();
         }

         for (int test=1; test<=10; test++)
         {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4*values.length);
            OutputStream os = new BufferedOutputStream(baos);
            OutputBitStream obs = new DefaultOutputBitStream(os, 16384);
            DebugOutputBitStream dbs = new DebugOutputBitStream(obs, System.out);
            dbs.showByte(true);

            for (int i=0; i<values.length; i++)
            {
               values[i] = (test<5) ? rnd.nextInt(test*1000+100) : rnd.nextInt();
               final int mask = (1 << (1 + (i % 30))) - 1;
               values[i] &= mask;
               System.out.print(values[i]+" ");

               if ((i % 20) == 19)
                  System.out.println();
            }

            System.out.println();
            System.out.println();

            for (int i=0; i<values.length; i++)
            {
               dbs.writeBits(values[i], (1 + (i % 30)));
            }

            // Close first to force flush()
            dbs.close();

            if (test == 10)
            {
               try
               {
                  System.out.println();
                  System.out.println("Trying to write to closed stream");
                  dbs.writeBit(1);
               }
               catch (BitStreamException e)
               {
                  System.out.println("\nException: " + e.getMessage());
               }
            }

            byte[] output = baos.toByteArray();
            ByteArrayInputStream bais = new ByteArrayInputStream(output);
            InputStream is = new BufferedInputStream(bais);
            InputBitStream ibs = new DefaultInputBitStream(is, 16384);
            System.out.println();
            System.out.println("Read: ");
            boolean ok = true;

            for (int i=0; i<values.length; i++)
            {
               int x = (int) ibs.readBits((1 + (i % 30)));
               System.out.print(x);
               System.out.print((x == values[i]) ? " ": "* ");
               ok &= (x == values[i]);

               if ((i % 20) == 19)
                  System.out.println();
            }

            ibs.close();
            System.out.println("\n");
            System.out.println("Bits written: "+dbs.written());
            System.out.println("Bits read: "+ibs.read());
            System.out.println("\n"+((ok)?"Success":"Failure"));
            System.out.println();

            if (test == 10)
            {
               try
               {
                  System.out.println();
                  System.out.println("Trying to read from closed stream");
                  ibs.readBit();
               }
               catch (BitStreamException e)
               {
                  System.out.println("\nException: " + e.getMessage());
               }
            }
         }
      }
      catch (Exception e)
      {
         e.printStackTrace();
         return false;
      }

      return true;
   }


   public static boolean testCorrectnessAligned2()
   {
      // Test correctness (byte aligned)
      System.out.println("Correctness Test - write array - byte aligned");
      byte[] input = new byte[100];
      byte[] output = new byte[100];
      Random rnd = new Random();
      System.out.println("\nInitial");

      try
      {
         for (int test=1; test<=10; test++)
         {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4*input.length);
            OutputStream os = new BufferedOutputStream(baos);
            OutputBitStream obs = new DefaultOutputBitStream(os, 16384);
            DebugOutputBitStream dbs = new DebugOutputBitStream(obs, System.out);
            dbs.showByte(true);

            for (int i=0; i<input.length; i++)
            {
               input[i] = (test<=5) ? (byte) rnd.nextInt(test*10+100) : (byte) rnd.nextInt();
               System.out.print((input[i]&0xFF)+" ");

               if ((i % 20) == 19)
                  System.out.println();
            }

            int count = 24 + test*(20+(test&1)) + (test&3);
            System.out.println();
            System.out.println();
            dbs.writeBits(input, 0, count);
            System.out.println(dbs.written());

            // Close first to force flush()
            dbs.close();
            byte[] block = baos.toByteArray();
            ByteArrayInputStream bais = new ByteArrayInputStream(block);
            InputStream is = new BufferedInputStream(bais);
            InputBitStream ibs = new DefaultInputBitStream(is, 16384);
            System.out.println("Read:");

            int chkSize8 = (count/3) & -8;
            int r = ibs.readBits(output, 0, chkSize8); // read in 2 chunks as a test
            r += ibs.readBits(output, chkSize8/8, count-chkSize8);
            boolean ok = r == count;

            if (ok == true)
            {
               for (int i=0; i<(r>>3); i++)
               {
                  System.out.print(output[i] & 0xFF);
                  System.out.print((output[i] == input[i]) ? " ": "* ");
                  ok &= (output[i] == input[i]);

                  if ((i % 20) == 19)
                     System.out.println();
               }
            }

            ibs.close();
            System.out.println("\n");
            System.out.println("Bits written: "+dbs.written());
            System.out.println("Bits read: "+ibs.read());
            System.out.println("\n"+((ok)?"Success":"Failure"));
            System.out.println();
            System.out.println();
         }
      }
      catch (Exception e)
      {
         e.printStackTrace();
         return false;
      }

      return true;
   }


   public static boolean testCorrectnessMisaligned2()
   {
      // Test correctness (not byte aligned)
      System.out.println("Correctness Test - write array - not byte aligned");
      byte[] input = new byte[100];
      byte[] output = new byte[100];
      Random rnd = new Random();

      try
      {
         for (int test=1; test<=10; test++)
         {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4*input.length);
            OutputStream os = new BufferedOutputStream(baos);
            OutputBitStream obs = new DefaultOutputBitStream(os, 16384);
            DebugOutputBitStream dbs = new DebugOutputBitStream(obs, System.out);
            dbs.showByte(true);

            for (int i=0; i<input.length; i++)
            {
               input[i] = (test<=5) ? (byte) rnd.nextInt(test*10+100) : (byte) rnd.nextInt();
               System.out.print((input[i]&0xFF)+" ");

               if ((i % 20) == 19)
                  System.out.println();
            }

            int count = 8 + test*(20+(test&1)) + (test&3);
            System.out.println();
            System.out.println();
            dbs.writeBit(0);
            dbs.writeBits(input, 1, count);

            // Close first to force flush()
            dbs.close();

            byte[] block = baos.toByteArray();
            ByteArrayInputStream bais = new ByteArrayInputStream(block);
            InputStream is = new BufferedInputStream(bais);
            InputBitStream ibs = new DefaultInputBitStream(is, 16384);
            System.out.println();
            System.out.println("Read: ");

            ibs.readBit();
            int r = ibs.readBits(output, 1, count);
            boolean ok = r == count;

            if (ok == true)
            {
               for (int i=1; i<1+(r>>3); i++)
               {
                  System.out.print(output[i] & 0xFF);
                  System.out.print((output[i] == input[i]) ? " ": "* ");
                  ok &= (output[i] == input[i]);

                  if ((i % 20) == 19)
                     System.out.println();
               }
            }

            ibs.close();
            System.out.println("\n");
            System.out.println("Bits written: "+dbs.written());
            System.out.println("Bits read: "+ibs.read());
            System.out.println("\n"+((ok)?"Success":"Failure"));
            System.out.println();

            if (test == 10)
            {
               try
               {
                  System.out.println();
                  System.out.println("Trying to read from closed stream");
                  ibs.readBits(1);
               }
               catch (BitStreamException e)
               {
                  System.out.println("\nException: " + e.getMessage());
               }
            }
         }
      }
      catch (Exception e)
      {
         e.printStackTrace();
         return false;
      }

      return true;
   }


   public static boolean testSpeed1(String[] args)
   {
      // Test speed
      System.out.println("\n\nSpeed Test1");
      String fileName = (args.length > 0) ? args[0] : "r:\\output.bin";
      File file = new File(fileName);
      file.deleteOnExit();
      int[] values = new int[] { 3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5, 8, 9, 7, 9, 3,
                                 31, 14, 41, 15, 59, 92, 26, 65, 53, 35, 58, 89, 97, 79, 93, 32 };

      try
      {
         final int iter = 15;
         long written = 0;
         long read = 0;
         long before, after;
         long delta1 = 0, delta2 = 0;
         int nn = 1000000 * values.length;

         for (int test=0; test<iter; test++)
         {
            FileOutputStream os = new FileOutputStream(file);
            OutputBitStream obs = new DefaultOutputBitStream(os, 1024*1024);
            before = System.nanoTime();

            for (int i=0; i<nn; i++)
            {
               obs.writeBits(values[i%values.length], 1+(i&63));
            }

            // Close first to force flush()
            obs.close();
            os.close();
            after = System.nanoTime();
            delta1 += (after-before);
            written += obs.written();

            FileInputStream is = new FileInputStream(new File(fileName));
            InputBitStream ibs = new DefaultInputBitStream(is, 1024*1024);
            before = System.nanoTime();

            for (int i=0; i<nn; i++)
            {
               ibs.readBits(1+(i&63));
            }

            ibs.close();
            after = System.nanoTime();
            delta2 += (after-before);
            read += ibs.read();
         }

         System.out.println(written+ " bits written ("+(written/1024/1024/8)+" MiB)");
         System.out.println(read+ " bits read ("+(read/1024/1024/8)+" MiB)");
         System.out.println();
         System.out.println("Write [ms]        : "+(delta1/1000000L));
         System.out.println("Throughput [MiB/s] : "+((written/1024*1000/8192)/(delta1/1000000L)));
         System.out.println("Read [ms]         : "+(delta2/1000000L));
         System.out.println("Throughput [MiB/s] : "+((read/1024*1000/8192)/(delta2/1000000L)));
      }
      catch (Exception e)
      {
         e.printStackTrace();
         return false;
      }

      return true;
   }


   public static boolean testSpeed2(String[] args)
   {
      // Test speed
      System.out.println("\n\nSpeed Test2");
      String fileName = (args.length > 0) ? args[0] : "r:\\output.bin";
      File file = new File(fileName);
      file.deleteOnExit();
      byte[] values = new byte[] { 3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5, 8, 9, 7, 9, 3,
                                 31, 14, 41, 15, 59, 92, 26, 65, 53, 35, 58, 89, 97, 79, 93, 32 };

      try
      {
         final int iter = 15;
         long written = 0;
         long read = 0;
         long before, after;
         long delta1 = 0, delta2 = 0;
         int nn = 32500000 * values.length;
         byte[] input = new byte[nn];
         byte[] output = new byte[nn];

         for (int i=0; i<32500000; i++)
            System.arraycopy(values, 0, input, i*values.length, values.length);

         for (int test=0; test<iter; test++)
         {
            FileOutputStream os = new FileOutputStream(file);
            OutputBitStream obs = new DefaultOutputBitStream(os, 1024*1024);
            before = System.nanoTime();
            obs.writeBits(input, 0, input.length);

            // Close first to force flush()
            obs.close();
            os.close();
            after = System.nanoTime();
            delta1 += (after-before);
            written += obs.written();

            FileInputStream is = new FileInputStream(new File(fileName));
            InputBitStream ibs = new DefaultInputBitStream(is, 1024*1024);
            before = System.nanoTime();
            ibs.readBits(output, 0, output.length);

            ibs.close();
            after = System.nanoTime();
            delta2 += (after-before);
            read += ibs.read();
         }

         System.out.println(written+ " bits written ("+(written/1024/1024/8)+" MiB)");
         System.out.println(read+ " bits read ("+(read/1024/1024/8)+" MiB)");
         System.out.println();
         System.out.println("Write [ms]        : "+(delta1/1000000L));
         System.out.println("Throughput [MiB/s] : "+((written/1024*1000/8192)/(delta1/1000000L)));
         System.out.println("Read [ms]         : "+(delta2/1000000L));
         System.out.println("Throughput [MiB/s] : "+((read/1024*1000/8192)/(delta2/1000000L)));
      }
      catch (Exception e)
      {
         e.printStackTrace();
         return false;
      }

      return true;
   }
}
