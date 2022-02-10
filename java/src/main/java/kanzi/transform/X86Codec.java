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

package kanzi.transform;

import java.util.Map;
import kanzi.ByteTransform;
import kanzi.Global;
import kanzi.Magic;
import kanzi.Memory.BigEndian;
import kanzi.Memory.LittleEndian;
import kanzi.SliceByteArray;


// Adapted from MCM: https://github.com/mathieuchartier/mcm/blob/master/X86Binary.hpp
public class X86Codec implements ByteTransform
{
   private static final byte X86_MASK_JUMP = (byte) 0xFE;
   private static final byte X86_INSTRUCTION_JUMP = (byte) 0xE8;
   private static final byte X86_INSTRUCTION_JCC = (byte) 0x80;
   private static final byte X86_TWO_BYTE_PREFIX = (byte) 0x0F;
   private static final byte X86_MASK_JCC = (byte) 0xF0;
   private static final byte X86_ESCAPE = (byte) 0x9B;
   private static final byte NOT_EXE = 0;
   private static final byte X86 = 1;
   private static final byte ARM64 = 2;
   private static final int X86_ADDR_MASK = (1 << 24) - 1;
   private static final int MASK_ADDRESS = 0xF0F0F0F0;
   private static final int ARM_B_ADDR_MASK = (1 << 26) - 1;
   private static final int ARM_B_OPCODE_MASK = 0xFFFFFFFF ^ ARM_B_ADDR_MASK;
   private static final int ARM_B_ADDR_SGN_MASK = 1 << 25;
   private static final int ARM_OPCODE_B = 0x14000000;  // 6 bit opcode
   private static final int ARM_OPCODE_BL = 0x94000000; // 6 bit opcode       
   private static final int ARM_CB_REG_BITS = 5; // lowest bits for register
   private static final int ARM_CB_ADDR_MASK = 0x00FFFFE0;
   private static final int ARM_CB_ADDR_SGN_MASK = 1 << 18;
   private static final int ARM_CB_OPCODE_MASK = 0x7F000000;
   private static final int ARM_OPCODE_CBZ = 0x34000000;  // 8 bit opcode
   private static final int ARM_OPCODE_CBNZ = 0x3500000; // 8 bit opcode
   private static final int WIN_PE = 0x00004550;
   private static final int WIN_X86_ARCH = 0x014C;
   private static final int WIN_AMD64_ARCH = 0x8664;
   private static final int WIN_ARM64_ARCH = 0xAA64;
   private static final int ELF_X86_ARCH = 0x03;
   private static final int ELF_AMD64_ARCH = 0x3E;  
   private static final int ELF_ARM64_ARCH = 0xB7;
   private static final int MAC_AMD64_ARCH = 0x10000007;
   private static final int MAC_ARM64_ARCH = 0x1000000C;
   private static final int MIN_BLOCK_SIZE = 4096;
   private static final int MAX_BLOCK_SIZE = (1 << (26+2)) - 1; // max offset << 2
   private boolean isBsVersion2;

   
   private Map<String, Object> ctx;
   private int codeStart;
   private int codeEnd;
   private int arch;


   public X86Codec()
   {
   }


   public X86Codec(Map<String, Object> ctx)
   {
      this.ctx = ctx;
   }


