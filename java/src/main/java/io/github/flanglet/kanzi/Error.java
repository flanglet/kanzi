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

/**
 * This final class defines constants for various error codes used
 * throughout the application.
 */
public final class Error {

    /**
     *  Missing paraneter
     */
    public static final int ERR_MISSING_PARAM = 1;

    /**
     *  Invalid block size
     */
    public static final int ERR_BLOCK_SIZE = 2;

    /**
     *  Invalid entropy coded
     */
    public static final int ERR_INVALID_CODEC = 3;

    /**
     * Failure to create a compressor
     */
    public static final int ERR_CREATE_COMPRESSOR = 4;

    /**
     * Failure to create a decompressor
     */
    public static final int ERR_CREATE_DECOMPRESSOR = 5;

    /**
     * The output should is a folder
     */
    public static final int ERR_OUTPUT_IS_DIR = 6;

    /**
     * Failure to ovwerwrite a file
     */
    public static final int ERR_OVERWRITE_FILE = 7;

    /**
     * Failure to create a file
     */
    public static final int ERR_CREATE_FILE = 8;

    /**
     * Failure to create a bit stream
     */
    public static final int ERR_CREATE_BITSTREAM = 9;

    /**
     * Failure to open a file
     */
    public static final int ERR_OPEN_FILE = 10;

    /**
     * Failure to read a file
     */
    public static final int ERR_READ_FILE = 11;

    /**
     * Failure to write a file
     */
    public static final int ERR_WRITE_FILE = 12;

    /**
     * Failure to process a block of data
     */
    public static final int ERR_PROCESS_BLOCK = 13;

    /**
     * Failure to create an entropy coded
     */
    public static final int ERR_CREATE_CODEC = 14;

    /**
     *  Invalid file
     */
    public static final int ERR_INVALID_FILE = 15;

    /**
     *  Invalid or unsupported bit stream version
     */
    public static final int ERR_STREAM_VERSION = 16;

    /**
     * Failure to create a stream
     */
    public static final int ERR_CREATE_STREAM = 17;

    /**
     * Invalid parameter
     */
    public static final int ERR_INVALID_PARAM = 18;

    /**
     * Checksum failure
     */
    public static final int ERR_CRC_CHECK = 19;

    /**
     *  Unknown error
     */
    public static final int ERR_UNKNOWN = 127;

    /**
     * Private constructor to prevent instantiation.
     */
    private Error() {
    }
}
