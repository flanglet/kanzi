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

openjdk version "17" 2021-09-14

Kanzi version 2.0 Java implementation. Block size is 100 MB. 


|        Compressor               | Encoding (sec)  | Decoding (sec)  |    Size          |
|---------------------------------|-----------------|-----------------|------------------|
|Original     	                  |                 |                 |   211,938,580    |
|Zstd 1.5.0 -2 --long=30          |	       0.9      |       0.3       |    68,745,610    |
|Zstd 1.5.0 -2 -T6 --long=30      |	       0.4      |       0.3       |    68,745,610    |
|**Kanzi -l 1**                   |    	 **3.3**    |     **1.6**     |  **68,471,355**  |
|**Kanzi -l 1 -j 6**              |      **1.3**    |     **1.0**     |  **68,471,355**  |
|Pigz 1.6 -6 -p6                  |        1.4      |       1.4       |    68,237,849    |
|Gzip 1.6 -6                      |        6.1      |       1.1       |    68,227,965    |
|Brotli 1.0.9 -2 --large_window=30|        1.5      |       0.8       |    68,033,377    |
|Pigz 1.6 -9 -p6                  |        3.0      |       1.6       |    67,656,836    |
|Gzip 1.6 -9                      |       14.0      |       1.0       |    67,631,990    |
|**Kanzi -l 2**                   |      **4.6**    |     **1.6**     |  **64,522,501**  |
|**Kanzi -l 2 -j 6**              |      **1.9**    |     **1.0**     |  **64,522,501**  |
|Brotli 1.0.9 -4 --large_window=30|        4.1      |       0.7       |    64,267,169    |
|Zstd 1.5.0 -9 --long=30          |	       3.3      |       0.3       |    59,874,613    |
|Zstd 1.5.0 -9 -T6 --long=30      |        1.5      |       0.3       |    59,874,613    |
|**Kanzi -l 3**                   |      **5.7**    |     **2.6**     |  **59,652,799**  |
|**Kanzi -l 3 -j 6**              |      **2.3**    |     **1.5**     |  **59,652,799**  |
|Zstd 1.5.0 -13 --long=30         |	      15.4      |       0.3       |    58,055,136    |
|Zstd 1.5.0 -13 -T6 --long=30     |	       8.9      |       0.3       |    58,055,136    |
|Orz 1.5.0                        |	       7.7      |       2.0       |    57,564,831    |
|Brotli 1.0.9 -9 --large_window=30|       36.7      |       0.7       |    56,232,817    |
|Lzma 5.2.2 -3	                  |       24.1	    |       2.6       |    55,743,540    |
|**Kanzi -l 4**                   |      **9.5**    |     **6.2**     |  **54,998,230**  |
|**Kanzi -l 4 -j 6**              |      **3.8**    |     **2.4**     |  **54,998,230**  |
|Bzip2 1.0.6 -9	                  |       14.9      |       5.2       |    54,506,769	 |
|Zstd 1.5.0 -19 --long=30	        |       59.7      |       0.4       |    52,773,547    |
|Zstd 1.5.0 -19	-T6 --long=30     |       59.7      |       0.4       |    52,773,547    |
|**Kanzi -l 5**                   |     **16.2**    |     **6.5**     |  **51,760,234**  |
|**Kanzi -l 5 -j 6**              |      **6.2**    |     **2.5**     |  **51,760,234**  |
|Brotli 1.0.9 --large_window=30   |      356.2	    |       0.9       |    49,383,136    |
|Lzma 5.2.2 -9                    |       65.6	    |       2.5       |    48,780,457    |
|**Kanzi -l 6**	                  |     **19.6**    |     **8.9**     |  **48,067,980**  |
|**Kanzi -l 6 -j 6**              |      **7.4**    |     **3.4**     |  **48,067,980**  |
|bsc 3.2.3 -b100 -T -t            |        8.8      |       6.0       |    46,932,394    |
|bsc 3.2.3 -b100                  |        5.4      |       4.9       |    46,932,394    |
|BCM 1.65 -b100                   |       15.5      |      21.1       |    46,506,716    |
|**Kanzi -l 7**                   |     **23.7**    |    **13.5**     |  **46,446,999**  |
|**Kanzi -l 7 -j 6**              |      **8.6**    |     **5.4**     |  **46,446,999**  |
|Tangelo 2.4	                    |       83.2      |      85.9       |    44,862,127    |
|zpaq v7.14 m4 t1                 |      107.3	    |     112.2       |    42,628,166    |
|zpaq v7.14 m4 t12                |      108.1	    |     111.5       |    42,628,166    |
|**Kanzi -l 8**                   |     **60.5**    |    **62.2**     |  **41,830,871**  |
|**Kanzi -l 8 -j 6**              |     **21.7**    |    **19.8**     |  **41,830,871**  |
|Tangelo 2.0	                    |      302.0      |     310.9       |    41,267,068    |
|**Kanzi -l 9**                   |     **89.0**    |    **91.8**     |  **40,369,883**  |
|**Kanzi -l 9 -j 6**              |     **36.6**    |    **34.7**     |  **40,369,883**  |
|zpaq v7.14 m5 t1                 |      343.1	    |     352.0       |    39,112,924    |
|zpaq v7.14 m5 t12                |	     344.3	    |     350.4       |    39,112,924    |



enwik8
-------

i7-7700K @4.20GHz, 32GB RAM, Ubuntu 20.04

openjdk version "17" 2021-09-14

Kanzi version 2.0 Java implementation. Block size is 100 MB. 1 thread


|        Compressor           | Encoding (sec)  | Decoding (sec)  |    Size          |
|-----------------------------|-----------------|-----------------|------------------|
|Original     	              |                 |                 |   100,000,000    |
|**Kanzi -l 1**               |     **1.91**    |    **1.06**     |  **32,650,127**  |
|**Kanzi -l 2**               |     **2.51**    |    **1.07**     |  **31,018,886**  |
|**Kanzi -l 3**               |     **3.06**    |    **1.58**     |  **27,330,407**  |
|**Kanzi -l 4**               |	    **4.75**    |    **3.09**     |  **25,670,919**  |
|**Kanzi -l 5**               |	    **6.87**    |    **2.70**     |  **22,490,796**  |
|**Kanzi -l 6**               |	    **9.54**    |    **3.98**     |  **21,232,303**  |
|**Kanzi -l 7**               |	   **12.19**    |    **6.16**     |  **20,935,522**  |
|**Kanzi -l 8**               |	   **24.28**    |   **24.98**     |  **19,671,830**  |
|**Kanzi -l 9**               |	   **35.41**    |   **36.42**     |  **19,097,962**  |

