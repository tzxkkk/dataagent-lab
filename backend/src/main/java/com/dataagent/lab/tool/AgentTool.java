package com.dataagent.lab.tool;

import com.dataagent.lab.domain.ToolResult;

import java.util.Map;

public interface AgentTool {
    String name();

    String description();

    Map<String, Object> inputSchema();

    ToolResult execute(Map<String, Object> arguments);
}
