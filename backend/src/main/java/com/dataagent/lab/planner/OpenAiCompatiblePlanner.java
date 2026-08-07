package com.dataagent.lab.planner;

import com.dataagent.lab.domain.AgentPlan;
import com.dataagent.lab.domain.PlannerUsage;
import com.dataagent.lab.domain.ToolInvocation;
import com.dataagent.lab.tool.ToolRegistry;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiCompatiblePlanner implements AgentPlanner {
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final PlannerPromptCatalog promptCatalog;
    private final HttpClient httpClient;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final boolean enabled;

    public OpenAiCompatiblePlanner(
            ObjectMapper objectMapper,
            ToolRegistry toolRegistry,
            PlannerPromptCatalog promptCatalog,
            @Value("${agent.openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${agent.openai.api-key:}") String apiKey,
            @Value("${agent.openai.model:gpt-4.1-mini}") String model,
            @Value("${agent.openai.timeout-seconds:30}") int timeoutSeconds,
            @Value("${agent.openai.enabled:false}") boolean enabled
    ) {
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.promptCatalog = promptCatalog;
        this.endpoint = chatCompletionsEndpoint(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.timeout = Duration.ofSeconds(Math.max(timeoutSeconds, 1));
        this.enabled = enabled;
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public AgentPlan plan(String input) {
        if (!enabled) {
            throw new IllegalStateException("OpenAI-compatible planner is not enabled");
        }
        if (model.isBlank()) {
            throw new IllegalStateException("OpenAI-compatible planner model is required");
        }

        Map<String, Object> payload = Map.of(
                "model", model,
                "temperature", 0,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", promptCatalog.systemPrompt(toolRegistry.describe())),
                        Map.of("role", "user", "content", input)
                )
        );

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json");
        if (!apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        try {
            HttpRequest request = requestBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Planner request failed with HTTP " + response.statusCode());
            }
            return parse(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Planner request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Planner request failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public PlannerDescriptor descriptor() {
        return new PlannerDescriptor("openai", PlannerPromptCatalog.VERSION, model, enabled);
    }

    private AgentPlan parse(String responseBody) {
        try {
            ChatResponse response = objectMapper.readValue(responseBody, ChatResponse.class);
            if (response.choices() == null || response.choices().isEmpty()
                    || response.choices().get(0).message() == null
                    || response.choices().get(0).message().content() == null) {
                throw new IllegalStateException("Planner response did not contain message content");
            }

            PlannerOutput output = objectMapper.readValue(
                    response.choices().get(0).message().content(),
                    PlannerOutput.class
            );
            if (output.toolName() == null || output.toolName().isBlank()) {
                throw new IllegalStateException("Planner response did not select a tool");
            }
            toolRegistry.require(output.toolName());

            Usage usage = response.usage();
            int inputTokens = usage == null || usage.promptTokens() == null ? 0 : usage.promptTokens();
            int outputTokens = usage == null || usage.completionTokens() == null ? 0 : usage.completionTokens();
            String rationale = output.rationale() == null || output.rationale().isBlank()
                    ? "Model selected a registered tool"
                    : output.rationale();
            Map<String, Object> arguments = output.arguments() == null ? Map.of() : output.arguments();

            return new AgentPlan(
                    rationale,
                    List.of(new ToolInvocation(output.toolName(), arguments)),
                    new PlannerUsage(PlannerPromptCatalog.VERSION, model, inputTokens, outputTokens)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Planner returned invalid JSON", exception);
        }
    }

    private URI chatCompletionsEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("OpenAI-compatible base URL is required");
        }
        String normalized = baseUrl.trim().replaceAll("/+$", "");
        if (!normalized.endsWith("/chat/completions")) {
            normalized += "/chat/completions";
        }
        return URI.create(normalized);
    }

    private record ChatResponse(List<Choice> choices, Usage usage) {
    }

    private record Choice(Message message) {
    }

    private record Message(String content) {
    }

    private record Usage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens
    ) {
    }

    private record PlannerOutput(String rationale, String toolName, Map<String, Object> arguments) {
    }
}
