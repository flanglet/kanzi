# Kanzi


Kanzi is a modern, modular, expandable and efficient lossless data compressor implemented in Java.

* modern: state-of-the-art algorithms are implemented and multi-core CPUs can take advantage of the built-in multi-threading.
* modular: entropy codec and a combination of transforms can be provided at runtime to best match the kind of data to compress.
* expandable: clean design with heavy use of interfaces as contracts makes integrating and expanding the code easy. No dependencies.
* efficient: the code is optimized for efficiency (trade-off between compression ratio and speed).

Unlike the most common lossless data compressors, Kanzi uses a variety of different compression algorithms and supports a wider range of compression ratios as a result. Most usual compressors do not take advantage of the many cores and threads available on modern CPUs (what a waste!). Kanzi is multithreaded by design and uses several threads by default to compress blocks concurrently. It is not compatible with standard compression formats. 
Kanzi is a lossless data compressor, not an archiver. It uses checksums (optional but recommended) to validate data integrity but does not have a mechanism for data recovery. It also lacks data deduplication across files.


For more details, check https://github.com/flanglet/kanzi/wiki.

See how to reuse the code here: https://github.com/flanglet/kanzi/wiki/Using-and-extending-the-code

There is a C++ implementation available here: https://github.com/flanglet/kanzi-cpp

There is Go implementation available here: https://github.com/flanglet/kanzi-go

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

![Build Status](https://github.com/flanglet/kanzi/actions/workflows/ant.yml/badge.svg)



## Why Kanzi

There are many excellent, open-source lossless data compressors available already.

If gzip is starting to show its age, zstd and brotli are open-source, standardized and used
daily by millions of people. Zstd is incredibly fast and probably the best choice in many cases.
There are a few scenarios where Kanzi could be a better choice:

- gzip, lzma, brotli, zstd are all LZ based. It means that they can reach certain compression
ratios only. Kanzi also makes use of BWT and CM which can compress beyond what LZ can do.

- These LZ based compressors are well suited for software distribution (one compression / many decompressions)
due to their fast decompression (but low compression speed at high compression ratios). 
There are other scenarios where compression speed is critical: when data is generated before being compressed and consumed
(one compression / one decompression) or during backups (many compressions / one decompression).

- Kanzi has built-in customized data transforms (multimedia, utf, text, dna, ...) that can be chosen and combined 
at compression time to better compress specific kinds of data.

- Kanzi can take advantage of the multiple cores of a modern CPU to improve performance

- It is easy to implement a new transform or entropy codec to either test an idea or improve
compression ratio on specific kinds of data.



## Benchmarks

Test machine:

AWS c5a8xlarge: AMD EPYC 7R32 (32 vCPUs), 64 GB RAM

openjdk 21.0.1+12-29

Ubuntu 22.04.3 LTS

Kanzi version 2.2 Java implementation.

On this machine kanzi can use up to 16 threads (depending on compression level).
bzip3 uses 16 threads. zstd can use 2 for compression, other compressors
are single threaded.


### silesia.tar

Download at http://sun.aei.polsl.pl/~sdeor/corpus/silesia.zip

|        Compressor               | Encoding (sec)  | Decoding (sec)  |    Size          |
|---------------------------------|-----------------|-----------------|------------------|
|Original     	                  |                 |                 |   211,957,760    |
|**Kanzi -l 1**                   |   	**1.337**   |    **1.186**    |  **80,284,705**  |
|lz4 1.9.5 -4                     |       3.397     |      0.987      |    79,914,864    |
|Zstd 1.5.5 -2                    |	      0.761     |      0.286      |    69,590,245    |
|**Kanzi -l 2**                   |   	**1.343**   |    **1.343**    |  **68,231,498**  |
|Brotli 1.1.0 -2                  |       1.749     |      2.459      |    68,044,145    |
|Gzip 1.10 -9                     |      20.15      |      1.316      |    67,652,229    |
|**Kanzi -l 3**                   |   	**1.906**   |    **1.692**    |  **64,916,444**  |
|Zstd 1.5.5 -5                    |	      2.003     |      0.324      |    63,103,408    |
|**Kanzi -l 4**                   |   	**2.458**   |    **2.521**    |  **60,770,201**  |
|Zstd 1.5.5 -9                    |	      4.166     |      0.282      |    59,444,065    |
|Brotli 1.1.0 -6                  |      14.53      |      4.263      |    58,552,177    |
|Zstd 1.5.5 -15                   |	     19.15      |      0.276      |    58,061,115    |
|Brotli 1.1.0 -9                  |      70.07      |      7.149      |    56,408,353    |
|Bzip2 1.0.8 -9	                  |      16.94      |      6.734      |    54,572,500    |
|**Kanzi -l 5**                   |   	**3.228**   |    **2.268**    |  **54,051,139**  |
|Zstd 1.5.5 -19                   |	     92.82      |      0.302      |    52,989,654    |
|**Kanzi -l 6**                   |   	**4.950**   |    **2.522**    |  **49,517,823**  |
|Lzma 5.2.5 -9                    |      92.6       |      3.075      |    48,744,632    |
|**Kanzi -l 7**                   |   	**4.478**   |    **3.181**    |  **47,308,484**  |
|bzip3 1.3.2.r4-gb2d61e8 -j 16    |       2.682     |      3.221      |    47,237,088    |
|**Kanzi -l 8**                   |    **10.67**    |   **11.13**     |  **43,247,248**  |
|**Kanzi -l 9**                   |    **24.78**    |   **26.73**     |  **41,807,179**  |
|zpaq 7.15 -m5 -t16               |     213.8       |    213.8        |    40,050,429    |



### enwik8

Download at https://mattmahoney.net/dc/enwik8.zip

|      Compressor        | Encoding (sec)   | Decoding (sec)   |    Size          |
|------------------------|------------------|------------------|------------------|
|Original                |                  |                  |   100,000,000    |
|**Kanzi -l 1**          |     **1.221**    |    **0.684**     |  **43,747,730**  |
|**Kanzi -l 2**          |     **1.254**    |    **0.907**     |  **37,745,093**  |
|**Kanzi -l 3**          |     **1.093**    |    **0.989**     |  **33,839,184**  |
|**Kanzi -l 4**          |	   **1.800**    |    **1.648**     |  **29,598,635**  |
|**Kanzi -l 5**          |	   **2.066**    |    **1.740**     |  **26,527,955**  |
|**Kanzi -l 6**          |	   **2.648**    |    **1.743**     |  **24,076,669**  |
|**Kanzi -l 7**          |     **3.742**    |    **1.741**     |  **22,817,376**  |
|**Kanzi -l 8**          |	   **6.619**    |    **6.633**     |  **21,181,978**  |
|**Kanzi -l 9**          |	  **17.81**     |   **18.23**      |  **20,035,133**  |
