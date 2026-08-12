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
                throw new IllegalStateException("工具名称重复：" + tool.name());
            }
        }
        this.tools = Map.copyOf(registered);
    }

    public AgentTool require(String name) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未知工具：" + name);
        }
        return tool;
    }

    public AgentTool requireValidated(String name, Map<String, Object> arguments) {
        // 先做通用 JSON Schema 校验，再调用具体工具的业务校验；模型不能绕过任意一层。
        AgentTool tool = require(name);
        Map<String, Object> normalizedArguments = arguments == null ? Map.of() : arguments;
        validateArguments(tool, normalizedArguments);
        String rejection = tool.validate(normalizedArguments);
        if (rejection != null) {
            throw new IllegalArgumentException("工具 " + tool.name() + " 拒绝了参数：" + rejection);
        }
        return tool;
    }

    public List<ToolDefinition> describe() {
        // 同一份工具定义既提供给模型选择，也用于后端运行时校验，避免两边参数约定漂移。
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
                throw new IllegalArgumentException("工具 " + tool.name() + " 收到未知参数：" + unknown);
            }
        }

        if (schema.get("required") instanceof List<?> requiredFields) {
            for (Object field : requiredFields) {
                String name = String.valueOf(field);
                if (!arguments.containsKey(name) || arguments.get(name) == null) {
                    throw new IllegalArgumentException("工具 " + tool.name() + " 缺少必填参数：" + name);
                }
            }
        }

        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (entry.getValue() == null || !(properties.get(entry.getKey()) instanceof Map<?, ?> propertySchema)) {
                continue;
            }
            Object expectedType = propertySchema.get("type");
            if ("string".equals(expectedType) && !(entry.getValue() instanceof String)) {
                throw new IllegalArgumentException("工具 " + tool.name() + " 的参数 " + entry.getKey()
                        + " 必须是字符串");
            }
            Object pattern = propertySchema.get("pattern");
            if (pattern != null && entry.getValue() instanceof String stringValue
                    && !Pattern.matches(String.valueOf(pattern), stringValue)) {
                throw new IllegalArgumentException("工具 " + tool.name() + " 的参数 " + entry.getKey()
                        + " 不符合格式 " + pattern);
            }
        }
    }
}
