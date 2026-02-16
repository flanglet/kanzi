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

package io.github.flanglet.kanzi.entropy;

import io.github.flanglet.kanzi.EntropyEncoder;
import io.github.flanglet.kanzi.Memory;
import io.github.flanglet.kanzi.OutputBitStream;
import io.github.flanglet.kanzi.Predictor;
import io.github.flanglet.kanzi.SliceByteArray;

/**
 * This class is an implementation of a Context Model based entropy encoder.
 * <p>
 * It uses a range coding approach where the current range is updated based on the predicted
 * probability of the next bit. The prediction is based on a context formed by previous bits.
 * </p>
 * <p>
 * The encoding process involves updating the range and normalizing it by writing bits to an
 * {@link OutputBitStream} when the range becomes too small.
 * </p>
 * <p>
 * This encoder is designed to adaptively encode binary data by maintaining probability models for
 * different contexts.
 * </p>
 */
public class CMEncoder implements EntropyEncoder {
  /**
   * The top value for the range, used in range coding. This value defines the maximum possible
   * range.
   */
  private static final long TOP = 0x00FFFFFFFFFFFFFFL;
  private static final long MASK_24_56 = 0x00FFFFFFFF000000L;
  private static final long MASK_0_24 = 0x0000000000FFFFFFL;
  private static final long MASK_0_32 = 0x00000000FFFFFFFFL;

  /**
   * The lower bound of the current range.
   */
  private long low;
  /**
   * The upper bound of the current range.
   */
  private long high;
  /**
   * The output bitstream to which compressed data is written.
   */
  private final OutputBitStream bitstream;
  /**
   * Flag indicating if the encoder has been disposed.
   */
  private boolean disposed;
  /**
   * A {@link SliceByteArray} used as a buffer for writing data to the bitstream.
   */
  private SliceByteArray sba;
  /**
   * An array of {@link CMPredictor} instances, used for different contexts.
   */
  private CMPredictor[] preds;
  /**
   * The current {@link Predictor} being used for probability estimation.
   */
  private Predictor predictor;

  /**
   * Creates a new {@code CMEncoder}.
   * <p>
   * The encoder is initialized with an {@link OutputBitStream} to write compressed data.
   * </p>
   *
   * @param bitstream The {@link OutputBitStream} to write compressed data to.
   * @throws NullPointerException if {@code bitstream} is {@code null}.
   */
  public CMEncoder(OutputBitStream bitstream) {
    if (bitstream == null)
      throw new NullPointerException("CM codec: Invalid null bitstream parameter");

    this.low = 0L;
    this.high = TOP;
    this.bitstream = bitstream;
    this.sba = new SliceByteArray(new byte[0], 0);
    this.preds = new CMPredictor[] {new CMPredictor(null), new CMPredictor(null)};
    this.predictor = this.preds[0];
  }