   @Override
   public boolean forward(SliceByteArray input, SliceByteArray output)
   {
      if (input.length == 0)
         return true;

      if ((input.length < MIN_BLOCK_SIZE) || (input.length > MAX_BLOCK_SIZE))
          return false;
      
      if (input.array == output.array)
         return false;

      final int count = input.length;

      if (output.length - output.index < getMaxEncodedLength(count))
         return false;
      
      this.isBsVersion2 = false;

      if (this.ctx != null)
      {
         Global.DataType dt = (Global.DataType) this.ctx.getOrDefault("dataType",
            Global.DataType.UNDEFINED);

         if ((dt != Global.DataType.UNDEFINED) && (dt != Global.DataType.EXE))
            return false;
         
         final int bsVersion = (Integer) ctx.getOrDefault("bsVersion", -1);
         this.isBsVersion2 = bsVersion == 2;
      }

      this.codeStart = input.index;
      this.codeEnd = input.index + count - 8;
      byte mode = detectType(input.array, input.index, count-8);

      if (mode == NOT_EXE)
         return false;

      if (this.ctx != null)
         this.ctx.put("dataType", Global.DataType.EXE);

      if (mode == X86)
         return this.forwardX86(input, output);

      if (mode == ARM64)
         return this.forwardARM(input, output);
      
      return false;
   }

   
   private boolean forwardX86(SliceByteArray input, SliceByteArray output)
   {
      final byte[] src = input.array;
      final byte[] dst = output.array;
      dst[output.index] = X86;
      int srcIdx = input.index + this.codeStart;
      int dstIdx = output.index + 9;
      int matches = 0;
      
      if (this.codeStart > input.index) 
      {
         System.arraycopy(src, input.index, dst, dstIdx, this.codeStart-input.index);
         dstIdx += (this.codeStart-input.index);
      }
       
      while (srcIdx < this.codeEnd) 
      {   
         if (src[srcIdx] == X86_TWO_BYTE_PREFIX) 
         {
            dst[dstIdx++] = src[srcIdx++];            

            if ((src[srcIdx] & X86_MASK_JCC) != X86_INSTRUCTION_JCC) 
            {
               // Not a relative jump
               if (src[srcIdx] == X86_ESCAPE)  
                  dst[dstIdx++] = X86_ESCAPE; 

               dst[dstIdx++] = src[srcIdx++];
               continue;
            }
         }
         else if ((src[srcIdx] & X86_MASK_JUMP) != X86_INSTRUCTION_JUMP) 
         {
            // Not a relative call
            if (src[srcIdx] == X86_ESCAPE)  
               dst[dstIdx++] = X86_ESCAPE; 
            
            dst[dstIdx++] = src[srcIdx++];                          
            continue;
         }

         // Current instruction is a jump/call.
         final int sgn = src[srcIdx+4] & 0xFF;
         final int offset = LittleEndian.readInt32(src, srcIdx+1);

         // False positive ?
         if (((sgn != 0) && (sgn != 0xFF)) || (offset == (int) 0xFF000000))
         {
            dst[dstIdx++] = X86_ESCAPE;
            dst[dstIdx++] = src[srcIdx++];
            continue;
         }

         // Absolute target address = srcIdx + 5 + offset. Let us ignore the +5 
         final int addr = srcIdx + ((sgn==0) ? offset : -(-offset & X86_ADDR_MASK));
         dst[dstIdx++] = src[srcIdx++];
         BigEndian.writeInt32(dst, dstIdx, addr ^ MASK_ADDRESS);
         srcIdx += 4;
         dstIdx += 4;
         matches++;
      }
      
      // Worth it ?
      if (matches < 16)
         return false;

      LittleEndian.writeInt32(dst, 1, this.codeStart-input.index);
      LittleEndian.writeInt32(dst, 5, dstIdx-input.index);
      final int end = input.length + input.index;
      System.arraycopy(src, srcIdx, dst, dstIdx, end-srcIdx);
      dstIdx += (end-srcIdx);
      input.index = end;
      output.index = dstIdx; 
      return true;
   }

   
   private boolean forwardARM(SliceByteArray input, SliceByteArray output)
   {
      final byte[] src = input.array;
      final byte[] dst = output.array;
      dst[output.index] = ARM64;
      int srcIdx = input.index + this.codeStart;
      int dstIdx = output.index + 9;
      int matches = 0;
      int fpRun = 0;
      
      if (this.codeStart > input.index) 
      {
         System.arraycopy(src, input.index, dst, dstIdx, this.codeStart-input.index);
         dstIdx += (this.codeStart-input.index);
      }
       
      while (srcIdx < this.codeEnd) 
      {
        final int instr = LittleEndian.readInt32(src, srcIdx);
        final int opcode1 = instr & ARM_B_OPCODE_MASK;
        //final int opcode2 = instr & ARM_CB_OPCODE_MASK;
        boolean isBL = (opcode1 == ARM_OPCODE_B) || (opcode1 == ARM_OPCODE_BL); // inconditional jump
        boolean isCB = false; // disable for now ... isCB = (opcode2 == ARM_OPCODE_CBZ) || (opcode2 == ARM_OPCODE_CBNZ); // conditional jump

        if ((isBL == false) && (isCB == false)) 
        {
            // Not a relative jump
            dst[dstIdx]   = src[srcIdx];
            dst[dstIdx+1] = src[srcIdx+1];
            dst[dstIdx+2] = src[srcIdx+2];
            dst[dstIdx+3] = src[srcIdx+3];
            srcIdx += 4;
            dstIdx += 4;
            continue;
        }
        
        int addr, val;
        
        if (isBL == true) 
        {
            // opcode(6) + sgn(1) + offset(25)
            // Absolute target address = srcIdx +/- (offet*4)
            final int offset = instr & ARM_B_ADDR_MASK;
            final int sgn = instr & ARM_B_ADDR_SGN_MASK;
            addr = srcIdx + 4 * ((sgn == 0) ? offset : ARM_B_OPCODE_MASK | offset);
            
            if (addr < 0)
               addr = 0;
            
            val = opcode1 | (addr >> 2);
        }
        else // isCB == true
        { 
            // opcode(8) + sgn(1) + offset(18) + register(5)
            // Absolute target address = srcIdx +/- (offet*4)
            final int offset = (instr & ARM_CB_ADDR_MASK) >> ARM_CB_REG_BITS;
            final int sgn = instr & ARM_CB_ADDR_SGN_MASK;
            addr = srcIdx + 4 * ((sgn == 0) ? offset : 0xFFFC0000 | offset);
            
            if (addr < 0)
               addr = 0;
            
            val = (instr & ~ARM_CB_ADDR_MASK) | ((addr >> 2) << ARM_CB_REG_BITS);        
        }
        
        if (addr == 0) 
        {
            fpRun++; 
            
            // Early exit if likely outside a code section
            if (fpRun == 4)
               break;
            
            LittleEndian.writeInt32(dst, dstIdx, val); // 0 address as escape
            dst[dstIdx+4] = src[srcIdx];
            dst[dstIdx+5] = src[srcIdx+1];
            dst[dstIdx+6] = src[srcIdx+2];
            dst[dstIdx+7] = src[srcIdx+3];
            srcIdx += 4;
            dstIdx += 8;
            continue;
        }
        
        fpRun = 0;          
        LittleEndian.writeInt32(dst, dstIdx, val);
        srcIdx += 4;
        dstIdx += 4;
        matches++;
      }  
      
      // Worth it ?
      if (matches < 16)
         return false;

      LittleEndian.writeInt32(dst, 1, this.codeStart-input.index);
      LittleEndian.writeInt32(dst, 5, dstIdx-input.index);
      final int end = input.length + input.index;
      System.arraycopy(src, srcIdx, dst, dstIdx, end-srcIdx);
      dstIdx += (end-srcIdx);
      input.index = end;
      output.index += dstIdx; 
      return true;
   }
   

