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

package io.github.flanglet.kanzi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;


/**
 * The {@code Global} class provides utility methods and constants for
 * various operations, including logarithmic calculations and squashing functions.
 */
public class Global {

  /**
   *  Enum representing the types of data that can be specially processed.
   */
  public enum DataType {
        UNDEFINED,
        TEXT,
        MULTIMEDIA,
        EXE,
        NUMERIC,
        BASE64,
        DNA,
        BIN,
        UTF8,
        SMALL_ALPHABET
  }

  /**
   * Private constructor to prevent instantiation.
   */
  private Global() {
  }


  /**
   *  Array with 256 elements: int(Math.log2(x-1))
   */
  public static final int[] LOG2_VALUES = new int[]{
      0, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 4,
      4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 5,
      5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
      5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 6,
      6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
      6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
      6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
      6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 7,
      7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
      7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
      7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
      7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
      7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
      7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
      7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
      7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 8
  };

  // Array with 256 elements: 4096*Math.log2(x)
  private static final int[] LOG2_4096 = new int[]{
          0,     0,  4096,  6492,  8192,  9511, 10588, 11499, 12288, 12984,
      13607, 14170, 14684, 15157, 15595, 16003, 16384, 16742, 17080, 17400,
      17703, 17991, 18266, 18529, 18780, 19021, 19253, 19476, 19691, 19898,
      20099, 20292, 20480, 20662, 20838, 21010, 21176, 21338, 21496, 21649,
      21799, 21945, 22087, 22226, 22362, 22495, 22625, 22752, 22876, 22998,
      23117, 23234, 23349, 23462, 23572, 23680, 23787, 23892, 23994, 24095,
      24195, 24292, 24388, 24483, 24576, 24668, 24758, 24847, 24934, 25021,
      25106, 25189, 25272, 25354, 25434, 25513, 25592, 25669, 25745, 25820,
      25895, 25968, 26041, 26112, 26183, 26253, 26322, 26390, 26458, 26525,
      26591, 26656, 26721, 26784, 26848, 26910, 26972, 27033, 27094, 27154,
      27213, 27272, 27330, 27388, 27445, 27502, 27558, 27613, 27668, 27722,
      27776, 27830, 27883, 27935, 27988, 28039, 28090, 28141, 28191, 28241,
      28291, 28340, 28388, 28437, 28484, 28532, 28579, 28626, 28672, 28718,
      28764, 28809, 28854, 28898, 28943, 28987, 29030, 29074, 29117, 29159,
      29202, 29244, 29285, 29327, 29368, 29409, 29450, 29490, 29530, 29570,
      29609, 29649, 29688, 29726, 29765, 29803, 29841, 29879, 29916, 29954,
      29991, 30027, 30064, 30100, 30137, 30172, 30208, 30244, 30279, 30314,
      30349, 30384, 30418, 30452, 30486, 30520, 30554, 30587, 30621, 30654,
      30687, 30719, 30752, 30784, 30817, 30849, 30880, 30912, 30944, 30975,
      31006, 31037, 31068, 31099, 31129, 31160, 31190, 31220, 31250, 31280,
      31309, 31339, 31368, 31397, 31426, 31455, 31484, 31513, 31541, 31569,
      31598, 31626, 31654, 31681, 31709, 31737, 31764, 31791, 31818, 31846,
      31872, 31899, 31926, 31952, 31979, 32005, 32031, 32058, 32084, 32109,
      32135, 32161, 32186, 32212, 32237, 32262, 32287, 32312, 32337, 32362,
      32387, 32411, 32436, 32460, 32484, 32508, 32533, 32557, 32580, 32604,
      32628, 32651, 32675, 32698, 32722, 32745, 32768
  };

  // 65536/(1 + exp(-alpha*x)) with alpha ~= 0.54
  private static final int[] INV_EXP = new int[]{
          0,     8,    22,    47,    88,   160,   283,   492,
        848,  1451,  2459,  4117,  6766, 10819, 16608, 24127,
      32768, 41409, 48928, 54717, 58770, 61419, 63077, 64085,
      64688, 65044, 65253, 65376, 65448, 65489, 65514, 65528,
      65536
  };

  private static final String[] WIN_RESERVED = new String[]{
        "AUX", "COM0", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6",
        "COM7", "COM8", "COM9", "COM¹", "COM²", "COM³", "CON", "LPT0", "LPT1", "LPT2",
        "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9", "NUL", "PRN"
  };

  private static final byte[] BASE64_SYMBOLS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".getBytes();

  private static final byte[] NUMERIC_SYMBOLS = "0123456789+-*/=,.:; ".getBytes();

  private static final byte[] DNA_SYMBOLS = "acgntuACGNTU".getBytes(); // either T or U and N for unknown

  private static final int[] SQUASH = initSquash();

