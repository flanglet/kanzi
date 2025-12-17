/*
 * Kanzi is a modern, modular, portable, and efficient lossless data compressor.
 *
 * Copyright (C) 2011-2025 Frederic Langlet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.flanglet.kanzi.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.*;

/**
 * Kanzi Compression Benchmark
 *
 * A comprehensive benchmarking tool for the Kanzi Java compression library that
 * tests various transform and entropy coding combinations with timing and
 * compression ratio analysis.
 *
 * Requirements: - Java 8+ - Kanzi Java library (kanzi.jar) in classpath
 *
 * Usage: java -cp kanzi.jar:. io.github.flanglet.kanzi.app.Benchmark input-file
 */
public class Benchmark {

    private static final DecimalFormat SIZE_FORMAT = new DecimalFormat("#,##0");
    private static final DecimalFormat RATIO_FORMAT = new DecimalFormat("0.00");
    private static final DecimalFormat SPEED_FORMAT = new DecimalFormat("0.00");
    private static final DecimalFormat TIME_FORMAT = new DecimalFormat("0.000");

    // Kanzi configuration lists
    private static final String[] TRANSFORMS = {"NONE", "PACK", "BWT", "BWTS", "LZ", "LZX", "LZP", "ROLZ", "ROLZX",
            "RLT", "ZRLT", "MTFT", "RANK", "SRT", "TEXT", "EXE", "MM", "UTF", "DNA"};

    private static final String[] OPTIMIZED_TRANSFORMS = {"TEXT", "RLT", "PACK", "ZRLT", "BWTS", "BWT", "LZP", "MTFT",
            "SRT", "LZ", "LZX", "ROLZ", "ROLZX", "RANK", "EXE", "MM"};

    private static final String[] ENTROPY_CODERS = {"NONE", "HUFFMAN", "ANS0", "ANS1", "RANGE", "CM", "FPAQ", "TPAQ",
            "TPAQX"};

    private static final String[] SPECIALIZED_TRANSFORMS = {"RLT", "PACK", "PACK+ZRLT+PACK", "PACK+RLT", "RLT+PACK",
            "RLT+TEXT+PACK", "RLT+PACK+LZP", "RLT+PACK+LZP+RLT", "TEXT+ZRLT+PACK", "RLT+LZP+PACK+RLT",
            "TEXT+ZRLT+PACK+LZP", "TEXT+RLT+PACK", "TEXT+RLT+LZP", "TEXT+RLT+PACK+LZP", "TEXT+RLT+LZP+RLT",
            "TEXT+RLT+PACK+LZP+RLT", "TEXT+RLT+LZP+PACK", "TEXT+RLT+PACK+RLT+LZP", "TEXT+RLT+LZP+PACK+RLT",
            "TEXT+PACK+RLT", "EXE+TEXT+RLT+UTF+PACK", "EXE+TEXT+RLT+UTF+DNA", "EXE+TEXT+RLT", "EXE+TEXT",
            "TEXT+BWTS+SRT+ZRLT", "BWTS+SRT+ZRLT", "TEXT+BWTS+MTFT+RLT", "BWTS+MTFT+RLT", "TEXT+BWT+MTFT+RLT",
            "BWT+MTFT+RLT"};

