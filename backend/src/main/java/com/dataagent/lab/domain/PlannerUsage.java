package com.dataagent.lab.domain;

public record PlannerUsage(
        String promptVersion,
        String model,
        int inputTokens,
        int outputTokens
) {
}
