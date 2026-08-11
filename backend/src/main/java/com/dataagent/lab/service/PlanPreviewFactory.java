package com.dataagent.lab.service;

import com.dataagent.lab.domain.AgentPlan;
import com.dataagent.lab.domain.PlanPreview;
import com.dataagent.lab.domain.ToolInvocation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PlanPreviewFactory {
    private static final Pattern TABLE_PATTERN = Pattern.compile("(?i)\\b(?:from|join)\\s+([a-zA-Z0-9_]+)");
    private static final Pattern WHERE_PATTERN = Pattern.compile(
            "(?is)\\bwhere\\s+(.+?)(?:\\bgroup\\s+by\\b|\\border\\s+by\\b|\\blimit\\b|$)"
    );

    public PlanPreview create(AgentPlan plan) {
        ToolInvocation invocation = plan.invocations().get(0);
        String toolName = invocation.toolName();
        Map<String, Object> arguments = invocation.arguments();
        String sql = toolName.equals("run_readonly_sql") ? String.valueOf(arguments.get("sql")) : null;
        List<String> sourceTables = sourceTables(toolName, arguments, sql);
        List<String> filters = filters(sql);
        List<String> assumptions = assumptions(toolName, sql);
        int rowLimit = toolName.equals("run_readonly_sql")
                ? 200
                : toolName.equals("search_metadata") || toolName.equals("search_datasets") ? 10 : 0;

        return new PlanPreview(
                plan.rationale(),
                toolName,
                arguments,
                sourceTables,
                filters,
                sql,
                assumptions,
                "LOW_READ_ONLY",
                rowLimit
        );
    }

    private List<String> sourceTables(String toolName, Map<String, Object> arguments, String sql) {
        if (toolName.equals("get_table_schema")) {
            return List.of(String.valueOf(arguments.get("tableName")));
        }
        if (toolName.equals("search_metadata")) {
            return List.of("metadata_catalog");
        }
        if (toolName.equals("search_datasets")) {
            return List.of("logical_dataset", "dataset_physical_table");
        }
        if (toolName.equals("resolve_dataset_tables")) {
            return List.of("dataset_physical_table");
        }
        if (toolName.equals("get_dataset_context")) {
            return List.of("logical_dataset", "dataset_field_catalog", "dataset_physical_table", "dataset_relation");
        }
        if (sql == null) {
            return List.of();
        }
        Set<String> tables = new LinkedHashSet<>();
        Matcher matcher = TABLE_PATTERN.matcher(sql);
        while (matcher.find()) {
            tables.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return List.copyOf(tables);
    }

    private List<String> filters(String sql) {
        if (sql == null) {
            return List.of();
        }
        Matcher matcher = WHERE_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return List.of("无筛选条件");
        }
        return Pattern.compile("(?i)\\s+and\\s+")
                .splitAsStream(matcher.group(1).trim())
                .map(String::trim)
                .toList();
    }

    private List<String> assumptions(String toolName, String sql) {
        List<String> assumptions = new ArrayList<>();
        if (sql != null && sql.contains("status = 'COMPLETED'")) {
            assumptions.add("将 COMPLETED 作为已完成订单口径");
        }
        if (sql != null && sql.contains("order_amount")) {
            assumptions.add("金额指标使用目录和表结构中确认的 order_amount 字段");
        }
        if (toolName.equals("run_readonly_sql")) {
            assumptions.add("仅执行单条只读 SELECT，结果最多返回 200 行");
        } else if (toolName.equals("resolve_dataset_tables")) {
            assumptions.add("仅按已维护映射解析物理表，不直接拼接或执行跨分表 SQL");
        } else if (toolName.equals("search_datasets")) {
            assumptions.add("仅搜索逻辑数据目录，不直接扫描物理业务表");
        } else {
            assumptions.add("仅查询元数据，不读取或修改业务数据");
        }
        return List.copyOf(assumptions);
    }
}
