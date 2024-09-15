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

package kanzi;

public class Event
{
   public enum Type
   {
      COMPRESSION_START,
      DECOMPRESSION_START,
      BEFORE_TRANSFORM,
      AFTER_TRANSFORM,
      BEFORE_ENTROPY,
      AFTER_ENTROPY,
      COMPRESSION_END,
      DECOMPRESSION_END,
      AFTER_HEADER_DECODING
   }

   public enum HashType
   {
      NO_HASH,
      SIZE_32,
      SIZE_64
   }

   private final int id;
   private final long size;
   private final long hash;
   private final Type type;
   private final HashType hashType;
   private final long time;
   private final String msg;


   public Event(Type type, int id, long size)
   {
      this(type, id, size, 0, HashType.NO_HASH);
   }


   public Event(Type type, int id, String msg)
   {
      this(type, id, msg, 0);
   }


   public Event(Type type, int id, String msg, long time)
   {
      this.id = id;
      this.size = 0L;
      this.hash = 0;
      this.hashType = HashType.NO_HASH;
      this.type = type;
      this.time = (time > 0) ? time : System.nanoTime();
      this.msg = msg;
   }


   public Event(Type type, int id, long size, long hash, HashType hashType)
   {
      this(type, id, size, hash, hashType, 0);
   }


   public Event(Type type, int id, long size, long hash, HashType hashType, long time)
   {
      this.id = id;
      this.size = size;
      this.hash = hash;
      this.hashType = hashType;
      this.type = type;
      this.time = (time > 0) ? time : System.nanoTime();
      this.msg = null;
   }


   public int getId()
   {
      return this.id;
   }


   public long getSize()
   {
      return this.size;
   }


   public long getTime()
   {
      return this.time;
   }


   public long getHash()
   {
      return (this.hashType == HashType.NO_HASH) ? 0 : this.hash;
   }


   public HashType getHashType()
   {
      return this.hashType;
   }


   public Type getType()
   {
      return this.type;
   }


   @Override
   public String toString()
   {
      if (this.msg != null)
         return this.msg;

      StringBuilder sb = new StringBuilder(200);
      sb.append("{ \"type\":\"").append(this.getType()).append("\"");

      if (this.id >= 0)
         sb.append(", \"id\":").append(this.getId());

      sb.append(", \"size\":").append(this.getSize());
      sb.append(", \"time\":").append(this.getTime());

      if (this.hashType == HashType.SIZE_32)
         sb.append(", \"hash\":").append(Integer.toHexString((int) this.getHash()));
      else if (this.hashType == HashType.SIZE_64)
         sb.append(", \"hash\":").append(Long.toHexString(this.getHash()));

      sb.append(" }");
      return sb.toString();
   }
}
