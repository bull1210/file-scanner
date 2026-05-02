package com.filescanner.dto;

import com.filescanner.entity.FileEntry;
import lombok.Data;

import java.util.List;

@Data
public class StatsDto {

    private Long scanId;

    private long totalFileCount;
    private long totalSizeBytes;

    private List<FileEntry> largestFiles;

    private List<String> extLabels;
    private List<Long>   extCounts;
    private List<Long>   extSizes;

    private List<Integer> depthLevels;
    private List<Long>    depthCounts;

    private long[] ageBuckets;

    private Long duplicateGroupCount;
    private Long duplicateWastedBytes;
    private Long directoryCount;

    public String getFormattedTotalSize() {
        return formatBytes(totalSizeBytes);
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