  /**
   * Encodes a block of data.
   * <p>
   * This method reads data from the provided byte array, encodes it using the context model, and
   * writes the compressed data to the internal bitstream.
   * </p>
   *
   * @param block The byte array containing the data to encode.
   * @param blkptr The starting position in the block.
   * @param count The number of bytes to encode.
   * @return The number of bytes encoded, or -1 if an error occurs (e.g., invalid parameters).
   */
  @Override
  public int encode(byte[] block, int blkptr, int count) {
    if ((block == null) || (blkptr + count > block.length) || (blkptr < 0) || (count < 0)
        || (count > 1 << 30))
      return -1;

    if (count == 0)
      return 0;

    int startChunk = blkptr;
    final int end = blkptr + count;
    int length = (count < 64) ? 64 : count;

    if (count >= 1 << 26) {
      // If the block is big (>=64MB), split the encoding to avoid allocating
      // too much memory.
      length = (count < (1 << 29)) ? count >> 3 : count >> 4;
    }

    // Split block into chunks, encode chunk and write bit array to bitstream
    while (startChunk < end) {
      final int chunkSize = startChunk + length < end ? length : end - startChunk;

      if (this.sba.array.length < (chunkSize + (chunkSize >> 3)))
        this.sba.array = new byte[chunkSize + (chunkSize >> 3)];

      this.sba.index = 0;
      final int endChunk = startChunk + chunkSize;
      int run = 0;
      this.predictor = this.preds[0];

      for (int i = startChunk; i < endChunk; i++) {
        if ((block[i] == 0) && (run < 254)) {
          run++;
          continue;
        }

        if (run > 0) {
          this.encodeBit(0, this.predictor.get());
          this.encodeBit(0, this.predictor.get());
          this.encodeBit(0, this.predictor.get());
          this.encodeBit(0, this.predictor.get());
          this.encodeBit(0, this.predictor.get());
          this.encodeBit(0, this.predictor.get());
          this.encodeBit(0, this.predictor.get());
          this.encodeBit(0, this.predictor.get());
          this.predictor = this.preds[1];

          this.encodeBit((run >> 7) & 1, this.predictor.get());
          this.encodeBit((run >> 6) & 1, this.predictor.get());
          this.encodeBit((run >> 5) & 1, this.predictor.get());
          this.encodeBit((run >> 4) & 1, this.predictor.get());
          this.encodeBit((run >> 3) & 1, this.predictor.get());
          this.encodeBit((run >> 2) & 1, this.predictor.get());
          this.encodeBit((run >> 1) & 1, this.predictor.get());
          this.encodeBit(run & 1, this.predictor.get());
          run = 0;
        }

        this.predictor = this.preds[0];
        int val = (block[i] & 0xFF);
        this.encodeBit((val >> 7) & 1, this.predictor.get());
        this.encodeBit((val >> 6) & 1, this.predictor.get());
        this.encodeBit((val >> 5) & 1, this.predictor.get());
        this.encodeBit((val >> 4) & 1, this.predictor.get());
        this.encodeBit((val >> 3) & 1, this.predictor.get());
        this.encodeBit((val >> 2) & 1, this.predictor.get());
        this.encodeBit((val >> 1) & 1, this.predictor.get());
        this.encodeBit(val & 1, this.predictor.get());
      }

      EntropyUtils.writeVarInt(this.bitstream, this.sba.index);
      this.bitstream.writeBits(this.sba.array, 0, 8 * this.sba.index);
      startChunk += chunkSize;

      if (startChunk < end)
        this.bitstream.writeBits(this.low | MASK_0_24, 56);
    }

    return count;
  }

  /**
   * Encodes a single bit based on a given prediction.
   * <p>
   * The range is split according to the prediction, and the bit is encoded by updating the range.
   * The predictor is then updated with the encoded bit.
   * </p>
   *
   * @param bit The bit to encode (0 or 1).
   * @param pred The prediction value (probability) for the bit, typically in the range [0, 256].
   *        This value is used to determine the split point in the range.
   */
  public void encodeBit(int bit, int pred) {
    // Calculate interval split
    // Written in a way to maximize accuracy of multiplication/division
    final long split = (((this.high - this.low) >>> 4) * pred) >>> 8;

    // Update fields with new interval bounds
    if (bit == 0)
      this.low += (split + 1);
    else
      this.high = this.low + split;

    // Update predictor
    this.predictor.update(bit);

    // Write unchanged first 32 bits to bitstream
    while (((this.low ^ this.high) & MASK_24_56) == 0)
      this.flush();
  }

  /**
   * Flushes 32 bits from the current range to the internal buffer.
   * <p>
   * This method is called when the range becomes too small and needs to be normalized.
   * </p>
   */
  private void flush() {
    Memory.BigEndian.writeInt32(this.sba.array, this.sba.index, (int) (this.high >>> 24));
    this.sba.index += 4;
    this.low <<= 32;
    this.high = (this.high << 32) | MASK_0_32;
  }

  /**
   * Returns the {@link OutputBitStream} used by this encoder.
   *
   * @return The {@link OutputBitStream}.
   */
  @Override
  public OutputBitStream getBitStream() {
    return this.bitstream;
  }

  /**
   * Disposes of any resources used by the encoder.
   * <p>
   * This method flushes the remaining bits in the range to the bitstream.
   * </p>
   */
  @Override
  public void dispose() {
    if (this.disposed == true)
      return;

    this.disposed = true;
    this.bitstream.writeBits(this.low | MASK_0_24, 56);
  }
}
