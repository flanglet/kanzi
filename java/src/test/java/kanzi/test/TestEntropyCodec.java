/*
Copyright 2011-2022 Frederic Langlet
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

import kanzi.BitStreamException;
import kanzi.entropy.BinaryEntropyDecoder;
import kanzi.entropy.BinaryEntropyEncoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import kanzi.EntropyDecoder;
import kanzi.EntropyEncoder;
import kanzi.InputBitStream;
import kanzi.OutputBitStream;
import kanzi.bitstream.DebugOutputBitStream;
import kanzi.bitstream.DefaultInputBitStream;
import kanzi.bitstream.DefaultOutputBitStream;
import kanzi.entropy.ANSRangeDecoder;
import kanzi.entropy.ANSRangeEncoder;
import kanzi.entropy.CMPredictor;
import kanzi.entropy.ExpGolombDecoder;
import kanzi.entropy.ExpGolombEncoder;
import kanzi.entropy.HuffmanDecoder;
import kanzi.entropy.HuffmanEncoder;
import kanzi.Predictor;
import kanzi.entropy.FPAQDecoder;
import kanzi.entropy.FPAQEncoder;
import kanzi.entropy.RangeDecoder;
import kanzi.entropy.RangeEncoder;
import kanzi.entropy.RiceGolombDecoder;
import kanzi.entropy.RiceGolombEncoder;
import kanzi.entropy.TPAQPredictor;
import org.junit.Assert;
import org.junit.Test;


public class TestEntropyCodec
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
           System.out.println("Codec: " + type);

           if (type.equals("ALL"))
           {
              System.out.println("\n\nTest Huffman Codec");

              if (testCorrectness("HUFFMAN") == false)
                System.exit(1);

              testSpeed("HUFFMAN", 200);
              System.out.println("\n\nTest ANS0 Codec");

              if (testCorrectness("ANS0") == false)
                System.exit(1);

              testSpeed("ANS0", 200);
              System.out.println("\n\nTest ANS1 Codec");

              if (testCorrectness("ANS1") == false)
                System.exit(1);

              testSpeed("ANS1", 150);
              System.out.println("\n\nTest Range Codec");

              if (testCorrectness("RANGE")== false)
                System.exit(1);

              testSpeed("RANGE", 150);
              System.out.println("\n\nTest FPAQ Codec");

              if (testCorrectness("FPAQ") == false)
                System.exit(1);

              testSpeed("FPAQ", 120);
              System.out.println("\n\nTest CM Codec");

              if (testCorrectness("CM") == false)
                System.exit(1);

              testSpeed("CM", 100);
              System.out.println("\n\nTestTPAQCodec");

              if (testCorrectness("TPAQ") == false)
                System.exit(1);

              testSpeed("TPAQ", 75);
              System.out.println("\n\nTestExpGolombCodec");

              if (testCorrectness("EXPGOLOMB") == false)
                System.exit(1);

              testSpeed("EXPGOLOMB", 150);
              System.out.println("\n\nTestRiceGolombCodec");

              if (testCorrectness("RICEGOLOMB") == false)
                System.exit(1);

              testSpeed("RICEGOLOMB", 150);
           }
           else
           {
              System.out.println("Test " + type + " Codec");
              testCorrectness(type);
              testSpeed(type, 100);
           }
       }
    }

   @Test
   public void testEntropy()
   {
      System.out.println("\n\nTest Huffman Codec");
      Assert.assertTrue(testCorrectness("HUFFMAN"));
      //testSpeed("HUFFMAN");
      System.out.println("\n\nTest ANS0 Codec");
      Assert.assertTrue(testCorrectness("ANS0"));
      //testSpeed("ANS0");
      System.out.println("\n\nTest ANS1 Codec");
      Assert.assertTrue(testCorrectness("ANS1"));
      //testSpeed("ANS1");
      System.out.println("\n\nTest Range Codec");
      Assert.assertTrue(testCorrectness("RANGE"));
      //testSpeed("RANGE");
      System.out.println("\n\nTest FPAQ Codec");
      Assert.assertTrue(testCorrectness("FPAQ"));
      //testSpeed("FPAQ");
      System.out.println("\n\nTest CM Codec");
      Assert.assertTrue(testCorrectness("CM"));
      //testSpeed("CM");
      System.out.println("\n\nTest TPAQ Codec");
      Assert.assertTrue(testCorrectness("TPAQ"));
      //testSpeed("TPAQ");
      System.out.println("\n\nTest ExpGolomb Codec");
      Assert.assertTrue(testCorrectness("EXPGOLOMB"));
      //testSpeed("EXPGOLOMB");
      System.out.println("\n\nTest RiceGolomb Codec");
      Assert.assertTrue(testCorrectness("RICEGOLOMB"));
      //testSpeed("RICEGOLOMB");
   }


   private static Predictor getPredictor(String type)
   {
      if (type.equals("TPAQ"))
         return new TPAQPredictor(null);

      if (type.equals("CM"))
         return new CMPredictor(null);

      return null;
   }


   private static EntropyEncoder getEncoder(String name, OutputBitStream obs)
   {
      switch(name)
      {
         case "CM":
         case "TPAQ":
            return new BinaryEntropyEncoder(obs, getPredictor(name));

         case "FPAQ":
            return new FPAQEncoder(obs);

         case "HUFFMAN":
            return new HuffmanEncoder(obs);

         case "ANS0":
            return new ANSRangeEncoder(obs, 0);

         case "ANS1":
            return new ANSRangeEncoder(obs, 1);

         case "RANGE":
            return new RangeEncoder(obs);

         case "EXPGOLOMB":
            return new ExpGolombEncoder(obs, true);

         case "RICEGOLOMB":
            return new RiceGolombEncoder(obs, true, 4);

         default:
            System.out.println("No such entropy encoder: "+name);
            return null;
      }
   }


   private static EntropyDecoder getDecoder(String name, InputBitStream ibs)
   {
      Map<String, Object> ctx = new HashMap<>();
      ctx.put("bsVersion", 4);
      
      switch(name)
      {
         case "CM":
         case "TPAQ":
            Predictor pred = getPredictor(name);

            if (pred == null)
            {
               System.out.println("No such entropy decoder: "+name);
               return null;
            }

            return new BinaryEntropyDecoder(ibs, pred);

         case "FPAQ":
            return new FPAQDecoder(ibs, ctx);

         case "HUFFMAN":
            return new HuffmanDecoder(ibs, ctx);

         case "ANS0":
            return new ANSRangeDecoder(ibs, 0, ctx);

         case "ANS1":
            return new ANSRangeDecoder(ibs, 1, ctx);

         case "RANGE":
            return new RangeDecoder(ibs);

         case "EXPGOLOMB":
            return new ExpGolombDecoder(ibs, true);

         case "RICEGOLOMB":
            return new RiceGolombDecoder(ibs, true, 4);

         default:
            System.out.println("No such entropy decoder: "+name);
            return null;
      }
   }


   public static boolean testCorrectness(String name)
   {
      // Test behavior
      System.out.println("Correctness test for " + name);

      for (int ii=1; ii<20; ii++)
      {
          System.out.println("\n\nTest "+ii);

          try
          {
            byte[] values;
            Random random = new Random();

            if (ii == 3)
                 values = new byte[] { 0, 0, 32, 15, -4, 16, 0, 16, 0, 7, -1, -4, -32, 0, 31, -1 };
            else if (ii == 2)
                 values = new byte[] { 61, 77, 84, 71, 90, 54, 57, 38, 114, 111, 108, 101, 61, 112, 114, 101 };
            else if (ii == 1)
            {
               values = new byte[32];

               for (int i=0; i<values.length; i++)
                    values[i] = (byte) 2; // all identical
            }
            else if (ii == 5)
            {
               values = new byte[32];

               for (int i=0; i<values.length; i++)
                    values[i] = (byte) (2 + (i & 1)); // 2 symbols
            }
            else
            {
               values = new byte[256];

               for (int i=0; i<values.length; i++)
                    values[i] = (byte) (64 + 4*ii + random.nextInt(8*ii+1));
            }

            System.out.println("Original:");

            for (int i=0; i<values.length; i++)
               System.out.print((values[i]&0xFF)+" ");

            System.out.println();
            System.out.println("\nEncoded:");
            ByteArrayOutputStream os = new ByteArrayOutputStream(16384);
            OutputBitStream obs = new DefaultOutputBitStream(os, 16384);
            DebugOutputBitStream dbgbs = new DebugOutputBitStream(obs, System.out);
            dbgbs.showByte(true);
            EntropyEncoder ec = getEncoder(name, dbgbs);

            if (ec == null)
               return false;

            ec.encode(values, 0, values.length);
            ec.dispose();
            dbgbs.close();
            byte[] buf = os.toByteArray();
            InputBitStream ibs = new DefaultInputBitStream(new ByteArrayInputStream(buf), 1024);
            EntropyDecoder ed = getDecoder(name, ibs);

            if (ed == null)
               return false;

            System.out.println();
            System.out.println("\nDecoded:");
            boolean ok = true;
            byte[] values2 = new byte[values.length];
            ed.decode(values2, 0, values2.length);
            ed.dispose();
            ibs.close();

            try
            {
               for (int j=0; j<values2.length; j++)
               {
                  if (values[j] != values2[j])
                     ok = false;

                  System.out.print((values2[j]&0xFF)+" ");
               }
            }
            catch (BitStreamException e)
            {
               e.printStackTrace();
               return false;
            }

            System.out.println("\n"+((ok == true) ? "Identical" : "Different"));
         }
         catch (Exception e)
         {
            e.printStackTrace();
            return false;
         }
      }

      return true;
   }


   public static void testSpeed(String name, int iter)
   {
      // Test speed
      System.out.println("\n\nSpeed test for " + name);
      int[] repeats = { 3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5, 8, 9, 7, 9, 3 };
      final int size = 500000;
      Random random = new Random();

      for (int jj=0; jj<3; jj++)
      {
          System.out.println("\nTest "+(jj+1));
          byte[] values1 = new byte[size];
          byte[] values2 = new byte[size];
          long delta1 = 0, delta2 = 0;

          for (int ii=0; ii<iter; ii++)
          {
              int idx = 0;

              for (int i=0; i<size; i++)
              {
                  int i0 = i;
                  int len = repeats[idx];
                  idx = (idx + 1) & 0x0F;
                  byte b = (byte) random.nextInt(256);

                  if (i0+len >= size)
                      len = size-i0-1;

                  for (int j=i0; j<i0+len; j++)
                  {
                     values1[j] = b;
                     i++;
                  }
              }

              // Encode
              ByteArrayOutputStream os = new ByteArrayOutputStream(size*2);
              OutputBitStream obs = new DefaultOutputBitStream(os, size);
              EntropyEncoder ec = getEncoder(name, obs);

              if (ec == null)
                 System.exit(1);

              long before1 = System.nanoTime();

              if (ec.encode(values1, 0, values1.length) < 0)
              {
                 System.out.println("Encoding error");
                 System.exit(1);
              }

              ec.dispose();
              long after1 = System.nanoTime();
              delta1 += (after1 - before1);
              obs.close();

              // Decode
              byte[] buf = os.toByteArray();
              InputBitStream ibs = new DefaultInputBitStream(new ByteArrayInputStream(buf), size);
              EntropyDecoder ed = getDecoder(name, ibs);

              if (ed == null)
                 System.exit(1);

              long before2 = System.nanoTime();

              if (ed.decode(values2, 0, size) < 0)
              {
                 System.out.println("Decoding error");
                 System.exit(1);
              }

              ed.dispose();
              long after2 = System.nanoTime();
              delta2 += (after2 - before2);
              ibs.close();

              // Sanity check
              for (int i=0; i<size; i++)
              {
                 if (values1[i] != values2[i])
                 {
                    System.out.println("Error at index "+i+" ("+values1[i]
                            +"<->"+values2[i]+")");
                    break;
                  }
              }
          }

          final long prod = (long) iter * (long) size;
          System.out.println("Encode [ms]       : " + delta1/1000000L);
          System.out.println("Throughput [KB/s] : " + prod * 1000000L / delta1 * 1000L / 1024L);
          System.out.println("Decode [ms]       : " + delta2/1000000L);
          System.out.println("Throughput [KB/s] : " + prod * 1000000L / delta2 * 1000L / 1024L);
      }
  }
}
