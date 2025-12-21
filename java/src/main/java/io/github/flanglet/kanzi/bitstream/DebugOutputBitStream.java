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
import java.io.PrintStream;
import io.github.flanglet.kanzi.OutputBitStream;


/**
 * A utility wrapper for an {@link OutputBitStream} that provides debugging functionality by
 * printing the bits written to the delegate bitstream. This class uses the decorator design pattern
 * to enhance the behavior of the original output bitstream.
 * <p>
 * The output can be formatted in either decimal or hexadecimal, and the width of the printed output
 * can be specified.
 * </p>
 */
public final class DebugOutputBitStream implements OutputBitStream {
  private final OutputBitStream delegate;
  private final PrintStream out;
  private final int width;
  private int lineIndex;
  private boolean mark;
  private boolean hexa;
  private byte current;

  /**
   * Constructs a DebugOutputBitStream that writes to the standard output with a default width of 80
   * characters.
   *
   * @param bitstream the underlying OutputBitStream to be decorated
   * @throws NullPointerException if the provided bitstream is null
   */
  public DebugOutputBitStream(OutputBitStream bitstream) {
    this(bitstream, System.out, 80);
  }

  /**
   * Constructs a DebugOutputBitStream that writes to the specified PrintStream with a default width
   * of 80 characters.
   *
   * @param bitstream the underlying OutputBitStream to be decorated
   * @param out the PrintStream to write debug output
   * @throws NullPointerException if the provided bitstream or PrintStream is null
   */
  public DebugOutputBitStream(OutputBitStream bitstream, PrintStream out) {
    this(bitstream, out, 80);
  }

  /**
   * Constructs a DebugOutputBitStream that writes to the specified PrintStream and allows for a
   * custom width.
   *
   * @param bitstream the underlying OutputBitStream to be decorated
   * @param out the PrintStream to write debug output
   * @param width the width of the output in characters
   * @throws NullPointerException if the provided bitstream or PrintStream is null
   */
  public DebugOutputBitStream(OutputBitStream bitstream, PrintStream out, int width) {
    if (bitstream == null)
      throw new NullPointerException("Invalid null bitstream parameter");

    if (out == null)
      throw new NullPointerException("Invalid null print stream parameter");

    if ((width != -1) && (width < 8))
      width = 8;

    if (width != -1)
      width &= 0xFFFFFFF8;

    this.width = width;
    this.delegate = bitstream;
    this.out = out;
  }

  /**
   * Sets a mark flag that indicates whether the debug output should be marked.
   *
   * @param mark true to set the mark, false to unset it
   */
  public synchronized void setMark(boolean mark) {
    this.mark = mark;
  }

  /**
   * Checks if the mark flag is set.
   *
   * @return true if the mark is set, false otherwise
   */
  public synchronized boolean mark() {
    return this.mark;
  }

  /**
   * Sets the format for showing bytes as either hexadecimal or decimal.
   *
   * @param hex true to show bytes in hexadecimal format, false for decimal
   */
  public synchronized void showByte(boolean hex) {
    this.hexa = hex;
  }

  /**
   * Checks the current format for showing bytes.
   *
   * @return true if bytes are shown in hexadecimal format, false for decimal
   */
  public synchronized boolean showByte() {
    return this.hexa;
  }

  /**
   * Prints a byte value in the specified format to the output stream.
   *
   * @param val the byte value to be printed
   */
  protected synchronized void printByte(byte val) {
    String s;

    if (this.hexa) {
      s = "0x" + Integer.toHexString(val & 0xFF);
    } else {
      if ((val >= 0) && (val < 10))
        s = "00" + Integer.toString(val & 0xFF);
      else if ((val >= 0) && (val < 100))
        s = "0" + Integer.toString(val & 0xFF);
      else
        s = Integer.toString(val & 0xFF);
    }

    this.out.print(" [" + s + "] ");
  }

