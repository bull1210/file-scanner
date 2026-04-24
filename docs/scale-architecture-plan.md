# Scale-Up Architecture Plan: Millions of Files, Multi-Node, Periodic Reports

## Context

The current file-scanner is a single-node Spring Boot app backed by H2 (file-mode). At small scale it works well. At millions of files and audit events it has ten concrete failure points:

1. H2 is single-process — multi-node is impossible without replacing it
2. `WatchMonitorService` does 1 INSERT per audit event — saturates at ~300 events/sec
3. Dashboard runs 5 full O(n) table-scan queries on every load — no caching
4. Sync job state lives in `ConcurrentHashMap` — lost on restart, invisible to other nodes
5. SSE emitters are node-local — cross-node live progress is broken
6. `audit_event` grows unbounded — no partitioning, no retention
7. Cloud sync is full re-download every time — no delta/incremental logic
8. `file_entry` has 5 single-column indexes + two VARCHAR(4096) path columns — index bloat at 100M rows
9. No periodic report generation or export
10. No distributed locking — two nodes can double-sync the same account

The goal is to resolve all ten problems incrementally, keeping the existing Spring Boot + Thymeleaf foundation intact throughout.

---

## Target Architecture

```
┌─────────────────────────────────────────────────┐
│              Load Balancer (nginx)               │
│         IP-hash sticky for SSE endpoints         │
└────────┬──────────────────────┬──────────────────┘
         │                      │
┌────────▼──────┐      ┌────────▼──────┐
│  App Node A   │      │  App Node B   │   (N nodes)
│ Spring Boot   │      │ Spring Boot   │
│ + ShedLock    │      │ + ShedLock    │
└───┬───────────┘      └───┬───────────┘
    │   SSE pub/sub         │   SSE pub/sub
    └──────────┬────────────┘
               │
    ┌──────────▼──────────┐
    │        Redis         │
    │  - Distributed locks │
    │  - SSE fan-out ch.   │
    │  - Stats L2 cache    │
    │  - Report job stream │
    └──────────┬───────────┘
               │
    ┌──────────▼──────────┐
    │     PostgreSQL       │
    │  - All tables        │
    │  - audit_event:      │
    │    monthly partitions│
    │  - ShedLock table    │
    │  - Stats cache table │
    │  - Report table      │
    └─────────────────────┘
```

---

## New Dependencies (`pom.xml`)

```xml
<!-- Database -->
<dependency>postgresql:42.x</dependency>
<dependency>org.flywaydb:flyway-core:10.x</dependency>

<!-- Distributed coordination -->
<dependency>org.redisson:redisson-spring-boot-starter:3.x</dependency>

<!-- Scheduled job locking (uses existing PostgreSQL conn — no extra infra) -->
<dependency>net.javacrumbs.shedlock:shedlock-spring:5.x</dependency>
<dependency>net.javacrumbs.shedlock:shedlock-provider-jdbc-template:5.x</dependency>

<!-- Report export -->
<dependency>org.apache.commons:commons-csv:1.x</dependency>
```

---

## Database Tier

### Replace H2 → PostgreSQL

- Add PostgreSQL driver + Flyway; remove H2 dependency
- Set `spring.jpa.hibernate.ddl-auto=validate` (Flyway owns schema from here)
- All JPA repositories work unchanged — they speak JPQL/Spring Data method names
- **Critical file:** `src/main/resources/application.properties`

### `audit_event` — Partition by month + composite index

**Migration V2:**
```sql
-- Recreate as partitioned table
CREATE TABLE audit_event (
  id BIGSERIAL, folder_path TEXT NOT NULL,
  file_path TEXT, old_path TEXT, event_type VARCHAR(32),
  source VARCHAR(16), user_name VARCHAR(256), domain_name VARCHAR(256),
  process_name VARCHAR(512), access_types VARCHAR(512),
  occurred_at TIMESTAMP NOT NULL
) PARTITION BY RANGE (occurred_at);

-- Monthly partitions (pre-create 6 months ahead via scheduled job)
CREATE TABLE audit_event_2026_04 PARTITION OF audit_event
  FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');

-- Replace 3 single-column indexes with 1 composite (the real access pattern)
CREATE INDEX idx_audit_folder_time ON audit_event (folder_path, occurred_at DESC);
-- Keep event_type only if cross-folder reports needed
```

Retention = `DROP TABLE audit_event_YYYY_MM` — instant, no row-by-row deletes.

### `file_entry` — Path normalization (at 50M+ rows)

