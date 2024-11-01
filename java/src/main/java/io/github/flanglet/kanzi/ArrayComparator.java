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
 * This interface defines a method for comparing sub-arrays within an array.
 */
public interface ArrayComparator {

    /**
     * Compares two sub-arrays starting at the specified indices.
     *
     * @param lidx the starting index of the left sub-array
     * @param ridx the starting index of the right sub-array
     * @return a negative integer, zero, or a positive integer as the
     *         left sub-array is less than, equal to, or greater than
     *         the right sub-array
     */
    public int compare(int lidx, int ridx);
}
