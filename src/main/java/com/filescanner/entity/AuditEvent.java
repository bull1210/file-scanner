package com.filescanner.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "audit_event", indexes = {
    @Index(name = "idx_audit_folder", columnList = "folder_path"),
    @Index(name = "idx_audit_time",   columnList = "occurred_at"),
    @Index(name = "idx_audit_type",   columnList = "event_type")
})
@Data
@NoArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "folder_path", length = 2048)
    private String folderPath;

    @Column(name = "file_path", length = 4096)
    private String filePath;

    @Column(name = "old_path", length = 4096)
    private String oldPath;               // populated for RENAMED events

    @Column(name = "event_type", length = 32)
    private String eventType;             // CREATED, DELETED, MODIFIED, RENAMED, READ, WRITE, PERMISSION_CHANGED, ACCESS

    @Column(name = "source", length = 16)
    private String source;                // WATCHSERVICE or WINEVENTLOG

    @Column(name = "user_name", length = 256)
    private String userName;

    @Column(name = "domain_name", length = 256)
    private String domainName;

    @Column(name = "process_name", length = 512)
    private String processName;

    @Column(name = "access_types", length = 512)
    private String accessTypes;           // e.g. "Read, Write" decoded from Windows access mask

    @Column(name = "occurred_at")
    private LocalDateTime occurredAt;
}
