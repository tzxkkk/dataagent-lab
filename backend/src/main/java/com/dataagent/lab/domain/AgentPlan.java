package com.dataagent.lab.domain;

import java.util.List;

public record AgentPlan(
        String rationale,
        List<ToolInvocation> invocations,
        PlannerUsage usage
) {
}
