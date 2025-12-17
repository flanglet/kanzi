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

package io.github.flanglet.kanzi;

/**
 * This class represents exceptions specific to bit stream operations. It
 * provides different error codes to identify various error conditions.
 */
public class BitStreamException extends RuntimeException {

    private static final long serialVersionUID = 7279737120722476336L;

    /**
     * Error code for undefined errors.
     */
    public static final int UNDEFINED = 0;

    /**
     * Error code for input/output errors.
     */
    public static final int INPUT_OUTPUT = 1;

    /**
     * Error code for end-of-stream errors.
     */
    public static final int END_OF_STREAM = 2;

    /**
     * Error code for invalid stream errors.
     */
    public static final int INVALID_STREAM = 3;

    /**
     * Error code for stream closed errors.
     */
    public static final int STREAM_CLOSED = 4;

    private final int code;

    /**
     * Constructs a {@code BitStreamException} with an undefined error code.
     */
    protected BitStreamException() {
        this.code = UNDEFINED;
    }

    /**
     * Constructs a {@code BitStreamException} with the specified detail message and
     * error code.
     *
     * @param message
     *            the detail message
     * @param code
     *            the error code
     */
    public BitStreamException(String message, int code) {
        super(message);
        this.code = code;
    }

    /**
     * Constructs a {@code BitStreamException} with the specified detail message,
     * cause, and error code.
     *
     * @param message
     *            the detail message
     * @param cause
     *            the cause
     * @param code
     *            the error code
     */
    public BitStreamException(String message, Throwable cause, int code) {
        super(message, cause);
        this.code = code;
    }

    /**
     * Constructs a {@code BitStreamException} with the specified cause and error
     * code.
     *
     * @param cause
     *            the cause
     * @param code
     *            the error code
     */
    public BitStreamException(Throwable cause, int code) {
        super(cause);
        this.code = code;
    }

    /**
     * Returns the error code of this exception.
     *
     * @return the error code
     */
    public int getErrorCode() {
        return this.code;
    }
}