  private static int[] initSquash() {
      final int[] res = new int[4096];

      for (int x = -2047; x <= 2047; x++) {
          final int w = x & 127;
          final int y = (x >> 7) + 16;
          res[x + 2047] = (INV_EXP[y] * (128 - w) + INV_EXP[y + 1] * w) >> 11;
      }

      res[4095] = 4095;
      return res;
  }


  /**
   * Return p = 1/(1 + exp(-d)), d scaled by 8 bits, p scaled by 12 bits.
   *
   * @param d the value to squash
   * @return the squashed value
   */
  public static int squash(int d) {
      if (d >= 2048)
          return 4095;

      return SQUASH[positiveOrNull(d + 2047)];
  }

  /**
   * Global variable initialized with the inverse of squash:
   * d = ln(p/(1-p)), d scaled by 8 bits, p by 12 bits.
   *
   */
  public static final int[] STRETCH = initStretch();

  private static int[] initStretch() {
      final int[] res = new int[4096];
      int pi = 0;

      for (int x = -2047; (x <= 2047) && (pi < 4096); x++) {
          final int i = squash(x);

          while (pi <= i)
              res[pi++] = x;
      }

      res[4095] = 2047;
      return res;
  }

  /**
   * Return the base 2 logarithm of a value.
   *
   * @param x the value to calculate the logarithm of
   * @return the base 2 logarithm
   * @throws ArithmeticException if the value is negative or zero
   */
  public static int log2(int x) throws ArithmeticException {
      if (x <= 0)
          throw new ArithmeticException("Cannot calculate log of a negative or null value");

      return (x <= 256) ? LOG2_VALUES[x - 1] : 31 - Integer.numberOfLeadingZeros(x);
  }


  /**
   * Return 1024 * log2(x). Max error is less than 0.1%.
   *
   * @param x the value to calculate the logarithm of
   * @return 1024 * log2(x)
   * @throws ArithmeticException if the value is negative or zero
   */
  public static int log2_1024(int x) throws ArithmeticException {
      if (x <= 0)
          throw new ArithmeticException("Cannot calculate log of a negative or null value");

      if (x < 256)
          return (LOG2_4096[x] + 2) >> 2;

      final int log = 31 - Integer.numberOfLeadingZeros(x);

      if ((x & (x - 1)) == 0)
          return log << 10;

      return ((log - 7) * 1024) + ((LOG2_4096[x >> (log - 7)] + 2) >> 2);
  }

  /**
   * Return the positive value or zero.
   *
   * @param x the value to process
   * @return the positive value or zero
   */
  public static int positiveOrNull(int x) {
      return x & ~(x >> 31);
  }

  /**
   * Check if a value is a power of 2.
   *
   * @param x the value to check
   * @return {@code true} if the value is a power of 2, {@code false} otherwise
   */
  public static boolean isPowerOf2(int x) {
      return (x & (x - 1)) == 0;
  }


  /**
   * Computes the histogram of byte frequencies from a specified block of data.
   *
   * <p>This method counts the occurrences of each byte value (0-255) in the
   * specified range of the input byte array. If {@code withTotal} is true,
   * the last element of the frequency array will contain the total count of bytes
   * processed.</p>
   *
   * @param block the byte array containing data
   * @param start the starting index of the block to process
   * @param end the ending index of the block to process
   * @param freqs the array to store the frequency counts (should have a length of at least 257 if {@code withTotal} is true)
   * @param withTotal if true, the last element of {@code freqs} will hold the total byte count
   */
   public static void computeHistogramOrder0(byte[] block, int start, int end, int[] freqs, boolean withTotal) {
      for (int i=0; i<256; i+=8)
      {
         freqs[i]   = 0;
         freqs[i+1] = 0;
         freqs[i+2] = 0;
         freqs[i+3] = 0;
         freqs[i+4] = 0;
         freqs[i+5] = 0;
         freqs[i+6] = 0;
         freqs[i+7] = 0;
      }

      if (withTotal == true)
         freqs[256] = end - start;

      int[] f0 = new int[256];
      int[] f1 = new int[256];
      int[] f2 = new int[256];
      int[] f3 = new int[256];
      final int end16 = start + ((end-start) & -16);

      for (int i=start; i<end16; )
      {
         f0[block[i]&0xFF]++;
         f1[block[i+1]&0xFF]++;
         f2[block[i+2]&0xFF]++;
         f3[block[i+3]&0xFF]++;
         f0[block[i+4]&0xFF]++;
         f1[block[i+5]&0xFF]++;
         f2[block[i+6]&0xFF]++;
         f3[block[i+7]&0xFF]++;
         i += 8;
         f0[block[i]&0xFF]++;
         f1[block[i+1]&0xFF]++;
         f2[block[i+2]&0xFF]++;
         f3[block[i+3]&0xFF]++;
         f0[block[i+4]&0xFF]++;
         f1[block[i+5]&0xFF]++;
         f2[block[i+6]&0xFF]++;
         f3[block[i+7]&0xFF]++;
         i += 8;
      }

      for (int i=end16; i<end; i++)
         freqs[block[i]&0xFF]++;

      for (int i=0; i<256; i++)
         freqs[i] += (f0[i] + f1[i] + f2[i] + f3[i]);
   }


