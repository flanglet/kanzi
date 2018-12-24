kanzi
=====


State-of-the-art lossless data compression in Java.
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

OpenJDK build 11+28

Kanzi version 1.5 Java implementation. Block size is 100 MB. 


|        Compressor           | Encoding (sec)  | Decoding (sec)  |    Size          |
|-----------------------------|-----------------|-----------------|------------------|
|Original     	              |                 |                 |   211,938,580    |	
|**Kanzi -l 1**               |  	   **2.6** 	  |     **1.6**     |  **80,003,837**  |
|**Kanzi -l 1 -j 12**         |  	   **1.6** 	  |     **1.2**     |  **80,003,837**  |
|Gzip 1.6	                    |        6.0      |       1.0       |    68,227,965    |        
|Gzip 1.6	-9                  |       14.3      |       1.0       |    67,631,990    |        
|**Kanzi -l 2**               |	     **6.1**	  |     **3.6**     |  **63,878,466**  |
|**Kanzi -l 2 -j 12**         |	     **2.7**	  |     **1.9**     |  **63,878,466**  |
|Zstd 1.3.3 -13               |	      11.9      |       0.3       |    58,789,182    |
|Brotli 1.0.5 -9              |       94.3      |       1.4       |    56,289,305    |
|Lzma 5.2.2 -3	              |       24.3	    |       2.4       |    55,743,540    |
|**Kanzi -l 3**               |	    **10.4**	  |     **7.4**     |  **55,594,153**  |
|**Kanzi -l 3 -j 12**         |	     **4.7**	  |     **3.2**     |  **55,594,153**  |
|Bzip2 1.0.6 -9	              |       14.1      |       4.8       |    54,506,769	   |
|Zstd 1.3.3 -19	              |       45.2      |       0.4       |    53,977,895    |
|**Kanzi -l 4**               |	    **16.3**	  |    **11.9**     |  **51,795,306**  |
|**Kanzi -l 4 -j 12**         |      **6.9**    |     **7.7**     |  **51,795,306**  |
|**Kanzi -l 5**	              |     **19.2**    |    **15.2**     |  **49,455,342**  |
|**Kanzi -l 5 -j 12**         |      **8.3**    |     **8.4**     |  **49,455,342**  |
|Lzma 5.2.2 -9                |       65.0	    |       2.4       |    48,780,457    |
|**Kanzi -l 6**               |     **28.8**	  |    **25.8**     |  **46,485,165**  |
|**Kanzi -l 6 -j 12**         |     **10.6**	  |    **11.9**     |  **46,485,165**  |
|Tangelo 2.4	                |       83.2      |      85.9       |    44,862,127    |
|zpaq v7.14 m4 t1             |      107.3	    |     112.2       |    42,628,166    |
|zpaq v7.14 m4 t12            |      108.1	    |     111.5       |    42,628,166    |
|**Kanzi -l 7**               |     **63.3**	  |    **65.1**     |  **41,838,503**  |
|**Kanzi -l 7 -j 12**         |     **21.9**	  |    **25.6**     |  **41,838,503**  |
|Tangelo 2.0	                |      302.0    	|     310.9       |    41,267,068    |
|**Kanzi -l 8**               |     **89.4**	  |    **91.5**     |  **40,844,691**  |
|**Kanzi -l 8 -j 12**         |     **36.5**	  |    **36.4**     |  **40,844,691**  |
|zpaq v7.14 m5 t1             |	     343.1	    |     352.0       |    39,112,924    |
|zpaq v7.14 m5 t12            |	     344.3	    |     350.4       |    39,112,924    |


enwik8
-------

i7-7700K @4.20GHz, 32GB RAM, Ubuntu 18.04

OpenJDK build 11+28

Kanzi version 1.5 Java implementation. Block size is 100 MB. 1 thread


|        Compressor           | Encoding (sec)  | Decoding (sec)  |    Size          |
|-----------------------------|-----------------|-----------------|------------------|
|Original     	              |                 |                 |   100,000,000    |	
|**Kanzi -l 1**               |  	  **1.84** 	  |    **1.13**     |  **35,611,290**  |
|**Kanzi -l 2**               |     **3.35**    |    **2.03**     |  **28,468,601**  |        
|**Kanzi -l 3**               |	    **5.30**    |    **3.84**     |  **25,517,555**  |
|**Kanzi -l 4**               |	    **7.57**	  |    **4.91**     |  **22,512,813**  |
|**Kanzi -l 5**               |	    **9.48**	  |    **6.68**     |  **21,934,022**  |
|**Kanzi -l 6**               |	   **16.92**	  |   **12.98**     |  **20,791,492**  |
|**Kanzi -l 7**               |	   **26.94**	  |   **27.73**     |  **19,613,190**  |
|**Kanzi -l 8**               |	   **36.42**	  |   **37.61**     |  **19,284,434**  |

