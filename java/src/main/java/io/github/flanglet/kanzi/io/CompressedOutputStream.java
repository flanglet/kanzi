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

import java.io.ByteArrayOutputStream;
import io.github.flanglet.kanzi.transform.TransformFactory;
import io.github.flanglet.kanzi.Error;
import io.github.flanglet.kanzi.Event;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.flanglet.kanzi.BitStreamException;
import io.github.flanglet.kanzi.EntropyEncoder;
import io.github.flanglet.kanzi.Global;
import io.github.flanglet.kanzi.SliceByteArray;
import io.github.flanglet.kanzi.OutputBitStream;
import io.github.flanglet.kanzi.bitstream.DefaultOutputBitStream;
import io.github.flanglet.kanzi.entropy.EntropyCodecFactory;
import io.github.flanglet.kanzi.transform.Sequence;
import io.github.flanglet.kanzi.util.hash.XXHash32;
import io.github.flanglet.kanzi.util.hash.XXHash64;
import io.github.flanglet.kanzi.Listener;
import io.github.flanglet.kanzi.Magic;
import io.github.flanglet.kanzi.entropy.EntropyUtils;



/**
 * A custom {@link OutputStream} that performs data compression with configurable block sizes,
 * listeners, and multi-threading support. It processes the data in blocks and supports a variety
 * of compression and transformation techniques based on the provided context.
 * <p>
 * The class includes an {@link OutputBitStream} for writing the compressed data, along with a number
 * of other internal buffers and hashers to manage the compression process efficiently.
 * <p>
 * Compression is performed in blocks, and listeners can be registered to monitor the process.
 *
 * <p>Example usage:
 * <pre>
 * {@code
 * Map<String, Object> ctx = new HashMap<>();
 * try (CompressedOutputStream cos = new CompressedOutputStream(new FileOutputStream("output.bin"), ctx)) {
 *     // Write compressed data
 *     cos.write(data);
 * }
 * }
 * </pre>
 */
public class CompressedOutputStream extends OutputStream {

   // Constant values for compression parameters
   private static final int BITSTREAM_TYPE           = 0x4B414E5A; // "KANZ"
   private static final int BITSTREAM_FORMAT_VERSION = 6;
   private static final int COPY_BLOCK_MASK          = 0x80;
   private static final int TRANSFORMS_MASK          = 0x10;
   private static final int MIN_BITSTREAM_BLOCK_SIZE = 1024;
   private static final int MAX_BITSTREAM_BLOCK_SIZE = 1024*1024*1024;
   private static final int DEFAULT_BUFFER_SIZE      = 256*1024;
   private static final int SMALL_BLOCK_SIZE         = 15;
   private static final byte[] EMPTY_BYTE_ARRAY      = new byte[0];
   private static final int MAX_CONCURRENCY          = 64;
   private static final int CANCEL_TASKS_ID          = -1;

   private final int blockSize;
   private int bufferId; // index of current write buffer
   private final int nbInputBlocks;
   private final int jobs;
   private int bufferThreshold;
   private final XXHash32 hasher32;
   private final XXHash64 hasher64;
   private final SliceByteArray[] buffers; // input & output per block
   private final int entropyType;
   private final long transformType;
   private final long inputSize;
   private final OutputBitStream obs;
   private final AtomicBoolean initialized;
   private final AtomicBoolean closed;
   private final AtomicInteger blockId;
   private final ExecutorService pool;
   private final List<Listener> listeners;
   private final Map<String, Object> ctx;
   private final boolean headless;


    /**
     * Constructs a {@link CompressedOutputStream} using the default output bitstream and provided context.
     *
     * @param os The underlying output stream to which the compressed data will be written.
     * @param ctx The context map that holds configuration settings for the compression process.
     */
    public CompressedOutputStream(OutputStream os, Map<String, Object> ctx) {
        this(new DefaultOutputBitStream(os, DEFAULT_BUFFER_SIZE), ctx);
    }