   @Override
   public boolean inverse(SliceByteArray input, SliceByteArray output)
   {
      if (input.length == 0)
        return true;

      if (input.length == 0)
         return true;

      if (input.array == output.array)
         return false;

      // Old format
      if (this.isBsVersion2 == true)
         return inverseV2(input, output);
      
      byte mode = input.array[input.index];

      if (mode == X86)
         return inverseX86(input, output);

      if (mode == ARM64)
         return inverseARM(input, output);

      return false;
  }

   
  private boolean inverseX86(SliceByteArray input, SliceByteArray output)
  {
      final byte[] src = input.array;
      final byte[] dst = output.array;
      int srcIdx = input.index + 9;
      int dstIdx = output.index;
      this.codeStart = input.index + LittleEndian.readInt32(src, input.index+1);
      this.codeEnd = input.index + LittleEndian.readInt32(src, input.index+5);

      if (this.codeStart > input.index) 
      {
         System.arraycopy(src, input.index+9, dst, dstIdx, this.codeStart-input.index);
         srcIdx += (this.codeStart-input.index);
         dstIdx += (this.codeStart-input.index);
      }

      while (srcIdx < this.codeEnd) 
      {     
         if (src[srcIdx] == X86_TWO_BYTE_PREFIX) 
         {
            dst[dstIdx++] = src[srcIdx++];   

            if ((src[srcIdx] & X86_MASK_JCC) != X86_INSTRUCTION_JCC) 
            {
               // Not a relative jump
               if (src[srcIdx] == X86_ESCAPE)
                  srcIdx++;

               dst[dstIdx++] = src[srcIdx++];
               continue;
             }
         }
         else if ((src[srcIdx] & X86_MASK_JUMP) != X86_INSTRUCTION_JUMP) 
         {
            // Not a relative call
            if (src[srcIdx] == X86_ESCAPE)
               srcIdx++;

            dst[dstIdx++] = src[srcIdx++];
            continue;
         }

         // Current instruction is a jump/call. Decode absolute address
         final int addr = BigEndian.readInt32(src, srcIdx+1) ^ MASK_ADDRESS;    
         final int offset = addr - dstIdx;        
         dst[dstIdx++] = src[srcIdx++];
         LittleEndian.writeInt32(dst, dstIdx, (offset>=0) ? offset : -(-offset & X86_ADDR_MASK));       
         srcIdx += 4;
         dstIdx += 4;
      }

      final int end = input.length + input.index;
      System.arraycopy(src, srcIdx, dst, dstIdx, end-srcIdx);
      dstIdx += (end-srcIdx);
      input.index = end;
      output.index = dstIdx;
      return true;
   }