  /**
   * Processes and writes a single bit to the underlying bitstream, while also printing it for
   * debugging purposes.
   *
   * @param bit the bit to write (0 or 1)
   * @throws BitStreamException if an error occurs while writing
   */
  @Override
  public synchronized void writeBit(int bit) throws BitStreamException {
    bit &= 1; // Ensure bit is either 0 or 1
    this.out.print((bit == 1) ? "1" : "0");
    this.current <<= 1;
    this.current |= bit;
    this.lineIndex++;

    if (this.mark)
      this.out.print("w");

    if (this.width != -1) {
      if ((this.lineIndex - 1) % this.width == this.width - 1) {
        if (this.showByte())
          this.printByte(this.current);
        this.out.println();
        this.lineIndex = 0;
      } else if ((this.lineIndex & 7) == 0) {
        if (this.showByte())
          this.printByte(this.current);
        else
          this.out.print(" ");
      }
    } else if ((this.lineIndex & 7) == 0) {
      if (this.showByte())
        this.printByte(this.current);
      else
        this.out.print(" ");
    }

    this.delegate.writeBit(bit);
  }

  /**
   * Writes a specified number of bits from a long value to the underlying bitstream and prints them
   * for debugging.
   *
   * @param bits the bits to write
   * @param length the number of bits to write
   * @return the number of bits successfully written
   * @throws BitStreamException if an error occurs while writing
   */
  @Override
  public synchronized int writeBits(long bits, int length) throws BitStreamException {
    int res = this.delegate.writeBits(bits, length);

    for (int i = 1; i <= res; i++) {
      long bit = (bits >>> (res - i)) & 1;
      this.current <<= 1;
      this.current |= bit;
      this.lineIndex++;
      this.out.print((bit == 1) ? "1" : "0");

      if (this.mark && (i == res))
        this.out.print("w");

      if (this.width != -1) {
        if (this.lineIndex % this.width == 0) {
          if (this.showByte())
            this.printByte(this.current);
          this.out.println();
          this.lineIndex = 0;
        } else if ((this.lineIndex & 7) == 0) {
          if (this.showByte())
            this.printByte(this.current);
          else
            this.out.print(" ");
        }
      } else if ((this.lineIndex & 7) == 0) {
        if (this.showByte())
          this.printByte(this.current);
        else
          this.out.print(" ");
      }
    }

    return res;
  }

  /**
   * Writes a specified number of bits from a byte array to the underlying bitstream and prints them
   * for debugging.
   *
   * @param bits the array containing bits to write
   * @param start the starting index in the array
   * @param count the number of bits to write
   * @return the number of bits successfully written
   * @throws BitStreamException if an error occurs while writing
   */
  @Override
  public synchronized int writeBits(byte[] bits, int start, int count) throws BitStreamException {
    int res = this.delegate.writeBits(bits, start, count);

    for (int i = start; i < start + (count >> 3); i++) {
      for (int j = 7; j >= 0; j--) {
        byte bit = (byte) ((bits[i] >>> j) & 1);
        this.current <<= 1;
        this.current |= bit;
        this.lineIndex++;
        this.out.print((bit == 1) ? "1" : "0");

        if (this.mark && (i == res))
          this.out.print("w");

        if (this.width != -1) {
          if (this.lineIndex % this.width == 0) {
            if (this.showByte())
              this.printByte(this.current);
            this.out.println();
            this.lineIndex = 0;
          } else if ((this.lineIndex & 7) == 0) {
            if (this.showByte())
              this.printByte(this.current);
            else
              this.out.print(" ");
          }
        } else if ((this.lineIndex & 7) == 0) {
          if (this.showByte())
            this.printByte(this.current);
          else
            this.out.print(" ");
        }
      }
    }

    return res;
  }

  /**
   * Closes the underlying bitstream.
   *
   * @throws BitStreamException if an error occurs while closing
   */
  @Override
  public void close() throws BitStreamException {
    this.delegate.close();
  }

  /**
   * Retrieves the total number of bits written to the underlying bitstream.
   *
   * @return the number of bits written
   */
  @Override
  public long written() {
    return this.delegate.written();
  }
}

