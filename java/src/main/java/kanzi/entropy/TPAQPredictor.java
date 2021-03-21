/*
Copyright 2011-2021 Frederic Langlet
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
package kanzi.entropy;

import kanzi.Predictor;
import java.util.Map;
import kanzi.Global;


// TPAQ predictor
// Initially based on Tangelo 2.4 (by Jan Ondrus).
// PAQ8 is written by Matt Mahoney.
// See http://encode.su/threads/1738-TANGELO-new-compressor-(derived-from-PAQ8-FP8)

public class TPAQPredictor implements Predictor
{
   private static final int MAX_LENGTH = 88;
   private static final int BUFFER_SIZE = 64*1024*1024;
   private static final int HASH_SIZE = 16*1024*1024;
   private static final int MASK_80808080 = 0x80808080;
   private static final int MASK_F0F0F000 = 0xF0F0F000;
   private static final int MASK_4F4FFFFF = 0x4F4FFFFF;
   private static final int MASK_FFFF0000 = 0xFFFF0000;
   private static final int HASH = 0x7FEB352D;

   ///////////////////////// state table ////////////////////////
   // States represent a bit history within some context.
   // State 0 is the starting state (no bits seen).
   // States 1-30 represent all possible sequences of 1-4 bits.
   // States 31-252 represent a pair of counts, (n0,n1), the number
   //   of 0 and 1 bits respectively.  If n0+n1 < 16 then there are
   //   two states for each pair, depending on if a 0 or 1 was the last
   //   bit seen.
   // If n0 and n1 are too large, then there is no state to represent this
   // pair, so another state with about the same ratio of n0/n1 is substituted.
   // Also, when a bit is observed and the count of the opposite bit is large,
   // then part of this count is discarded to favor newer data over old.
   private static final byte[][] STATE_TRANSITIONS =
   {
      // Bit 0
      {
            1,     3,  -113,     4,     5,     6,     7,     8,     9,    10,
           11,    12,    13,    14,    15,    16,    17,    18,    19,    20,
           21,    22,    23,    24,    25,    26,    27,    28,    29,    30,
           31,    32,    33,    34,    35,    36,    37,    38,    39,    40,
           41,    42,    43,    44,    45,    46,    47,    48,    49,    50,
           51,    52,    47,    54,    55,    56,    57,    58,    59,    60,
           61,    62,    63,    64,    65,    66,    67,    68,    69,     6,
           71,    71,    71,    61,    75,    56,    77,    78,    77,    80,
           81,    82,    83,    84,    85,    86,    87,    88,    77,    90,
           91,    92,    80,    94,    95,    96,    97,    98,    99,    90,
          101,    94,   103,   101,   102,   104,   107,   104,   105,   108,
          111,   112,   113,   114,   115,   116,    92,   118,    94,   103,
          119,   122,   123,    94,   113,   126,   113,  -128,  -127,   114,
         -125,  -124,   112,  -122,   111,  -122,   110,  -122,  -122,  -128,
         -128,  -114,  -113,   115,   113,  -114,  -128,  -108,  -107,    79,
         -108,  -114,  -108,  -106,  -101,  -107,   -99,  -107,   -97,  -107,
         -125,   101,    98,   115,   114,    91,    79,    58,     1,   -86,
         -127,  -128,   110,   -82,  -128,   -80,  -127,   -82,   -77,   -82,
          -80,  -115,   -99,   -77,   -71,   -99,   -69,   -68,   -88,  -105,
          -65,   -64,   -68,   -69,   -84,   -81,   -86,  -104,   -71,   -86,
          -80,   -86,   -53,  -108,   -71,   -53,   -71,   -64,   -47,   -68,
          -45,   -64,   -43,   -42,   -68,   -40,   -88,    84,    54,    54,
          -35,    54,    55,    85,    69,    63,    56,    86,    58,   -26,
          -25,    57,   -27,    56,   -32,    54,    54,    66,    58,    54,
           61,    57,   -34,    78,    85,    82,     0,     0,     0,     0,
            0,     0,     0,     0,     0,     0
      },
      // Bit 1
      {
            2,   -93,   -87,   -93,   -91,    89,   -11,   -39,   -11,   -11,
          -23,   -12,   -29,    74,   -35,   -35,   -38,   -30,   -13,   -38,
          -18,   -14,    74,   -18,   -15,   -16,   -17,   -32,   -31,   -35,
          -24,    72,   -32,   -28,   -33,   -31,   -18,    73,   -89,    76,
          -19,   -22,   -25,    72,    31,    63,   -31,   -19,   -20,   -21,
           53,   -22,    53,   -22,   -27,   -37,   -27,   -23,   -24,   -28,
          -30,    72,    74,   -34,    75,   -36,   -89,    57,   -38,    70,
          -88,    72,    73,    74,   -39,    76,   -89,    79,    79,   -90,
          -94,   -94,   -94,   -94,   -91,    89,    89,   -91,    89,   -94,
           93,    93,    93,   -95,   100,    93,    93,    93,    93,    93,
          -95,   102,   120,   104,   105,   106,   108,   106,   109,   110,
          -96,  -122,   108,   108,   126,   117,   117,   121,   119,   120,
          107,   124,   117,   117,   125,   127,   124,  -117,  -126,   124,
         -123,   109,   110,  -121,   110,  -120,  -119,  -118,   127,  -116,
         -115,  -111,  -112,   124,   125,  -110,  -109,  -105,   125,  -106,
          127,  -104,  -103,  -102,  -100,  -117,   -98,  -117,  -100,  -117,
         -126,   117,   -93,   -92,  -115,   -93,  -109,     2,     2,   -57,
          -85,   -84,   -83,   -79,   -81,   -85,   -85,   -78,   -76,   -84,
          -75,   -74,   -73,   -72,   -70,   -78,   -67,   -75,   -75,   -66,
          -63,   -74,   -74,   -62,   -61,   -60,   -59,   -58,   -87,   -56,
          -55,   -54,   -52,   -76,   -51,   -50,   -49,   -48,   -46,   -62,
          -44,   -72,   -41,   -63,   -72,   -48,   -63,   -93,   -37,   -88,
           94,   -39,   -33,   -32,   -31,    76,   -29,   -39,   -27,   -37,
           79,    86,   -91,   -39,   -42,   -31,   -40,   -40,   -22,    75,
          -42,   -19,    74,    74,   -93,   -39,     0,     0,     0,     0,
            0,     0,     0,     0,     0,     0
      }
   };


   private static final int[] STATE_MAP =
   {
      -31,  -400,   406,  -547,  -642,  -743,  -827,  -901,
     -901,  -974,  -945,  -955, -1060, -1031, -1044,  -956,
     -994, -1035, -1147, -1069, -1111, -1145, -1096, -1084,
    -1171, -1199, -1062, -1498, -1199, -1199, -1328, -1405,
    -1275, -1248, -1167, -1448, -1441, -1199, -1357, -1160,
    -1437, -1428, -1238, -1343, -1526, -1331, -1443, -2047,
    -2047, -2044, -2047, -2047, -2047,  -232,  -414,  -573,
     -517,  -768,  -627,  -666,  -644,  -740,  -721,  -829,
     -770,  -963,  -863, -1099,  -811,  -830,  -277, -1036,
     -286,  -218,   -42,  -411,   141, -1014, -1028,  -226,
     -469,  -540,  -573,  -581,  -594,  -610,  -628,  -711,
     -670,  -144,  -408,  -485,  -464,  -173,  -221,  -310,
     -335,  -375,  -324,  -413,   -99,  -179,  -105,  -150,
      -63,    -9,    56,    83,   119,   144,   198,   118,
      -42,   -96,  -188,  -285,  -376,   107,  -138,    38,
      -82,   186,  -114,  -190,   200,   327,    65,   406,
      108,   -95,   308,   171,   -18,   343,   135,   398,
      415,   464,   514,   494,   508,   519,    92,  -123,
      343,   575,   585,   516,    -7,  -156,   209,   574,
      613,   621,   670,   107,   989,   210,   961,   246,
      254,   -12,  -108,    97,   281,  -143,    41,   173,
     -209,   583,   -55,   250,   354,   558,    43,   274,
       14,   488,   545,    84,   528,   519,   587,   634,
      663,    95,   700,    94,  -184,   730,   742,   162,
      -10,   708,   692,   773,   707,   855,   811,   703,
      790,   871,   806,     9,   867,   840,   990,  1023,
     1409,   194,  1397,   183,  1462,   178,   -23,  1403,
      247,   172,     1,   -32,  -170,    72,  -508,   -46,
     -365,   -26,  -146,   101,   -18,  -163,  -422,  -461,
     -146,   -69,   -78,  -319,  -334,  -232,   -99,     0,
       47,   -74,     0,  -452,    14,   -57,     1,     1,
        1,     1,     1,     1,     1,     1,     1,     1,
   };


   private static final int[] MATCH_PRED =
   {
        0,    64,   128,   192,   256,   320,   384,   448,
      512,   576,   640,   704,   768,   832,   896,   960,
     1024,  1038,  1053,  1067,  1082,  1096,  1111,  1125,
     1139,  1154,  1168,  1183,  1197,  1211,  1226,  1240,
     1255,  1269,  1284,  1298,  1312,  1327,  1341,  1356,
     1370,  1385,  1399,  1413,  1428,  1442,  1457,  1471,
     1486,  1500,  1514,  1529,  1543,  1558,  1572,  1586,
     1601,  1615,  1630,  1644,  1659,  1673,  1687,  1702,
     1716,  1731,  1745,  1760,  1774,  1788,  1803,  1817,
     1832,  1846,  1861,  1875,  1889,  1904,  1918,  1933,
     1947,  1961,  1976,  1990,  2005,  2019,  2034,  2047,
   };


   static int hash(int x, int y)
   {
      final int h = x*HASH ^ y*HASH;
      return (h>>1) ^ (h>>9) ^ (x>>2) ^ (y>>3) ^ HASH;
   }



   private int pr;                     // next predicted value (0-4095)
   private int c0;                     // bitwise context: last 0-7 bits with a leading 1 (1-255)
   private int c4;                     // last 4 whole bytes, last is in low 8 bits
   private int c8;                     // last 8 to 4 whole bytes, last is in low 8 bits
   private int bpos;                   // number of bits in c0 (0-7)
   private int pos;
   private int binCount;
   private int matchLen;
   private int matchPos;
   private int hash;
   private final int statesMask;
   private final int mixersMask;
   private final int hashMask;
   private final int bufferMask;
   private final LogisticAdaptiveProbMap sse0;
   private final LogisticAdaptiveProbMap sse1;
   private final Mixer[] mixers;
   private Mixer mixer;                  // current mixer
   private final byte[] buffer;
   private final int[] hashes;           // hash table(context, buffer position)
   private final byte[] bigStatesMap;    // hash table(context, prediction)
   private final byte[] smallStatesMap0; // hash table(context, prediction)
   private final byte[] smallStatesMap1; // hash table(context, prediction)
   private int cp0;                      // context pointers
   private int cp1;
   private int cp2;
   private int cp3;
   private int cp4;
   private int cp5;
   private int cp6;
   private int ctx0;                     // contexts
   private int ctx1;
   private int ctx2;
   private int ctx3;
   private int ctx4;
   private int ctx5;
   private int ctx6;
   private boolean extra;


   public TPAQPredictor()
   {
      this(null); // 256 MB
   }


   public TPAQPredictor(Map<String, Object> ctx)
   {
      int statesSize = 1 << 28;
      int mixersSize = 1 << 12;
      int hashSize = HASH_SIZE;
      int extraMem = 0;
      int bufferSize = BUFFER_SIZE;

      if (ctx != null)
      {
         // If extra mode, add more memory for states table, hash table
         // and add second SSE
         String codec = (String) ctx.getOrDefault("codec", "NONE");
         this.extra = "TPAQX".equals(codec);
         extraMem = (this.extra == true) ? 1 : 0;

         // Block size requested by the user
         // The user can request a big block size to force more states
         final int rbsz = (Integer) ctx.getOrDefault("blockSize", 32768);

         if (rbsz >= 64*1024*1024)
            statesSize = 1 << 28;
         else if (rbsz >= 16*1024*1024)
            statesSize = 1 << 27;
         else if (rbsz >= 4*1024*1024)
            statesSize = 1 << 26;
         else statesSize = (rbsz >= 1024*1024) ? 1 << 24 : 1 << 22;

         // Actual size of the current block
         // Too many mixers hurts compression for small blocks.
         // Too few mixers hurts compression for big blocks.
         final int absz = (Integer) ctx.getOrDefault("size", rbsz);

         if (absz >= 32*1024*1024)
            mixersSize = 1 << 16;
         else if (absz >= 16*1024*1024)
            mixersSize = 1 << 15;
         else if (absz >= 8*1024*1024)
            mixersSize = 1 << 14;
         else if (absz >= 4*1024*1024)
            mixersSize = 1 << 13;
         else
            mixersSize = (absz >= 1024*1024) ? 1 << 11 : 1 << 8;

         bufferSize = Math.min(BUFFER_SIZE, rbsz);
         hashSize = Math.min(hashSize, 16*absz);
      }

      mixersSize <<= (2*extraMem);
      statesSize <<= (2*extraMem);
      hashSize <<= (2*extraMem);

      this.pr = 2048;
      this.c0 = 1;
      this.bpos = 8;
      this.mixers = new Mixer[mixersSize];

      for (int i=0; i<this.mixers.length; i++)
         this.mixers[i] = new Mixer();

      this.mixer = this.mixers[0];
      this.bigStatesMap = new byte[statesSize];
      this.smallStatesMap0 = new byte[1<<16];
      this.smallStatesMap1 = new byte[1<<24];
      this.hashes = new int[hashSize];
      this.buffer = new byte[bufferSize];
      this.statesMask = this.bigStatesMap.length - 1;
      this.mixersMask = (this.mixers.length - 1) & ~1;
      this.hashMask = this.hashes.length - 1;
      this.bufferMask = this.buffer.length - 1;
      this.sse0 = (this.extra == true) ? new LogisticAdaptiveProbMap(256, 6) :
         new LogisticAdaptiveProbMap(256, 7);
      this.sse1 = (this.extra == true) ? new LogisticAdaptiveProbMap(65536, 7) : null;
   }


   // Update the probability model
   @Override
   public void update(int bit)
   {
     this.mixer.update(bit);
     this.bpos--;
     this.c0 = (this.c0 << 1) | bit;

     if (this.c0 > 255)
     {
        this.buffer[this.pos&this.bufferMask] = (byte) this.c0;
        this.pos++;
        this.c8 = (this.c8<<8) | (this.c4>>>24);
        this.c4 = (this.c4<<8) | (this.c0&0xFF);
        this.hash = (((this.hash*HASH)<<4) + this.c4) & this.hashMask;
        this.c0 = 1;
        this.bpos = 8;
        this.binCount += ((this.c4>>7) & 1);

        // Select Neural Net
        this.mixer = this.mixers[(this.c4&this.mixersMask) | ((this.matchLen!=0) ? 1 : 0)];

        // Add contexts to NN
        this.ctx0 = (this.c4&0xFF) << 8;
        this.ctx1 = (this.c4&0xFFFF) << 8;
        this.ctx2 = createContext(2, this.c4&0x00FFFFFF);
        this.ctx3 = createContext(3, this.c4);

        if (this.binCount < (this.pos>>2))
        {
           // Mostly text or mixed
           this.ctx4 = createContext(this.ctx1, this.c4^(this.c8&0xFFFF));
           this.ctx5 = (this.c8&MASK_F0F0F000) | ((this.c4&MASK_F0F0F000)>>4);

           if (this.extra == true)
           {
               final int h1 = ((this.c4&MASK_80808080) == 0) ? this.c4&MASK_4F4FFFFF : this.c4&MASK_80808080;
               final int h2 = ((this.c8&MASK_80808080) == 0) ? this.c8&MASK_4F4FFFFF : this.c8&MASK_80808080;
               this.ctx6 = hash(h1<<2, h2>>2);
           }
        }
        else
        {
           // Mostly binary
           this.ctx4 = createContext(HASH+this.matchLen, this.c4^(this.c4&0x000FFFFF));
           this.ctx5 = this.ctx0 | (this.c8<<16);

           if (this.extra == true)
               this.ctx6 = hash(this.c4&MASK_FFFF0000, this.c8>>16);
        }

        this.findMatch();

        // Keep track of current position
        this.hashes[this.hash] = this.pos;
      }

      // Get initial predictions
      // It has been observed that accessing memory via [ctx ^ c] is significantly faster
      // on SandyBridge/Windows and slower on SkyLake/Linux except when [ctx & 255 == 0]
      // (with c < 256). Hence, use XOR for _ctx5 which is the only context that fulfills
      // the condition.
      final int c = this.c0;
      final int mask = this.statesMask;
      final byte[] bst = this.bigStatesMap;
      final byte[] sst0 = this.smallStatesMap0;
      final byte[] sst1 = this.smallStatesMap1;
      final byte[] table = STATE_TRANSITIONS[bit];
      sst0[this.cp0] = table[sst0[this.cp0]&0xFF];
      sst1[this.cp1] = table[sst1[this.cp1]&0xFF];
      bst[this.cp2] = table[bst[this.cp2]&0xFF];
      bst[this.cp3] = table[bst[this.cp3]&0xFF];
      bst[this.cp4] = table[bst[this.cp4]&0xFF];
      bst[this.cp5] = table[bst[this.cp5]&0xFF];
      this.cp0 = this.ctx0 + c;
      final int p0 = STATE_MAP[sst0[this.cp0]&0xFF];
      this.cp1 = this.ctx1 + c;
      final int p1 = STATE_MAP[sst1[this.cp1]&0xFF];
      this.cp2 = (this.ctx2 + c) & mask;
      final int p2 = STATE_MAP[bst[this.cp2]&0xFF];
      this.cp3 = (this.ctx3 + c) & mask;
      final int p3 = STATE_MAP[bst[this.cp3]&0xFF];
      this.cp4 = (this.ctx4 + c) & mask;
      final int p4 = STATE_MAP[bst[this.cp4]&0xFF];
      this.cp5 = (this.ctx5 ^ c) & mask;
      final int p5 = STATE_MAP[bst[this.cp5]&0xFF];

      final int p7 = (this.matchLen == 0) ? 0 : this.getMatchContextPred();
      int p;

      if (this.extra == false)
      {
         // Mix predictions using NN
         p = this.mixer.get(p0, p1, p2, p3, p4, p5, p7, p7);

         // SSE (Secondary Symbol Estimation)
         if (this.binCount < (this.pos>>3))
            p = (3*this.sse0.get(bit, p, this.c0) + p) >> 2;
      }
      else
      {
         // One more prediction
         bst[this.cp6] = table[bst[this.cp6]&0xFF];
         this.cp6 = (this.ctx6 + c) & mask;
         final int p6 = STATE_MAP[bst[this.cp6]&0xFF];

         // Mix predictions using NN
         p = this.mixer.get(p0, p1, p2, p3, p4, p5, p6, p7);

         // SSE (Secondary Symbol Estimation)
         if (this.binCount < (this.pos>>3))
         {
            p = this.sse1.get(bit, p, this.ctx0+c);
         }
         else
         {
            if (this.binCount >= (this.pos>>2))
               p = (3*this.sse0.get(bit, p, this.c0) + p) >> 2;

            p = (3*this.sse1.get(bit, p, this.ctx0+c) + p) >> 2;
         }
      }

      this.pr = p + ((p-2048) >>> 31);
   }


   private void findMatch()
   {
      // Update ongoing sequence match or detect match in the buffer (LZ like)
      if (this.matchLen > 0)
      {
         this.matchLen += ((this.matchLen - MAX_LENGTH) >>> 31);
         this.matchPos++;
      }
      else
      {
         // Retrieve match position
         this.matchPos = this.hashes[this.hash];

         // Detect match
         if ((this.matchPos != 0) && (this.pos - this.matchPos <= this.bufferMask))
         {
            int r = this.matchLen + 2;
            int s = this.pos - r;
            int t = this.matchPos - r;

            while (r <= MAX_LENGTH)
            {
               if (this.buffer[(s-1)&this.bufferMask] != this.buffer[(t-1)&this.bufferMask])
                  break;

               if (this.buffer[s&this.bufferMask] != this.buffer[t&this.bufferMask])
                  break;

               r += 2;
               s -= 2;
               t -= 2;
            }

            this.matchLen = r - 2;
         }
      }
   }


   // Get a prediction from the match model in [-2047..2048]
   private int getMatchContextPred()
   {
      if (this.c0 == ((this.buffer[this.matchPos&this.bufferMask]&0xFF) | 256) >> this.bpos)
      {
         return (((this.buffer[this.matchPos&this.bufferMask] >> (this.bpos-1)) & 1) != 0) ?
            MATCH_PRED[this.matchLen-1] : -MATCH_PRED[this.matchLen-1];
      }

      this.matchLen = 0;
      return 0;
   }


   private static int createContext(int ctxId, int cx)
   {
      cx = cx*987654323 + ctxId;
      cx = (cx << 16) | (cx >>> 16);
      return cx*123456791 + ctxId;
   }


   // Return the split value representing the probability of 1 in the [0..4095] range.
   @Override
   public int get()
   {
      return this.pr;
   }


   // Mixer combines models using a neural network with 8 inputs.
   static class Mixer
   {
      private static final int BEGIN_LEARN_RATE = 60 << 7;
      static final int END_LEARN_RATE = 11 << 7;  // 8 << 7 for text, else 14 << 7

      private int pr;  // squashed prediction
      private int skew;
      private int w0, w1, w2, w3, w4, w5, w6, w7;
      private int p0, p1, p2, p3, p4, p5, p6, p7;
      private int learnRate;


      Mixer()
      {
         this.pr = 2048;
         this.w0 = this.w1 = this.w2 = this.w3 = 32768;
         this.w4 = this.w5 = this.w6 = this.w7 = 32768;
         this.learnRate = BEGIN_LEARN_RATE;
      }


      // Adjust weights to minimize coding cost of last prediction
      void update(int bit)
      {
         final int err = (((bit<<12) - this.pr) * this.learnRate) >> 10;

         if (err == 0)
            return;

         // Quickly decaying learn rate
         this.learnRate += ((END_LEARN_RATE-this.learnRate)>>31);
         this.skew += err;

         // Train Neural Network: update weights
         this.w0 += ((this.p0*err + 0) >> 12);
         this.w1 += ((this.p1*err + 0) >> 12);
         this.w2 += ((this.p2*err + 0) >> 12);
         this.w3 += ((this.p3*err + 0) >> 12);
         this.w4 += ((this.p4*err + 0) >> 12);
         this.w5 += ((this.p5*err + 0) >> 12);
         this.w6 += ((this.p6*err + 0) >> 12);
         this.w7 += ((this.p7*err + 0) >> 12);
      }


      public int get(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7)
      {
         this.p0 = p0;
         this.p1 = p1;
         this.p2 = p2;
         this.p3 = p3;
         this.p4 = p4;
         this.p5 = p5;
         this.p6 = p6;
         this.p7 = p7;

         // Neural Network dot product (sum weights*inputs)
         this.pr = Global.squash((this.w0*p0 + this.w1*p1 + this.w2*p2 + this.w3*p3 +
                                  this.w4*p4 + this.w5*p5 + this.w6*p6 + this.w7*p7 +
                                  this.skew  + 65536) >> 17);

         return this.pr;
      }
   }

}