    /**
     * Constructs a {@link CompressedOutputStream} using a custom output bitstream and provided context.
     *
     * @param obs The custom output bitstream to be used for writing compressed data.
     * @param ctx The context map that holds configuration settings for the compression process.
     * @throws NullPointerException if a null paramater has been provided.
     * @throws IllegalArgumentException if an invalid paramater has been provided.
     */
    public CompressedOutputStream(OutputBitStream obs, Map<String, Object> ctx) {
       if (obs == null)
          throw new NullPointerException("Invalid null output bitstream parameter");

       if (ctx == null)
          throw new NullPointerException("Invalid null context parameter");

       String entropyCodec = (String) ctx.get("entropy");

       if (entropyCodec == null)
          throw new NullPointerException("Invalid null entropy encoder type parameter");

       String transform = (String) ctx.get("transform");

       if (transform == null)
          throw new NullPointerException("Invalid null transform type parameter");

       int tasks = (Integer) ctx.getOrDefault("jobs", 1);

       if ((tasks <= 0) || (tasks > MAX_CONCURRENCY))
          throw new IllegalArgumentException("The number of jobs must be in [1.." + MAX_CONCURRENCY+ "]");

       final int bSize = (Integer) ctx.get("blockSize");

       if (bSize > MAX_BITSTREAM_BLOCK_SIZE)
          throw new IllegalArgumentException("The block size must be at most "+(MAX_BITSTREAM_BLOCK_SIZE>>20)+ " MiB");

       if (bSize < MIN_BITSTREAM_BLOCK_SIZE)
          throw new IllegalArgumentException("The block size must be at least "+MIN_BITSTREAM_BLOCK_SIZE);

       if ((bSize & -16) != bSize)
          throw new IllegalArgumentException("The block size must be a multiple of 16");

       ExecutorService threadPool = (ExecutorService) ctx.get("pool");

       if ((tasks > 1) && (threadPool == null))
          throw new IllegalArgumentException("The thread pool cannot be null when the number of jobs is "+tasks);

       this.obs = obs;
       this.entropyType = EntropyCodecFactory.getType(entropyCodec);
       this.transformType = new TransformFactory().getType(transform);
       this.blockSize = bSize;
       this.bufferThreshold = bSize;

       // If input size has been provided, calculate the number of blocks
       this.inputSize = (Long) ctx.getOrDefault("fileSize", 0L);
       final int nbBlocks = (this.inputSize == 0) ? 0 : (int) ((this.inputSize+(bSize-1)) / bSize);
       this.nbInputBlocks = Math.min(nbBlocks, MAX_CONCURRENCY-1);

       int checksum = (Integer) ctx.getOrDefault("checksum", 0);

       if (checksum == 32) {
          this.hasher32 = new XXHash32(BITSTREAM_TYPE);
          this.hasher64 = null;
       }
       else if (checksum == 64) {
          this.hasher32 = null;
          this.hasher64 = new XXHash64(BITSTREAM_TYPE);
       }
       else {
          this.hasher32 = null;
          this.hasher64 = null;
       }

       this.jobs = tasks;
       this.pool = threadPool;
       this.closed = new AtomicBoolean(false);
       this.initialized = new AtomicBoolean(false);
       this.buffers = new SliceByteArray[2*this.jobs];
       this.headless = (Boolean) ctx.getOrDefault("headerless", false);

       // Allocate first buffer and add padding for incompressible blocks
       final int bufSize = Math.max(this.blockSize + (this.blockSize>>3), 256*1024);
       this.buffers[0] = new SliceByteArray(new byte[bufSize], bufSize, 0);
       this.buffers[this.jobs] = new SliceByteArray(new byte[0], 0, 0);

       for (int i=1; i<this.jobs; i++) {
          this.buffers[i] = new SliceByteArray(EMPTY_BYTE_ARRAY, 0);
          this.buffers[this.jobs+i] = new SliceByteArray(EMPTY_BYTE_ARRAY, 0);
       }

       this.blockId = new AtomicInteger(0);
       this.listeners = new ArrayList<>(10);
       this.ctx = ctx;
    }

