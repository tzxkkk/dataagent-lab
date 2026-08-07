package com.dataagent.lab.tool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolRegistry {
    private final Map<String, AgentTool> tools;

    public ToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> registered = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            if (registered.put(tool.name(), tool) != null) {
                throw new IllegalStateException("Duplicate tool name: " + tool.name());
            }
        }
        this.tools = Map.copyOf(registered);
    }

    public AgentTool require(String name) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }
        return tool;
    }

    public List<ToolDefinition> describe() {
        return tools.values().stream()
                .map(tool -> new ToolDefinition(tool.name(), tool.description(), tool.inputSchema()))
                .toList();
    }
}
