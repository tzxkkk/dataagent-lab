package com.dataagent.lab.tool;

import com.dataagent.lab.domain.ToolResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class TableSchemaTool implements AgentTool {
    private final JdbcTemplate jdbcTemplate;

    public TableSchemaTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String name() {
        return "get_table_schema";
    }

    @Override
    public String description() {
        return "Inspect columns and types for an allow-listed warehouse table";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("tableName", Map.of(
                        "type", "string",
                        "description", "Catalog table name"
                )),
                "required", List.of("tableName"),
                "additionalProperties", false
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String tableName = String.valueOf(arguments.getOrDefault("tableName", ""))
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!tableName.matches("[a-z0-9_]+")) {
            return ToolResult.failure("Invalid table name");
        }

        Integer catalogMatches = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM metadata_catalog WHERE LOWER(table_name) = ?",
                Integer.class,
                tableName
        );
        if (catalogMatches == null || catalogMatches == 0) {
            return ToolResult.failure("Table is not in the metadata catalog: " + tableName);
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT UPPER(column_name) AS column_name, UPPER(data_type) AS data_type, is_nullable "
                        + "FROM information_schema.columns "
                        + "WHERE LOWER(table_schema) = LOWER(SCHEMA()) AND LOWER(table_name) = ? "
                        + "ORDER BY ordinal_position",
                tableName
        );
        if (rows.isEmpty()) {
            return ToolResult.failure("Table not found: " + tableName);
        }
        return ToolResult.success("Loaded " + rows.size() + " columns for " + tableName,
                Map.of("tableName", tableName, "columns", rows));
    }
}
