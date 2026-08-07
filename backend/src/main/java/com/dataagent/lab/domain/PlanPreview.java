package com.dataagent.lab.domain;

import java.util.List;
import java.util.Map;

public record PlanPreview(
        String interpretation,
        String toolName,
        Map<String, Object> arguments,
        List<String> sourceTables,
        List<String> filters,
        String sql,
        List<String> assumptions,
        String riskLevel,
        int rowLimit
) {
}