    /**
     * Writes the header information for the compressed data stream. This includes
     * metadata such as the compression format version, block sizes, and other relevant
     * parameters that the decompressor will need to interpret the stream.
     *
     * @throws IOException If an I/O error occurs while writing the header.
     */
    protected void writeHeader() throws IOException {
       if ((this.headless == true) || (this.initialized.getAndSet(true) == true))
          return;

       if (this.obs.writeBits(BITSTREAM_TYPE, 32) != 32)
          throw new io.github.flanglet.kanzi.io.IOException("Cannot write bitstream type to header", Error.ERR_WRITE_FILE);

       if (this.obs.writeBits(BITSTREAM_FORMAT_VERSION, 4) != 4)
          throw new io.github.flanglet.kanzi.io.IOException("Cannot write bitstream version to header", Error.ERR_WRITE_FILE);

       int chkSize = 0;

       if (this.hasher32 != null)
          chkSize = 1;
       else if (this.hasher64 != null)
          chkSize = 2;

       if (this.obs.writeBits(chkSize, 2) != 2)
          throw new io.github.flanglet.kanzi.io.IOException("Cannot write checksum type to header", Error.ERR_WRITE_FILE);

       if (this.obs.writeBits(this.entropyType, 5) != 5)
          throw new io.github.flanglet.kanzi.io.IOException("Cannot write entropy type to header", Error.ERR_WRITE_FILE);

       if (this.obs.writeBits(this.transformType, 48) != 48)
          throw new io.github.flanglet.kanzi.io.IOException("Cannot write transform types to header", Error.ERR_WRITE_FILE);

       if (this.obs.writeBits(this.blockSize >>> 4, 28) != 28)
          throw new io.github.flanglet.kanzi.io.IOException("Cannot write block size to header", Error.ERR_WRITE_FILE);

       // this.inputSize not provided or >= 2^48 -> 0, <2^16 -> 1, <2^32 -> 2, <2^48 -> 3
       int szMask = 0;

       if ((this.inputSize != 0) && (this.inputSize < (1L<<48))) {
          if (this.inputSize >= (1L<<32)) {
             szMask = 3;
          }
          else {
             long isz = this.inputSize;

             if (isz > (1L<<30)) {
                 isz >>>= 4;
                 szMask++;
             }

             szMask += ((Global.log2((int) isz)>>>4) + 1);
          }
       }

       if (this.obs.writeBits(szMask, 2) != 2)
          throw new io.github.flanglet.kanzi.io.IOException("Cannot write size of input to header", Error.ERR_WRITE_FILE);

       if (szMask > 0) {
           if (this.obs.writeBits(this.inputSize, 16*szMask) != 16*szMask)
              throw new io.github.flanglet.kanzi.io.IOException("Cannot write size of input to header", Error.ERR_WRITE_FILE);
       }

       if (this.obs.writeBits(0, 15) != 15)
          throw new io.github.flanglet.kanzi.io.IOException("Cannot write padding to header", Error.ERR_WRITE_FILE);

       final int seed = 0x01030507 * BITSTREAM_FORMAT_VERSION;
       final int HASH = 0x1E35A7BD;
       int cksum = HASH * seed;
       cksum ^= (HASH * ~chkSize);
       cksum ^= (HASH * ~this.entropyType);
       cksum ^= (HASH * (int) (~this.transformType >>> 32));
       cksum ^= (HASH * (int) ~this.transformType);
       cksum ^= (HASH * ~this.blockSize);

       if (szMask > 0) {
          cksum ^= (HASH * (int) (~this.inputSize >>> 32));
          cksum ^= (HASH * (int) ~this.inputSize);
       }

       cksum = (cksum >>> 23) ^ (cksum >>> 3);

       if (this.obs.writeBits(cksum, 24) != 24)
          throw new io.github.flanglet.kanzi.io.IOException("Cannot write checksum to header", Error.ERR_WRITE_FILE);
    }

    /**
     * Registers a listener to be notified of compression progress events.
     *
     * @param bl The listener to be added.
     * @return {@code true} if the listener was added successfully, {@code false} otherwise.
     */
    public boolean addListener(Listener bl) {
       return (bl != null) ? this.listeners.add(bl) : false;
    }

    /**
     * Removes a previously registered listener.
     *
     * @param bl The listener to be removed.
     * @return {@code true} if the listener was removed successfully, {@code false} otherwise.
     */
    public boolean removeListener(Listener bl) {
        return (bl != null) ? this.listeners.remove(bl) : false;
    }

    /**
     * Writes <code>len</code> bytes from the specified byte array
     * starting at offset <code>off</code> to this output stream.
     * The general contract for <code>write(array, off, len)</code> is that
     * some of the bytes in the array <code>array</code> are written to the
     * output stream in order; element <code>array[off]</code> is the first
     * byte written and <code>array[off+len-1]</code> is the last byte written
     * by this operation.
     * <p>
     * The <code>write</code> method of <code>OutputStream</code> calls
     * the write method of one argument on each of the bytes to be
     * written out. Subclasses are encouraged to override this method and
     * provide a more efficient implementation.
     * <p>
     * If <code>array</code> is <code>null</code>, a
     * <code>NullPointerException</code> is thrown.
     * <p>
     * If <code>off</code> is negative, or <code>len</code> is negative, or
     * <code>off+len</code> is greater than the length of the array
     * <code>array</code>, then an IndexOutOfBoundsException is thrown.
     *
     * @param      data the data.
     * @param      off   the start offset in the data.
     * @param      len   the number of bytes to write.
     * @exception  IOException  if an I/O error occurs. In particular,
     *             an <code>IOException</code> is thrown if the output
     *             stream is closed.
     */
    @Override
    public void write(byte[] data, int off, int len) throws IOException {
      if ((off < 0) || (len < 0) || (len + off > data.length))
         throw new IndexOutOfBoundsException();

      int remaining = len;

      while (remaining > 0) {
         // Limit to number of available bytes in current buffer
         final int lenChunk = Math.min(remaining, this.bufferThreshold-this.buffers[this.bufferId].index);

         if (lenChunk > 0) {
            // Process a chunk of in-buffer data. No access to bitstream required
            System.arraycopy(data, off, this.buffers[this.bufferId].array, this.buffers[this.bufferId].index, lenChunk);
            this.buffers[this.bufferId].index += lenChunk;
            off += lenChunk;
            remaining -= lenChunk;

            if (this.buffers[this.bufferId].index >= this.bufferThreshold) {
               // Current write buffer is full
               final int nbTasks = (this.nbInputBlocks == 0) ? this.jobs : Math.min(this.nbInputBlocks, this.jobs);

               if (this.bufferId+1 < nbTasks) {
                  this.bufferId++;
                  final int bufSize = Math.max(this.blockSize + (this.blockSize>>3), 256*1024);

                  if (this.buffers[this.bufferId].length == 0) {
                     this.buffers[this.bufferId].array = new byte[bufSize];
                     this.buffers[this.bufferId].length = bufSize;
                  }

                  this.buffers[this.bufferId].index = 0;
               }
               else {
                  // If all buffers are full, time to encode
                  this.processBlock();
               }
            }

            if (remaining == 0)
               break;
         }

         // Buffer full, time to encode
         this.write(data[off]);
         off++;
         remaining--;
      }
   }



