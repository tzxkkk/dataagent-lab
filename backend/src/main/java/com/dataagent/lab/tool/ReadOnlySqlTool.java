package com.dataagent.lab.tool;

import com.dataagent.lab.domain.ToolResult;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ReadOnlySqlTool implements AgentTool {
    private static final int MAX_ROWS = 200;

    private final JdbcTemplate jdbcTemplate;

    public ReadOnlySqlTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String name() {
        return "run_readonly_sql";
    }

    @Override
    public String description() {
        return "Validate and execute one read-only SELECT query with a row limit";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("sql", Map.of(
                        "type", "string",
                        "description", "One read-only SELECT statement"
                )),
                "required", List.of("sql"),
                "additionalProperties", false
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String sql = String.valueOf(arguments.getOrDefault("sql", "")).trim();
        String rejection = validate(sql);
        if (rejection != null) {
            return ToolResult.failure(rejection);
        }

        String executableSql = hasLimit(sql) ? sql : sql + " LIMIT " + MAX_ROWS;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(executableSql);
            return ToolResult.success("Query returned " + rows.size() + " rows",
                    Map.of("sql", executableSql, "rows", rows));
        } catch (RuntimeException exception) {
            return ToolResult.failure("SQL execution failed: " + exception.getMessage());
        }
    }

    String validate(String sql) {
        if (sql.isBlank()) {
            return "SQL is required";
        }
        if (sql.contains(";")) {
            return "Only one SQL statement is allowed";
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Select)) {
                return "Only SELECT statements are allowed";
            }
        } catch (Exception exception) {
            return "SQL parsing failed: " + exception.getMessage();
        }

        String normalized = sql.toLowerCase(Locale.ROOT);
        if (normalized.contains(" for update") || normalized.contains(" into outfile")) {
            return "Unsafe SELECT clause is not allowed";
        }
        return null;
    }

    private boolean hasLimit(String sql) {
        return sql.toLowerCase(Locale.ROOT).matches("(?s).*\\blimit\\s+\\d+.*");
    }
}
