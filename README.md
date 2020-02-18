kanzi
=====


State-of-the-art lossless data compression in Java.
The goal is to provide clean APIs and really fast implementation.
It includes compression codecs (Run Length coding, Exp Golomb coding, Huffman, Range, LZ, ANS, Context Mixers, PAQ derivatives), bit stream manipulation, and transforms such as Burrows-Wheeler (BWT) and Move-To-Front, etc ...

Kanzi is the most versatile lossless data compressor in Java. However, it is not an implementation of usual compression formats (zip, Zstandard, LZMA, ...).


For more details, check https://github.com/flanglet/kanzi/wiki.

Credits

Matt Mahoney,
Yann Collet,
Jan Ondrus,
Yuta Mori,
Ilya Muravyov,
Neal Burns,
Fabian Giesen,
Jarek Duda,
Ilya Grebnov

Disclaimer

Use at your own risk. Always keep a backup of your files.

[![Build Status](https://travis-ci.org/flanglet/kanzi.svg?branch=master)](https://travis-ci.org/flanglet/kanzi)

Silesia corpus benchmark
-------------------------

i7-7700K @4.20GHz, 32GB RAM, Ubuntu 18.04

jdk12 - java 12 2019-03-19

Kanzi version 1.7 Java implementation. Block size is 100 MB. 


|        Compressor           | Encoding (sec)  | Decoding (sec)  |    Size          |
|-----------------------------|-----------------|-----------------|------------------|
|Original     	              |                 |                 |   211,938,580    |	
|**Kanzi -l 1**               |  	   **3.1** 	  |     **1.7**     |  **74,599,655**  |
|**Kanzi -l 1 -j 6**          |  	   **1.6** 	  |     **1.1**     |  **74,599,655**  |
|Gzip 1.6	                    |        6.0      |       1.0       |    68,227,965    |        
|Gzip 1.6	-9                  |       14.3      |       1.0       |    67,631,990    |        
|**Kanzi -l 2**               |	     **6.2**	  |     **3.2**     |  **61,679,732**  |
|**Kanzi -l 2 -j 6**          |	     **2.6**	  |     **1.7**     |  **61,679,732**  |
|Zstd 1.3.3 -13               |	      11.9      |       0.3       |    58,789,182    |
|Brotli 1.0.5 -9              |       94.3      |       1.4       |    56,289,305    |
|**Kanzi -l 3**               |	    **11.3**	  |     **7.5**     |  **55,952,061**  |
|**Kanzi -l 3 -j 6**          |	     **4.1**	  |     **2.6**     |  **55,952,061**  |
|Lzma 5.2.2 -3	              |       24.3	    |       2.4       |    55,743,540    |
|Bzip2 1.0.6 -9	              |       14.1      |       4.8       |    54,506,769	   |
|Zstd 1.3.3 -19	              |       45.2      |       0.4       |    53,977,895    |
|**Kanzi -l 4**               |	    **17.2**	  |     **9.0**     |  **51,754,417**  |
|**Kanzi -l 4 -j 6**          |      **7.0**    |     **3.6**     |  **51,754,417**  |
|Lzma 5.2.2 -9                |       65.0	    |       2.4       |    48,780,457    |
|**Kanzi -l 5**	              |     **21.5**    |    **12.0**     |  **48,256,346**  |
|**Kanzi -l 5 -j 6**          |      **9.0**    |     **4.4**     |  **48,256,346**  |
|**Kanzi -l 6**               |     **28.0**	  |    **19.4**     |  **46,394,304**  |
|**Kanzi -l 6 -j 6**          |     **11.0**	  |     **6.6**     |  **46,394,304**  |
|Tangelo 2.4	                |       83.2      |      85.9       |    44,862,127    |
|zpaq v7.14 m4 t1             |      107.3	    |     112.2       |    42,628,166    |
|zpaq v7.14 m4 t12            |      108.1	    |     111.5       |    42,628,166    |
|**Kanzi -l 7**               |     **65.8**	  |    **66.2**     |  **41,862,443**  |
|**Kanzi -l 7 -j 6**          |     **22.5**	  |    **21.8**     |  **41,862,443**  |
|Tangelo 2.0	                |      302.0    	|     310.9       |    41,267,068    |
|**Kanzi -l 8**               |     **95.5**	  |    **97.2**     |  **40,473,911**  |
|**Kanzi -l 8 -j 6**          |     **35.4**	  |    **36.0**     |  **40,473,911**  |
|zpaq v7.14 m5 t1             |	     343.1	    |     352.0       |    39,112,924    |
|zpaq v7.14 m5 t12            |	     344.3	    |     350.4       |    39,112,924    |


enwik8
-------

i7-7700K @4.20GHz, 32GB RAM, Ubuntu 18.04

jdk12 - java 12 2019-03-19

Kanzi version 1.7 Java implementation. Block size is 100 MB. 1 thread


|        Compressor           | Encoding (sec)  | Decoding (sec)  |    Size          |
|-----------------------------|-----------------|-----------------|------------------|
|Original     	              |                 |                 |   100,000,000    |	
|**Kanzi -l 1**               |  	  **2.04** 	  |    **1.26**     |  **33,869,944**  |
|**Kanzi -l 2**               |     **3.30**    |    **2.01**     |  **27,404,489**  |        
|**Kanzi -l 3**               |	    **5.51**    |    **3.65**     |  **25,661,699**  |
|**Kanzi -l 4**               |	    **7.62**	  |    **4.00**     |  **22,478,636**  |
|**Kanzi -l 5**               |	   **10.46**	  |    **5.37**     |  **21,275,446**  |
|**Kanzi -l 6**               |	   **13.38**	  |    **8.18**     |  **20,869,366**  |
|**Kanzi -l 7**               |	   **25.06**	  |   **25.01**     |  **19,570,938**  |
|**Kanzi -l 8**               |	   **35.78**	  |   **36.51**     |  **19,141,858**  |

