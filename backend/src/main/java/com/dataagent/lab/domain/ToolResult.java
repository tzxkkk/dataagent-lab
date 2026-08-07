package com.dataagent.lab.domain;

import java.util.Map;

public record ToolResult(boolean success, String summary, Map<String, Object> data) {
    public static ToolResult success(String summary, Map<String, Object> data) {
        return new ToolResult(true, summary, data);
    }

    public static ToolResult failure(String summary) {
        return new ToolResult(false, summary, Map.of());
    }
}

