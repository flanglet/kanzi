/*
 * Kanzi is a modern, modular, portable, and efficient lossless data compressor.
 *
 * Copyright (C) 2025 Frederic Langlet
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

import io.github.flanglet.kanzi.Event;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import io.github.flanglet.kanzi.Listener;
import io.github.flanglet.kanzi.Event.HeaderInfo;


/**
 * The {@code InfoPrinter} class implements the {@code Listener} interface and provides
 * functionality to process events and print information about encoding or decoding processes.
 */
public class InfoPrinter implements Listener {
  /**
   * Enum representing the type of information to be printed.
   */
  public enum Type {
    /** Represents compression information. */
    COMPRESSION,
    /** Represents decompression information. */
    DECOMPRESSION,
    /** Represents header information. */
    INFO
  }

  private final PrintStream ps;
  private final Map<Integer, BlockInfo> map;
  private final Event.Type[] thresholds;
  private final Type type;
  private final int level;
  private int headerInfo;


  /**
   * Constructs an {@code InfoPrinter} with the specified information level, type, and output
   * stream.
   *
   * @param infoLevel the level of information to be printed
   * @param type the type of information (encoding or decoding)
   * @param ps the {@code PrintStream} to which information will be printed
   */
  public InfoPrinter(int infoLevel, Type type, PrintStream ps) {
    if (ps == null)
      throw new NullPointerException("Invalid null print stream parameter");

    this.ps = ps;
    this.level = infoLevel;
    this.type = type;
    this.headerInfo = 0;
    this.map = new ConcurrentHashMap<>();
    this.thresholds = (type == Type.COMPRESSION)
        ? new Event.Type[] {Event.Type.COMPRESSION_START, Event.Type.BEFORE_TRANSFORM,
            Event.Type.AFTER_TRANSFORM, Event.Type.BEFORE_ENTROPY, Event.Type.AFTER_ENTROPY,
            Event.Type.COMPRESSION_END}
        : new Event.Type[] {Event.Type.DECOMPRESSION_START, Event.Type.BEFORE_ENTROPY,
            Event.Type.AFTER_ENTROPY, Event.Type.BEFORE_TRANSFORM, Event.Type.AFTER_TRANSFORM,
            Event.Type.DECOMPRESSION_END};
  }


  /**
   * Processes an event and takes action based on the event type and the thresholds defined for the
   * {@code InfoPrinter}.
   *
   * @param evt the {@code Event} to be processed
   */
  @Override
  public void processEvent(Event evt) {
    if (this.type == Type.INFO) {
      processHeaderInfo(evt);
      return;
    }

    int currentBlockId = evt.getId();

    if (evt.getType() == this.thresholds[1]) {
      // Register initial block size
      BlockInfo bi = new BlockInfo();
      bi.time0 = evt.getTime();
      bi.stage0Size = evt.getSize();

      this.map.put(currentBlockId, bi);

      if (this.level >= 5) {
        this.ps.println(evt);
      }
    } else if (evt.getType() == this.thresholds[2]) {
      BlockInfo bi = this.map.get(currentBlockId);

      if (bi == null)
        return;

      bi.time1 = evt.getTime();

      if (this.level >= 5) {
        long duration_ms = (bi.time1 - bi.time0) / 1000000L;
        this.ps.println(String.format("%s [%d ms]", evt, duration_ms));
      }
    } else if (evt.getType() == this.thresholds[3]) {
      BlockInfo bi = this.map.get(currentBlockId);

      if (bi == null)
        return;

      bi.time2 = evt.getTime();
      bi.stage1Size = evt.getSize();

      if (this.level >= 5) {
        long duration_ms = (bi.time2 - bi.time1) / 1000000L;
        this.ps.println(String.format("%s [%d ms]", evt, duration_ms));
      }
    } else if (evt.getType() == this.thresholds[4]) {
      long stage2Size = evt.getSize();
      BlockInfo bi = this.map.remove(currentBlockId);

      if ((bi == null) || (this.level < 3))
        return;

      bi.time3 = evt.getTime();
      long duration1_ms = (bi.time1 - bi.time0) / 1000000L;
      long duration2_ms = (bi.time3 - bi.time2) / 1000000L;
      StringBuilder msg = new StringBuilder();

      if (this.level >= 5) {
        this.ps.println(String.format("%s [%d ms]", evt, duration2_ms));
      }

      // Display block info
      if (this.level >= 4) {
        msg.append(String.format("Block %d: %d => %d [%d ms] => %d [%d ms]", currentBlockId,
            bi.stage0Size, bi.stage1Size, duration1_ms, stage2Size, duration2_ms));

        // Add compression ratio for encoding
        if ((this.type == Type.COMPRESSION) && (bi.stage0Size != 0))
          msg.append(String.format(" (%d%%)", (stage2Size * 100L / bi.stage0Size)));

        // Optionally add hash
        if (evt.getHashType() == Event.HashType.SIZE_32)
          msg.append(String.format("  [%s]", Integer.toHexString((int) evt.getHash())));
        else if (evt.getHashType() == Event.HashType.SIZE_64)
          msg.append(String.format("  [%s]", Long.toHexString(evt.getHash())));

        this.ps.println(msg.toString());
      }
    } else if ((evt.getType() == Event.Type.AFTER_HEADER_DECODING) && (this.level >= 3)) {
      StringBuilder msg = new StringBuilder();
      this.ps.println(msg.toString());
      HeaderInfo info = evt.getHeaderInfo();

      if (info == null)
        return;

      if (this.level >= 5) {
        // JSON output
        this.ps.println(evt.toString());
      } else {
        // Plain text output
        msg.append("Bitstream version: ").append(info.bsVersion).append('\n');
        String str =
            (info.checksumSize == 0) ? "NONE" : String.valueOf(info.checksumSize) + " bits";
        msg.append("Block checksum: ").append(info.checksumSize).append('\n');
        msg.append("Block size: ").append(info.blockSize).append(" bytes").append('\n');
        str = ((info.entropyType == null) || (info.entropyType.isEmpty())) ? "no" : info.entropyType;
        msg.append("Using ").append(str).append(" entropy codec (stage 1)").append('\n');
        str = ((info.transformType == null) || (info.transformType.isEmpty())) ? "no"
            : info.transformType;
        msg.append("Using ").append(str).append(" transform (stage 2)").append('\n');

        if (info.originalSize >= 0)
          msg.append("Original size: ").append(String.valueOf(info.originalSize) + " byte(s)")
              .append('\n');

        this.ps.println(msg.toString());
      }
    } else if (this.level >= 5) {
      this.ps.println(evt);
    }
  }


