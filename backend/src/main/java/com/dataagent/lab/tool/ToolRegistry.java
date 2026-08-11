package com.dataagent.lab.tool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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

    public AgentTool requireValidated(String name, Map<String, Object> arguments) {
        AgentTool tool = require(name);
        Map<String, Object> normalizedArguments = arguments == null ? Map.of() : arguments;
        validateArguments(tool, normalizedArguments);
        String rejection = tool.validate(normalizedArguments);
        if (rejection != null) {
            throw new IllegalArgumentException("Arguments rejected by " + tool.name() + ": " + rejection);
        }
        return tool;
    }

    public List<ToolDefinition> describe() {
        return tools.values().stream()
                .map(tool -> new ToolDefinition(tool.name(), tool.description(), tool.inputSchema()))
                .toList();
    }

    private void validateArguments(AgentTool tool, Map<String, Object> arguments) {
        Map<String, Object> schema = tool.inputSchema();
        Map<?, ?> properties = schema.get("properties") instanceof Map<?, ?> value ? value : Map.of();
        if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
            List<String> unknown = arguments.keySet().stream()
                    .filter(key -> !properties.containsKey(key))
                    .sorted()
                    .toList();
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("Unknown arguments for " + tool.name() + ": " + unknown);
            }
        }

        if (schema.get("required") instanceof List<?> requiredFields) {
            for (Object field : requiredFields) {
                String name = String.valueOf(field);
                if (!arguments.containsKey(name) || arguments.get(name) == null) {
                    throw new IllegalArgumentException("Missing required argument for " + tool.name() + ": " + name);
                }
            }
        }

        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (entry.getValue() == null || !(properties.get(entry.getKey()) instanceof Map<?, ?> propertySchema)) {
                continue;
            }
            Object expectedType = propertySchema.get("type");
            if ("string".equals(expectedType) && !(entry.getValue() instanceof String)) {
                throw new IllegalArgumentException("Argument " + entry.getKey() + " for " + tool.name()
                        + " must be a string");
            }
            Object pattern = propertySchema.get("pattern");
            if (pattern != null && entry.getValue() instanceof String stringValue
                    && !Pattern.matches(String.valueOf(pattern), stringValue)) {
                throw new IllegalArgumentException("Argument " + entry.getKey() + " for " + tool.name()
                        + " does not match " + pattern);
            }
        }
    }
}
