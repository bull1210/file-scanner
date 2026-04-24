package com.filescanner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.filescanner.dto.QueryResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiQueryService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Value("${anthropic.api.key}")
    private String apiKey;

    @Value("${anthropic.model}")
    private String model;

    @Value("${anthropic.api.url}")
    private String apiUrl;

    @Value("${anthropic.max.tokens:1024}")
    private int maxTokens;

    private static final Pattern UNSAFE_SQL = Pattern.compile(
            "(?i)\\b(INSERT|UPDATE|DELETE|DROP|TRUNCATE|ALTER|CREATE|REPLACE|MERGE|EXEC|EXECUTE|GRANT|REVOKE)\\b"
    );

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30,    TimeUnit.SECONDS)
            .build();

    private static final String SYSTEM_PROMPT = """
            You are a SQL generation assistant for an H2 database. Your ONLY job is to
            produce a single, valid, read-only H2 SQL SELECT statement that answers the
            user's question. Never produce more than one statement. Never use semicolons.
            Never explain. Output ONLY the raw SQL — no markdown, no code fences, no prose.

            The database has these two tables:

            TABLE file_scan (
              id               BIGINT PRIMARY KEY,
              root_path        VARCHAR(2048),
              started_at       TIMESTAMP,
              finished_at      TIMESTAMP,
              total_files      BIGINT,
              total_size_bytes BIGINT
            )

            TABLE file_entry (
              id             BIGINT PRIMARY KEY,
              scan_id        BIGINT,           -- FK to file_scan.id
              name           VARCHAR(512),     -- file/folder name only
              full_path      VARCHAR(4096),    -- absolute path
              extension      VARCHAR(32),      -- lowercase, no dot (e.g. 'java', 'pdf'); NULL for dirs
              size_bytes     BIGINT,           -- 0 for directories
              created_at     TIMESTAMP,
              last_modified  TIMESTAMP,
              is_directory   BOOLEAN,
              depth          INT,              -- 0 = root
              parent_path    VARCHAR(4096)
            )

            H2-specific notes:
            - Use DATEDIFF('DAY', last_modified, NOW()) to compute file age in days.
            - Use LOWER(extension) for case-insensitive extension matching.
            - LIMIT N is supported; use it to avoid returning huge result sets (default LIMIT 100).
            - Always filter by scan_id unless the user explicitly asks about all scans.
            - If the user does not specify a scan, use the most recent one:
              (SELECT MAX(id) FROM file_scan)
            - Sizes are stored in bytes. 1 KB = 1024 bytes, 1 MB = 1048576 bytes.
            - Column is_directory is a BOOLEAN; use is_directory = false for files only.
            - Do NOT use backtick identifiers. H2 uses standard double-quotes if quoting is needed.

            Return ONLY the SQL. Nothing else.
            """;

    public QueryResult query(String naturalLanguagePrompt) throws IOException {
        String sql = callClaude(naturalLanguagePrompt).strip();

        if (sql.startsWith("```")) {
            sql = sql.replaceAll("```[a-z]*\\n?", "").strip();
        }
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).strip();
        }

        if (UNSAFE_SQL.matcher(sql).find()) {
            throw new SecurityException(
                    "Generated SQL contains disallowed keywords: " + sql);
        }
        if (!sql.toUpperCase().startsWith("SELECT")) {
            throw new IllegalStateException(
                    "Claude did not return a SELECT statement: " + sql);
        }

        log.info("Executing AI-generated SQL: {}", sql);
        List<Map<String, Object>> rows = jdbc.queryForList(sql);

        QueryResult result = new QueryResult();
        result.setOriginalPrompt(naturalLanguagePrompt);
        result.setGeneratedSql(sql);
        result.setRows(rows);
        result.setColumns(rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet()));
        return result;
    }

    private String callClaude(String userPrompt) throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("system", SYSTEM_PROMPT);

        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        ArrayNode content = userMsg.putArray("content");
        ObjectNode textBlock = content.addObject();
        textBlock.put("type", "text");
        textBlock.put("text", userPrompt);

        RequestBody requestBody = RequestBody.create(
                objectMapper.writeValueAsString(body),
                MediaType.parse("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(apiUrl)
                .addHeader("x-api-key",         apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type",      "application/json")
                .post(requestBody)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Anthropic API error " + response.code() + ": " + responseBody);
            }
            JsonNode responseJson = objectMapper.readTree(responseBody);
            return responseJson.path("content").get(0).path("text").asText();
        }
    }
}
