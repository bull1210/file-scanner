package com.filescanner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.filescanner.entity.CloudAccount;
import com.filescanner.entity.CloudFileEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

@Service
@Slf4j
@RequiredArgsConstructor
public class GoogleDriveService {

    private final ObjectMapper objectMapper;

    private static final String AUTH_URL   = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL  = "https://oauth2.googleapis.com/token";
    private static final String DRIVE_BASE = "https://www.googleapis.com/drive/v3";
    private static final String USER_URL   = "https://www.googleapis.com/oauth2/v2/userinfo";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final String SCOPES =
            "https://www.googleapis.com/auth/drive.readonly " +
            "https://www.googleapis.com/auth/userinfo.email " +
            "https://www.googleapis.com/auth/userinfo.profile";

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60,  TimeUnit.SECONDS)
            .build();

    // -------------------------------------------------------------------------
    // OAuth
    // -------------------------------------------------------------------------

    public String buildAuthUrl(String clientId, String redirectUri, String state) {
        return AUTH_URL
                + "?client_id="    + enc(clientId)
                + "&response_type=code"
                + "&redirect_uri=" + enc(redirectUri)
                + "&scope="        + enc(SCOPES)
                + "&state="        + enc(state)
                + "&access_type=offline"
                + "&prompt=consent";
    }

    public TokenResponse exchangeCode(String code, String clientId,
                                       String clientSecret, String redirectUri) throws IOException {
        return callToken(new FormBody.Builder()
                .add("code",          code)
                .add("client_id",     clientId)
                .add("client_secret", clientSecret)
                .add("redirect_uri",  redirectUri)
                .add("grant_type",    "authorization_code")
                .build());
    }

    public TokenResponse refreshToken(CloudAccount account) throws IOException {
        return callToken(new FormBody.Builder()
                .add("refresh_token", account.getRefreshToken())
                .add("client_id",     account.getClientId())
                .add("client_secret", account.getClientSecret())
                .add("grant_type",    "refresh_token")
                .build());
    }

    // -------------------------------------------------------------------------
    // User info
    // -------------------------------------------------------------------------

    public UserInfo getUserInfo(String accessToken) throws IOException {
        JsonNode root = get(USER_URL, accessToken);
        return new UserInfo(
                root.path("id").asText(),
                root.path("email").asText("unknown"),
                root.path("name").asText(""));
    }

    // -------------------------------------------------------------------------
    // Efficient two-pass file sync
    // -------------------------------------------------------------------------

    public void syncFiles(CloudAccount account,
                          BiConsumer<CloudFileEntry, String> onEntry) throws IOException {
        String token = resolveToken(account);

        // Pass 1 — build folderId → fullPath map (avoids N+1 parent lookups)
        Map<String, String> pathMap = new HashMap<>();
        pathMap.put("root", "/");
        fetchFolderPaths(token, pathMap, onEntry);

        // Pass 2 — stream all non-folder files (folders already emitted in pass 1)
        String pageToken = null;
        do {
            String url = DRIVE_BASE + "/files?pageSize=1000"
                    + "&q=mimeType!='application/vnd.google-apps.folder' and trashed=false"
                    + "&fields=nextPageToken,files(id,name,size,mimeType,parents"
                    + ",modifiedTime,createdTime,webViewLink)"
                    + (pageToken != null ? "&pageToken=" + enc(pageToken) : "");
            JsonNode root = get(url, token);
            for (JsonNode item : root.path("files"))
                onEntry.accept(toEntry(account.getId(), item, pathMap), "FILES");
            pageToken = root.path("nextPageToken").asText(null);
            throttle();
        } while (pageToken != null);
    }

    /** Fetches all folders, builds path map iteratively, and emits each as a directory entry. */
    private void fetchFolderPaths(String token, Map<String, String> pathMap,
                                   BiConsumer<CloudFileEntry, String> onEntry) throws IOException {
        // folderId → [name, parentId]
        Map<String, String[]> raw = new HashMap<>();
        String pageToken = null;
        do {
            String url = DRIVE_BASE + "/files?pageSize=1000"
                    + "&q=mimeType='application/vnd.google-apps.folder' and trashed=false"
                    + "&fields=nextPageToken,files(id,name,parents,createdTime,modifiedTime,webViewLink)"
                    + (pageToken != null ? "&pageToken=" + enc(pageToken) : "");
            JsonNode root = get(url, token);
            for (JsonNode item : root.path("files")) {
                String id       = item.path("id").asText();
                String name     = item.path("name").asText("");
                String parentId = item.path("parents").size() > 0
                        ? item.path("parents").get(0).asText("root") : "root";
                raw.put(id, new String[]{name, parentId,
                        item.path("createdTime").asText(null),
                        item.path("modifiedTime").asText(null),
                        item.path("webViewLink").asText(null)});
            }
            pageToken = root.path("nextPageToken").asText(null);
            throttle();
        } while (pageToken != null);

        // Iterative path resolution (handles arbitrary depth)
        boolean changed = true;
        int guard = 60;
        while (changed && guard-- > 0) {
            changed = false;
            for (Map.Entry<String, String[]> e : raw.entrySet()) {
                if (pathMap.containsKey(e.getKey())) continue;
                String parentId = e.getValue()[1];
                if (pathMap.containsKey(parentId)) {
                    String parent = pathMap.get(parentId);
                    pathMap.put(e.getKey(), parent.equals("/") ? "/" + e.getValue()[0] : parent + "/" + e.getValue()[0]);
                    changed = true;
                }
            }
        }
        // Fallback for unresolvable parents
        raw.forEach((id, d) -> pathMap.putIfAbsent(id, "/" + d[0]));

        // Emit folder entries now that paths are known
        for (Map.Entry<String, String[]> e : raw.entrySet()) {
            String[] d = e.getValue();
            String parentId = d[1];
            String fullPath = pathMap.get(e.getKey());
            String parentPath = pathMap.getOrDefault(parentId, "/");

            CloudFileEntry fe = new CloudFileEntry();
            fe.setAccountId(null); // will be set by sync service
            fe.setProviderId(e.getKey());
            fe.setName(d[0]);
            fe.setDirectory(true);
            fe.setSizeBytes(0L);
            fe.setFullPath(fullPath);
            fe.setParentPath(parentPath);
            fe.setMimeType(FOLDER_MIME);
            fe.setCreatedAt(parseTime(d[2]));
            fe.setLastModified(parseTime(d[3]));
            fe.setWebUrl(d[4]);
            onEntry.accept(fe, "FOLDERS");
        }
    }

    private CloudFileEntry toEntry(Long accountId, JsonNode item, Map<String, String> pathMap) {
        CloudFileEntry e = new CloudFileEntry();
        e.setAccountId(accountId);
        e.setProviderId(item.path("id").asText());
        e.setName(item.path("name").asText(""));
        e.setMimeType(item.path("mimeType").asText(null));
        e.setDirectory(false);
        e.setWebUrl(item.path("webViewLink").asText(null));

        // Google Docs/Sheets/Slides have no size field
        String sz = item.path("size").asText(null);
        e.setSizeBytes(sz != null && !sz.isBlank() ? Long.parseLong(sz) : 0L);

        String parentId = item.path("parents").size() > 0
                ? item.path("parents").get(0).asText("root") : "root";
        String parentPath = pathMap.getOrDefault(parentId, "/");
        e.setParentPath(parentPath);
        e.setFullPath(parentPath.equals("/") ? "/" + e.getName() : parentPath + "/" + e.getName());

        int dot = e.getName().lastIndexOf('.');
        if (dot >= 0 && dot < e.getName().length() - 1)
            e.setExtension(e.getName().substring(dot + 1).toLowerCase());

        e.setLastModified(parseTime(item.path("modifiedTime").asText(null)));
        e.setCreatedAt(parseTime(item.path("createdTime").asText(null)));
        return e;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    String resolveToken(CloudAccount account) throws IOException {
        if (account.getTokenExpiresAt() == null
                || LocalDateTime.now().isAfter(account.getTokenExpiresAt().minusMinutes(5)))
            return refreshToken(account).accessToken();
        return account.getAccessToken();
    }

    private TokenResponse callToken(RequestBody body) throws IOException {
        Request req = new Request.Builder().url(TOKEN_URL).post(body).build();
        try (Response resp = http.newCall(req).execute()) {
            String json = resp.body().string();
            if (!resp.isSuccessful()) throw new IOException("Token error: " + json);
            JsonNode node = objectMapper.readTree(json);
            return new TokenResponse(
                    node.path("access_token").asText(),
                    node.path("refresh_token").asText(null),
                    node.path("expires_in").asLong(3600));
        }
    }

    private JsonNode get(String url, String token) throws IOException {
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + token)
                .build();
        try (Response resp = http.newCall(req).execute()) {
            String json = resp.body().string();
            if (!resp.isSuccessful())
                throw new IOException("Drive API " + resp.code() + ": " + json.substring(0, Math.min(300, json.length())));
            return objectMapper.readTree(json);
        }
    }

    private LocalDateTime parseTime(String iso) {
        if (iso == null || iso.length() < 19) return null;
        try { return LocalDateTime.parse(iso.substring(0, 19), DateTimeFormatter.ISO_LOCAL_DATE_TIME); }
        catch (Exception e) { return null; }
    }

    /** Stay well within Google's 1000 req/100s quota. */
    private void throttle() {
        try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    public record TokenResponse(String accessToken, String refreshToken, long expiresIn) {}
    public record UserInfo(String id, String email, String displayName) {}
}
