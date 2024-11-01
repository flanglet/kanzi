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

/**
 * The {@code Predictor} interface is used by a binary entropy coder to
 * predict the probabilities of 0 and 1 symbols in the input signal.
 *
 * <p>Implementations of this interface should maintain a probability model
 * that can be updated based on input bits and can provide a split value
 * representing the predicted probability of the next bit being 1.</p>
 */
public interface Predictor {

    /**
     * Updates the probability model based on the provided bit.
     *
     * @param bit the bit to update the model with (0 or 1)
     */
    public void update(int bit);

    /**
     * Returns a split value representing the probability of the next bit being 1.
     * The returned value is in the range of [0..4095], where a value of
     * 410 roughly corresponds to a probability of 10% for the next bit being 1.
     *
     * @return the split value representing the probability of 1
     */
    public int get();
}
