package com.filescanner.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "cloud_account")
@Data
@NoArgsConstructor
public class CloudAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ONEDRIVE or GOOGLEDRIVE */
    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "client_id", length = 512)
    private String clientId;

    @Column(name = "client_secret", length = 512)
    private String clientSecret;

    @Column(name = "access_token", length = 4096)
    private String accessToken;

    @Column(name = "refresh_token", length = 4096)
    private String refreshToken;

    @Column(name = "token_expires_at")
    private LocalDateTime tokenExpiresAt;

    @Column(name = "user_email", length = 255)
    private String userEmail;

    @Column(name = "provider_user_id", length = 255)
    private String providerUserId;

    /** PENDING → CONNECTED → SYNCING → SYNCED / ERROR */
    @Column(name = "status", length = 32)
    private String status = "PENDING";

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Column(name = "total_files")
    private Long totalFiles;

    @Column(name = "total_folders")
    private Long totalFolders;

    @Column(name = "total_size_bytes")
    private Long totalSizeBytes;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
