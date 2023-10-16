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

package kanzi.entropy;

import kanzi.EntropyEncoder;
import kanzi.OutputBitStream;


public final class ExpGolombEncoder implements EntropyEncoder
{
    private static final int[][] CACHE = new int[][]
    {
       // Unsigned
       new int[]
       {
          513, 1538, 1539, 2564, 2565, 2566, 2567, 3592, 3593, 3594, 3595, 3596, 3597, 3598, 3599, 4624,
         4625, 4626, 4627, 4628, 4629, 4630, 4631, 4632, 4633, 4634, 4635, 4636, 4637, 4638, 4639, 5664,
         5665, 5666, 5667, 5668, 5669, 5670, 5671, 5672, 5673, 5674, 5675, 5676, 5677, 5678, 5679, 5680,
         5681, 5682, 5683, 5684, 5685, 5686, 5687, 5688, 5689, 5690, 5691, 5692, 5693, 5694, 5695, 6720,
         6721, 6722, 6723, 6724, 6725, 6726, 6727, 6728, 6729, 6730, 6731, 6732, 6733, 6734, 6735, 6736,
         6737, 6738, 6739, 6740, 6741, 6742, 6743, 6744, 6745, 6746, 6747, 6748, 6749, 6750, 6751, 6752,
         6753, 6754, 6755, 6756, 6757, 6758, 6759, 6760, 6761, 6762, 6763, 6764, 6765, 6766, 6767, 6768,
         6769, 6770, 6771, 6772, 6773, 6774, 6775, 6776, 6777, 6778, 6779, 6780, 6781, 6782, 6783, 7808,
         7809, 7808, 6783, 6782, 6781, 6780, 6779, 6778, 6777, 6776, 6775, 6774, 6773, 6772, 6771, 6770,
         6769, 6768, 6767, 6766, 6765, 6764, 6763, 6762, 6761, 6760, 6759, 6758, 6757, 6756, 6755, 6754,
         6753, 6752, 6751, 6750, 6749, 6748, 6747, 6746, 6745, 6744, 6743, 6742, 6741, 6740, 6739, 6738,
         6737, 6736, 6735, 6734, 6733, 6732, 6731, 6730, 6729, 6728, 6727, 6726, 6725, 6724, 6723, 6722,
         6721, 6720, 5695, 5694, 5693, 5692, 5691, 5690, 5689, 5688, 5687, 5686, 5685, 5684, 5683, 5682,
         5681, 5680, 5679, 5678, 5677, 5676, 5675, 5674, 5673, 5672, 5671, 5670, 5669, 5668, 5667, 5666,
         5665, 5664, 4639, 4638, 4637, 4636, 4635, 4634, 4633, 4632, 4631, 4630, 4629, 4628, 4627, 4626,
         4625, 4624, 3599, 3598, 3597, 3596, 3595, 3594, 3593, 3592, 2567, 2566, 2565, 2564, 1539, 1538
      },
      // Signed
      new int[]
      {
          513, 2052, 2054, 3080, 3082, 3084, 3086, 4112, 4114, 4116, 4118, 4120, 4122, 4124, 4126, 5152,
         5154, 5156, 5158, 5160, 5162, 5164, 5166, 5168, 5170, 5172, 5174, 5176, 5178, 5180, 5182, 6208,
         6210, 6212, 6214, 6216, 6218, 6220, 6222, 6224, 6226, 6228, 6230, 6232, 6234, 6236, 6238, 6240,
         6242, 6244, 6246, 6248, 6250, 6252, 6254, 6256, 6258, 6260, 6262, 6264, 6266, 6268, 6270, 7296,
         7298, 7300, 7302, 7304, 7306, 7308, 7310, 7312, 7314, 7316, 7318, 7320, 7322, 7324, 7326, 7328,
         7330, 7332, 7334, 7336, 7338, 7340, 7342, 7344, 7346, 7348, 7350, 7352, 7354, 7356, 7358, 7360,
         7362, 7364, 7366, 7368, 7370, 7372, 7374, 7376, 7378, 7380, 7382, 7384, 7386, 7388, 7390, 7392,
         7394, 7396, 7398, 7400, 7402, 7404, 7406, 7408, 7410, 7412, 7414, 7416, 7418, 7420, 7422, 8448,
         8451, 8449, 7423, 7421, 7419, 7417, 7415, 7413, 7411, 7409, 7407, 7405, 7403, 7401, 7399, 7397,
         7395, 7393, 7391, 7389, 7387, 7385, 7383, 7381, 7379, 7377, 7375, 7373, 7371, 7369, 7367, 7365,
         7363, 7361, 7359, 7357, 7355, 7353, 7351, 7349, 7347, 7345, 7343, 7341, 7339, 7337, 7335, 7333,
         7331, 7329, 7327, 7325, 7323, 7321, 7319, 7317, 7315, 7313, 7311, 7309, 7307, 7305, 7303, 7301,
         7299, 7297, 6271, 6269, 6267, 6265, 6263, 6261, 6259, 6257, 6255, 6253, 6251, 6249, 6247, 6245,
         6243, 6241, 6239, 6237, 6235, 6233, 6231, 6229, 6227, 6225, 6223, 6221, 6219, 6217, 6215, 6213,
         6211, 6209, 5183, 5181, 5179, 5177, 5175, 5173, 5171, 5169, 5167, 5165, 5163, 5161, 5159, 5157,
         5155, 5153, 4127, 4125, 4123, 4121, 4119, 4117, 4115, 4113, 3087, 3085, 3083, 3081, 2055, 2053
      }
    };

    private final int[] cache;
    private final int signed;
    private final OutputBitStream bitstream;


    public ExpGolombEncoder(OutputBitStream bitstream, boolean signed)
    {
       if (bitstream == null)
          throw new NullPointerException("ExpGolomb codec: Invalid null bitstream parameter");

       this.signed = (signed == true) ? 1 : 0;
       this.cache = CACHE[this.signed];
       this.bitstream = bitstream;
    }


    public boolean isSigned()
    {
       return this.signed == 1;
    }


    @Override
    public int encode(byte[] block, int blkptr, int count)
    {
      if ((block == null) || (blkptr+count > block.length) || (blkptr < 0) || (count < 0))
         return -1;

      final int end = blkptr + count;

      for (int i = blkptr; i<end; i++)
         this.encodeByte(block[i]);

      return count;
    }


    public void encodeByte(byte val)
    {
       if (val == 0)
       {
          // shortcut when input is 0
          this.bitstream.writeBit(1);
          return;
       }

       final int emit = this.cache[val&0xFF];
       this.bitstream.writeBits(emit&0x1FF, emit>>>9);
    }


    @Override
    public OutputBitStream getBitStream()
    {
       return this.bitstream;
    }


    @Override
    public void dispose()
    {
    }
}
