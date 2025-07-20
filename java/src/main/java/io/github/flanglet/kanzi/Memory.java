/*
Copyright 2011-2025 Frederic Langlet
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

package io.github.flanglet.kanzi;

import java.nio.ByteOrder;


/**
 * The {@code Memory} class provides utility methods for reading and writing
 * data in big-endian and little-endian formats, regardless of the CPU's native
 * endianness.
 */
public class Memory {

    /**
     * Private constructor to prevent instantiation.
     */
    private Memory() {
    }

    // Constants for shifting bits based on endianness
    private static final int SHIFT64_7 = (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) ? 56 : 0;
    private static final int SHIFT64_6 = (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) ? 48 : 8;
    private static final int SHIFT64_5 = (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) ? 40 : 16;
    private static final int SHIFT64_4 = (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) ? 32 : 24;
    private static final int SHIFT64_3 = (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) ? 24 : 32;
    private static final int SHIFT64_2 = (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) ? 16 : 40;
    private static final int SHIFT64_1 = (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) ? 8 : 48;
    private static final int SHIFT64_0 = (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) ? 0 : 56;

    private static final int SHIFT32_3 = (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) ? 24 : 0;
    private static final int SHIFT32_2 = (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) ? 16 : 8;
    private static final int SHIFT32_1 = (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) ? 8 : 16;
    private static final int SHIFT32_0 = (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) ? 0 : 24;

    private static final int SHIFT16_1 = (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) ? 8 : 0;
    private static final int SHIFT16_0 = (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) ? 0 : 8;

    /**
     * The {@code BigEndian} class provides methods for reading and writing
     * data in big-endian format.
     */
    public static class BigEndian {

        private BigEndian() {
        }

        /**
         * Reads a 64-bit long value from the specified byte array in big-endian order.
         *
         * @param buf the byte array
         * @param offset the offset within the array
         * @return the 64-bit long value
         */
        public static long readLong64(byte[] buf, int offset) {
            return ((((long) buf[offset + 7]) & 0xFF) << SHIFT64_7) |
                   ((((long) buf[offset + 6]) & 0xFF) << SHIFT64_6) |
                   ((((long) buf[offset + 5]) & 0xFF) << SHIFT64_5) |
                   ((((long) buf[offset + 4]) & 0xFF) << SHIFT64_4) |
                   ((((long) buf[offset + 3]) & 0xFF) << SHIFT64_3) |
                   ((((long) buf[offset + 2]) & 0xFF) << SHIFT64_2) |
                   ((((long) buf[offset + 1]) & 0xFF) << SHIFT64_1) |
                   ((((long) buf[offset])   & 0xFF) << SHIFT64_0);
        }

        /**
         * Reads a 32-bit int value from the specified byte array in big-endian order.
         *
         * @param buf the byte array
         * @param offset the offset within the array
         * @return the 32-bit int value
         */
        public static int readInt32(byte[] buf, int offset) {
            return ((buf[offset + 3] & 0xFF) << SHIFT32_3) |
                   ((buf[offset + 2] & 0xFF) << SHIFT32_2) |
                   ((buf[offset + 1] & 0xFF) << SHIFT32_1) |
                   ((buf[offset]   & 0xFF) << SHIFT32_0);
        }

        /**
         * Reads a 16-bit int value from the specified byte array in big-endian order.
         *
         * @param buf the byte array
         * @param offset the offset within the array
         * @return the 16-bit int value
         */
        public static int readInt16(byte[] buf, int offset) {
            return ((buf[offset + 1] & 0xFF) << SHIFT16_1) |
                   ((buf[offset]   & 0xFF) << SHIFT16_0);
        }

        /**
         * Writes a 64-bit long value to the specified byte array in big-endian order.
         *
         * @param buf the byte array
         * @param offset the offset within the array
         * @param val the 64-bit long value
         */
        public static void writeLong64(byte[] buf, int offset, long val) {
            buf[offset]   = (byte) (val >> SHIFT64_0);
            buf[offset+1] = (byte) (val >> SHIFT64_1);
            buf[offset+2] = (byte) (val >> SHIFT64_2);
            buf[offset+3] = (byte) (val >> SHIFT64_3);
            buf[offset+4] = (byte) (val >> SHIFT64_4);
            buf[offset+5] = (byte) (val >> SHIFT64_5);
            buf[offset+6] = (byte) (val >> SHIFT64_6);
            buf[offset+7] = (byte) (val >> SHIFT64_7);
        }

        /**
         * Writes a 32-bit int value to the specified byte array in big-endian order.
         *
         * @param buf the byte array
         * @param offset the offset within the array
         * @param val the 32-bit int value
         */
        public static void writeInt32(byte[] buf, int offset, int val) {
            buf[offset]   = (byte) (val >> SHIFT32_0);
            buf[offset+1] = (byte) (val >> SHIFT32_1);
            buf[offset+2] = (byte) (val >> SHIFT32_2);
            buf[offset+3] = (byte) (val >> SHIFT32_3);
        }

        /**
         * Writes a 16-bit int value to the specified byte array in big-endian order.
         *
         * @param buf the byte array
         * @param offset the offset within the array
         * @param val the 16-bit int value
         */
        public static void writeInt16(byte[] buf, int offset, int val) {
            buf[offset+1] = (byte) (val >> SHIFT16_0);
            buf[offset]   = (byte) (val >> SHIFT16_1);
        }
    }

