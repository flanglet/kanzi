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

package io.github.flanglet.kanzi.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code LyndonWords} class provides functionality for finding Lyndon words
 * in a given string. A Lyndon word is a string that is strictly smaller than
 * any of its non-trivial suffixes. This class splits a string into Lyndon words
 * based on the Chen-Fox algorithm.
 *
 * <p>
 * Note: This class is not thread-safe due to the mutable state of its
 * breakpoints list.
 * </p>
 */
public class LyndonWords {

    // List of breakpoints for the Lyndon words
    private final List<Integer> breakpoints;

    /**
     * Constructs a {@code LyndonWords} object, initializing the list of
     * breakpoints.
     */
    public LyndonWords() {
        this.breakpoints = new ArrayList<>();
    }

    /**
     * Finds the breakpoints of Lyndon words in a byte array.
     *
     * <p>
     * This method uses the Chen-Fox algorithm to find the breakpoints where the
     * string can be split into Lyndon words. It is not thread-safe.
     * </p>
     *
     * @param buf
     *            the byte array representing the string
     * @param length
     *            the length of the byte array
     * @return a list of breakpoints where Lyndon words occur
     */
    private List<Integer> chenFoxLyndonBreakpoints(byte[] buf, int length) {
        int k = 0;
        this.breakpoints.clear();

        while (k < length) {
            int i = k;
            int j = k + 1;

            // Find the suffixes which are lexicographically greater than the current prefix
            while (j < length && buf[i] <= buf[j]) {
                i = (buf[i] == buf[j]) ? i + 1 : k;
                j++;
            }

            // Record the breakpoint and adjust k to the next potential Lyndon word start
            while (k <= i) {
                k += (j - i);
                this.breakpoints.add(k);
            }
        }

        return this.breakpoints;
    }

    /**
     * Splits a string into Lyndon words using the default character encoding.
     *
     * @param s
     *            the input string to be split
     * @return an array of Lyndon words
     */
    public String[] split(String s) {
        return this.split(s, null); // relies on default encoding
    }

    /**
     * Splits a string into Lyndon words, using the specified character encoding.
     *
     * @param s
     *            the input string to be split
     * @param cs
     *            the charset to use for encoding the string, or {@code null} to use
     *            the default encoding
     * @return an array of Lyndon words
     */
    public String[] split(String s, Charset cs) {
        byte[] buf = (cs == null) ? s.getBytes(StandardCharsets.UTF_8) : s.getBytes(cs);
        this.chenFoxLyndonBreakpoints(buf, s.length());

        // Create an array to hold the Lyndon words
        String[] res = new String[this.breakpoints.size()];
        int n = 0;
        int prev = 0;

        // Split the string based on the calculated breakpoints
        for (int bp : this.breakpoints) {
            res[n++] = s.substring(prev, bp);
            prev = bp;
        }

        return res;
    }

    /**
     * Returns the positions of the breakpoints in the input string using the
     * default character encoding.
     *
     * @param s
     *            the input string to be analyzed
     * @return an array of integers representing the positions of the Lyndon word
     *         breakpoints
     */
    public int[] getPositions(String s) {
        return this.getPositions(s, null); // relies on default encoding
    }

    /**
     * Returns the positions of the breakpoints in the input string using the
     * specified character encoding.
     *
     * @param s
     *            the input string to be analyzed
     * @param cs
     *            the charset to use for encoding the string, or {@code null} to use
     *            the default encoding
     * @return an array of integers representing the positions of the Lyndon word
     *         breakpoints
     */
    public int[] getPositions(String s, Charset cs) {
        byte[] buf = (cs == null) ? s.getBytes(StandardCharsets.UTF_8) : s.getBytes(cs);
        return this.getPositions(buf, buf.length); // relies on default encoding
    }

    /**
     * Returns the positions of the breakpoints in the byte array.
     *
     * @param buf
     *            the byte array representing the string
     * @param length
     *            the length of the byte array
     * @return an array of integers representing the positions of the Lyndon word
     *         breakpoints
     */
    public int[] getPositions(byte[] buf, int length) {
        this.chenFoxLyndonBreakpoints(buf, length);
        int[] res = new int[this.breakpoints.size()];
        int n = 0;

        // Fill the result array with the breakpoints
        for (Integer bp : this.breakpoints) {
            res[n++] = bp;
        }

        return res;
    }

    /**
     * Main method for testing the Lyndon word splitting functionality.
     *
     * <p>
     * This method demonstrates the use of the {@code split} method to split a
     * string into Lyndon words.
     * </p>
     *
     * @param args
     *            command-line arguments (not used)
     */
    public static void main(String[] args) {
        String[] ss = new LyndonWords().split("TO_BE_OR_NOT_TO_BE");

        // Print the resulting Lyndon words
        for (String s : ss) {
            System.out.println(s);
        }
    }
}
