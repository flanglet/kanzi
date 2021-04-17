/*
Copyright 2011-2021 Frederic Langlet
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

package kanzi.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import kanzi.ByteTransform;
import kanzi.Global;
import kanzi.SliceByteArray;


// The Burrows-Wheeler Transform is a reversible transform based on
// permutation of the data in the original message to reduce the entropy.

// The initial text can be found here:
// Burrows M and Wheeler D, [A block sorting lossless data compression algorithm]
// Technical Report 124, Digital Equipment Corporation, 1994

// See also Peter Fenwick, [Block sorting text compression - final report]
// Technical Report 130, 1996

// This implementation replaces the 'slow' sorting of permutation strings
// with the construction of a suffix array (faster but more complex).
// The suffix array contains the indexes of the sorted suffixes.
//
// E.G.    0123456789A
// Source: mississippi\0
// Suffixes:    rank  sorted
// mississippi\0  0  -> 4             i\0
//  ississippi\0  1  -> 3          ippi\0
//   ssissippi\0  2  -> 10      issippi\0
//    sissippi\0  3  -> 8    ississippi\0
//     issippi\0  4  -> 2   mississippi\0
//      ssippi\0  5  -> 9            pi\0
//       sippi\0  6  -> 7           ppi\0
//        ippi\0  7  -> 1         sippi\0
//         ppi\0  8  -> 6      sissippi\0
//          pi\0  9  -> 5        ssippi\0
//           i\0  10 -> 0     ssissippi\0
// Suffix array SA : 10 7 4 1 0 9 8 6 3 5 2
// BWT[i] = input[SA[i]-1] => BWT(input) = ipssmpissii (+ primary index 5)
// The suffix array and permutation vector are equal when the input is 0 terminated
// The insertion of a guard is done internally and is entirely transparent.
//
// This implementation extends the canonical algorithm to use up to MAX_CHUNKS primary
// indexes (based on input block size). Each primary index corresponds to a data chunk.
// Chunks may be inverted concurrently.

public class BWT implements ByteTransform
{
   private static final int MAX_BLOCK_SIZE = 1024*1024*1024; // 1 GB
   private static final int NB_FASTBITS = 17;
   private static final int MASK_FASTBITS = 1 << NB_FASTBITS;
   private static final int BLOCK_SIZE_THRESHOLD1 = 256;
   private static final int BLOCK_SIZE_THRESHOLD2 = 8 * 1024 * 1024;     


   private int[] buffer1;
   private short[] buffer2;
   private int[] buckets;
   private int[] freqs;
   private final int[] primaryIndexes;
   private DivSufSort saAlgo;
   private final ExecutorService pool;
   private final int jobs;


   // Static allocation of memory
   public BWT()
   {
      this.buffer1 = new int[0];
      this.buffer2 = new short[0];
      this.buckets = new int[256];
      this.freqs = new int[256];
      this.primaryIndexes = new int[8];
      this.pool = null;
      this.jobs = 1;
   }


   // Number of jobs provided in the context
   public BWT(Map<String, Object> ctx)
   {
      final int tasks = (Integer) ctx.get("jobs");

      if (tasks <= 0)
         throw new IllegalArgumentException("The number of jobs must be in positive");

      ExecutorService threadPool = (ExecutorService) ctx.get("pool");

      if ((tasks > 1) && (threadPool == null))
         throw new IllegalArgumentException("The thread pool cannot be null when the number of jobs is "+tasks);

      this.buffer1 = new int[0];
      this.buffer2 = new short[0];
      this.buckets = new int[256];
      this.freqs = new int[256];
      this.primaryIndexes = new int[8];
      this.pool = (tasks == 1) ? null : threadPool;
      this.jobs = tasks;
   }


   public int getPrimaryIndex(int n)
   {
      return this.primaryIndexes[n];
   }


   // Not thread safe
   public boolean setPrimaryIndex(int n, int primaryIndex)
   {
      if ((primaryIndex < 0) || (n < 0) || (n >= this.primaryIndexes.length))
         return false;

      this.primaryIndexes[n] = primaryIndex;
      return true;
   }


   // Not thread safe
   @Override
   public boolean forward(SliceByteArray src, SliceByteArray dst)
   {
      if (src.length == 0)
         return true;

      if (src.array == dst.array)
         return false;

      final int count = src.length;

      // Not a recoverable error: instead of silently fail the transform,
      // issue a fatal error.
      if (count > maxBlockSize())
         throw new IllegalArgumentException("The max BWT block size is "+maxBlockSize()+", got "+count);

      if (dst.index+count > dst.array.length)
         return false;

      final byte[] input = src.array;
      final byte[] output = dst.array;
      final int srcIdx = src.index;
      final int dstIdx = dst.index;

      if (count < 2)
      {
         if (count == 1)
            output[dst.index++] = input[src.index++];

         return true;
      }

      // Lazy dynamic memory allocation
      if (this.saAlgo == null)
         this.saAlgo = new DivSufSort();

      if (this.buffer1.length < count)
         this.buffer1 = new int[count];

      final int[] sa = this.buffer1;
      boolean res = true;
      int chunks = getBWTChunks(count);

      if (chunks == 1)
      {
         final int pIdx = this.saAlgo.computeBWT(input, output, sa, srcIdx, dstIdx, count);
         res = this.setPrimaryIndex(0, pIdx);
      }
      else
      {
         this.saAlgo.computeSuffixArray(input, sa, srcIdx, count);
         final int st = count / chunks;
         final int step = (chunks * st == count) ? st : st + 1;
         final int srcIdx2 = srcIdx - 1;
         output[dstIdx] = input[srcIdx2+count];

         for (int i=0, idx=0; i<count; i++)
         {
            if ((sa[i]%step) != 0)
               continue;

            if (this.setPrimaryIndex(sa[i]/step, i+1) == true)
            {
               idx++;

               if (idx == chunks)
                  break;
            }
         }

         final int dstIdx2 = dstIdx + 1;
         final int pIdx0 = this.getPrimaryIndex(0);

         for (int i=0; i<pIdx0-1; i++)
            output[dstIdx2+i] = input[srcIdx2+sa[i]];

         for (int i=pIdx0; i<count; i++)
            output[dstIdx+i] = input[srcIdx2+sa[i]];
      }

      src.index += count;
      dst.index += count;
      return res;
   }


   // Not thread safe
   @Override
   public boolean inverse(SliceByteArray src, SliceByteArray dst)
   {
      if (src.length == 0)
         return true;

      if (src.array == dst.array)
         return false;

      final int count = src.length;

      // Not a recoverable error: instead of silently fail the transform,
      // issue a fatal error.
      if (count > maxBlockSize())
         throw new IllegalArgumentException("The max BWT block size is "+maxBlockSize()+", got "+count);

      if (dst.index + count > dst.array.length)
         return false;

      if (count < 2)
      {
         if (count == 1)
            dst.array[dst.index++] = src.array[src.index++];

         return true;
      }

      // Find the fastest way to implement inverse based on block size
      if ((count <= BLOCK_SIZE_THRESHOLD2) && (this.jobs == 1))
         return inverseMergeTPSI(src, dst, count);

      return inverseBiPSIv2(src, dst, count);
   }


   // When count <= BLOCK_SIZE_THRESHOLD2, mergeTPSI algo
   private boolean inverseMergeTPSI(SliceByteArray src, SliceByteArray dst, int count)
   {
      // Lazy dynamic memory allocation
      if (this.buffer1.length < count)
         this.buffer1 = new int[Math.max(count, 64)];

      // Aliasing
      final byte[] input = src.array;
      final byte[] output = dst.array;
      final int srcIdx = src.index;
      final int dstIdx = dst.index;
      final int[] buckets_ = this.buckets;
      final int[] data = this.buffer1;

      // Build array of packed index + value (assumes block size < 1<<24)
      int pIdx = this.getPrimaryIndex(0);

      if ((pIdx < 0) || (pIdx > count))
         return false;

      Global.computeHistogramOrder0(input, srcIdx, srcIdx+count, this.buckets, false);

      for (int i=0, sum=0; i<256; i++)
      {
         final int tmp = buckets_[i];
         buckets_[i] = sum;
         sum += tmp;
      }

      for (int i=0; i<pIdx; i++)
      {
         final int val = input[srcIdx+i] & 0xFF;
         data[buckets_[val]] = ((i-1)<<8) | val;
         buckets_[val]++;
      }

      for (int i=pIdx; i<count; i++)
      {
         final int val = input[srcIdx+i] & 0xFF;
         data[buckets_[val]] = (i<<8) | val;
         buckets_[val]++;
      }

      if (count < BLOCK_SIZE_THRESHOLD1)
      {
         for (int i=0, t=pIdx-1; i<count; i++)
         {
            final int ptr = data[t];
            output[dstIdx+i] = (byte) ptr;
            t = ptr >>> 8;
         }
      }
      else
      {
         final int ckSize = ((count & 7) == 0) ? count>>3 : (count>>3) + 1;
         int t0 = this.getPrimaryIndex(0) - 1;
         int t1 = this.getPrimaryIndex(1) - 1;
         int t2 = this.getPrimaryIndex(2) - 1;
         int t3 = this.getPrimaryIndex(3) - 1;
         int t4 = this.getPrimaryIndex(4) - 1;
         int t5 = this.getPrimaryIndex(5) - 1;
         int t6 = this.getPrimaryIndex(6) - 1;
         int t7 = this.getPrimaryIndex(7) - 1;
         int n = 0;

         while (true)
         {
            final int ptr0 = data[t0];
            output[dstIdx+n] = (byte) ptr0;
            t0 = ptr0 >>> 8;
            final int ptr1 = data[t1];
            output[dstIdx+n+ckSize] = (byte) ptr1;
            t1 = ptr1 >>> 8;
            final int ptr2 = data[t2];
            output[dstIdx+n+ckSize*2] = (byte) ptr2;
            t2 = ptr2 >>> 8;
            final int ptr3 = data[t3];
            output[dstIdx+n+ckSize*3] = (byte) ptr3;
            t3 = ptr3 >>> 8;
            final int ptr4 = data[t4];
            output[dstIdx+n+ckSize*4] = (byte) ptr4;
            t4 = ptr4 >>> 8;
            final int ptr5 = data[t5];
            output[dstIdx+n+ckSize*5] = (byte) ptr5;
            t5 = ptr5 >>> 8;
            final int ptr6 = data[t6];
            output[dstIdx+n+ckSize*6] = (byte) ptr6;
            t6 = ptr6 >>> 8;
            final int ptr7 = data[t7];
            output[dstIdx+n+ckSize*7] = (byte) ptr7;
            t7 = ptr7 >>> 8;
            n++;

            if (ptr7 < 0)
               break;
         }

         while (n < ckSize)
         {
            final int ptr0 = data[t0];
            output[dstIdx+n] = (byte) ptr0;
            t0 = ptr0 >>> 8;
            final int ptr1 = data[t1];
            output[dstIdx+n+ckSize] = (byte) ptr1;
            t1 = ptr1 >>> 8;
            final int ptr2 = data[t2];
            output[dstIdx+n+ckSize*2] = (byte) ptr2;
            t2 = ptr2 >>> 8;
            final int ptr3 = data[t3];
            output[dstIdx+n+ckSize*3] = (byte) ptr3;
            t3 = ptr3 >>> 8;
            final int ptr4 = data[t4];
            output[dstIdx+n+ckSize*4] = (byte) ptr4;
            t4 = ptr4 >>> 8;
            final int ptr5 = data[t5];
            output[dstIdx+n+ckSize*5] = (byte) ptr5;
            t5 = ptr5 >>> 8;
            final int ptr6 = data[t6];
            output[dstIdx+n+ckSize*6] = (byte) ptr6;
            t6 = ptr6 >>> 8;
            n++;
         }
      }

      src.index += count;
      dst.index += count;
      return true;
   }


   // When count > BLOCK_SIZE_THRESHOLD2, biPSIv2 algo
   private boolean inverseBiPSIv2(SliceByteArray src, SliceByteArray dst, int count)
   {
      // Lazy dynamic memory allocations
      if (this.buffer1.length < count+1)
         this.buffer1 = new int[Math.max(count+1, 64)];

      if (this.buckets.length < 65536)
         this.buckets = new int[65536];

      if (this.buffer2.length < MASK_FASTBITS+1)
         this.buffer2 = new short[MASK_FASTBITS+1];

      // Aliasing
      final byte[] input = src.array;
      final byte[] output = dst.array;
      final int srcIdx = src.index;
      final int dstIdx = dst.index;
      final int srcIdx2 = src.index - 1;

      int pIdx = this.getPrimaryIndex(0);

      if ((pIdx < 0) || (pIdx > count))
         return false;

      Global.computeHistogramOrder0(input, srcIdx, srcIdx+count, this.freqs, false);
      final int[] buckets_ = this.buckets;
      final int[] freqs_ = this.freqs;

      for (int sum=1, c=0; c<256; c++)
      {
         final int f = sum;
         sum += freqs_[c];
         freqs_[c] = f;

         if (f != sum)
         {
            final int c256 = c << 8;
            final int hi = (sum < pIdx) ? sum : pIdx;

            for (int i=f; i<hi; i++)
               buckets_[c256|(input[srcIdx+i]&0xFF)]++;

            final int lo = (f-1 > pIdx) ? f-1: pIdx;

            for (int i=lo; i<sum-1; i++)
               buckets_[c256|(input[srcIdx+i]&0xFF)]++;
         }
      }

      final int lastc = input[srcIdx] & 0xFF;
      final short[] fastBits = this.buffer2;
      int shift = 0;

      while ((count>>>shift) > MASK_FASTBITS)
         shift++;

      for (int v=0, sum=1, c=0; c<256; c++)
      {
         if (c == lastc)
            sum++;

         for (int d=0; d<256; d++)
         {
            final int s = sum;
            sum += buckets_[(d<<8)|c];
            buckets_[(d<<8)|c] = s;

            if (s != sum)
            {
               for (; v <= ((sum-1)>>shift); v++)
                  fastBits[v] = (short) ((c<<8)|d);
            }
         }
      }

      final int[] data = this.buffer1;

      for (int i=0; i<pIdx; ++i)
      {
         final int c = input[srcIdx+i] & 0xFF;
         final int p = freqs_[c];
         freqs_[c]++;

         if (p < pIdx)
         {
            final int idx = (c<<8) | (input[srcIdx+p]&0xFF);
            data[buckets_[idx]] = i;
            buckets_[idx]++;
         }
         else if (p > pIdx)
         {
            final int idx = (c<<8) | (input[srcIdx2+p]&0xFF);
            data[buckets_[idx]] = i;
            buckets_[idx]++;
         }
      }

      for (int i=pIdx; i<count; i++)
      {
         final int c = input[srcIdx+i] & 0xFF;
         final int p = freqs_[c];
         freqs_[c]++;

         if (p < pIdx)
         {
            final int idx = (c<<8) | (input[srcIdx+p]&0xFF);
            data[buckets_[idx]] = i + 1;
            buckets_[idx]++;
         }
         else if (p > pIdx)
         {
            final int idx = (c<<8) | (input[srcIdx2+p]&0xFF);
            data[buckets_[idx]] = i + 1;
            buckets_[idx]++;
         }
      }

      for (int c=0; c<256; c++)
      {
         final int c256 = c << 8;

         for (int d=0; d<c; d++)
         {
            final int tmp = buckets_[(d<<8)|c];
            buckets_[(d<<8)|c] = buckets_[c256|d];
            buckets_[c256|d] = tmp;
         }
      }

      final int chunks = getBWTChunks(count);

      // Build inverse
      // Several chunks may be decoded concurrently (depending on the availability
      // of jobs in the pool).
      final int st = count / chunks;
      final int ckSize = (chunks*st == count) ? st : st+1;
      final int nbTasks = (this.jobs <= chunks) ? this.jobs : chunks;
      List<Callable<Integer>> tasks = new ArrayList<>(nbTasks);
      final int[] jobsPerTask = Global.computeJobsPerTask(new int[nbTasks], chunks, nbTasks);

      // Create one task per job
      for (int j=0, c=0; j<nbTasks; j++)
      {
         // Each task decodes jobsPerTask[j] chunks
         final int start = dstIdx + c*ckSize;
         tasks.add(new InverseBiPSIv2Task(output, start, count, ckSize, c, c+jobsPerTask[j]));
         c += jobsPerTask[j];
      }

      try
      {
         if (this.jobs == 1)
         {
            tasks.get(0).call();
         }
         else
         {
            // Wait for completion of all concurrent tasks
            for (Future<Integer> result : this.pool.invokeAll(tasks))
               result.get();
         }
      }
      catch (Exception e)
      {
         return false;
      }

      output[dstIdx+count-1] = (byte) lastc;
      src.index += count;
      dst.index += count;
      return true;
   }


   public static int maxBlockSize()
   {
      return MAX_BLOCK_SIZE;
   }


   public static int getBWTChunks(int size)
   {
      return (size < BLOCK_SIZE_THRESHOLD1) ? 1 : 8;
   }


   // Process one or several chunk(s)
   class InverseBiPSIv2Task implements Callable<Integer>
   {
      private final byte[] output;
      private final int dstIdx;     // initial offset
      private final int ckSize;     // chunk size, must be adjusted to not go over total
      private final int total;      // max number of bytes to process
      private final int firstChunk; // index first chunk
      private final int lastChunk;  // index last chunk


      public InverseBiPSIv2Task(byte[] output, int dstIdx, int total,
         int ckSize, int firstChunk, int lastChunk)
      {
         this.output = output;
         this.dstIdx = dstIdx;
         this.ckSize = ckSize;
         this.total = total;
         this.firstChunk = firstChunk;
         this.lastChunk = lastChunk;
      }


      @Override
      public Integer call() throws Exception
      {
         final int[] data = BWT.this.buffer1;
         final int[] buckets = BWT.this.buckets;
         final short[] fastBits = BWT.this.buffer2;
         int start = this.dstIdx;
         int shift = 0;

         while ((this.total>>>shift) > MASK_FASTBITS)
            shift++;

         // Process each chunk sequentially
         for (int c=this.firstChunk; c<this.lastChunk; c++)
         {
            final int end = (start+this.ckSize) > this.total-1 ? this.total-1 : start+this.ckSize;
            int p = BWT.this.getPrimaryIndex(c);

            for (int i=start+1; i<=end; i+=2)
            {
               int s = fastBits[p>>shift] & 0xFFFF;

               while (buckets[s] <= p)
                  s++;

               this.output[i-1] = (byte) (s>>>8);
               this.output[i] = (byte) s;
               p = data[p];
            }

            start = end;
         }

         return 0;
      }
   }


   // Return the max size required for the encoding output buffer
   @Override
   public int getMaxEncodedLength(int srcLength)
   {
      return srcLength;
   }
}
