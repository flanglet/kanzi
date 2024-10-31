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
package io.github.flanglet.kanzi.entropy;

import io.github.flanglet.kanzi.Global;

// APM maps a probability and a context into a new probability
// that the next bit will be 1. After each guess, it updates
// its state to improve future guesses.

/*package*/ final class LogisticAdaptiveProbMap
{
   private int index;        // last prob, context
   private final int rate;   // update rate
   private final int[] data; // prob, context -> prob


   LogisticAdaptiveProbMap(int n, int rate)
   {
      final int size = (n == 0) ? 33 : n*33;
      this.data = new int[size];
      this.rate = rate;

      for (int j=0; j<=32; j++)
         this.data[j] = Global.squash((j-16)<<7) << 4;

      for (int i=1; i<n; i++)
         System.arraycopy(this.data, 0, this.data, i*33, 33);
   }


   // Return improved prediction given current bit, prediction and context
   int get(int bit, int pr, int ctx)
   {
      // Update probability based on error and learning rate
      final int g = (-bit & 65528) + (bit<<this.rate);
      this.data[this.index] += ((g-this.data[this.index]) >> this.rate);
      this.data[this.index+1] += ((g-this.data[this.index+1]) >> this.rate);
      pr = Global.STRETCH[pr];

      // Find index: 33*ctx + quantized prediction in [0..32]
      this.index = ((pr+2048)>>7) + (ctx<<5) + ctx;

      // Return interpolated probability
      final int w = pr & 127;
      return (this.data[this.index]*(128-w) + this.data[this.index+1]*w) >> 11;
   }
}
