---
name: Audit & Monitoring
description: Real-time file system monitoring via NIO WatchService and Windows Security Event Log
type: project
originSessionId: 1263378a-94f1-4f6a-a8b0-fa0e6a867a0c
---
## WatchMonitorService (NIO WatchService)
- Recursively registers all subdirectories under the monitored root
- Detects: CREATE, DELETE, MODIFY
- **Rename heuristic:** DELETE + CREATE in same parent within 400ms → marked as RENAMED
- Persists each change as `AuditEvent` to DB
- Broadcasts live events via SSE (`GET /audit/stream`) — one emitter per monitored folder
- Auto-registers newly created subdirectories

## WinEventLogService (Windows Security Event Log)
- Runs PowerShell as admin
- Queries Windows Security Event Log for events: 4663 (file access), 4660 (delete), 4670 (permission change)
- Decodes Windows access masks (%%4416=Read, %%4417=Write, etc.)
- Checks admin status + audit policy enablement via PowerShell
- Parses PowerShell JSON → `AuditEvent` records
- Handles both single-event and array responses from PowerShell

## AuditEvent fields
- `source`: WATCHSERVICE or WINEVENTLOG
- `eventType`: CREATED, DELETED, MODIFIED, RENAMED, READ, WRITE, PERMISSION_CHANGED, ACCESS
- `user`, `domain`, `process`: populated from Windows event log entries
- DB indexes on: folder_path, occurred_at, event_type

## Controller routes (`AuditController`)
- `GET /audit` — dashboard (folder picker or event viewer)
- `POST /audit/start` — start WatchService on folder
- `POST /audit/stop` — stop monitoring
- `GET /audit/stream` — SSE live events
- `POST /audit/winlog` — query Windows Security Event Log (requires admin)
- `POST /audit/clear` — clear stored audit events

**Why:** Provides real-time visibility into who/what is touching files on the machine, and integrates with Windows native auditing for privileged access tracking.
**How to apply:** WinEventLogService requires admin privileges and Windows Security Auditing to be enabled on the target folder. WatchService works without admin but only sees file-level changes, not user context.
