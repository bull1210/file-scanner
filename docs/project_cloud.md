---
name: Cloud Integration
description: Google Drive and OneDrive OAuth2 flows, sync strategies, SSE progress, and OAuth redirect URIs
type: project
originSessionId: 1263378a-94f1-4f6a-a8b0-fa0e6a867a0c
---
**Status:** Fully implemented — backend services, entities, repositories, controller routes, and all UI templates are in place.

## Google Drive (`GoogleDriveService`)
- **OAuth scopes:** `drive.readonly`, `userinfo.email`, `userinfo.profile`
- **Redirect URI:** `http://localhost:8080/cloud/oauth/callback/googledrive`
- **Sync strategy:** Two-pass
  1. Pass 1: fetch all folders (1000/page), build folderId→fullPath map
  2. Pass 2: stream non-folder files (1000/page), resolve parents from map
- **Throttle:** 50ms sleep between API pages to stay under Google's 1000 req/100s quota
- **Token refresh:** auto-refresh when within 5 min of expiry

## OneDrive (`OneDriveService`)
- **OAuth scopes:** `Files.Read.All`, `offline_access`, `User.Read`
- **Redirect URI:** `http://localhost:8080/cloud/oauth/callback/onedrive`
- **Endpoint:** Microsoft Graph v1.0
- **Sync strategy:** Single-pass delta API (`/me/drive/root/delta`) — files + folders in one walk
- **Token refresh:** same 5-min buffer pattern

## CloudSyncService (orchestration)
- Async via ExecutorService (cached thread pool)
- Deletes all previous `cloud_file_entry` records before re-sync
- Batches 500 entries per `saveAll` flush
- SSE progress emitted every 200 items: `{stage, count, message, done, error}`
- Sets `CloudAccount.status` to ERROR with message on failure

## OAuth App Setup (required for users)
- **Google:** Create OAuth 2.0 client at Google Cloud Console; add redirect URI above
- **Microsoft:** Create App Registration at Azure Portal; add redirect URI above
- Users enter `clientId` + `clientSecret` in the UI at `/cloud`; tokens stored in `CloudAccount` entity

## Why:
Google Drive and OneDrive were added to let users scan cloud storage the same way they scan local directories, with browsing, search, and AI queries over cloud file metadata.
**How to apply:** When touching cloud sync, remember Google needs the two-pass folder map to avoid N+1 parent lookups. OneDrive delta API handles both files and folders in one pass.