  private void processHeaderInfo(Event evt) {
    if ((this.level == 0) || (evt.getType() != Event.Type.AFTER_HEADER_DECODING))
      return;

    HeaderInfo info = evt.getHeaderInfo();

    if (info == null)
      return;

    String spaces = "                              ";
    StringBuilder sb = new StringBuilder(200);

    if (this.headerInfo++ == 0) {
      sb.append('\n');
      sb.append("|     File Name      |Ver|Check|Block Size|  File Size | Orig. Size | Ratio |");

      if (this.level >= 4) {
        sb.append(" Entropy|        Transforms        |");
      }

      sb.append('\n');
    }

    Path fullPath = Paths.get(info.inputName);
    String fileName = fullPath.getFileName().toString();

    if (fileName.length() > 20)
      fileName = fileName.substring(0, 18) + "..";

    sb.append('|').append(spaces.substring(0, 20 - fileName.length())).append(fileName);
    String str = String.valueOf(info.bsVersion);
    sb.append('|').append(spaces.substring(0, 3 - str.length())).append(str);
    str = String.valueOf(info.checksumSize);
    sb.append('|').append(spaces.substring(0, 5 - str.length())).append(str);
    str = String.valueOf(info.blockSize);
    sb.append('|').append(spaces.substring(0, 10 - str.length())).append(str);
    str = formatSize(info.originalSize);
    sb.append('|').append(spaces.substring(0, 12 - str.length())).append(str);

    if (info.fileSize > 0) {
      str = formatSize(info.fileSize);
      sb.append('|').append(spaces.substring(0, 12 - str.length())).append(str);
      float ratio = (info.originalSize == 0) ? 0 : ((float) (info.fileSize) / info.originalSize);
      str = String.format("%.3f", ratio);
      sb.append('|').append(spaces.substring(0, 7 - str.length())).append(str);
    } else {
      sb.append("|     N/A    ");
    }

    if (this.level >= 4) {
      str = info.entropyType;
      sb.append('|').append(spaces.substring(0, 8 - str.length())).append(str);
      str = info.transformType;
      sb.append('|').append(spaces.substring(0, 26 - str.length())).append(str);
    }

    sb.append('|');
    this.ps.println(sb.toString());
  }


  private static String formatSize(long size) {
    if (size < 1024)
      return String.valueOf(size);

    if (size < 1024 * 1024)
      return String.format("%.1f KiB", size / 1024.0);

    if (size < 1024L * 1024L * 1024L)
      return String.format("%.1f MiB", size / (1024.0 * 1024.0));

    return String.format("%.1f GiB", size / (1024.0 * 1024.0 * 1024.0));
  }


  /**
   * Inner class representing information about a specific block.
   */
  static class BlockInfo {
    long time0;
    long time1;
    long time2;
    long time3;
    long stage0Size;
    long stage1Size;
  }
}