  /**
   * Computes the histogram of byte frequencies in the context of an order 1 model.
   *
   * <p>This method counts the occurrences of each byte value (0-255) while considering
   * the previous byte in the sequence. If {@code withTotal} is true, the last element
   * of each frequency array will contain the total count of bytes processed.</p>
   *
   * @param block the byte array containing data
   * @param start the starting index of the block to process
   * @param end the ending index of the block to process
   * @param freqs the 2D array to store the frequency counts for each byte value
   * @param withTotal if true, the last element of each frequency array will hold the total byte count
   */
   public static void computeHistogramOrder1(byte[] block, int start, int end, int[][] freqs, boolean withTotal)
   {
      final int quarter = (end-start) >> 2;
      int n0 = start + 0*quarter;
      int n1 = start + 1*quarter;
      int n2 = start + 2*quarter;
      int n3 = start + 3*quarter;

      if (withTotal == true)
      {
         if (end-start < 32)
         {
            int prv = 0;

            for (int i=start; i<end; i++)
            {
               freqs[prv][block[i]&0xFF]++;
               freqs[prv][256]++;
               prv = block[i] & 0xFF;
            }
         }
         else
         {
            int prv0 = 0;
            int prv1 = block[n1-1] & 0xFF;
            int prv2 = block[n2-1] & 0xFF;
            int prv3 = block[n3-1] & 0xFF;

            for (; n0<start+quarter; n0++, n1++, n2++, n3++)
            {
               final int cur0 = block[n0] & 0xFF;
               final int cur1 = block[n1] & 0xFF;
               final int cur2 = block[n2] & 0xFF;
               final int cur3 = block[n3] & 0xFF;
               freqs[prv0][cur0]++;
               freqs[prv0][256]++;
               freqs[prv1][cur1]++;
               freqs[prv1][256]++;
               freqs[prv2][cur2]++;
               freqs[prv2][256]++;
               freqs[prv3][cur3]++;
               freqs[prv3][256]++;
               prv0 = cur0;
               prv1 = cur1;
               prv2 = cur2;
               prv3 = cur3;
            }

            for (; n3 < end; n3++)
            {
               freqs[prv3][block[n3]&0xFF]++;
               freqs[prv3][256]++;
               prv3 = block[n3] & 0xFF;
            }
         }
     }
     else // no total
     {
         if (end-start < 32)
         {
            int prv = 0;

            for (int i=start; i<end; i++)
            {
               freqs[prv][block[i]&0xFF]++;
               prv = block[i] & 0xFF;
            }
         }
         else
         {
            int prv0 = 0;
            int prv1 = block[n1-1] & 0xFF;
            int prv2 = block[n2-1] & 0xFF;
            int prv3 = block[n3-1] & 0xFF;

            for (; n0<start+quarter; n0++, n1++, n2++, n3++)
            {
               final int cur0 = block[n0] & 0xFF;
               final int cur1 = block[n1] & 0xFF;
               final int cur2 = block[n2] & 0xFF;
               final int cur3 = block[n3] & 0xFF;
               freqs[prv0][cur0]++;
               freqs[prv1][cur1]++;
               freqs[prv2][cur2]++;
               freqs[prv3][cur3]++;
               prv0 = cur0;
               prv1 = cur1;
               prv2 = cur2;
               prv3 = cur3;
            }

            for (; n3<end; n3++)
            {
               freqs[prv3][block[n3]&0xFF]++;
               prv3 = block[n3] & 0xFF;
            }
         }
      }
   }


  /**
   * Computes the first-order entropy of the given histogram data.
   *
   * <p>This method calculates the entropy based on the provided histogram of byte frequencies.
   * The histogram should represent frequencies for byte values 0-255.</p>
   *
   * @param length the total number of bytes processed
   * @param histo the histogram array of size 256 containing byte frequencies
   * @return the calculated first-order entropy scaled to the range [0..1024]
   */
   public static int computeFirstOrderEntropy1024(int length, int[] histo) {
      if (length == 0)
         return 0;

      long sum = 0;
      final int logLength1024 = Global.log2_1024(length);

      for (int i = 0; i < 256; i++)
      {
         if (histo[i] == 0)
            continue;

         final long count = histo[i];
         sum += ((count * (logLength1024 - Global.log2_1024(histo[i]))) >> 3);
      }

      return (int) (sum / length);
   }


