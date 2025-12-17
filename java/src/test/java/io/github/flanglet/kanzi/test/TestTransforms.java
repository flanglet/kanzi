/*
 * Kanzi is a modern, modular, portable, and efficient lossless data compressor.
 *
 * Copyright (C) 2011-2025 Frederic Langlet
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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import io.github.flanglet.kanzi.ByteTransform;
import io.github.flanglet.kanzi.SliceByteArray;
import io.github.flanglet.kanzi.transform.FSDCodec;
import io.github.flanglet.kanzi.transform.LZCodec;
import io.github.flanglet.kanzi.transform.RLT;
import io.github.flanglet.kanzi.transform.ROLZCodec;
import io.github.flanglet.kanzi.transform.SBRT;
import io.github.flanglet.kanzi.transform.SRT;
import io.github.flanglet.kanzi.transform.TransformFactory;
import io.github.flanglet.kanzi.transform.ZRLT;
import org.junit.Assert;
import org.junit.Test;

public class TestTransforms {
    private final static Random RANDOM = new Random(Long.MAX_VALUE);

    public static void main(String[] args) {
        if (args.length == 0) {
            args = new String[]{"-TYPE=ALL"};
        }

        String type = args[0].toUpperCase();

        if (type.startsWith("-TYPE=")) {
            type = type.substring(6);
            System.out.println("Transform: " + type);

            if (type.equals("ALL")) {
                System.out.println("\n\nTestLZ");

                if (testCorrectness("LZ") == false)
                    System.exit(1);

                testSpeed("LZ");
                System.out.println("\n\nTestLZX");

                if (testCorrectness("LZX") == false)
                    System.exit(1);

                testSpeed("LZX");
                System.out.println("\n\nTestLZP");

                if (testCorrectness("LZP") == false)
                    System.exit(1);

                // testSpeed("LZP"); skip (returns false if not good enough compression)
                System.out.println("\n\nTestROLZ");

                if (testCorrectness("ROLZ") == false)
                    System.exit(1);

                testSpeed("ROLZ");
                System.out.println("\n\nTestROLZX");

                if (testCorrectness("ROLZX") == false)
                    System.exit(1);

                testSpeed("ROLZX");
                System.out.println("\n\nTestZRLT");

                if (testCorrectness("ZRLT") == false)
                    System.exit(1);

                testSpeed("ZRLT");
                System.out.println("\n\nTestRLT");

                if (testCorrectness("RLT") == false)
                    System.exit(1);

                testSpeed("RLT");
                System.out.println("\n\nTestSRT");

                if (testCorrectness("SRT") == false)
                    System.exit(1);

                testSpeed("SRT");
                System.out.println("\n\nTestRANK");

                if (testCorrectness("RANK") == false)
                    System.exit(1);

                testSpeed("RANK");
                System.out.println("\n\nTestMTFT");

                if (testCorrectness("MTFT") == false)
                    System.exit(1);

                testSpeed("MTFT");
                System.out.println("\n\nTestFSD");

                if (testCorrectness("FSD") == false)
                    System.exit(1);

                // testSpeed("FSD"); no good data
            } else {
                System.out.println("Test" + type);

                if (testCorrectness(type) == false)
                    System.exit(1);

                testSpeed(type);
            }
        }
    }

    @Test
    public void testTransforms() {
        System.out.println("\n\nTestRANK");
        Assert.assertTrue(testCorrectness("RANK"));
        // testSpeed("RANK");
        System.out.println("\n\nTestMTFT");
        Assert.assertTrue(testCorrectness("MTFT"));
        // testSpeed("MTFT");
        System.out.println("\n\nTestSRT");
        Assert.assertTrue(testCorrectness("SRT"));
        // testSpeed("SRT");
        System.out.println("\n\nTestLZ");
        Assert.assertTrue(testCorrectness("LZ"));
        // testSpeed("LZ");
        System.out.println("\n\nTestLZX");
        Assert.assertTrue(testCorrectness("LZX"));
        // testSpeed("LZX");
        System.out.println("\n\nTestLZP");
        Assert.assertTrue(testCorrectness("LZP"));
        // testSpeed("LZP");
        System.out.println("\n\nTestROLZ");
        Assert.assertTrue(testCorrectness("ROLZ"));
        // testSpeed("ROLZ");
        System.out.println("\n\nTestROLZX");
        Assert.assertTrue(testCorrectness("ROLZX"));
        // testSpeed("ROLZX");
        System.out.println("\n\nTestZRLT");
        Assert.assertTrue(testCorrectness("ZRLT"));
        // testSpeed("ZRLT");
        System.out.println("\n\nTestFSD");
        Assert.assertTrue(testCorrectness("MM"));
        // testSpeed("MM");
        System.out.println("\n\nTestRLT");
        Assert.assertTrue(testCorrectness("RLT"));
        // testSpeed("RLT");
    }

    private static ByteTransform getTransform(String name) {
        switch (name) {
            case "LZ" :
                return new LZCodec();

            case "LZX" :
                Map<String, Object> ctx1 = new HashMap<>();
                ctx1.put("lz", TransformFactory.LZX_TYPE);
                return new LZCodec(ctx1);

            case "LZP" :
                Map<String, Object> ctx2 = new HashMap<>();
                ctx2.put("lz", TransformFactory.LZP_TYPE);
                return new LZCodec(ctx2);

            case "ZRLT" :
                return new ZRLT();

            case "RLT" :
                return new RLT();

            case "SRT" :
                return new SRT();

            case "MM" :
                return new FSDCodec();

            case "ROLZ" :
                return new ROLZCodec(false);

            case "ROLZX" :
                return new ROLZCodec(true);

            case "RANK" :
                return new SBRT(SBRT.MODE_RANK);

            case "MTFT" :
                return new SBRT(SBRT.MODE_MTF);

            default :
                System.out.println("No such byte transform: " + name);
                return null;
        }
    }

    private static boolean testCorrectness(String name) {
        byte[] input;
        byte[] output;
        byte[] reverse;

        // Test behavior
        System.out.println("Correctness test for " + name);
        int range = 256;

        for (int ii = 0; ii <= 50; ii++) {
            System.out.println("\nTest " + ii);
            int[] arr = new int[0];

            if (ii == 0) {
                arr = new int[]{0, 1, 2, 2, 2, 2, 7, 9, 9, 16, 16, 16, 1, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
                        3, 3, 3, 3};
            } else if (ii < 10) {
                arr = new int[80000];

                for (int i = 0; i < arr.length; i++)
                    arr[i] = (byte) ii;
            } else if (ii == 10) {
                arr = new int[80000];
                arr[0] = (byte) 1;

                for (int i = 1; i < arr.length; i++)
                    arr[i] = (byte) 8;
            } else if (ii == 11) {
                arr = new int[80000];
                arr[0] = 1;

                for (int i = 1; i < arr.length; i++)
                    arr[i] = 8;
            } else if (ii == 12) {
                arr = new int[]{0, 0, 1, 1, 2, 2, 3, 3};
            } else if (ii == 13) {
                arr = new int[512];

                for (int i = 0; i < 256; i++) {
                    arr[2 * i] = i;
                    arr[2 * i + 1] = i;
                }

                arr[1] = 255;
            } else if (ii < 16) {
                // Lots of zeros
                arr = new int[1 << (ii + 6)];

                for (int i = 0; i < arr.length; i++) {
                    int val = RANDOM.nextInt(100);

                    if (val >= 33)
                        val = 0;

                    arr[i] = val;
                }
            } else if (ii == 16) {
                // Totally RANDOM
                arr = new int[512];

                for (int j = 20; j < arr.length; j++)
                    arr[j] = RANDOM.nextInt(range);
            } else {
                arr = new int[1024];
                int idx = 20;

                while (idx < arr.length) {
                    int len = RANDOM.nextInt(120); // above LZP min match threshold

                    if (len % 3 == 0)
                        len = 1;

                    int val = RANDOM.nextInt(range);
                    int end = (idx + len) < arr.length ? idx + len : arr.length;

                    for (int j = idx; j < end; j++)
                        arr[j] = val;

                    idx += len;
                }
            }

            int size = arr.length;
            ByteTransform f = getTransform(name);
            input = new byte[size];
            output = new byte[f.getMaxEncodedLength(size)];
            reverse = new byte[size];
            SliceByteArray sa1 = new SliceByteArray(input, 0);
            SliceByteArray sa2 = new SliceByteArray(output, 0);
            SliceByteArray sa3 = new SliceByteArray(reverse, 0);
            Arrays.fill(output, (byte) 0xAA);

            for (int i = 0; i < arr.length; i++) {
                input[i] = (byte) (arr[i] & 255);
            }

            System.out.println("\nOriginal: ");

            if (ii == 1) {
                System.out.print("1 8 (" + (arr.length - 1) + " times)");
            } else {
                for (int i = 0; i < input.length; i++) {
                    System.out.print((input[i] & 255) + " ");
                }
            }

            if (f.forward(sa1, sa2) == false) {
                System.out.println("\nNo compression (ratio > 1.0), skip reverse");
                continue;
            }

            if (sa1.index != input.length) {
                System.out.println("\nNo compression (ratio > 1.0), skip reverse");
                continue;
            }

            System.out.println("\nCoded: ");
            // java.util.Arrays.fill(input, (byte) 0);

            for (int i = 0; i < sa2.index; i++) {
                System.out.print((output[i] & 255) + " "); // +"("+Integer.toBinaryString(output[i] & 255)+") ");
            }

            f = getTransform(name);
            sa2.length = sa2.index;
            sa1.index = 0;
            sa2.index = 0;
            sa3.index = 0;

            if (f.inverse(sa2, sa3) == false) {
                System.out.println("Decoding error");
                return false;
            }

            System.out.println();
            System.out.println("Decoded: ");
            int idx = -1;

            for (int i = 0; i < input.length; i++) {
                if (input[i] != reverse[i]) {
                    idx = i;
                    break;
                }
            }

            if (idx == -1) {
                if (ii == 1) {
                    System.out.println("1 8 (" + (arr.length - 1) + " times)");
                } else {
                    for (int i = 0; i < reverse.length; i++)
                        System.out.print((reverse[i] & 255) + " ");

                    System.out.println();
                }

                System.out.println("Identical");
            } else {
                System.out.println("Different (index " + idx + ": " + input[idx] + " - " + reverse[idx] + ")");
                System.out.println("");

                for (int i = 0; i < idx; i++)
                    System.out.println(i + ": " + sa1.array[i] + " " + sa3.array[i]);

                System.out.println(idx + ": " + sa1.array[idx] + "* " + sa3.array[idx] + "*");
                return false;
            }

            System.out.println();
        }

        return true;
    }

    public static void testSpeed(String name) {
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

        for (int jj = 0; jj < 3; jj++) {
            ByteTransform f = getTransform(name);
            input = new byte[size];
            output = new byte[f.getMaxEncodedLength(size)];
            reverse = new byte[size];
            SliceByteArray sa1 = new SliceByteArray(input, 0);
            SliceByteArray sa2 = new SliceByteArray(output, 0);
            SliceByteArray sa3 = new SliceByteArray(reverse, 0);

            // Generate RANDOM data with runs
            // Leave zeros at the beginning for ZRLT to succeed
            int n = iter / 20;

            while (n < input.length) {
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

            for (int ii = 0; ii < iter; ii++) {
                f = getTransform(name);
                sa1.index = 0;
                sa2.index = 0;
                before = System.nanoTime();

                if (f.forward(sa1, sa2) == false) {
                    System.out.println("Encoding error");
                    continue;
                }

                after = System.nanoTime();
                delta1 += (after - before);
            }

            for (int ii = 0; ii < iter; ii++) {
                f = getTransform(name);
                sa2.length = sa2.index;
                sa3.index = 0;
                sa2.index = 0;
                before = System.nanoTime();

                if (f.inverse(sa2, sa3) == false) {
                    System.out.println("Decoding error");
                    System.exit(1);
                }

                after = System.nanoTime();
                delta2 += (after - before);
            }

            int idx = -1;

            // Sanity check
            for (int i = 0; i < sa1.index; i++) {
                if (sa1.array[i] != sa3.array[i]) {
                    idx = i;
                    break;
                }
            }

            if (idx >= 0) {
                System.out.println("Failure at index " + idx + " (" + sa1.array[idx] + "<->" + sa3.array[idx] + ")");
                for (int i = 0; i < idx; i++)
                    System.out.println(i + " " + sa1.array[i] + " " + sa3.array[i]);

                System.out.println(idx + " " + sa1.array[idx] + "* " + sa3.array[idx] + "*");
            }

            final long prod = (long) iter * (long) size;
            System.out.println(name + " encoding [ms]: " + delta1 / 1000000);
            System.out.println("Throughput [MiB/s]: " + prod * 1000000L / delta1 * 1000L / (1024 * 1024));
            System.out.println(name + " decoding [ms]: " + delta2 / 1000000);
            System.out.println("Throughput [MiB/s]: " + prod * 1000000L / delta2 * 1000L / (1024 * 1024));
        }
    }
}