**Migration V5** (Tier 3 — defer until approaching 50M rows):
```sql
CREATE TABLE path_dictionary (id BIGSERIAL PK, path TEXT UNIQUE NOT NULL);
-- Add FK columns to file_entry, backfill, drop old VARCHAR(4096) columns
ALTER TABLE file_entry ADD COLUMN full_path_id BIGINT REFERENCES path_dictionary(id);
ALTER TABLE file_entry ADD COLUMN parent_path_id BIGINT REFERENCES path_dictionary(id);
```

Replaces 4 composite indexes (covering current query patterns):
1. `(scan_id, is_directory, size_bytes DESC)` — largest files + browse
2. `(scan_id, extension)` — extension grouping
3. `(scan_id, depth)` — depth distribution
4. `(scan_id, parent_path_id)` — browse by parent (integer FK, not string compare)

Effect: ~90% index size reduction at 100M rows.

### New tables

**`stats_cache`** — replaces 5 live O(n) queries per dashboard load:
```sql
CREATE TABLE stats_cache (
  scan_id BIGINT PRIMARY KEY,
  computed_at TIMESTAMP NOT NULL,
  payload JSONB NOT NULL,
  ttl_seconds INT NOT NULL DEFAULT 300
);
```

**`report`** — report job registry:
```sql
CREATE TABLE report (
  id BIGSERIAL PK, report_type VARCHAR(32), scan_id BIGINT,
  parameters JSONB, status VARCHAR(16), created_at TIMESTAMP,
  completed_at TIMESTAMP, storage_path TEXT, file_size_bytes BIGINT
);
```

**`audit_retention_policy`**:
```sql
CREATE TABLE audit_retention_policy (
  folder_path TEXT PK, retain_days INT NOT NULL DEFAULT 90
);
```

**`shedlock`** (ShedLock standard table):
```sql
CREATE TABLE shedlock (
  name VARCHAR(64) PK, lock_until TIMESTAMP,
  locked_at TIMESTAMP, locked_by VARCHAR(255)
);
```

### Fix `file_entry.ageBuckets` query (H2 → PostgreSQL)

**File:** `src/main/java/com/filescanner/repository/FileEntryRepository.java`

Replace H2-specific `DATEDIFF`:
```sql
-- Old (H2)
SUM(CASE WHEN DATEDIFF('DAY', last_modified, NOW()) < 1 THEN 1 ELSE 0 END)
-- New (PostgreSQL)
SUM(CASE WHEN last_modified >= NOW() - INTERVAL '1 day' THEN 1 ELSE 0 END)
```

---

## Coordination Tier

### Redis — 3 roles

| Role | Mechanism | Key pattern |
|---|---|---|
| Distributed lock (sync/scan) | Redisson `RLock` with TTL + watchdog | `lock:sync:{accountId}` |
| SSE fan-out across nodes | Redis Pub/Sub | `sync:progress:{accountId}`, `audit:{folderPath}` |
| Report job queue | Redis Stream with consumer group | `jobs:reports` |
| Stats L2 cache | Redis Hash | `stats:{scanId}` |
| Node heartbeat | String with 30s TTL | `node:{nodeId}:heartbeat` |

### Replace `ConcurrentHashMap<Long, Future<?>>` with DB status + Redisson lock

**File:** `src/main/java/com/filescanner/service/CloudSyncService.java`

```java
// Before starting sync:
RLock lock = redisson.getLock("lock:sync:" + accountId);
if (!lock.tryLock(0, 60, TimeUnit.SECONDS)) return; // another node owns it
try { runSync(accountId); } finally { lock.unlock(); }
```

`isSyncing()` reads `CloudAccount.status == "SYNCING"` from DB instead of checking the local `Future` map.

### SSE fan-out via Redis Pub/Sub

**Files:**
- `src/main/java/com/filescanner/service/CloudSyncService.java` — `push()` publishes to Redis
- `src/main/java/com/filescanner/service/WatchMonitorService.java` — `broadcast()` publishes to Redis
- New `SseRelayListener.java` — subscribes to Redis channels, pushes to local `SseEmitter` list

```java
// CloudSyncService.push() — replaces local SseEmitter iteration
redisTemplate.convertAndSend("sync:progress:" + accountId, payload);

// SseRelayListener (new)
@Component
public class SseRelayListener implements MessageListener {
    // Receives from Redis, pushes to local SseEmitter lists
}
```

---

## Caching Tier

### Stats caching — eliminate 5 full-table scans per dashboard load

**File:** `src/main/java/com/filescanner/service/StatsService.java`

Strategy: cache-aside using `stats_cache` table (no Redis required for this — DB table is sufficient for a single stat per scan).