    private final File inputFile;
    private final long originalSize;
    private final int parallelJobs;
    private final List<CompressionResult> results = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java -cp kanzi.jar:. io.github.flanglet.kanzi.app.Benchmark <input-file>");
            System.exit(1);
        }

        File inputFile = new File(args[0]);
        if (!inputFile.exists() || !inputFile.canRead()) {
            System.err.println("Error: Cannot read input file: " + inputFile);
            System.exit(1);
        }

        try {
            new Benchmark(inputFile).run();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    public Benchmark(File inputFile) {
        this.inputFile = inputFile;
        this.originalSize = inputFile.length();
        this.parallelJobs = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }

    public void run() throws Exception {
        printHeader();

        // Sequential tests
        runLevelPresets();
        runLevelPresetsWithLargeBlocks();
        runLargeBlockSizes();
        runSpecializedTransforms();

        // Parallel tests
        runFourTransformCombinations();
        runSingleTransformCombinations();
        runTwoTransformCombinations();
        runThreeTransformCombinations();

        analyzeResults();
    }

    private void printHeader() {
        System.out.println("Kanzi Compression Benchmark");
        System.out.println("Input file: " + inputFile + " (" + formatSize(originalSize) + ")");
        System.out.println("Parallel jobs: " + parallelJobs);
        System.out.println();
        System.out.printf("%12s %10s %9s %10s %s%n", "COMPRESSED", "TIME", "RATIO", "SPEED", "ALGORITHM");
        System.out.printf("%12s %10s %9s %10s %s%n", "------------", "----------", "---------", "----------",
                "----------");
    }

    private void runLevelPresets() throws Exception {
        System.out.println("\n# KANZI Level Presets (Default Block Size)");

        for (int level = 1; level <= 9; level++) {
            benchmarkKanzi("kanzi -l" + level, new String[]{"--level=" + level});
        }
    }

    private void runLevelPresetsWithLargeBlocks() throws Exception {
        System.out.println("\n# KANZI Level Presets (64MB Block Size)");

        for (int level = 1; level <= 9; level++) {
            benchmarkKanzi("kanzi -b64m -l" + level, new String[]{"--block=64m", "--level=" + level});
        }
    }

    private void runLargeBlockSizes() throws Exception {
        System.out.println("\n# KANZI Various Block Sizes (Level 9)");

        String[] blockSizes = {"1m", "4m", "8m", "16m", "32m", "64m", "96m", "128m", "256m"};
        for (String blockSize : blockSizes) {
            benchmarkKanzi("kanzi -b" + blockSize + " -l9", new String[]{"--block=" + blockSize, "--level=9"});
        }
    }

    private void runSpecializedTransforms() throws Exception {
        System.out.println("\n# KANZI Specialized Transform Chains (64M blocks)");

        for (String transform : SPECIALIZED_TRANSFORMS) {
            benchmarkKanzi("kanzi -t" + transform + " -eTPAQX",
                    new String[]{"--block=64m", "--transform=" + transform, "--entropy=TPAQX"});
        }
    }

    private void runFourTransformCombinations() throws Exception {
        System.out.println("\n# KANZI Parallel Tests - 4-Transform BWT/BWTS Combinations");

        List<TestConfig> testCases = new ArrayList<>();
        String[] bwtTypes = {"BWT", "BWTS"};
        String[] sortTypes = {"MTFT", "SRT"};
        String[] rltTypes = {"RLT", "ZRLT"};
        String[] entropyTypes = {"CM", "TPAQ", "TPAQX"};

        for (String t2 : bwtTypes) {
            for (String t3 : sortTypes) {
                for (String t4 : rltTypes) {
                    for (String e : entropyTypes) {
                        String transform = "TEXT+" + t2 + "+" + t3 + "+" + t4;
                        String name = "kanzi -t" + transform + " -e" + e;
                        String[] args = {"--block=64m", "--transform=" + transform, "--entropy=" + e};
                        testCases.add(new TestConfig(name, args));
                    }
                }
            }
        }

        runParallelTests(testCases);
    }

    private void runSingleTransformCombinations() throws Exception {
        System.out.println("\n# KANZI Parallel Tests - Single Transform + Entropy");

        List<TestConfig> testCases = new ArrayList<>();
        for (String t1 : TRANSFORMS) {
            for (String e : ENTROPY_CODERS) {
                String name = "kanzi -t" + t1 + " -e" + e;
                String[] args = {"--block=64m", "--transform=" + t1, "--entropy=" + e};
                testCases.add(new TestConfig(name, args));
            }
        }

        runParallelTests(testCases);
    }

    private void runTwoTransformCombinations() throws Exception {
        System.out.println("\n# KANZI Parallel Tests - Two Transform Combinations");

        List<TestConfig> testCases = new ArrayList<>();
        for (String t1 : OPTIMIZED_TRANSFORMS) {
            for (String t2 : OPTIMIZED_TRANSFORMS) {
                if (!t1.equals(t2)) {
                    for (String e : ENTROPY_CODERS) {
                        String transform = t1 + "+" + t2;
                        String name = "kanzi -t" + transform + " -e" + e;
                        String[] args = {"--block=64m", "--transform=" + transform, "--entropy=" + e};
                        testCases.add(new TestConfig(name, args));
                    }
                }
            }
        }

        runParallelTests(testCases);
    }

    private void runThreeTransformCombinations() throws Exception {
        System.out.println("\n# KANZI Parallel Tests - Three Transform Combinations");

        List<TestConfig> testCases = new ArrayList<>();
        for (String t1 : OPTIMIZED_TRANSFORMS) {
            for (String t2 : OPTIMIZED_TRANSFORMS) {
                if (!t1.equals(t2)) {
                    for (String t3 : OPTIMIZED_TRANSFORMS) {
                        if (!t2.equals(t3)) {
                            for (String e : ENTROPY_CODERS) {
                                String transform = t1 + "+" + t2 + "+" + t3;
                                String name = "kanzi -t" + transform + " -e" + e;
                                String[] args = {"--block=64m", "--transform=" + transform, "--entropy=" + e};
                                testCases.add(new TestConfig(name, args));
                            }
                        }
                    }
                }
            }
        }

        runParallelTests(testCases);
    }

    private void runParallelTests(List<TestConfig> testCases) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(parallelJobs);
        List<Future<CompressionResult>> futures = new ArrayList<>();

        for (TestConfig config : testCases) {
            futures.add(executor.submit(() -> benchmarkKanziInternal(config.name, config.args)));
        }

        // Collect results and sort by compression ratio
        List<CompressionResult> parallelResults = new ArrayList<>();
        for (Future<CompressionResult> future : futures) {
            try {
                parallelResults.add(future.get());
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                System.err.println("Error in parallel test: " + e.getMessage());
            }
        }

        executor.shutdown();

        // Sort by compression ratio and print
        parallelResults.sort(Comparator.comparing(r -> r.ratio));
        for (CompressionResult result : parallelResults) {
            printResult(result);
            results.add(result);
        }
    }

    private CompressionResult benchmarkKanzi(String name, String[] args) throws Exception {
        CompressionResult result = benchmarkKanziInternal(name, args);
        printResult(result);
        results.add(result);
        return result;
    }

    private CompressionResult benchmarkKanziInternal(String name, String[] args) {
        File tempOutput = null;
        try {
            // Create temporary output file
            tempOutput = File.createTempFile("kanzi_benchmark_", ".knz");
            tempOutput.deleteOnExit();

            // Build complete argument list for Kanzi
            List<String> fullArgs = new ArrayList<>();
            fullArgs.add("-c");
            fullArgs.add("--force");
            fullArgs.add("-i");
            fullArgs.add(inputFile.getAbsolutePath());
            fullArgs.add("-o");
            fullArgs.add(tempOutput.getAbsolutePath());
            fullArgs.addAll(Arrays.asList(args));

            // Measure compression time
            long startTime = System.nanoTime();

            // Capture and suppress Kanzi output during benchmarking
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;

            ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
            PrintStream suppressedOut = new PrintStream(outputBuffer, true, StandardCharsets.UTF_8);

            try {
                // Redirect output to suppress Kanzi's console output
                System.setOut(suppressedOut);
                System.setErr(suppressedOut);

                // Call Kanzi.execute() directly - no ProcessBuilder!
                int exitCode = Kanzi.execute(fullArgs.toArray(new String[0]));

                long endTime = System.nanoTime();
                double timeSeconds = (endTime - startTime) / 1_000_000_000.0;

                if (exitCode != 0) {
                    // Restore streams to show error
                    System.setOut(originalOut);
                    System.setErr(originalErr);

                    String output = outputBuffer.toString(StandardCharsets.UTF_8);
                    System.err.println("Kanzi failed with exit code " + exitCode + " for: " + name);
                    if (!output.trim().isEmpty()) {
                        System.err.println("Output: " + output);
                    }
                    throw new RuntimeException("Kanzi compression failed with exit code: " + exitCode);
                }

                if (!tempOutput.exists() || tempOutput.length() == 0) {
                    System.err.println("Output file not created or empty for: " + name);
                    throw new RuntimeException("Kanzi compression produced no output");
                }

                long compressedSize = tempOutput.length();
                return new CompressionResult(name, originalSize, compressedSize, timeSeconds);

            } finally {
                // Always restore original streams
                System.setOut(originalOut);
                System.setErr(originalErr);
            }

        } catch (Exception e) {
            System.err.println("Error in compression test '" + name + "': " + e.getMessage());
            return new CompressionResult(name + " (FAILED)", originalSize, originalSize, 0.0);
        } finally {
            // Clean up
            if (tempOutput != null && tempOutput.exists()) {
                if (tempOutput.delete() == false) {
                    System.err.println("Could not delete file '" + tempOutput.getAbsolutePath() + "'");
                }
            }
        }
    }

    private void printResult(CompressionResult result) {
        System.out.printf("%12s %10s %8.2f%% %10.2f %s%n", formatSize(result.compressedSize),
                formatTime(result.timeSeconds), result.ratio, result.speedMBps, result.algorithm);
    }

    private void analyzeResults() {
        System.out.println("\n" + "=".repeat(50));
        System.out.println("FINAL ANALYSIS & RECOMMENDATIONS");
        System.out.println("=".repeat(50));

        if (results.isEmpty()) {
            System.out.println("No results found for analysis");
            return;
        }

        // Find best compression
        CompressionResult bestCompression = results.stream().min(Comparator.comparing(r -> r.ratio)).orElse(null);

        // Find most reasonable compression (balance of ratio and speed)
        CompressionResult bestBalanced = results.stream().min(Comparator.comparing(result -> {
            // Balance score: heavily weight compression ratio, but penalize very slow
            // speeds
            double balanceScore = result.ratio * 2.0;
            if (result.speedMBps > 0 && !Double.isInfinite(result.speedMBps)) {
                balanceScore += 100.0 / result.speedMBps;
            } else {
                balanceScore += 1000.0; // Penalty for very slow
            }
            return balanceScore;
        })).orElse(null);

        System.out.println("\n📊 **BEST COMPRESSION RATIO:**");
        printAnalysisResult(bestCompression);

        System.out.println("\n⚖️  **MOST REASONABLE TRADE-OFF:**");
        printAnalysisResult(bestBalanced);

        // Additional insights
        System.out.println("\n💡 **INSIGHTS:**");
        int totalTests = results.size();
        long fastTests = results.stream().mapToLong(r -> r.speedMBps > 100.0 ? 1 : 0).sum();
        long goodCompression = results.stream().mapToLong(r -> r.ratio < 5.0 ? 1 : 0).sum();

        System.out.println("   • Tested " + totalTests + " compression configurations");
        System.out.println("   • " + fastTests + " algorithms achieved >100 MB/s speed");
        System.out.println("   • " + goodCompression + " algorithms achieved <5% compression ratio");

        if (bestCompression.ratio < 3.0) {
            System.out.println("   • Excellent compression achieved (<3%)");
        } else if (bestCompression.ratio < 5.0) {
            System.out.println("   • Very good compression achieved (<5%)");
        }

        if (bestBalanced.speedMBps > 50.0) {
            System.out.println("   • Balanced option provides good speed (>50 MB/s)");
        }
    }

    private void printAnalysisResult(CompressionResult result) {
        if (result == null) {
            System.out.println("   No valid results found");
            return;
        }

        long savings = originalSize - result.compressedSize;
        double reductionPercent = 100.0 - result.ratio;

        System.out.println("   Algorithm: " + result.algorithm);
        System.out.println("   Size:      " + formatSize(originalSize) + " → " + formatSize(result.compressedSize)
                + " (" + RATIO_FORMAT.format(result.ratio) + "%)");
        System.out.println("   Time:      " + formatTime(result.timeSeconds));
        System.out.println("   Speed:     " + SPEED_FORMAT.format(result.speedMBps) + " MB/s");
        System.out.println(
                "   Savings:   " + formatSize(savings) + " (" + RATIO_FORMAT.format(reductionPercent) + "% reduction)");
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024)
            return bytes + "B";
        if (bytes < 1024 * 1024)
            return SIZE_FORMAT.format(bytes / 1024.0) + "KB";
        if (bytes < 1024L * 1024 * 1024)
            return SIZE_FORMAT.format(bytes / (1024.0 * 1024.0)) + "MB";
        return SIZE_FORMAT.format(bytes / (1024.0 * 1024.0 * 1024.0)) + "GB";
    }

    private static String formatTime(double seconds) {
        if (seconds < 1.0)
            return TIME_FORMAT.format(seconds) + "s";
        if (seconds < 60.0)
            return TIME_FORMAT.format(seconds) + "s";
        int minutes = (int) (seconds / 60);
        int remainingSeconds = (int) (seconds % 60);
        return minutes + "m" + remainingSeconds + "s";
    }

    // Inner classes
    static class CompressionResult {
        final String algorithm;
        final long originalSize;
        final long compressedSize;
        final double timeSeconds;
        final double ratio;
        final double speedMBps;

        CompressionResult(String algorithm, long originalSize, long compressedSize, double timeSeconds) {
            this.algorithm = algorithm;
            this.originalSize = originalSize;
            this.compressedSize = compressedSize;
            this.timeSeconds = timeSeconds;
            this.ratio = (compressedSize * 100.0) / originalSize;
            this.speedMBps = timeSeconds > 0
                    ? (originalSize / (1024.0 * 1024.0)) / timeSeconds
                    : Double.POSITIVE_INFINITY;
        }
    }

    static class TestConfig {
        final String name;
        final String[] args;

        TestConfig(String name, String[] args) {
            this.name = name;
            this.args = args;
        }
    }
}
