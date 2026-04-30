package com.filescanner.dto;

import com.filescanner.entity.FileEntry;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class DuplicateGroup {
    private String name;
    private long count;
    private long totalSizeBytes;
    private List<FileEntry> files;
}