```java
public StatsDto computeStats(Long scanId) {
    // 1. Check stats_cache
    StatsCache cached = statsCacheRepo.findById(scanId).orElse(null);
    if (cached != null && !isStale(cached)) return deserialize(cached.getPayload());
    // 2. Compute from DB (existing 5 queries)
    StatsDto dto = computeFromDb(scanId);
    // 3. Write back
    statsCacheRepo.save(new StatsCache(scanId, LocalDateTime.now(), serialize(dto)));
    return dto;
}
```

TTL rules:
- Scan `COMPLETED` → 24-hour TTL (data never changes)
- Scan `RUNNING` → 30-second TTL (refresh on each batch flush)

Add `@CacheEvict` on `ScanService` after each `saveAll()` batch flush for active scans.

---

## Multi-Node Coordination

### Node identity + watch ownership

Add `node_id VARCHAR(64)` column to `file_scan` table (Migration V4).

**File:** `src/main/java/com/filescanner/service/WatchMonitorService.java`

On `startMonitoring(folderPath)`:
1. Check DB: is another node currently watching this path and its heartbeat is alive in Redis?
   - Yes → skip (don't duplicate)
   - No → claim by writing `node_id = thisNodeId` to DB, then start WatchService
2. On shutdown / `stopMonitoring()` → clear `node_id` from DB

Node heartbeat refresh: `@Scheduled(fixedDelay=15_000)` writes `SETEX node:{id}:heartbeat 30 "alive"` to Redis.

### Audit event batching (fix per-event INSERTs)

**File:** `src/main/java/com/filescanner/service/WatchMonitorService.java`

```java
// Add to WatchMonitorService
private final BlockingQueue<AuditEvent> auditBuffer = new LinkedBlockingQueue<>(5000);
private static final int FLUSH_EVERY_MS = 200;

// Dedicated flush thread (started in @PostConstruct)
private void flushLoop() {
    while (!Thread.currentThread().isInterrupted()) {
        List<AuditEvent> batch = new ArrayList<>();
        auditBuffer.drainTo(batch, 500);
        if (!batch.isEmpty()) auditRepo.saveAll(batch);
        Thread.sleep(FLUSH_EVERY_MS);
    }
}

// In the watch loop, replace auditRepo.save(ae) with:
auditBuffer.offer(ae);  // non-blocking; drops if buffer full (back-pressure)
broadcast(folderPath, ae);  // SSE push still immediate
```

Effect: 300 individual INSERTs/sec → 2 batched `saveAll()` calls/sec.

---

## Reporting Pipeline

### New: `ReportService.java`

Generates CSV reports using Apache Commons CSV, streamed row-by-row (no full result set in memory).

Report types (initial set):
- **Daily audit summary** — event counts by type + top 10 paths per folder
- **Weekly extension breakdown** — per-scan extension counts/sizes, largest files
- **Scan comparison** — files added/removed/changed between two scan IDs
- **Cloud sync summary** — generated after each sync completes

### New: `ReportController.java`

```
GET  /reports            — list all reports (reads report table)
GET  /reports/{id}/download — streams file from storage_path
POST /reports/generate   — on-demand trigger for a report type
```

### Scheduled report jobs (ShedLock protected)

```java
@Scheduled(cron = "0 0 2 * * *")           // daily at 02:00
@SchedulerLock(name = "dailyAuditReport", lockAtMostFor = "30m")
public void dailyAuditReport() { ... }

@Scheduled(cron = "0 0 3 * * MON")         // Monday at 03:00
@SchedulerLock(name = "weeklyExtReport", lockAtMostFor = "60m")
public void weeklyExtensionReport() { ... }
```

ShedLock uses the `shedlock` PostgreSQL table — guaranteed to run on exactly one node.

Report files stored in configurable local path: `reports.output.dir=./reports` (use NFS mount in multi-node).

---

## Audit Retention

### `RetentionService.java`

```java
@Scheduled(cron = "0 30 1 * * *")          // nightly at 01:30
@SchedulerLock(name = "auditRetention", lockAtMostFor = "2h")
public void enforceRetention() {
    // For each policy: DROP whole partition if entirely expired,
    // else DELETE FROM audit_event WHERE folder_path=:p AND occurred_at < cutoff
}
```

Monthly partition pre-creation job:
```java
@Scheduled(cron = "0 0 0 1 * *")           // 1st of each month
@SchedulerLock(name = "partitionCreate", lockAtMostFor = "5m")
public void createFuturePartitions() {
    // CREATE TABLE IF NOT EXISTS audit_event_YYYY_MM PARTITION OF ...
    // for next 3 months
}
```

---

## Incremental Cloud Sync (Delta)

**Files:**
- `src/main/java/com/filescanner/service/OneDriveService.java`
- `src/main/java/com/filescanner/service/GoogleDriveService.java`
- `src/main/java/com/filescanner/service/CloudSyncService.java`
- `src/main/java/com/filescanner/entity/CloudAccount.java` — add `deltaToken TEXT`
- `src/main/java/com/filescanner/repository/CloudFileEntryRepository.java` — upsert query

**OneDrive** already uses the delta endpoint. Store `@odata.deltaLink` token from the last page. On next sync, start from that URL instead of `/me/drive/root/delta`.

**Google Drive** change from `files.list` to `changes.list` API with stored `pageToken`.

**CloudSyncService** — on sync start:
```java
if (account.getDeltaToken() != null) {
    // Incremental: upsert changed entries, delete tombstones
    runIncrementalSync(account);
} else {
    // First sync or forced full: deleteByAccountId + full fetch
    runFullSync(account);
}
```

Upsert via:
```java
@Modifying @Transactional
@Query("""
  INSERT INTO cloud_file_entry (...) VALUES (...)
  ON CONFLICT (account_id, provider_id) DO UPDATE SET ...
""", nativeQuery = true)
void upsert(Long accountId, String providerId, ...);
```

---

## Prioritized Migration Steps

### Tier 1 — Do immediately (unblocks everything)

| Step | Change | Files | Effort |
|---|---|---|---|
| 1 | PostgreSQL + Flyway | `pom.xml`, `application.properties`, `V1__baseline.sql` | 2–3 days |
| 2 | Audit partition + composite index | `V2__partitioned_audit.sql` | 1 day |
| 3 | Stats cache table + `StatsService` cache-aside | `V3__stats_cache.sql`, `StatsService.java` | 1 day |

### Tier 2 — Multi-node foundation

| Step | Change | Files | Effort |
|---|---|---|---|
| 4 | Redis + Redisson distributed lock | `pom.xml`, `CloudSyncService.java` | 2 days |
| 5 | Redis SSE fan-out | `CloudSyncService.java`, `WatchMonitorService.java`, new `SseRelayListener.java` | 2 days |
| 6 | Audit event batching (queue + flush thread) | `WatchMonitorService.java` | 1 day |
| 7 | Node heartbeat + watch ownership | `WatchMonitorService.java`, `V4__scan_node_id.sql` | 1 day |

### Tier 3 — Reporting + retention

| Step | Change | Files | Effort |
|---|---|---|---|
| 8 | ShedLock setup | `pom.xml`, `V5__shedlock.sql`, all `@Scheduled` methods | 0.5 days |
| 9 | Report pipeline (CSV) | New `ReportService.java`, `ReportController.java`, `V6__reports.sql` | 3 days |
| 10 | Audit retention + partition management | New `RetentionService.java`, `V7__retention_policy.sql` | 1 day |
| 11 | Incremental cloud sync | `OneDriveService.java`, `GoogleDriveService.java`, `CloudSyncService.java`, `CloudAccount.java`, `V8__cloud_delta.sql` | 2 days |

### Tier 4 — Scale optimization (at 50M+ rows)

| Step | Change | Files | Effort |
|---|---|---|---|
| 12 | Path dictionary normalization | `V9__path_dict.sql`, `ScanService.java`, `FileEntryRepository.java`, `BrowseController.java` | 3 days |

---

## Verification

### After Step 1 (PostgreSQL)
- `mvn spring-boot:run` starts without errors
- `/`, `/dashboard/{id}`, `/cloud` all load
- Scan a directory → files appear in browse
- H2 console no longer available (expected)

### After Step 2 (Audit partition)
- Start WatchService on a folder, create/delete files
- Confirm `SELECT * FROM audit_event_2026_04` returns rows
- Confirm `EXPLAIN` on `findByFolderAndType()` shows Index Scan on `idx_audit_folder_time`

### After Step 3 (Stats cache)
- Load dashboard → observe `stats_cache` table row written
- Load dashboard again → observe `computed_at` unchanged (cache hit)
- Confirm `EXPLAIN` on dashboard queries is not triggered on second load

### After Step 5 (Redis SSE)
- Start two app instances on different ports
- Connect SSE browser tab to Node A
- Trigger cloud sync via Node B
- Confirm progress updates arrive on the Node A tab

### After Step 9 (Reports)
- `POST /reports/generate?type=DAILY_AUDIT` → row appears in `report` table
- `GET /reports/{id}/download` → CSV file downloads correctly
- Verify ShedLock: trigger daily job on two nodes simultaneously → only one runs (check `shedlock` table `locked_by` column)

### After Step 11 (Incremental sync)
- Connect Google Drive, run full sync → `delta_token` populated in `cloud_account`
- Re-sync → verify no `deleteByAccountId` called (check logs)
- Create a file in Google Drive, re-sync → only that file appears in sync log
