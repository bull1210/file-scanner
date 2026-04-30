package com.filescanner.service;

import com.filescanner.dto.CloudDuplicateGroup;
import com.filescanner.dto.DuplicateGroup;
import com.filescanner.dto.StatsDto;
import com.filescanner.entity.CloudFileEntry;
import com.filescanner.entity.FileEntry;
import com.filescanner.repository.CloudFileEntryRepository;
import com.filescanner.repository.FileEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DuplicateService {

    private final FileEntryRepository      entryRepo;
    private final CloudFileEntryRepository cloudEntryRepo;

    // -------------------------------------------------------------------------
    // Local scan duplicates
    // -------------------------------------------------------------------------

    public List<DuplicateGroup> findDuplicates(Long scanId) {
        List<FileEntry> allDups = entryRepo.findAllDuplicateFiles(scanId);

        Map<String, List<FileEntry>> byName = new LinkedHashMap<>();
        for (FileEntry e : allDups) {
            byName.computeIfAbsent(e.getName(), k -> new ArrayList<>()).add(e);
        }

        return byName.entrySet().stream()
                .map(entry -> {
                    List<FileEntry> files = entry.getValue();
                    long totalSize = files.stream()
                            .mapToLong(f -> f.getSizeBytes() != null ? f.getSizeBytes() : 0L)
                            .sum();
                    return new DuplicateGroup(entry.getKey(), files.size(), totalSize, files);
                })
                .sorted(Comparator.comparingLong(DuplicateGroup::getTotalSizeBytes).reversed())
                .collect(Collectors.toList());
    }

    @Transactional
    public String deleteFile(Long entryId) throws IOException {
        FileEntry entry = entryRepo.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("File entry not found: " + entryId));

        Path path = Path.of(entry.getFullPath());
        boolean deleted = Files.deleteIfExists(path);
        entryRepo.deleteById(entryId);

        log.info("Deleted file (existed={}): {}", deleted, entry.getFullPath());
        return entry.getFullPath();
    }

    public byte[] exportCsv(List<DuplicateGroup> groups) {
        StringBuilder sb = new StringBuilder();
        sb.append("File Name,Occurrences,Total Size (bytes),Total Size,Full Path,Size (bytes),Size,Last Modified\n");

        for (DuplicateGroup group : groups) {
            for (FileEntry file : group.getFiles()) {
                long sz = file.getSizeBytes() != null ? file.getSizeBytes() : 0L;
                sb.append(escapeCsv(group.getName())).append(",");
                sb.append(group.getCount()).append(",");
                sb.append(group.getTotalSizeBytes()).append(",");
                sb.append(escapeCsv(StatsDto.formatBytes(group.getTotalSizeBytes()))).append(",");
                sb.append(escapeCsv(file.getFullPath())).append(",");
                sb.append(sz).append(",");
                sb.append(escapeCsv(StatsDto.formatBytes(sz))).append(",");
                sb.append(file.getLastModified() != null ? file.getLastModified() : "").append("\n");
            }
        }

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------
    // Cloud account duplicates
    // -------------------------------------------------------------------------

    public List<CloudDuplicateGroup> findCloudDuplicates(Long accountId) {
        List<CloudFileEntry> allDups = cloudEntryRepo.findAllDuplicateFiles(accountId);

        Map<String, List<CloudFileEntry>> byName = new LinkedHashMap<>();
        for (CloudFileEntry e : allDups) {
            byName.computeIfAbsent(e.getName(), k -> new ArrayList<>()).add(e);
        }

        return byName.entrySet().stream()
                .map(entry -> {
                    List<CloudFileEntry> files = entry.getValue();
                    long totalSize = files.stream()
                            .mapToLong(f -> f.getSizeBytes() != null ? f.getSizeBytes() : 0L)
                            .sum();
                    return new CloudDuplicateGroup(entry.getKey(), files.size(), totalSize, files);
                })
                .sorted(Comparator.comparingLong(CloudDuplicateGroup::getTotalSizeBytes).reversed())
                .collect(Collectors.toList());
    }

    @Transactional
    public void removeCloudEntry(Long entryId) {
        if (!cloudEntryRepo.existsById(entryId)) {
            throw new IllegalArgumentException("Cloud file entry not found: " + entryId);
        }
        cloudEntryRepo.deleteById(entryId);
        log.info("Removed cloud file entry #{} from scan index", entryId);
    }

    public byte[] exportCloudCsv(List<CloudDuplicateGroup> groups) {
        StringBuilder sb = new StringBuilder();
        sb.append("File Name,Occurrences,Total Size (bytes),Total Size,Full Path,Size (bytes),Size,Last Modified,Web URL\n");

        for (CloudDuplicateGroup group : groups) {
            for (CloudFileEntry file : group.getFiles()) {
                long sz = file.getSizeBytes() != null ? file.getSizeBytes() : 0L;
                sb.append(escapeCsv(group.getName())).append(",");
                sb.append(group.getCount()).append(",");
                sb.append(group.getTotalSizeBytes()).append(",");
                sb.append(escapeCsv(StatsDto.formatBytes(group.getTotalSizeBytes()))).append(",");
                sb.append(escapeCsv(file.getFullPath())).append(",");
                sb.append(sz).append(",");
                sb.append(escapeCsv(StatsDto.formatBytes(sz))).append(",");
                sb.append(file.getLastModified() != null ? file.getLastModified() : "").append(",");
                sb.append(escapeCsv(file.getWebUrl())).append("\n");
            }
        }

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
