package com.filescanner.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "cloud_file_entry", indexes = {
    @Index(name = "idx_cfe_account",     columnList = "account_id"),
    @Index(name = "idx_cfe_parent",      columnList = "account_id,parent_path"),
    @Index(name = "idx_cfe_extension",   columnList = "extension"),
    @Index(name = "idx_cfe_size",        columnList = "size_bytes"),
    @Index(name = "idx_cfe_modified",    columnList = "last_modified")
})
@Data
@NoArgsConstructor
public class CloudFileEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** Native file/folder ID from the provider */
    @Column(name = "provider_id", length = 512)
    private String providerId;

    @Column(name = "name", length = 512)
    private String name;

    @Column(name = "full_path", length = 4096)
    private String fullPath;

    @Column(name = "parent_path", length = 4096)
    private String parentPath;

    @Column(name = "mime_type", length = 256)
    private String mimeType;

    @Column(name = "extension", length = 32)
    private String extension;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "is_directory")
    private boolean directory;

    @Column(name = "last_modified")
    private LocalDateTime lastModified;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** Direct browser URL to open the file in OneDrive / Google Drive */
    @Column(name = "web_url", length = 2048)
    private String webUrl;
}
