package com.dataagent.lab.planner;

import com.dataagent.lab.domain.ToolResult;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatiblePlannerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;
    private OpenAiCompatiblePlanner planner;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handleRequest);
        server.start();

        ToolRegistry toolRegistry = new ToolRegistry(List.of(new StubTool()));
        PlannerPromptCatalog promptCatalog = new PlannerPromptCatalog(objectMapper);
        planner = new OpenAiCompatiblePlanner(
                objectMapper,
                toolRegistry,
                promptCatalog,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-key",
                "test-model",
                5,
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
                "{\"rationale\":\"Use the SQL tool\",\"toolName\":\"run_readonly_sql\","
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
        assertThat(plan.usage().model()).isEqualTo("test-model");
        assertThat(plan.usage().inputTokens()).isEqualTo(17);
        assertThat(plan.usage().outputTokens()).isEqualTo(9);

        JsonNode sent = objectMapper.readTree(requestBody.get());
        assertThat(sent.path("model").asText()).isEqualTo("test-model");
        assertThat(sent.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(sent.path("messages").get(0).path("content").asText())
                .contains("run_readonly_sql", "inputSchema");
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
    }

    @Test
    void rejectsUnknownToolName() throws Exception {
        respondWithContent(
                "{\"rationale\":\"Unregistered\",\"toolName\":\"drop_table\",\"arguments\":{}}",
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
                .hasMessage("Planner returned invalid JSON");
    }

    private void respondWithContent(String content, int promptTokens, int completionTokens) throws Exception {
        responseBody.set(objectMapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("content", content))),
                "usage", Map.of("prompt_tokens", promptTokens, "completion_tokens", completionTokens)
        )));
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
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
}
