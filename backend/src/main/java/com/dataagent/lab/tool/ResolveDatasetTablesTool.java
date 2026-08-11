package com.dataagent.lab.tool;

import com.dataagent.lab.domain.ToolResult;
import com.dataagent.lab.service.PhysicalTableResolver;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ResolveDatasetTablesTool implements AgentTool {
    private final PhysicalTableResolver resolver;

    public ResolveDatasetTablesTool(PhysicalTableResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public String name() {
        return "resolve_dataset_tables";
    }

    @Override
    public String description() {
        return "根据逻辑数据集和可选月份范围解析已维护的物理表映射";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "datasetId", Map.of(
                                "type", "string",
                                "description", "Logical dataset identifier"
                        ),
                        "startMonth", Map.of(
                                "type", "string",
                                "pattern", "^\\d{4}-(0[1-9]|1[0-2])$",
                                "description", "Inclusive start month in yyyy-MM format"
                        ),
                        "endMonth", Map.of(
                                "type", "string",
                                "pattern", "^\\d{4}-(0[1-9]|1[0-2])$",
                                "description", "Inclusive end month in yyyy-MM format"
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
            List<Map<String, Object>> physicalTables = resolver.resolve(datasetId, startMonth, endMonth);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("datasetId", datasetId);
            data.put("startMonth", startMonth);
            data.put("endMonth", endMonth);
            data.put("physicalTables", physicalTables);
            return ToolResult.success("解析到 " + physicalTables.size() + " 张物理表", data);
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(exception.getMessage());
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