    /**
     * Writes the specified byte to this output stream. The general
     * contract for <code>write</code> is that one byte is written
     * to the output stream. The byte to be written is the eight
     * low-order bits of the argument <code>b</code>. The 24
     * high-order bits of <code>b</code> are ignored.
     * <p>
     * Subclasses of <code>OutputStream</code> must provide an
     * implementation for this method.
     *
     * @param  b   the <code>byte</code>
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public void write(int b) throws IOException {
       try {
          if (this.buffers[this.bufferId].index >= this.bufferThreshold) {
             // Current write buffer is full
             final int nbTasks = (this.nbInputBlocks == 0) ? this.jobs : Math.min(this.nbInputBlocks, this.jobs);

             if (this.bufferId+1 < nbTasks) {
                this.bufferId++;

                final int bufSize = Math.max(this.blockSize + (this.blockSize>>3), 256*1024);

                if (this.buffers[this.bufferId].length == 0) {
                   this.buffers[this.bufferId].array = new byte[bufSize];
                   this.buffers[this.bufferId].length = bufSize;
                }

                this.buffers[this.bufferId].index = 0;
             }
             else {
                // If all buffers are full, time to encode
                if (this.closed.get() == true)
                   throw new io.github.flanglet.kanzi.io.IOException("Stream closed", Error.ERR_WRITE_FILE);

                this.processBlock();
             }
          }

          this.buffers[this.bufferId].array[this.buffers[this.bufferId].index++] = (byte) b;
       }
       catch (BitStreamException e) {
          throw new io.github.flanglet.kanzi.io.IOException(e.getMessage(), Error.ERR_READ_FILE);
       }
       catch (Exception e) {
          throw new io.github.flanglet.kanzi.io.IOException(e.getMessage(), Error.ERR_UNKNOWN);
       }
    }


    /**
     * Flushes this output stream and forces any buffered output bytes
     * to be written out. The general contract of <code>flush</code> is
     * that calling it is an indication that, if any bytes previously
     * written have been buffered by the implementation of the output
     * stream, such bytes should immediately be written to their
     * intended destination.
     * <p>
     * If the intended destination of this stream is an abstraction provided by
     * the underlying operating system, for example a file, then flushing the
     * stream guarantees only that bytes previously written to the stream are
     * passed to the operating system for writing; it does not guarantee that
     * they are actually written to a physical device such as a disk drive.
     * <p>
     * The <code>flush</code> method of <code>OutputStream</code> does nothing.
     *
     */
    @Override
    public void flush() {
       // Let the bitstream of the entropy encoder flush itself when needed
    }


    /**
     * Closes this output stream and releases any system resources
     * associated with this stream. The general contract of <code>close</code>
     * is that it closes the output stream. A closed stream cannot perform
     * output operations and cannot be reopened.
     *
     * @exception  IOException  if an I/O error occurs.
     */
    @Override
    public void close() throws IOException {
       if (this.closed.getAndSet(true) == true)
          return;

       this.processBlock();

       try {
          // Write end block of size 0
          this.obs.writeBits(0, 5); // write length-3 (5 bits max)
          this.obs.writeBits(0, 3);
          this.obs.close();
       }
       catch (BitStreamException e) {
          throw new io.github.flanglet.kanzi.io.IOException(e.getMessage(), e.getErrorCode());
       }

       this.listeners.clear();
       this.bufferThreshold = 0;

       // Release resources, force error on any subsequent write attempt
       for (int i=0; i<this.buffers.length; i++)
          this.buffers[i] = new SliceByteArray(EMPTY_BYTE_ARRAY, 0);
    }


