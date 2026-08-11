package com.dataagent.lab.tool;

import com.dataagent.lab.domain.ToolResult;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ReadOnlySqlTool implements AgentTool {
    private static final int MAX_ROWS = 200;
    private static final Set<String> INTERNAL_TABLES = Set.of(
            "agent_run",
            "agent_trace_event",
            "agent_run_feedback",
            "agent_pending_plan"
    );

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
    public String validate(Map<String, Object> arguments) {
        String sql = String.valueOf(arguments.getOrDefault("sql", "")).trim();
        return validate(sql);
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String sql = String.valueOf(arguments.getOrDefault("sql", "")).trim();
        String rejection = validate(arguments);
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
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Select)) {
                return "Only SELECT statements are allowed";
            }
        } catch (Exception exception) {
            return "SQL parsing failed: " + exception.getMessage();
        }
        Set<String> referencedTables = new TablesNamesFinder().getTables(statement);
        boolean accessesInternalTable = referencedTables.stream()
                .map(this::simpleTableName)
                .anyMatch(INTERNAL_TABLES::contains);
        if (accessesInternalTable) {
            return "Access to internal Agent persistence tables is not allowed";
        }
        if (referencedTables.stream().anyMatch(this::isSchemaQualified)) {
            return "Cross-schema table access is not allowed";
        }
        for (String tableName : referencedTables) {
            String normalizedTableName = simpleTableName(tableName);
            Integer catalogMatches = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM metadata_catalog WHERE LOWER(table_name) = ?",
                    Integer.class,
                    normalizedTableName
            );
            if (catalogMatches == null || catalogMatches == 0) {
                return "Table is not in the business metadata catalog: " + normalizedTableName;
            }
        }

        String normalized = sql.toLowerCase(Locale.ROOT);
        if (normalized.contains(" for update") || normalized.contains(" into outfile")) {
            return "Unsafe SELECT clause is not allowed";
        }
        if (normalized.contains(" union all ") && groupByCount(normalized) > 1) {
            return "Cross-partition aggregation must UNION ALL row-level data first "
                    + "and aggregate once in an outer SELECT";
        }
        return null;
    }

    private String simpleTableName(String tableName) {
        String normalized = tableName.replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
        int separator = normalized.lastIndexOf('.');
        return separator < 0 ? normalized : normalized.substring(separator + 1);
    }

    private boolean isSchemaQualified(String tableName) {
        return tableName.replace("`", "").replace("\"", "").contains(".");
    }

    private int groupByCount(String normalizedSql) {
        int count = 0;
        var matcher = java.util.regex.Pattern.compile("\\bgroup\\s+by\\b").matcher(normalizedSql);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private boolean hasLimit(String sql) {
        return sql.toLowerCase(Locale.ROOT).matches("(?s).*\\blimit\\s+\\d+.*");
    }
}
