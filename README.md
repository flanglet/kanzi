# Kanzi


Kanzi is a modern, modular, expandable and efficient lossless data compressor implemented in Java.

* modern: state-of-the-art algorithms are implemented and multi-core CPUs can take advantage of the built-in multi-threading.
* modular: entropy codec and a combination of transforms can be provided at runtime to best match the kind of data to compress.
* expandable: clean design with heavy use of interfaces as contracts makes integrating and expanding the code easy. No dependencies.
* efficient: the code is optimized for efficiency (trade-off between compression ratio and speed).

Unlike the most common lossless data compressors, Kanzi uses a variety of different compression algorithms and supports a wider range of compression ratios as a result. 
Most usual compressors do not take advantage of the many cores and threads available on modern CPUs (what a waste!). Kanzi is concurrent by design and uses threads to compress several blocks in parallel. It is not compatible with standard compression formats. 

Kanzi is a lossless data compressor, not an archiver. It uses checksums (optional but recommended) to validate data integrity but does not have a mechanism for data recovery. 
It also lacks data deduplication across files. However, Kanzi generates a bitstream that is seekable (one or several consecutive blocks can be decompressed without the need for the whole bitstream to be decompressed).


For more details, check https://github.com/flanglet/kanzi/wiki.

See how to reuse the code here: https://github.com/flanglet/kanzi/wiki/Using-and-extending-the-code

There is a C++ implementation available here: https://github.com/flanglet/kanzi-cpp

There is Go implementation available here: https://github.com/flanglet/kanzi-go


![Build Status](https://github.com/flanglet/kanzi/actions/workflows/ant.yml/badge.svg)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=flanglet_kanzi&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=flanglet_kanzi)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)


## Why Kanzi

There are many excellent, open-source lossless data compressors available already.

If gzip is starting to show its age, zstd and brotli are open-source, standardized and used
daily by millions of people. Zstd is incredibly fast and probably the best choice in many cases.
There are a few scenarios where Kanzi can be a better choice:

- gzip, lzma, brotli, zstd are all LZ based. It means that they can reach certain compression
ratios only. Kanzi also makes use of BWT and CM which can compress beyond what LZ can do.

- These LZ based compressors are well suited for software distribution (one compression / many decompressions)
due to their fast decompression (but low compression speed at high compression ratios). 
There are other scenarios where compression speed is critical: when data is generated before being compressed and consumed
(one compression / one decompression) or during backups (many compressions / one decompression).

- Kanzi has built-in customized data transforms (multimedia, utf, text, dna, ...) that can be chosen and combined 
at compression time to better compress specific kinds of data.

- Kanzi can take advantage of the multiple cores of a modern CPU to improve performance

- Implementing a new transform or entropy codec (to either test an idea or improve compression ratio on specific kinds of data) is simple.
  

## Benchmarks

Test machine:

AWS c5a8xlarge: AMD EPYC 7R32 (32 vCPUs), 64 GB RAM

openjdk 21.0.3 2024-04-16

Ubuntu 24.04 LTS

Kanzi version 2.3.0 Java

On this machine, Kanzi uses up to 16 threads (half of CPUs by default).

bzip3 uses 16 threads. zstd uses 16 threads for compression and 1 for decompression, 
other compressors are single threaded.

The default block size at level 9 is 32MB, severely limiting the number of threads
in use, especially with enwik8, but all tests are performed with default values.


### silesia.tar

Download at http://sun.aei.polsl.pl/~sdeor/corpus/silesia.zip

|        Compressor               | Encoding (sec)  | Decoding (sec)  |    Size          |
|---------------------------------|-----------------|-----------------|------------------|
|Original     	                  |                 |                 |   211,957,760    |
|**Kanzi -l 1**                   |   	**1.137**   |    **1.153**    |  **80,277,212**  |
|Lz4 1.9.5 -4                     |       0.321     |      0.330      |    79,912,419    |
|Zstd 1.5.6 -2 -T16               |	      0.151     |      0.271      |    69,556,157    |
|**Kanzi -l 2**                   |   	**1.082**   |    **1.313**    |  **68,195,845**  |
|Brotli 1.1.0 -2                  |       1.749     |      0.761      |    68,041,629    |
|Gzip 1.12 -9                     |      20.09      |      1.403      |    67,652,449    |
|**Kanzi -l 3**                   |   	**1.884**   |    **1.624**    |  **65,613,695**  |
|Zstd 1.5.6 -5 -T16               |	      0.356     |      0.289      |    63,131,656    |
|**Kanzi -l 4**                   |   	**2.548**   |    **2.420**    |  **61,249,959**  |
|Zstd 1.5.5 -9 -T16               |	      0.690     |      0.278      |    59,429,335    |
|Brotli 1.1.0 -6                  |       8.388     |      0.677      |    58,571,909    |
|Zstd 1.5.6 -13 -T16              |	      3.244     |      0.272      |    58,041,112    |
|Brotli 1.1.0 -9                  |      70.07      |      0.677      |    56,376,419    |
|Bzip2 1.0.8 -9	                  |      16.94      |      6.734      |    54,572,500    |
|**Kanzi -l 5**                   |   	**3.270**   |    **2.143**    |  **54,039,773**  |
|Zstd 1.5.6 -19 -T16              |	     20.87      |      0.303      |    52,889,925    |
|**Kanzi -l 6**                   |   	**4.506**   |    **2.256**    |  **49,567,817**  |
|Lzma 5.4.5 -9                    |      95.97      |      3.172      |    48,745,354    |
|**Kanzi -l 7**                   |   	**4.246**   |    **3.251**    |  **47,520,629**  |
|bzip3 1.3.2.r4-gb2d61e8 -j 16    |       2.682     |      3.221      |    47,237,088    |
|**Kanzi -l 8**                   |   	**9.549**   |    **9.983**    |  **43,167,429**  |
|**Kanzi -l 9**                   |    **26.95**    |   **28.31**     |  **41,497,835**  |
|zpaq 7.15 -m5 -t16               |     213.8       |    213.8        |    40,050,429    |



### enwik8

Download at https://mattmahoney.net/dc/enwik8.zip

|      Compressor        | Encoding (sec)   | Decoding (sec)   |    Size          |
|------------------------|------------------|------------------|------------------|
|Original                |                  |                  |   100,000,000    |
|**Kanzi -l 1**          |     **1.140**    |    **0.596**     |  **43,746,017**  |
|**Kanzi -l 2**          |     **1.040**    |    **0.720**     |  **37,816,913**  |
|**Kanzi -l 3**          |     **1.148**    |    **0.892**     |  **33,865,383**  |
|**Kanzi -l 4**          |	   **1.321**    |    **1.566**     |  **29,597,577**  |
|**Kanzi -l 5**          |	   **1.751**    |    **1.649**     |  **26,528,023**  |
|**Kanzi -l 6**          |	   **2.954**    |    **1.319**     |  **24,076,674**  |
|**Kanzi -l 7**          |     **3.234**    |    **2.322**     |  **22,817,373**  |
|**Kanzi -l 8**          |	   **6.836**    |    **6.741**     |  **21,181,983**  |
|**Kanzi -l 9**          |	  **17.99**     |   **18.41**      |  **20,035,138**  |


## Build 

First option (ant):

```ant```

Second option (maven):

```mvn -Dmaven.test.skip=true```


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

Use at your own risk. Always keep a copy of your original files.
