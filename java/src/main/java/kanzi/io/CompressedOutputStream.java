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

package kanzi.io;

import java.io.ByteArrayOutputStream;
import kanzi.transform.TransformFactory;
import kanzi.Error;
import kanzi.Event;
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
import kanzi.BitStreamException;
import kanzi.EntropyEncoder;
import kanzi.Global;
import kanzi.SliceByteArray;
import kanzi.OutputBitStream;
import kanzi.bitstream.DefaultOutputBitStream;
import kanzi.entropy.EntropyCodecFactory;
import kanzi.transform.Sequence;
import kanzi.util.hash.XXHash32;
import kanzi.Listener;
import kanzi.Magic;
import kanzi.entropy.EntropyUtils;



// Implementation of a java.io.OutputStream that compresses a stream
// using a 2 step process:
// - step 1: a ByteFunction is used to reduce the size of the input data (bytes input & output)
// - step 2: an EntropyEncoder is used to entropy code the results of step 1 (bytes input, bits output)
public class CompressedOutputStream extends OutputStream
{
   private static final int BITSTREAM_TYPE           = 0x4B414E5A; // "KANZ"
   private static final int BITSTREAM_FORMAT_VERSION = 5;
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
   private final XXHash32 hasher;
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


   public CompressedOutputStream(OutputStream os, Map<String, Object> ctx)
   {
      this(new DefaultOutputBitStream(os, DEFAULT_BUFFER_SIZE), ctx);
   }


   // Allow caller to provide custom output bitstream
   public CompressedOutputStream(OutputBitStream obs, Map<String, Object> ctx)
   {
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

      boolean checksum = (Boolean) ctx.getOrDefault("checksum", false);
      this.hasher = (checksum == true) ? new XXHash32(BITSTREAM_TYPE) : null;
      this.jobs = tasks;
      this.pool = threadPool;
      this.closed = new AtomicBoolean(false);
      this.initialized = new AtomicBoolean(false);
      this.buffers = new SliceByteArray[2*this.jobs];
      this.headless = (Boolean) ctx.getOrDefault("headerless", false);

      // Allocate first buffer and add padding for incompressible blocks
      final int bufSize = Math.max(this.blockSize + (this.blockSize>>6), 65536);
      this.buffers[0] = new SliceByteArray(new byte[bufSize], bufSize, 0);
      this.buffers[this.jobs] = new SliceByteArray(new byte[0], 0, 0);

      for (int i=1; i<this.jobs; i++)
      {
         this.buffers[i] = new SliceByteArray(EMPTY_BYTE_ARRAY, 0);
         this.buffers[this.jobs+i] = new SliceByteArray(EMPTY_BYTE_ARRAY, 0);
      }

      this.blockId = new AtomicInteger(0);
      this.listeners = new ArrayList<>(10);
      this.ctx = ctx;
   }

   protected void writeHeader() throws IOException
   {
      if (this.obs.writeBits(BITSTREAM_TYPE, 32) != 32)
         throw new kanzi.io.IOException("Cannot write bitstream type to header", Error.ERR_WRITE_FILE);

      if (this.obs.writeBits(BITSTREAM_FORMAT_VERSION, 4) != 4)
         throw new kanzi.io.IOException("Cannot write bitstream version to header", Error.ERR_WRITE_FILE);

      if (this.obs.writeBits((this.hasher != null) ? 1 : 0, 1) != 1)
         throw new kanzi.io.IOException("Cannot write checksum to header", Error.ERR_WRITE_FILE);

      if (this.obs.writeBits(this.entropyType, 5) != 5)
         throw new kanzi.io.IOException("Cannot write entropy type to header", Error.ERR_WRITE_FILE);

      if (this.obs.writeBits(this.transformType, 48) != 48)
         throw new kanzi.io.IOException("Cannot write transform types to header", Error.ERR_WRITE_FILE);

      if (this.obs.writeBits(this.blockSize >>> 4, 28) != 28)
         throw new kanzi.io.IOException("Cannot write block size to header", Error.ERR_WRITE_FILE);

      // this.inputSize not provided or >= 2^48 -> 0, <2^16 -> 1, <2^32 -> 2, <2^48 -> 3
      int szMask = 0;

      if ((this.inputSize != 0) && (this.inputSize < (1L<<48)))
      {
         if (this.inputSize >= (1L<<32))
            szMask = 3;
         else
            szMask = (Global.log2((int) this.inputSize)>>4) + 1;
      }

      if (this.obs.writeBits(szMask, 2) != 2)
         throw new kanzi.io.IOException("Cannot write size of input to header", Error.ERR_WRITE_FILE);

      if (szMask > 0)
      {
          if (this.obs.writeBits(this.inputSize, 16*szMask) != 16*szMask)
             throw new kanzi.io.IOException("Cannot write size of input to header", Error.ERR_WRITE_FILE);
      }

      final int HASH = 0x1E35A7BD;
      int cksum = HASH * BITSTREAM_FORMAT_VERSION;
      cksum ^= (HASH * ~this.entropyType);
      cksum ^= (HASH * (int) (~this.transformType >>> 32));
      cksum ^= (HASH * (int) ~this.transformType);
      cksum ^= (HASH * ~this.blockSize);

      if (szMask > 0)
      {
         cksum ^= (HASH * (int) (~this.inputSize >>> 32));
         cksum ^= (HASH * (int) ~this.inputSize);
      }

      cksum = (cksum >>> 23) ^ (cksum >>> 3);

      if (this.obs.writeBits(cksum, 16) != 16)
         throw new kanzi.io.IOException("Cannot write checksum to header", Error.ERR_WRITE_FILE);
   }


