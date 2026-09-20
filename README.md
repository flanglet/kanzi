
# Kanzi

Kanzi is a modern, modular, portable, and efficient lossless data compressor written in Java.

* Modern: Kanzi implements state-of-the-art compression algorithms and is built to fully utilize multi-core CPUs via built-in multi-threading.
* Modular: Entropy codecs and data transforms can be selected and combined at runtime to best suit the specific data being compressed.
* Expandable: A clean, interface-driven design—with no external dependencies—makes Kanzi easy to integrate, extend, and customize.
* Efficient: Carefully optimized to balance compression ratio and speed for practical, high-performance usage.

Unlike most mainstream lossless compressors, Kanzi is not limited to a single compression paradigm. By combining multiple algorithms and techniques, it supports a broader range of compression ratios and adapts better to diverse data types.

Most traditional compressors underutilize modern hardware by running single-threaded—even on machines with many cores. Kanzi, in contrast, is concurrent by design, compressing multiple blocks in parallel across threads for significant performance gains. However, it is not compatible with standard compression formats.

It’s important to note that Kanzi is a data compressor, not an archiver. It includes optional checksums for verifying data integrity, but does not provide features like cross-file deduplication or data recovery mechanisms. That said, it produces a seekable bitstream—meaning one or more consecutive blocks can be decompressed independently, without needing to process the entire stream.


For more details, check [Wiki](https://github.com/flanglet/kanzi/wiki), [QA](https://github.com/flanglet/kanzi/wiki/Q&A) and [DeepWiki](https://deepwiki.com/flanglet/kanzi)

See how to reuse the code here: https://github.com/flanglet/kanzi/wiki/Using-and-extending-the-code

There is a C++ implementation available here: https://github.com/flanglet/kanzi-cpp

There is Go implementation available here: https://github.com/flanglet/kanzi-go


![Build Status](https://github.com/flanglet/kanzi/actions/workflows/ant.yml/badge.svg)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=flanglet_kanzi&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=flanglet_kanzi)
<a href="https://scan.coverity.com/projects/flanglet-kanzi">
  <img alt="Coverity Scan Build Status"
       src="https://img.shields.io/coverity/scan/16859.svg"/>
</a>
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/flanglet/kanzi)


## Why Kanzi


While excellent open-source compressors like zstd and brotli exist, they are primarily based on Lempel-Ziv (LZ) algorithms. Zstd, in particular, is a fantastic general-purpose choice known for its speed. However, LZ-based tools have inherent limits regarding compression ratios.

Kanzi offers a compelling alternative for specific high-performance scenarios:

* Beyond LZ: By incorporating Burrows-Wheeler Transform (BWT) and Context Modeling (CM), Kanzi can achieve compression ratios that traditional LZ methods cannot.

* Speed where it counts: While LZ is ideal for "compress once, decompress often" (like software distribution), it often slows down significantly at high compression settings. Kanzi leverages multi-core CPUs to maintain performance, making it highly effective for backups, real-time data generation, and one-off transfers.

* Content-Aware: Kanzi features built-in, customizable transforms for specific data types (e.g., multimedia, DNA, UTF text), improving efficiency where generic compressors fail.

* Extensible: The architecture is developer-friendly, making it straightforward to implement new transforms or entropy codecs for experimentation or niche data types.


## Benchmarks

Kanzi version 2.6.0 

*Note: The default block size at level 9 is 32MB, severely limiting the number of threads
in use, especially with enwik8, but all tests are performed with default values.*


### silesia.tar

Test machine: AMD Ryzen 9950X on Ubuntu 26.04

openjdk version "27" 2026-09-15

Median of 5 runs

Download at http://sun.aei.polsl.pl/~sdeor/corpus/silesia.zip

|        Compressor               |  Encoding (ms)  |  Decoding (ms)  |    Size          |
|---------------------------------|-----------------|-----------------|------------------|
|Original                         |                 |                 |   211,957,760    |
|lz4 1.1.10 -T16 -4               |        18       |         13      |    79,910,851    |
|**kanzi -l 1**                   |     **497**     |      **246**    |    79,184,957    |
|zstd 1.5.8 -T16 -2               |         6       |         11      |    69,443,247    |
|**kanzi -l 2**                   |     **693**     |      **350**    |    68,627,321    |
|brotli 1.1.0 -2                  |       880       |        333      |    68,040,160    |
|gzip 1.13 -9                     |     10328       |        704      |    67,651,076    |
|**kanzi -l 3**                   |     **802**     |      **479**    |    63,093,409    |
|zstd 1.5.8 -T16 -5               |       138       |        123      |    62,867,556    |
|**kanzi -l 4**                   |     **936**     |      **754**    |    60,789,646    |
|zstd 1.5.8 -T16 -9               |       320       |        114      |    59,233,481    |
|brotli 1.1.0 -6                  |      4039       |        299      |    58,511,709    |
|zstd 1.5.8 -T16 -13              |      1820       |        112      |    57,843,283    |
|brotli 1.1.0 -9                  |     23030       |        293      |    56,407,229    |
|bzip2 1.0.8 -9                   |      8223       |       3453      |    54,588,597    |
|**kanzi -l 5**                   |    **1814**     |      **685**    |    53,863,205    |
|zstd 1.5.8 -T16 -19              |     11290       |        130      |    52,830,213    |
|**kanzi -l 6**                   |    **2300**     |      **840**    |    49,472,110    |
|xz 5.8.1 -9                      |     43611       |        931      |    48,802,580    |
|bsc 3.3.11 -T16                  |      1201       |        698      |    47,900,848    |
|**kanzi -l 7**                   |    **1840**     |     **1558**    |    47,330,431    |
|bzip3 1.5.1.r3-g428f422 -j 16    |      2348       |       2218      |    47,260,281    |
|**kanzi -l 8**                   |    **5928**     |     **6340**    |    42,962,926    |
|**kanzi -l 9**                   |   **14693**     |    **15192**    |    41,520,677    |



### enwik8

Test machine: Apple M3 24 GB Sonoma 15.7.3

java version "27" 2026-09-15

Median of 5 runs

Download at https://mattmahoney.net/dc/enwik8.zip

|   Compressor    | Encoding (ms)  | Decoding (ms)  |  Size        |
|-----------------|----------------|----------------|--------------|
|Original         |                |                |  100,000,000 |
|Kanzi -l 1       |       580      |        149     |   42,941,668 |
|Kanzi -l 2       |       660      |        271     |   37,688,371 |
|Kanzi -l 3       |       753      |        470     |   32,562,496 |
|Kanzi -l 4       |      1052      |        679     |   29,466,291 |
|Kanzi -l 5       |      1546      |        812     |   26,521,279 |
|Kanzi -l 6       |      2165      |       1171     |   24,076,777 |
|Kanzi -l 7       |      2831      |       1850     |   22,817,366 |
|Kanzi -l 8       |      7397      |       7508     |   21,181,998 |
|Kanzi -l 9       |     10414      |      10675     |   20,035,687 |


## Build 

First option (ant):

```ant```

Second option (maven):

```mvn -Dmaven.test.skip=true```

Third option (gradle):

```./gradlew clean build```

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
