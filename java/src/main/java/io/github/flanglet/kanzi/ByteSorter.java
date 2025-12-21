/*
 * Kanzi is a modern, modular, portable, and efficient lossless data compressor.
 *
 * Copyright (C) 2024 Frederic Langlet
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

package io.github.flanglet.kanzi;

/**
 * This interface defines a method for sorting a sub-array of bytes.
 */
public interface ByteSorter {

  /**
   * Sorts a sub-array of bytes.
   *
   * @param array the array containing the sub-array to be sorted
   * @param idx the starting index of the sub-array
   * @param len the length of the sub-array
   * @return {@code true} if the sub-array was successfully sorted, {@code false} otherwise
   */
  public boolean sort(byte[] array, int idx, int len);
}