   // Decompress bitstream format < 3
   private boolean inverseV2(SliceByteArray input, SliceByteArray output)
   {
      if (input.length == 0)
         return true;

      if (input.array == output.array)
          return false;

      final int count = input.length;

      if (input.index + count > input.array.length)
         return false;

      // Aliasing
      final byte[] src = input.array;
      final byte[] dst = output.array;
      int srcIdx = input.index;
      int dstIdx = output.index;
      final int end = count - 8;

      while (srcIdx < end)
      {
         dst[dstIdx++] = src[srcIdx++];

         // Relative jump ?
         if ((src[srcIdx-1] & X86_MASK_JUMP) != X86_INSTRUCTION_JUMP)
            continue;

         if (src[srcIdx] == 0xF5)
         {
            // Not an encoded address. Skip escape symbol
            srcIdx++;
            continue;
         }

         final int sgn = src[srcIdx] - 1;

         // Invalid sign of jump address difference => false positive ?
         if ((sgn != 0) && (sgn != -1))
            continue;

         int addr = (0xFF & (MASK_ADDRESS ^ src[srcIdx+3]))        |
                   ((0xFF & (MASK_ADDRESS ^ src[srcIdx+2])) <<  8) |
                   ((0xFF & (MASK_ADDRESS ^ src[srcIdx+1])) << 16) |
                   ((0xFF & sgn) << 24);

         addr -= dstIdx;
         dst[dstIdx]   = (byte)  addr;
         dst[dstIdx+1] = (byte) (addr >>  8);
         dst[dstIdx+2] = (byte) (addr >> 16);
         dst[dstIdx+3] = (byte)  sgn;
         srcIdx += 4;
         dstIdx += 4;
      }

      while (srcIdx < count)
         dst[dstIdx++] = src[srcIdx++];

      input.index = srcIdx;
      output.index = dstIdx;
      return true;
   }
  
  
   private boolean inverseARM(SliceByteArray input, SliceByteArray output)
   {
      final byte[] src = input.array;
      final byte[] dst = output.array;
      int srcIdx = input.index + 9;
      int dstIdx = output.index;
      this.codeStart = input.index + LittleEndian.readInt32(src, input.index+1);
      this.codeEnd = input.index + LittleEndian.readInt32(src, input.index+5);
      int fpRun = 0;
      
      if (this.codeStart > input.index) 
      {
         System.arraycopy(src, input.index+9, dst, dstIdx, this.codeStart-input.index);
         dstIdx += (this.codeStart-input.index);
      }
    
      while (srcIdx < this.codeEnd) 
      {
         final int instr = LittleEndian.readInt32(src, srcIdx);
         final int opcode1 = instr & ARM_B_OPCODE_MASK;
         //const int opcode2 = instr & ARM_CB_OPCODE_MASK;
         boolean isBL = (opcode1 == ARM_OPCODE_B) || (opcode1 == ARM_OPCODE_BL); // inconditional jump
         boolean isCB = false; // disable for now ... isCB =(opcode2 == ARM_OPCODE_CBZ) || (opcode2 == ARM_OPCODE_CBNZ); // conditional jump

         if ((isBL == false) && (isCB == false)) 
         {                
            // Not a relative jump 
            dst[dstIdx]   = src[srcIdx];
            dst[dstIdx+1] = src[srcIdx+1];
            dst[dstIdx+2] = src[srcIdx+2];
            dst[dstIdx+3] = src[srcIdx+3];
            srcIdx += 4;
            dstIdx += 4;
            continue;
         }

         // Decode absolute address
         int val, addr;

         if (isBL == true) 
         {
            addr = (instr & ARM_B_ADDR_MASK) << 2;
            final int offset = (addr - dstIdx) >> 2;
            val = opcode1 | (offset & ARM_B_ADDR_MASK);
         }
         else 
         {
            addr = ((instr & ARM_CB_ADDR_MASK) >> ARM_CB_REG_BITS) << 2;
            final int offset = (addr - dstIdx) >> 2;
            val = (instr & ~ARM_CB_ADDR_MASK) | (offset << ARM_CB_REG_BITS);              
         }

         if (addr == 0) 
         {
            // False positive
            fpRun++;

            // Early exit if likely outside a code section
            if (fpRun == 4)
                break;

            dst[dstIdx]   = src[srcIdx+4];
            dst[dstIdx+1] = src[srcIdx+5];
            dst[dstIdx+2] = src[srcIdx+6];
            dst[dstIdx+3] = src[srcIdx+7];
            srcIdx += 8;
            dstIdx += 4;
            continue;
         } 

         fpRun = 0;       
         LittleEndian.writeInt32(dst, dstIdx, val);
         srcIdx += 4;
         dstIdx += 4;
      }
 
      final int end = input.length + input.index;
      System.arraycopy(src, srcIdx, dst, dstIdx, end-srcIdx);
      dstIdx += (end-srcIdx);
      input.index = end;
      output.index += dstIdx;
      return true;
   }

    
   @Override
   public int getMaxEncodedLength(int srcLen)
   {
      // Allocate some extra buffer for incompressible data.
      return (srcLen <= 256) ? srcLen + 32 : srcLen + srcLen / 8;
   }


