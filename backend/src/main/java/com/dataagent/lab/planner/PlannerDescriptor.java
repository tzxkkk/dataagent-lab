package com.dataagent.lab.planner;

public record PlannerDescriptor(
        String mode,
        String promptVersion,
        String model,
        boolean ready
) {
}
