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
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

@Service
@Slf4j
@RequiredArgsConstructor
public class OneDriveService {

    private final ObjectMapper objectMapper;

    private static final String AUTH_BASE  = "https://login.microsoftonline.com/common/oauth2/v2.0";
    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";
    private static final String SCOPES     = "Files.Read.All offline_access User.Read";

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60,  TimeUnit.SECONDS)
            .build();

    // -------------------------------------------------------------------------
    // OAuth
    // -------------------------------------------------------------------------

    public String buildAuthUrl(String clientId, String redirectUri, String state) {
        return AUTH_BASE + "/authorize"
                + "?client_id="     + enc(clientId)
                + "&response_type=code"
                + "&redirect_uri="  + enc(redirectUri)
                + "&scope="         + enc(SCOPES)
                + "&state="         + enc(state)
                + "&response_mode=query";
    }

    public TokenResponse exchangeCode(String code, String clientId,
                                       String clientSecret, String redirectUri) throws IOException {
        RequestBody body = new FormBody.Builder()
                .add("client_id",     clientId)
                .add("client_secret", clientSecret)
                .add("code",          code)
                .add("redirect_uri",  redirectUri)
                .add("grant_type",    "authorization_code")
                .build();
        return callToken(body);
    }

    public TokenResponse refreshToken(CloudAccount account) throws IOException {
        RequestBody body = new FormBody.Builder()
                .add("client_id",     account.getClientId())
                .add("client_secret", account.getClientSecret())
                .add("refresh_token", account.getRefreshToken())
                .add("grant_type",    "refresh_token")
                .build();
        return callToken(body);
    }

    // -------------------------------------------------------------------------
    // User info
    // -------------------------------------------------------------------------

    public UserInfo getUserInfo(String accessToken) throws IOException {
        JsonNode root = get(GRAPH_BASE + "/me?$select=id,mail,userPrincipalName,displayName", accessToken);
        String email = root.path("mail").asText(null);
        if (email == null || email.isBlank()) email = root.path("userPrincipalName").asText("unknown");
        return new UserInfo(root.path("id").asText(), email, root.path("displayName").asText(""));
    }

    // -------------------------------------------------------------------------
    // Efficient file sync via delta API
    // -------------------------------------------------------------------------

    public void syncFiles(CloudAccount account,
                          BiConsumer<CloudFileEntry, String> onEntry) throws IOException {
        String token = resolveToken(account);

        // Delta API returns ALL items (files + folders) in one paginated walk
        String url = GRAPH_BASE + "/me/drive/root/delta"
                + "?$select=id,name,size,folder,file,lastModifiedDateTime"
                + ",createdDateTime,parentReference,webUrl,deleted"
                + "&$top=1000";

        while (url != null) {
            JsonNode root = get(url, token);
            for (JsonNode item : root.path("value")) {
                if (item.has("deleted")) continue;   // tombstone — skip
                onEntry.accept(toEntry(account.getId(), item), "FILES");
            }
            url = root.path("@odata.nextLink").asText(null);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private CloudFileEntry toEntry(Long accountId, JsonNode item) {
        CloudFileEntry e = new CloudFileEntry();
        e.setAccountId(accountId);
        e.setProviderId(item.path("id").asText());
        e.setName(item.path("name").asText(""));
        e.setDirectory(item.has("folder"));
        e.setSizeBytes(item.path("size").asLong(0));
        e.setWebUrl(item.path("webUrl").asText(null));

        // parentReference.path  →  "/drive/root:/Folder/Sub"  strip prefix
        String rawParent = item.path("parentReference").path("path").asText("");
        String parentPath = rawParent.replaceFirst("^/?drive/root:?", "");
        if (parentPath.isBlank()) parentPath = "/";
        e.setParentPath(parentPath);
        e.setFullPath(parentPath.endsWith("/")
                ? parentPath + e.getName()
                : parentPath + "/" + e.getName());

        if (!e.isDirectory()) {
            int dot = e.getName().lastIndexOf('.');
            if (dot >= 0 && dot < e.getName().length() - 1)
                e.setExtension(e.getName().substring(dot + 1).toLowerCase());
            if (item.has("file"))
                e.setMimeType(item.path("file").path("mimeType").asText(null));
        }

        e.setLastModified(parseTime(item.path("lastModifiedDateTime").asText(null)));
        e.setCreatedAt(parseTime(item.path("createdDateTime").asText(null)));
        return e;
    }

    /** Refreshes token if within 5 min of expiry. */
    String resolveToken(CloudAccount account) throws IOException {
        if (account.getTokenExpiresAt() == null
                || LocalDateTime.now().isAfter(account.getTokenExpiresAt().minusMinutes(5))) {
            return refreshToken(account).accessToken();
        }
        return account.getAccessToken();
    }

    private TokenResponse callToken(RequestBody body) throws IOException {
        Request req = new Request.Builder()
                .url(AUTH_BASE + "/token")
                .post(body)
                .build();
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
                throw new IOException("Graph API " + resp.code() + ": " + json.substring(0, Math.min(200, json.length())));
            return objectMapper.readTree(json);
        }
    }

    private LocalDateTime parseTime(String iso) {
        if (iso == null || iso.length() < 19) return null;
        try { return LocalDateTime.parse(iso.substring(0, 19), DateTimeFormatter.ISO_LOCAL_DATE_TIME); }
        catch (Exception e) { return null; }
    }

    private String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    public record TokenResponse(String accessToken, String refreshToken, long expiresIn) {}
    public record UserInfo(String id, String email, String displayName) {}
}
