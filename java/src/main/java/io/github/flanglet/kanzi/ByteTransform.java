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


public interface ByteTransform
{
   // Read src.length bytes from src.array[src.index], process them and
   // write them to dst.array[dst.index]. The index of each slice is updated
   // with the number of bytes respectively read from and written to.
   public boolean forward(SliceByteArray src, SliceByteArray dst);


   // Read src.length bytes from src.array[src.index], process them and
   // write them to dst.array[dst.index]. The index of each slice is updated
   // with the number of bytes respectively read from and written to.
   public boolean inverse(SliceByteArray src, SliceByteArray dst);


   // Return the max size required for the output buffer
   public int getMaxEncodedLength(int srcLength);
}

