/*
Copyright 2011-2024 Frederic Langlet
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

import java.io.OutputStream;


/**
 * An output stream that discards all data written to it.
 * This is useful for situations where you need an output stream
 * but do not want to actually output any data.
 */
public class NullOutputStream extends OutputStream {

    /**
     * Writes the specified byte to this output stream.
     * This implementation does not perform any action.
     *
     * @param b the byte to be written
     */
    @Override
    public void write(int b) {
        // No operation performed
    }

    /**
     * Writes len bytes from the specified byte array starting at
     * offset offs to this output stream. This implementation does
     * not perform any action.
     *
     * @param b    the byte array containing the data to be written
     * @param offs the start offset in the data
     * @param len  the number of bytes to write
     */
    @Override
    public void write(byte[] b, int offs, int len) {
        // No operation performed
    }
}
