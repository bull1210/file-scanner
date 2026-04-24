package com.filescanner.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "file_entry", indexes = {
    @Index(name = "idx_scan_id",       columnList = "scan_id"),
    @Index(name = "idx_extension",     columnList = "extension"),
    @Index(name = "idx_depth",         columnList = "depth"),
    @Index(name = "idx_size_bytes",    columnList = "size_bytes"),
    @Index(name = "idx_last_modified", columnList = "last_modified")
})
@Data
@NoArgsConstructor
public class FileEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scan_id", nullable = false)
    private Long scanId;

    @Column(name = "name", nullable = false, length = 512)
    private String name;

    @Column(name = "full_path", nullable = false, length = 4096)
    private String fullPath;

    @Column(name = "extension", length = 32)
    private String extension;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "last_modified")
    private LocalDateTime lastModified;

    @Column(name = "is_directory", nullable = false)
    private boolean directory;

    @Column(name = "depth", nullable = false)
    private int depth;

    @Column(name = "parent_path", length = 4096)
    private String parentPath;
}
