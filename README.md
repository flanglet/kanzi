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

[![Build Status](https://travis-ci.org/flanglet/kanzi.svg?branch=master)](https://travis-ci.org/flanglet/kanzi)

Silesia corpus benchmark
-------------------------

i7-7700K @4.20GHz, 32GB RAM, Ubuntu 16.04

jdk12 - java 12 2019-03-19

Kanzi version 1.6 Java implementation. Block size is 100 MB. 


|        Compressor           | Encoding (sec)  | Decoding (sec)  |    Size          |
|-----------------------------|-----------------|-----------------|------------------|
|Original     	              |                 |                 |   211,938,580    |	
|**Kanzi -l 1**               |  	   **2.6** 	  |     **1.6**     |  **76,600,331**  |
|**Kanzi -l 1 -j 6**          |  	   **1.4** 	  |     **1.1**     |  **76,600,331**  |
|Gzip 1.6	                    |        6.0      |       1.0       |    68,227,965    |        
|Gzip 1.6	-9                  |       14.3      |       1.0       |    67,631,990    |        
|**Kanzi -l 2**               |	     **5.6**	  |     **3.2**     |  **61,788,747**  |
|**Kanzi -l 2 -j 6**          |	     **2.5**	  |     **1.6**     |  **61,788,747**  |
|Zstd 1.3.3 -13               |	      11.9      |       0.3       |    58,789,182    |
|Brotli 1.0.5 -9              |       94.3      |       1.4       |    56,289,305    |
|**Kanzi -l 3**               |	     **9.4**	  |     **6.5**     |  **55,983,177**  |
|**Kanzi -l 3 -j 6**          |	     **3.8**	  |     **2.5**     |  **55,983,177**  |
|Lzma 5.2.2 -3	              |       24.3	    |       2.4       |    55,743,540    |
|Bzip2 1.0.6 -9	              |       14.1      |       4.8       |    54,506,769	   |
|Zstd 1.3.3 -19	              |       45.2      |       0.4       |    53,977,895    |
|**Kanzi -l 4**               |	    **22.1**	  |     **8.4**     |  **51,800,821**  |
|**Kanzi -l 4 -j 6**          |      **7.8**    |     **3.2**     |  **51,800,821**  |
|Lzma 5.2.2 -9                |       65.0	    |       2.4       |    48,780,457    |
|**Kanzi -l 5**	              |     **20.8**    |    **11.2**     |  **48,279,102**  |
|**Kanzi -l 5 -j 6**          |      **9.5**    |     **3.9**     |  **48,279,102**  |
|**Kanzi -l 6**               |     **32.3**	  |    **22.0**     |  **46,485,189**  |
|**Kanzi -l 6 -j 6**          |     **12.0**	  |     **7.1**     |  **46,485,189**  |
|Tangelo 2.4	                |       83.2      |      85.9       |    44,862,127    |
|zpaq v7.14 m4 t1             |      107.3	    |     112.2       |    42,628,166    |
|zpaq v7.14 m4 t12            |      108.1	    |     111.5       |    42,628,166    |
|**Kanzi -l 7**               |     **62.4**	  |    **64.4**     |  **41,892,099**  |
|**Kanzi -l 7 -j 6**          |     **22.9**	  |    **22.2**     |  **41,892,099**  |
|Tangelo 2.0	                |      302.0    	|     310.9       |    41,267,068    |
|**Kanzi -l 8**               |     **96.4**	  |    **98.8**     |  **40,502,391**  |
|**Kanzi -l 8 -j 6**          |     **35.2**	  |    **35.4**     |  **40,502,391**  |
|zpaq v7.14 m5 t1             |	     343.1	    |     352.0       |    39,112,924    |
|zpaq v7.14 m5 t12            |	     344.3	    |     350.4       |    39,112,924    |


enwik8
-------

i7-7700K @4.20GHz, 32GB RAM, Ubuntu 18.04

jdk12 - java 12 2019-03-19

Kanzi version 1.6 Java implementation. Block size is 100 MB. 1 thread


|        Compressor           | Encoding (sec)  | Decoding (sec)  |    Size          |
|-----------------------------|-----------------|-----------------|------------------|
|Original     	              |                 |                 |   100,000,000    |	
|**Kanzi -l 1**               |  	  **1.81** 	  |    **1.15**     |  **34,135,723**  |
|**Kanzi -l 2**               |     **3.27**    |    **2.01**     |  **27,450,033**  |        
|**Kanzi -l 3**               |	    **4.95**    |    **3.65**     |  **25,695,567**  |
|**Kanzi -l 4**               |	    **7.48**	  |    **3.70**     |  **22,512,452**  |
|**Kanzi -l 5**               |	   **10.19**	  |    **5.37**     |  **21,301,346**  |
|**Kanzi -l 6**               |	   **16.65**	  |   **10.78**     |  **20,791,496**  |
|**Kanzi -l 7**               |	   **25.07**	  |   **25.65**     |  **19,597,394**  |
|**Kanzi -l 8**               |	   **35.18**	  |   **36.12**     |  **19,163,098**  |

