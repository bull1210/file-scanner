package com.filescanner.service;

import com.filescanner.entity.FileEntry;
import com.filescanner.entity.FileScan;
import com.filescanner.repository.FileEntryRepository;
import com.filescanner.repository.FileScanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScanService {

    private final FileScanRepository scanRepo;
    private final FileEntryRepository entryRepo;

    @Value("${scanner.batch.size:500}")
    private int batchSize;

    @Value("${scanner.max.depth:50}")
    private int maxDepth;

    @Transactional
    public Long startScan(String rootPathStr) throws IOException {
        Path rootPath = Path.of(rootPathStr).toAbsolutePath().normalize();
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException(
                    "Path does not exist or is not a directory: " + rootPath);
        }

        FileScan scan = new FileScan(rootPath.toString());
        scan = scanRepo.save(scan);
        final Long scanId = scan.getId();

        List<FileEntry> buffer = new ArrayList<>(batchSize);
        AtomicLong fileCount  = new AtomicLong(0);
        AtomicLong totalBytes = new AtomicLong(0);

        Files.walkFileTree(rootPath, Set.of(), maxDepth, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                int depth = rootPath.relativize(dir).getNameCount();
                if (depth == 0) return FileVisitResult.CONTINUE;
                buffer.add(buildEntry(scanId, dir, attrs, true, depth, rootPath));
                flushIfFull(buffer);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                int depth = rootPath.relativize(file).getNameCount();
                buffer.add(buildEntry(scanId, file, attrs, false, depth, rootPath));
                totalBytes.addAndGet(attrs.size());
                fileCount.incrementAndGet();
                flushIfFull(buffer);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.warn("Cannot access: {} — {}", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        if (!buffer.isEmpty()) {
            entryRepo.saveAll(buffer);
        }

        scan.setFinishedAt(LocalDateTime.now());
        scan.setTotalFiles(fileCount.get());
        scan.setTotalSizeBytes(totalBytes.get());
        scanRepo.save(scan);

        log.info("Scan #{} complete: {} files, {} bytes", scanId, fileCount.get(), totalBytes.get());
        return scanId;
    }

    private void flushIfFull(List<FileEntry> buffer) {
        if (buffer.size() >= batchSize) {
            entryRepo.saveAll(buffer);
            buffer.clear();
        }
    }

    private FileEntry buildEntry(Long scanId, Path path, BasicFileAttributes attrs,
                                  boolean isDir, int depth, Path root) {
        FileEntry e = new FileEntry();
        e.setScanId(scanId);
        e.setDirectory(isDir);
        e.setDepth(depth);
        e.setFullPath(path.toString());
        e.setName(path.getFileName().toString());
        e.setParentPath(path.getParent() != null ? path.getParent().toString() : "");
        e.setSizeBytes(isDir ? 0L : attrs.size());
        e.setLastModified(LocalDateTime.ofInstant(
                attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault()));
        try {
            e.setCreatedAt(LocalDateTime.ofInstant(
                    attrs.creationTime().toInstant(), ZoneId.systemDefault()));
        } catch (Exception ex) {
            e.setCreatedAt(e.getLastModified());
        }
        if (!isDir) {
            String fname = path.getFileName().toString();
            int dot = fname.lastIndexOf('.');
            e.setExtension(dot >= 0 && dot < fname.length() - 1
                    ? fname.substring(dot + 1).toLowerCase()
                    : null);
        }
        return e;
    }

    public List<FileScan> getAllScans() {
        return scanRepo.findAllByOrderByStartedAtDesc();
    }

    public Optional<FileScan> getScan(Long scanId) {
        return scanRepo.findById(scanId);
    }
}
