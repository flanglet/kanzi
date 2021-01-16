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

package kanzi.test;

import java.util.Arrays;
import java.util.Random;
import kanzi.ByteTransform;
import kanzi.SliceByteArray;
import kanzi.transform.BWTS;
import kanzi.transform.SBRT;
import org.junit.Assert;
import org.junit.Test;


public class TestTransforms
{
   public static void main(String[] args)
   {
      if (args.length == 0)
      {
         args = new String[] { "-TYPE=ALL" };
      }

      String type = args[0].toUpperCase();

      if (type.startsWith("-TYPE="))
      {
         type = type.substring(6);
         System.out.println("Transform: " + type);

         if (type.equals("ALL"))
         {
            System.out.println("\n\nTestRANK");

            if (testCorrectness("RANK") == false)
               System.exit(1);

            testSpeed("RANK");
            System.out.println("\n\nTestMTFT");

            if (testCorrectness("MTFT") == false)
               System.exit(1);

            testSpeed("MTFT");
            System.out.println("\n\nTestBWTS");

            if (testCorrectness("BWTS") == false)
               System.exit(1);

            testSpeed("BWTS");
         }
         else
         {
            System.out.println("Test" + type);

            if (testCorrectness(type) == false)
               System.exit(1);

            testSpeed(type);
         }
      }
   }


   @Test
   public void testFunctions()
   {
      System.out.println("\n\nTestRANK");
      Assert.assertTrue(testCorrectness("RANK"));
      //testSpeed("RANK");
      System.out.println("\n\nTestMTFT");
      Assert.assertTrue(testCorrectness("MTFT"));
      //testSpeed("MTFT");
      System.out.println("\n\nTestBWTS");
      Assert.assertTrue(testCorrectness("BWTS"));
      //testSpeed("BWTS");
   }


   private static ByteTransform getByteTransform(String name)
   {
      switch(name)
      {
         case "RANK":
            return new SBRT(SBRT.MODE_RANK);

         case "MTFT":
            return new SBRT(SBRT.MODE_MTF);

         case "BWTS":
            return new BWTS();

         default:
            System.out.println("No such byte transform: "+name);
            return null;
      }
   }


   private static boolean testCorrectness(String name)
   {
      byte[] input;
      byte[] output;
      byte[] reverse;
      Random rnd = new Random();

      // Test behavior
      System.out.println("Correctness test for " + name);
      int range = 256;

      for (int ii=0; ii<20; ii++)
      {
         System.out.println("\nTest "+ii);
         int[] arr = new int[0];

         if (ii == 0)
         {
            arr = new int[] {
               0, 1, 2, 2, 2, 2, 7, 9,  9, 16, 16, 16, 1, 3,
              3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3
            };
         }
         else if (ii == 1)
         {
            arr = new int[800];
            arr[0] = 1;

            for (int i=1; i<arr.length; i++)
               arr[i] = 8;
         }
         else if (ii == 2)
         {
            arr = new int[] { 0, 0, 1, 1, 2, 2, 3, 3 };
         }
         else if (ii == 3)
         {
            arr = new int[512];

            for (int i=0; i<256; i++)
            {
               arr[2*i] = i;
               arr[2*i+1] = i;
            }

            arr[1] = 255;
         }
         else if (ii < 6)
         {
            // Lots of zeros
            arr = new int[1<<(ii+6)];

            for (int i=0; i<arr.length; i++)
            {
               int val = rnd.nextInt(100);

               if (val >= 33)
                  val = 0;

               arr[i] = val;
            }
         }
         else if (ii == 6)
         {
            // Totally random
            arr = new int[512];

            for (int j=20; j<arr.length; j++)
               arr[j] = rnd.nextInt(range);
         }
         else
         {
            arr = new int[1024];
            int idx = 20;

            while (idx < arr.length)
            {
               int len = rnd.nextInt(40);

               if (len % 3 == 0)
                 len = 1;

               int val = rnd.nextInt(range);
               int end = (idx+len) < arr.length ? idx+len : arr.length;

               for (int j=idx; j<end; j++)
                  arr[j] = val;

               idx += len;
            }
         }

         int size = arr.length;
         ByteTransform f = getByteTransform(name);
         input = new byte[size];
         output = new byte[size];
         reverse = new byte[size];
         SliceByteArray sa1 = new SliceByteArray(input, 0);
         SliceByteArray sa2 = new SliceByteArray(output, 0);
         SliceByteArray sa3 = new SliceByteArray(reverse, 0);
         Arrays.fill(output, (byte) 0xAA);

         for (int i=0; i<arr.length; i++)
         {
            input[i] = (byte) (arr[i] & 255);
         }

         System.out.println("\nOriginal: ");

         if (ii == 1)
         {
            System.out.print("1 8 ("+(arr.length-2)+" times)");
         }
         else
         {
            for (int i=0; i<input.length; i++)
            {
               System.out.print((input[i] & 255) + " ");
            }
         }

         if (f.forward(sa1, sa2) == false)
         {
            if (sa1.index != input.length)
            {
               System.out.println("\nNo compression (ratio > 1.0), skip reverse");
               continue;
            }

            System.out.println("\nEncoding error");
            return false;
         }

         if (sa1.index != input.length)
         {
            System.out.println("\nNo compression (ratio > 1.0), skip reverse");
            continue;
         }

         System.out.println("\nCoded: ");
         //java.util.Arrays.fill(input, (byte) 0);

         for (int i=0; i<sa2.index; i++)
         {
            System.out.print((output[i] & 255) + " "); //+"("+Integer.toBinaryString(output[i] & 255)+") ");
         }

         f = getByteTransform(name);
         sa2.length = sa2.index;
         sa1.index = 0;
         sa2.index = 0;
         sa3.index = 0;

         if (f.inverse(sa2, sa3) == false)
         {
            System.out.println("Decoding error");
            return false;
         }

         System.out.println();
         System.out.println("Decoded: ");
         int idx = -1;

         for (int i=0; i<input.length; i++)
         {
            if (input[i] != reverse[i])
            {
               idx = i;
               break;
            }
         }

         if (idx == -1)
         {
            if (ii == 1)
            {
               System.out.println("1 8 ("+(arr.length-2)+" times)");
            }
            else
            {
               for (int i=0; i<reverse.length; i++)
                  System.out.print((reverse[i] & 255) + " ");

               System.out.println();
            }

            System.out.println("Identical");
         }
         else
         {
            System.out.println("Different (index "+idx+": "+input[idx]+" - "+reverse[idx]+")");
            System.out.println("");

            for (int i=0; i<idx; i++)
               System.out.println(i+": "+sa1.array[i]+" "+sa3.array[i]);

            System.out.println(idx+": "+sa1.array[idx]+"* "+sa3.array[idx]+"*");
            return false;
         }

         System.out.println();
      }

      return true;
   }


