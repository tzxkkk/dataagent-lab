package com.dataagent.lab.planner;

import com.dataagent.lab.domain.AgentPlan;
import com.dataagent.lab.domain.ClarificationOption;
import com.dataagent.lab.domain.ClarificationPrompt;
import com.dataagent.lab.domain.PlannerUsage;
import com.dataagent.lab.domain.PlanningToolStep;
import com.dataagent.lab.domain.ToolInvocation;
import com.dataagent.lab.domain.ToolResult;
import com.dataagent.lab.tool.AgentTool;
import com.dataagent.lab.tool.ToolRegistry;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

@Component
public class OpenAiCompatiblePlanner implements AgentPlanner {
    private static final Set<String> INSPECTION_TOOLS = Set.of(
            "search_datasets",
            "get_dataset_context",
            "search_metadata",
            "get_table_schema",
            "resolve_dataset_tables"
    );

    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final PlannerPromptCatalog promptCatalog;
    private final HttpClient httpClient;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final int maxPlanningSteps;
    private final boolean enabled;

    public OpenAiCompatiblePlanner(
            ObjectMapper objectMapper,
            ToolRegistry toolRegistry,
            PlannerPromptCatalog promptCatalog,
            @Value("${agent.openai.base-url:https://api.deepseek.com/v1}") String baseUrl,
            @Value("${agent.openai.api-key:}") String apiKey,
            @Value("${agent.openai.api-key-file:}") String apiKeyFile,
            @Value("${agent.openai.model:deepseek-chat}") String model,
            @Value("${agent.openai.timeout-seconds:45}") int timeoutSeconds,
            @Value("${agent.openai.max-planning-steps:12}") int maxPlanningSteps,
            @Value("${agent.openai.proxy-url:}") String proxyUrl,
            @Value("${agent.openai.enabled:true}") boolean enabled
    ) {
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.promptCatalog = promptCatalog;
        this.endpoint = chatCompletionsEndpoint(baseUrl);
        this.apiKey = resolveApiKey(apiKey, apiKeyFile);
        this.model = model == null ? "" : model.trim();
        this.timeout = Duration.ofSeconds(Math.max(timeoutSeconds, 1));
        this.maxPlanningSteps = Math.max(maxPlanningSteps, 1);
        this.enabled = enabled;
        this.httpClient = httpClient(this.timeout, proxyUrl);
    }

    @Override
    public AgentPlan plan(String input) {
        return plan(input, step -> {
        });
    }

