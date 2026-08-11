package com.dataagent.lab.domain;

import java.util.List;

public record AgentPlan(
        String rationale,
        List<ToolInvocation> invocations,
        PlannerUsage usage,
        ClarificationPrompt clarification,
        List<PlanningToolStep> planningSteps
) {
    public AgentPlan(String rationale, List<ToolInvocation> invocations, PlannerUsage usage) {
        this(rationale, invocations, usage, null, List.of());
    }

    public AgentPlan {
        invocations = invocations == null ? List.of() : List.copyOf(invocations);
        planningSteps = planningSteps == null ? List.of() : List.copyOf(planningSteps);
    }

    public boolean requiresClarification() {
        return clarification != null;
    }
}
