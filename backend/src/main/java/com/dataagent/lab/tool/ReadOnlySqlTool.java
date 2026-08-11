package com.dataagent.lab.tool;

import com.dataagent.lab.domain.ToolResult;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.BadSqlGrammarException;
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
        return "校验并执行一条带行数限制的只读 SELECT 查询";
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
            return ToolResult.success("查询返回 " + rows.size() + " 行",
                    Map.of("sql", executableSql, "rows", rows));
        } catch (RuntimeException exception) {
            return ToolResult.failure(executionFailureMessage(exception));
        }
    }

    String validate(String sql) {
        if (sql.isBlank()) {
            return "SQL 不能为空";
        }
        if (sql.contains(";")) {
            return "只允许一条 SQL 语句";
        }
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Select)) {
                return "只允许 SELECT 语句";
            }
        } catch (Exception exception) {
            return "SQL 解析失败：请检查语法、关键字和括号是否完整";
        }
        Set<String> referencedTables = new TablesNamesFinder().getTables(statement);
        boolean accessesInternalTable = referencedTables.stream()
                .map(this::simpleTableName)
                .anyMatch(INTERNAL_TABLES::contains);
        if (accessesInternalTable) {
            return "禁止访问 Agent 内部持久化表";
        }
        if (referencedTables.stream().anyMatch(this::isSchemaQualified)) {
            return "禁止跨 Schema 访问数据表";
        }
        for (String tableName : referencedTables) {
            String normalizedTableName = simpleTableName(tableName);
            Integer catalogMatches = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM metadata_catalog WHERE LOWER(table_name) = ?",
                    Integer.class,
                    normalizedTableName
            );
            if (catalogMatches == null || catalogMatches == 0) {
                return "数据表不在业务目录白名单中：" + normalizedTableName;
            }
        }

        String normalized = sql.toLowerCase(Locale.ROOT);
        if (normalized.contains(" for update") || normalized.contains(" into outfile")) {
            return "不允许使用危险的 SELECT 子句";
        }
        if (normalized.contains(" union all ") && groupByCount(normalized) > 1) {
            return "跨分表聚合必须先用 UNION ALL 合并明细，再由外层 SELECT 统一聚合";
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

    private String executionFailureMessage(RuntimeException exception) {
        if (exception instanceof QueryTimeoutException) {
            return "SQL 执行失败：查询超时";
        }
        if (exception instanceof BadSqlGrammarException) {
            return "SQL 执行失败：字段、表名或 SQL 结构不正确";
        }
        if (exception instanceof DataAccessException) {
            return "SQL 执行失败：数据库拒绝了该查询";
        }
        return "SQL 执行失败：数据库查询异常";
    }
}
