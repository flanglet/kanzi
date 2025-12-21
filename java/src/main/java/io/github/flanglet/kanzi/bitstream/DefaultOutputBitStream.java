/*
 * Kanzi is a modern, modular, portable, and efficient lossless data compressor.
 *
 * Copyright (C) 2025 Frederic Langlet
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

package io.github.flanglet.kanzi.bitstream;

import io.github.flanglet.kanzi.BitStreamException;
import java.io.IOException;
import java.io.OutputStream;
import io.github.flanglet.kanzi.Memory;
import io.github.flanglet.kanzi.OutputBitStream;


/**
 * A default implementation of the {@link OutputBitStream} interface that writes bits to an output
 * stream. This class buffers output data for efficient bit-level writing and handles various edge
 * cases.
 * <p>
 * The output stream is written to using a byte buffer. The buffer size must be a multiple of 8 and
 * at least 1024 bytes.
 * </p>
 */
public final class DefaultOutputBitStream implements OutputBitStream {
  private final OutputStream os; // The output stream to write bits to
  private byte[] buffer; // Buffer to hold bytes before writing to the stream
  private boolean closed; // Indicates if the stream is closed
  private int position; // Index of the current byte in the buffer
  private int availBits; // Bits not consumed in the current byte
  private long written; // Total bits written so far
  private long current; // Cached bits to be written

  /**
   * Constructs a DefaultOutputBitStream with the specified output stream and buffer size.
   *
   * @param os the OutputStream to write bits to
   * @param bufferSize the size of the buffer (must be at least 1024 and a multiple of 8)
   * @throws NullPointerException if the provided OutputStream is null
   * @throws IllegalArgumentException if the buffer size is invalid
   */
  public DefaultOutputBitStream(OutputStream os, int bufferSize) {
    if (os == null)
      throw new NullPointerException("Invalid null output stream parameter");

    if (bufferSize < 1024)
      throw new IllegalArgumentException("Invalid buffer size (must be at least 1024)");

    if (bufferSize > 1 << 28)
      throw new IllegalArgumentException("Invalid buffer size (must be at most 268435456)");

    if ((bufferSize & 7) != 0)
      throw new IllegalArgumentException("Invalid buffer size (must be a multiple of 8)");

    this.os = os;
    this.buffer = new byte[bufferSize];
    this.availBits = 64;
  }


  /**
   * Writes the least significant bit of the specified integer to the output stream.
   *
   * @param bit the bit value to write (should be either 0 or 1)
   * @throws IllegalStateException if the stream is closed
   */
  @Override
  public void writeBit(int bit) {
    if (this.availBits <= 1) {
      // availBits = 0 if stream is closed => force pushCurrent()
      this.current |= (bit & 1);
      this.pushCurrent();
    } else {
      this.availBits--;
      this.current |= ((long) (bit & 1) << this.availBits);
    }
  }


  /**
   * Writes a specified number of bits from the provided long value to the output stream.
   *
   * @param value the long value containing the bits to write
   * @param count the number of bits to write (must be in the range [1..64])
   * @return the number of bits actually written
   * @throws IllegalArgumentException if the count is invalid
   * @throws IllegalStateException if the stream is closed
   */
  @Override
  public int writeBits(long value, int count) {
    if (count == 0)
      return 0;

    if (count > 64)
      throw new IllegalArgumentException("Invalid bit count: " + count + " (must be in [1..64])");

    this.current |= ((value << (64 - count)) >>> (64 - this.availBits));

    if (count >= this.availBits) {
      int remaining = count - this.availBits;
      pushCurrent();

      if (remaining != 0) {
        this.current = value << (64 - remaining);
        this.availBits -= remaining;
      }
    } else {
      this.availBits -= count;
    }

    return count;
  }


