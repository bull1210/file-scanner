# Plan: Spring Boot File System Scanner with AI Query

## Context
Build a Spring Boot application that recursively scans a given directory, persists all file metadata in an embedded H2 database, presents statistics via a dashboard with Chart.js charts, supports tree browsing, and lets users ask natural-language questions that are converted to SQL by the Claude API and rendered as HTML results.

---

## Tech Stack
- **Spring Boot 3.2.x** (Maven, Java 21)
- **JPA/Hibernate + H2 file-mode** — persistent embedded DB, no external setup
- **Thymeleaf** — server-side HTML rendering
- **Bootstrap 5 + Chart.js 4** — UI and charts
- **OkHttp** — REST calls to Anthropic Messages API (`claude-sonnet-4-6`)
- **Lombok** — reduce boilerplate

---

## Project Structure
```
file-scanner/
├── pom.xml
└── src/main/
    ├── java/com/filescanner/
    │   ├── FileScannerApplication.java
    │   ├── entity/
    │   │   ├── FileScan.java
    │   │   └── FileEntry.java
    │   ├── repository/
    │   │   ├── FileScanRepository.java
    │   │   └── FileEntryRepository.java
    │   ├── service/
    │   │   ├── ScanService.java
    │   │   ├── StatsService.java
    │   │   └── AiQueryService.java
    │   ├── controller/
    │   │   ├── ScanController.java
    │   │   ├── DashboardController.java
    │   │   ├── BrowseController.java
    │   │   └── QueryController.java
    │   └── dto/
    │       ├── StatsDto.java
    │       └── QueryResult.java
    └── resources/
        ├── application.properties
        └── templates/
            ├── index.html
            ├── dashboard.html
            ├── browse.html
            └── query.html
```

---

## Database Schema (H2 file-mode, `./filedb`)

### `file_scan`
| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | auto-increment |
| root_path | VARCHAR(2048) | scanned directory |
| started_at | TIMESTAMP | |
| finished_at | TIMESTAMP | null while running |
| total_files | BIGINT | |
| total_size_bytes | BIGINT | |

### `file_entry`
| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | auto-increment |
| scan_id | BIGINT | FK to file_scan |
| name | VARCHAR(512) | filename only |
| full_path | VARCHAR(4096) | absolute path |
| extension | VARCHAR(32) | lowercase, no dot; NULL for dirs |
| size_bytes | BIGINT | 0 for directories |
| created_at | TIMESTAMP | |
| last_modified | TIMESTAMP | |
| is_directory | BOOLEAN | |
| depth | INT | 0 = root |
| parent_path | VARCHAR(4096) | |

Indexes on: `scan_id`, `extension`, `size_bytes`, `last_modified`, `depth`

---

## Key application.properties
```properties
spring.datasource.url=jdbc:h2:file:./filedb;AUTO_SERVER=TRUE;DB_CLOSE_ON_EXIT=FALSE
spring.jpa.hibernate.ddl-auto=update
spring.h2.console.enabled=true

anthropic.api.key=${ANTHROPIC_API_KEY}
anthropic.model=claude-sonnet-4-6
anthropic.api.url=https://api.anthropic.com/v1/messages
anthropic.max.tokens=1024

scanner.batch.size=500
scanner.max.depth=50
```

---

## Implementation Steps (build in this order to stay compilable)

### Step 1 — pom.xml
Dependencies: `spring-boot-starter-web`, `spring-boot-starter-thymeleaf`, `spring-boot-starter-data-jpa`, `spring-boot-starter-jdbc`, `spring-boot-starter-validation`, `h2` (runtime), `okhttp3:4.12.0`, `jackson-databind`, `lombok`

### Step 2 — Entities
- **FileScan.java** (`@Entity`, `@Table(name="file_scan")`) — fields: id, rootPath, startedAt, finishedAt, totalFiles, totalSizeBytes
- **FileEntry.java** (`@Entity`, `@Table(name="file_entry")`, 5 `@Index` annotations) — all fields above; `scan_id` stored as plain `Long` (not `@ManyToOne`) for efficient batch inserts

### Step 3 — Repositories
- **FileScanRepository** — `findAllByOrderByStartedAtDesc()`
- **FileEntryRepository** — derived queries for browsing + 4 `@Query` methods:
  - `countByExtension(scanId)` → `[ext, count, totalSize][]`
  - `countByDepth(scanId)` → `[depth, count][]`
  - `ageBuckets(scanId)` → native SQL with `DATEDIFF` returning 5 bucket counts
  - `totalCountAndSize(scanId)` → `[count, totalBytes]`

