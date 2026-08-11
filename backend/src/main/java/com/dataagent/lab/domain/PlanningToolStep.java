package com.dataagent.lab.domain;

public record PlanningToolStep(
        ToolInvocation invocation,
        ToolResult result
) {
}