    /**
     * Processes a block of data, compressing it according to the configured settings.
     * This method is typically called when the input buffer is full and the compressed
     * output needs to be written.
     *
     * @throws IOException If an I/O error occurs while processing the block.
     */
    private void processBlock() throws IOException {
       this.writeHeader();

       if (this.buffers[0].index == 0)
          return;

       try {
          // Protect against future concurrent modification of the list of block listeners
          Listener[] blockListeners = this.listeners.toArray(new Listener[this.listeners.size()]);
          int nbTasks = this.jobs;
          int[] jobsPerTask;

          // Assign optimal number of tasks and jobs per task (if the number of blocks is known)
          if (nbTasks > 1)
          {
             // Limit the number of jobs if there are fewer blocks that this.nbInputBlocks
             // It allows more jobs per task and reduces memory usage.
             if (this.nbInputBlocks > 0)
                 nbTasks = Math.min(this.nbInputBlocks, nbTasks);

             jobsPerTask = Global.computeJobsPerTask(new int[nbTasks], this.jobs, nbTasks);
          }
          else {
             jobsPerTask = new int[] { this.jobs };
          }

          List<Callable<Status>> tasks = new ArrayList<>(this.jobs);
          int firstBlockId = this.blockId.get();

          // Create as many tasks as non-empty buffers to encode
          for (int taskId=0; taskId<nbTasks; taskId++) {
             final int dataLength = this.buffers[taskId].index;

             if (dataLength == 0)
                break;

             Map<String, Object> map = new HashMap<>(this.ctx);
             map.put("jobs", jobsPerTask[taskId]);
             this.buffers[taskId].index = 0;

             Callable<Status> task = new EncodingTask(this.buffers[taskId],
                     this.buffers[this.jobs+taskId], dataLength, this.transformType,
                     this.entropyType, firstBlockId+taskId+1,
                     this.obs, this.hasher32, this.hasher64, this.blockId,
                     blockListeners, map);
             tasks.add(task);
          }

          if (tasks.size() == 1) {
             // Synchronous call
             Status status = tasks.get(0).call();

             if (status.error != 0)
                throw new io.github.flanglet.kanzi.io.IOException(status.msg, status.error);
          }
          else {
             // Invoke the tasks concurrently and validate the results
             for (Future<Status> result : this.pool.invokeAll(tasks)) {
                // Wait for completion of next task and validate result
                Status status = result.get();

                if (status.error != 0)
                   throw new io.github.flanglet.kanzi.io.IOException(status.msg, status.error);
             }
          }

          this.bufferId = 0;
       }
       catch (io.github.flanglet.kanzi.io.IOException e) {
          throw e;
       }
       catch (Exception e) {
          if (e instanceof InterruptedException)
             Thread.currentThread().interrupt();

          int errorCode = (e instanceof BitStreamException) ? ((BitStreamException) e).getErrorCode() :
                  Error.ERR_UNKNOWN;
          throw new io.github.flanglet.kanzi.io.IOException(e.getMessage(), errorCode);
       }
    }


    /**
     * Returns the total number of bytes written to the output stream so far.
     *
     * @return The number of bytes written, in bytes.
     */
    public long getWritten() {
        return (this.obs.written() + 7) >> 3;
    }

    /**
     * Notifies all registered listeners of a specific event.
     *
     * @param listeners An array of listeners to be notified.
     * @param evt The event to be passed to each listener.
     */
    static void notifyListeners(Listener[] listeners, Event evt) {
        for (Listener bl : listeners) {
            try {
                bl.processEvent(evt);
            } catch (Exception e) {
                // Ignore exceptions in block listeners
            }
        }
    }



   /**
    * A task used to encode a block of data. This task can run in parallel with other encoding tasks
    * to perform transformations and entropy encoding on the data.
    * <p>
    * Each encoding task processes a block of data, applies transformations and entropy encoding
    * according to the specified parameters, and produces a status indicating whether the encoding
    * was successful or encountered an error.
    * <p>
    * This class implements the {@link Callable} interface and is intended to be executed by an
    * {@link ExecutorService} to perform concurrent block encoding.
    */
   static class EncodingTask implements Callable<Status> {

       /** The input data slice to be processed (e.g., compressed block). */
       private final SliceByteArray data;

       /** The output buffer where the encoded data will be stored. */
       private final SliceByteArray buffer;

       /** The length of the block of data being processed. */
       private final int length;

       /** The type of transformation to apply to the data. */
       private final long transformType;

       /** The type of entropy encoding to use for the data. */
       private final int entropyType;

       /** The unique ID of the block being processed. */
       private final int blockId;

       /** The output bit stream used to write the encoded data. */
       private final OutputBitStream obs;

       /** A 32-bit hash function used for hashing the data. */
       private final XXHash32 hasher32;

       /** A 64-bit hash function used for hashing the data. */
       private final XXHash64 hasher64;

       /** An atomic integer to track the processed block ID. */
       private final AtomicInteger processedBlockId;

       /** An array of listeners to notify about progress or events. */
       private final Listener[] listeners;

       /** The context map that provides additional configuration for encoding. */
       private final Map<String, Object> ctx;

