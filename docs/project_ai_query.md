---
name: AI Query Feature
description: Natural language → SQL via Claude API with security blocklist — how AiQueryService works
type: project
originSessionId: 1263378a-94f1-4f6a-a8b0-fa0e6a867a0c
---
**Flow:** User types NL question → `AiQueryService` → Claude API (`claude-sonnet-4-6`) → raw SQL → security check → `JdbcTemplate.queryForList()` → rendered table in `query.html`

**System prompt tells Claude:** Generate read-only H2 SQL only; describes `file_scan` and `file_entry` table schemas + H2 dialect hints (DATEDIFF, LOWER, etc.); return ONLY raw SQL, no markdown, no prose; default to most recent scan; LIMIT 100.

**Security layer:**
- Regex blocklist: INSERT, UPDATE, DELETE, DROP, TRUNCATE, ALTER, CREATE, REPLACE, MERGE, EXEC, GRANT, REVOKE
- SQL must start with SELECT — throws `SecurityException` otherwise

**Config (application.properties):**
```
anthropic.api.key=${ANTHROPIC_API_KEY:change-me}
anthropic.model=claude-sonnet-4-6
anthropic.api.url=https://api.anthropic.com/v1/messages
anthropic.max.tokens=1024
```

**HTTP client:** OkHttp (not Spring's RestTemplate/WebClient)

**Why:** Lets non-technical users explore scan data without writing SQL. The blocklist + SELECT-only check prevents the AI from generating destructive statements.
**How to apply:** If extending AI queries to cover cloud_file_entry, update the system prompt to describe that table too.
