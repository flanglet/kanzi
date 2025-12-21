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

package io.github.flanglet.kanzi.entropy;

import java.util.Arrays;
import java.util.Map;
import io.github.flanglet.kanzi.Predictor;

/**
 * <p>
 * Implementation of a Context Model based predictor.
 * This predictor estimates the probability of the next bit being 1 based on
 * a combination of different contexts and adaptive learning rates.
 * </p>
 *
 * <p>
 * It uses multiple probability counters that are updated based on the
 * actual decoded bit, allowing it to adapt to the characteristics of the
 * input data.
 * </p>
 */
public class CMPredictor implements Predictor {
   /**
    * The rate at which the fastest probability counter adapts.
    */
   private static final int FAST_RATE = 2;
   /**
    * The rate at which the medium probability counter adapts.
    */
   private static final int MEDIUM_RATE = 4;
   /**
    * The rate at which the slowest probability counter adapts.
    */
   private static final int SLOW_RATE = 6;
   /**
    * The scaling factor for probabilities, representing the maximum possible
    * probability value.
    */
   private static final int PSCALE = 65536;

   /**
    * The first context variable, derived from the previous bit.
    */
   private int c1;
   /**
    * The second context variable, derived from the bit before the previous one.
    */
   private int c2;
   /**
    * The current context, formed by previous bits.
    */
   private int ctx;
   /**
    * An index used for accessing probability counters.
    */
   private int idx;
   /**
    * A mask used to differentiate between run contexts.
    */
   private int runMask;
   /**
    * A 2D array of probability counters, used for general context modeling.
    * `counter1[i][j]` stores the probability for context `i` and sub-context `j`.
    */
   private final int[][] counter1;
   /**
    * A 2D array of probability counters, used for more specific context modeling.
    * `counter2[i][j]` stores the probability for context `i` and sub-context `j`.
    */
   private final int[][] counter2;
   /**
    * A flag indicating if the bitstream version is 3 or older, which affects
    * probability calculation.
    */
   private final boolean isBsVersion3;

   /**
    * Creates a new {@code CMPredictor}.
    * <p>
    * The predictor is initialized with default probability values and can be
    * configured with a context map to handle different bitstream versions.
    * </p>
    *
    * @param ctx A map containing context information for the predictor,
    *            e.g., "bsVersion" to specify the bitstream version.
    */
   public CMPredictor(Map<String, Object> ctx) {
      this.ctx = 1;
      this.idx = 0;
      this.counter1 = new int[256][257];
      this.counter2 = new int[512][17];

      int bsVersion = 4;

      if (ctx != null)
         bsVersion = (Integer) ctx.getOrDefault("bsVersion", 4);

      this.isBsVersion3 = bsVersion < 4;

      for (int i = 0; i < 256; i++) {
         Arrays.fill(this.counter1[i], PSCALE >> 1);

         for (int j = 0; j < 16; j++) {
            this.counter2[i + i][j] = j << 12;
            this.counter2[i + i + 1][j] = j << 12;
         }

         this.counter2[i + i][16] = (this.isBsVersion3 == true) ? 15 << 12 : 65535;
         this.counter2[i + i + 1][16] = (this.isBsVersion3 == true) ? 15 << 12 : 65535;
      }
   }

   /**
    * Updates the probability model based on the actual decoded bit.
    * <p>
    * The internal counters are adjusted based on the provided bit and adaptive
    * learning rates.
    * The context is also updated for the next prediction.
    * </p>
    *
    * @param bit The actual bit that was decoded (0 or 1).
    */
   @Override
   public void update(int bit) {
      final int[] counter1_ = this.counter1[this.ctx];
      final int[] counter2_ = this.counter2[this.ctx | this.runMask];

      if (bit == 0) {
         counter1_[256] -= (counter1_[256] >> FAST_RATE);
         counter1_[this.c1] -= (counter1_[this.c1] >> MEDIUM_RATE);
         counter2_[this.idx] -= (counter2_[this.idx] >> SLOW_RATE);
         counter2_[this.idx + 1] -= (counter2_[this.idx + 1] >> SLOW_RATE);
         this.ctx += this.ctx;
      } else {
         counter1_[256] -= ((counter1_[256] - PSCALE + 16) >> FAST_RATE);
         counter1_[this.c1] -= ((counter1_[this.c1] - PSCALE + 16) >> MEDIUM_RATE);
         counter2_[this.idx] -= ((counter2_[this.idx] - PSCALE + 16) >> SLOW_RATE);
         counter2_[this.idx + 1] -= ((counter2_[this.idx + 1] - PSCALE + 16) >> SLOW_RATE);
         this.ctx += (this.ctx + 1);
      }

      if (this.ctx > 255) {
         this.c2 = this.c1;
         this.c1 = this.ctx & 0xFF;
         this.ctx = 1;
         this.runMask = (this.c1 == this.c2) ? 0x100 : 0;
      }
   }

   /**
    * Returns the predicted probability of the next bit being 1.
    * <p>
    * The prediction is an integer value in the range [0, 4095], representing the
    * split point
    * in a range coding scheme.
    * </p>
    *
    * @return The predicted probability of the next bit being 1, scaled to [0,
    *         4095].
    */
   @Override
   public int get() {
      final int[] pc1 = this.counter1[this.ctx];
      final int p = (13 * (pc1[256] + pc1[this.c1]) + 6 * pc1[this.c2]) >> 5;
      this.idx = p >>> 12;
      final int[] pc2 = this.counter2[this.ctx | this.runMask];
      final int x1 = pc2[this.idx];
      final int x2 = pc2[this.idx + 1];

      if (this.isBsVersion3 == true) {
         final int ssep = x1 + (((x2 - x1) * (p & 4095)) >> 12);
         return (p + 3 * ssep + 32) >>> 6; // rescale to [0..4095]
      }

      return (p + p + 3 * (x1 + x2) + 64) >>> 7; // rescale to [0..4095]
   }
}
