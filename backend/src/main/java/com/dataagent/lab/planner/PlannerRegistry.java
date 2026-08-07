package com.dataagent.lab.planner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class PlannerRegistry {
    private final Map<String, AgentPlanner> planners;
    private final String defaultMode;

    public PlannerRegistry(
            List<AgentPlanner> planners,
            @Value("${agent.planner.default-mode:offline}") String defaultMode
    ) {
        Map<String, AgentPlanner> registered = new LinkedHashMap<>();
        for (AgentPlanner planner : planners) {
            String mode = normalize(planner.descriptor().mode());
            if (registered.put(mode, planner) != null) {
                throw new IllegalStateException("Duplicate planner mode: " + mode);
            }
        }
        this.planners = Map.copyOf(registered);
        this.defaultMode = normalize(defaultMode);
        if (!this.planners.containsKey(this.defaultMode)) {
            throw new IllegalStateException("Unknown default planner mode: " + this.defaultMode);
        }
    }

    public AgentPlanner require(String mode) {
        String selectedMode = mode == null || mode.isBlank() ? defaultMode : normalize(mode);
        AgentPlanner planner = planners.get(selectedMode);
        if (planner == null) {
            throw new IllegalArgumentException("Unknown planner mode: " + selectedMode);
        }
        return planner;
    }

    public List<PlannerDescriptor> describe() {
        return planners.values().stream()
                .map(AgentPlanner::descriptor)
                .sorted((left, right) -> left.mode().compareTo(right.mode()))
                .toList();
    }

    private String normalize(String mode) {
        return mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
    }
}