       /**
        * Constructs a new {@link EncodingTask} to encode a block of data.
        *
        * @param iBuffer The input data slice to be encoded.
        * @param oBuffer The output buffer where the encoded data will be stored.
        * @param length The length of the input data to encode.
        * @param transformType The transformation type to apply.
        * @param entropyType The entropy encoding type to use.
        * @param blockId The unique ID of the block.
        * @param obs The output bit stream used for writing the encoded data.
        * @param hasher32 A 32-bit hash function for hashing the data.
        * @param hasher64 A 64-bit hash function for hashing the data.
        * @param processedBlockId An atomic integer tracking the processed block ID.
        * @param listeners The array of listeners to notify of events.
        * @param ctx The context map providing configuration for encoding.
        */
       EncodingTask(SliceByteArray iBuffer, SliceByteArray oBuffer, int length,
                 long transformType, int entropyType, int blockId,
                 OutputBitStream obs, XXHash32 hasher32, XXHash64 hasher64,
                 AtomicInteger processedBlockId, Listener[] listeners,
                 Map<String, Object> ctx) {
           this.data = iBuffer;
           this.buffer = oBuffer;
           this.length = length;
           this.transformType = transformType;
           this.entropyType = entropyType;
           this.blockId = blockId;
           this.obs = obs;
           this.hasher32 = hasher32;
           this.hasher64 = hasher64;
           this.processedBlockId = processedBlockId;
           this.listeners = listeners;
           this.ctx = ctx;
       }

       /**
        * Encodes the data block by applying the specified transformations and entropy encoding.
        * This method is executed by the {@link ExecutorService} and returns a {@link Status}
        * object indicating the result of the encoding process.
        *
        * @return A {@link Status} indicating the result of the block encoding.
        * @throws Exception If an error occurs during the encoding process.
        */
       @Override
       public Status call() throws Exception {
           return this.encodeBlock(this.data, this.buffer, this.length,
                   this.transformType, this.entropyType, this.blockId);
       }

