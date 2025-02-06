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

package io.github.flanglet.kanzi.transform;

import java.util.Map;
import io.github.flanglet.kanzi.ByteTransform;
import io.github.flanglet.kanzi.SliceByteArray;


/**
 * NullTransform is a no-op transform that simply copies the input to the output
 * without performing any modifications.
 */
public class NullTransform implements ByteTransform {

    /**
     * Default constructor.
     */
    public NullTransform() {
    }

    /**
     * Constructor with a context map.
     *
     * @param ctx the context map (not used in this implementation)
     */
    public NullTransform(Map<String, Object> ctx) {
    }

    /**
     * Performs the forward transform, which in this case is a no-op copy
     * from the input to the output.
     *
     * @param input  the input byte array
     * @param output the output byte array
     * @return true if the transform was successful, false otherwise
     */
    @Override
    public boolean forward(SliceByteArray input, SliceByteArray output) {
        return doCopy(input, output);
    }

    /**
     * Performs the inverse transform, which in this case is a no-op copy
     * from the input to the output.
     *
     * @param input  the input byte array
     * @param output the output byte array
     * @return true if the transform was successful, false otherwise
     */
    @Override
    public boolean inverse(SliceByteArray input, SliceByteArray output) {
        return doCopy(input, output);
    }

    /**
     * Copies the input byte array to the output byte array.
     *
     * @param input  the input byte array
     * @param output the output byte array
     * @return true if the copy was successful, false otherwise
     */
    private static boolean doCopy(SliceByteArray input, SliceByteArray output) {
        if (input.length == 0)
            return true;

        final int count = input.length;

        if (output.length - output.index < count)
            return false;

        if ((input.array != output.array) || (input.index != output.index))
            System.arraycopy(input.array, input.index, output.array, output.index, count);

        input.index += count;
        output.index += count;
        return true;
    }

    /**
     * Returns the maximum encoded length, which is the same as the source length
     * for this no-op transform.
     *
     * @param srcLen the source length
     * @return the maximum encoded length
     */
    @Override
    public int getMaxEncodedLength(int srcLen) {
        return srcLen;
    }
}