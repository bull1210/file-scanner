# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build (skip tests)
mvn clean package -DskipTests

# Build and run tests
mvn clean verify

# Run the application
mvn spring-boot:run

# Run a single test class
mvn test -Dtest=ClassName

# Run a single test method
mvn test -Dtest=ClassName#methodName
```

The app starts on `http://localhost:8080`. The H2 console is at `/h2-console` (JDBC URL: `jdbc:h2:file:./filedb`, user: `sa`, no password).

## Environment

Set `ANTHROPIC_API_KEY` before running — the AI query feature calls the Anthropic API. The property defaults to `change-me` if unset, which will cause AI queries to fail.

## Architecture

**Layered MVC:**
- `controller/` → handles HTTP, redirects, flash messages, pagination
- `service/` → business logic (scan, stats computation, AI query)
- `repository/` → Spring Data JPA + one `JdbcTemplate` call (AI query result execution)
- `entity/` → `FileScan` (scan run metadata) + `FileEntry` (one row per file/dir)
- `dto/` → `StatsDto` (dashboard chart data) + `QueryResult` (AI query output)
- `templates/` → Thymeleaf pages; Chart.js 4 and Bootstrap 5 are loaded via CDN

**Scanning flow (`ScanService`):**
`Files.walkFileTree` traverses the directory up to `scanner.max.depth` (default 50). Entries are buffered and flushed in batches of `scanner.batch.size` (default 500) via `saveAll` to keep insert cost manageable. `FileScan.finishedAt` and totals are written once the walk completes.

**AI query flow (`AiQueryService`):**
User natural language → Claude API (system prompt describes both tables + H2 dialect hints) → raw SQL response cleaned of markdown fences → **regex-checked against a blocklist** (INSERT/UPDATE/DELETE/DROP/TRUNCATE/ALTER/CREATE/REPLACE/MERGE/EXEC/GRANT/REVOKE) → must start with SELECT → executed via `JdbcTemplate.queryForList()` → results rendered as a dynamic HTML table in `query.html`.

**Database:**
H2 file-based at `./filedb` (relative to CWD when the app starts). Schema is auto-managed by Hibernate (`ddl-auto=update`). `FileEntry` has five indexes: `scan_id`, `extension`, `depth`, `size_bytes`, `last_modified` — keep these in mind when adding new queries.

**Browse pagination:**
`BrowseController` pages at 100 entries per page. Directory listing sorts directories first then alphabetically. Breadcrumbs are built by splitting `fullPath` on the OS separator; cross-drive paths are handled gracefully.

**Stats computation (`StatsService`):**
All chart data is computed from the DB at request time — no caching. Extension stats are limited to 15 entries in the DTO (`extLabels` / `extCounts` / `extSizes` lists). Age buckets use a native H2 SQL query via `DATEDIFF`.