   public static void testSpeed(String name)
   {
      // Test speed
      byte[] input;
      byte[] output;
      byte[] reverse;
      Random rnd = new Random();
      final int iter = 2000;
      final int size = 50000;
      System.out.println("\n\nSpeed test for " + name);
      System.out.println("Iterations: " + iter);
      System.out.println();
      int range = name.equals("ZRLT") ? 5 : 256;

      for (int jj=0; jj<3; jj++)
      {
         ByteTransform f = getByteTransform(name);
         input = new byte[size];
         output = new byte[size];
         reverse = new byte[size];
         SliceByteArray sa1 = new SliceByteArray(input, 0);
         SliceByteArray sa2 = new SliceByteArray(output, 0);
         SliceByteArray sa3 = new SliceByteArray(reverse, 0);

         // Generate random data with runs
         // Leave zeros at the beginning for ZRLT to succeed
         int n = iter/20;

         while (n < input.length)
         {
            byte val = (byte) rnd.nextInt(range);
            input[n++] = val;
            int run = rnd.nextInt(256);
            run -= 220;

            while ((--run > 0) && (n < input.length))
               input[n++] = val;
         }

         long before, after;
         long delta1 = 0;
         long delta2 = 0;

         for (int ii = 0; ii < iter; ii++)
         {
            f = getByteTransform(name);
            sa1.index = 0;
            sa2.index = 0;
            before = System.nanoTime();

            if (f.forward(sa1, sa2) == false)
            {
               System.out.println("Encoding error");
               continue;
            }

            after = System.nanoTime();
            delta1 += (after - before);
         }

         for (int ii = 0; ii < iter; ii++)
         {
            f = getByteTransform(name);
            sa2.length = sa2.index;
            sa3.index = 0;
            sa2.index = 0;
            before = System.nanoTime();

            if (f.inverse(sa2, sa3) == false)
            {
               System.out.println("Decoding error");
               System.exit(1);
            }

            after = System.nanoTime();
            delta2 += (after - before);
         }

         int idx = -1;

         // Sanity check
         for (int i=0; i<sa1.index; i++)
         {
            if (sa1.array[i] != sa3.array[i])
            {
               idx = i;
               break;
            }
         }

         if (idx >= 0) {
            System.out.println("Failure at index "+idx+" ("+sa1.array[idx]+"<->"+sa3.array[idx]+")");
            for (int i=0; i<idx; i++)
               System.out.println(i+" "+sa1.array[i]+" "+sa3.array[i]);

            System.out.println(idx+" "+sa1.array[idx]+"* "+sa3.array[idx]+"*");
         }

         final long prod = (long) iter * (long) size;
         System.out.println(name + " encoding [ms]: " + delta1 / 1000000);
         System.out.println("Throughput [MB/s]: " + prod * 1000000L / delta1 * 1000L / (1024*1024));
         System.out.println(name + " decoding [ms]: " + delta2 / 1000000);
         System.out.println("Throughput [MB/s]: " + prod * 1000000L / delta2 * 1000L / (1024*1024));
      }
   }
}
