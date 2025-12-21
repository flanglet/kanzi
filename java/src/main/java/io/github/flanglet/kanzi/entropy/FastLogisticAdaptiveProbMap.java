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

import io.github.flanglet.kanzi.Global;

/**
 * <p>
 * Implementation of an Adaptive Probability Map (APM) with fast logistic
 * function.
 * This class maps a probability and a context into a new probability that the
 * next bit will be 1.
 * After each guess, it updates its state to improve future guesses.
 * </p>
 *
 * <p>
 * It uses a logistic function to squash the prediction and adapts its internal
 * probabilities based on the actual bit observed and a learning rate.
 * </p>
 */
/* package */ final class FastLogisticAdaptiveProbMap {
   /**
    * The index into the {@code data} array, representing the last probability and
    * context.
    */
   private int index;

   /**
    * The update rate for adapting probabilities. A smaller rate means faster
    * adaptation.
    */
   private final int rate;

   /**
    * The internal data array storing probabilities for different contexts.
    * Each entry is a packed integer representing a probability.
    */
   private final int[] data;

   /**
    * Creates a new {@code FastLogisticAdaptiveProbMap}.
    *
    * @param n    The number of contexts to support.
    * @param rate The update rate for adapting probabilities.
    */
   FastLogisticAdaptiveProbMap(int n, int rate) {
      this.data = new int[n * 32];
      this.rate = rate;

      for (int j = 0; j < 32; j++) {
         this.data[j] = Global.squash((j - 16) << 7) << 4;
      }

      for (int i = 1; i < n; i++) {
         System.arraycopy(this.data, 0, this.data, i * 32, 32);
      }
   }

   /**
    * Returns an improved prediction given the current bit, prediction, and
    * context.
    *
    * @param bit The actual bit observed (0 or 1).
    * @param pr  The current prediction (probability of 1).
    * @param ctx The current context.
    * @return The improved prediction (probability of 1), scaled.
    */
   int get(int bit, int pr, int ctx) {
      // Update probability based on error and learning rate
      final int g = (-bit & 65528) + (bit << this.rate);
      this.data[this.index] += ((g - this.data[this.index]) >> this.rate);

      // Find index: 32*ctx + quantized prediction in [0..32[
      this.index = ((Global.STRETCH[pr] + 2048) >> 7) + (ctx << 5);
      return (this.data[this.index]) >> 4;
   }
}
