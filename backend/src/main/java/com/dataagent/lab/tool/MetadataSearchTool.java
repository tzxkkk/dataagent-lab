package com.dataagent.lab.tool;

import com.dataagent.lab.domain.ToolResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MetadataSearchTool implements AgentTool {
    private final JdbcTemplate jdbcTemplate;

    public MetadataSearchTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String name() {
        return "search_metadata";
    }

    @Override
    public String description() {
        return "Search table names and descriptions in the data catalog";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of(
                        "type", "string",
                        "description", "Business term or table keyword"
                )),
                "required", List.of("query"),
                "additionalProperties", false
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String query = String.valueOf(arguments.getOrDefault("query", "")).trim();
        String pattern = "%" + query + "%";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT table_name, display_name, description FROM metadata_catalog "
                        + "WHERE table_name LIKE ? OR display_name LIKE ? OR description LIKE ? "
                        + "ORDER BY table_name LIMIT 10",
                pattern, pattern, pattern
        );
        return ToolResult.success("Found " + rows.size() + " catalog entries", Map.of("rows", rows));
    }
}
