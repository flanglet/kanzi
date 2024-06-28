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

package kanzi.transform;

import java.util.Map;
import kanzi.ByteTransform;


public class TransformFactory
{
   private static final int ONE_SHIFT = 6; // bits per transform
   private static final int MAX_SHIFT = (8-1) * ONE_SHIFT; // 8 transforms
   private static final int MASK = (1<<ONE_SHIFT) - 1;

   // Up to 64 transforms can be declared (6 bit index)
   public static final short NONE_TYPE    = 0;  // copy
   public static final short BWT_TYPE     = 1;  // Burrows Wheeler
   public static final short BWTS_TYPE    = 2;  // Burrows Wheeler Scott
   public static final short LZ_TYPE      = 3;  // Lempel Ziv
   public static final short SNAPPY_TYPE  = 4;  // Snappy (obsolete)
   public static final short RLT_TYPE     = 5;  // Run Length
   public static final short ZRLT_TYPE    = 6;  // Zero Run Length
   public static final short MTFT_TYPE    = 7;  // Move To Front
   public static final short RANK_TYPE    = 8;  // Rank
   public static final short EXE_TYPE     = 9;  // EXE codec
   public static final short DICT_TYPE    = 10; // Text codec
   public static final short ROLZ_TYPE    = 11; // ROLZ codec
   public static final short ROLZX_TYPE   = 12; // ROLZ Extra codec
   public static final short SRT_TYPE     = 13; // Sorted Rank
   public static final short LZP_TYPE     = 14; // Lempel Ziv Predict
   public static final short MM_TYPE      = 15; // Multimedia (FSD) codec
   public static final short LZX_TYPE     = 16; // Lempel Ziv Extra
   public static final short UTF_TYPE     = 17; // UTF codec
   public static final short PACK_TYPE    = 18; // Alias codec
   public static final short DNA_TYPE     = 19; // DNA Alias codec
   public static final short RESERVED3    = 20; // Reserved
   public static final short RESERVED4    = 21; // Reserved
   public static final short RESERVED5    = 22; // Reserved


   // The returned type contains 8 transform values
   public long getType(String name)
   {
      if (name.indexOf('+') < 0)
         return this.getTypeToken(name) << MAX_SHIFT;

      String[] tokens = name.split("\\+");

      if (tokens.length == 0)
         throw new IllegalArgumentException("Unknown transform type: " + name);

      if (tokens.length > 8)
         throw new IllegalArgumentException("Only 8 transforms allowed: " + name);

      long res = 0;
      int shift = MAX_SHIFT;

      for (String token: tokens)
      {
         final long typeTk = this.getTypeToken(token);

         // Skip null transform
         if (typeTk != NONE_TYPE)
         {
            res |= (typeTk << shift);
            shift -= ONE_SHIFT;
         }
      }

      return res;
   }


   private long getTypeToken(String name)
   {
      // Strings in switch not supported in JDK 6
      name = String.valueOf(name).toUpperCase();

      switch (name)
      {
         case "TEXT":
            return DICT_TYPE;

         case "BWT":
            return BWT_TYPE;

         case "BWTS":
            return BWTS_TYPE;

         case "LZ":
            return LZ_TYPE;

         case "LZX":
            return LZX_TYPE;

         case "LZP":
            return LZP_TYPE;

         case "ROLZ":
            return ROLZ_TYPE;

         case "ROLZX":
            return ROLZX_TYPE;

         case "SRT":
            return SRT_TYPE;

         case "RANK":
            return RANK_TYPE;

         case "MTFT":
            return MTFT_TYPE;

         case "ZRLT":
            return ZRLT_TYPE;

         case "UTF":
            return UTF_TYPE;

         case "RLT":
            return RLT_TYPE;

         case "EXE":
            return EXE_TYPE;

         case "MM":
            return MM_TYPE;

         case "PACK":
            return PACK_TYPE;

         case "DNA":
            return DNA_TYPE;

         case "NONE":
            return NONE_TYPE;

         default:
            throw new IllegalArgumentException("Unknown transform type: '" + name + "'");
      }
   }


