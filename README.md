kanzi
=====


Kanzi is a modern, modular, expendable and efficient lossless data compressor implemented in Java.

* modern: state-of-the-art algorithms are implemented and multi-core CPUs can take advantage of the built-in multi-threading.
* modular: entropy codec and a combination of transforms can be provided at runtime to best match the kind of data to compress.
* expendable: clean design with heavy use of interfaces as contracts makes integrating and expanding the code easy. No dependencies.
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

Use at your own risk. Always keep a backup of your files. The bitstream format is not yet finalized.

[![Build Status](https://travis-ci.org/flanglet/kanzi.svg?branch=master)](https://travis-ci.org/flanglet/kanzi)

[![Language grade: Java](https://img.shields.io/lgtm/grade/java/g/flanglet/kanzi.svg?logo=lgtm&logoWidth=18)](https://lgtm.com/projects/g/flanglet/kanzi/context:java)

Silesia corpus benchmark
-------------------------

i7-7700K @4.20GHz, 32GB RAM, Ubuntu 18.04.05

java 14.0.1 2020-04-14

Kanzi version 1.8 Java implementation. Block size is 100 MB. 


|        Compressor           | Encoding (sec)  | Decoding (sec)  |    Size          |
|-----------------------------|-----------------|-----------------|------------------|
|Original     	              |                 |                 |   211,938,580    |	
|Gzip 1.6	-4                  |        3.4      |       1.1       |    71,045,115    |        
|**Kanzi -l 1**               |  	   **3.5** 	  |     **1.7**     |  **69,840,720**  |
|**Kanzi -l 1 -j 6**          |  	   **1.6** 	  |     **1.0**     |  **69,840,720**  |
|Zstd 1.4.5 -2                |	       0.7      |       0.3       |    69,636,234    |
|Gzip 1.6	-5                  |        4.4      |       1.1       |    69,143,980    |        
|Brotli 1.0.7 -2              |        4.4      |       2.0       |    68,033,377    |
|Gzip 1.6	-9                  |       14.3      |       1.0       |    67,631,990    |        
|**Kanzi -l 2**               |	     **6.7**	  |     **3.4**     |  **60,147,109**  |
|**Kanzi -l 2 -j 6**          |	     **2.7**	  |     **1.6**     |  **60,147,109**  |
|Zstd 1.4.5 -13               |	      16.0      |       0.3       |    58,125,865    |
|Orz 1.5.0                    |	       7.6      |       2.0       |    57,564,831    |
|Brotli 1.0.7 -9              |       92.2      |       1.7       |    56,289,305    |
|Lzma 5.2.2 -3	              |       24.3	    |       2.4       |    55,743,540    |
|**Kanzi -l 3**               |	    **11.1**	  |     **6.6**     |  **54,996,910**  |
|**Kanzi -l 3 -j 6**          |	     **4.3**	  |     **2.5**     |  **54,996,910**  |
|Bzip2 1.0.6 -9	              |       14.9      |       5.2       |    54,506,769	   |
|Zstd 1.4.5 -19	              |       61.8      |       0.3       |    53,261,006    |
|Zstd 1.4.5 -19	-T6           |       53.4      |       0.3       |    53,261,006    |
|**Kanzi -l 4**               |	    **18.1**	  |     **9.4**     |  **51,739,977**  |
|**Kanzi -l 4 -j 6**          |      **6.3**    |     **3.3**     |  **51,739,977**  |
|Lzma 5.2.2 -9                |       65.0	    |       2.4       |    48,780,457    |
|**Kanzi -l 5**	              |     **22.0**    |    **12.0**     |  **48,067,650**  |
|**Kanzi -l 5 -j 6**          |      **7.4**    |     **4.0**     |  **48,067,650**  |
|**Kanzi -l 6**               |     **26.3**	  |    **18.0**     |  **46,541,768**  |
|**Kanzi -l 6 -j 6**          |      **9.2**	  |     **5.9**     |  **46,541,768**  |
|Tangelo 2.4	                |       83.2      |      85.9       |    44,862,127    |
|zpaq v7.14 m4 t1             |      107.3	    |     112.2       |    42,628,166    |
|zpaq v7.14 m4 t12            |      108.1	    |     111.5       |    42,628,166    |
|**Kanzi -l 7**               |     **62.0**	  |    **64.5**     |  **41,804,239**  |
|**Kanzi -l 7 -j 6**          |     **21.9**	  |    **20.0**     |  **41,804,239**  |
|Tangelo 2.0	                |      302.0    	|     310.9       |    41,267,068    |
|**Kanzi -l 8**               |     **94.6**	  |    **93.6**     |  **40,423,483**  |
|**Kanzi -l 8 -j 6**          |     **34.2**	  |    **35.8**     |  **40,423,483**  |
|zpaq v7.14 m5 t1             |	     343.1	    |     352.0       |    39,112,924    |
|zpaq v7.14 m5 t12            |	     344.3	    |     350.4       |    39,112,924    |


enwik8
-------

i7-7700K @4.20GHz, 32GB RAM, Ubuntu 18.04.05

java 14.0.1 2020-04-14

Kanzi version 1.8 Java implementation. Block size is 100 MB. 1 thread


|        Compressor           | Encoding (sec)  | Decoding (sec)  |    Size          |
|-----------------------------|-----------------|-----------------|------------------|
|Original     	              |                 |                 |   100,000,000    |	
|**Kanzi -l 1**               |  	  **2.28** 	  |    **1.26**     |  **32,654,135**  |
|**Kanzi -l 2**               |     **3.57**    |    **2.13**     |  **27,410,862**  |        
|**Kanzi -l 3**               |	    **6.03**    |    **3.50**     |  **25,670,935**  |
|**Kanzi -l 4**               |	    **7.68**	  |    **4.08**     |  **22,481,393**  |
|**Kanzi -l 5**               |	   **10.46**	  |    **5.53**     |  **21,232,214**  |
|**Kanzi -l 6**               |	   **12.86**	  |    **7.48**     |  **20,951,582**  |
|**Kanzi -l 7**               |	   **25.67**	  |   **25.44**     |  **19,515,358**  |
|**Kanzi -l 8**               |	   **35.26**	  |   **36.57**     |  **19,099,778**  |

