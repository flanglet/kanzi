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

import java.util.Random;
import io.github.flanglet.kanzi.ByteTransform;
import io.github.flanglet.kanzi.SliceByteArray;
import io.github.flanglet.kanzi.transform.BWT;
import io.github.flanglet.kanzi.transform.BWTS;
import org.junit.Assert;
import org.junit.Test;


public class TestBWT
{
   @Test
   public void testBWT()
   {
      Assert.assertTrue(testCorrectness(true, 200));
      Assert.assertTrue(testCorrectness(false, 200));
   }


   public static void printHexa(String s)
   {
       final char[] CHARS = new char[] { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };

       for (int i=0; i<s.length(); i++)
       {
          int c = s.charAt(i) & 0xFF;
          char c1 = CHARS[c >> 4];
          char c2 = CHARS[c & 0x0F];
          System.out.print("0x" + c1 + c2 + " ");
       }
   }


   public static void main(String[] args)
   {
      if (args.length > 0)
      {
         byte[] buf1 = args[0].getBytes();
         byte[] buf2 = new byte[buf1.length];
         SliceByteArray sa1 = new SliceByteArray(buf1, 0);
         SliceByteArray sa2 = new SliceByteArray(buf2, 0);
         BWT bwt = new BWT();
         bwt.forward(sa1, sa2);
         System.out.print("BWT:  " + new String(buf2));
         System.out.println(" (" + bwt.getPrimaryIndex(0) + ")");
         sa1.index = 0;
         sa2.index = 0;
         BWTS bwts = new BWTS();
         bwts.forward(sa1, sa2);
         System.out.println("BWTS: " + new String(buf2));
         System.exit(0);
      }

      System.out.println("TestBWT and TestBWTS");

      if (testCorrectness(true, 20) == false)
         System.exit(1);

      if (testCorrectness(false, 20) == false)
         System.exit(1);

      testSpeed(true, 200, 256*1024); // test MergeTPSI inverse
      testSpeed(true, 5, 10*1024*1024); // test BiPSIv2 inverse
      testSpeed(false, 200, 256*1024);
   }


    public static boolean testCorrectness(boolean isBWT, int iters)
    {
      System.out.println("\nBWT"+(!isBWT?"S":"")+" Correctness test");

      // Test behavior
      for (int ii=1; ii<=iters; ii++)
      {
         System.out.println("\nTest "+ii);
         int start = 0;
         byte[] buf1;
         Random rnd = new Random();

         if (ii == 1)
         {
            buf1 = "mississippi".getBytes();
         }
         else if (ii == 2)
         {
            buf1 = "3.14159265358979323846264338327950288419716939937510".getBytes();
         }
         else if (ii == 3)
         {
            buf1 = "SIX.MIXED.PIXIES.SIFT.SIXTY.PIXIE.DUST.BOXES".getBytes();
         }
         else if (ii < iters)
         {
            buf1 = new byte[128];

            for (int i=0; i<buf1.length; i++)
            {
               buf1[i] = (byte) (65 + rnd.nextInt(4*ii));
            }
         }
         else
         {
            buf1 = new byte[8*1024*1024];

            for (int i=0; i<buf1.length; i++)
               buf1[i] = (byte) i;
         }

         byte[] buf2 = new byte[buf1.length];
         byte[] buf3 = new byte[buf1.length];
         SliceByteArray sa1 = new SliceByteArray(buf1, 0);
         SliceByteArray sa2 = new SliceByteArray(buf2, 0);
         SliceByteArray sa3 = new SliceByteArray(buf3, 0);
         ByteTransform transform = (isBWT) ? new BWT() : new BWTS();
         String str1 = new String(buf1, start, buf1.length-start);

         if (str1.length() < 512)
         {
            System.out.print("Input:   ");
            printHexa(str1);
            System.out.println();
         }

         System.out.println();
         sa1.index = start;
         sa2.index = 0;
         transform.forward(sa1, sa2);
         String str2 = new String(buf2);

         if (str2.length() < 512)
         {
            System.out.print("Encoded:   ");
            printHexa(str2);
            System.out.println();
         }

         if (isBWT)
         {
            BWT bwt = (BWT) transform;
            int chunks = BWT.getBWTChunks(buf1.length);
            int[] pi = new int[chunks];

            for (int i=0; i<chunks; i++)
            {
               pi[i] = bwt.getPrimaryIndex(i);
               System.out.println("(Primary index="+pi[i]+")");
            }

            transform = new BWT();
            bwt = (BWT) transform;

            for (int i=0; i<chunks; i++)
               bwt.setPrimaryIndex(i, pi[i]);
         }
         else
         {
            transform = new BWTS();
         }

         System.out.println();
         sa2.index = 0;
         sa3.index = start;

         transform.inverse(sa2, sa3);
         String str3 = new String(buf3, start, buf3.length-start);

         if (str3.length() < 512)
         {
            System.out.print("Output:   ");
            printHexa(str3);
            System.out.println();
         }

         if (str1.equals(str3) == true)
         {
            System.out.println("Identical");
         }
         else
         {
            int idx = -1;

            for (int i=0; i<buf1.length; i++)
            {
               if (buf1[i] != buf3[i])
               {
                  idx = i;
                  break;
               }
            }

            System.out.println("Different at index "+idx+" "+buf1[idx]+" <-> "+buf3[idx]);
            return false;
         }
      }

      return true;
   }


   public static void testSpeed(boolean isBWT, int iter, int size)
   {
      System.out.println("\nBWT"+(!isBWT?"S":"")+" Speed test");
      byte[] buf1 = new byte[size];
      byte[] buf2 = new byte[size];
      byte[] buf3 = new byte[size];
      SliceByteArray sa1 = new SliceByteArray(buf1, 0);
      SliceByteArray sa2 = new SliceByteArray(buf2, 0);
      SliceByteArray sa3 = new SliceByteArray(buf3, 0);
      System.out.println("Iterations: "+iter);
      System.out.println("Transform size: "+size);

      for (int jj = 0; jj < 3; jj++)
      {
         long delta1 = 0;
         long delta2 = 0;
         java.util.Random random = new java.util.Random();
         long before, after;

         for (int ii = 0; ii < iter; ii++)
         {
            ByteTransform bwt = (isBWT) ? new BWT() : new BWTS();

            for (int i = 0; i < size; i++)
               buf1[i] = (byte) (random.nextInt(255) + 1);

            before = System.nanoTime();
            sa1.index = 0;
            sa2.index = 0;
            bwt.forward(sa1, sa2);
            after = System.nanoTime();
            delta1 += (after - before);
            before = System.nanoTime();
            sa2.index = 0;
            sa3.index = 0;
            bwt.inverse(sa2, sa3);
            after = System.nanoTime();
            delta2 += (after - before);

            int idx = -1;

            // Sanity check
            for (int i=0; i<size; i++)
            {
               if (buf1[i] != buf3[i])
               {
                  idx = i;
                  break;
               }
            }

            if (idx >= 0)
               System.out.println("Failure at index "+idx+" ("+buf1[idx]+"<->"+buf3[idx]+")");
         }

         final long prod = (long) iter * (long) size;
         System.out.println("Forward transform [ms] : " + delta1 / 1000000);
         System.out.println("Throughput [KiB/s]      : " + prod * 1000000L / delta1 * 1000L / 1024);
         System.out.println("Inverse transform [ms] : " + delta2 / 1000000);
         System.out.println("Throughput [KiB/s]      : " + prod * 1000000L / delta2 * 1000L / 1024);
      }
   }
}