    @Override
    public AgentPlan plan(String input, Consumer<PlanningToolStep> planningObserver) {
        if (!enabled) {
            throw new IllegalStateException("模型规划器已禁用");
        }
        if (apiKey.isBlank()) {
            throw new IllegalStateException("尚未配置模型规划器 API Key");
        }
        if (model.isBlank()) {
            throw new IllegalStateException("必须配置模型规划器使用的模型");
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", promptCatalog.systemPrompt(toolRegistry.describe())));
        messages.add(Map.of("role", "user", "content", input));

        List<PlanningToolStep> planningSteps = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;
        String actualModel = model;

        for (int attempt = 0; attempt <= maxPlanningSteps; attempt++) {
            ModelTurn turn = call(messages);
            inputTokens += turn.inputTokens();
            outputTokens += turn.outputTokens();
            actualModel = turn.model();
            PlannerOutput output = parseOutput(turn.content());
            String action = normalize(output.action());
            String rationale = rationale(output.rationale());

            if (("clarify".equals(action) || "final".equals(action))
                    && !usesSimplifiedChinese(action, output, rationale)) {
                if (attempt == maxPlanningSteps) {
                    throw new IllegalStateException("模型面向用户的内容没有使用简体中文");
                }
                messages.add(Map.of("role", "assistant", "content", turn.content()));
                messages.add(Map.of(
                        "role", "user",
                        "content", "请保持相同 action 和技术参数不变，将 rationale、question、option label、"
                                + "resolvedInput 等所有面向用户的自然语言字段改写为简体中文，然后只返回 JSON。"
                ));
                continue;
            }

            if ("clarify".equals(action)) {
                ClarificationPrompt clarification = clarification(output);
                return new AgentPlan(
                        rationale,
                        List.of(),
                        usage(actualModel, inputTokens, outputTokens),
                        clarification,
                        planningSteps
                );
            }

            if (output.toolName() == null || output.toolName().isBlank()) {
                throw new IllegalStateException("模型规划器没有选择工具");
            }
            AgentTool registeredTool = toolRegistry.require(output.toolName());
            Map<String, Object> arguments = output.arguments() == null ? Map.of() : output.arguments();
            ToolInvocation invocation = new ToolInvocation(registeredTool.name(), arguments);

            if ("final".equals(action)) {
                try {
                    toolRegistry.requireValidated(invocation.toolName(), invocation.arguments());
                    return new AgentPlan(
                            rationale,
                            List.of(invocation),
                            usage(actualModel, inputTokens, outputTokens),
                            null,
                            planningSteps
                    );
                } catch (IllegalArgumentException exception) {
                    if (attempt == maxPlanningSteps) {
                        throw exception;
                    }
                    ToolResult rejection = ToolResult.failure("最终工具在预览前被拒绝："
                            + exception.getMessage());
                    recordStep(planningSteps, planningObserver, new PlanningToolStep(invocation, rejection));
                    appendToolResult(messages, turn.content(), invocation, rejection);
                    continue;
                }
            }

            if (!"inspect".equals(action)) {
                throw new IllegalStateException("模型规划器返回了不支持的操作：" + output.action());
            }
            if (attempt == maxPlanningSteps) {
                throw new IllegalStateException("模型规划器超过了 " + maxPlanningSteps + " 个检查步骤");
            }

            if (!INSPECTION_TOOLS.contains(invocation.toolName())) {
                ToolResult rejection = ToolResult.failure(
                        "该工具不能在规划阶段运行：" + invocation.toolName()
                                + "。请使用 action=final 提交，以便后端请求用户确认。"
                );
                recordStep(planningSteps, planningObserver, new PlanningToolStep(invocation, rejection));
                appendToolResult(messages, turn.content(), invocation, rejection);
                continue;
            }

            ToolResult result;
            try {
                AgentTool tool = toolRegistry.requireValidated(invocation.toolName(), invocation.arguments());
                result = tool.execute(invocation.arguments());
            } catch (IllegalArgumentException exception) {
                result = ToolResult.failure("工具参数被拒绝：" + exception.getMessage());
            }
            recordStep(planningSteps, planningObserver, new PlanningToolStep(invocation, result));
            appendToolResult(messages, turn.content(), invocation, result);
        }

        throw new IllegalStateException("模型规划器没有生成最终操作");
    }

    @Override
    public PlannerDescriptor descriptor() {
        return new PlannerDescriptor("openai", PlannerPromptCatalog.VERSION, model,
                enabled && !apiKey.isBlank() && !model.isBlank());
    }

    @Override
    public boolean handlesClarification() {
        return true;
    }

    private ModelTurn call(List<Map<String, Object>> messages) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("temperature", 0);
        payload.put("response_format", Map.of("type", "json_object"));
        payload.put("messages", messages);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化模型规划请求", exception);
        }

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("模型规划请求失败，HTTP 状态码：" + response.statusCode());
            }
            ChatResponse chatResponse = objectMapper.readValue(response.body(), ChatResponse.class);
            if (chatResponse.choices() == null || chatResponse.choices().isEmpty()
                    || chatResponse.choices().get(0).message() == null
                    || chatResponse.choices().get(0).message().content() == null) {
                throw new IllegalStateException("模型规划响应中没有消息内容");
            }
            Usage usage = chatResponse.usage();
            return new ModelTurn(
                    chatResponse.choices().get(0).message().content(),
                    usage == null || usage.promptTokens() == null ? 0 : usage.promptTokens(),
                    usage == null || usage.completionTokens() == null ? 0 : usage.completionTokens(),
                    chatResponse.model() == null || chatResponse.model().isBlank() ? model : chatResponse.model()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型规划请求被中断", exception);
        } catch (IOException exception) {
            String detail = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            throw new IllegalStateException("模型规划请求失败：" + detail, exception);
        }
    }

    private PlannerOutput parseOutput(String content) {
        try {
            return objectMapper.readValue(content, PlannerOutput.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("模型规划器返回了无效 JSON", exception);
        }
    }

    private ClarificationPrompt clarification(PlannerOutput output) {
        if (output.question() == null || output.question().isBlank()) {
            throw new IllegalStateException("模型必须提供澄清问题");
        }
        List<ClarificationOption> options = output.options() == null ? List.of() : output.options();
        if (options.size() < 2 || options.size() > 5) {
            throw new IllegalStateException("模型澄清必须提供 2 到 5 个选项");
        }
        for (ClarificationOption option : options) {
            if (option == null || option.label() == null || option.label().isBlank()
                    || option.resolvedInput() == null || option.resolvedInput().isBlank()) {
                throw new IllegalStateException("模型澄清选项必须包含 label 和 resolvedInput");
            }
        }
        return new ClarificationPrompt(output.question().trim(), List.copyOf(options));
    }

    private void appendToolResult(
            List<Map<String, Object>> messages,
            String assistantContent,
            ToolInvocation invocation,
            ToolResult result
    ) {
        messages.add(Map.of("role", "assistant", "content", assistantContent));
        try {
            String toolResult = objectMapper.writeValueAsString(Map.of(
                    "toolName", invocation.toolName(),
                    "success", result.success(),
                    "summary", result.summary(),
                    "data", result.data()
            ));
            messages.add(Map.of(
                    "role", "user",
                    "content", "Backend inspection tool result. Continue planning from this evidence:\n" + toolResult
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化规划工具结果", exception);
        }
    }

    private void recordStep(
            List<PlanningToolStep> planningSteps,
            Consumer<PlanningToolStep> planningObserver,
            PlanningToolStep step
    ) {
        planningSteps.add(step);
        planningObserver.accept(step);
    }

    private PlannerUsage usage(String actualModel, int inputTokens, int outputTokens) {
        return new PlannerUsage(PlannerPromptCatalog.VERSION, actualModel, inputTokens, outputTokens);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String rationale(String value) {
        return value == null || value.isBlank() ? "模型已选择有数据依据的后端操作" : value.trim();
    }

    private boolean usesSimplifiedChinese(String action, PlannerOutput output, String rationale) {
        if (!containsHanCharacter(rationale)) {
            return false;
        }
        if (!"clarify".equals(action)) {
            return true;
        }
        if (!containsHanCharacter(output.question())) {
            return false;
        }
        List<ClarificationOption> options = output.options() == null ? List.of() : output.options();
        return !options.isEmpty() && options.stream().allMatch(option -> option != null
                && containsHanCharacter(option.label())
                && containsHanCharacter(option.resolvedInput()));
    }

    private boolean containsHanCharacter(String value) {
        return value != null && value.codePoints()
                .anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private String resolveApiKey(String inlineApiKey, String apiKeyFile) {
        String inline = inlineApiKey == null ? "" : inlineApiKey.trim();
        if (!inline.isBlank()) {
            return inline;
        }
        if (apiKeyFile == null || apiKeyFile.isBlank()) {
            return "";
        }
        Path path = Path.of(apiKeyFile.trim()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            return "";
        }
        try {
            return Files.readString(path).trim();
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取模型规划器 API Key 文件", exception);
        }
    }

    private HttpClient httpClient(Duration requestTimeout, String proxyUrl) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(requestTimeout);
        if (proxyUrl != null && !proxyUrl.isBlank()) {
            URI proxy = URI.create(proxyUrl.trim());
            int port = proxy.getPort() > 0 ? proxy.getPort() : 80;
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), port)));
        }
        return builder.build();
    }

    private URI chatCompletionsEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("必须配置模型规划器 Base URL");
        }
        String normalized = baseUrl.trim().replaceAll("/+$", "");
        if (!normalized.endsWith("/chat/completions")) {
            normalized += "/chat/completions";
        }
        return URI.create(normalized);
    }

    private record ChatResponse(List<Choice> choices, Usage usage, String model) {
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

    private record PlannerOutput(
            String action,
            String rationale,
            String toolName,
            Map<String, Object> arguments,
            String question,
            List<ClarificationOption> options
    ) {
    }

    private record ModelTurn(String content, int inputTokens, int outputTokens, String model) {
    }
}
