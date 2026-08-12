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
        return "查看白名单数据表的字段和类型";
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
    public String validate(Map<String, Object> arguments) {
        String tableName = tableName(arguments);
        if (!tableName.matches("[a-z0-9_]+")) {
            return "tableName 必须使用数据目录中的技术表名，不能使用中文展示名：" + tableName;
        }

        Integer catalogMatches = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM metadata_catalog WHERE LOWER(table_name) = ?",
                Integer.class,
                tableName
        );
        if (catalogMatches == null || catalogMatches == 0) {
            return "数据目录中不存在该表：" + tableName;
        }
        return null;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String tableName = tableName(arguments);
        String rejection = validate(arguments);
        if (rejection != null) {
            return ToolResult.failure(rejection);
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT UPPER(column_name) AS column_name, UPPER(data_type) AS data_type, is_nullable "
                        + "FROM information_schema.columns "
                        + "WHERE LOWER(table_schema) = LOWER(SCHEMA()) AND LOWER(table_name) = ? "
                        + "ORDER BY ordinal_position",
                tableName
        );
        if (rows.isEmpty()) {
            return ToolResult.failure("找不到数据表：" + tableName);
        }
        return ToolResult.success("已读取 " + tableName + " 的 " + rows.size() + " 个字段",
                Map.of("tableName", tableName, "columns", rows));
    }

    private String tableName(Map<String, Object> arguments) {
        return String.valueOf(arguments.getOrDefault("tableName", ""))
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