    /**
     * The {@code LittleEndian} class provides methods for reading and writing
     * data in little-endian format.
     */
    public static class LittleEndian {

        private LittleEndian() {
        }

        /**
         * Reads a 64-bit long value from the specified byte array in little-endian order.
         *
         * @param buf the byte array
         * @param offset the offset within the array
         * @return the 64-bit long value
         */
        public static long readLong64(byte[] buf, int offset) {
            return ((((long) buf[offset + 7]) & 0xFF) << SHIFT64_0) |
                   ((((long) buf[offset + 6]) & 0xFF) << SHIFT64_1) |
                   ((((long) buf[offset + 5]) & 0xFF) << SHIFT64_2) |
                   ((((long) buf[offset + 4]) & 0xFF) << SHIFT64_3) |
                   ((((long) buf[offset + 3]) & 0xFF) << SHIFT64_4) |
                   ((((long) buf[offset + 2]) & 0xFF) << SHIFT64_5) |
                   ((((long) buf[offset + 1]) & 0xFF) << SHIFT64_6) |
                   ((((long) buf[offset])   & 0xFF) << SHIFT64_7);
        }

        /**
         * Reads a 32-bit int value from the specified byte array in little-endian order.
         *
         * @param buf the byte array
         * @param offset the offset within the array
         * @return the 32-bit int value
         */
        public static int readInt32(byte[] buf, int offset) {
            return ((buf[offset + 3] & 0xFF) << SHIFT32_0) |
                   ((buf[offset + 2] & 0xFF) << SHIFT32_1) |
                   ((buf[offset + 1] & 0xFF) << SHIFT32_2) |
                   ((buf[offset]   & 0xFF) << SHIFT32_3);
        }

        /**
         * Reads a 16-bit int value from the specified byte array in little-endian order.
         *
         * @param buf the byte array
         * @param offset the offset within the array
         * @return the 16-bit int value
         */
        public static int readInt16(byte[] buf, int offset) {
            return ((buf[offset + 1] & 0xFF) << SHIFT16_0) |
                   ((buf[offset]   & 0xFF) << SHIFT16_1);
        }

        /**
         * Writes a 64-bit long value to the specified byte array in little-endian order.
         *
         * @param buf the byte array
         * @param offset the offset within the array
         * @param val the 64-bit long value
         */
        public static void writeLong64(byte[] buf, int offset, long val) {
            buf[offset]   = (byte) (val >> SHIFT64_7);
            buf[offset + 1] = (byte) (val >> SHIFT64_6);
            buf[offset + 2] = (byte) (val >> SHIFT64_5);
            buf[offset + 3] = (byte) (val >> SHIFT64_4);
            buf[offset + 4] = (byte) (val >> SHIFT64_3);
            buf[offset + 5] = (byte) (val >> SHIFT64_2);
            buf[offset + 6] = (byte) (val >> SHIFT64_1);
            buf[offset + 7] = (byte) (val >> SHIFT64_0);
        }

        /**
         * Writes a 32-bit int value to the specified byte array in little-endian order.
         *
         * @param buf the byte array
         * @param offset the offset within the array
         * @param val the 32-bit int value
         */
        public static void writeInt32(byte[] buf, int offset, int val) {
            buf[offset]   = (byte) (val >> SHIFT32_3);
            buf[offset + 1] = (byte) (val >> SHIFT32_2);
            buf[offset + 2] = (byte) (val >> SHIFT32_1);
            buf[offset + 3] = (byte) (val >> SHIFT32_0);
        }

        /**
         * Writes a 16-bit int value to the specified byte array in little-endian order.
         *
         * @param buf the byte array
         * @param offset the offset within the array
         * @param val the 16-bit int value
         */
        public static void writeInt16(byte[] buf, int offset, int val) {
            buf[offset]   = (byte) (val >> SHIFT16_1);
            buf[offset + 1] = (byte) (val >> SHIFT16_0);
        }
    }

    /**
     * Main method for testing the functionality of the Memory class.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        int n = 0x01020304;
        byte[] buf = new byte[4];
        System.out.println("Is big endian: " + (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN));

        // Test Big Endian
        Memory.BigEndian.writeInt32(buf, 0, n);
        System.out.println(buf[0] + " " + buf[1] + " " + buf[2] + " " + buf[3]);
        int p = Memory.BigEndian.readInt32(buf, 0);
        System.out.println(Integer.toHexString(p));

        // Test Little Endian
        Memory.LittleEndian.writeInt32(buf, 0, n);
        System.out.println(buf[0] + " " + buf[1] + " " + buf[2] + " " + buf[3]);
        p = Memory.LittleEndian.readInt32(buf, 0);
        System.out.println(Integer.toHexString(p));

        // Test mixed Big Endian write and Little Endian read
        Memory.BigEndian.writeInt32(buf, 0, n);
        System.out.println(buf[0] + " " + buf[1] + " " + buf[2] + " " + buf[3]);
        p = Memory.LittleEndian.readInt32(buf, 0);
        System.out.println(Integer.toHexString(p));

        // Test mixed Little Endian write and Big Endian read
        Memory.LittleEndian.writeInt32(buf, 0, n);
        System.out.println(buf[0] + " " + buf[1] + " " + buf[2] + " " + buf[3]);
        p = Memory.BigEndian.readInt32(buf, 0);
        System.out.println(Integer.toHexString(p));
    }
}

