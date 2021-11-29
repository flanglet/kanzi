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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kanzi.io.CompressedInputStream;
import kanzi.io.CompressedOutputStream;
import org.junit.Assert;
import org.junit.Test;


public class TestCompressedtStream
{
   private static final ExecutorService pool = Executors.newFixedThreadPool(4);

   
   public static void main(String[] args)
   {
      testCorrectness();
      pool.shutdown();
   }


   @Test
   public void testCompressedtStream()
   {
      Assert.assertTrue(testCorrectness());
   }


   public static boolean testCorrectness()
   {
      // Test correctness (byte aligned)
      System.out.println("Correctness Test");
      byte[] values = new byte[65536 << 6];
      byte[] incompressible = new byte[65536 << 6];
      Random rnd = new Random();
      long sum = 0;

      try
      {
         for (int test=1; test<=40; test++)
         {
            final int length = 65536 << (test % 7);
            
            for (int i=0; i<length; i++)
            {
               values[i] = (byte) rnd.nextInt(4*test+1);
               incompressible[i] = (byte) (rnd.nextInt());
            }

            System.out.println("\nIteration " + test + " (size " + length + ")");
            long res;
            res = compress1(values, length);
            System.out.println((res == 0) ? "Success" : "Failure");
            sum += res;
            res = compress2(values, length);
            System.out.println((res == 0) ? "Success" : "Failure");
            sum += res;
            res = compress3(incompressible, length);
            System.out.println((res == 0) ? "Success" : "Failure");
            sum += res;
            
            if (test == 1)
            {
               res = compress4(values, length);
               System.out.println((res == 0) ? "Success" : "Failure");
               sum += res;
               res = compress5(values, length);
               System.out.println((res == 0) ? "Success" : "Failure");
               sum += res;
            }
         }
      }
      catch (Exception e)
      {
         e.printStackTrace();
         return false;
      }

      return sum == 0;
   }
   
   public static long compress1(byte[] block, int length)
   {
      try
      {
         System.out.println("Test - regular");
         final int blockSize = (length / (1 + new Random().nextInt(3))) & -16;
         byte[] buf = new byte[length];
         System.arraycopy(block, 0, buf, 0, length);
         ByteArrayOutputStream baos = new ByteArrayOutputStream(2*block.length);
         OutputStream os = new BufferedOutputStream(baos);
         HashMap<String, Object> ctx1 = new HashMap<>();
         ctx1.put("transform", "NONE"); 
         ctx1.put("blockSize", blockSize);
         ctx1.put("checksum", false);
         CompressedOutputStream cos = new CompressedOutputStream(os, ctx1);
         cos.write(block, 0, length);
         cos.close();
         os.close();
         long written = cos.getWritten();

         byte[] output = baos.toByteArray();
         ByteArrayInputStream bais = new ByteArrayInputStream(output);
         InputStream is = new BufferedInputStream(bais);
         HashMap<String, Object> ctx2 = new HashMap<>();
         CompressedInputStream cis = new CompressedInputStream(is, ctx2);
         
         for (int i=0; i<length; i++)
            block[i] = 0;
         
         while (cis.read(block, 0, length) == length)
         {  }
         
         cis.close();
         is.close();
         long read = cis.getRead();
         int res = check(block, buf, length);
         
         if (res != 0)
            return res;
         
         return read ^ written;
      }
      catch (IOException e) 
      {
         System.out.println("Exception: " +e.getMessage());
         return 2;
      }
   }
   
   public static long compress2(byte[] block, int length)
   {
      try
      {
         final int blockSize = (length / (1 + new Random().nextInt(3))) & -16;
         boolean check = new Random().nextInt(2) == 0;
         int jobs = 1 + new Random().nextInt(3);
         System.out.println("Test - " + jobs + " job(s) " + ((check == true) ? "checksum" : ""));
         byte[] buf = new byte[length];
         System.arraycopy(block, 0, buf, 0, length);
         ByteArrayOutputStream baos = new ByteArrayOutputStream(2*block.length);
         OutputStream os = new BufferedOutputStream(baos);
         HashMap<String, Object> ctx1 = new HashMap<>();
         ctx1.put("transform", "LZX");
         ctx1.put("codec", "FPAQ");
         ctx1.put("blockSize", blockSize);
         ctx1.put("checksum", check);
         ctx1.put("pool", pool);
         ctx1.put("jobs", jobs);
         CompressedOutputStream cos = new CompressedOutputStream(os, ctx1);
         cos.write(block, 0, length);
         cos.close();
         os.close();
         long written = cos.getWritten();

         byte[] output = baos.toByteArray();
         ByteArrayInputStream bais = new ByteArrayInputStream(output);
         InputStream is = new BufferedInputStream(bais);
         HashMap<String, Object> ctx2 = new HashMap<>();
         ctx2.put("pool", pool); 
         ctx2.put("jobs", jobs);
         CompressedInputStream cis = new CompressedInputStream(is, ctx2);
         
         for (int i=0; i<length; i++)
            block[i] = 0;
         
         while (cis.read(block, 0, length) == length)
         {  }
         
         cis.close();
         is.close();
         long read = cis.getRead();
         
         int res = check(block, buf, length);
         
         if (res != 0)
            return res;
         
         return read ^ written;
      }
      catch (IOException e) 
      {
         System.out.println("Exception: " +e.getMessage());
         return 2;
      }
   }

