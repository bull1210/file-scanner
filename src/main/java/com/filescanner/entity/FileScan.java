package com.filescanner.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "file_scan")
@Data
@NoArgsConstructor
public class FileScan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "root_path", nullable = false, length = 2048)
    private String rootPath;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "total_files")
    private Long totalFiles;

    @Column(name = "total_size_bytes")
    private Long totalSizeBytes;

    public FileScan(String rootPath) {
        this.rootPath = rootPath;
        this.startedAt = LocalDateTime.now();
    }
}
