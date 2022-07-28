/*
Copyright 2011-2022 Frederic Langlet
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


public class Magic 
{
   public static final int NO_MAGIC = 0;
   public static final int JPG_MAGIC = 0xFFD8FFE0;
   public static final int GIF_MAGIC = 0x47494638;
   public static final int PDF_MAGIC = 0x25504446;
   public static final int ZIP_MAGIC = 0x504B0304; // Works for jar & office docs
   public static final int LZMA_MAGIC = 0x377ABCAF;
   public static final int PNG_MAGIC = 0x89504E47;
   public static final int ELF_MAGIC = 0x7F454C46;
   public static final int MAC_MAGIC32 = 0xFEEDFACE;
   public static final int MAC_CIGAM32 = 0xCEFAEDFE;
   public static final int MAC_MAGIC64 = 0xFEEDFACF;
   public static final int MAC_CIGAM64 = 0xCFFAEDFE;
   public static final int ZSTD_MAGIC = 0x28B52FFD;
   public static final int BROTLI_MAGIC = 0x81CFB2CE;
   public static final int RIFF_MAGIC = 0x04524946;
   public static final int CAB_MAGIC = 0x4D534346;
   
   public static final int BZIP2_MAGIC = 0x425A68;
   
   public static final int GZIP_MAGIC = 0x1F8B;
   public static final int BMP_MAGIC = 0x424D;
   public static final int WIN_MAGIC = 0x4D5A;
   public static final int PBM_MAGIC = 0x5034; // bin only       
   public static final int PGM_MAGIC = 0x5035; // bin only
   public static final int PPM_MAGIC = 0x5036; // bin only


   private static final int[] KEYS32 =
   { 
      GIF_MAGIC, PDF_MAGIC, ZIP_MAGIC, LZMA_MAGIC, PNG_MAGIC,
      ELF_MAGIC, MAC_MAGIC32, MAC_CIGAM32, MAC_MAGIC64, MAC_CIGAM64,
      ZSTD_MAGIC, BROTLI_MAGIC, CAB_MAGIC, RIFF_MAGIC
   };

   private static final int[] KEYS16 = 
   { 
      GZIP_MAGIC, BMP_MAGIC, WIN_MAGIC
   };


   public static int getType(byte[] src, int start)
   {
      final int key = Memory.BigEndian.readInt32(src, start);

      if (((key & ~0x0F) == JPG_MAGIC) || ((key >> 8) == BZIP2_MAGIC))
         return key;

      for (int i=0; i<KEYS32.length; i++) 
      {
         if (key == KEYS32[i])
            return key;
      }

      final int key16 = key >> 16;
      
      for (int i=0; i<KEYS16.length; i++) 
      {
         if (key16 == KEYS16[i])
            return key16;  
      }     
      
      if ((key16 == PBM_MAGIC) || (key16 == PGM_MAGIC) || (key16 == PPM_MAGIC)) 
      {
         final int subkey = (key >> 8) & 0xFF;

         if ((subkey == 0x07) || (subkey == 0x0A) || (subkey == 0x0D) || (subkey == 0x20))
            return key16;
      }      

      return NO_MAGIC;      
   }
   
   
   public static boolean isCompressed(int magic) 
   {
      switch (magic) 
      {
         case JPG_MAGIC:
         case GIF_MAGIC:
         case PNG_MAGIC:
         case RIFF_MAGIC:
         case LZMA_MAGIC:
         case ZSTD_MAGIC:
         case BROTLI_MAGIC:
         case CAB_MAGIC:
         case ZIP_MAGIC:
         case GZIP_MAGIC:
         case BZIP2_MAGIC:
            return true;

         default:
            return false;
      }
   }
   
}