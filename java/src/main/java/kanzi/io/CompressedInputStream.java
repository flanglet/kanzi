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

import java.io.ByteArrayInputStream;
import kanzi.transform.TransformFactory;
import kanzi.Error;
import kanzi.Event;
import java.io.IOException;
import java.io.InputStream;
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
import kanzi.EntropyDecoder;
import kanzi.Global;
import kanzi.SliceByteArray;
import kanzi.InputBitStream;
import kanzi.bitstream.DefaultInputBitStream;
import kanzi.entropy.EntropyCodecFactory;
import kanzi.transform.Sequence;
import kanzi.util.hash.XXHash32;
import kanzi.Listener;


// Implementation of a java.io.InputStream that can decompress a stream
// compressed with CompressedOutputStream
public class CompressedInputStream extends InputStream
{
   private static final int BITSTREAM_TYPE           = 0x4B414E5A; // "KANZ"
   private static final int BITSTREAM_FORMAT_VERSION = 6;
   private static final int DEFAULT_BUFFER_SIZE      = 256*1024;
   private static final int EXTRA_BUFFER_SIZE        = 512;
   private static final int COPY_BLOCK_MASK          = 0x80;
   private static final int TRANSFORMS_MASK          = 0x10;
   private static final int MIN_BITSTREAM_BLOCK_SIZE = 1024;
   private static final int MAX_BITSTREAM_BLOCK_SIZE = 1024*1024*1024;
   private static final byte[] EMPTY_BYTE_ARRAY      = new byte[0];
   private static final int CANCEL_TASKS_ID          = -1;
   private static final int MAX_CONCURRENCY          = 64;
   private static final int MAX_BLOCK_ID             = Integer.MAX_VALUE;

   private int blockSize;
   private int bufferId; // index of current read buffer
   private int maxBufferId; // max index of read buffer
   private int nbInputBlocks;
   private int jobs;
   private int bufferThreshold;
   private int available; // decoded not consumed bytes
   private XXHash32 hasher;
   private final SliceByteArray[] buffers; // input & output per block
   private int entropyType;
   private long transformType;
   private long outputSize;
   private final InputBitStream ibs;
   private final AtomicBoolean initialized;
   private final AtomicBoolean closed;
   private final AtomicInteger blockId;
   private final ExecutorService pool;
   private final List<Listener> listeners;
   private final Map<String, Object> ctx;
   private final boolean headless;


   public CompressedInputStream(InputStream is, Map<String, Object> ctx)
   {
      this(new DefaultInputBitStream(is, DEFAULT_BUFFER_SIZE), ctx);
   }


