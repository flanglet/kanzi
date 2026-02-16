/*
 * Kanzi is a modern, modular, portable, and efficient lossless data compressor.
 *
 * Copyright (C) 2026 Frederic Langlet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.flanglet.kanzi.test;

import io.github.flanglet.kanzi.entropy.BinaryEntropyDecoder;
import io.github.flanglet.kanzi.entropy.BinaryEntropyEncoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import io.github.flanglet.kanzi.EntropyDecoder;
import io.github.flanglet.kanzi.EntropyEncoder;
import io.github.flanglet.kanzi.InputBitStream;
import io.github.flanglet.kanzi.OutputBitStream;
import io.github.flanglet.kanzi.bitstream.DebugOutputBitStream;
import io.github.flanglet.kanzi.bitstream.DefaultInputBitStream;
import io.github.flanglet.kanzi.bitstream.DefaultOutputBitStream;
import io.github.flanglet.kanzi.entropy.ANSRangeDecoder;
import io.github.flanglet.kanzi.entropy.ANSRangeEncoder;
import io.github.flanglet.kanzi.entropy.CMPredictor;
import io.github.flanglet.kanzi.entropy.ExpGolombDecoder;
import io.github.flanglet.kanzi.entropy.ExpGolombEncoder;
import io.github.flanglet.kanzi.entropy.HuffmanDecoder;
import io.github.flanglet.kanzi.entropy.HuffmanEncoder;
import io.github.flanglet.kanzi.Predictor;
import io.github.flanglet.kanzi.entropy.FPAQDecoder;
import io.github.flanglet.kanzi.entropy.FPAQEncoder;
import io.github.flanglet.kanzi.entropy.RangeDecoder;
import io.github.flanglet.kanzi.entropy.RangeEncoder;
import io.github.flanglet.kanzi.entropy.TPAQPredictor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;


public class TestEntropyCodec {
  private final static Random RANDOM = new Random();

  public static void main(String[] args) {
    if (args.length == 0) {
      args = new String[] {"-TYPE=ALL"};
    }

    String type = args[0].toUpperCase();

    if (type.startsWith("-TYPE=")) {
      type = type.substring(6);
      System.out.println("Codec: " + type);

      if (type.equals("ALL")) {
        System.out.println("\n\nTest Huffman Codec");


        testSpeed("HUFFMAN", 200);
        System.out.println("\n\nTest ANS0 Codec");


        testSpeed("ANS0", 200);
        System.out.println("\n\nTest ANS1 Codec");


        testSpeed("ANS1", 150);
        System.out.println("\n\nTest Range Codec");


        testSpeed("RANGE", 150);
        System.out.println("\n\nTest FPAQ Codec");


        testSpeed("FPAQ", 120);
        System.out.println("\n\nTest CM Codec");


        testSpeed("CM", 100);
        System.out.println("\n\nTestTPAQCodec");


        testSpeed("TPAQ", 75);
        System.out.println("\n\nTestExpGolombCodec");


        testSpeed("EXPGOLOMB", 150);
      } else {
        System.out.println("Test " + type + " Codec");
        testSpeed(type, 100);
      }
    }
  }


  private static Predictor getPredictor(String type) {
    if (type.equals("TPAQ"))
      return new TPAQPredictor(null);

    if (type.equals("CM"))
      return new CMPredictor(null);

    return null;
  }


  private static EntropyEncoder getEncoder(String name, OutputBitStream obs) {
    switch (name) {
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

      default:
        System.out.println("No such entropy encoder: " + name);
        return null;
    }
  }


  private static EntropyDecoder getDecoder(String name, InputBitStream ibs) {
    Map<String, Object> ctx = new HashMap<>();
    ctx.put("bsVersion", 6);

    switch (name) {
      case "CM":
      case "TPAQ":
        Predictor pred = getPredictor(name);

        if (pred == null) {
          System.out.println("No such entropy decoder: " + name);
          return null;
        }

        return new BinaryEntropyDecoder(ibs, pred);

      case "FPAQ":
        return new FPAQDecoder(ibs, ctx);

      case "HUFFMAN":
        return new HuffmanDecoder(ibs, ctx);

      case "ANS0":
        return new ANSRangeDecoder(ibs, ctx, 0);

      case "ANS1":
        return new ANSRangeDecoder(ibs, ctx, 1);

      case "RANGE":
        return new RangeDecoder(ibs);

      case "EXPGOLOMB":
        return new ExpGolombDecoder(ibs, true);

      default:
        System.out.println("No such entropy decoder: " + name);
        return null;
    }
  }


  @ParameterizedTest
  @CsvSource({"HUFFMAN", "ANS0", "ANS1", "RANGE", "FPAQ", "CM", "TPAQ", "TPAQ", "EXPGOLOMB"})
  void testCorrectness(String name) {
    // Test behavior
    System.out.println("Correctness test for " + name);

    for (int ii = 1; ii < 20; ii++) {
      System.out.println("\n\nTest " + ii);

      int finalii = ii;
      Assertions.assertDoesNotThrow(new Executable() {

        @Override
        public void execute() throws Throwable {
          byte[] values;

          if (finalii == 3)
            values = new byte[] {0, 0, 32, 15, -4, 16, 0, 16, 0, 7, -1, -4, -32, 0, 31, -1};
          else if (finalii == 2)
            values =
                new byte[] {61, 77, 84, 71, 90, 54, 57, 38, 114, 111, 108, 101, 61, 112, 114, 101};
          else if (finalii == 1) {
            values = new byte[40];

            for (int i = 0; i < values.length; i++)
              values[i] = (byte) 2; // all identical
          } else if (finalii == 4) {
            values = new byte[40];

            for (int i = 0; i < values.length; i++)
              values[i] = (byte) (2 + (i & 1)); // 2 symbols
          } else if (finalii == 5) {
            values = new byte[] {42};
          } else if (finalii == 6) {
            values = new byte[] {42, 42};
          } else {
            values = new byte[256];

            for (int i = 0; i < values.length; i++)
              values[i] =
                  (byte) (64 + 4 * finalii + TestEntropyCodec.RANDOM.nextInt(8 * finalii + 1));
          }

          System.out.println("Original:");

          for (int i = 0; i < values.length; i++)
            System.out.print((values[i] & 0xFF) + " ");

          System.out.println();
          System.out.println("\nEncoded:");
          ByteArrayOutputStream os = new ByteArrayOutputStream(16384);
          OutputBitStream obs = new DefaultOutputBitStream(os, 16384);
          DebugOutputBitStream dbgbs = new DebugOutputBitStream(obs, System.out);
          dbgbs.showByte(true);
          EntropyEncoder ec = getEncoder(name, dbgbs);

          Assertions.assertNotNull(ec);

          ec.encode(values, 0, values.length);
          ec.dispose();
          dbgbs.close();
          byte[] buf = os.toByteArray();
          InputBitStream ibs = new DefaultInputBitStream(new ByteArrayInputStream(buf), 1024);
          EntropyDecoder ed = getDecoder(name, ibs);

          Assertions.assertNotNull(ed);

          System.out.println();
          System.out.println("\nDecoded:");
          byte[] values2 = new byte[values.length];
          ed.decode(values2, 0, values2.length);
          ed.dispose();
          ibs.close();

          Assertions.assertDoesNotThrow(new Executable() {
            @Override
            public void execute() throws Throwable {
              for (int j = 0; j < values2.length; j++) {
                Assertions.assertEquals(values[j], values2[j]);
                System.out.print((values2[j] & 0xFF) + " ");
              }
            }
          });
          // System.out.println("\n" + ((ok == true) ? "Identical" : "Different"));
        }
      });
    }
  }


  public static void testSpeed(String name, int iter) {
    // Test speed
    System.out.println("\n\nSpeed test for " + name);
    int[] repeats = {3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5, 8, 9, 7, 9, 3};
    final int size = 500000;

    for (int jj = 0; jj < 3; jj++) {
      System.out.println("\nTest " + (jj + 1));
      byte[] values1 = new byte[size];
      byte[] values2 = new byte[size];
      long delta1 = 0, delta2 = 0;

      for (int ii = 0; ii < iter; ii++) {
        int idx = 0;

        for (int i = 0; i < size; i++) {
          int i0 = i;
          int len = repeats[idx];
          idx = (idx + 1) & 0x0F;
          byte b = (byte) TestEntropyCodec.RANDOM.nextInt(256);

          if (i0 + len >= size)
            len = size - i0 - 1;

          for (int j = i0; j < i0 + len; j++) {
            values1[j] = b;
            i++;
          }
        }

        // Encode
        ByteArrayOutputStream os = new ByteArrayOutputStream(size * 2);
        OutputBitStream obs = new DefaultOutputBitStream(os, size);
        EntropyEncoder ec = getEncoder(name, obs);

        if (ec == null)
          System.exit(1);

        long before1 = System.nanoTime();

        if (ec.encode(values1, 0, values1.length) < 0) {
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

        if (ed.decode(values2, 0, size) < 0) {
          System.out.println("Decoding error");
          System.exit(1);
        }

        ed.dispose();
        long after2 = System.nanoTime();
        delta2 += (after2 - before2);
        ibs.close();

        // Sanity check
        for (int i = 0; i < size; i++) {
          if (values1[i] != values2[i]) {
            System.out
                .println("Error at index " + i + " (" + values1[i] + "<->" + values2[i] + ")");
            break;
          }
        }
      }

      final long prod = (long) iter * (long) size;
      System.out.println("Encode [ms]        : " + delta1 / 1000000L);
      System.out.println("Throughput [KiB/s] : " + prod * 1000000L / delta1 * 1000L / 1024L);
      System.out.println("Decode [ms]        : " + delta2 / 1000000L);
      System.out.println("Throughput [KiB/s] : " + prod * 1000000L / delta2 * 1000L / 1024L);
    }
  }
}