  /**
   * Writes the specified number of bits from the provided byte array to the output stream.
   *
   * @param bits the byte array containing the bits to write
   * @param start the starting index in the array
   * @param count the number of bits to write (must be in the range [1..64])
   * @return the number of bits actually written
   * @throws IllegalArgumentException if the count is invalid
   * @throws IllegalStateException if the stream is closed
   */
  @Override
  public int writeBits(byte[] bits, int start, int count) {
    if (this.isClosed() == true)
      throw new BitStreamException("Stream closed", BitStreamException.STREAM_CLOSED);

    if ((count >> 3) > bits.length - start)
      throw new IllegalArgumentException("Invalid length: " + count + " (must be in [1.."
          + (((long) (bits.length - start)) << 3) + "])");

    int remaining = count;

    // Byte aligned cursor ?
    if ((this.availBits & 7) == 0) {
      // Fill up this.current
      while ((this.availBits != 64) && (remaining >= 8)) {
        this.writeBits(bits[start], 8);
        start++;
        remaining -= 8;
      }

      // Copy bits array to internal buffer
      final int maxPos = this.buffer.length - 8;

      while ((remaining >> 3) >= maxPos - this.position) {
        System.arraycopy(bits, start, this.buffer, this.position, maxPos - this.position);
        start += (maxPos - this.position);
        remaining -= ((maxPos - this.position) << 3);
        this.position = maxPos;
        this.flush();
      }

      final int r = (remaining >> 6) << 3;

      if (r > 0) {
        System.arraycopy(bits, start, this.buffer, this.position, r);
        this.position += r;
        start += r;
        remaining -= (r << 3);
      }
    } else {
      // Not byte aligned
      if (remaining >= 64) {
        final int r = 64 - this.availBits;

        while (remaining >= 64) {
          final long value = Memory.BigEndian.readLong64(bits, start);
          this.current |= (value >>> r);
          this.pushCurrent();
          this.current = (value << -r);
          start += 8;
          remaining -= 64;
        }

        this.availBits -= r;
      }
    }

    // Last bytes
    while (remaining >= 8) {
      this.writeBits(bits[start] & 0xFF, 8);
      start++;
      remaining -= 8;
    }

    if (remaining > 0)
      this.writeBits(bits[start] >>> (8 - remaining), remaining);

    return count;
  }


  /**
   * Pushes the current 64 bits into the buffer. This method is called when the buffer needs to be
   * flushed.
   */
  private void pushCurrent() {
    Memory.BigEndian.writeLong64(this.buffer, this.position, this.current);
    this.availBits = 64;
    this.current = 0;
    this.position += 8;

    if (this.position >= this.buffer.length - 8) {
      this.flush();
    }
  }


  /**
   * Writes the buffer to the underlying output stream.
   *
   * @throws BitStreamException if the stream is closed or an I/O error occurs
   */
  private void flush() throws BitStreamException {
    if (this.isClosed()) {
      throw new BitStreamException("Stream closed", BitStreamException.STREAM_CLOSED);
    }

    try {
      if (this.position > 0) {
        this.os.write(this.buffer, 0, this.position);
        this.written += (((long) this.position) << 3);
        this.position = 0;
      }
    } catch (IOException e) {
      throw new BitStreamException(e.getMessage(), BitStreamException.INPUT_OUTPUT);
    }
  }

  /**
   * Closes the output stream and releases any resources associated with it. Subsequent calls to
   * write operations will throw an exception.
   *
   * @throws BitStreamException if the stream is closed or an I/O error occurs
   */
  @Override
  public void close() {
    if (this.isClosed() == true)
      return;

    final int savedBitIndex = this.availBits;
    final int savedPosition = this.position;
    final long savedCurrent = this.current;

    try {
      // Push last bytes (the very last byte may be incomplete)
      for (int shift = 56; this.availBits < 64; shift -= 8) {
        this.buffer[this.position++] = (byte) (this.current >> shift);
        this.availBits += 8;
      }

      this.written -= (this.availBits - 64);
      this.availBits = 64;
      this.flush();
    } catch (BitStreamException e) {
      // Revert fields to allow subsequent attempts in case of transient failure
      this.position = savedPosition;
      this.availBits = savedBitIndex;
      this.current = savedCurrent;
      throw e;
    }

    try {
      this.os.flush();
    } catch (IOException e) {
      throw new BitStreamException(e, BitStreamException.INPUT_OUTPUT);
    }

    this.closed = true;
    this.position = 0;
    this.availBits = 0;
    this.written -= 64; // adjust because this.availBits = 0

    // Reset fields to force a flush() and trigger an exception
    // on writeBit() or writeBits()
    this.buffer = new byte[8];
  }

  /**
   * Returns the total number of bits written so far, including both bits flushed to the output
   * stream and bits currently held in memory.
   *
   * @return the total number of bits written
   */
  @Override
  public long written() {
    return this.written + (this.position << 3) + (64 - this.availBits);
  }

  /**
   * Checks if the output stream is closed.
   *
   * @return true if the stream is closed; false otherwise
   */
  public boolean isClosed() {
    return this.closed;
  }
}

