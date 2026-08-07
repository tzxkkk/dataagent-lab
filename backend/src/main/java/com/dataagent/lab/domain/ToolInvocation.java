package com.dataagent.lab.domain;

import java.util.Map;

public record ToolInvocation(String toolName, Map<String, Object> arguments) {
}