       /**
        * Encodes the block of data using the specified transformation and entropy encoding types.
        * The encoding process may involve applying a series of transforms followed by entropy coding.
        *
        * The encoding mode is determined by the following flags:
        * <ul>
        * <li>Bit 7 (0x80) indicates whether the block is a copy block (when set).</li>
        * <li>Bits 6-5 (0b0yy00000) specify the size of the block minus one.</li>
        * <li>Bit 4 (0b000y0000) indicates if there are more than 4 transforms.</li>
        * <li>If there are 4 or fewer transforms, the lower 4 bits (0b0000yyyy) specify the transform skip flags.</li>
        * <li>If there are more than 4 transforms, the next byte (0b00000000) specifies skip flags.</li>
        * </ul>
        *
        * @param data The input data slice to be encoded.
        * @param buffer The output buffer to store the encoded data.
        * @param blockLength The length of the data block to encode.
        * @param blockTransformType The transformation type for the block.
        * @param blockEntropyType The entropy encoding type for the block.
        * @param currentBlockId The unique ID of the block being processed.
        *
        * @return A {@link Status} object indicating the result of the block encoding.
        */
       private Status encodeBlock(SliceByteArray data, SliceByteArray buffer,
                                  int blockLength, long blockTransformType,
                                  int blockEntropyType, int currentBlockId) {
            EntropyEncoder ee = null;

            try {
               if (blockLength == 0) {
                  this.processedBlockId.incrementAndGet();
                  return new Status(currentBlockId, 0, "Success");
               }

               byte mode = 0;
               int postTransformLength;
               long checksum = 0;
               Event.HashType hashType = Event.HashType.NO_HASH;

               // Compute block checksum
               if (this.hasher32 != null) {
                  checksum = this.hasher32.hash(data.array, data.index, blockLength) & 0x00000000FFFFFFFFL;
                  hashType = Event.HashType.SIZE_32;
               }
               else if (this.hasher64 != null) {
                  checksum = this.hasher64.hash(data.array, data.index, blockLength);
                  hashType = Event.HashType.SIZE_64;
               }

               if (this.listeners.length > 0) {
                  // Notify before transform
                  Event evt = new Event(Event.Type.BEFORE_TRANSFORM, currentBlockId,
                          blockLength, checksum, hashType);
                  notifyListeners(this.listeners, evt);
               }

               if (blockLength <= SMALL_BLOCK_SIZE) {
                  blockTransformType = TransformFactory.NONE_TYPE;
                  blockEntropyType = EntropyCodecFactory.NONE_TYPE;
                  mode |= COPY_BLOCK_MASK;
               }
               else {
                  boolean skipBlockOpt = (Boolean) this.ctx.getOrDefault("skipBlocks", false);

                  if (skipBlockOpt == true) {
                     boolean skipBlock = Magic.isCompressed(Magic.getType(data.array, data.index));

                     if (skipBlock == false) {
                        int[] histo = new int[256];
                        Global.computeHistogramOrder0(data.array, data.index, data.index+blockLength, histo, false);
                        final int entropy = Global.computeFirstOrderEntropy1024(blockLength, histo);
                        skipBlock = entropy >= EntropyUtils.INCOMPRESSIBLE_THRESHOLD;
                        //this.ctx.put("histo0", histo);
                     }

                     if (skipBlock == true) {
                        blockTransformType = TransformFactory.NONE_TYPE;
                        blockEntropyType = EntropyCodecFactory.NONE_TYPE;
                        mode |= COPY_BLOCK_MASK;
                     }
                  }
               }

               this.ctx.put("size", blockLength);
               Sequence transform = new TransformFactory().newFunction(this.ctx, blockTransformType);
               int requiredSize = transform.getMaxEncodedLength(blockLength);

               if (blockLength >= 4) {
                  final int magic = Magic.getType(data.array, 0);
  
                  if (Magic.isCompressed(magic) == true)
                     this.ctx.put("dataType", Global.DataType.BIN);
                  else if (Magic.isMultimedia(magic) == true)
                     this.ctx.put("dataType", Global.DataType.MULTIMEDIA);
                  else if (Magic.isExecutable(magic) == true)
                     this.ctx.put("dataType", Global.DataType.EXE);
               }

               if (buffer.length < requiredSize) {
                  buffer.length = requiredSize;

                  if (buffer.array.length < buffer.length)
                     buffer.array = new byte[buffer.length];
               }

               // Forward transform (ignore error, encode skipFlags)
               buffer.index = 0;
               data.length = blockLength;
               transform.forward(data, buffer);
               postTransformLength = buffer.index;

               if (postTransformLength < 0) {
                  this.processedBlockId.set(CANCEL_TASKS_ID);
                  return new Status(currentBlockId, Error.ERR_WRITE_FILE, "Invalid transform size");
               }

               this.ctx.put("size", postTransformLength);
               final int dataSize = (postTransformLength < 256) ? 1 : (Global.log2(postTransformLength)>>3) + 1;

               if (dataSize > 4) {
                  this.processedBlockId.set(CANCEL_TASKS_ID);
                  return new Status(currentBlockId, Error.ERR_WRITE_FILE, "Invalid block data length");
               }

               // Register flags and functions
               final int skipFlags = transform.getSkipFlags() & 0xFF;
               final int nbFunctions = transform.getNbFunctions();
               transform = null;

               // Record size of 'block size' - 1 in bytes
               mode |= (((dataSize-1) & 0x03) << 5);

               if (this.listeners.length > 0) {
                  // Notify after transform
                  Event evt = new Event(Event.Type.AFTER_TRANSFORM, currentBlockId,
                          postTransformLength, checksum, hashType);
                  notifyListeners(this.listeners, evt);
               }

               final int bufSize = Math.max(256*1024, Math.max(postTransformLength, blockLength+(blockLength>>3)));

               if (data.length < bufSize) {
                  // Rare case where the transform expanded the input or entropy coder
                  // may expand size
                  data.length = bufSize;

                  if (data.array.length < data.length)
                     data.array = new byte[data.length];
               }

               this.data.index = 0;
               CustomByteArrayOutputStream baos = new CustomByteArrayOutputStream(this.data.array, this.data.length);
               DefaultOutputBitStream os = new DefaultOutputBitStream(baos, 16384);

               if (((mode & COPY_BLOCK_MASK) != 0) || (nbFunctions <= 4)) {
                  mode |= (skipFlags>>>4);
                  os.writeBits(mode, 8);
               }
               else {
                  mode |= TRANSFORMS_MASK;
                  os.writeBits(mode, 8);
                  os.writeBits(skipFlags, 8);
               }

               os.writeBits(postTransformLength, 8*dataSize);

               // Write checksum
               if (this.hasher32 != null)
                  os.writeBits(checksum, 32);
               else if (this.hasher64 != null)
                  os.writeBits(checksum, 64);

               if (this.listeners.length > 0) {
                  // Notify before entropy
                  Event evt = new Event(Event.Type.BEFORE_ENTROPY, currentBlockId,
                          postTransformLength, checksum, hashType);
                  notifyListeners(this.listeners, evt);
               }

               // Each block is encoded separately
               // Rebuild the entropy encoder to reset block statistics
               ee = EntropyCodecFactory.newEncoder(os, this.ctx, blockEntropyType);

               // Entropy encode block
               if (ee.encode(buffer.array, 0, postTransformLength) != postTransformLength) {
                  this.processedBlockId.set(CANCEL_TASKS_ID);
                  return new Status(currentBlockId, Error.ERR_PROCESS_BLOCK, "Entropy coding failed");
               }

               // Dispose before displaying statistics. Dispose may write to the bitstream
               ee.dispose();

               // Force ee to null to avoid double dispose (in the finally section)
               ee = null;

               os.close();
               long written = os.written();

               // Lock free synchronization
               while (true) {
                  final int taskId = this.processedBlockId.get();

                  if (taskId == CANCEL_TASKS_ID)
                     return new Status(currentBlockId, 0, "Canceled");

                  if (taskId == currentBlockId-1)
                     break;

                  // Wait for the concurrent task processing the previous block to complete
                  // entropy encoding. Entropy encoding must happen sequentially (and
                  // in the correct block order) in the bitstream.
                  // Backoff improves performance in heavy contention scenarios
                  Thread.onSpinWait(); // Use Thread.yield() with JDK 8 and below
               }

               if (this.listeners.length > 0) {
                  // Notify after entropy
                  Event evt1 = new Event(Event.Type.AFTER_ENTROPY,
                          currentBlockId, (written+7) >> 3, checksum, hashType);
                  notifyListeners(this.listeners, evt1);

                  int v = (Integer) this.ctx.getOrDefault("verbosity", 0);

                  if (v >= 5) {
                     final long blockOffset = this.obs.written();
                     String bsf = String.format("%8s", Integer.toBinaryString(skipFlags)).replace(" ","0");
                     String msg = String.format("{ \"type\":\"%s\", \"id\": %d, \"offset\":%d, \"skipFlags\":%s }",
                           "BLOCK_INFO", currentBlockId, blockOffset, bsf);
                     Event evt2 = new Event(Event.Type.BLOCK_INFO, currentBlockId, msg);
                     notifyListeners(this.listeners, evt2);
                  }
               }

               // Emit block size in bits (max size pre-entropy is 1 GB = 1 << 30 bytes)
               final int lw = (written < 8) ? 3 : Global.log2((int) (written >> 3)) + 4;
               this.obs.writeBits(lw-3, 5); // write length-3 (5 bits max)
               this.obs.writeBits(written, lw);
               int chkSize = (int) Math.min(written, 1<<30);

               // Emit data to shared bitstream
               for (int n=0; written>0; ) {
                  this.obs.writeBits(this.data.array, n, chkSize);
                  n += ((chkSize+7) >> 3);
                  written -= chkSize;
                  chkSize = (int) Math.min(written, 1<<30);
               }

               // After completion of the entropy coding, increment the block id.
               // It unblocks the task processing the next block (if any).
               this.processedBlockId.incrementAndGet();

               return new Status(currentBlockId, 0, "Success");
            }
            catch (Exception e) {
               this.processedBlockId.set(CANCEL_TASKS_ID);
               return new Status(currentBlockId, Error.ERR_PROCESS_BLOCK,
                  "Error in block "+currentBlockId+": "+e.getMessage());
            }
            finally {
               // Make sure to unfreeze next block
               if (this.processedBlockId.get() == this.blockId-1)
                  this.processedBlockId.incrementAndGet();

               if (ee != null)
                 ee.dispose();
            }
         }
      }


