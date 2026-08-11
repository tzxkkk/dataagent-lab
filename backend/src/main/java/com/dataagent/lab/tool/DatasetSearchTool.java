package com.dataagent.lab.tool;

import com.dataagent.lab.domain.ToolResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DatasetSearchTool implements AgentTool {
    private final JdbcTemplate jdbcTemplate;

    public DatasetSearchTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String name() {
        return "search_datasets";
    }

    @Override
    public String description() {
        return "按业务术语、领域、粒度或负责人搜索逻辑数据集";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of(
                        "type", "string",
                        "description", "Business term, domain, dataset, grain, or owner keyword"
                )),
                "required", List.of("query"),
                "additionalProperties", false
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String query = String.valueOf(arguments.getOrDefault("query", "")).trim();
        if (query.isBlank()) {
            return ToolResult.failure("搜索词不能为空");
        }

        String pattern = "%" + query.toLowerCase() + "%";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT d.dataset_id, d.display_name, d.business_domain, d.description, "
                        + "d.grain_description, d.owner_name, d.trust_status, "
                        + "d.partition_strategy, d.routing_column, "
                        + "COUNT(p.physical_table_name) AS physical_table_count "
                        + "FROM logical_dataset d "
                        + "LEFT JOIN dataset_physical_table p ON p.dataset_id = d.dataset_id "
                        + "WHERE LOWER(d.dataset_id) LIKE ? OR LOWER(d.display_name) LIKE ? "
                        + "OR LOWER(d.business_domain) LIKE ? OR LOWER(d.description) LIKE ? "
                        + "OR LOWER(d.grain_description) LIKE ? OR LOWER(d.owner_name) LIKE ? "
                        + "GROUP BY d.dataset_id, d.display_name, d.business_domain, d.description, "
                        + "d.grain_description, d.owner_name, d.trust_status, "
                        + "d.partition_strategy, d.routing_column "
                        + "ORDER BY d.trust_status, d.dataset_id LIMIT 10",
                pattern, pattern, pattern, pattern, pattern, pattern
        );
        return ToolResult.success("找到 " + rows.size() + " 个逻辑数据集", Map.of("rows", rows));
    }
}