   private byte detectType(byte[] src, int start, int count)
   {
      // Let us check the first bytes ... but this may not be the first block
      // Best effort
      final int magic = Magic.getType(src);
      this.arch = 0;

      if (this.parseHeader(src, start, count, magic) == true) 
      {
         if ((this.arch == ELF_X86_ARCH) || (this.arch == ELF_AMD64_ARCH))
            return X86;

         if ((this.arch == WIN_X86_ARCH) || (this.arch == WIN_AMD64_ARCH))
            return X86;

         if (this.arch == MAC_AMD64_ARCH)
            return X86;

         if ((this.arch == ELF_ARM64_ARCH) || (this.arch == WIN_ARM64_ARCH))
            return ARM64;

         if (this.arch == MAC_ARM64_ARCH)
            return ARM64;
      } 
      
      int jumpsX86 = 0;
      int jumpsARM64 = 0;
      int zeros = 0;
      int smallVals = 0;
      final int end = start + count;

      for (int i=start; i<end; i++) 
      {
         if (src[i] < 16) 
         {
            smallVals++;
            
            if (src[i] == 0)
               zeros++;
         }

         // X86
         if ((src[i] & X86_MASK_JUMP) == X86_INSTRUCTION_JUMP)
         {
            if ((src[i+4] == 0) || (src[i+4] == 0xFF)) 
            {
               // Count relative jumps (CALL = E8/ JUMP = E9 .. .. .. 00/FF)
               jumpsX86++;
            }
         } 
         else if ((src[i] == X86_TWO_BYTE_PREFIX) && ((src[i+1] & X86_MASK_JCC) == X86_INSTRUCTION_JCC)) 
         {
            i++;
            
            if ((src[i] == 0x38) || (src[i] == 0x3A))
               i++; 
               
            // Count relative conditional jumps (0x0F 0x8?)
            // Only count those with 16/32 offsets
            jumpsX86++;
         }

         // ARM
         if ((i & 3) != 0)
            continue;

         final int instr = LittleEndian.readInt32(src, i);
         final int opcode1 = instr & ARM_B_OPCODE_MASK;
         final int opcode2 = instr & ARM_CB_OPCODE_MASK;

         if ((opcode1 == ARM_OPCODE_B) || (opcode1 == ARM_OPCODE_BL))
            jumpsARM64++;
         else if ((opcode2 == ARM_OPCODE_CBZ) || (opcode2 == ARM_OPCODE_CBNZ))
            jumpsARM64++;
      }

      if (zeros < (count / 10))
         return NOT_EXE;

      // Filter out (some/many) multimedia files
      if (smallVals > (count / 2))
         return NOT_EXE;

      // Ad-hoc thresholds
      if (jumpsX86 >= (count / 200)) 
         return X86;

      if (jumpsARM64 >= (count / 200))
         return ARM64;

      // Number of jump instructions too small => either not a binary
      // or not worth the change, skip.
      return NOT_EXE;
   }
   
   
   // Return true if known header
   private boolean parseHeader(byte[] src, int start, int count, int magic) 
   {
      if (magic == Magic.WIN_MAGIC)
      {
         if (count >= 64) 
         {
            this.arch = LittleEndian.readInt32(src, start+18);
            final int posPE = LittleEndian.readInt32(src, start+60);

            if ((posPE > 0) && (posPE <= count-48) && (LittleEndian.readInt32(src, start+posPE) == WIN_PE)) 
            {
               this.codeStart = Math.min(start+LittleEndian.readInt32(src, start+posPE+44), start+count);
               this.codeEnd = Math.min(start+LittleEndian.readInt32(src, start+posPE+28), start+count);
               this.arch = LittleEndian.readInt16(src, start+posPE+4);                     
            }

            return true;
         }
      } 
      else if (magic == Magic.ELF_MAGIC) 
      {
         boolean isLittleEndian = src[5] == 1;
         
         if (count >= 32) 
         {
            if (isLittleEndian == true)
            {
               if (src[start+4] == 2)
                  this.codeStart = 0x40 + (int) LittleEndian.readInt16(src, 0x36) * (int) LittleEndian.readInt16(src, 0x38);
               else 
                  this.codeStart = 0x34 + (int) LittleEndian.readInt16(src, 0x2A) * (int) LittleEndian.readInt16(src, 0x2C);
               
               this.arch = LittleEndian.readInt16(src, start+18);
            }
            else
            {
               if (src[start+4] == 2)
                  this.codeStart = 0x40 + (int) LittleEndian.readInt16(src, 0x36) * (int) LittleEndian.readInt16(src, 0x38);
               else 
                  this.codeStart = 0x34 + (int) LittleEndian.readInt16(src, 0x2A) * (int) LittleEndian.readInt16(src, 0x2C);

               this.arch = BigEndian.readInt16(src, start+18);
            }
            
            this.codeStart = Math.min(this.codeStart, count);
            return true;
         }
      }
      else if ((magic == Magic.MAC_MAGIC32) || (magic == Magic.MAC_CIGAM32) || 
               (magic == Magic.MAC_MAGIC64) || (magic == Magic.MAC_CIGAM64)) 
      { 

         if (count >= 8)
            this.arch = LittleEndian.readInt32(src, start+4);

         codeStart = 0x20; // just ignore Mach-O header for now
         return true;
      }

      return false;
   }   
}