    public boolean addListener(Listener bl)
    {
       return (bl != null) ? this.listeners.add(bl) : false;
    }


    public boolean removeListener(Listener bl)
    {
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
     * <code>array</code>, then an <tt>IndexOutOfBoundsException</tt> is thrown.
     *
     * @param      data the data.
     * @param      off   the start offset in the data.
     * @param      len   the number of bytes to write.
     * @exception  IOException  if an I/O error occurs. In particular,
     *             an <code>IOException</code> is thrown if the output
     *             stream is closed.
     */
    @Override
    public void write(byte[] data, int off, int len) throws IOException
    {
      if ((off < 0) || (len < 0) || (len + off > data.length))
         throw new IndexOutOfBoundsException();

      int remaining = len;

      while (remaining > 0)
      {
         // Limit to number of available bytes in current buffer
         final int lenChunk = Math.min(remaining, this.bufferThreshold-this.buffers[this.bufferId].index);

         if (lenChunk > 0)
         {
            // Process a chunk of in-buffer data. No access to bitstream required
            System.arraycopy(data, off, this.buffers[this.bufferId].array, this.buffers[this.bufferId].index, lenChunk);
            this.buffers[this.bufferId].index += lenChunk;
            off += lenChunk;
            remaining -= lenChunk;

            if (this.buffers[this.bufferId].index >= this.bufferThreshold)
            {
               // Current write buffer is full
               final int nbTasks = (this.nbInputBlocks == 0) ? this.jobs : Math.min(this.nbInputBlocks, this.jobs);

               if (this.bufferId+1 < nbTasks)
               {
                  this.bufferId++;
                  final int bufSize = Math.max(this.blockSize + (this.blockSize>>6), 65536);

                  if (this.buffers[this.bufferId].length == 0)
                  {
                     this.buffers[this.bufferId].array = new byte[bufSize];
                     this.buffers[this.bufferId].length = bufSize;
                  }

                  this.buffers[this.bufferId].index = 0;
               }
               else
               {
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
    * @param      b   the <code>byte</code>..
    * @throws java.io.IOException
    */
   @Override
   public void write(int b) throws IOException
   {
      try
      {
         if (this.buffers[this.bufferId].index >= this.bufferThreshold)
         {
            // Current write buffer is full
            final int nbTasks = (this.nbInputBlocks == 0) ? this.jobs : Math.min(this.nbInputBlocks, this.jobs);
            if (this.bufferId+1 < nbTasks)
            {
               this.bufferId++;

               final int bufSize = Math.max(this.blockSize + (this.blockSize>>6), 65536);

               if (this.buffers[this.bufferId].length == 0)
               {
                  this.buffers[this.bufferId].array = new byte[bufSize];
                  this.buffers[this.bufferId].length = bufSize;
               }

               this.buffers[this.bufferId].index = 0;
            }
            else
            {
               // If all buffers are full, time to encode
               if (this.closed.get() == true)
                  throw new kanzi.io.IOException("Stream closed", Error.ERR_WRITE_FILE);

               this.processBlock();
            }
         }

         this.buffers[this.bufferId].array[this.buffers[this.bufferId].index++] = (byte) b;
      }
      catch (BitStreamException e)
      {
         throw new kanzi.io.IOException(e.getMessage(), Error.ERR_READ_FILE);
      }
      catch (Exception e)
      {
         if (e instanceof InterruptedException)
            Thread.currentThread().interrupt();

         throw new kanzi.io.IOException(e.getMessage(), Error.ERR_UNKNOWN);
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
   public void flush()
   {
      // Let the bitstream of the entropy encoder flush itself when needed
   }


   /**
    * Closes this output stream and releases any system resources
    * associated with this stream. The general contract of <code>close</code>
    * is that it closes the output stream. A closed stream cannot perform
    * output operations and cannot be reopened.
    * <p>
    *
    * @exception  IOException  if an I/O error occurs.
    */
   @Override
   public void close() throws IOException
   {
      if (this.closed.getAndSet(true) == true)
         return;

      this.processBlock();

      try
      {
         // Write end block of size 0
         this.obs.writeBits(0, 5); // write length-3 (5 bits max)
         this.obs.writeBits(0, 3);
         this.obs.close();
      }
      catch (BitStreamException e)
      {
         throw new kanzi.io.IOException(e.getMessage(), e.getErrorCode());
      }

      this.listeners.clear();
      this.bufferThreshold = 0;

      // Release resources, force error on any subsequent write attempt
      for (int i=0; i<this.buffers.length; i++)
         this.buffers[i] = new SliceByteArray(EMPTY_BYTE_ARRAY, 0);
   }


   private void processBlock() throws IOException
   {
      if ((this.headless == false) && (this.initialized.getAndSet(true) == false))
         this.writeHeader();

      if (this.buffers[0].index == 0)
         return;

      try
      {
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
         else
         {
            jobsPerTask = new int[] { this.jobs };
         }

         List<Callable<Status>> tasks = new ArrayList<>(this.jobs);
         int firstBlockId = this.blockId.get();

         // Create as many tasks as non-empty buffers to encode
         for (int taskId=0; taskId<nbTasks; taskId++)
         {
            final int dataLength = this.buffers[taskId].index;

            if (dataLength == 0)
               break;

            Map<String, Object> map = new HashMap<>(this.ctx);
            map.put("jobs", jobsPerTask[taskId]);
            this.buffers[taskId].index = 0;

            Callable<Status> task = new EncodingTask(this.buffers[taskId],
                    this.buffers[this.jobs+taskId], dataLength, this.transformType,
                    this.entropyType, firstBlockId+taskId+1,
                    this.obs, this.hasher, this.blockId,
                    blockListeners, map);
            tasks.add(task);
         }

         if (tasks.size() == 1)
         {
            // Synchronous call
            Status status = tasks.get(0).call();

            if (status.error != 0)
               throw new kanzi.io.IOException(status.msg, status.error);
         }
         else
         {
            // Invoke the tasks concurrently and validate the results
            for (Future<Status> result : this.pool.invokeAll(tasks))
            {
               // Wait for completion of next task and validate result
               Status status = result.get();

               if (status.error != 0)
                  throw new kanzi.io.IOException(status.msg, status.error);
            }
         }

         this.bufferId = 0;
      }
      catch (kanzi.io.IOException e)
      {
         throw e;
      }
      catch (Exception e)
      {
         if (e instanceof InterruptedException)
            Thread.currentThread().interrupt();

         int errorCode = (e instanceof BitStreamException) ? ((BitStreamException) e).getErrorCode() :
                 Error.ERR_UNKNOWN;
         throw new kanzi.io.IOException(e.getMessage(), errorCode);
      }
   }


   // Return the number of bytes written so far
   public long getWritten()
   {
      return (this.obs.written() + 7) >> 3;
   }


   static void notifyListeners(Listener[] listeners, Event evt)
   {
      for (Listener bl : listeners)
      {
         try
         {
            bl.processEvent(evt);
         }
         catch (Exception e)
         {
            // Ignore exceptions in block listeners
         }
      }
   }


   // A task used to encode a block
   // Several tasks (transform+entropy) may run in parallel
   static class EncodingTask implements Callable<Status>
   {
      private final SliceByteArray data;
      private final SliceByteArray buffer;
      private final int length;
      private final long transformType;
      private final int entropyType;
      private final int blockId;
      private final OutputBitStream obs;
      private final XXHash32 hasher;
      private final AtomicInteger processedBlockId;
      private final Listener[] listeners;
      private final Map<String, Object> ctx;


      EncodingTask(SliceByteArray iBuffer, SliceByteArray oBuffer, int length,
              long transformType, int entropyType, int blockId,
              OutputBitStream obs, XXHash32 hasher,
              AtomicInteger processedBlockId, Listener[] listeners,
              Map<String, Object> ctx)
      {
         this.data = iBuffer;
         this.buffer = oBuffer;
         this.length = length;
         this.transformType = transformType;
         this.entropyType = entropyType;
         this.blockId = blockId;
         this.obs = obs;
         this.hasher = hasher;
         this.processedBlockId = processedBlockId;
         this.listeners = listeners;
         this.ctx = ctx;
      }


      @Override
      public Status call() throws Exception
      {
         return this.encodeBlock(this.data, this.buffer, this.length,
                 this.transformType, this.entropyType, this.blockId);
      }


      // Encode mode + transformed entropy coded data
      // mode | 0b10000000 => copy block
      //      | 0b0yy00000 => size(size(block))-1
      //      | 0b000y0000 => 1 if more than 4 transforms
      //  case 4 transforms or less
      //      | 0b0000yyyy => transform sequence skip flags (1 means skip)
      //  case more than 4 transforms
      //      | 0b00000000
      //      then 0byyyyyyyy => transform sequence skip flags (1 means skip)
      private Status encodeBlock(SliceByteArray data, SliceByteArray buffer,
           int blockLength, long blockTransformType,
           int blockEntropyType, int currentBlockId)
      {
         EntropyEncoder ee = null;

         try
         {
            if (blockLength == 0)
            {
               this.processedBlockId.incrementAndGet();
               return new Status(currentBlockId, 0, "Success");
            }

            byte mode = 0;
            int postTransformLength;
            int checksum = 0;

            // Compute block checksum
            if (this.hasher != null)
               checksum = this.hasher.hash(data.array, data.index, blockLength);

            if (this.listeners.length > 0)
            {
               // Notify before transform
               Event evt = new Event(Event.Type.BEFORE_TRANSFORM, currentBlockId,
                       blockLength, checksum, this.hasher != null);

               notifyListeners(this.listeners, evt);
            }

            if (blockLength <= SMALL_BLOCK_SIZE)
            {
               blockTransformType = TransformFactory.NONE_TYPE;
               blockEntropyType = EntropyCodecFactory.NONE_TYPE;
               mode |= COPY_BLOCK_MASK;
            }
            else
            {
               boolean skipBlockOpt = (Boolean) this.ctx.getOrDefault("skipBlocks", false);

               if (skipBlockOpt == true)
               {
                  boolean skipBlock = Magic.isCompressed(Magic.getType(data.array, data.index));
				
                  if (skipBlock == false)
                  {
                     int[] histo = new int[256];
                     Global.computeHistogramOrder0(data.array, data.index, data.index+blockLength, histo, false);
                     final int entropy = Global.computeFirstOrderEntropy1024(blockLength, histo);
                     skipBlock = entropy >= EntropyUtils.INCOMPRESSIBLE_THRESHOLD;
                     //this.ctx.put("histo0", histo);
                  }

                  if (skipBlock == true)
                  {
                     blockTransformType = TransformFactory.NONE_TYPE;
                     blockEntropyType = EntropyCodecFactory.NONE_TYPE;
                     mode |= COPY_BLOCK_MASK;
                  }
               }
            }

            this.ctx.put("size", blockLength);
            Sequence transform = new TransformFactory().newFunction(this.ctx, blockTransformType);
            int requiredSize = transform.getMaxEncodedLength(blockLength);

            if (blockLength >= 4)
            {
               final int magic = Magic.getType(data.array, 0);

               if (Magic.isCompressed(magic) == true)
                  this.ctx.put("dataType", Global.DataType.BIN);
               else if (Magic.isMultimedia(magic) == true)
                  this.ctx.put("dataType", Global.DataType.MULTIMEDIA);
               else if (Magic.isExecutable(magic) == true)
                  this.ctx.put("dataType", Global.DataType.EXE);
            }

            if (buffer.length < requiredSize)
            {
               buffer.length = requiredSize;

               if (buffer.array.length < buffer.length)
                  buffer.array = new byte[buffer.length];
            }

            // Forward transform (ignore error, encode skipFlags)
            buffer.index = 0;
            data.length = blockLength;
            transform.forward(data, buffer);
            postTransformLength = buffer.index;

            if (postTransformLength < 0)
            {
               this.processedBlockId.set(CANCEL_TASKS_ID);
               return new Status(currentBlockId, Error.ERR_WRITE_FILE, "Invalid transform size");
            }

            this.ctx.put("size", postTransformLength);
            final int dataSize = (postTransformLength < 256) ? 1 : (Global.log2(postTransformLength)>>3) + 1;

            if (dataSize > 4)
            {
               this.processedBlockId.set(CANCEL_TASKS_ID);
               return new Status(currentBlockId, Error.ERR_WRITE_FILE, "Invalid block data length");
            }

            // Record size of 'block size' - 1 in bytes
            mode |= (((dataSize-1) & 0x03) << 5);

            if (this.listeners.length > 0)
            {
               // Notify after transform
               Event evt = new Event(Event.Type.AFTER_TRANSFORM, currentBlockId,
                       postTransformLength, checksum, this.hasher != null);

               notifyListeners(this.listeners, evt);
            }

            final int bufSize = Math.max(512*1024, Math.max(postTransformLength, blockLength+(blockLength>>3)));

            if (data.length < bufSize)
            {
               // Rare case where the transform expanded the input or entropy coder
               // may expand size
               data.length = bufSize;

               if (data.array.length < data.length)
                  data.array = new byte[data.length];
            }

            this.data.index = 0;
            CustomByteArrayOutputStream baos = new CustomByteArrayOutputStream(this.data.array, this.data.length);
            DefaultOutputBitStream os = new DefaultOutputBitStream(baos, 16384);

            if (((mode & COPY_BLOCK_MASK) != 0) || (transform.getNbFunctions() <= 4))
            {
               mode |= ((transform.getSkipFlags()&0xFF)>>>4);
               os.writeBits(mode, 8);
            }
            else
            {
               mode |= TRANSFORMS_MASK;
               os.writeBits(mode, 8);
               os.writeBits(transform.getSkipFlags()&0xFF, 8);
            }

            os.writeBits(postTransformLength, 8*dataSize);

            // Write checksum
            if (this.hasher != null)
               os.writeBits(checksum, 32);

            if (this.listeners.length > 0)
            {
               // Notify before entropy
               Event evt = new Event(Event.Type.BEFORE_ENTROPY, currentBlockId,
                       postTransformLength, checksum, this.hasher != null);

               notifyListeners(this.listeners, evt);
            }

            // Each block is encoded separately
            // Rebuild the entropy encoder to reset block statistics
            ee = EntropyCodecFactory.newEncoder(os, this.ctx, blockEntropyType);

            // Entropy encode block
            if (ee.encode(buffer.array, 0, postTransformLength) != postTransformLength)
            {
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
            while (true)
            {
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

            if (this.listeners.length > 0)
            {
               // Notify after entropy
               Event evt = new Event(Event.Type.AFTER_ENTROPY,
                       currentBlockId, (written+7) >> 3, checksum, this.hasher != null);

               notifyListeners(this.listeners, evt);
            }

            // Emit block size in bits (max size pre-entropy is 1 GB = 1 << 30 bytes)
            final int lw = (written < 8) ? 3 : Global.log2((int) (written >> 3)) + 4;
            this.obs.writeBits(lw-3, 5); // write length-3 (5 bits max)
            this.obs.writeBits(written, lw);
            int chkSize = (int) Math.min(written, 1<<30);

            // Emit data to shared bitstream
            for (int n=0; written>0; )
            {
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
         catch (Exception e)
         {
            this.processedBlockId.set(CANCEL_TASKS_ID);
            return new Status(currentBlockId, Error.ERR_PROCESS_BLOCK,
               "Error in block "+currentBlockId+": "+e.getMessage());
         }
         finally
         {
            // Make sure to unfreeze next block
            if (this.processedBlockId.get() == this.blockId-1)
               this.processedBlockId.incrementAndGet();

            if (ee != null)
              ee.dispose();
         }
      }
   }


   static class Status
   {
      final int blockId;
      final int error; // 0 = OK
      final String msg;

      Status(int blockId, int error, String msg)
      {
         this.blockId = blockId;
         this.error = error;
         this.msg = msg;
      }
   }




   static class CustomByteArrayOutputStream extends ByteArrayOutputStream
   {
      public CustomByteArrayOutputStream(byte[] buffer, int size)
      {
         super(size);

         if (buffer.length < size)
            throw new IllegalArgumentException("Invalid buffer length: " + buffer.length);

         this.buf = buffer;
      }
   }
}