### Step 4 — ScanService
- `startScan(String rootPath)` — validates path, creates `FileScan`, walks tree with `Files.walkFileTree`, builds `FileEntry` objects, flushes to DB in batches of 500, finalises scan record
- `buildEntry()` — extracts name, path, extension (lowercase, no dot), size, timestamps, depth
- `visitFileFailed()` — logs and continues (permission errors don't abort scan)

### Step 5 — StatsService + StatsDto
- `computeStats(scanId)` — calls all 4 repository aggregate methods, populates `StatsDto`
- **StatsDto** holds: `largestFiles` (List\<FileEntry\>), `extLabels/Counts/Sizes`, `depthLevels/Counts`, `ageBuckets[5]`, totals, `getFormattedTotalSize()` helper

### Step 6 — AiQueryService
Flow: user prompt → Claude API → raw SQL → safety check → `JdbcTemplate.queryForList()` → `QueryResult`

**Safety checks (defence-in-depth):**
1. Regex blocks `INSERT|UPDATE|DELETE|DROP|TRUNCATE|ALTER|CREATE|...` (word-boundary `\b`)
2. Must start with `SELECT`

**System prompt key elements:**
- Full schema (DDL-style with column comments for extension format, FK notes)
- H2-specific hints: `DATEDIFF('DAY', col, NOW())`, `LIMIT N`
- Default to latest scan: `(SELECT MAX(id) FROM file_scan)` when user doesn't specify
- `1 MB = 1048576 bytes` to prevent unit confusion
- "Output ONLY the raw SQL — no markdown, no code fences, no prose"
- Strip accidental code fences as fallback

OkHttp client: 10s connect timeout, 30s read timeout. Request uses headers `x-api-key`, `anthropic-version: 2023-06-01`, `content-type: application/json`.

### Step 7 — Controllers
| Controller | Route | Purpose |
|---|---|---|
| ScanController | GET `/`, POST `/scan` | Home page (scan form + past scans list), trigger scan |
| DashboardController | GET `/dashboard/{scanId}` | Stats page with all 4 charts |
| BrowseController | GET `/browse/{scanId}?path=&search=&page=` | File tree, pagination 100/page, breadcrumbs |
| QueryController | GET/POST `/query` | AI query form + results |

### Step 8 — Thymeleaf Templates

**index.html** — scan form + Bootstrap table of past scans (id, path, started, duration, file count), links to Dashboard/Browse for each, link to AI Query

**dashboard.html** — 4 summary cards (total files, total size, extension count, duration) + 4 Chart.js canvases:
- Doughnut: top 15 extensions by count
- Horizontal bar: top 20 largest files (x-axis labels formatted as KB/MB)
- Bar: files by age bucket (< 1d, 1–7d, 7–30d, 30d–1yr, > 1yr)
- Bar: entry count by depth level
Data injected via Thymeleaf `/*[[...]]*/` inline JavaScript variables

**browse.html** — breadcrumb nav, search input, Bootstrap table (type badge, name, ext, size, last modified, depth), server-side pagination, directories are clickable links for drill-down

**query.html** — textarea prompt, example questions list, "Generated SQL" code block, results as Bootstrap table with dynamic columns from `QueryResult`

---

## Verification Checklist
1. `mvn clean package && java -jar target/*.jar` — app starts, H2 creates `filedb.mv.db`
2. `http://localhost:8080/h2-console` — `FILE_SCAN` and `FILE_ENTRY` tables exist
3. Enter a local path on home page → scan runs → redirects to dashboard with populated charts
4. Browse page: click a directory → drills in; breadcrumbs update; search filters by path fragment
5. `/query` → type "show me the 5 largest files" → Generated SQL shows `ORDER BY size_bytes DESC LIMIT 5` → results table renders
6. Restart app → home page still shows past scans (H2 file persistence)
7. Try malicious prompt "delete all files" → app returns "Rejected: query would modify data" error

---

## Critical Files to Create
- `pom.xml`
- `src/main/resources/application.properties`
- `src/main/java/com/filescanner/entity/FileEntry.java`
- `src/main/java/com/filescanner/service/ScanService.java`
- `src/main/java/com/filescanner/service/AiQueryService.java`
- `src/main/resources/templates/dashboard.html`
- `src/main/resources/templates/query.html`