   public static long compress3(byte[] block, int length)
   {
      try
      {
         System.out.println("Test - incompressible data");
         final int blockSize = (length / (1 + new Random().nextInt(3))) & -16;
         byte[] buf = new byte[length];
         System.arraycopy(block, 0, buf, 0, length);
         ByteArrayOutputStream baos = new ByteArrayOutputStream(2*block.length);
         OutputStream os = new BufferedOutputStream(baos);
         HashMap<String, Object> ctx1 = new HashMap<>();
         ctx1.put("transform", "LZP+ZRLT");
         ctx1.put("codec", "ANS0");
         ctx1.put("blockSize", blockSize);
         ctx1.put("checksum", false);
         ctx1.put("pool", pool); 
         ctx1.put("jobs", 1);
         CompressedOutputStream cos = new CompressedOutputStream(os, ctx1);
         cos.write(block, 0, length);
         cos.close();
         os.close();
         long written = cos.getWritten();

         byte[] output = baos.toByteArray();
         ByteArrayInputStream bais = new ByteArrayInputStream(output);
         InputStream is = new BufferedInputStream(bais);
         HashMap<String, Object> ctx2 = new HashMap<>();
         CompressedInputStream cis = new CompressedInputStream(is, ctx2);
         
         for (int i=0; i<length; i++)
            block[i] = 0;
         
         while (cis.read(block, 0, length) == length)
         {  }
          
         cis.close();
         is.close();
         long read = cis.getRead();
         
         int res = check(block, buf, length);
         
         if (res != 0)
            return res;

         return read ^ written;
      }
      catch (IOException e)
      {
         System.out.println("Exception: " +e.getMessage());
         return 2;
      }
   }

   public static long compress4(byte[] block, int length)
   {
      try
      {
         System.out.println("Test - write after close");
         ByteArrayOutputStream baos = new ByteArrayOutputStream(2*block.length);
         OutputStream os = new BufferedOutputStream(baos);
         HashMap<String, Object> ctx1 = new HashMap<>();
         ctx1.put("transform", "NONE");
         ctx1.put("codec", "HUFFMAN");
         ctx1.put("blockSize", length);
         ctx1.put("checksum", false);
         ctx1.put("pool", pool); 
         ctx1.put("jobs", 1);
         CompressedOutputStream cos = new CompressedOutputStream(os, ctx1);
         cos.write(block, 0, length);
         cos.close();
         //cos.write(block, 0, length);
         cos.write(123);
         os.close();

         System.out.println("Failure: no exception thrown in write after close");
         return 1;
      }
      catch (IOException e)
      {
         System.out.println("OK, exception: " +e.getMessage());
         return 0;
      }
   }

   public static long compress5(byte[] block, int length)
   {
      try
      {
         System.out.println("Test - read after close");
         ByteArrayOutputStream baos = new ByteArrayOutputStream(2*block.length);
         OutputStream os = new BufferedOutputStream(baos);
         HashMap<String, Object> ctx1 = new HashMap<>();
         ctx1.put("transform", "NONE");
         ctx1.put("codec", "HUFFMAN");
         ctx1.put("blockSize", 4 * 1024 * 1024);
         ctx1.put("checksum", false);
         ctx1.put("pool", pool); 
         ctx1.put("jobs", 1);
         CompressedOutputStream cos = new CompressedOutputStream(os, ctx1);
         cos.write(block, 0, length);
         cos.close();
         os.close();

         byte[] output = baos.toByteArray();
         ByteArrayInputStream bais = new ByteArrayInputStream(output);
         InputStream is = new BufferedInputStream(bais);
         HashMap<String, Object> ctx2 = new HashMap<>();
         CompressedInputStream cis = new CompressedInputStream(is, ctx2);
         
         while (cis.read(block, 0, length) == length)
         {  }
         
         cis.close();
         //cis.read(block, 0, length);
         cis.read();
         is.close();

         System.out.println("Failure: no exception thrown in read after close");
         return 1;
      }
      catch (IOException e)
      {
         System.out.println("OK, exception: " +e.getMessage());
         return 0;
      }
   }   
   
   
   private static int check(byte[] data1, byte[] data2, int length)
   {
      for (int i=0; i<length; i++)
         if (data1[i] != data2[i])
            return 3;
      
      return 0;
   }
}
