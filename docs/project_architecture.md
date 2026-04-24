---
name: Architecture
description: Complete layer-by-layer breakdown of file-scanner: all controllers, services, entities, repos, and templates
type: project
originSessionId: 1263378a-94f1-4f6a-a8b0-fa0e6a867a0c
---
**Package root:** `com.filescanner`

## Entities
- `FileScan` — scan run metadata (id, rootPath, startedAt, finishedAt, totalFiles, totalSizeBytes)
- `FileEntry` — one row per local file/dir; indexes on scan_id, extension, depth, size_bytes, last_modified
- `CloudAccount` — OAuth credentials + sync state (provider, clientId, clientSecret, accessToken, refreshToken, tokenExpiresAt, status: PENDING→CONNECTED→SYNCING→SYNCED/ERROR)
- `CloudFileEntry` — cloud file mirror with webUrl; indexes on account_id, account_id+parent_path, extension, size_bytes, last_modified
- `AuditEvent` — FS change record (source: WATCHSERVICE/WINEVENTLOG, type: CREATED/DELETED/MODIFIED/RENAMED/READ/WRITE/PERMISSION_CHANGED/ACCESS, user/domain/process, timestamps)

## Repositories
- `FileScanRepository`, `FileEntryRepository` — Spring Data JPA
- `CloudAccountRepository`, `CloudFileEntryRepository`
- `AuditEventRepository`

## Services
- `ScanService` — `Files.walkFileTree`, max depth 50, batch 500, fills FileEntry + FileScan
- `StatsService` — on-demand dashboard stats (top 20 files, extension dist, depth dist, age buckets via H2 DATEDIFF), 15-entry chart limit
- `AiQueryService` — NL → Claude API → SQL → blocklist check → JdbcTemplate execution
- `GoogleDriveService` — OAuth2 + two-pass sync (folders map first, then files); 50ms throttle between pages
- `OneDriveService` — OAuth2 + single-pass delta API sync
- `CloudSyncService` — async sync orchestration, batch 500, SSE progress events every 200 items
- `WatchMonitorService` — NIO WatchService, recursive, rename heuristic (DELETE+CREATE in same parent within 400ms)
- `WinEventLogService` — PowerShell admin query of Windows Security Event Log (events 4663/4660/4670)

## Controllers → Routes
- `ScanController` — `GET /`, `POST /scan`
- `DashboardController` — `GET /dashboard/{scanId}`
- `BrowseController` — `GET /browse/{scanId}` (100/page, path nav, search, breadcrumbs)
- `CloudController` — `GET/POST /cloud`, `GET /cloud/connect/{id}`, OAuth callbacks, `POST /cloud/{id}/sync`, `GET /cloud/{id}/progress` (SSE), `GET /cloud/{id}/browse`, `POST /cloud/{id}/delete`
- `QueryController` — `GET/POST /query`
- `AuditController` — `GET /audit`, `POST /audit/start|stop|clear|winlog`, `GET /audit/stream` (SSE)

## Templates (Thymeleaf)
`index.html`, `dashboard.html`, `browse.html`, `query.html`, `audit.html`, `cloud-accounts.html`, `cloud-add.html`, `cloud-detail.html`, `cloud-browse.html`

## Key application.properties
```
server.port=8080
anthropic.model=claude-sonnet-4-6
anthropic.max.tokens=1024
scanner.max.depth=50
scanner.batch.size=500
cloud.oauth.redirect-base=http://localhost:8080
cloud.sync.batch-size=500
spring.jpa.hibernate.ddl-auto=update
spring.jpa.properties.hibernate.jdbc.batch_size=500
```