   // Allow caller to provide custom input bitstream
   public CompressedInputStream(InputBitStream ibs, Map<String, Object> ctx)
   {
      if (ibs == null)
         throw new NullPointerException("Invalid null input bitstream parameter");

      if (ctx == null)
         throw new NullPointerException("Invalid null context parameter");

      final int tasks = (Integer) ctx.getOrDefault("jobs", 1);

      if ((tasks <= 0) || (tasks > MAX_CONCURRENCY))
         throw new IllegalArgumentException("The number of jobs must be in [1.." + MAX_CONCURRENCY+ "]");

      ExecutorService threadPool = (ExecutorService) ctx.get("pool");

      if ((tasks > 1) && (threadPool == null))
         throw new IllegalArgumentException("The thread pool cannot be null when the number of jobs is "+tasks);

      this.ibs = ibs;
      this.jobs = tasks;
      this.pool = threadPool;
      this.buffers = new SliceByteArray[2*this.jobs];
      this.closed = new AtomicBoolean(false);
      this.initialized = new AtomicBoolean(false);

      for (int i=0; i<this.buffers.length; i++)
         this.buffers[i] = new SliceByteArray(EMPTY_BYTE_ARRAY, 0);

      this.blockId = new AtomicInteger(0);
      this.listeners = new ArrayList<>(10);
      this.ctx = ctx;
      this.blockSize = 0;
      this.outputSize = 0;
      this.nbInputBlocks = 0;
      this.bufferThreshold = 0;
      this.entropyType = EntropyCodecFactory.NONE_TYPE;
      this.transformType = TransformFactory.NONE_TYPE;
      this.headless = (Boolean) ctx.getOrDefault("headerless", false);

      if (this.headless == true)
      {
         // Validation of required values
         int bsVersion = (Integer) ctx.getOrDefault("bsVersion", BITSTREAM_FORMAT_VERSION);

         if (bsVersion != BITSTREAM_FORMAT_VERSION)
             throw new IllegalArgumentException("Invalid or missing bitstream version, " +
                                                "cannot read this version of the stream: " + bsVersion);

         this.ctx.put("bsVersion", BITSTREAM_FORMAT_VERSION);
         String entropy = (String) ctx.getOrDefault("entropy", "");
         this.entropyType = EntropyCodecFactory.getType(entropy); // throws on error

         String transform = (String) ctx.getOrDefault("transform", "");
         this.transformType = new TransformFactory().getType(transform); // throws on error

         this.blockSize = (Integer) ctx.getOrDefault("blockSize", 0);

         if ((this.blockSize < MIN_BITSTREAM_BLOCK_SIZE) || (this.blockSize > MAX_BITSTREAM_BLOCK_SIZE))
             throw new IllegalArgumentException("Invalid or missing block size: " + this.blockSize);

         this.bufferThreshold = this.blockSize;
         boolean cksum = (Boolean) ctx.getOrDefault("checksum", false);

         if (cksum == true)
             this.hasher = new XXHash32(BITSTREAM_TYPE);

         long oSize = (Long) ctx.getOrDefault("outputSize", 0L);

         if (oSize != 0)
         {
             this.outputSize = ((oSize < 0) || (oSize >= (1L<<48))) ? 0 : oSize;
             int nbBlocks = (int) ((this.outputSize + this.blockSize - 1) / (long) this.blockSize);
             this.nbInputBlocks = Math.min(nbBlocks, MAX_CONCURRENCY-1);
         }
      }
   }


