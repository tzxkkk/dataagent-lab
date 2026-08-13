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

        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new StubTool(),
                new SearchStubTool(),
                new SchemaStubTool()
        ));
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
                "{\"action\":\"final\",\"rationale\":\"使用只读 SQL 工具\",\"toolName\":\"run_readonly_sql\","
                        + "\"arguments\":{\"sql\":\"SELECT 1\"}}",
                17,
                9
        );

        var plan = planner.plan("Run a safe query");

        assertThat(plan.rationale()).isEqualTo("使用只读 SQL 工具");
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
                "{\"action\":\"final\",\"rationale\":\"执行有数据依据的 SQL\","
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
        assertThat(sent.path("messages").toString()).contains("后端检查工具结果", "fact_order");
    }

    @Test
    void returnsModelGeneratedClarificationWithoutSelectingATool() throws Exception {
        respondWithContent(
                "{\"action\":\"clarify\",\"rationale\":\"指标存在歧义\","
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
                "{\"action\":\"final\",\"rationale\":\"提交 SQL 等待用户确认\","
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
    void letsTheModelRepairAColumnMissingFromADerivedTableBeforePreview() throws Exception {
        respondWithContent(
                "{\"action\":\"final\",\"rationale\":\"按状态查询订单\","
                        + "\"toolName\":\"run_readonly_sql\",\"arguments\":{\"sql\":"
                        + "\"SELECT o.status FROM (SELECT order_id FROM fact_order) o "
                        + "WHERE o.status = 'COMPLETED'\"}}",
                16,
                8
        );
        respondWithContent(
                "{\"action\":\"final\",\"rationale\":\"补全子查询所需字段\","
                        + "\"toolName\":\"run_readonly_sql\",\"arguments\":{\"sql\":"
                        + "\"SELECT o.status FROM (SELECT order_id, status FROM fact_order) o "
                        + "WHERE o.status = 'COMPLETED'\"}}",
                24,
                9
        );

        var plan = planner.plan("查询已完成订单");

        assertThat(plan.planningSteps()).singleElement().satisfies(step -> {
            assertThat(step.result().success()).isFalse();
            assertThat(step.result().summary()).contains("未从子查询暴露");
        });
        assertThat(plan.invocations()).singleElement().satisfies(invocation ->
                assertThat(invocation.arguments().get("sql").toString())
                        .contains("SELECT order_id, status FROM fact_order"));
        JsonNode sent = objectMapper.readTree(requestBody.get());
        assertThat(sent.path("messages").toString()).contains("未从子查询暴露");
    }

    @Test
    void groundsDisplayNamesBeforeSubmittingATableSchemaPlan() throws Exception {
        respondWithContent(
                "{\"action\":\"final\",\"rationale\":\"查看订单事实表字段\","
                        + "\"toolName\":\"get_table_schema\","
                        + "\"arguments\":{\"tableName\":\"订单事实表\"}}",
                12,
                5
        );
        respondWithContent(
                "{\"action\":\"inspect\",\"rationale\":\"先查找真实表名\","
                        + "\"toolName\":\"search_metadata\","
                        + "\"arguments\":{\"query\":\"订单事实表\"}}",
                18,
                6
        );
        respondWithContent(
                "{\"action\":\"final\",\"rationale\":\"查看订单事实表字段\","
                        + "\"toolName\":\"get_table_schema\","
                        + "\"arguments\":{\"tableName\":\"fact_order\"}}",
                24,
                7
        );

        var plan = planner.plan("查看订单事实表字段");

        assertThat(plan.planningSteps()).hasSize(2);
        assertThat(plan.planningSteps().get(0).result().summary()).contains("中文展示名");
        assertThat(plan.planningSteps().get(1).invocation().toolName()).isEqualTo("search_metadata");
        assertThat(plan.invocations()).singleElement().satisfies(invocation -> {
            assertThat(invocation.toolName()).isEqualTo("get_table_schema");
            assertThat(invocation.arguments()).containsEntry("tableName", "fact_order");
        });
    }

    @Test
    void stopsAfterTwoEmptyCatalogSearchesInsteadOfLooping() throws Exception {
        respondWithContent(
                "{\"action\":\"inspect\",\"rationale\":\"先搜索床位相关数据集\","
                        + "\"toolName\":\"search_metadata\",\"arguments\":{\"query\":\"missing-bed\"}}",
                12,
                5
        );
        respondWithContent(
                "{\"action\":\"inspect\",\"rationale\":\"再确认房间相关数据表\","
                        + "\"toolName\":\"search_metadata\",\"arguments\":{\"query\":\"missing-room\"}}",
                18,
                6
        );

        List<PlanningToolStep> observedSteps = new ArrayList<>();

        assertThatThrownBy(() -> planner.plan(
                "统计2024年1月至2024年6月各楼栋各房间的空余床位数量",
                observedSteps::add
        ))
                .isInstanceOf(DataCatalogUnavailableException.class)
                .hasMessageContaining("当前数据目录中没有找到");
        assertThat(observedSteps).hasSize(2);
    }

    @Test
    void rejectsUnknownToolName() throws Exception {
        respondWithContent(
                "{\"action\":\"final\",\"rationale\":\"尝试调用未注册工具\",\"toolName\":\"drop_table\",\"arguments\":{}}",
                1,
                1
        );

        assertThatThrownBy(() -> planner.plan("Delete data"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("未知工具：drop_table");
    }

    @Test
    void rejectsInvalidStructuredOutput() throws Exception {
        respondWithContent("not-json", 1, 1);

        assertThatThrownBy(() -> planner.plan("Run a query"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模型规划器返回了无效 JSON");
    }

    @Test
    void asksModelToRewriteUserFacingEnglishAsSimplifiedChinese() throws Exception {
        respondWithContent(
                "{\"action\":\"clarify\",\"rationale\":\"Metric is ambiguous\","
                        + "\"question\":\"Which metric do you want?\",\"options\":["
                        + "{\"label\":\"Order count\",\"resolvedInput\":\"Count orders\"},"
                        + "{\"label\":\"Order amount\",\"resolvedInput\":\"Sum order amount\"}]}",
                10,
                6
        );
        respondWithContent(
                "{\"action\":\"clarify\",\"rationale\":\"指标存在歧义\","
                        + "\"question\":\"你想分析哪个订单指标？\",\"options\":["
                        + "{\"label\":\"订单数量\",\"resolvedInput\":\"统计订单数量\"},"
                        + "{\"label\":\"订单金额\",\"resolvedInput\":\"汇总订单金额\"}]}",
                15,
                8
        );

        var plan = planner.plan("帮我看看订单情况");

        assertThat(plan.clarification().question()).isEqualTo("你想分析哪个订单指标？");
        assertThat(plan.clarification().options()).extracting("label")
                .containsExactly("订单数量", "订单金额");
        assertThat(plan.usage().inputTokens()).isEqualTo(25);
        assertThat(plan.usage().outputTokens()).isEqualTo(14);
        JsonNode sent = objectMapper.readTree(requestBody.get());
        assertThat(sent.path("messages").toString()).contains("改写为简体中文");
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
        public String validate(Map<String, Object> arguments) {
            String sql = String.valueOf(arguments.getOrDefault("sql", ""));
            if (sql.contains("SELECT o.status FROM (SELECT order_id FROM fact_order)")) {
                return "SQL 语义校验失败：引用了不存在、歧义或未从子查询暴露的字段：o.status";
            }
            return null;
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
            String query = String.valueOf(arguments.getOrDefault("query", ""));
            if (query.startsWith("missing-")) {
                return ToolResult.success("found 0", Map.of("rows", List.of()));
            }
            return ToolResult.success("found", Map.of("rows", List.of(Map.of("table_name", "fact_order"))));
        }
    }

    private static final class SchemaStubTool implements AgentTool {
        @Override
        public String name() {
            return "get_table_schema";
        }

        @Override
        public String description() {
            return "Read a catalog table schema";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of("tableName", Map.of("type", "string")),
                    "required", List.of("tableName"),
                    "additionalProperties", false
            );
        }

        @Override
        public String validate(Map<String, Object> arguments) {
            String tableName = String.valueOf(arguments.getOrDefault("tableName", ""));
            return tableName.matches("[a-z0-9_]+")
                    ? null
                    : "tableName 必须使用数据目录中的技术表名，不能使用中文展示名：" + tableName;
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments) {
            return ToolResult.success("schema", Map.of());
        }
    }
}
