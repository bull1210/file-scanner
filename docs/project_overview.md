---
name: Project Overview
description: High-level summary of the file-scanner Spring Boot project — purpose, stack, entry points
type: project
originSessionId: 1263378a-94f1-4f6a-a8b0-fa0e6a867a0c
---
**file-scanner** is a Spring Boot 3.2.5 / Java 17 web app at `C:\Users\mmkan\testclause\file-scanner`.

**Purpose:** Scan local directories and cloud storage (Google Drive, OneDrive), explore files with pagination/search, run AI-powered natural language SQL queries, and monitor file system changes in real time.

**Stack:**
- Spring Boot 3.2.5, Spring Web, Spring Data JPA, Thymeleaf
- H2 file-based DB at `./filedb` (JDBC: `jdbc:h2:file:./filedb`, user `sa`, no password)
- OkHttp 4.12.0 for cloud OAuth/API calls
- Bootstrap 5 + Chart.js 4 via CDN
- Lombok, Jackson, Spring Validation

**Run:**
- `mvn spring-boot:run` → http://localhost:8080
- H2 console: http://localhost:8080/h2-console
- Requires `ANTHROPIC_API_KEY` env var for AI query feature

**Build:** `mvn clean package -DskipTests` / `mvn clean verify`

**Why:** A local-first file intelligence tool — all data stays in H2, no cloud dependency for storage.
**How to apply:** Always assume H2 file-mode constraints. No caching layer — stats computed on-demand from DB.