   /**
    * A static class representing the status of a block during the compression process.
    * It encapsulates information about the block ID, the error code (if any), and an optional
    * message that provides additional details about the status of the block.
    */
   static class Status {

       /** The ID of the block this status pertains to. */
       final int blockId;

       /** Error code: 0 indicates no error (OK), other values indicate errors. */
       final int error;

       /** A message that provides additional information about the block's status. */
       final String msg;

       /**
        * Constructs a new {@link Status} object.
        *
        * @param blockId The ID of the block that this status describes.
        * @param error The error code. A value of 0 indicates no error (OK), any other value indicates an error.
        * @param msg An optional message that provides additional context or details about the status of the block.
        */
       Status(int blockId, int error, String msg) {
           this.blockId = blockId;
           this.error = error;
           this.msg = msg;
       }
   }


   /**
    * A custom subclass of {@link ByteArrayOutputStream} that allows for using a pre-existing byte buffer
    * and ensures that the buffer is large enough to accommodate the specified size.
    *
    * This class overrides the default buffer used by {@link ByteArrayOutputStream} and allows for
    * more control over the byte array used internally.
    */
   static class CustomByteArrayOutputStream extends ByteArrayOutputStream {

       /**
        * Constructs a {@link CustomByteArrayOutputStream} using the specified buffer and size.
        *
        * @param buffer The byte array to use as the internal buffer. The buffer length must be greater than or
        *               equal to the specified size.
        * @param size The initial size of the output stream. This must not exceed the length of the buffer.
        *
        * @throws IllegalArgumentException If the provided buffer length is smaller than the specified size.
        */
       public CustomByteArrayOutputStream(byte[] buffer, int size) {
           super(size);

           // Ensure that the provided buffer is large enough
           if (buffer.length < size) {
               throw new IllegalArgumentException("Invalid buffer length: " + buffer.length);
           }

           // Use the provided buffer
           this.buf = buffer;
       }
   }

}
