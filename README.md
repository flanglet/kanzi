kanzi
=====


Kanzi is a modern, modular, expendable and efficient lossless data compressor implemented in Java.

* modern: state-of-the-art algorithms are implemented and multi-core CPUs can take advantage of the built-in multi-threading.
* modular: entropy codec and a combination of transforms can be provided at runtime to best match the kind of data to compress.
* expandable: clean design with heavy use of interfaces as contracts makes integrating and expanding the code easy. No dependencies.
* efficient: the code is optimized for efficiency (trade-off between compression ratio and speed).

Kanzi supports a wide range of compression ratios and can compress many files more than most common compressors (at the cost of decompression speed).
It is not compatible with standard compression formats. 


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

![Build Status](https://github.com/flanglet/kanzi/actions/workflows/ant.yml/badge.svg)

[![Language grade: Java](https://img.shields.io/lgtm/grade/java/g/flanglet/kanzi.svg?logo=lgtm&logoWidth=18)](https://lgtm.com/projects/g/flanglet/kanzi/context:java)

Silesia corpus benchmark
-------------------------

i7-7700K @4.20GHz, 32GB RAM, Ubuntu 20.04

openjdk version "16" 2021-03-16

Kanzi version 1.9 Java implementation. Block size is 100 MB. 


|        Compressor               | Encoding (sec)  | Decoding (sec)  |    Size          |
|---------------------------------|-----------------|-----------------|------------------|
|Original     	                  |                 |                 |   211,938,580    |	
|Zstd 1.4.8 -2 --long=30          |	       1.2      |       0.3       |    68,761,465    |
|Zstd 1.4.8 -2 -T6 --long=30      |	       0.7      |       0.3       |    68,761,465    |
|**Kanzi -l 1**                   |  	   **3.3** 	  |     **1.6**     |  **68,471,355**  |
|**Kanzi -l 1 -j 6**              |  	   **1.5** 	  |     **1.0**     |  **68,471,355**  |
|Pigz 1.6 -6 -p6                  |        1.4      |       1.4       |    68,237,849    |        
|Gzip 1.6 -6                      |        6.1      |       1.1       |    68,227,965    |   
|Brotli 1.0.9 -2 --large_window=30|        1.5      |       0.8       |    68,033,377    |
|Pigz 1.6 -9 -p6                  |        3.0      |       1.6       |    67,656,836    |
|Gzip 1.6 -9                      |       14.0      |       1.0       |    67,631,990    |        
|**Kanzi -l 2**                   |	     **4.5**	  |     **1.6**     |  **64,522,501**  |
|**Kanzi -l 2 -j 6**              |	     **1.9**	  |     **1.0**     |  **64,522,501**  |
|Brotli 1.0.9 -4 --large_window=30|        4.1      |       0.7       |    64,267,169    |
|Zstd 1.4.8 -9 --long=30          |	       5.3      |       0.3       |    59,937,600    |
|Zstd 1.4.8 -9 -T6 --long=30      |	       2.8      |       0.3       |    59,937,600    |
|**Kanzi -l 3**                   |	     **6.0**	  |     **3.4**     |  **59,647,212**  |
|**Kanzi -l 3 -j 6**              |	     **2.5**	  |     **1.6**     |  **59,647,212**  |
|Zstd 1.4.8 -13 --long=30         |	      16.0      |       0.3       |    58,065,257    |
|Zstd 1.4.8 -13 -T6 --long=30     |	       9.2      |       0.3       |    58,065,257    |
|Orz 1.5.0                        |	       7.7      |       2.0       |    57,564,831    |
|Brotli 1.0.9 -9 --large_window=30|       36.7      |       0.7       |    56,232,817    |
|Lzma 5.2.2 -3	                  |       24.1	    |       2.6       |    55,743,540    |
|**Kanzi -l 4**                   |	    **10.3**	  |     **6.2**     |  **54,996,858**  |
|**Kanzi -l 4 -j 6**              |	     **4.2**	  |     **2.4**     |  **54,996,858**  |
|Bzip2 1.0.6 -9	                  |       14.9      |       5.2       |    54,506,769	   |
|Zstd 1.4.8 -19 --long=30	        |       59.9      |       0.3       |    53,039,786    |
|Zstd 1.4.8 -19	-T6 --long=30     |       59.7      |       0.4       |    53,039,786    |
|**Kanzi -l 5**                   |	    **17.1**	  |     **8.2**     |  **51,745,795**  |
|**Kanzi -l 5 -j 6**              |      **6.0**    |     **3.1**     |  **51,745,795**  |
|Brotli 1.0.9 --large_window=30   |      356.2	    |       0.9       |    49,383,136    |
|Lzma 5.2.2 -9                    |       65.6	    |       2.5       |    48,780,457    |
|**Kanzi -l 6**	                  |     **20.8**    |    **11.0**     |  **48,067,846**  |
|**Kanzi -l 6 -j 6**              |      **7.5**    |     **3.9**     |  **48,067,846**  |
|BCM 1.6.0 -7	                    |       18.0      |      22.1       |    46,506,716    |
|**Kanzi -l 7**                   |     **25.2**	  |    **15.3**     |  **46,446,991**  |
|**Kanzi -l 7 -j 6**              |      **9.1**	  |     **5.8**     |  **46,446,991**  |
|Tangelo 2.4	                    |       83.2      |      85.9       |    44,862,127    |
|zpaq v7.14 m4 t1                 |      107.3	    |     112.2       |    42,628,166    |
|zpaq v7.14 m4 t12                |      108.1	    |     111.5       |    42,628,166    |
|**Kanzi -l 8**                   |     **60.7**	  |    **62.3**     |  **41,830,871**  |
|**Kanzi -l 8 -j 6**              |     **20.2**	  |    **20.2**     |  **41,830,871**  |
|Tangelo 2.0	                    |      302.0    	|     310.9       |    41,267,068    |
|**Kanzi -l 9**                   |     **89.7**	  |    **91.6**     |  **40,369,883**  |
|**Kanzi -l 9 -j 6**              |     **36.8**	  |    **35.6**     |  **40,369,883**  |
|zpaq v7.14 m5 t1                 |	     343.1	    |     352.0       |    39,112,924    |
|zpaq v7.14 m5 t12                |	     344.3	    |     350.4       |    39,112,924    |



enwik8
-------

i7-7700K @4.20GHz, 32GB RAM, Ubuntu 20.04

openjdk version "16" 2021-03-16

Kanzi version 1.9 Java implementation. Block size is 100 MB. 1 thread


|        Compressor           | Encoding (sec)  | Decoding (sec)  |    Size          |
|-----------------------------|-----------------|-----------------|------------------|
|Original     	              |                 |                 |   100,000,000    |	
|**Kanzi -l 1**               |  	  **2.06** 	  |    **1.16**     |  **32,650,127**  |
|**Kanzi -l 2**               |     **2.63**    |    **1.15**     |  **31,018,886**  |
|**Kanzi -l 3**               |     **3.33**    |    **1.93**     |  **27,328,809**  |
|**Kanzi -l 4**               |	    **5.18**    |    **3.41**     |  **25,670,935**  |
|**Kanzi -l 5**               |	    **7.37**	  |    **3.74**     |  **22,484,700**  |
|**Kanzi -l 6**               |	    **9.97**	  |    **5.22**     |  **21,232,218**  |
|**Kanzi -l 7**               |	   **13.19**	  |    **7.35**     |  **20,935,522**  |
|**Kanzi -l 8**               |	   **24.79**	  |   **24.93**     |  **19,671,830**  |
|**Kanzi -l 9**               |	   **35.41**	  |   **36.37**     |  **19,097,962**  |