   public Sequence newFunction(Map<String, Object> ctx, long functionType)
   {
      int nbtr = 0;

      // Several transforms
      for (int i=0; i<8; i++)
      {
         if (((functionType >>> (MAX_SHIFT-ONE_SHIFT*i)) & MASK) != NONE_TYPE)
            nbtr++;
      }

      // Only null transforms ? Keep first.
      if (nbtr == 0)
         nbtr = 1;

      ByteTransform[] transforms = new ByteTransform[nbtr];
      nbtr = 0;

      for (int i=0; i<transforms.length; i++)
      {
         final int t = (int) ((functionType >>> (MAX_SHIFT-ONE_SHIFT*i)) & MASK);

         if ((t != NONE_TYPE) || (i == 0))
            transforms[nbtr++] = newFunctionToken(ctx, t);
      }

      return new Sequence(transforms);
   }


   private static ByteTransform newFunctionToken(Map<String, Object> ctx, int functionType)
   {
      switch (functionType)
      {
         case DICT_TYPE:
            String entropyType = (String) ctx.getOrDefault("entropy", "NONE");
            entropyType = entropyType.toUpperCase();
            int textCodecType  = 1;

            // Select text encoding based on entropy codec.
            if (entropyType.equals("NONE") || entropyType.equals("ANS0") ||
                entropyType.equals("HUFFMAN") || entropyType.equals("RANGE"))
               textCodecType = 2;

            ctx.put("textcodec", textCodecType);
            return new TextCodec(ctx);

         case ROLZ_TYPE:
            return new ROLZCodec(ctx);

         case ROLZX_TYPE:
            return new ROLZCodec(ctx);

         case BWT_TYPE:
            return new BWTBlockCodec(ctx);

         case BWTS_TYPE:
            return new BWTS(ctx);

         case RANK_TYPE:
            ctx.put("sbrt", SBRT.MODE_RANK);
            return new SBRT(ctx);

         case SRT_TYPE:
            return new SRT(ctx);

         case MTFT_TYPE:
            ctx.put("sbrt", SBRT.MODE_MTF);
            return new SBRT(ctx);

         case ZRLT_TYPE:
            return new ZRLT(ctx);

         case UTF_TYPE:
            return new UTFCodec(ctx);

         case RLT_TYPE:
            return new RLT(ctx);

         case LZ_TYPE:
            ctx.put("lz", LZ_TYPE);
            return new LZCodec(ctx);

         case LZX_TYPE:
            ctx.put("lz", LZX_TYPE);
            return new LZCodec(ctx);

         case LZP_TYPE:
            ctx.put("lz", LZP_TYPE);
            return new LZCodec(ctx);

         case EXE_TYPE:
            return new EXECodec(ctx);

         case MM_TYPE:
            return new FSDCodec(ctx);

         case PACK_TYPE:
            return new AliasCodec(ctx);

         case DNA_TYPE:
            ctx.put("packOnlyDNA", true);
            return new AliasCodec(ctx);

         case NONE_TYPE:
            return new NullTransform(ctx);

         default:
            throw new IllegalArgumentException("Unknown transform type: '" + functionType + "'");
      }
   }


   public String getName(long functionType)
   {
      StringBuilder sb = new StringBuilder();

      for (int i=0; i<8; i++)
      {
         final int t = (int) (functionType >>> (MAX_SHIFT-ONE_SHIFT*i)) & MASK;

         if (t == NONE_TYPE)
            continue;

         String name = getNameToken(t);

         if (sb.length() != 0)
            sb.append('+');

         sb.append(name);
      }

      if (sb.length() == 0)
         sb.append(getNameToken(NONE_TYPE));

      return sb.toString();
   }


   private static String getNameToken(int functionType)
   {
      switch (functionType)
      {
         case DICT_TYPE:
            return "TEXT";

         case ROLZ_TYPE:
            return "ROLZ";

         case ROLZX_TYPE:
            return "ROLZX";

         case BWT_TYPE:
            return "BWT";

         case BWTS_TYPE:
            return "BWTS";

         case SRT_TYPE:
            return "SRT";

         case UTF_TYPE:
            return "UTF";

         case RANK_TYPE:
            return "RANK";

         case MTFT_TYPE:
            return "MTFT";

         case ZRLT_TYPE:
            return "ZRLT";

         case RLT_TYPE:
            return "RLT";

         case EXE_TYPE:
            return "EXE";

         case LZ_TYPE:
            return "LZ";

         case LZX_TYPE:
            return "LZX";

         case LZP_TYPE:
            return "LZP";

         case MM_TYPE:
            return "MM";

         case PACK_TYPE:
            return "PACK";

         case DNA_TYPE:
            return "DNA";

         case NONE_TYPE:
            return "NONE";

         default:
            throw new IllegalArgumentException("Unknown transform type: '" + functionType + "'");
      }
   }
}
