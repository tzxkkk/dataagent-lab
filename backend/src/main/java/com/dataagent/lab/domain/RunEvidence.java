package com.dataagent.lab.domain;

import java.util.List;
import java.util.Map;

public record RunEvidence(
        String summary,
        List<String> sourceTables,
        List<String> filters,
        String sql,
        int rowCount,
        Map<String, Object> resultData
) {
}
