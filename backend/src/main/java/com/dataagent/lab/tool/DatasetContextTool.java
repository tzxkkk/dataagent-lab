package com.dataagent.lab.tool;

import com.dataagent.lab.domain.ToolResult;
import com.dataagent.lab.service.PhysicalTableResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DatasetContextTool implements AgentTool {
    private final JdbcTemplate jdbcTemplate;
    private final PhysicalTableResolver resolver;

    public DatasetContextTool(JdbcTemplate jdbcTemplate, PhysicalTableResolver resolver) {
        this.jdbcTemplate = jdbcTemplate;
        this.resolver = resolver;
    }

    @Override
    public String name() {
        return "get_dataset_context";
    }

    @Override
    public String description() {
        return "Load a logical dataset, maintained field semantics, physical table mappings, and join relationships";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "datasetId", Map.of(
                                "type", "string",
                                "description", "Logical dataset identifier returned by search_datasets"
                        ),
                        "startMonth", Map.of(
                                "type", "string",
                                "pattern", "^\\d{4}-(0[1-9]|1[0-2])$",
                                "description", "Optional inclusive start month in yyyy-MM format"
                        ),
                        "endMonth", Map.of(
                                "type", "string",
                                "pattern", "^\\d{4}-(0[1-9]|1[0-2])$",
                                "description", "Optional inclusive end month in yyyy-MM format"
                        )
                ),
                "required", List.of("datasetId"),
                "additionalProperties", false
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String datasetId = stringValue(arguments.get("datasetId"));
        String startMonth = stringValue(arguments.get("startMonth"));
        String endMonth = stringValue(arguments.get("endMonth"));
        try {
            List<Map<String, Object>> datasets = jdbcTemplate.queryForList(
                    "SELECT dataset_id, display_name, business_domain, description, grain_description, "
                            + "owner_name, trust_status, partition_strategy, routing_column "
                            + "FROM logical_dataset WHERE dataset_id = ?",
                    datasetId
            );
            if (datasets.isEmpty()) {
                return ToolResult.failure("Unknown logical dataset: " + datasetId);
            }

            List<Map<String, Object>> physicalTables = resolver.resolve(datasetId, startMonth, endMonth);
            List<Map<String, Object>> fields = jdbcTemplate.queryForList(
                    "SELECT field_name, display_name, description, value_semantics "
                            + "FROM dataset_field_catalog WHERE dataset_id = ? ORDER BY field_name",
                    datasetId
            );
            List<Map<String, Object>> relations = jdbcTemplate.queryForList(
                    "SELECT source_dataset_id, target_dataset_id, relation_type, join_expression, description "
                            + "FROM dataset_relation WHERE source_dataset_id = ? OR target_dataset_id = ? "
                            + "ORDER BY source_dataset_id, target_dataset_id, relation_type",
                    datasetId,
                    datasetId
            );

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("dataset", datasets.get(0));
            data.put("fields", fields);
            data.put("physicalTables", physicalTables);
            data.put("relations", relations);
            return ToolResult.success("Loaded context for logical dataset " + datasetId, data);
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(exception.getMessage());
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
