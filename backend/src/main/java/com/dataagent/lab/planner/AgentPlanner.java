package com.dataagent.lab.planner;

import com.dataagent.lab.domain.AgentPlan;

public interface AgentPlanner {
    AgentPlan plan(String input);

    PlannerDescriptor descriptor();
}
