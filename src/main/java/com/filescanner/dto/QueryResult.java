package com.filescanner.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class QueryResult {

    private String originalPrompt;
    private String generatedSql;
    private List<String> columns;
    private List<Map<String, Object>> rows;

    public boolean isEmpty() {
        return rows == null || rows.isEmpty();
    }

    public int getRowCount() {
        return rows == null ? 0 : rows.size();
    }
}