  /**
   * Computes the distribution of jobs per task.
   *
   * <p>This method divides a specified number of jobs across a given number of tasks,
   * ensuring that jobs are distributed as evenly as possible.</p>
   *
   * @param jobsPerTask the array to hold the number of jobs assigned to each task
   * @param jobs the total number of jobs to distribute
   * @param tasks the total number of tasks
   * @return the updated jobsPerTask array
   * @throws IllegalArgumentException if the number of tasks or jobs is less than or equal to zero
   */
   public static int[] computeJobsPerTask(int[] jobsPerTask, int jobs, int tasks) {
      if (tasks <= 0)
         throw new IllegalArgumentException("Invalid number of tasks provided: "+tasks);

      if (jobs <= 0)
         throw new IllegalArgumentException("Invalid number of jobs provided: "+jobs);

      int q = (jobs <= tasks) ? 1 : jobs / tasks;
      int r = (jobs <= tasks) ? 0 : jobs - q*tasks;
      Arrays.fill(jobsPerTask, q);
      int n = 0;

      while (r != 0)
      {
         jobsPerTask[n]++;
         r--;
         n++;

         if (n == tasks)
            n = 0;
      }

      return jobsPerTask;
   }


  /**
   * Sorts a list of files by their path and size.
   *
   * <p>If {@code sortBySize} is true, the files will be sorted primarily by their
   * parent directory paths and then by their sizes in descending order.</p>
   *
   * @param files the list of file paths to sort
   * @param sortBySize if true, sort by file size; otherwise sort by path
   */
   public static void sortFilesByPathAndSize(List<Path> files, final boolean sortBySize)
   {
      Comparator<Path> c = new Comparator<Path>()
      {
         @Override
         public int compare(Path p1, Path p2)
         {
            if (sortBySize == false)
               return p1.compareTo(p2);

            // First, compare parent directory paths
            final int res = p1.getParent().compareTo(p2.getParent());

            if (res != 0)
               return res;

            try
            {
               // Then, compare file sizes (decreasing order)
               return (int) (Files.size(p2) - Files.size(p1));
            }
            catch (IOException e)
            {
               return -1;
            }
         }
      };

      Collections.sort(files, c);
   }


  /**
   * Detects the data type based on byte frequency analysis.
   *
   * <p>This method identifies the type of data represented by the byte frequencies.
   * Possible types include DNA, numeric, Base64, binary, and small alphabet.</p>
   *
   * @param count the total count of bytes processed
   * @param freqs0 the frequency array for byte values (0-255)
   * @return the detected {@link DataType}
   */
   public static DataType detectSimpleType(int count, int[] freqs0) {
      if (count == 0)
         return DataType.UNDEFINED;

      int sum = 0;

      for (int i=0; i<12; i++)
         sum += freqs0[DNA_SYMBOLS[i]];

      if (sum > count-count/12)
         return DataType.DNA;

      sum = 0;

      for (int i=0; i<20; i++)
         sum += freqs0[NUMERIC_SYMBOLS[i]];

      if (sum == count)
         return DataType.NUMERIC;

      // Last symbol with padding '='
      sum = (freqs0[0x3D] == 1) ? 1 : 0;

      for (int i=0; i<64; i++)
         sum += freqs0[BASE64_SYMBOLS[i]];

      if (sum == count)
         return DataType.BASE64;

      sum = 0;

      for (int i=0; i<256; i+=8)
      {
         sum += (freqs0[i+0] > 0) ? 1 : 0;
         sum += (freqs0[i+1] > 0) ? 1 : 0;
         sum += (freqs0[i+2] > 0) ? 1 : 0;
         sum += (freqs0[i+3] > 0) ? 1 : 0;
         sum += (freqs0[i+4] > 0) ? 1 : 0;
         sum += (freqs0[i+5] > 0) ? 1 : 0;
         sum += (freqs0[i+6] > 0) ? 1 : 0;
         sum += (freqs0[i+7] > 0) ? 1 : 0;
      }

      if (sum == 256)
         return DataType.BIN;

      if (sum <= 4)
         return DataType.SMALL_ALPHABET;

      return DataType.UNDEFINED;
   }


   /**
    * Checks if a given file name is a reserved name on Windows systems.
    *
    * <p>This method checks if the provided file name is one of the reserved names
    * on Windows operating systems.</p>
    *
    * @param fileName the file name to check
    * @return true if the file name is reserved; false otherwise
    */
    public static boolean isReservedName(String fileName) {
      if (!System.getProperty("os.name").toLowerCase().contains("windows"))
         return false;

      for (int i=0; i<WIN_RESERVED.length; i++)
      {
         final int res = fileName.compareTo(WIN_RESERVED[i]);

         if (res == 0)
            return true;

         if (res < 0)
            break;
      }

      return false;
   }
}
