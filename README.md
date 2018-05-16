kanzi
=====


Lossless data compression in Java.
The goal is to provide clean APIs and really fast implementation.
It includes compression codecs (Run Length coding, Exp Golomb coding, Huffman, Range, LZ4, Snappy, ANS, Context Mixers, PAQ derivatives), bit stream manipulation, and transforms such as Burrows-Wheeler (BWT) and Move-To-Front, etc ...



For more details, check https://github.com/flanglet/kanzi/wiki.

Credits

Matt Mahoney,
Yann Collet,
Jan Ondrus,
Yuta Mori,
Ilya Muravyov,
Neal Burns,
Fabian Giesen,
Jarek Duda

Disclaimer

Use at your own risk. Always keep a backup of your files.



Silesia corpus benchmark
-------------------------

i7-7700K @4.20GHz, 32GB RAM, Ubuntu 16.04

Java Hotspot 10.0.1

Kanzi version 1.4 Java implementation. Block size is 100 MB. 



|        Compressor           | Encoding (sec)  | Decoding (sec)  |    Size          |
|-----------------------------|-----------------|-----------------|------------------|
|Original     	              |                 |                 |   211,938,580    |	
|**Kanzi -l 1**               |  	   **2.8** 	  |     **1.8**     |  **80,790,093**  |
|**Kanzi -l 1 -j 12**         |  	   **1.9** 	  |     **1.3**     |  **80,790,093**  |
|Gzip 1.6	                    |        6.0      |       1.0       |    68,227,965    |        
|Gzip 1.6	-9                  |       14.3      |       1.0       |    67,631,990    |        
|Zstd 1.3.3 -13               |	      11.9      |       0.3       |    58,789,182    |
|Lzma 5.2.2 -3	              |       24.3	    |       2.4       |    55,743,540    |
|**Kanzi -l 2**               |	    **11.4**	  |     **7.3**     |  **55,728,473**  |
|**Kanzi -l 2 -j 12**         |	     **5.1**	  |     **3.4**     |  **55,728,473**  |
|Bzip2 1.0.6 -9	              |       14.1      |       4.8       |    54,506,769	   |
|Zstd 1.3.3 -19	              |       45.2      |       0.4       |    53,977,895    |
|**Kanzi -l 3**               |	    **18.1**	  |    **13.1**     |  **51,781,285**  |
|**Kanzi -l 3 -j 12**         |      **7.3**    |     **8.3**     |  **51,781,285**  |
|**Kanzi -l 4**	              |     **20.1**    |    **16.4**     |  **49,460,294**  |
|**Kanzi -l 4 -j 12**         |      **8.1**    |     **8.9**     |  **49,460,294**  |
|Lzma 5.2.2 -9                |       65.0	    |       2.4       |    48,780,457    |
|**Kanzi -l 5**               |     **30.8**	  |    **26.5**     |  **46,485,064**  |
|**Kanzi -l 5 -j 12**         |     **11.3**	  |    **11.2**     |  **46,485,064**  |
|Tangelo 2.4	                |       83.2      |      85.9       |    44,862,127    |
|zpaq v7.14 m4 t1             |      107.3	    |     112.2       |    42,628,166    |
|zpaq v7.14 m4 t12            |      108.1	    |     111.5       |    42,628,166    |
|Tangelo 2.0	                |      302.0    	|     310.9       |    41,267,068    |
|**Kanzi -l 6**               |     **83.6**	  |    **84.3**     |  **41,144,431**  |
|**Kanzi -l 6 -j 12**         |     **33.7**	  |    **33.0**     |  **41,144,431**  |
|zpaq v7.14 m5 t1             |	     343.1	    |     352.0       |    39,112,924    |
|zpaq v7.14 m5 t12            |	     344.3	    |     350.4       |    39,112,924    |


enwik8
-------

i7-7700K @4.20GHz, 32GB RAM, Ubuntu 18.04

g++ version 7.3.0

Kanzi version 1.4 C++ implementation. Block size is 100 MB. 1 thread


|        Compressor           | Encoding (sec)  | Decoding (sec)  |    Size          |
|-----------------------------|-----------------|-----------------|------------------|
|Original     	              |                 |                 |   100,000,000    |	
|**Kanzi -l 1**               |  	   **2.2** 	  |     **1.4**     |  **37,389,414**  |
|**Kanzi -l 2**               |      **5.8**    |     **4.2**     |  **25,439,827**  |        
|**Kanzi -l 3**               |	    **11.3**    |     **7.8**     |  **22,744,813**  |
|**Kanzi -l 4**               |	    **12.5**	  |     **9.2**     |  **22,096,910**  |
|**Kanzi -l 5**               |	    **17.2**	  |    **13.5**     |  **20,791,382**  |
|**Kanzi -l 6**               |	    **35.4**	  |    **36.0**     |  **19,464,686**  |

