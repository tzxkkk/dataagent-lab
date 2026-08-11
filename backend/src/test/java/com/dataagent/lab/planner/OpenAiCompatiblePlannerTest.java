package com.dataagent.lab.planner;

import com.dataagent.lab.domain.ToolResult;
import com.dataagent.lab.domain.PlanningToolStep;
import com.dataagent.lab.tool.AgentTool;
import com.dataagent.lab.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatiblePlannerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Queue<String> responseBodies = new ConcurrentLinkedQueue<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;
    private OpenAiCompatiblePlanner planner;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handleRequest);
        server.start();

        ToolRegistry toolRegistry = new ToolRegistry(List.of(new StubTool(), new SearchStubTool()));
        PlannerPromptCatalog promptCatalog = new PlannerPromptCatalog(objectMapper);
        planner = new OpenAiCompatiblePlanner(
                objectMapper,
                toolRegistry,
                promptCatalog,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-key",
                "",
                "test-model",
                5,
                4,
                "",
                true
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void parsesToolSelectionPromptVersionAndTokenUsage() throws Exception {
        respondWithContent(
                "{\"action\":\"final\",\"rationale\":\"Use the SQL tool\",\"toolName\":\"run_readonly_sql\","
                        + "\"arguments\":{\"sql\":\"SELECT 1\"}}",
                17,
                9
        );

        var plan = planner.plan("Run a safe query");

        assertThat(plan.rationale()).isEqualTo("Use the SQL tool");
        assertThat(plan.invocations()).singleElement().satisfies(invocation -> {
            assertThat(invocation.toolName()).isEqualTo("run_readonly_sql");
            assertThat(invocation.arguments()).containsEntry("sql", "SELECT 1");
        });
        assertThat(plan.usage().promptVersion()).isEqualTo(PlannerPromptCatalog.VERSION);
        assertThat(plan.usage().model()).isEqualTo("test-model-actual");
        assertThat(plan.usage().inputTokens()).isEqualTo(17);
        assertThat(plan.usage().outputTokens()).isEqualTo(9);

        JsonNode sent = objectMapper.readTree(requestBody.get());
        assertThat(sent.path("model").asText()).isEqualTo("test-model");
        assertThat(sent.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(sent.path("messages").get(0).path("content").asText())
                .contains("run_readonly_sql", "inputSchema", "inspect", "final", "clarify")
                .doesNotContain("Known warehouse tables are fact_order");
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
    }

    @Test
    void executesInspectionToolAndFeedsItsResultBackBeforeFinalPlan() throws Exception {
        respondWithContent(
                "{\"action\":\"inspect\",\"rationale\":\"Find grounded tables\","
                        + "\"toolName\":\"search_metadata\",\"arguments\":{\"query\":\"orders\"}}",
                11,
                4
        );
        respondWithContent(
                "{\"action\":\"final\",\"rationale\":\"Run grounded SQL\","
                        + "\"toolName\":\"run_readonly_sql\",\"arguments\":{\"sql\":\"SELECT 1\"}}",
                23,
                7
        );

        var plan = planner.plan("Count orders");

        assertThat(plan.planningSteps()).singleElement().satisfies(step -> {
            assertThat(step.invocation().toolName()).isEqualTo("search_metadata");
            assertThat(step.result().success()).isTrue();
        });
        assertThat(plan.invocations()).singleElement().satisfies(invocation ->
                assertThat(invocation.toolName()).isEqualTo("run_readonly_sql"));
        assertThat(plan.usage().inputTokens()).isEqualTo(34);
        assertThat(plan.usage().outputTokens()).isEqualTo(11);
        JsonNode sent = objectMapper.readTree(requestBody.get());
        assertThat(sent.path("messages").toString()).contains("Backend inspection tool result", "fact_order");
    }

    @Test
    void returnsModelGeneratedClarificationWithoutSelectingATool() throws Exception {
        respondWithContent(
                "{\"action\":\"clarify\",\"rationale\":\"Metric is ambiguous\","
                        + "\"question\":\"你想统计订单数量还是金额？\",\"options\":["
                        + "{\"label\":\"订单数量\",\"resolvedInput\":\"统计订单数量\"},"
                        + "{\"label\":\"订单金额\",\"resolvedInput\":\"统计订单金额\"}]}",
                13,
                8
        );

        var plan = planner.plan("看看订单情况");

        assertThat(plan.requiresClarification()).isTrue();
        assertThat(plan.invocations()).isEmpty();
        assertThat(plan.clarification().question()).contains("数量", "金额");
        assertThat(plan.clarification().options()).hasSize(2);
    }

    @Test
    void letsTheModelRepairAPrematureSqlInspectionWithoutExecutingIt() throws Exception {
        respondWithContent(
                "{\"action\":\"inspect\",\"rationale\":\"Run SQL now\","
                        + "\"toolName\":\"run_readonly_sql\",\"arguments\":{\"sql\":\"SELECT 1\"}}",
                9,
                3
        );
        respondWithContent(
                "{\"action\":\"final\",\"rationale\":\"Submit SQL for approval\","
                        + "\"toolName\":\"run_readonly_sql\",\"arguments\":{\"sql\":\"SELECT 1\"}}",
                14,
                5
        );

        List<PlanningToolStep> observedSteps = new ArrayList<>();
        var plan = planner.plan("Run a safe query", observedSteps::add);

        assertThat(plan.planningSteps()).singleElement().satisfies(step -> {
            assertThat(step.result().success()).isFalse();
            assertThat(step.result().summary()).contains("action=final");
        });
        assertThat(plan.invocations()).singleElement().satisfies(invocation ->
                assertThat(invocation.toolName()).isEqualTo("run_readonly_sql"));
        assertThat(observedSteps).containsExactlyElementsOf(plan.planningSteps());
    }

    @Test
    void rejectsUnknownToolName() throws Exception {
        respondWithContent(
                "{\"action\":\"final\",\"rationale\":\"Unregistered\",\"toolName\":\"drop_table\",\"arguments\":{}}",
                1,
                1
        );

        assertThatThrownBy(() -> planner.plan("Delete data"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown tool: drop_table");
    }

    @Test
    void rejectsInvalidStructuredOutput() throws Exception {
        respondWithContent("not-json", 1, 1);

        assertThatThrownBy(() -> planner.plan("Run a query"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Model planner returned invalid JSON");
    }

    private void respondWithContent(String content, int promptTokens, int completionTokens) throws Exception {
        responseBodies.add(objectMapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("content", content))),
                "usage", Map.of("prompt_tokens", promptTokens, "completion_tokens", completionTokens),
                "model", "test-model-actual"
        )));
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        String responseBody = responseBodies.poll();
        if (responseBody == null) {
            throw new IOException("No stub response configured");
        }
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static final class StubTool implements AgentTool {
        @Override
        public String name() {
            return "run_readonly_sql";
        }

        @Override
        public String description() {
            return "Run one read-only SQL statement";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of("sql", Map.of("type", "string")),
                    "required", List.of("sql")
            );
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments) {
            return ToolResult.success("ok", Map.of());
        }
    }

    private static final class SearchStubTool implements AgentTool {
        @Override
        public String name() {
            return "search_metadata";
        }

        @Override
        public String description() {
            return "Search catalog metadata";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of("query", Map.of("type", "string")),
                    "required", List.of("query"),
                    "additionalProperties", false
            );
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments) {
            return ToolResult.success("found", Map.of("rows", List.of(Map.of("table_name", "fact_order"))));
        }
    }
}
