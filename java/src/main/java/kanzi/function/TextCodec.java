/*
Copyright 2011-2017 Frederic Langlet
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

package kanzi.function;

import java.util.Map;
import kanzi.ByteFunction;
import kanzi.Global;
import kanzi.SliceByteArray;


// Simple one-pass text codec. Uses a default (small) static dictionary
// or potentially larger custom one. Generates a dynamic dictionary.
public final class TextCodec implements ByteFunction
{
   private static final int THRESHOLD1 = 128;
   private static final int THRESHOLD2 = THRESHOLD1 * THRESHOLD1;
   private static final int THRESHOLD3 = 32;
   private static final int THRESHOLD4 = THRESHOLD3 * 128;
   private static final int MAX_DICT_SIZE = 1 << 19;
   private static final int MAX_WORD_LENGTH = 32;
   private static final int MAX_BLOCK_SIZE = 1 << 30; //1 GB
   public static final int LOG_HASHES_SIZE = 24; // 16 MB
   public static final byte LF = 0x0A;
   public static final byte CR = 0x0D;
   public static final byte ESCAPE_TOKEN1 = (byte) 0x0F; // dictionary word preceded by space symbol
   public static final byte ESCAPE_TOKEN2 = (byte) 0x0E; // toggle upper/lower case of first word char
   private static final int HASH1 = 0x7FEB352D;
   private static final int HASH2 = 0x846CA68B;

   private static final boolean[] DELIMITER_CHARS = initDelimiterChars();

   private static boolean[] initDelimiterChars()
   {
      boolean[] res = new boolean[256];

      for (int i=0; i<256; i++)
      {
         if ((i >= ' ') && (i <= '/')) // [ !"#$%&'()*+,-./]
            res[i] = true;
         else if ((i >= ':') && (i <= '?')) // [:;<=>?]
            res[i] = true;
         else
         {
            switch (i)
            {
               case '\n' :
               case '\t' :
               case '\r' :
               case '_'  :
               case '|'  :
               case '{'  :
               case '}'  :
               case '['  :
               case ']'  :
                 res[i] = true;
                 break;
               default :
                 res[i] = false;
            }
         }
      }

      return res;
   }

   // Default dictionary
   // 1024 of the most common English words with at least 2 chars.
   // Each char is 6 bit encoded: 0 to 31. Add 32 to a letter starting a word (MSB).
   // TheBeAndOfInToHaveItThatFor...
   private static final byte[] DICT_EN_1024 = new byte[]
   {
      (byte) 0xCC, (byte) 0x71, (byte) 0x21, (byte) 0x12, (byte) 0x03, (byte) 0x43, (byte) 0xB8, (byte) 0x5A, 
      (byte) 0x0D, (byte) 0xCC, (byte) 0xED, (byte) 0x88, (byte) 0x4C, (byte) 0x7A, (byte) 0x13, (byte) 0xCC, 
      (byte) 0x70, (byte) 0x13, (byte) 0x94, (byte) 0xE4, (byte) 0x78, (byte) 0x39, (byte) 0x49, (byte) 0xC4, 
      (byte) 0x9C, (byte) 0x05, (byte) 0x44, (byte) 0xB8, (byte) 0xDC, (byte) 0x80, (byte) 0x20, (byte) 0x3C, 
      (byte) 0x80, (byte) 0x62, (byte) 0x04, (byte) 0xE1, (byte) 0x51, (byte) 0x3D, (byte) 0x84, (byte) 0x85, 
      (byte) 0x89, (byte) 0xC0, (byte) 0x0F, (byte) 0x31, (byte) 0xC4, (byte) 0x62, (byte) 0x04, (byte) 0xB6, 
      (byte) 0x39, (byte) 0x42, (byte) 0xC3, (byte) 0xD8, (byte) 0x73, (byte) 0xAE, (byte) 0x46, (byte) 0x20, 
      (byte) 0x0D, (byte) 0xB0, (byte) 0x06, (byte) 0x23, (byte) 0x3B, (byte) 0x31, (byte) 0xC8, (byte) 0x4B, 
      (byte) 0x60, (byte) 0x12, (byte) 0xA1, (byte) 0x2B, (byte) 0x14, (byte) 0x08, (byte) 0x78, (byte) 0x0D, 
      (byte) 0x62, (byte) 0x54, (byte) 0x4E, (byte) 0x32, (byte) 0xD3, (byte) 0x93, (byte) 0xC8, (byte) 0x71, 
      (byte) 0x36, (byte) 0x1C, (byte) 0x04, (byte) 0xF3, (byte) 0x1C, (byte) 0x42, (byte) 0x11, (byte) 0xD8, 
      (byte) 0x72, (byte) 0x02, (byte) 0x1E, (byte) 0x61, (byte) 0x13, (byte) 0x98, (byte) 0x85, (byte) 0x44, 
      (byte) 0x9C, (byte) 0x04, (byte) 0xA0, (byte) 0x44, (byte) 0x49, (byte) 0xC8, (byte) 0x32, (byte) 0x71, 
      (byte) 0x11, (byte) 0x88, (byte) 0xE3, (byte) 0x04, (byte) 0xB1, (byte) 0x8B, (byte) 0x94, (byte) 0x47, 
      (byte) 0x61, (byte) 0x11, (byte) 0x13, (byte) 0x62, (byte) 0x0B, (byte) 0x2F, (byte) 0x23, (byte) 0x8C, 
      (byte) 0x12, (byte) 0x11, (byte) 0x02, (byte) 0x01, (byte) 0x44, (byte) 0x84, (byte) 0xCC, (byte) 0x71, 
      (byte) 0x11, (byte) 0x13, (byte) 0x31, (byte) 0xD1, (byte) 0x39, (byte) 0x41, (byte) 0x87, (byte) 0xCC, 
      (byte) 0x42, (byte) 0xCB, (byte) 0xD8, (byte) 0x71, (byte) 0x0D, (byte) 0xD8, (byte) 0xE4, (byte) 0x4A, 
      (byte) 0xCC, (byte) 0x71, (byte) 0x0C, (byte) 0xE0, (byte) 0x44, (byte) 0xF4, (byte) 0x3E, (byte) 0xE5, 
      (byte) 0x8D, (byte) 0xB9, (byte) 0x44, (byte) 0xE8, (byte) 0x35, (byte) 0x33, (byte) 0xA9, (byte) 0x51, 
      (byte) 0x24, (byte) 0xE2, (byte) 0x39, (byte) 0x42, (byte) 0xC3, (byte) 0xB9, (byte) 0x51, (byte) 0x11, 
      (byte) 0xB8, (byte) 0xB0, (byte) 0xF3, (byte) 0x1C, (byte) 0x83, (byte) 0x4A, (byte) 0x8C, (byte) 0x06, 
      (byte) 0x36, (byte) 0x01, (byte) 0x8C, (byte) 0xC7, (byte) 0x00, (byte) 0xDA, (byte) 0xC8, (byte) 0x28, 
      (byte) 0x4B, (byte) 0x93, (byte) 0x1C, (byte) 0x44, (byte) 0x67, (byte) 0x39, (byte) 0x6C, (byte) 0xC7, 
      (byte) 0x10, (byte) 0xDA, (byte) 0x13, (byte) 0x4A, (byte) 0xF1, (byte) 0x0E, (byte) 0x3C, (byte) 0xB1, 
      (byte) 0x33, (byte) 0x58, (byte) 0xEB, (byte) 0x0E, (byte) 0x44, (byte) 0x4C, (byte) 0xC7, (byte) 0x11, 
      (byte) 0x21, (byte) 0x21, (byte) 0x10, (byte) 0x43, (byte) 0x6D, (byte) 0x39, (byte) 0x6D, (byte) 0x80, 
      (byte) 0x35, (byte) 0x39, (byte) 0x48, (byte) 0x45, (byte) 0x24, (byte) 0xED, (byte) 0x11, (byte) 0x6D, 
      (byte) 0x12, (byte) 0x13, (byte) 0x21, (byte) 0x04, (byte) 0xCC, (byte) 0x83, (byte) 0x04, (byte) 0xB0, 
      (byte) 0x03, (byte) 0x6C, (byte) 0x00, (byte) 0xD6, (byte) 0x33, (byte) 0x1C, (byte) 0x83, (byte) 0x46, 
      (byte) 0xB0, (byte) 0x02, (byte) 0x84, (byte) 0x9C, (byte) 0x44, (byte) 0x44, (byte) 0xD8, (byte) 0x42, 
      (byte) 0xCB, (byte) 0xB8, (byte) 0xD2, (byte) 0xD8, (byte) 0x9C, (byte) 0x84, (byte) 0xB5, (byte) 0x11, 
      (byte) 0x16, (byte) 0x20, (byte) 0x15, (byte) 0x31, (byte) 0x11, (byte) 0xD8, (byte) 0x84, (byte) 0xC7, 
      (byte) 0x39, (byte) 0x44, (byte) 0xE0, (byte) 0x34, (byte) 0xE4, (byte) 0xC7, (byte) 0x11, (byte) 0x1B, 
      (byte) 0x4E, (byte) 0x80, (byte) 0xB2, (byte) 0xE1, (byte) 0x10, (byte) 0xB2, (byte) 0x04, (byte) 0x54, 
      (byte) 0x48, (byte) 0x44, (byte) 0x14, (byte) 0xE4, (byte) 0x44, (byte) 0xB8, (byte) 0x51, (byte) 0x73, 
      (byte) 0x1C, (byte) 0xE5, (byte) 0x06, (byte) 0x1F, (byte) 0x23, (byte) 0xA0, (byte) 0x18, (byte) 0x02, 
      (byte) 0x0D, (byte) 0x49, (byte) 0x3D, (byte) 0x87, (byte) 0x20, (byte) 0xB1, (byte) 0x2B, (byte) 0x01, 
      (byte) 0x24, (byte) 0xF3, (byte) 0x38, (byte) 0xE8, (byte) 0xCE, (byte) 0x58, (byte) 0xDC, (byte) 0xCE, 
      (byte) 0x0C, (byte) 0x06, (byte) 0x32, (byte) 0x00, (byte) 0xC1, (byte) 0x21, (byte) 0x00, (byte) 0x22, 
      (byte) 0xB3, (byte) 0x00, (byte) 0xA1, (byte) 0x24, (byte) 0x00, (byte) 0x21, (byte) 0xE3, (byte) 0x20, 
      (byte) 0x51, (byte) 0x44, (byte) 0x44, (byte) 0x43, (byte) 0x53, (byte) 0xD8, (byte) 0x71, (byte) 0x11, 
      (byte) 0x12, (byte) 0x11, (byte) 0x13, (byte) 0x58, (byte) 0x41, (byte) 0x0D, (byte) 0xCC, (byte) 0x73, 
      (byte) 0x92, (byte) 0x12, (byte) 0x45, (byte) 0x44, (byte) 0x37, (byte) 0x21, (byte) 0x04, (byte) 0x37, 
      (byte) 0x43, (byte) 0x43, (byte) 0x11, (byte) 0x18, (byte) 0x01, (byte) 0x39, (byte) 0x44, (byte) 0xEE, 
      (byte) 0x34, (byte) 0x48, (byte) 0x0B, (byte) 0x48, (byte) 0xE9, (byte) 0x40, (byte) 0x09, (byte) 0x3B, 
      (byte) 0x14, (byte) 0x49, (byte) 0x38, (byte) 0x02, (byte) 0x4D, (byte) 0x40, (byte) 0x0B, (byte) 0x2D, 
      (byte) 0x8B, (byte) 0xD1, (byte) 0x11, (byte) 0x51, (byte) 0x0D, (byte) 0x4E, (byte) 0x45, (byte) 0xCF, 
      (byte) 0x10, (byte) 0x24, (byte) 0xE2, (byte) 0x38, (byte) 0xD4, (byte) 0xC0, (byte) 0x20, (byte) 0xD8, 
      (byte) 0x8E, (byte) 0x34, (byte) 0x21, (byte) 0x11, (byte) 0x36, (byte) 0xC1, (byte) 0x32, (byte) 0x08, 
      (byte) 0x73, (byte) 0x8E, (byte) 0x2F, (byte) 0x81, (byte) 0x00, (byte) 0x47, (byte) 0x32, (byte) 0x0F, 
      (byte) 0xAC, (byte) 0x00, (byte) 0x63, (byte) 0x50, (byte) 0x49, (byte) 0x15, (byte) 0x11, (byte) 0x1C, 
      (byte) 0xCE, (byte) 0x58, (byte) 0x04, (byte) 0x43, (byte) 0x98, (byte) 0x84, (byte) 0x4B, (byte) 0x94, 
      (byte) 0x84, (byte) 0x4C, (byte) 0x98, (byte) 0xB0, (byte) 0x12, (byte) 0x4A, (byte) 0x60, (byte) 0x12, 
      (byte) 0xA8, (byte) 0x41, (byte) 0x0F, (byte) 0xD8, (byte) 0xE4, (byte) 0x4B, (byte) 0x0F, (byte) 0x24, 
      (byte) 0xC8, (byte) 0x2C, (byte) 0xBD, (byte) 0x84, (byte) 0x35, (byte) 0x3C, (byte) 0x87, (byte) 0x39, 
      (byte) 0x42, (byte) 0xC3, (byte) 0xC8, (byte) 0xF1, (byte) 0x0D, (byte) 0x0F, (byte) 0x24, (byte) 0xC0, 
      (byte) 0x18, (byte) 0x48, (byte) 0xCE, (byte) 0x09, (byte) 0x33, (byte) 0x91, (byte) 0xB0, (byte) 0x81, 
      (byte) 0x87, (byte) 0x4E, (byte) 0x93, (byte) 0x81, (byte) 0x98, (byte) 0xE8, (byte) 0x8E, (byte) 0x35, 
      (byte) 0x32, (byte) 0x0D, (byte) 0x50, (byte) 0x49, (byte) 0x15, (byte) 0x11, (byte) 0x16, (byte) 0x0E, 
      (byte) 0x34, (byte) 0x4B, (byte) 0x44, (byte) 0x54, (byte) 0x44, (byte) 0x60, (byte) 0x35, (byte) 0x25, 
      (byte) 0x84, (byte) 0x46, (byte) 0x51, (byte) 0x16, (byte) 0xB0, (byte) 0x40, (byte) 0x0D, (byte) 0x8C, 
      (byte) 0x81, (byte) 0x45, (byte) 0x11, (byte) 0x11, (byte) 0x0D, (byte) 0x08, (byte) 0x4C, (byte) 0xC4, 
      (byte) 0x34, (byte) 0x3B, (byte) 0x44, (byte) 0x10, (byte) 0x3A, (byte) 0xC4, (byte) 0x01, (byte) 0x51, 
      (byte) 0x33, (byte) 0x45, (byte) 0x8B, (byte) 0x48, (byte) 0x08, (byte) 0x49, (byte) 0xCE, (byte) 0x2C, 
      (byte) 0x3C, (byte) 0x8E, (byte) 0x30, (byte) 0x44, (byte) 0xC7, (byte) 0x20, (byte) 0xD1, (byte) 0xA0, 
      (byte) 0x48, (byte) 0xAD, (byte) 0x80, (byte) 0x44, (byte) 0xCA, (byte) 0xC8, (byte) 0x3E, (byte) 0x23, 
      (byte) 0x95, (byte) 0x11, (byte) 0x1A, (byte) 0x12, (byte) 0x49, (byte) 0x41, (byte) 0x27, (byte) 0x00, 
      (byte) 0xF3, (byte) 0xC4, (byte) 0x37, (byte) 0x35, (byte) 0x11, (byte) 0x36, (byte) 0xB3, (byte) 0x8E, 
      (byte) 0x2B, (byte) 0x25, (byte) 0x11, (byte) 0x12, (byte) 0x32, (byte) 0x12, (byte) 0x08, (byte) 0xE5, 
      (byte) 0x44, (byte) 0x46, (byte) 0x52, (byte) 0x06, (byte) 0x1D, (byte) 0x3B, (byte) 0x00, (byte) 0x0E, 
      (byte) 0x32, (byte) 0x11, (byte) 0x10, (byte) 0x24, (byte) 0xC8, (byte) 0x38, (byte) 0xD8, (byte) 0x06, 
      (byte) 0x44, (byte) 0x41, (byte) 0x32, (byte) 0x38, (byte) 0xC1, (byte) 0x0E, (byte) 0x34, (byte) 0x49, 
      (byte) 0x40, (byte) 0x20, (byte) 0xBC, (byte) 0x44, (byte) 0x48, (byte) 0xF1, (byte) 0x02, (byte) 0x4E, 
      (byte) 0xD3, (byte) 0x93, (byte) 0x20, (byte) 0x21, (byte) 0x22, (byte) 0x1C, (byte) 0xE2, (byte) 0x02, 
      (byte) 0x12, (byte) 0x11, (byte) 0x06, (byte) 0x20, (byte) 0xDC, (byte) 0xC7, (byte) 0x44, (byte) 0x41, 
      (byte) 0x32, (byte) 0x61, (byte) 0x24, (byte) 0xC4, (byte) 0x32, (byte) 0xB1, (byte) 0x15, (byte) 0x10, 
      (byte) 0xB9, (byte) 0x44, (byte) 0x10, (byte) 0xBB, (byte) 0x04, (byte) 0x11, (byte) 0x38, (byte) 0x8E, 
      (byte) 0x30, (byte) 0xF0, (byte) 0x0D, (byte) 0x62, (byte) 0x13, (byte) 0x97, (byte) 0xC8, (byte) 0x73, 
      (byte) 0x96, (byte) 0xBC, (byte) 0xB0, (byte) 0x18, (byte) 0xAC, (byte) 0x85, (byte) 0x44, (byte) 0xAC, 
      (byte) 0x44, (byte) 0xD3, (byte) 0x11, (byte) 0x19, (byte) 0x06, (byte) 0x1A, (byte) 0xD5, (byte) 0x0C, 
      (byte) 0x04, (byte) 0x44, (byte) 0x6E, (byte) 0x3C, (byte) 0x43, (byte) 0x6F, (byte) 0x44, (byte) 0xE0, 
      (byte) 0x4B, (byte) 0x10, (byte) 0xC9, (byte) 0x40, (byte) 0x4E, (byte) 0x70, (byte) 0x0D, (byte) 0x0E, 
      (byte) 0xC1, (byte) 0x00, (byte) 0x49, (byte) 0x44, (byte) 0x44, (byte) 0xC1, (byte) 0x41, (byte) 0x12, 
      (byte) 0x4C, (byte) 0x83, (byte) 0x8D, (byte) 0x88, (byte) 0x02, (byte) 0xCB, (byte) 0xC4, (byte) 0x43, 
      (byte) 0x04, (byte) 0x30, (byte) 0x11, (byte) 0x11, (byte) 0x88, (byte) 0x44, (byte) 0x53, (byte) 0x00, 
      (byte) 0x83, (byte) 0x6F, (byte) 0x51, (byte) 0x3B, (byte) 0x44, (byte) 0x5D, (byte) 0x38, (byte) 0x87, 
      (byte) 0x00, (byte) 0x84, (byte) 0x72, (byte) 0x4C, (byte) 0x04, (byte) 0x53, (byte) 0xC5, (byte) 0x43, 
      (byte) 0x71, (byte) 0x00, (byte) 0x84, (byte) 0x84, (byte) 0x98, (byte) 0xE0, (byte) 0x0B, (byte) 0xC4, 
      (byte) 0x40, (byte) 0x0B, (byte) 0x2D, (byte) 0x89, (byte) 0xCE, (byte) 0x30, (byte) 0x4C, (byte) 0xC4, 
      (byte) 0x02, (byte) 0x20, (byte) 0x0D, (byte) 0x0C, (byte) 0x80, (byte) 0xC0, (byte) 0x4C, (byte) 0x4B, 
      (byte) 0x0E, (byte) 0x34, (byte) 0x46, (byte) 0x21, (byte) 0x51, (byte) 0x22, (byte) 0x0D, (byte) 0x11, 
      (byte) 0x24, (byte) 0xB8, (byte) 0x39, (byte) 0x43, (byte) 0x46, (byte) 0x98, (byte) 0xE3, (byte) 0x83, 
      (byte) 0x88, (byte) 0xE5, (byte) 0x11, (byte) 0x4E, (byte) 0x52, (byte) 0x0D, (byte) 0x0E, (byte) 0xA3, 
      (byte) 0x4E, (byte) 0x5A, (byte) 0xA2, (byte) 0x0D, (byte) 0x0E, (byte) 0x71, (byte) 0x0B, (byte) 0x3E, 
      (byte) 0xD2, (byte) 0x06, (byte) 0x1D, (byte) 0x38, (byte) 0x87, (byte) 0x20, (byte) 0xB0, (byte) 0xEB, 
      (byte) 0x39, (byte) 0x3E, (byte) 0x0E, (byte) 0x51, (byte) 0x1D, (byte) 0x12, (byte) 0x91, (byte) 0x81, 
      (byte) 0x38, (byte) 0x11, (byte) 0x2D, (byte) 0x8E, (byte) 0x44, (byte) 0x38, (byte) 0x48, (byte) 0x4F, 
      (byte) 0x50, (byte) 0x0D, (byte) 0xB0, (byte) 0xE3, (byte) 0x53, (byte) 0x1E, (byte) 0x70, (byte) 0x0B, 
      (byte) 0x16, (byte) 0xB3, (byte) 0x96, (byte) 0xB0, (byte) 0x82, (byte) 0xCB, (byte) 0x20, (byte) 0xE3, 
      (byte) 0x67, (byte) 0x20, (byte) 0x61, (byte) 0xEE, (byte) 0x44, (byte) 0x60, (byte) 0x0D, (byte) 0x21, 
      (byte) 0x90, (byte) 0x13, (byte) 0x20, (byte) 0xE3, (byte) 0x71, (byte) 0x10, (byte) 0x39, (byte) 0x91, 
      (byte) 0x10, (byte) 0x43, (byte) 0x61, (byte) 0x2D, (byte) 0x41, (byte) 0x36, (byte) 0x1C, (byte) 0x84, 
      (byte) 0xC4, (byte) 0x84, (byte) 0xB0, (byte) 0x02, (byte) 0x2B, (byte) 0x83, (byte) 0x94, (byte) 0x45, 
      (byte) 0x21, (byte) 0x0B, (byte) 0x16, (byte) 0x42, (byte) 0x06, (byte) 0x1D, (byte) 0x38, (byte) 0x4E, 
      (byte) 0x4C, (byte) 0x7A, (byte) 0xC8, (byte) 0x4D, (byte) 0x32, (byte) 0xC4, (byte) 0x9C, (byte) 0xE5, 
      (byte) 0x12, (byte) 0x12, (byte) 0xB1, (byte) 0x13, (byte) 0x8C, (byte) 0x44, (byte) 0x8F, (byte) 0x21, 
      (byte) 0x31, (byte) 0x2F, (byte) 0x44, (byte) 0xE5, (byte) 0x48, (byte) 0x0C, (byte) 0x4C, (byte) 0x84, 
      (byte) 0x45, (byte) 0x52, (byte) 0x02, (byte) 0x12, (byte) 0x72, (byte) 0x0C, (byte) 0x48, (byte) 0x42, 
      (byte) 0xC5, (byte) 0x95, (byte) 0x12, (byte) 0x04, (byte) 0x34, (byte) 0x38, (byte) 0xC4, (byte) 0x48, 
      (byte) 0x24, (byte) 0x48, (byte) 0x04, (byte) 0x49, (byte) 0x40, (byte) 0x4C, (byte) 0x71, (byte) 0x11, 
      (byte) 0x8C, (byte) 0x45, (byte) 0x44, (byte) 0x2C, (byte) 0xE3, (byte) 0xCC, (byte) 0x10, (byte) 0xD4, 
      (byte) 0xE0, (byte) 0x58, (byte) 0x06, (byte) 0x2A, (byte) 0x20, (byte) 0xB2, (byte) 0xF3, (byte) 0x44, 
      (byte) 0x83, (byte) 0xE7, (byte) 0x39, (byte) 0x44, (byte) 0x66, (byte) 0x00, (byte) 0xC1, (byte) 0x2E, 
      (byte) 0x15, (byte) 0x31, (byte) 0x0D, (byte) 0xBC, (byte) 0xB0, (byte) 0x0D, (byte) 0x4E, (byte) 0xF2, 
      (byte) 0xC0, (byte) 0x08, (byte) 0x49, (byte) 0x0D, (byte) 0x0E, (byte) 0x03, (byte) 0x0E, (byte) 0x34, 
      (byte) 0x6C, (byte) 0x88, (byte) 0x34, (byte) 0x21, (byte) 0x32, (byte) 0x4C, (byte) 0x03, (byte) 0x43, 
      (byte) 0x8C, (byte) 0x44, (byte) 0x88, (byte) 0x18, (byte) 0xDB, (byte) 0xC0, (byte) 0x45, (byte) 0x32, 
      (byte) 0x02, (byte) 0x50, (byte) 0xB0, (byte) 0x11, (byte) 0xC9, (byte) 0x40, (byte) 0xC3, (byte) 0x10, 
      (byte) 0xD2, (byte) 0xD8, (byte) 0xB0, (byte) 0x43, (byte) 0x01, (byte) 0x11, (byte) 0x1B, (byte) 0xC0, 
      (byte) 0x62, (byte) 0xB0, (byte) 0x16, (byte) 0x84, (byte) 0xE3, (byte) 0x8A, (byte) 0xC8, (byte) 0x82, 
      (byte) 0xC4, (byte) 0x34, (byte) 0x21, (byte) 0x20, (byte) 0x2C, (byte) 0xC3, (byte) 0x92, (byte) 0x4E, 
      (byte) 0x83, (byte) 0x42, (byte) 0x2D, (byte) 0x40, (byte) 0xC4, (byte) 0x80, (byte) 0x60, (byte) 0x08, 
      (byte) 0x36, (byte) 0x42, (byte) 0x13, (byte) 0x1C, (byte) 0x44, (byte) 0x73, (byte) 0x38, (byte) 0xE2, 
      (byte) 0xE5, (byte) 0x21, (byte) 0x51, (byte) 0x2E, (byte) 0x34, (byte) 0x21, (byte) 0x2B, (byte) 0x10, 
      (byte) 0x04, (byte) 0x93, (byte) 0x91, (byte) 0x73, (byte) 0xCB, (byte) 0x00, (byte) 0x83, (byte) 0x68, 
      (byte) 0x0C, (byte) 0x43, (byte) 0x53, (byte) 0x20, (byte) 0x56, (byte) 0x34, (byte) 0x35, (byte) 0x32, 
      (byte) 0x0B, (byte) 0xC8, (byte) 0x84, (byte) 0xC4, (byte) 0xB0, (byte) 0x83, (byte) 0x54, (byte) 0x4C, 
      (byte) 0x48, (byte) 0x8E, (byte) 0x50, (byte) 0xF2, (byte) 0xC4, (byte) 0xD8, (byte) 0x41, (byte) 0x0A, 
      (byte) 0xB0, (byte) 0x04, (byte) 0xD3, (byte) 0x11, (byte) 0x18, (byte) 0x51, (byte) 0x20, (byte) 0xD1, 
      (byte) 0xA3, (byte) 0x11, (byte) 0x30, (byte) 0x08, (byte) 0x2E, (byte) 0x83, (byte) 0x45, (byte) 0x39, 
      (byte) 0x13, (byte) 0x00, (byte) 0x4C, (byte) 0x83, (byte) 0x8D, (byte) 0xB4, (byte) 0xE4, (byte) 0xC7, 
      (byte) 0x20, (byte) 0xD1, (byte) 0xA0, (byte) 0x35, (byte) 0x84, (byte) 0xC7, (byte) 0x20, (byte) 0xD1, 
      (byte) 0xA4, (byte) 0x54, (byte) 0x44, (byte) 0x58, (byte) 0x4C, (byte) 0x72, (byte) 0x0D, (byte) 0x1A, 
      (byte) 0x01, (byte) 0x8E, (byte) 0xAC, (byte) 0x40, (byte) 0x03, (byte) 0xC8, (byte) 0xE3, (byte) 0x04, 
      (byte) 0x4C, (byte) 0x83, (byte) 0x04, (byte) 0x4B, (byte) 0x43, (byte) 0x43, (byte) 0x11, (byte) 0x14, 
      (byte) 0x93, (byte) 0x00, (byte) 0xD0, (byte) 0xF6, (byte) 0x1C, (byte) 0x44, (byte) 0xC7, (byte) 0x11, 
      (byte) 0x1B, (byte) 0x40, (byte) 0x4D, (byte) 0x44, (byte) 0x44, (byte) 0xCC, (byte) 0xE1, (byte) 0x84, 
      (byte) 0x4C, (byte) 0x71, (byte) 0x11, (byte) 0x94, (byte) 0xE2, (byte) 0xCB, (byte) 0x39, (byte) 0x6B, 
      (byte) 0xC0, (byte) 0x44, (byte) 0x43, (byte) 0x53, (byte) 0xC9, (byte) 0x33, (byte) 0x8F, (byte) 0xA0, 
      (byte) 0xD0, (byte) 0xC4, (byte) 0x10, (byte) 0x38, (byte) 0xC8, (byte) 0x14, (byte) 0x52, (byte) 0x02, 
      (byte) 0x50, (byte) 0xB4, (byte) 0xEF, (byte) 0x50, (byte) 0x12, (byte) 0xC8, (byte) 0x0A, (byte) 0x02, 
      (byte) 0xD1, (byte) 0x10, (byte) 0x00, (byte) 0xD8, (byte) 0xC8, (byte) 0xF1, (byte) 0x00, (byte) 0x2A, 
      (byte) 0xC0, (byte) 0x08, (byte) 0x35, (byte) 0x30, (byte) 0x08, (byte) 0x37, (byte) 0x11, (byte) 0x0C, 
      (byte) 0x00, (byte) 0x83, (byte) 0x67, (byte) 0x10, (byte) 0x04, (byte) 0x60, (byte) 0x2C, (byte) 0xB3, 
      (byte) 0x96, (byte) 0xB0, (byte) 0x40, (byte) 0xC8, (byte) 0x02, (byte) 0xE1, (byte) 0x45, (byte) 0x20, 
      (byte) 0x21, (byte) 0x21, (byte) 0x10, (byte) 0xD1, (byte) 0x05, (byte) 0x21, (byte) 0x38, (byte) 0xCE, 
      (byte) 0x39, (byte) 0x19, (byte) 0xD4, (byte) 0x1A, (byte) 0xF1, (byte) 0x11, (byte) 0x48, (byte) 0xE3, 
      (byte) 0x6B, (byte) 0x01, (byte) 0x31, (byte) 0x11, (byte) 0x8D, (byte) 0x44, (byte) 0x48, (byte) 0x34, 
      (byte) 0x6D, (byte) 0x80, (byte) 0x46, (byte) 0x72, (byte) 0x12, (byte) 0x4C, (byte) 0xE4, (byte) 0x58, 
      (byte) 0x81, (byte) 0x11, (byte) 0x94, (byte) 0x13, (byte) 0x62, (byte) 0x13, (byte) 0x1C, (byte) 0x83, 
      (byte) 0x72, (byte) 0x11, (byte) 0x38, (byte) 0x11, (byte) 0x4C, (byte) 0x80, (byte) 0x8B, (byte) 0x13, 
      (byte) 0x24, (byte) 0xC0, (byte) 0x4C, (byte) 0x83, (byte) 0x8D, (byte) 0xB0, (byte) 0xE4, (byte) 0x4D, 
      (byte) 0x20, (byte) 0xD1, (byte) 0xB6, (byte) 0x00, (byte) 0xB2, (byte) 0xA4, (byte) 0x54, (byte) 0x43, 
      (byte) 0x53, (byte) 0xD8, (byte) 0x83, (byte) 0x62, (byte) 0x1C, (byte) 0xE3, (byte) 0x92, (byte) 0x12, 
      (byte) 0x11, (byte) 0x07, (byte) 0x01, (byte) 0x52, (byte) 0x0E, (byte) 0x47, (byte) 0x21, (byte) 0xCE, 
      (byte) 0x39, (byte) 0x39, (byte) 0x48, (byte) 0x44, (byte) 0x49, (byte) 0x4E, (byte) 0x38, (byte) 0x3C, 
      (byte) 0xC8, (byte) 0x4C, (byte) 0xB1, (byte) 0x20, (byte) 0x44, (byte) 0xE5, (byte) 0x0D, (byte) 0x0E, 
      (byte) 0x02, (byte) 0x11, (byte) 0xCC, (byte) 0x40, (byte) 0x02, (byte) 0x1C, (byte) 0x44, (byte) 0x66, 
      (byte) 0x00, (byte) 0xFC, (byte) 0x94, (byte) 0x04, (byte) 0x91, (byte) 0x02, (byte) 0x4E, (byte) 0x43, 
      (byte) 0x4E, (byte) 0x50, (byte) 0x61, (byte) 0xEF, (byte) 0x44, (byte) 0xE5, (byte) 0x44, (byte) 0x80, 
      (byte) 0x24, (byte) 0x4E, (byte) 0x49, (byte) 0x28, (byte) 0x0B, (byte) 0x4C, (byte) 0x73, (byte) 0x94, 
      (byte) 0x18, (byte) 0x79, (byte) 0xC4, (byte) 0x00, (byte) 0x39, (byte) 0x4E, (byte) 0x39, (byte) 0x3C, 
      (byte) 0x84, (byte) 0x08, (byte) 0xE3, (byte) 0x43, (byte) 0x84, (byte) 0xE6, (byte) 0x2C, (byte) 0x00, 
      (byte) 0x83, (byte) 0x6B, (byte) 0x20, (byte) 0x48, (byte) 0x01, (byte) 0x2C, (byte) 0x48, (byte) 0x88, 
      (byte) 0x54, (byte) 0x82, (byte) 0xF3, (byte) 0x00, (byte) 0x12, (byte) 0xC4, (byte) 0xAC, (byte) 0xE5, 
      (byte) 0x44, (byte) 0xBD, (byte) 0x13, (byte) 0x82, (byte) 0x11, (byte) 0x24, (byte) 0xAE, (byte) 0x14, 
      (byte) 0x51, (byte) 0x11, (byte) 0xC9, (byte) 0x35, (byte) 0x03, (byte) 0x10, (byte) 0xD4, (byte) 0xE2, 
      (byte) 0x38, (byte) 0xD4, (byte) 0x88, (byte) 0x0C, (byte) 0x44, (byte) 0x60, (byte) 0x3C, (byte) 0xF1, 
      (byte) 0x00, (byte) 0x47, (byte) 0x24, (byte) 0xD4, (byte) 0x0D, (byte) 0x88, (byte) 0x54, (byte) 0x62, 
      (byte) 0xD1, (byte) 0x00, (byte) 0x44, (byte) 0xB6, (byte) 0x27, (byte) 0x50, (byte) 0xC0, (byte) 0x0D, 
      (byte) 0x91, (byte) 0x52, (byte) 0x03, (byte) 0x10, (byte) 0xD0, (byte) 0x84, (byte) 0xCC, (byte) 0x45, 
      (byte) 0xD3, (byte) 0xB0, (byte) 0x44, (byte) 0xC7, (byte) 0x38, (byte) 0x3A, (byte) 0x0D, (byte) 0x08, 
      (byte) 0xB5, (byte) 0x03, (byte) 0x20, (byte) 0xD1, (byte) 0xB2, (byte) 0x10, (byte) 0xD0, (byte) 0xF1, 
      (byte) 0x10, (byte) 0x02, (byte) 0xC8, (byte) 0x64, (byte) 0x4C, (byte) 0x84, (byte) 0x35, (byte) 0x21, 
      (byte) 0x21, (byte) 0x50, (byte) 0x82, (byte) 0xC3, (byte) 0x88, (byte) 0xE3, (byte) 0x53, (byte) 0x44, 
      (byte) 0xE2, (byte) 0xE0, (byte) 0x50, (byte) 0x32, (byte) 0x04, (byte) 0x34, (byte) 0x21, (byte) 0x32, 
      (byte) 0x11, (byte) 0x51, (byte) 0x11, (byte) 0x00, (byte) 0xB8, (byte) 0x94, (byte) 0x4E, (byte) 0x23, 
      (byte) 0x8B, (byte) 0x2C, (byte) 0x41, (byte) 0x84, (byte) 0xA0, (byte) 0xD4, (byte) 0xC4, (byte) 0x44, 
      (byte) 0x44, (byte) 0x93, (byte) 0xC9, (byte) 0x40, (byte) 0x82, (byte) 0x11, (byte) 0x24, (byte) 0xB2, 
      (byte) 0x3C, (byte) 0x40, (byte) 0x88, (byte) 0x00, (byte) 0xBC, (byte) 0x48, (byte) 0x48, (byte) 0xA9, 
      (byte) 0x17, (byte) 0x3C, (byte) 0x44, (byte) 0x48, (byte) 0x10, (byte) 0xD0, (byte) 0x84, (byte) 0x84, 
      (byte) 0x41, (byte) 0xC8, (byte) 0x34, (byte) 0x38, (byte) 0x44, (byte) 0x4D, (byte) 0x31, (byte) 0x11, 
      (byte) 0xC4, (byte) 0x44, (byte) 0x94, (byte) 0x2D, (byte) 0x3C, (byte) 0xD1, (byte) 0x10, (byte) 0x04, 
      (byte) 0xF2, (byte) 0x21, (byte) 0x7C, (byte) 0x44, (byte) 0x2C, (byte) 0x04, (byte) 0xC8, (byte) 0x38, 
      (byte) 0xD4, (byte) 0x87, (byte) 0x20, (byte) 0xF8, (byte) 0x0D, (byte) 0x20, (byte) 0xC0, (byte) 0x0B, 
      (byte) 0xA0, (byte) 0xC3, (byte) 0xD1, (byte) 0x39, (byte) 0x51, (byte) 0x27, (byte) 0x00, (byte) 0x84, 
      (byte) 0x72, (byte) 0x4C, (byte) 0x06, (byte) 0x33, (byte) 0x38, (byte) 0xFC, (byte) 0x44, (byte) 0x0D, 
      (byte) 0x40, (byte) 0x84, (byte) 0xBC, (byte) 0x44, (byte) 0x47, (byte) 0x00, (byte) 0xF4, (byte) 0xAB, 
      (byte) 0x01, (byte) 0x31, (byte) 0x36, (byte) 0x44, (byte) 0x84, (byte) 0xC4, (byte) 0x46, (byte) 0xF2, 
      (byte) 0x02, (byte) 0x2A, (byte) 0x42, (byte) 0xD2, (byte) 0x13, (byte) 0x22, (byte) 0x06, (byte) 0x34, 
      (byte) 0x81, (byte) 0x48, (byte) 0x08, (byte) 0x03, (byte) 0x53, (byte) 0x88, (byte) 0x70, (byte) 0x0D, 
      (byte) 0x08, (byte) 0x49, (byte) 0xCE, (byte) 0x4C, (byte) 0x42, (byte) 0xE6, (byte) 0x10, (byte) 0xD1, 
      (byte) 0x11, (byte) 0x00, (byte) 0xBC, (byte) 0x4E, (byte) 0x08, (byte) 0xAC, (byte) 0x44, (byte) 0x41, 
      (byte) 0x42, (byte) 0x11, (byte) 0x12, (byte) 0x02, (byte) 0xCE, (byte) 0x34, (byte) 0x69, (byte) 0x48, 
      (byte) 0x4F, (byte) 0x31, (byte) 0xC4, (byte) 0x31, (byte) 0x21, (byte) 0x0B, (byte) 0x54, (byte) 0x44, 
      (byte) 0xB1, (byte) 0x10, (byte) 0xF3, (byte) 0x91, (byte) 0x4E, (byte) 0x23, (byte) 0x8D, (byte) 0x0C, 
      (byte) 0x84, (byte) 0xC8, (byte) 0x38, (byte) 0xDC, (byte) 0x44, (byte) 0x00, (byte) 0x21, (byte) 0xF3, 
      (byte) 0x45, (byte) 0x44, (byte) 0xC7, (byte) 0x90, (byte) 0x51, (byte) 0x4E, (byte) 0x45, (byte) 0x38, 
      (byte) 0xC4, (byte) 0x08, (byte) 0x80, (byte) 0xC4, (byte) 0xC4, (byte) 0x04, (byte) 0xC4, (byte) 0x90, 
      (byte) 0x35, (byte) 0x02, (byte) 0x01, (byte) 0x32, (byte) 0x0E, (byte) 0x36, (byte) 0x53, (byte) 0x91, 
      (byte) 0x08, (byte) 0x49, (byte) 0x80, (byte) 0x44, (byte) 0x31, (byte) 0x0D, (byte) 0x8D, (byte) 0x15, 
      (byte) 0x06, (byte) 0xAC, (byte) 0x40, (byte) 0x03, (byte) 0x11, (byte) 0x1D, (byte) 0x4E, (byte) 0x20, 
      (byte) 0x21, (byte) 0x30, (byte) 0x50, (byte) 0x84, (byte) 0xC4, (byte) 0xD8, (byte) 0x73, (byte) 0x8B, 
      (byte) 0x13, (byte) 0x21, (byte) 0x04, (byte) 0x32, (byte) 0xC2, (byte) 0x0D, (byte) 0x0E, (byte) 0x52, 
      (byte) 0x0D, (byte) 0x00, (byte) 0xB2, (byte) 0xD8, (byte) 0xC8, (byte) 0x84, (byte) 0x71, (byte) 0x11, 
      (byte) 0x35, (byte) 0x11, (byte) 0x36, (byte) 0x54, (byte) 0x44, (byte) 0x13, (byte) 0x24, (byte) 0xCE, 
      (byte) 0x45, (byte) 0x8C, (byte) 0x44, (byte) 0x48, (byte) 0xF3, (byte) 0x8D, (byte) 0x0E, (byte) 0xF5, 
      (byte) 0x12, (byte) 0x1E, (byte) 0x00, (byte) 0x82, (byte) 0x39, (byte) 0x10, (byte) 0xC8, (byte) 0x34, 
      (byte) 0x68, (byte) 0x51, (byte) 0x39, (byte) 0x31, (byte) 0xC4, (byte) 0x46, (byte) 0xB1, (byte) 0x00, 
      (byte) 0x44, (byte) 0xDC, (byte) 0x8E, (byte) 0x36, (byte) 0x73, (byte) 0x8F, (byte) 0x12, (byte) 0x31, 
      (byte) 0x15, (byte) 0x10, (byte) 0xB3, (byte) 0x8F, (byte) 0x94, (byte) 0x41, (byte) 0x0B, (byte) 0x20, 
      (byte) 0xD1, (byte) 0xB1, (byte) 0x10, (byte) 0x00, (byte) 0xE2, (byte) 0x01, (byte) 0x14, (byte) 0x58, 
      (byte) 0x8C, (byte) 0x84, (byte) 0x84, (byte) 0x01, (byte) 0x21, (byte) 0x31, (byte) 0x38, (byte) 0x00, 
      (byte) 0xF5, (byte) 0x01, (byte) 0x12, (byte) 0x0E, (byte) 0x51, (byte) 0x28, (byte) 0x40, (byte) 0x2C, 
      (byte) 0xB8, (byte) 0x80, (byte) 0x48, (byte) 0x4B, (byte) 0x8F, (byte) 0x11, (byte) 0x10, (byte) 0x13, 
      (byte) 0x20, (byte) 0xE3, (byte) 0x62, (byte) 0x2C, (byte) 0xE4, (byte) 0x84, (byte) 0xD4, (byte) 0x84, 
      (byte) 0x88, (byte) 0x4F, (byte) 0x11, (byte) 0x02, (byte) 0x10, (byte) 0x85, (byte) 0x44, (byte) 0x85, 
      (byte) 0x42, (byte) 0x0B, (byte) 0x0C, (byte) 0x83, (byte) 0x46, (byte) 0xD4, (byte) 0x02, (byte) 0xD4, 
      (byte) 0x13, (byte) 0x11, (byte) 0x12, (byte) 0x10, (byte) 0x04, (byte) 0x42, (byte) 0x1E, (byte) 0x55, 
      (byte) 0x0B, (byte) 0x2E, (byte) 0xC3, (byte) 0x83, (byte) 0x10, (byte) 0xBA, (byte) 0x4E, (byte) 0x20, 
      (byte) 0xDC, (byte) 0x84, (byte) 0x01, (byte) 0x23, (byte) 0x8D, (byte) 0xCC, (byte) 0x05, (byte) 0xE3, 
      (byte) 0x21, (byte) 0x11, (byte) 0x02, (byte) 0x4C, (byte) 0xE4, (byte) 0x6F, (byte) 0x39, (byte) 0x22, 
      (byte) 0x13, (byte) 0x20, (byte) 0xE3, (byte) 0x6F, (byte) 0x2C, (byte) 0x06, (byte) 0x04, (byte) 0x47, 
      (byte) 0x23, (byte) 0xCE, (byte) 0x45, (byte) 0x39, (byte) 0x11, (byte) 0x44, (byte) 0xE4, (byte) 0x71, 
      (byte) 0x10, (byte) 0x23, (byte) 0x91, (byte) 0x0F, (byte) 0x13, (byte) 0x96, (byte) 0x8C, (byte) 0x04, 
      (byte) 0xC0, (byte) 0xBC, (byte) 0x03, (byte) 0xC4, (byte) 0x47, (byte) 0x31, (byte) 0xC4, (byte) 0x39, 
      (byte) 0x16, (byte) 0x32, (byte) 0x3C, (byte) 0x00, (byte) 0x84, (byte) 0x91, (byte) 0x51, (byte) 0x11, 
      (byte) 0x62, (byte) 0x53, (byte) 0x91, (byte) 0x33, (byte) 0x25, (byte) 0x0F, (byte) 0x3C, (byte) 0xE4, 
      (byte) 0x53, (byte) 0x80, (byte) 0x24, (byte) 0xC8, (byte) 0x38, (byte) 0xDB, (byte) 0x85, (byte) 0x14, 
      (byte) 0x80, (byte) 0x88, (byte) 0x00, (byte) 0xBD, (byte) 0x87, (byte) 0x39, (byte) 0x21, (byte) 0x28, 
      (byte) 0x0C, (byte) 0x40, (byte) 0x27, (byte) 0x00, (byte) 0xF3, (byte) 0xD8, (byte) 0x9C, (byte) 0x40, 
      (byte) 0x11, (byte) 0x4E, (byte) 0x11, (byte) 0x12, (byte) 0x4F, (byte) 0x31, (byte) 0x00, (byte) 0x32, 
      (byte) 0xF4, (byte) 0x4E, (byte) 0x24, (byte) 0x40, (byte) 0x93, (byte) 0x9C, (byte) 0x84, (byte) 0xE1, 
      (byte) 0x01, (byte) 0x21, (byte) 0x31, (byte) 0x10, (byte) 0xF4, (byte) 0x44, (byte) 0x48, (byte) 0x43, 
      (byte) 0x53, (byte) 0xCC, (byte) 0xE5, (byte) 0x8D, (byte) 0xBD, (byte) 0x42, (byte) 0xCB, (byte) 0x85, 
      (byte) 0x44, (byte) 0xAC, (byte) 0x00, (byte) 0xF8, (byte) 0xD1, (byte) 0x62, (byte) 0xC3, (byte) 0x8C, 
      (byte) 0x88, (byte) 0x04, (byte) 0xE3, (byte) 0x00, (byte) 0x3C, (byte) 0x4E, (byte) 0x38, (byte) 0xCC, 
      (byte) 0x8C, (byte) 0x20, (byte) 0xB1, (byte) 0x25, (byte) 0x20, (byte) 0x42, (byte) 0xC3, (byte) 0xA0, 
      (byte) 0xC3, (byte) 0xC0, (byte) 0x09, (byte) 0x39, (byte) 0x54, (byte) 0x34, (byte) 0x3A, (byte) 0xC0, 
      (byte) 0x44, (byte) 0x61, (byte) 0x23, (byte) 0x38, (byte) 0x69, (byte) 0xD4, (byte) 0x18, (byte) 0x4B, 
      (byte) 0xD1, (byte) 0x10, (byte) 0xF0, (byte) 0x11, (byte) 0x12, (byte) 0x43, (byte) 0x55, (byte) 0x21, 
      (byte) 0x13, (byte) 0x8D, (byte) 0x30, (byte) 0x43, (byte) 0x53, (byte) 0x00, (byte) 0xBB, (byte) 0xD1, 
      (byte) 0x38, (byte) 0x35, (byte) 0x02, (byte) 0x12, (byte) 0x71, (byte) 0x11, (byte) 0x48, (byte) 0x42, 
      (byte) 0xC5, (byte) 0xCC, (byte) 0x40, (byte) 0x02, (byte) 0x1E, (byte) 0xE2, (byte) 0x0B, (byte) 0xC9, 
      (byte) 0x40, (byte) 0x87, (byte) 0xC8, (byte) 0x84, (byte) 0xD4, (byte) 0x01, (byte) 0x32, (byte) 0x0E, 
      (byte) 0x37, (byte) 0x32, (byte) 0x04, (byte) 0x88, (byte) 0xE4, (byte) 0x93, (byte) 0xA0, (byte) 0xD0, 
      (byte) 0xD4, (byte) 0x49, (byte) 0x34, (byte) 0x58, (byte) 0xC8, (byte) 0xA2, (byte) 0x0D, (byte) 0xC9, 
      (byte) 0x34, (byte) 0x44, (byte) 0x11, (byte) 0x3A, (byte) 0x0C, (byte) 0x00, (byte) 0x61, (byte) 0x28, 
      (byte) 0x4D, (byte) 0x21, (byte) 0x0B, (byte) 0x16, (byte) 0xF1, (byte) 0xCE, (byte) 0x34, (byte) 0x4B, 
      (byte) 0xD1, (byte) 0x20, (byte) 0x21, (byte) 0x36, (byte) 0x10, (byte) 0x04, (byte) 0x6C, (byte) 0x39, 
      (byte) 0x24, (byte) 0xF2, (byte) 0x50, (byte) 0xDC, (byte) 0x8E, (byte) 0x38, (byte) 0xD8, (byte) 0x8B, 
      (byte) 0x10, (byte) 0x04, (byte) 0x6F, (byte) 0x44, (byte) 0x00, (byte) 0x93, (byte) 0x20, (byte) 0x21, 
      (byte) 0x2F, (byte) 0x20, (byte) 0x40, (byte) 0x84, (byte) 0xD8, (byte) 0x02, (byte) 0x13, (byte) 0xC4, 
      (byte) 0x40, (byte) 0x84, (byte) 0x35, (byte) 0x3A, (byte) 0x0C, (byte) 0x3C, (byte) 0xE4, (byte) 0x53, 
      (byte) 0x00, (byte) 0xD4, (byte) 0xEF, (byte) 0x44, (byte) 0xE0, (byte) 0xD4, (byte) 0x09, (byte) 0x3A, 
      (byte) 0xC4, (byte) 0x15, (byte) 0x3D, (byte) 0x80, (byte) 0x2C, (byte) 0xBC, (byte) 0x84, (byte) 0x44, 
      (byte) 0x81, (byte) 0x12, (byte) 0xB4, (byte) 0x45, (byte) 0x92, (byte) 0xC8, (byte) 0x70, (byte) 0x11, 
      (byte) 0x12, (byte) 0xC3, (byte) 0x95, (byte) 0x20, (byte) 0x4A, (byte) 0x88, (byte) 0x0E, (byte) 0xD3, 
      (byte) 0x91, (byte) 0xC8, (byte) 0x83, (byte) 0x0F, (byte) 0x2D, (byte) 0x8D, (byte) 0x88, (byte) 0x14, 
      (byte) 0x4B, (byte) 0x8D, (byte) 0x4C, (byte) 0xE8, (byte) 0x80, (byte) 0x4C, (byte) 0x21, (byte) 0xEC, 
      (byte) 0x61, (byte) 0x21, (byte) 0x0B, (byte) 0x16, (byte) 0x52, (byte) 0x0D, (byte) 0x12, (byte) 0x23, 
      (byte) 0x8C, (byte) 0x3D, (byte) 0x44, (byte) 0xC4, (byte) 0x47, (byte) 0x23, (byte) 0x8D, (byte) 0x1A, 
      (byte) 0x04, (byte) 0xD3, (byte) 0x10, (byte) 0xD4, (byte) 0xC8, (byte) 0x38, (byte) 0xD8, (byte) 0xD1, 
      (byte) 0x01, (byte) 0x69, (byte) 0x48, (byte) 0x2C, (byte) 0xCC, (byte) 0x44, (byte) 0x3D, (byte) 0x40, 
      (byte) 0x4B, (byte) 0x20, (byte) 0x20, (byte) 0x0D, (byte) 0xC8, (byte) 0x40, (byte) 0x94, (byte) 0x44, 
      (byte) 0x84, (byte) 0xD8, (byte) 0xC8, (byte) 0x23, (byte) 0x91, (byte) 0x13, (byte) 0x31, (byte) 0x12, 
      (byte) 0x4F, (byte) 0x24, (byte) 0xCE, (byte) 0x08, (byte) 0xAB, (byte) 0xCE, (byte) 0x48, (byte) 0x84, 
      (byte) 0xC8, (byte) 0x54, (byte) 0x48, (byte) 0x80, (byte) 0x51, (byte) 0x21, (byte) 0x22, (byte) 0x10, 
      (byte) 0xD4, (byte) 0xD4, (byte) 0x45, (byte) 0x8D, (byte) 0x88, (byte) 0x34, (byte) 0x33, (byte) 0x96, 
      (byte) 0xB0, (byte) 0x43, (byte) 0x0E, (byte) 0x45, (byte) 0x89, (byte) 0x17, (byte) 0x21, (byte) 0x24, 
      (byte) 0xEB, (byte) 0x21, (byte) 0x24, (byte) 0xC4, (byte) 0x37, (byte) 0x24, (byte) 0xD1, (byte) 0x00, 
      (byte) 0x81, (byte) 0x87, (byte) 0x4E, (byte) 0x25, (byte) 0x0B, (byte) 0x4D, (byte) 0x44, (byte) 0x44, 
      (byte) 0x84, (byte) 0x82, (byte) 0xCB, (byte) 0x20, (byte) 0xE3, (byte) 0x65, (byte) 0x39, (byte) 0x13, 
      (byte) 0x04, (byte) 0x46, (byte) 0x31, (byte) 0x02, (byte) 0x21, (byte) 0x22, (byte) 0x0E, (byte) 0x36, 
      (byte) 0x43, (byte) 0x44, (byte) 0x44, (byte) 0x66, (byte) 0x2C, (byte) 0x39, (byte) 0x51, (byte) 0x32, 
      (byte) 0x50, (byte) 0xC3, (byte) 0x04, (byte) 0x47, (byte) 0x63, (byte) 0x8D, (byte) 0x0C, (byte) 0x44, 
      (byte) 0x71, (byte) 0x10, (byte) 0xB0, (byte) 0x13, (byte) 0x12, (byte) 0x05, (byte) 0x40, (byte) 0x20, 
      (byte) 0xB0, (byte) 0x01, (byte) 0x2C, (byte) 0x4A, (byte) 0xC8, (byte) 0x34, (byte) 0x4A, (byte) 0xC8, 
      (byte) 0x28, (byte) 0x42, (byte) 0xD8, (byte) 0xB9, (byte) 0x44, (byte) 0xD2, (byte) 0x20, (byte) 0x31, 
      (byte) 0x32, (byte) 0x1C, (byte) 0xE4, (byte) 0xF2, (byte) 0x1C, (byte) 0xE4, (byte) 0x53, (byte) 0x88, 
      (byte) 0xE5, (byte) 0x0D, (byte) 0x4D, (byte) 0x16, (byte) 0x31, (byte) 0x38, (byte) 0xB1, (byte) 0x20, 
      (byte) 0x44, (byte) 0x40, (byte) 0x32, (byte) 0x20, (byte) 0xD1, (byte) 0x8B, (byte) 0x13, (byte) 0x15, 
      (byte) 0x0B, (byte) 0x12, (byte) 0x30, (byte) 0x14, (byte) 0x18, (byte) 0x74, (byte) 0xC4, (byte) 0x46, 
      (byte) 0xC0, (byte) 0x11, (byte) 0x28, (byte) 0x44, (byte) 0xE8, (byte) 0x34, (byte) 0x32, (byte) 0x02, 
      (byte) 0x01, (byte) 0x31, (byte) 0x2F, (byte) 0x44, (byte) 0x44, (byte) 0x84, (byte) 0x35, (byte) 0x3A, 
      (byte) 0xC0, (byte) 0x34, (byte) 0x38, (byte) 0x80, (byte) 0x30, (byte) 0xF0, (byte) 0x08, (byte) 0x18, 
      (byte) 0xDB, (byte) 0x00, (byte) 0x4C, (byte) 0x44, (byte) 0x48, (byte) 0x00, (byte) 0xBB, (byte) 0xCE, 
      (byte) 0x3D, (byte) 0x42, (byte) 0xC0, (byte) 0x4C, (byte) 0x83, (byte) 0x8D, (byte) 0x90, (byte) 0x23, 
      (byte) 0x8D, (byte) 0x38, (byte) 0xC6, (byte) 0x2C, (byte) 0x10, (byte) 0x32, (byte) 0x02, (byte) 0x00, 
      (byte) 0xB9, (byte) 0xCE, (byte) 0x48, (byte) 0xF2, (byte) 0x13, (byte) 0x00, (byte) 0xB8, (byte) 0x87, 
      (byte) 0x51, (byte) 0x10, (byte) 0x87, (byte) 0x99, (byte) 0x13, (byte) 0x94, (byte) 0x34, (byte) 0x3C, 
      (byte) 0xC7, (byte) 0x39, (byte) 0x44, (byte) 0x80, (byte) 0x34, (byte) 0x38, (byte) 0x14, (byte) 0x4C, 
      (byte) 0x73, (byte) 0x91, (byte) 0x21, (byte) 0x36, (byte) 0x28, (byte) 0x35, (byte) 0x24, (byte) 0xC4, 
      (byte) 0x00, (byte) 0x3C, (byte) 0x44, (byte) 0x08, (byte) 0x43, (byte) 0x53, (byte) 0x2D, (byte) 0x89, 
      (byte) 0x54, (byte) 0x4D, (byte) 0x44, (byte) 0x44, (byte) 0xD9, (byte) 0x13, (byte) 0x8D, (byte) 0x1A, 
      (byte) 0x83, (byte) 0x55, (byte) 0x38, (byte) 0xB5, (byte) 0x44, (byte) 0xAC, (byte) 0x81, (byte) 0x44, 
      (byte) 0x9C, (byte) 0x42, (byte) 0x06, (byte) 0x1D, (byte) 0x3A, (byte) 0x0D, (byte) 0x09, (byte) 0x11, 
      (byte) 0x00, (byte) 0x48, (byte) 0x4C, (byte) 0x48, (byte) 0x18, (byte) 0x74, (byte) 0xE1, (byte) 0x00, 
      (byte) 0xD2, (byte) 0xA2, (byte) 0x50, (byte) 0xB4, (byte) 0xD4, (byte) 0x44, (byte) 0x02, (byte) 0xE2, 
      (byte) 0x11, (byte) 0x14, (byte) 0xC0, (byte) 0x20, (byte) 0xD2, (byte) 0xD8, (byte) 0xD8, (byte) 0x44, 
      (byte) 0x93, (byte) 0x91, (byte) 0x71, (byte) 0x02, (byte) 0x51, (byte) 0x32, (byte) 0x15, (byte) 0x12, 
      (byte) 0x13, (byte) 0x80, (byte) 0x44, (byte) 0x3C, (byte) 0x84, (byte) 0x10, (byte) 0xAA, (byte) 0xCE, 
      (byte) 0x34, (byte) 0x6B, (byte) 0x85, (byte) 0x14, (byte) 0x80, (byte) 0x84, (byte) 0x47, (byte) 0x24, 
      (byte) 0xC0, (byte) 0x4C, (byte) 0x43, (byte) 0x04, (byte) 0x35, (byte) 0x3C, (byte) 0x44, (byte) 0x49, 
      (byte) 0x38, (byte) 0x40, (byte) 0x62, (byte) 0x31, (byte) 0x00, (byte) 0x2F, (byte) 0x63, (byte) 0x91, 
      (byte) 0x28, (byte) 0x44, (byte) 0x71, (byte) 0x11, (byte) 0x23, (byte) 0x94, (byte) 0x44, (byte) 0x21, 
      (byte) 0x33, (byte) 0x1D, (byte) 0x13, (byte) 0x96, (byte) 0x94, (byte) 0xE4, (byte) 0x56, (byte) 0x01, 
      (byte) 0x10, (byte) 0xEF, (byte) 0x38, (byte) 0xB2, (byte) 0x02, (byte) 0x63, (byte) 0x20, (byte) 0x88, 
      (byte) 0x10, (byte) 0xD0, (byte) 0x84, (byte) 0x91, (byte) 0x81, (byte) 0x12, (byte) 0x84, (byte) 0x40, 
      (byte) 0xE8, (byte) 0x4C, (byte) 0x43, (byte) 0x36, (byte) 0x10, (byte) 0x03, (byte) 0xCE, (byte) 0x36, 
      (byte) 0x52, (byte) 0x0B, (byte) 0x2E, (byte) 0xF2, (byte) 0xC0, (byte) 0x36, (byte) 0xC2, (byte) 0x0B, 
      (byte) 0x21, (byte) 0x30, (byte) 0x11, (byte) 0x62, (byte) 0x65, (byte) 0x0D, (byte) 0x9C, (byte) 0xE4, 
      (byte) 0xE7, (byte) 0x10, (byte) 0x04, (byte) 0xE0, (byte) 0x0C, (byte) 0x34, (byte) 0x44, (byte) 0x49, 
      (byte) 0x28, (byte) 0x8E, (byte) 0x2C, (byte) 0x39, (byte) 0x4E, (byte) 0x09, (byte) 0x44, (byte) 0xA5, 
      (byte) 0x39, (byte) 0x11, (byte) 0x08, (byte) 0x18, (byte) 0xDC, (byte) 0xD1, (byte) 0x10, (byte) 0x04, 
      (byte) 0xCC, (byte) 0x10, (byte) 0xD4, (byte) 0xE1, (byte) 0x2C, (byte) 0xE3, (byte) 0x83, (byte) 0xD0, 
      (byte) 0xF3, (byte) 0x8D, (byte) 0x88, (byte) 0xE5, (byte) 0x11, (byte) 0x48, (byte) 0x4C, (byte) 0xC7, 
      (byte) 0x21, (byte) 0x10, (byte) 0xF6, (byte) 0x01, (byte) 0x30, (byte) 0x87, (byte) 0x80, (byte) 0x51, 
      (byte) 0x44, (byte) 0x09, (byte) 0x39, (byte) 0x00, (byte) 0x44, (byte) 0xB6, (byte) 0x32, (byte) 0x4C, 
      (byte) 0xE4, (byte) 0x44, (byte) 0xCC, (byte) 0x75, (byte) 0x12, (byte) 0xC8, (byte) 0xE5, (byte) 0x0D, 
      (byte) 0x0E, (byte) 0x45, (byte) 0x44, (byte) 0x45, (byte) 0x85, (byte) 0x87, (byte) 0x11, (byte) 0x11, 
      (byte) 0x21, (byte) 0x00, (byte) 0x16, (byte) 0x20, (byte) 0x0C, (byte) 0xC2, (byte) 0x0D, (byte) 0x21, 
      (byte) 0x24, (byte) 0xD1, (byte) 0x01, (byte) 0x32, (byte) 0x0E, (byte) 0x36, (byte) 0xC3, (byte) 0x94, 
      (byte) 0x4C, (byte) 0x7B, (byte) 0xC0, (byte) 0x18, (byte) 0x49, (byte) 0x0D, (byte) 0x4C, (byte) 0x44, 
      (byte) 0x6F, (byte) 0x44, (byte) 0xE0, (byte) 0x40, (byte) 0x04, (byte) 0xB6, (byte) 0x2F, (byte) 0x38, 
      (byte) 0x83, (byte) 0x53, (byte) 0xC8, (byte) 0x40, (byte) 0x13, (byte) 0xB4, (byte) 0x04, (byte) 0xD4, 
      (byte) 0x44, (byte) 0x02, (byte) 0xF1, (byte) 0x00, (byte) 0x21, (byte) 0x25, (byte) 0x01, (byte) 0x18, 
      (byte) 0x87, (byte) 0x00, (byte) 0xB2, (byte) 0xC4, (byte) 0x34, (byte) 0x61, (byte) 0x2F, (byte) 0x01, 
      (byte) 0x24, (byte) 0xA0, (byte) 0x3C, (byte) 0xF2, (byte) 0xD8, (byte) 0xB0, (byte) 0x02, (byte) 0x0B, 
      (byte) 0xD1, (byte) 0x25, (byte) 0x00, (byte) 0x2C, (byte) 0xB6, (byte) 0x2C, (byte) 0x21, (byte) 0x7C, 
      (byte) 0xCE, (byte) 0x50, (byte) 0x61, (byte) 0xE2, (byte) 0x2C, (byte) 0x40, (byte) 0x11, (byte) 0x2D, 
      (byte) 0x89, (byte) 0x91, (byte) 0x39, (byte) 0x69, (byte) 0x40, (byte) 0x09, (byte) 0x33, (byte) 0x91, 
      (byte) 0xC9, (byte) 0x30, (byte) 0x13, (byte) 0x12, (byte) 0xB3, (byte) 0x82, (byte) 0x00, (byte) 0xB9, 
      (byte) 0x94, (byte) 0x62, (byte) 0x40, (byte) 0x12, (byte) 0x4F, (byte) 0x20, (byte) 0x15, (byte) 0x13, 
      (byte) 0x23, (byte) 0x94, (byte) 0x4C, (byte) 0x7C, (byte) 0x82, (byte) 0x10, (byte) 0xD1, (byte) 0x2C, 
      (byte) 0x39, (byte) 0x31, (byte) 0xC4, (byte) 0x46, (byte) 0x20, (byte) 0x11, (byte) 0x10, (byte) 0x44, 
      (byte) 0x70, (byte) 0x50, (byte) 0x80, (byte) 0x8A, (byte) 0x2D, (byte) 0x88, (byte) 0x84, (byte) 0x35, 
      (byte) 0x34, (byte) 0x40, (byte) 0x2E, (byte) 0x50, (byte) 0x02, (byte) 0x12, (byte) 0x80, (byte) 0x84, 
      (byte) 0x80, (byte) 0x13, (byte) 0x95, (byte) 0x12, (byte) 0x11, (byte) 0x18, (byte) 0x38, (byte) 0xD0, 
      (byte) 0xEF, (byte) 0x20, (byte) 0x24, (byte) 0xD4, (byte) 0x44, (byte) 0x4B, (byte) 0x44, (byte) 0x4D, 
      (byte) 0x63, (byte) 0x91, (byte) 0x2A, (byte) 0xC0, (byte) 0x0D, (byte) 0x00, (byte) 0x61, (byte) 0x0C, 
      (byte) 0x10, (byte) 0xD4, (byte) 0xE8, (byte) 0x34, (byte) 0x32, (byte) 0x15, (byte) 0x20, (byte) 0x35, 
      (byte) 0x00, (byte) 0x2E, (byte) 0x50, (byte) 0x0D, (byte) 0xC8, (byte) 0x86, (byte) 0x44, (byte) 0xC8, 
      (byte) 0xF1, (byte) 0x04, (byte) 0x0E, (byte) 0x15, (byte) 0x12, (byte) 0x63, (byte) 0x21, (byte) 0x11, 
      (byte) 0x20, (byte) 0xE5, (byte) 0x12, (byte) 0xB8, (byte) 0x20, (byte) 0x94, (byte) 0x46, (byte) 0x00, 
      (byte) 0xC3, (byte) 0xC4, (byte) 0x40, (byte) 0x03, (byte) 0x63, (byte) 0x22, (byte) 0x06, (byte) 0x36, 
      (byte) 0x23, (byte) 0x8B, (byte) 0x2C, (byte) 0x40, (byte) 0x93, (byte) 0x20, (byte) 0xE3, (byte) 0x6B, 
      (byte) 0x21, (byte) 0x24, (byte) 0xE0, (byte) 0x3C, (byte) 0xF4, (byte) 0x4E, (byte) 0x00, (byte) 0x21, 
      (byte) 0xE2, (byte) 0x1C, (byte) 0x04, (byte) 0x46, (byte) 0x13, (byte) 0x05, (byte) 0x00, (byte) 0x2C, 
      (byte) 0x84, (byte) 0xD8, (byte) 0xBD, (byte) 0x11, (byte) 0x12, (byte) 0x49, (byte) 0x44, (byte) 0x44, 
      (byte) 0xD4, (byte) 0xE4, (byte) 0xC4, (byte) 0xB4, (byte) 0xE4, (byte) 0xC4, (byte) 0xBC, (byte) 0x04, 
      (byte) 0x53, (byte) 0xC4, (byte) 0x40, (byte) 0x0B, (byte) 0xD8, (byte) 0x40, (byte) 0x62, (byte) 0x51, 
      (byte) 0x14, (byte) 0x44, (byte) 0x35, (byte) 0x38, (byte) 0xC4, (byte) 0x4C, (byte) 0x44, (byte) 0x4C, 
      (byte) 0x20, (byte) 0xD1, (byte) 0x33, (byte) 0x45, (byte) 0x41, (byte) 0x32, (byte) 0x00, (byte) 0x3D, 
      (byte) 0x87, (byte) 0x01, (byte) 0x31, (byte) 0x15, (byte) 0x11, (byte) 0x18, (byte) 0x51, (byte) 0x10, 
      (byte) 0x02, (byte) 0xB6, (byte) 0x39, (byte) 0x14, (byte) 0x58, (byte) 0x89, (byte) 0x43, (byte) 0xEF, 
      (byte) 0x01, (byte) 0x14, (byte) 0xC8, (byte) 0x09, (byte) 0x42, (byte) 0xC0, (byte) 0x44, (byte) 0xB6, 
      (byte) 0x20, (byte) 0x30, (byte) 0xE5, (byte) 0x0D, (byte) 0x4E, (byte) 0x00, (byte) 0x48, (byte) 0x2C, 
      (byte) 0x84, (byte) 0xD8, (byte) 0x90, (byte) 0x04, (byte) 0xF1, (byte) 0x10, (byte) 0x23, (byte) 0x86, 
      (byte) 0x34, (byte) 0x86, (byte) 0x44, (byte) 0xC8, (byte) 0x84, (byte) 0xE2, (byte) 0x1C, (byte) 0x04, 
      (byte) 0x40, (byte) 0x09, (byte) 0x31, (byte) 0x11, (byte) 0xC8, (byte) 0xE3, (byte) 0x04, (byte) 0x04, 
      (byte) 0xE0, (byte) 0xD8, (byte) 0xAC, (byte) 0xE4, (byte) 0x92, (byte) 0x8C, (byte) 0x41, (byte) 0x91, 
      (byte) 0x10, (byte) 0x49, (byte) 0x05, (byte) 0x14, (byte) 0x40, (byte) 0x93, (byte) 0x81, (byte) 0x34, 
      (byte) 0xC0, (byte) 0x08, (byte) 0xAC, (byte) 0x93, (byte) 0x00, (byte) 0x51, (byte) 0x6C, (byte) 0x20, 
      (byte) 0x30, (byte) 0xCB, (byte) 0x13, (byte) 0x31, (byte) 0x0B, (byte) 0x11, (byte) 0x52, (byte) 0x12, 
      (byte) 0x20, (byte) 0xE3, (byte) 0x76, (byte) 0x1D, (byte) 0x8A, (byte) 0xC4, (byte) 0x18, (byte) 0x02, 
      (byte) 0xE2, (byte) 0x00, (byte) 0xF2, (byte) 0x13, (byte) 0x00, (byte) 0xBC, (byte) 0xD1, (byte) 0x00, 
      (byte) 0x31, (byte) 0x24, (byte) 0x2C, (byte) 0x40, (byte) 0x93, (byte) 0x20, (byte) 0xE3, (byte) 0x64, 
      (byte) 0x54, (byte) 0x44, (byte) 0x58, (byte) 0x04, (byte) 0xE0, (byte) 0xD8, (byte) 0x8D, (byte) 0x13, 
      (byte) 0x8F, (byte) 0xB0, (byte) 0x02, (byte) 0x4E, (byte) 0x47, (byte) 0x52, (byte) 0x04, (byte) 0x5B, 
      (byte) 0x24, (byte) 0xC0, (byte) 0x34, (byte) 0x30, (byte) 0x11, (byte) 0x0E, (byte) 0x12, (byte) 0x0B, 
      (byte) 0x2E, (byte) 0x43, (byte) 0x0F, (byte) 0x2C, (byte) 0xE6, (byte) 0x04, (byte) 0x12, (byte) 0x32, 
      (byte) 0x12, (byte) 0x09, (byte) 0x44, (byte) 0x92, (byte) 0x20, (byte) 0xE3, (byte) 0x6E, (byte) 0x3C, 
      (byte) 0xF3, (byte) 0x91, (byte) 0x4D, (byte) 0x43, (byte) 0x48, (byte) 0x4D, (byte) 0x88, (byte) 0x0D, 
      (byte) 0x00, (byte) 0xB6, (byte) 0x12, (byte) 0x21, (byte) 0x2C, (byte) 0xC4, (byte) 0x37, (byte) 0x25, 
      (byte) 0x06, (byte) 0x18, (byte) 0x44, (byte) 0x93, (byte) 0xAC, (byte) 0x05, (byte) 0x98, (byte) 0x11, 
      (byte) 0x19, (byte) 0xD4, (byte) 0x48, (byte) 0x10, (byte) 0x0D, (byte) 0x0F, (byte) 0x21, (byte) 0x02, 
      (byte) 0x4C, (byte) 0x83, (byte) 0x8D, (byte) 0x84, (byte) 0x40, (byte) 0x8E, (byte) 0x30, (byte) 0x4C, 
      (byte) 0x8A, (byte) 0x20, (byte) 0xB2, (byte) 0xF2, (byte) 0x21, (byte) 0x24, (byte) 0xC4, (byte) 0x47, 
      (byte) 0x24, (byte) 0xD8, (byte) 0x2C, (byte) 0x48, (byte) 0x91, (byte) 0x20, (byte) 0xC1, (byte) 0x2F, 
      (byte) 0x44, (byte) 0xE1, (byte) 0x91, (byte) 0x00, (byte) 0xC8, (byte) 0x8E, (byte) 0x30, (byte) 0xF0, 
      (byte) 0x11, (byte) 0x12, (byte) 0x20, (byte) 0x0F, (byte) 0xB0, (byte) 0x84, (byte) 0x92, (byte) 0x84, 
      (byte) 0x00, (byte) 0xF2, (byte) 0x39, (byte) 0x14, (byte) 0xF3, (byte) 0x44, (byte) 0x02, (byte) 0x0D, 
      (byte) 0x20, (byte) 0xD1, (byte) 0xA4, (byte) 0x01, (byte) 0x26, (byte) 0x2D, (byte) 0x10, (byte) 0x04, 
      (byte) 0x71, (byte) 0x10, (byte) 0x62, (byte) 0x0E, (byte) 0x37, (byte) 0x24, (byte) 0xD1, (byte) 0x01, 
      (byte) 0x31, (byte) 0x06, (byte) 0x62, (byte) 0xF5, (byte) 0x11, (byte) 0x3C, (byte) 0xE4, (byte) 0x84, 
      (byte) 0xBC, (byte) 0x44, (byte) 0x45, (byte) 0x39, (byte) 0x13, (byte) 0x33, (byte) 0x10, (byte) 0x21, 
      (byte) 0xCD, (byte) 0x38, (byte) 0xB3, (byte) 0x86, (byte) 0x62, (byte) 0x40, (byte) 0x8E, (byte) 0x34, 
      (byte) 0xE3, (byte) 0x08, (byte) 0x0A, (byte) 0x15, (byte) 0x03, (byte) 0x18, (byte) 0x44, (byte) 0xE4, 
      (byte) 0x5C, (byte) 0x03, (byte) 0x0F, (byte) 0x2C, (byte) 0x48, (byte) 0x87, (byte) 0x10, (byte) 0x22, 
      (byte) 0xA4, (byte) 0x35, (byte) 0x52, (byte) 0x11, (byte) 0x38, (byte) 0xD3, (byte) 0x04, (byte) 0x35, 
      (byte) 0x3A, (byte) 0xC4, (byte) 0x1A, (byte) 0x30, (byte) 0x11, (byte) 0x2B, (byte) 0x31, (byte) 0x11, 
      (byte) 0x33, (byte) 0x10, (byte) 0x13, (byte) 0x1C, (byte) 0x44, (byte) 0x6B, (byte) 0x01, (byte) 0x41, 
      (byte) 0x87, (byte) 0x99, (byte) 0x41, (byte) 0x12, (byte) 0x4A, (byte) 0x20, (byte) 0x11, (byte) 0xAC, 
      (byte) 0xE5, (byte) 0x84, (byte) 0x46, (byte) 0x70, (byte) 0x0D, (byte) 0x1A, (byte) 0xF0, (byte) 0x12, 
      (byte) 0x4F, (byte) 0x23, (byte) 0x82, (byte) 0x20, (byte) 0x02, (byte) 0xE5, (byte) 0x39, (byte) 0x11, 
      (byte) 0x84, (byte) 0x4E, (byte) 0x75, (byte) 0x0D, (byte) 0x0D, (byte) 0x11, (byte) 0x03, (byte) 0xC4, 
      (byte) 0x43, (byte) 0x0E, (byte) 0x54, (byte) 0x4B, (byte) 0x00, (byte) 0x34, (byte) 0x01, (byte) 0x84, 
      (byte) 0x46, (byte) 0x43, (byte) 0x49, (byte) 0x39, (byte) 0x89, (byte) 0x17, (byte) 0x00, (byte) 0x24, 
      (byte) 0xCB, (byte) 0x62, (byte) 0x32, (byte) 0x04, (byte) 0x94, (byte) 0x83, (byte) 0x40, (byte) 0x2E, 
      (byte) 0xC0, (byte) 0x18, (byte) 0x04, (byte) 0x49, (byte) 0xC4, (byte) 0x00, (byte) 0xB4, (byte) 0xC7, 
      (byte) 0x94, (byte) 0xB3, (byte) 0x8E, (byte) 0x46, (byte) 0x21, (byte) 0xC0, (byte) 0x34, (byte) 0x61, 
      (byte) 0x2B, (byte) 0x01, (byte) 0x8B, (byte) 0xCE, (byte) 0x39, (byte) 0x19, (byte) 0x54, (byte) 0x36, 
      (byte) 0x44, (byte) 0x93, (byte) 0x00, (byte) 0x12, (byte) 0xC8, (byte) 0x48, (byte) 0x7C, (byte) 0xD1, 
      (byte) 0x20, (byte) 0x02, (byte) 0xF2, (byte) 0x3D, (byte) 0x12, (byte) 0x0D, (byte) 0x1A, (byte) 0x32, 
      (byte) 0x0D, (byte) 0x34, (byte) 0x44, (byte) 0x61, (byte) 0x20, (byte) 0x6C, (byte) 0xC7, (byte) 0x00, 
      (byte) 0xD2, (byte) 0xAF, (byte) 0x44, (byte) 0xE4, (byte) 0xC4, (byte) 0x09, (byte) 0x38, (byte) 0x15, 
      (byte) 0x38, (byte) 0x80, (byte) 0xE8, (byte) 0x30, (byte) 0x01, (byte) 0x88, (byte) 0x34, (byte) 0x4C, 
      (byte) 0xCE, (byte) 0x34, (byte) 0x81, (byte) 0x87, (byte) 0x4F, (byte) 0x24, (byte) 0xC0, (byte) 0x46, 
      (byte) 0x04, (byte) 0x4C, (byte) 0x94, (byte) 0x83, (byte) 0x48, (byte) 0x48, (byte) 0x7B, (byte) 0x14, 
      (byte) 0x48, (byte) 0x80, (byte) 0xAE, (byte) 0x58, (byte) 0xD1, (byte) 0x11, (byte) 0x89, (byte) 0x16, 
      (byte) 0x20, (byte) 0x45, (byte) 0x3B, (byte) 0xD1, (byte) 0x21, (byte) 0x50, (byte) 0x13, (byte) 0x12, 
      (byte) 0xE4, (byte) 0xC7, (byte) 0x11, (byte) 0x14, (byte) 0xB2, (byte) 0x20, (byte) 0xC3, (byte) 0xCB, 
      (byte) 0x12, (byte) 0xF3, (byte) 0x8F, (byte) 0x50, (byte) 0xB0, (byte) 0x11, (byte) 0xC4, (byte) 0x41, 
      (byte) 0x4B, (byte) 0x10, (byte) 0x24, (byte) 0xE4, (byte) 0x48, (byte) 0xF1, (byte) 0x02, (byte) 0x20, 
      (byte) 0x02, (byte) 0xCB, (byte) 0x63, (byte) 0x23, (byte) 0x00, (byte) 0x2C, (byte) 0xBA, (byte) 0xC8, 
      (byte) 0x18, (byte) 0x74, (byte) 0xEC, (byte) 0x11, (byte) 0x24, (byte) 0x80, (byte) 0x18, (byte) 0x4C, 
      (byte) 0x93, (byte) 0x10, (byte) 0xFA, (byte) 0x84, (byte) 0x62, (byte) 0xF1, (byte) 0x00, (byte) 0x08, 
      (byte) 0x4B, (byte) 0xD1, (byte) 0x38, (byte) 0x64, (byte) 0x44, (byte) 0x49, (byte) 0x28, (byte) 0x40, 
      (byte) 0x37, (byte) 0x22, (byte) 0x03, (byte) 0x12, (byte) 0x64, (byte) 0x44, (byte) 0x01, (byte) 0x39, 
      (byte) 0x48, (byte) 0x5E, (byte) 0x83, (byte) 0x53, (byte) 0x11, (byte) 0x15, (byte) 0x48, (byte) 0x11, 
      (byte) 0x6B, (byte) 0x00, (byte) 0x34, (byte) 0x01, (byte) 0x84, (byte) 0xB4, (byte) 0x04, (byte) 0xC8, 
      (byte) 0x38, (byte) 0xD0, (byte) 0x0B, (byte) 0x94, (byte) 0x84, (byte) 0x87, (byte) 0xAC, (byte) 0xE4, 
      (byte) 0x84, (byte) 0x88, (byte) 0x03, (byte) 0x04, (byte) 0x44, (byte) 0x08, (byte) 0xC8, (byte) 0x48, 
      (byte) 0x25, (byte) 0x12, (byte) 0x4A, (byte) 0x44, (byte) 0x14, (byte) 0x00, (byte) 0xBD, (byte) 0x84, 
      (byte) 0x20, (byte) 0x61, (byte) 0xD3, (byte) 0xBC, (byte) 0x44, (byte) 0x45, (byte) 0x39, (byte) 0x13, 
      (byte) 0x00, (byte) 0x34, (byte) 0x21, (byte) 0x32, (byte) 0x11, (byte) 0x51, (byte) 0x0D, (byte) 0xD8, 
      (byte) 0x04, (byte) 0xC4, (byte) 0x46, (byte) 0xF4, (byte) 0x4E, (byte) 0x0D, (byte) 0x40, (byte) 0x93, 
      (byte) 0x20, (byte) 0xE3, (byte) 0x6F, (byte) 0x11, (byte) 0x14, (byte) 0x8E, (byte) 0x34, (byte) 0x02, 
      (byte) 0xE2, (byte) 0x10, (byte) 0xB2, (byte) 0xEF, (byte) 0x39, (byte) 0x61, (byte) 0x11, (byte) 0x91, 
      (byte) 0x51, (byte) 0x0D, (byte) 0x20, (byte) 0xD1, (byte) 0xA2, (byte) 0x38, (byte) 0xB3, (byte) 0x91, 
      (byte) 0xA0, (byte) 0xD4, (byte) 0x88, (byte) 0x0C, (byte) 0x48, (byte) 0x40, (byte) 0x47, (byte) 0x43, 
      (byte) 0x48, (byte) 0x4E, (byte) 0xB1, (byte) 0x12, (byte) 0x4A, (byte) 0x00, (byte) 0xD4, (byte) 0x2D, 
      (byte) 0x3D, (byte) 0x88, (byte) 0x0C, (byte) 0x4C, (byte) 0x40, (byte) 0x34, (byte) 0x61, (byte) 0x2C, 
      (byte) 0x10, (byte) 0xD4, (byte) 0xC8, (byte) 0x38, (byte) 0xD8, (byte) 0xC4, (byte) 0x10, (byte) 0xF9, 
      (byte) 0x03, (byte) 0x18, (byte) 0x4C, (byte) 0x93, (byte) 0x44, (byte) 0xE3, (byte) 0x46, (byte) 0x9C, 
      (byte) 0x04, (byte) 0x43, (byte) 0xCD, (byte) 0x13, (byte) 0x94, (byte) 0x04, (byte) 0xB1, (byte) 0x2D, 
      (byte) 0x10, (byte) 0x21, (byte) 0x12, (byte) 0x48, (byte) 0x04, (byte) 0x58, (byte) 0xC8, (byte) 0x01, 
      (byte) 0x44, (byte) 0x88, (byte) 0xE3, (byte) 0x0C, (byte) 0x38, (byte) 0xD9, (byte) 0x44, (byte) 0x01, 
      (byte) 0x19, (byte) 0x40, (byte) 0x30, (byte) 0x82, (byte) 0xD8, (byte) 0xC8, (byte) 0x40, (byte) 0x23, 
      (byte) 0x44, (byte) 0x40, (byte) 0x0C, (byte) 0x88, (byte) 0xE3, (byte) 0x45, (byte) 0x11, (byte) 0x11, 
      (byte) 0x0D, (byte) 0x08, (byte) 0x4C, (byte) 0x44, (byte) 0x3C, (byte) 0xB6, (byte) 0x2F, (byte) 0x44, 
      (byte) 0xE3, (byte) 0xC4, (byte) 0x45, (byte) 0x36, (byte) 0x2C, (byte) 0x10, (byte) 0x44, (byte) 0xC8, 
      (byte) 0x34, (byte) 0x68, (byte) 0x0B, (byte) 0x58, (byte) 0x06, (byte) 0x12, (byte) 0xC9, (byte) 0x35, 
      (byte) 0x05, (byte) 0x16, (byte) 0x01, (byte) 0x84, (byte) 0x34, (byte) 0x26, (byte) 0x23, (byte) 0x10, 
      (byte) 0x04, (byte) 0xC7, (byte) 0x99, (byte) 0x13, (byte) 0x96, (byte) 0x4C, (byte) 0x7C, (byte) 0x84, 
      (byte) 0x2C, (byte) 0xBC, (byte) 0x8E, (byte) 0x2C, (byte) 0x32, (byte) 0x04, (byte) 0x46, (byte) 0x00, 
      (byte) 0x93, (byte) 0x9C, (byte) 0x40, (byte) 0x15, (byte) 0x63, (byte) 0x61, (byte) 0x13, (byte) 0x84, 
      (byte) 0x01, (byte) 0xAC, (byte) 0x01, (byte) 0x14, (byte) 0x48, (byte) 0x00, (byte) 0x61, (byte) 0x23, 
      (byte) 0x10, (byte) 0x00, (byte) 0xF2, (byte) 0x20, (byte) 0xD1, (byte) 0xB1, (byte) 0x21, (byte) 0x21, 
      (byte) 0x23, (byte) 0x10, (byte) 0x20, (byte) 0x03, (byte) 0x13, (byte) 0x61, (byte) 0xCE, (byte) 0x32, 
      (byte) 0x52, (byte) 0x06, (byte) 0x51, (byte) 0x11, (byte) 0x2F, (byte) 0x38, (byte) 0xB2, (byte) 0x02, 
      (byte) 0x12, (byte) 0x13, (byte) 0x83, (byte) 0x62, (byte) 0xC0, (byte) 0x02, (byte) 0x1C, (byte) 0x83, 
      (byte) 0x44, (byte) 0x88, (byte) 0x04, (byte) 0xC4, (byte) 0x18, (byte) 0xE4, (byte) 0x58, (byte) 0x80, 
      (byte) 0x71, (byte) 0x00, (byte) 0x0E, (byte) 0x54, (byte) 0x4E, (byte) 0x35, (byte) 0x38, (byte) 0x80, 
      (byte) 0x44, (byte) 0x4B, (byte) 0x91, (byte) 0x0C, (byte) 0x44, (byte) 0x71, (byte) 0x10, (byte) 0x02, 
      (byte) 0xC8, (byte) 0x4D, (byte) 0x8B, (byte) 0xC0, (byte) 0x45, (byte) 0x33, (byte) 0x44, (byte) 0x47, 
      (byte) 0x80, (byte) 0x11, (byte) 0x0E, (byte) 0x11, (byte) 0x00, (byte) 0x4F, (byte) 0x52, (byte) 0x0E, 
      (byte) 0x2C, (byte) 0x43, (byte) 0x42, (byte) 0x13, (byte) 0x33, (byte) 0x93, (byte) 0x00, (byte) 0xB8, 
      (byte) 0xC4, (byte) 0x14, (byte) 0x43, (byte) 0x52, (byte) 0x13, (byte) 0x64, (byte) 0x48, (byte) 0x4C, 
      (byte) 0x48, (byte) 0x8E, (byte) 0x35, (byte) 0x25, (byte) 0x0C, (byte) 0x11, (byte) 0x18, (byte) 0x84, 
      (byte) 0x35, (byte) 0x31, (byte) 0x11, (byte) 0x99, (byte) 0x13, (byte) 0x94, (byte) 0x3F, (byte) 0x31, 
      (byte) 0xCE, (byte) 0x50, (byte) 0x61, (byte) 0xD3, (byte) 0xB0, (byte) 0xE0, (byte) 0xC4, (byte) 0x44, 
      (byte) 0xDC, (byte) 0xC0, (byte) 0x48, (byte) 0xA8, (byte) 0x8E, (byte) 0x00, (byte) 0x21, (byte) 0xF1, 
      (byte) 0x10, (byte) 0x04, (byte) 0x8E, (byte) 0x36, (byte) 0x01, (byte) 0x84, (byte) 0x94, (byte) 0x83, 
      (byte) 0x46, (byte) 0x11, (byte) 0x1C, (byte) 0x8F, (byte) 0x10, (byte) 0x22, (byte) 0x05, (byte) 0x20, 
      (byte) 0x28, (byte) 0x8E, (byte) 0x34, (byte) 0xD1, (byte) 0x02, (byte) 0x4C, (byte) 0x83, (byte) 0x8D, 
      (byte) 0xD8, (byte) 0x84, (byte) 0x87, (byte) 0xC4, (byte) 0x44, (byte) 0x8F, (byte) 0x38, (byte) 0xD4, 
      (byte) 0x84, (byte) 0xBD, (byte) 0x11, (byte) 0x13, (byte) 0x4D, (byte) 0x8B, (byte) 0x0E, (byte) 0x54, 
      (byte) 0x43, (byte) 0x04, (byte) 0x35, (byte) 0x38, (byte) 0x80, (byte) 0x44, (byte) 0x3A, (byte) 0xCE, 
      (byte) 0x1A, (byte) 0xF1, (byte) 0x0D, (byte) 0xC9, (byte) 0x43, (byte) 0x33, (byte) 0x44, (byte) 0x41, 
      (byte) 0x24, (byte) 0x35, (byte) 0x32, (byte) 0x11, (byte) 0x12, (byte) 0x22, (byte) 0x13, (byte) 0x21, 
      (byte) 0x91, (byte) 0x0D, (byte) 0xCC, (byte) 0x74, (byte) 0x4E, (byte) 0x50, (byte) 0x61, (byte) 0xCE, 
      (byte) 0x51, (byte) 0x3B, (byte) 0xC4, (byte) 0x4F, (byte) 0x22, (byte) 0x0C, (byte) 0x20, (byte) 0xB0, 
      (byte) 0x11, (byte) 0xD4, (byte) 0x80, (byte) 0x93, (byte) 0x20, (byte) 0xCB, (byte) 0x44, (byte) 0x59, 
      (byte) 0x23, (byte) 0xC0, (byte) 0x3C, (byte) 0x44, (byte) 0x73, (byte) 0x1D, (byte) 0x11, (byte) 0x00, 
      (byte) 0x4E, (byte) 0x22, (byte) 0xC0, (byte) 0x49, (byte) 0x2C, (byte) 0x87, (byte) 0x00, (byte) 0xA1, 
      (byte) 0x32, (byte) 0x39, (byte) 0x44, (byte) 0x42, (byte) 0x12, (byte) 0x00, (byte) 0x82, (byte) 0x39, 
      (byte) 0x43, (byte) 0x53, (byte) 0xBC, (byte) 0x02, (byte) 0x0D, (byte) 0x94, (byte) 0x02, (byte) 0xCB, 
      (byte) 0xC4, (byte) 0x80, (byte) 0x87, (byte) 0xBC, (byte) 0xE4, (byte) 0x92, (byte) 0x20, (byte) 0x12, 
      (byte) 0xC4, (byte) 0x80, (byte) 0x20, (byte) 0x84, (byte) 0x3D, (byte) 0x3C, (byte) 0x8E, (byte) 0x2C, 
      (byte) 0x80, (byte) 0xF3, (byte) 0x44, (byte) 0x05, (byte) 0x44, (byte) 0x2F, (byte) 0x30, (byte) 0x0B, 
      (byte) 0x2B, (byte) 0x22, (byte) 0x98, (byte) 0x89, (byte) 0x11, (byte) 0x00, (byte) 0x4C, (byte) 0x4B, 
      (byte) 0x4E, (byte) 0x34, (byte) 0x4B, (byte) 0xCB, (byte) 0x10, (byte) 0xD4, (byte) 0xD8, (byte) 0xBC, 
      (byte) 0x44, (byte) 0x48, (byte) 0x38, (byte) 0x38, (byte) 0xC4, (byte) 0x14, (byte) 0x83, (byte) 0x44, 
      (byte) 0xB4, (byte) 0xE4, (byte) 0x4C, (byte) 0x00, (byte) 0xBC, (byte) 0x44, (byte) 0x54, (byte) 0x40, 
      (byte) 0x0B, (byte) 0x8D, (byte) 0x12, (byte) 0x0D, (byte) 0x2A, (byte) 0x05, (byte) 0x13, (byte) 0x1C, 
      (byte) 0xE4, (byte) 0x72, (byte) 0x11, (byte) 0x15, (byte) 0x44, (byte) 0xB4, (byte) 0x03, (byte) 0x04, 
      (byte) 0xB0, (byte) 0xE3, (byte) 0x04, (byte) 0x35, (byte) 0x38, (byte) 0x06, (byte) 0x10, (byte) 0xD4, 
      (byte) 0xE3, (byte) 0x38, (byte) 0x25, (byte) 0x0C, (byte) 0x10, (byte) 0xD4, (byte) 0xE0, (byte) 0x09, 
      (byte) 0x32, (byte) 0x15, (byte) 0x21, (byte) 0x36, (byte) 0x20, (byte) 0x35, (byte) 0x85, (byte) 0x80, 
      (byte) 0x62, (byte) 0x01, (byte) 0x51, (byte) 0x00, (byte) 0x80, (byte) 0xF3, (byte) 0x60, (byte) 0xF1, 
      (byte) 0x20, (byte) 0x09, (byte) 0x32, (byte) 0x15, (byte) 0x13, (byte) 0x34, (byte) 0x40, (byte) 0x20, 
      (byte) 0xDA, (byte) 0x0D, (byte) 0x4C, (byte) 0x44, (byte) 0x44, (byte) 0x49, (byte) 0x32, (byte) 0x0D, 
      (byte) 0x1B, (byte) 0x10, (byte) 0x03, (byte) 0x20, (byte) 0xE8, (byte) 0xC0, (byte) 0x34, (byte) 0x61, 
      (byte) 0x11, (byte) 0x98, (byte) 0x43, (byte) 0x44, (byte) 0x44, (byte) 0x04, (byte) 0xC8, (byte) 0x38, 
      (byte) 0xDA, (byte) 0xC4, (byte) 0x00, (byte) 0x58, (byte) 0x8E, (byte) 0x3D, (byte) 0x8B, (byte) 0x00, 
      (byte) 0x4C, (byte) 0x21, (byte) 0xE2, (byte) 0x2C, (byte) 0x02, (byte) 0x0C, (byte) 0x80, (byte) 0xD6, 
      (byte) 0x0E, (byte) 0x34, (byte) 0x4C, (byte) 0x8E, (byte) 0x15, (byte) 0x35, (byte) 0x80, (byte) 0x44, 
      (byte) 0x4B, (byte) 0xC0, (byte) 0x45, (byte) 0x36, (byte) 0x23, (byte) 0x11, (byte) 0x52, (byte) 0x02, 
      (byte) 0x12, (byte) 0x23, (byte) 0x83, (byte) 0x12, (byte) 0xB0, (byte) 0x0D, (byte) 0x19, (byte) 0x40, 
      (byte) 0x06, (byte) 0x12, (byte) 0xB2, (byte) 0x0D, (byte) 0x2A, (byte) 0x73, (byte) 0x96, (byte) 0x11, 
      (byte) 0x51, (byte) 0x11, (byte) 0x88, (byte) 0xE3, (byte) 0x45, (byte) 0x21, (byte) 0x13, (byte) 0x22, 
      (byte) 0x38, (byte) 0xC3, (byte) 0x04, (byte) 0x35, (byte) 0x38, (byte) 0x88, (byte) 0x4D, (byte) 0x88, 
      (byte) 0x0D, (byte) 0x61, (byte) 0x61, (byte) 0xC4, (byte) 0x44, (byte) 0x4C, (byte) 0x8E, (byte) 0x30, 
      (byte) 0x45, (byte) 0x87, (byte) 0x11, (byte) 0x11, (byte) 0x23, (byte) 0x10, (byte) 0x10, (byte) 0x13, 
      (byte) 0x12, (byte) 0x34, (byte) 0x48, (byte) 0x54, (byte) 0x49, (byte) 0xC8, (byte) 0x18, (byte) 0x71, 
      (byte) 0x11, (byte) 0x84, (byte) 0x40, (byte) 0x14, (byte) 0x4C, (byte) 0x81, (byte) 0x54, (byte) 0x2E, 
      (byte) 0xE3, (byte) 0x4B, (byte) 0x20, (byte) 0xD1, (byte) 0x36, (byte) 0x38, (byte) 0xC0, (byte) 0x0D, 
      (byte) 0xBD, (byte) 0x12, (byte) 0x0E, (byte) 0x44, (byte) 0x84, (byte) 0xD8, (byte) 0xCD, (byte) 0x10, 
      (byte) 0x03, (byte) 0x21, (byte) 0x32, (byte) 0x0E, (byte) 0x34, (byte) 0x02, (byte) 0xE5, (byte) 0x39, 
      (byte) 0x44, (byte) 0x65, (byte) 0x20, (byte) 0xD0, (byte) 0x0D, (byte) 0x08, (byte) 0x80, (byte) 0x0B, 
      (byte) 0x79, (byte) 0xE7, (byte) 0x9E
   };

   private static final DictEntry[] STATIC_DICTIONARY = new DictEntry[1024];
   private static final int STATIC_DICT_WORDS = createDictionary(unpackDictionary32(DICT_EN_1024), STATIC_DICTIONARY, 1024, 0);

   private final ByteFunction delegate;
   
   
   public TextCodec()
   {
      this.delegate = new TextCodec1();
   }
  
   
   public TextCodec(Map<String, Object> ctx)
   {
      // Encode the word indexes as varints with a token or with a mask
      final int encodingType = (int) ctx.getOrDefault("textcodec", 1);
      this.delegate = (encodingType == 1) ? new TextCodec1(ctx) : new TextCodec2(ctx);
   }
   
   
   // Create dictionary from array of words
   private static int createDictionary(byte[] words, DictEntry[] dict, int maxWords, int startWord)
   {
      int anchor = 0;
      int h = HASH1;
      int nbWords = startWord;

      for (int i=0; ((i<words.length) && (nbWords<maxWords)); i++)
      {
         byte cur = words[i];

         if (isText(cur))
         {
            h = h*HASH1 ^ cur*HASH2;
            continue;
         }

         if ((isDelimiter(cur)) && (i>=anchor+1)) // At least 2 letters
         {
            dict[nbWords] = new DictEntry(words, anchor, h, nbWords, i-anchor);
            nbWords++;
         }

         anchor = i + 1;
         h = HASH1;
      }

      return nbWords;
   }


   private static boolean isText(byte val)
   {
      return isLowerCase(val) || isUpperCase(val);
   }


   private static boolean isLowerCase(byte val)
   {
      return (val >= 'a') && (val <= 'z');
   }


   private static boolean isUpperCase(byte val)
   {
      return (val >= 'A') && (val <= 'Z');
   }


   public static boolean isDelimiter(byte val)
   {
      return DELIMITER_CHARS[val&0xFF];
   }


   // Unpack dictionary with 32 symbols (all lowercase except first word char)
   private static byte[] unpackDictionary32(byte[] dict)
   {
      byte[] buf = new byte[dict.length*2];
      int d = 0;
      int val = 0;

      // Unpack 3 bytes into 4 6-bit symbols
      for (int i=0; i<dict.length; i++)
      {
         val = (val<<8) | (dict[i]&0xFF);

         if ((i % 3) == 2)
         {
            for (int ii=18; ii>=0; ii-=6)
            {
               int c = (val>>ii) & 0x3F;

               if (c >= 32)
                  buf[d++] = ' ';

               c &= 0x1F;

               // Ignore padding symbols (> 26 and <= 31)
               if (c <= 26)
                  buf[d++] = (byte) (c + 'a');
            }

            val = 0;
         }
      }

      buf[d] = ' '; // End
      byte[] res = new byte[d];
      System.arraycopy(buf, 1, res, 0, res.length);
      return res;
   }


   // return status (8 bits):
   // 0x80 => not text
   // 0x01 => CR+LF transform
   public static int computeStats(byte[] block, final int srcIdx, final int srcEnd)
   {
      final int[][] freqs = new int[257][256];
         
      for (int i=0; i<257; i++)
         freqs[i] = new int[256];
      
      int prv = 0;
      final int srcEnd4 = srcIdx + ((srcEnd-srcIdx) & -4);
      final int[] freqs0 = freqs[256];

      // Unroll loop
      for (int i=srcIdx; i<srcEnd4; i+=4)
      {
         final int cur0 = block[i]   & 0xFF;
         final int cur1 = block[i+1] & 0xFF;
         final int cur2 = block[i+2] & 0xFF;
         final int cur3 = block[i+3] & 0xFF;
         freqs0[cur0]++;
         freqs0[cur1]++;
         freqs0[cur2]++;
         freqs0[cur3]++;
         freqs[prv][cur0]++;
         freqs[cur0][cur1]++;
         freqs[cur1][cur2]++;
         freqs[cur2][cur3]++;
         prv = cur3;
      }

      for (int i=srcEnd4; i<srcEnd; i++)
      {
         final int cur = block[i] & 0xFF;
         freqs0[cur]++;
         freqs[prv][cur]++;
         prv = cur;
      }

      int nbTextChars = 0;

      for (int i=32; i<128; i++)
      {
         if (isText((byte) i) == true)
            nbTextChars += freqs0[i];
      }

      // Not text (crude threshold)
      if (2*nbTextChars < srcEnd-srcIdx)
         return 0x80;

      int nbBinChars = 0;

      for (int i=128; i<256; i++)
         nbBinChars += freqs0[i];

      // Not text (crude threshold)
      if (4*nbBinChars > srcEnd-srcIdx)
         return 0x80;

      // Check CR+LF matches
      int res = 0;

      if ((freqs0[CR] != 0) && (freqs0[CR] == freqs0[LF]))
      {
         res = 1;

         for (int i=0; i<256; i++)
         {
            if ((i != LF) && (freqs[CR][i]) != 0)
            {
               res = 0;
               break;
            }
         }
      }

      return res;
   }
   
   
   public static boolean sameWords(DictEntry e, byte[] src, int anchor, int length)
   {
      final byte[] buf = e.buf;

      // Skip first position (same result)
      for (int i=e.pos+1, j=anchor, l=e.pos+length; i<=l; i++, j++)
      {
         if (buf[i] != src[j])
            return false;
      }

      return true;
   }  


   @Override
   public int getMaxEncodedLength(int srcLength)
   {
      return this.delegate.getMaxEncodedLength(srcLength);
   }


   @Override
   public boolean forward(SliceByteArray src, SliceByteArray dst)
   {
      if (src.length == 0)
         return true;

      if (src.array == dst.array)
         return false;

      if (src.length > MAX_BLOCK_SIZE)
         throw new IllegalArgumentException("The max TextCodec block size is "+MAX_BLOCK_SIZE+", got "+src.length);

      return this.delegate.forward(src, dst);   
   }


   @Override
   public boolean inverse(SliceByteArray src, SliceByteArray dst)
   {
      if (src.length == 0)
         return true;

      if (src.array == dst.array)
         return false;

      if (src.length > MAX_BLOCK_SIZE)
         throw new IllegalArgumentException("The max TextCodec block size is "+MAX_BLOCK_SIZE+", got "+src.length);

      return this.delegate.inverse(src, dst);
   }
   
    
   
   // Encode word indexes using a token
   static class TextCodec1 implements ByteFunction
   {
      private final DictEntry[] dictMap;
      private DictEntry[] dictList;
      private final int staticDictSize;
      private final int logHashSize;
      private final int hashMask;
      private boolean isCRLF; // EOL = CR+LF ?
      private int dictSize;


      public TextCodec1()
      {
         this.logHashSize = LOG_HASHES_SIZE;
         this.dictSize = THRESHOLD2*4;
         this.dictMap = new DictEntry[1<<this.logHashSize];
         this.dictList = new DictEntry[this.dictSize];
         this.hashMask = (1 << this.logHashSize) - 1;        
         System.arraycopy(STATIC_DICTIONARY, 0, this.dictList, 0, Math.min(STATIC_DICTIONARY.length, this.dictSize));
         int nbWords = STATIC_DICT_WORDS;
 
         // Add special entries at end of static dictionary
         this.dictList[nbWords]   = new DictEntry(new byte[] { ESCAPE_TOKEN2 }, 0, 0, nbWords, 1);
         this.dictList[nbWords+1] = new DictEntry(new byte[] { ESCAPE_TOKEN1 }, 0, 0, nbWords+1, 1);
         this.staticDictSize = nbWords + 2;
      }


      public TextCodec1(Map<String, Object> ctx)
      {
         // Actual block size
         final int blockSize = (Integer) ctx.getOrDefault("size", 0);
         int log = (blockSize >= 1<<10)? Math.min(Global.log2(blockSize/4), 26) : 8;

         // Select an appropriate initial dictionary size
         int dSize = 1<<12;

         for (int i=14; i<=24; i+=2)
         {
            if (blockSize >= 1<<i)
               dSize <<= 1;
         }

         boolean extraPerf = (Boolean) ctx.getOrDefault("extra", false);
         final int extraMem = (extraPerf == true) ? 1 : 0;
         this.logHashSize = log + extraMem;
         this.dictSize = dSize;
         this.dictMap = new DictEntry[1<<this.logHashSize];
         this.dictList = new DictEntry[this.dictSize];
         this.hashMask = (1 << this.logHashSize) - 1;
         System.arraycopy(STATIC_DICTIONARY, 0, this.dictList, 0, Math.min(STATIC_DICTIONARY.length, this.dictSize));
         int nbWords = STATIC_DICT_WORDS;

         // Add special entries at end of static dictionary
         this.dictList[nbWords]   = new DictEntry(new byte[] { ESCAPE_TOKEN2 }, 0, 0, nbWords, 1);
         this.dictList[nbWords+1] = new DictEntry(new byte[] { ESCAPE_TOKEN1 }, 0, 0, nbWords+1, 1);
         this.staticDictSize = nbWords + 2;
      }


      private void reset()
      {
         // Clear and populate hash map
         for (int i=0; i<this.dictMap.length; i++)
            this.dictMap[i] = null;
         
         for (int i=0; i<this.staticDictSize; i++)
         {
            DictEntry e = this.dictList[i];
            this.dictMap[e.hash&this.hashMask] = e;
         }

         // Pre-allocate all dictionary entries
         for (int i=this.staticDictSize; i<this.dictSize; i++)
            this.dictList[i] = new DictEntry(null, -1, 0, i, 0);
      }
      

      @Override
      public boolean forward(SliceByteArray input, SliceByteArray output)
      {
         final int count = input.length;

         if (output.length - output.index < this.getMaxEncodedLength(count))
            return false;

         final byte[] src = input.array;
         final byte[] dst = output.array;
         int srcIdx = input.index;
         int dstIdx = output.index;
         final int srcEnd = input.index + count;
         
         int mode = computeStats(src, srcIdx, srcEnd);

         // Not text ?
         if ((mode & 0x80) != 0)
            return false;

         if (count <= 64)
         {
            for (int i=0; i<count; i++)
               dst[dstIdx++] = src[srcIdx++];

            input.index = srcIdx;
            output.index = dstIdx;
            return true;
         }

         this.reset();
         final int dstEnd = output.index + this.getMaxEncodedLength(count);
         final int dstEnd4 = dstEnd - 4;
         int emitAnchor = input.index; // never less than input.index
         int words = this.staticDictSize;

         // DOS encoded end of line (CR+LF) ?
         this.isCRLF = (mode & 0x01) != 0;
         dst[dstIdx++] = (byte) mode;

         while ((srcIdx < srcEnd) && (src[srcIdx] == ' '))
         {
            dst[dstIdx++] = ' ';
            srcIdx++;
            emitAnchor++;
         }

         int delimAnchor = isText(src[srcIdx]) ? srcIdx-1 : srcIdx; // previous delimiter

         while (srcIdx < srcEnd)
         {
            final byte cur = src[srcIdx];

            if (isText(cur))
            {
               srcIdx++;
               continue;
            }

            if ((srcIdx > delimAnchor+2) && isDelimiter(cur)) // At least 2 letters
            {
               // Compute hashes
               // h1 -> hash of word chars
               // h2 -> hash of word chars with first char case flipped
               final byte val = src[delimAnchor+1];
               final int caseFlag = isUpperCase(val) ? 32 : -32;
               int h1 = HASH1*HASH1 ^ val*HASH2;
               int h2 = HASH1*HASH1 ^ (val+caseFlag)*HASH2;

               for (int i=delimAnchor+2; i<srcIdx; i++)
               {
                  final int h = src[i] * HASH2;
                  h1 = h1*HASH1 ^ h;
                  h2 = h2*HASH1 ^ h;
               }

               // Check word in dictionary
               final int length = srcIdx - delimAnchor - 1;
               DictEntry e1 = this.dictMap[h1&this.hashMask];

               // Check for hash collisions
               if ((e1 != null) && (((e1.hash != h1) || (e1.data>>>24) != length)))
                  e1 = null;

               DictEntry e = e1;

               if (e == null)
               {
                  DictEntry e2 = this.dictMap[h2&this.hashMask];

                  if ((e2 != null) && ((e2.data>>>24) == length) && (e2.hash == h2))
                     e = e2;
               }

               if (e != null)
               {
                  if (sameWords(e, src, delimAnchor+2, length-1) == false)
                     e = null;
               }

               if (e == null)
               {
                  // Word not found in the dictionary or hash collision: add or replace word
                  if (((length > 3) || ((length > 2) && (words < THRESHOLD2))) && (length < MAX_WORD_LENGTH))
                  {
                     e = this.dictList[words];

                     if ((e.data&0x00FFFFFF) >= this.staticDictSize)
                     {
                        // Evict and reuse old entry
                        this.dictMap[e.hash&this.hashMask] = null;
                        e.buf = src;
                        e.pos = delimAnchor + 1;
                        e.hash = h1;
                        e.data = (length<<24) | words;
                     }
                     
                     this.dictMap[h1&this.hashMask] = e;
                     words++;

                     // Dictionary full ? Expand or reset index to end of static dictionary
                     if (words >= this.dictSize)
                     {
                        if (this.expandDictionary() == false)
                           words = this.staticDictSize;
                     }
                  }
               }
               else
               {              
                  // Word found in the dictionary
                  // Skip space if only delimiter between 2 word references
                  if ((emitAnchor != delimAnchor) || (src[delimAnchor] != ' '))
                     dstIdx = this.emitSymbols(src, emitAnchor, dst, dstIdx, delimAnchor, dstEnd);

                  if (dstIdx >= dstEnd4)
                     break;

                  dst[dstIdx++] = (e == e1) ? ESCAPE_TOKEN1 : ESCAPE_TOKEN2;
                  dstIdx = emitWordIndex(dst, dstIdx, e.data&0x00FFFFFF);
                  emitAnchor = delimAnchor + 1 + (e.data>>>24);
               }
            }

            // Reset delimiter position
            delimAnchor = srcIdx;
            srcIdx++;
         }

         // Emit last symbols
         dstIdx = this.emitSymbols(src, emitAnchor, dst, dstIdx, srcEnd-1, dstEnd);

         output.index = dstIdx;
         input.index = srcIdx;
         return srcIdx == srcEnd;
      }


      private boolean expandDictionary()
      {
         if (this.dictSize >= MAX_DICT_SIZE)
            return false;

         DictEntry[] newDict = new DictEntry[this.dictSize*2];
         System.arraycopy(this.dictList, 0, newDict, 0, this.dictSize);

         for (int i=this.dictSize; i<this.dictSize*2; i++)
            newDict[i] = new DictEntry(null, -1, 0, i, 0);

         this.dictList = newDict;
         this.dictSize <<= 1;
         return true;
      }


      private int emitSymbols(byte[] src, final int srcIdx, byte[] dst, int dstIdx, final int srcEnd, final int dstEnd)
      {
         for (int i=srcIdx; i<=srcEnd; i++)
         {
            if (dstIdx >= dstEnd)
               break;

            final byte cur = src[i];

            switch (cur)
            {
               case ESCAPE_TOKEN1 :
               case ESCAPE_TOKEN2 :
                  // Emit special word
                  dst[dstIdx++] = ESCAPE_TOKEN1;
                  final int idx = (cur == ESCAPE_TOKEN1) ? this.staticDictSize-1 : this.staticDictSize-2;
                  int lenIdx = 2;

                  if (idx >= THRESHOLD2)
                     lenIdx = 3;
                  else if (idx < THRESHOLD1)
                     lenIdx = 1;

                  if (dstIdx + lenIdx < dstEnd)
                     dstIdx = emitWordIndex(dst, dstIdx, idx);

                  break;

               case CR :
                  if (this.isCRLF == false)
                     dst[dstIdx++] = cur;

                  break;

               default:
                  dst[dstIdx++] = cur;
            }
         }

         return dstIdx;
      }


      private static int emitWordIndex(byte[] dst, int dstIdx, int val)
      {
         // Emit word index (varint 5 bits + 7 bits + 7 bits)
         if (val >= THRESHOLD1)
         {
            if (val >= THRESHOLD2)
               dst[dstIdx++] = (byte) (0xE0|(val>>14));

            dst[dstIdx]   = (byte) (0x80|(val>>7));
            dst[dstIdx+1] = (byte) (0x7F&val);
            return dstIdx + 2;
         }

         dst[dstIdx] = (byte) val;
         return dstIdx + 1;
      }


      @Override
      public boolean inverse(SliceByteArray input, SliceByteArray output)
      {
         final int count = input.length;
         int srcIdx = input.index;
         int dstIdx = output.index;
         final byte[] src = input.array;
         final byte[] dst = output.array;

         if (count <= 64)
         {
            for (int i=0; i<count; i++)
               dst[dstIdx++] = src[srcIdx++];

            input.index = srcIdx;
            output.index = dstIdx;
            return true;
         }

         this.reset();
         final int srcEnd = input.index + count;
         final int dstEnd = dst.length;
         int delimAnchor = isText(src[srcIdx]) ? srcIdx-1 : srcIdx; // previous delimiter
         int words = this.staticDictSize;
         boolean wordRun = false;
         final boolean _isCRLF = (src[srcIdx++] & 0x01) != 0;
         this.isCRLF = _isCRLF;

         while ((srcIdx < srcEnd) && (dstIdx < dstEnd))
         {
            byte cur = src[srcIdx];

            if (isText(cur))
            {
               dst[dstIdx] = cur;
               srcIdx++;
               dstIdx++;
               continue;
            }

            if ((srcIdx > delimAnchor+2) && isDelimiter(cur))
            {
               int h1 = HASH1;

               for (int i=delimAnchor+1; i<srcIdx; i++)
                  h1 = h1*HASH1 ^ src[i]*HASH2;

               // Lookup word in dictionary
               final int length = srcIdx - delimAnchor - 1;
               DictEntry e = this.dictMap[h1&this.hashMask];

               // Check for hash collisions
               if (e != null)
               {
                  if ((e.hash != h1) || ((e.data>>>24) != length))
                     e = null;
                  else if (sameWords(e, src, delimAnchor+2, length-1) == false)
                     e = null;
               }

               if (e == null)
               {
                  // Word not found in the dictionary or hash collision: add or replace word
                  if (((length > 3) || ((length > 2) && (words < THRESHOLD2))) && (length < MAX_WORD_LENGTH))
                  {
                     e = this.dictList[words];

                     if ((e.data&0x00FFFFFF) >= this.staticDictSize)
                     {
                        // Evict and reuse old entry
                        this.dictMap[e.hash&this.hashMask] = null;
                        e.buf = src;
                        e.pos = delimAnchor + 1;
                        e.hash = h1;
                        e.data = (length<<24) | words;
                     }

                     this.dictMap[h1&this.hashMask] = e;
                     words++;

                     // Dictionary full ? Expand or reset index to end of static dictionary
                     if (words >= this.dictSize)
                     {
                        if (this.expandDictionary() == false)
                           words = this.staticDictSize;
                     }
                  }
               }
            }

            srcIdx++;

            if ((cur == ESCAPE_TOKEN1) || (cur == ESCAPE_TOKEN2))
            {
               // Word in dictionary
               // Read word index (varint 5 bits + 7 bits + 7 bits)
               int idx = src[srcIdx++] & 0xFF;

               if ((idx&0x80) != 0)
               {
                  idx &= 0x7F;
                  int idx2 = src[srcIdx++];

                  if ((idx2&0x80) != 0)
                  {
                     idx = ((idx&0x1F)<<7) | (idx2&0x7F);
                     idx2 = src[srcIdx++];
                  }

                  idx = (idx<<7) | (idx2&0x7F);

                  if (idx >= this.dictSize)
                     break;
               }

               final DictEntry e = this.dictList[idx];
               final int length = e.data >>> 24;
               final byte[] buf = e.buf;

               // Sanity check
               if ((e.pos < 0) || (dstIdx+length >= dstEnd))
                  break;

               // Add space if only delimiter between 2 words (not an escaped delimiter)
               if ((wordRun == true) && (length > 1))
                  dst[dstIdx++] = ' ';

               // Emit word
               if (cur != ESCAPE_TOKEN2)
               {
                  dst[dstIdx++] = (byte) buf[e.pos];
               }
               else
               {
                  // Flip case of first character
                  dst[dstIdx++] = isUpperCase(buf[e.pos]) ? (byte) (buf[e.pos]+32) : (byte) (buf[e.pos]-32);
               }

               if (length > 1)
               {
                  for (int n=e.pos+1, l=e.pos+length; n<l; n++, dstIdx++)
                     dst[dstIdx] = buf[n];

                  // Regular word entry
                  wordRun = true;
                  delimAnchor = srcIdx;
               }
               else
               {
                  // Escape entry
                  wordRun = false;
                  delimAnchor = srcIdx-1;
               }
            }
            else
            {
               wordRun = false;
               delimAnchor = srcIdx-1;

               if ((_isCRLF == true) && (cur == LF))
                  dst[dstIdx++] = CR;

               dst[dstIdx++] = cur;
            }
         }

         output.index = dstIdx;
         input.index = srcIdx;
         return srcIdx == srcEnd;
      }


      @Override
      public int getMaxEncodedLength(int srcLength)
      {
         // Limit to 1 x srcLength and let the caller deal with
         // a failure when the output is too small
         return srcLength;
      }
   }

   
   // Encode word indexes using a mask (0x80)
   static class TextCodec2 implements ByteFunction
   {
      private final DictEntry[] dictMap;
      private DictEntry[] dictList;
      private final int staticDictSize;
      private final int logHashSize;
      private final int hashMask;
      private boolean isCRLF; // EOL = CR+LF ?
      private int dictSize;

      
      public TextCodec2()
      {
         this.logHashSize = LOG_HASHES_SIZE;
         this.dictSize = THRESHOLD2*4;
         this.dictMap = new DictEntry[1<<this.logHashSize];
         this.dictList = new DictEntry[this.dictSize];
         this.hashMask = (1 << this.logHashSize) - 1;
         System.arraycopy(STATIC_DICTIONARY, 0, this.dictList, 0, Math.min(STATIC_DICTIONARY.length, this.dictSize));
         this.staticDictSize = STATIC_DICT_WORDS;
      }

      
      public TextCodec2(Map<String, Object> ctx)
      {
         // Actual block size
         final int blockSize = (Integer) ctx.getOrDefault("size", 0);
         int log = (blockSize >= 1<<10)? Math.min(Global.log2(blockSize/4), 26) : 8;

         // Select an appropriate initial dictionary size
         int dSize = 1<<12;

         for (int i=14; i<=24; i+=2)
         {
            if (blockSize >= 1<<i)
               dSize <<= 1;
         }

         boolean extraPerf = (Boolean) ctx.getOrDefault("extra", false);
         final int extraMem = (extraPerf == true) ? 1 : 0;
         this.logHashSize = log + extraMem;
         this.dictSize = dSize;
         this.dictMap = new DictEntry[1<<this.logHashSize];
         this.dictList = new DictEntry[this.dictSize];
         this.hashMask = (1 << this.logHashSize) - 1;
         System.arraycopy(STATIC_DICTIONARY, 0, this.dictList, 0, Math.min(STATIC_DICTIONARY.length, this.dictSize));
         this.staticDictSize = STATIC_DICT_WORDS;
      }


      private void reset()
      {
         // Clear and populate hash map
         for (int i=0; i<this.dictMap.length; i++)
            this.dictMap[i] = null;
         
         for (int i=0; i<this.staticDictSize; i++)
         {
            DictEntry e = this.dictList[i];
            this.dictMap[e.hash&this.hashMask] = e;
         }

         // Pre-allocate all dictionary entries
         for (int i=this.staticDictSize; i<this.dictSize; i++)
            this.dictList[i] = new DictEntry(null, -1, 0, i, 0);
      }
      
      
      @Override
      public boolean forward(SliceByteArray input, SliceByteArray output)
      {
         final int count = input.length;

         if (output.length - output.index < this.getMaxEncodedLength(count))
            return false;

         final byte[] src = input.array;
         final byte[] dst = output.array;
         int srcIdx = input.index;
         int dstIdx = output.index;
         final int srcEnd = input.index + count;
         
         int mode = computeStats(src, srcIdx, srcEnd);

         // Not text ?
         if ((mode & 0x80) != 0)
            return false;

         if (count <= 64)
         {
            for (int i=0; i<count; i++)
               dst[dstIdx++] = src[srcIdx++];
            
            input.index = srcIdx;
            output.index = dstIdx;
            return true;
         }

         this.reset();
         final int dstEnd = output.index + this.getMaxEncodedLength(count);
         final int dstEnd3 = dstEnd - 3;
         int emitAnchor = input.index; // never less than input.index
         int words = this.staticDictSize;

         // DOS encoded end of line (CR+LF) ?
         this.isCRLF = (mode & 0x01) != 0;
         dst[dstIdx++] = (byte) mode;

         while ((srcIdx < srcEnd) && (src[srcIdx] == ' '))
         {
            dst[dstIdx++] = ' ';
            srcIdx++;
            emitAnchor++;
         }

         int delimAnchor = isText(src[srcIdx]) ? srcIdx-1 : srcIdx; // previous delimiter

         while (srcIdx < srcEnd)
         {
            final byte cur = src[srcIdx];

            if (isText(cur))
            {
               srcIdx++;
               continue;
            }

            if ((srcIdx > delimAnchor+2) && isDelimiter(cur)) // At least 2 letters
            {
               // Compute hashes
               // h1 -> hash of word chars
               // h2 -> hash of word chars with first char case flipped
               final byte val = src[delimAnchor+1];
               final int caseFlag = isUpperCase(val) ? 32 : -32;
               int h1 = HASH1*HASH1 ^ val*HASH2;
               int h2 = HASH1*HASH1 ^ (val+caseFlag)*HASH2;

               for (int i=delimAnchor+2; i<srcIdx; i++)
               {
                  final int h = src[i] * HASH2;
                  h1 = h1*HASH1 ^ h;
                  h2 = h2*HASH1 ^ h;
               }

               // Check word in dictionary
               final int length = srcIdx - delimAnchor - 1;
               DictEntry e1 = this.dictMap[h1&this.hashMask];              

               // Check for hash collisions
               if ((e1 != null) && (((e1.hash != h1) || (e1.data>>>24) != length)))
                  e1 = null;

               DictEntry e = e1;
               
               if (e1 == null)
               {
                  DictEntry e2 = this.dictMap[h2&this.hashMask];

                  if ((e2 != null) && ((e2.data>>>24) == length) && (e2.hash == h2))
                     e = e2;
               }
               
               if (e != null)
               {
                  if (sameWords(e, src, delimAnchor+2, length-1) == false)
                     e = null;
               }

               if (e == null)
               {
                  // Word not found in the dictionary or hash collision: add or replace word
                  if (((length > 3) || ((length > 2) && (words < THRESHOLD2))) && (length < MAX_WORD_LENGTH))
                  {
                     e = this.dictList[words];

                     if ((e.data&0x00FFFFFF) >= this.staticDictSize)
                     {
                        // Evict and reuse old entry
                        this.dictMap[e.hash&this.hashMask] = null;
                        e.buf = src;
                        e.pos = delimAnchor + 1;
                        e.hash = h1;
                        e.data = (length<<24) | words;
                     }

                     this.dictMap[h1&this.hashMask] = e;
                     words++;

                     // Dictionary full ? Expand or reset index to end of static dictionary
                     if (words >= this.dictSize)
                     {
                        if (this.expandDictionary() == false)
                           words = this.staticDictSize;
                     }
                  }
               }
               else
               {
                  // Word found in the dictionary
                  // Skip space if only delimiter between 2 word references
                  if ((emitAnchor != delimAnchor) || (src[delimAnchor] != ' '))
                     dstIdx = this.emitSymbols(src, emitAnchor, dst, dstIdx, delimAnchor, dstEnd);

                  if (dstIdx >= dstEnd3)
                     break;

                  dstIdx = emitWordIndex(dst, dstIdx, e.data&0x00FFFFFF, ((e == e1) ? 0 : 32));
                  emitAnchor = delimAnchor + 1 + (e.data>>>24);
               }
            }

            // Reset delimiter position
            delimAnchor = srcIdx;
            srcIdx++;
         }

         // Emit last symbols
         dstIdx = this.emitSymbols(src, emitAnchor, dst, dstIdx, srcEnd-1, dstEnd);
         output.index = dstIdx;
         input.index = srcIdx;
         return srcIdx == srcEnd;
      }


      private boolean expandDictionary()
      {
         if (this.dictSize >= MAX_DICT_SIZE)
            return false;

         DictEntry[] newDict = new DictEntry[this.dictSize*2];
         System.arraycopy(this.dictList, 0, newDict, 0, this.dictSize);

         for (int i=this.dictSize; i<this.dictSize*2; i++)
            newDict[i] = new DictEntry(null, -1, 0, i, 0);

         this.dictList = newDict;
         this.dictSize <<= 1;
         return true;
      }


      private int emitSymbols(byte[] src, final int srcIdx, byte[] dst, int dstIdx, final int srcEnd, final int dstEnd)
      {
         for (int i=srcIdx; i<=srcEnd; i++)
         {
            if (dstIdx >= dstEnd-1)
               break;

            final byte cur = src[i];           

            switch (cur)
            {
               case ESCAPE_TOKEN1:
                  dst[dstIdx++] = ESCAPE_TOKEN1;
                  dst[dstIdx++] = ESCAPE_TOKEN1;                  
                  break;

               case CR :
                  if (this.isCRLF == false)
                     dst[dstIdx++] = cur;

                  break;

               default:
                  if ((cur & 0x80) != 0)
                     dst[dstIdx++] = ESCAPE_TOKEN1;

                  dst[dstIdx++] = cur;
            }
         }                                    

         return dstIdx;
      }


      private static int emitWordIndex(byte[] dst, int dstIdx, int val, int mask)
      {
         // Emit word index (varint 5 bits + 7 bits + 7 bits)
         // 1st byte: 0x80 => word idx, 0x40 => more bytes, 0x20 => toggle case 1st symbol
         // 2nd byte: 0x80 => 1 more byte
         if (val >= THRESHOLD3)
         {
            if (val >= THRESHOLD4)
            {
               // 5 + 7 + 7 => 2^19
               dst[dstIdx]   = (byte) (0xC0|mask|((val>>14)&0x1F));
               dst[dstIdx+1] = (byte) (0x80|((val>>7)&0x7F));
               dst[dstIdx+2] = (byte) (val&0x7F);
               return dstIdx + 3;
            }

            // 5 + 7 => 2^12 = 32*128
            dst[dstIdx]   = (byte) (0xC0|mask|((val>>7)&0x1F));
            dst[dstIdx+1] = (byte) (val&0x7F);
            return dstIdx + 2;
         }

         // 5 => 32
         dst[dstIdx] = (byte) (0x80|mask|val);
         return dstIdx + 1;
      }


      @Override
      public boolean inverse(SliceByteArray input, SliceByteArray output)
      {
         final int count = input.length;
         int srcIdx = input.index;
         int dstIdx = output.index;
         final byte[] src = input.array;
         final byte[] dst = output.array;

         if (count <= 64)
         {
            for (int i=0; i<count; i++)
               dst[dstIdx++] = src[srcIdx++];

            input.index = srcIdx;
            output.index = dstIdx;
            return true;
         }

         this.reset();
         final int srcEnd = input.index + count;
         final int dstEnd = dst.length;
         int delimAnchor = isText(src[srcIdx]) ? srcIdx-1 : srcIdx; // previous delimiter
         int words = this.staticDictSize;
         boolean wordRun = false;
         final boolean _isCRLF = (src[srcIdx++] & 0x01) != 0;
         this.isCRLF = _isCRLF;

         while ((srcIdx < srcEnd) && (dstIdx < dstEnd))
         {
            byte cur = src[srcIdx];

            if (isText(cur))
            {
               dst[dstIdx] = cur;
               srcIdx++;
               dstIdx++;
               continue;
            }

            if ((srcIdx > delimAnchor+2) && isDelimiter(cur))
            {
               int h1 = HASH1;

               for (int i=delimAnchor+1; i<srcIdx; i++)
                  h1 = h1*HASH1 ^ src[i]*HASH2;

               // Lookup word in dictionary
               final int length = srcIdx - delimAnchor - 1;
               DictEntry e = this.dictMap[h1&this.hashMask];

               // Check for hash collisions
               if (e != null)
               {
                  if ((e.hash != h1) || ((e.data>>>24) != length))
                     e = null;
                  else if (sameWords(e, src, delimAnchor+2, length-1) == false)
                     e = null;
               }

               if (e == null)
               {
                  // Word not found in the dictionary or hash collision: add or replace word
                  if (((length > 3) || ((length > 2) && (words < THRESHOLD2))) && (length < MAX_WORD_LENGTH))
                  {
                     e = this.dictList[words];

                     if ((e.data&0x00FFFFFF) >= this.staticDictSize)
                     {
                        // Evict and reuse old entry
                        this.dictMap[e.hash&this.hashMask] = null;
                        e.buf = src;
                        e.pos = delimAnchor + 1;
                        e.hash = h1;
                        e.data = (length<<24) | words;
                     }

                     this.dictMap[h1&this.hashMask] = e;
                     words++;

                     // Dictionary full ? Expand or reset index to end of static dictionary
                     if (words >= this.dictSize)
                     {
                        if (this.expandDictionary() == false)
                           words = this.staticDictSize;
                     }
                  }
               }
            }

            srcIdx++;

            if ((cur & 0x80) != 0)
            {
               // Word in dictionary
               // Read word index (varint 5 bits + 7 bits + 7 bits)
               int idx = cur & 0x1F;

               if ((cur&0x40) != 0)
               {
                  int idx2 = src[srcIdx++];

                  if ((idx2&0x80) != 0)
                  {
                     idx = (idx<<7) | (idx2&0x7F);
                     idx2 = src[srcIdx++];
                  }

                  idx = (idx<<7) | (idx2&0x7F);

                  if (idx >= this.dictSize)
                     break;
               }

               final DictEntry e = this.dictList[idx];
               final int length = e.data >>> 24;
               final byte[] buf = e.buf;

               // Sanity check
               if ((e.pos < 0) || (dstIdx+length >= dstEnd))
                  break;

               // Add space if only delimiter between 2 words (not an escaped delimiter)
               if ((wordRun == true) && (length > 1))
                  dst[dstIdx++] = ' ';

               // Emit word
               if ((cur & 0x20) == 0)
               {
                  dst[dstIdx++] = (byte) buf[e.pos];
               }
               else
               {
                  // Flip case of first character
                  dst[dstIdx++] = isUpperCase(buf[e.pos]) ? (byte) (buf[e.pos]+32) : (byte) (e.buf[e.pos]-32);
               }

               if (length > 1)
               {
                  for (int n=e.pos+1, l=e.pos+length; n<l; n++, dstIdx++)
                     dst[dstIdx] = buf[n];

                  // Regular word entry
                  wordRun = true;
                  delimAnchor = srcIdx;
               }
               else
               {
                  // Escape entry
                  wordRun = false;
                  delimAnchor = srcIdx-1;
               }
            }
            else
            {           
               if (cur == ESCAPE_TOKEN1)
               {
                  dst[dstIdx++] = src[srcIdx++];
               }
               else
               {
                  if ((_isCRLF == true) && (cur == LF))
                     dst[dstIdx++] = CR;

                  dst[dstIdx++] = cur;
               }

               wordRun = false;
               delimAnchor = srcIdx-1;
            }
         }

         output.index = dstIdx;
         input.index = srcIdx;
         return srcIdx == srcEnd;
      }


      @Override
      public int getMaxEncodedLength(int srcLength)
      {
         // Limit to 1 x srcLength and let the caller deal with
         // a failure when the output is too small
         return srcLength;
      } 
   }

   
   public static class DictEntry
   {
      int hash; // full word hash
      int pos;  // position in text
      int data; // packed word length (8 MSB) + index in dictionary (24 LSB)
      byte[] buf;  // text data


      DictEntry(byte[] buf, int pos, int hash, int idx, int length)
      {
         this.buf = buf;
         this.pos = pos;
         this.hash = hash;
         this.data = (length << 24) | idx;
      }

      @Override
      public String toString()
      {
         final int length = this.data >>> 24;
         StringBuilder sb = new StringBuilder(length);

         for (int i=0; i<length; i++)
            sb.append((char) this.buf[this.pos+i]);

         return sb.toString();
      }
   }
}