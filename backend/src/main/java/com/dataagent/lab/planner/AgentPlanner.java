package com.dataagent.lab.planner;

import com.dataagent.lab.domain.AgentPlan;
import com.dataagent.lab.domain.PlanningToolStep;

import java.util.function.Consumer;

public interface AgentPlanner {
    AgentPlan plan(String input);

    default AgentPlan plan(String input, Consumer<PlanningToolStep> planningObserver) {
        return plan(input);
    }

    PlannerDescriptor descriptor();

    default boolean handlesClarification() {
        return false;
    }
}
