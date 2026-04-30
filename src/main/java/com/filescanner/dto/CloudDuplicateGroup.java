package com.filescanner.dto;

import com.filescanner.entity.CloudFileEntry;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class CloudDuplicateGroup {
    private String name;
    private long count;
    private long totalSizeBytes;
    private List<CloudFileEntry> files;
}