   protected void readHeader() throws IOException
   {
      // Read stream type
      final int type = (int) this.ibs.readBits(32);

      // Sanity check
      if (type != BITSTREAM_TYPE)
         throw new kanzi.io.IOException("Invalid stream type", Error.ERR_INVALID_FILE);

      // Read stream version
      final int bsVersion = (int) this.ibs.readBits(4);

      // Sanity check
      if (bsVersion > BITSTREAM_FORMAT_VERSION)
         throw new kanzi.io.IOException("Invalid bitstream, cannot read this version of the stream: " + bsVersion,
                 Error.ERR_STREAM_VERSION);

      this.ctx.put("bsVersion", bsVersion);

      // Read block checksum
      if (this.ibs.readBit() == 1)
         this.hasher = new XXHash32(BITSTREAM_TYPE);

      // Read entropy codec
      try
      {
         this.entropyType = (int) this.ibs.readBits(5);
         this.ctx.put("entropy", EntropyCodecFactory.getName(this.entropyType));
      }
      catch (IllegalArgumentException e)
      {
         throw new kanzi.io.IOException("Invalid bitstream, unknown entropy codec type: "+
                 this.entropyType , Error.ERR_INVALID_CODEC);
      }

      try
      {
         // Read transforms: 8*6 bits
         this.transformType = this.ibs.readBits(48);
         this.ctx.put("transform", new TransformFactory().getName(this.transformType));
      }
      catch (IllegalArgumentException e)
      {
         throw new kanzi.io.IOException("Invalid bitstream, unknown transform type: "+
                 this.transformType, Error.ERR_INVALID_CODEC);
      }

      // Read block size
      this.blockSize = (int) this.ibs.readBits(28) << 4;

      if ((this.blockSize < MIN_BITSTREAM_BLOCK_SIZE) || (this.blockSize > MAX_BITSTREAM_BLOCK_SIZE))
         throw new kanzi.io.IOException("Invalid bitstream, incorrect block size: " + this.blockSize,
                 Error.ERR_BLOCK_SIZE);

      this.ctx.put("blockSize", this.blockSize);
      this.bufferThreshold = this.blockSize;
      int szMask = 0;

      if (bsVersion >= 5)
      {
         // Read original size
         // 0 -> not provided, <2^16 -> 1, <2^32 -> 2, <2^48 -> 3
         szMask = (int) this.ibs.readBits(2);

         if (szMask != 0)
         {
            this.outputSize = this.ibs.readBits(16*szMask);
            this.ctx.put("outputSize", this.outputSize);
            final int nbBlocks = (int) ((this.outputSize + this.blockSize - 1) / this.blockSize);
            this.nbInputBlocks = Math.min(nbBlocks, MAX_CONCURRENCY-1);
         }

         // Read and verify checksum
         int crcSize = 24;
         int seed = 0x01030507 * bsVersion;

         if (bsVersion == 5)
         {
             crcSize = 16;
             seed = (int) bsVersion;
         }

         final int cksum1 = (int) this.ibs.readBits(crcSize);
         final int HASH = 0x1E35A7BD;
         int cksum2 = HASH * seed;
         cksum2 ^= (HASH * ~this.entropyType);
         cksum2 ^= (HASH * (int) (~this.transformType >>> 32));
         cksum2 ^= (HASH * (int)  ~this.transformType);
         cksum2 ^= (HASH * ~this.blockSize);

         if (szMask > 0)
         {
            cksum2 ^= (HASH * (int) (~this.outputSize >>> 32));
            cksum2 ^= (HASH * (int)  ~this.outputSize);
         }

         cksum2 = (cksum2 >>> 23) ^ (cksum2 >>> 3);

         if (cksum1 != (cksum2 & ((1 << crcSize) - 1)))
            throw new kanzi.io.IOException("Invalid bitstream, corrupted header", Error.ERR_CRC_CHECK);
      }
      else if (bsVersion >= 3)
      {
         final int nbBlocks = (int) this.ibs.readBits(6);
         this.nbInputBlocks = (nbBlocks == 0) ? 65536 : nbBlocks;

         // Read and verify checksum from bitstream version 3
         final int cksum1 = (int) this.ibs.readBits(4);
         final int HASH = 0x1E35A7BD;
         int cksum2 = HASH * bsVersion;
         cksum2 ^= (HASH * this.entropyType);
         cksum2 ^= (HASH * (int) (this.transformType >>> 32));
         cksum2 ^= (HASH * (int)  this.transformType);
         cksum2 ^= (HASH * this.blockSize);
         cksum2 ^= (HASH * this.nbInputBlocks);
         cksum2 = (cksum2 >>> 23) ^ (cksum2 >>> 3);

         if (cksum1 != (cksum2&0x0F))
            throw new kanzi.io.IOException("Invalid bitstream, corrupted header", Error.ERR_CRC_CHECK);
      }
      else
      {
         // Header prior to version 3
         this.nbInputBlocks = (int) this.ibs.readBits(6);
         this.ibs.readBits(4); // reserved
      }

      if (this.listeners.isEmpty() == false)
      {
         StringBuilder sb = new StringBuilder(200);
         sb.append("Bitstream version: ").append(bsVersion).append("\n");
         sb.append("Checksum: ").append(this.hasher != null).append("\n");
         sb.append("Block size: ").append(this.blockSize).append(" bytes\n");
         String w1 = EntropyCodecFactory.getName(this.entropyType);

         if ("NONE".equals(w1))
            w1 = "no";

         sb.append("Using ").append(w1).append(" entropy codec (stage 1)\n");
         String w2 = new TransformFactory().getName(this.transformType);

         if ("NONE".equals(w2))
            w2 = "no";

         sb.append("Using ").append(w2).append(" transform (stage 2)\n");

         if (szMask != 0)
            sb.append("Original size: ").append(this.outputSize).append(" byte(s)\n");

         // Protect against future concurrent modification of the block listeners list
         Listener[] blockListeners = this.listeners.toArray(new Listener[this.listeners.size()]);
         Event evt = new Event(Event.Type.AFTER_HEADER_DECODING, 0, sb.toString());
         notifyListeners(blockListeners, evt);
      }
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
    * Reads the next byte of data from the input stream. The value byte is
    * returned as an <code>int</code> in the range <code>0</code> to
    * <code>255</code>. If no byte is available because the end of the stream
    * has been reached, the value <code>-1</code> is returned. This method
    * blocks until input data is available, the end of the stream is detected,
    * or an exception is thrown.
    *
    * @return     the next byte of data, or <code>-1</code> if the end of the
    *             stream is reached.
    * @exception  IOException  if an I/O error occurs.
    */
   @Override
   public int read() throws IOException
   {
      try
      {
         if (this.available == 0)
         {
            if (this.closed.get() == true)
               throw new kanzi.io.IOException("Stream closed", Error.ERR_WRITE_FILE);

            this.available = this.processBlock();

            if (this.available == 0) // Reached end of stream
               return -1;
         }

         int res = this.buffers[this.bufferId].array[this.buffers[this.bufferId].index++] & 0xFF;
         this.available--;

         // Is current read buffer empty ?
         if ((this.bufferId < this.maxBufferId) && (this.buffers[this.bufferId].index >= this.bufferThreshold))
            this.bufferId++;

         return res;
      }
      catch (kanzi.io.IOException e)
      {
         throw e;
      }
      catch (BitStreamException e)
      {
         throw new kanzi.io.IOException(e.getMessage(), Error.ERR_READ_FILE);
      }
      catch (Exception e)
      {
         throw new kanzi.io.IOException(e.getMessage(), Error.ERR_UNKNOWN);
      }
   }


    /**
     * Reads some number of bytes from the input stream and stores them into
     * the buffer array <code>array</code>. The number of bytes actually read is
     * returned as an integer.  This method blocks until input data is
     * available, end of file is detected, or an exception is thrown.
     *
     * <p> If the length of <code>array</code> is zero, then no bytes are read and
     * <code>0</code> is returned; otherwise, there is an attempt to read at
     * least one byte. If no byte is available because the stream is at the
     * end of the file, the value <code>-1</code> is returned; otherwise, at
     * least one byte is read and stored into <code>array</code>.
     *
     * <p> The first byte read is stored into element <code>array[0]</code>, the
     * next one into <code>array[1]</code>, and so on. The number of bytes read is,
     * at most, equal to the length of <code>array</code>. Let <i>k</i> be the
     * number of bytes actually read; these bytes will be stored in elements
     * <code>array[0]</code> through <code>array[</code><i>k</i><code>-1]</code>,
     * leaving elements <code>array[</code><i>k</i><code>]</code> through
     * <code>array[array.length-1]</code> unaffected.
     *
     * <p> The <code>read(array)</code> method for class <code>InputStream</code>
     * has the same effect as: <pre><code> read(b, 0, array.length) </code></pre>
     *
     * @param      data   the buffer into which the data is read.
     * @return     the total number of bytes read into the buffer, or
     *             <code>-1</code> if there is no more data because the end of
     *             the stream has been reached.
     * @exception  IOException  If the first byte cannot be read for any reason
     * other than the end of the file, if the input stream has been closed, or
     * if some other I/O error occurs.
     * @exception  NullPointerException  if <code>array</code> is <code>null</code>.
     * @see        java.io.InputStream#read(byte[], int, int)
     */
   @Override
   public int read(byte[] data, int off, int len) throws IOException
   {
      if ((off < 0) || (len < 0) || (len + off > data.length))
         throw new IndexOutOfBoundsException();

      int remaining = len;

      while (remaining > 0)
      {
         // Limit to number of available bytes in buffer
         final int lenChunk = Math.min(remaining, Math.min(this.available, this.bufferThreshold-this.buffers[this.bufferId].index));

         if (lenChunk > 0)
         {
            // Process a chunk of in-buffer data. No access to bitstream required
            System.arraycopy(this.buffers[this.bufferId].array, this.buffers[this.bufferId].index,
                  data, off, lenChunk);
            this.buffers[this.bufferId].index += lenChunk;
            off += lenChunk;
            this.available -= lenChunk;
            remaining -= lenChunk;

            if ((this.bufferId < this.maxBufferId) && (this.buffers[this.bufferId].index >= this.bufferThreshold))
            {
               if (this.bufferId+1 >= this.jobs)
                  break;

               this.bufferId++;
            }

            if (remaining == 0)
               break;
         }

         // Buffer empty, time to decode
         int c2 = this.read();

         // EOF ?
         if (c2 == -1)
            break;

         data[off++] = (byte) c2;
         remaining--;
      }

      return len - remaining;
   }


   private int processBlock() throws IOException
   {
      if ((this.headless == false) && (this.initialized.getAndSet(true)== false))
         this.readHeader();

      try
      {
         // Add a padding area to manage any block with header or temporarily expanded
         final int blkSize = Math.max(this.blockSize+EXTRA_BUFFER_SIZE, this.blockSize+(this.blockSize>>4));

         // Protect against future concurrent modification of the list of block listeners
         Listener[] blockListeners = this.listeners.toArray(new Listener[this.listeners.size()]);
         int decoded = 0;

         while (true)
         {
            List<Callable<Status>> tasks = new ArrayList<>(this.jobs);
            final int firstBlockId = this.blockId.get();
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

            final int bufSize = Math.max(this.blockSize+EXTRA_BUFFER_SIZE,
                                         this.blockSize+(this.blockSize>>4));

            // Create as many tasks as empty buffers to decode
            for (int taskId=0; taskId<nbTasks; taskId++)
            {
               if (this.buffers[taskId].array.length < bufSize)
               {
                  this.buffers[taskId].array = new byte[bufSize];
                  this.buffers[taskId].length = bufSize;
               }

               Map<String, Object> map = new HashMap<>(this.ctx);
               map.put("jobs", jobsPerTask[taskId]);
               this.buffers[taskId].index = 0;
               this.buffers[this.jobs+taskId].index = 0;
               Callable<Status> task = new DecodingTask(this.buffers[taskId],
                       this.buffers[this.jobs+taskId], blkSize, this.transformType,
                       this.entropyType, firstBlockId+taskId+1,
                       this.ibs, this.hasher, this.blockId,
                       blockListeners, map);
               tasks.add(task);
            }

            List<Status> results = new ArrayList<>(tasks.size());
            int skipped = 0;
            this.maxBufferId = nbTasks - 1;

            if (nbTasks == 1)
            {
               // Synchronous call
               Status status = tasks.get(0).call();
               results.add(status);

               if (status.skipped == true)
                  skipped++;

               decoded += status.decoded;

               if (status.error != 0)
                  throw new kanzi.io.IOException(status.msg, status.error);

               if (status.decoded > this.blockSize)
                  throw new kanzi.io.IOException("Invalid data", Error.ERR_PROCESS_BLOCK);
            }
            else
            {
               // Invoke the tasks concurrently and wait for the results
               for (Future<Status> result : this.pool.invokeAll(tasks))
               {
                  Status status = result.get();
                  results.add(status);

                  if (status.skipped == true)
                     skipped++;

                  decoded += status.decoded;

                  if (status.error != 0)
                     throw new kanzi.io.IOException(status.msg, status.error);

                  if (status.decoded > this.blockSize)
                     throw new kanzi.io.IOException("Invalid data", Error.ERR_PROCESS_BLOCK);
               }
            }

            int n = 0;

            for (Status res : results)
            {
               System.arraycopy(res.data, 0, this.buffers[n].array, 0, res.decoded);
               this.buffers[n].index = 0;
               n++;

               if (blockListeners.length > 0)
               {
                  // Notify after transform ... in block order !
                  Event evt = new Event(Event.Type.AFTER_TRANSFORM, res.blockId,
                          res.decoded, res.checksum, this.hasher != null, res.completionTime);

                  notifyListeners(blockListeners, evt);
               }
            }

            // Unless all blocks were skipped, exit the loop (usual case)
            if (skipped != results.size())
               break;
         }

         this.bufferId = 0;
         return decoded;
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


   /**
    * Closes this input stream and releases any system resources associated
    * with the stream.
    *
    * @exception  IOException  if an I/O error occurs.
    */
   @Override
   public void close() throws IOException
   {
      if (this.closed.getAndSet(true) == true)
         return;

      try
      {
         this.ibs.close();
      }
      catch (BitStreamException e)
      {
         throw new kanzi.io.IOException(e.getMessage(), e.getErrorCode());
      }

      // Release resources, force error on any subsequent write attempt
      this.available = 0;
      this.bufferThreshold = 0;

      for (int i=0; i<this.buffers.length; i++)
         this.buffers[i] = new SliceByteArray(EMPTY_BYTE_ARRAY, 0);
   }


   // Return the number of bytes read so far
   public long getRead()
   {
      return (this.ibs.read() + 7) >> 3;
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


   // A task used to decode a block
   // Several tasks (transform+entropy) may run in parallel
   static class DecodingTask implements Callable<Status>
   {
      private final SliceByteArray data;
      private final SliceByteArray buffer;
      private final int blockSize;
      private final long transformType;
      private final int entropyType;
      private final int blockId;
      private final InputBitStream ibs;
      private final XXHash32 hasher;
      private final AtomicInteger processedBlockId;
      private final Listener[] listeners;
      private final Map<String, Object> ctx;


      DecodingTask(SliceByteArray iBuffer, SliceByteArray oBuffer, int blockSize,
              long transformType, int entropyType, int blockId,
              InputBitStream ibs, XXHash32 hasher,
              AtomicInteger processedBlockId, Listener[] listeners,
              Map<String, Object> ctx)
      {
         this.data = iBuffer;
         this.buffer = oBuffer;
         this.blockSize = blockSize;
         this.transformType = transformType;
         this.entropyType = entropyType;
         this.blockId = blockId;
         this.ibs = ibs;
         this.hasher = hasher;
         this.processedBlockId = processedBlockId;
         this.listeners = listeners;
         this.ctx = ctx;
      }


      @Override
      public Status call() throws Exception
      {
         return this.decodeBlock(this.data, this.buffer,
                 this.transformType, this.entropyType, this.blockId);
      }


      // Decode mode + transformed entropy coded data
      // mode | 0b10000000 => copy block
      //      | 0b0yy00000 => size(size(block))-1
      //      | 0b000y0000 => 1 if more than 4 transforms
      //  case 4 transforms or less
      //      | 0b0000yyyy => transform sequence skip flags (1 means skip)
      //  case more than 4 transforms
      //      | 0b00000000
      //      then 0byyyyyyyy => transform sequence skip flags (1 means skip)
      private Status decodeBlock(SliceByteArray data, SliceByteArray buffer,
         long blockTransformType, int blockEntropyType, int currentBlockId)
      {
         // Lock free synchronization
         while (true)
         {
            final int taskId = this.processedBlockId.get();

            if (taskId == CANCEL_TASKS_ID)
               return new Status(data, currentBlockId, 0, 0, 0, "Canceled");

            if (taskId == currentBlockId-1)
               break;

            // Wait for the concurrent task processing the previous block to complete
            // entropy encoding. Entropy encoding must happen sequentially (and
            // in the correct block order) in the bitstream.
            // Backoff improves performance in heavy contention scenarios
            Thread.onSpinWait(); // Use Thread.yield() with JDK 8 and below
         }

         // Read shared bitstream sequentially (each task is gated by _processedBlockId)
         final int lr = (int) this.ibs.readBits(5) + 3;
         long read = this.ibs.readBits(lr);

         if (read == 0)
         {
            this.processedBlockId.set(CANCEL_TASKS_ID);
            return new Status(data, currentBlockId, 0, 0, 0, "Success");
         }

         if (read > 1L<<34)
         {
            this.processedBlockId.set(CANCEL_TASKS_ID);
            return new Status(data, currentBlockId, 0, 0, Error.ERR_BLOCK_SIZE, "Invalid block size");
         }

         final int r = (int) ((read + 7) >> 3);

         if (data.array.length < Math.max(this.blockSize, r))
            data.array = new byte[Math.max(this.blockSize, r)];

         for (int n=0; read>0; )
         {
            final int chkSize = (read < (1L<<30)) ? (int) read : 1<<30;
            this.ibs.readBits(data.array, n, chkSize);
            n += ((chkSize+7) >> 3);
            read -= chkSize;
         }

         // After completion of the bitstream reading, increment the block id.
         // It unblocks the task processing the next block (if any)
         this.processedBlockId.incrementAndGet();

         // Check if the block must be skipped
         int from = (int) this.ctx.getOrDefault("from", 0);
         int to = (int) this.ctx.getOrDefault("to", MAX_BLOCK_ID);

         if ((this.blockId < from) || (this.blockId >= to))
            return new Status(data, currentBlockId, 0, 0, 0, "Success", true);

         ByteArrayInputStream bais = new ByteArrayInputStream(data.array, 0, r);
         DefaultInputBitStream is = new DefaultInputBitStream(bais, 16384);
         int checksum1 = 0;
         EntropyDecoder ed = null;

         try
         {
            // Extract block header directly from bitstream
            byte mode = (byte) is.readBits(8);
            byte skipFlags = 0;

            if ((mode & COPY_BLOCK_MASK) != 0)
            {
               blockTransformType = TransformFactory.NONE_TYPE;
               blockEntropyType = EntropyCodecFactory.NONE_TYPE;
            }
            else
            {
               if ((mode & TRANSFORMS_MASK) != 0)
                  skipFlags = (byte) is.readBits(8);
               else
                  skipFlags = (byte) ((mode<<4) | 0x0F);
            }

            final int dataSize = 1 + ((mode>>5) & 0x03);
            final int length = dataSize << 3;
            final long mask = (1L<<length) - 1;
            int preTransformLength = (int) (is.readBits(length) & mask);

            if (preTransformLength == 0)
            {
               // Last block is empty, return success and cancel pending tasks
               this.processedBlockId.set(CANCEL_TASKS_ID);
               return new Status(data, currentBlockId, 0, checksum1, 0, null);
            }

            if ((preTransformLength < 0) || (preTransformLength > MAX_BITSTREAM_BLOCK_SIZE))
            {
               // Error => cancel concurrent decoding tasks
               this.processedBlockId.set(CANCEL_TASKS_ID);
               return new Status(data, currentBlockId, 0, checksum1, Error.ERR_READ_FILE,
                    "Invalid compressed block length: " + preTransformLength);
            }

            // Extract checksum from bit stream (if any)
            if (this.hasher != null)
               checksum1 = (int) is.readBits(32);

            if (this.listeners.length > 0)
            {
               // Notify before entropy (block size in bitstream is unknown)
               Event evt = new Event(Event.Type.BEFORE_ENTROPY, currentBlockId,
                       -1, checksum1, this.hasher != null);

               notifyListeners(this.listeners, evt);
            }

            final int bufferSize = Math.max(this.blockSize, preTransformLength+EXTRA_BUFFER_SIZE);

            if (buffer.length < bufferSize)
            {
               buffer.length = bufferSize;

               if (buffer.array.length < buffer.length)
                  buffer.array = new byte[buffer.length];
            }

            final int savedIdx = data.index;
            this.ctx.put("size", preTransformLength);

            // Each block is decoded separately
            // Rebuild the entropy decoder to reset block statistics
            ed = EntropyCodecFactory.newDecoder(is, this.ctx, blockEntropyType);

            // Block entropy decode
            if (ed.decode(buffer.array, 0, preTransformLength) != preTransformLength)
            {
               // Error => cancel concurrent decoding tasks
               this.processedBlockId.set(CANCEL_TASKS_ID);
               return new Status(data, currentBlockId, 0, checksum1, Error.ERR_PROCESS_BLOCK,
                  "Entropy decoding failed");
            }

            is.close();
            ed.dispose();
            ed = null;

            if (this.listeners.length > 0)
            {
               // Notify after entropy (block size set to size in bitstream)
               Event evt = new Event(Event.Type.AFTER_ENTROPY, currentBlockId,
                       (int) (is.read()>>3), checksum1, this.hasher != null);

               notifyListeners(this.listeners, evt);
            }

            if (this.listeners.length > 0)
            {
               // Notify before transform (block size after entropy decoding)
               Event evt = new Event(Event.Type.BEFORE_TRANSFORM, currentBlockId,
                       preTransformLength, checksum1, this.hasher != null);

               notifyListeners(this.listeners, evt);
            }

            Sequence transform = new TransformFactory().newFunction(this.ctx,
                     blockTransformType);
            transform.setSkipFlags(skipFlags);
            buffer.index = 0;

            // Inverse transform
            buffer.length = preTransformLength;

            if (transform.inverse(buffer, data) == false)
               return new Status(data, currentBlockId, 0, checksum1, Error.ERR_PROCESS_BLOCK,
                  "Transform inverse failed");

            final int decoded = data.index - savedIdx;

            // Verify checksum
            if (this.hasher != null)
            {
               final int checksum2 = this.hasher.hash(data.array, savedIdx, decoded);

               if (checksum2 != checksum1)
                  return new Status(data, currentBlockId, decoded, checksum1, Error.ERR_CRC_CHECK,
                          "Corrupted bitstream: expected checksum " + Integer.toHexString(checksum1) +
                          ", found " + Integer.toHexString(checksum2));
            }

            return new Status(data, currentBlockId, decoded, checksum1, 0, null);
         }
         catch (Exception e)
         {
            this.processedBlockId.set(CANCEL_TASKS_ID);
            return new Status(data, currentBlockId, 0, checksum1, Error.ERR_PROCESS_BLOCK,
               "Error in block "+currentBlockId+": "+e.getMessage());
         }
         finally
         {
            // Make sure to unfreeze next block
            if (this.processedBlockId.get() == this.blockId-1)
               this.processedBlockId.incrementAndGet();

            if (ed != null)
               ed.dispose();
         }
      }
   }


   static class Status
   {
      final int blockId;
      final int decoded;
      final byte[] data;
      final boolean skipped;
      final int error; // 0 = OK
      final String msg;
      final int checksum;
      final long completionTime;

      Status(SliceByteArray data, int blockId, int decoded, int checksum, int error, String msg)
      {
         this(data, blockId, decoded, checksum, error, msg, false);
      }

      Status(SliceByteArray data, int blockId, int decoded, int checksum, int error, String msg, boolean skipped)
      {
         this.data = data.array;
         this.blockId = blockId;
         this.decoded = decoded;
         this.checksum = checksum;
         this.error = error;
         this.msg = msg;
         this.completionTime = System.nanoTime();
         this.skipped = skipped;
      }
   }
}
