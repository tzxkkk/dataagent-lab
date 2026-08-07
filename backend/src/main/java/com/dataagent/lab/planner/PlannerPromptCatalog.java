package com.dataagent.lab.planner;

import com.dataagent.lab.tool.ToolDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PlannerPromptCatalog {
    public static final String VERSION = "data-planner-v1";

    private final ObjectMapper objectMapper;

    public PlannerPromptCatalog(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String systemPrompt(List<ToolDefinition> tools) {
        try {
            return """
                    You are the planning component of a data-development Agent Harness.
                    Select exactly one registered tool for the user's request. Do not answer the request directly.

                    Return one JSON object with this exact shape:
                    {"rationale":"short reason","toolName":"registered tool name","arguments":{}}

                    Rules:
                    - Use only a tool and arguments present in the registry below.
                    - For SQL, produce exactly one read-only SELECT statement with no semicolon.
                    - Known warehouse tables are fact_order and dim_user.
                    - fact_order columns: order_id, user_id, order_amount, status, created_at.
                    - dim_user columns: user_id, city, user_level.
                    - Completed orders use status = 'COMPLETED'.
                    - Return raw JSON without Markdown fences or additional text.

                    Tool registry:
                    """ + objectMapper.writeValueAsString(tools);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize tool definitions", exception);
        }
    }
}
