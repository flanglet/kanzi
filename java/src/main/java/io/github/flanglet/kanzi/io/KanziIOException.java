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

package io.github.flanglet.kanzi.io;


/**
 * Custom exception class that extends {@link java.io.IOException}.
 * This exception includes an error code to provide more specific information
 * about the nature of the I/O error that occurred.
 */
public class KanziIOException extends java.io.IOException {
    private static final long serialVersionUID = -9153775235137373283L;

    private final int code;

    /**
     * Constructs a new {@code KanziIOException} with the specified detail message
     * and error code.
     *
     * @param msg the detail message explaining the reason for the exception
     * @param code an integer error code that provides additional context about the error
     */
    public KanziIOException(String msg, int code) {
        super(msg);
        this.code = code;
    }

    /**
     * Returns the error code associated with this exception.
     *
     * @return the error code indicating the type of I/O error
     */
    public int getErrorCode() {
        return this.code;
    }
}
