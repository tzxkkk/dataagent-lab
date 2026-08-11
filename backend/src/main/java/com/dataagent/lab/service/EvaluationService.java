package com.dataagent.lab.service;

import com.dataagent.lab.domain.AgentRun;
import com.dataagent.lab.domain.RunStatus;
import com.dataagent.lab.evaluation.EvaluationCase;
import com.dataagent.lab.evaluation.EvaluationCaseResult;
import com.dataagent.lab.evaluation.EvaluationCategoryReport;
import com.dataagent.lab.evaluation.EvaluationReport;
import com.dataagent.lab.planner.AgentPlanner;
import com.dataagent.lab.planner.PlannerDescriptor;
import com.dataagent.lab.planner.PlannerRegistry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class EvaluationService {
    private final AgentRunService runService;
    private final PlannerRegistry plannerRegistry;

    public EvaluationService(AgentRunService runService, PlannerRegistry plannerRegistry) {
        this.runService = runService;
        this.plannerRegistry = plannerRegistry;
    }

    public List<EvaluationCase> cases() {
        return List.of(
                evaluationCase("metadata-order-cn", "metadata", "搜索订单主题数据表",
                        "search_metadata", "fact_order"),
                evaluationCase("metadata-user-cn", "metadata", "找一下用户维度数据",
                        "search_metadata", "dim_user"),
                evaluationCase("metadata-order-en", "metadata", "Which table contains order records?",
                        "search_metadata", "fact_order"),
                evaluationCase("metadata-city-cn", "metadata", "有哪些包含城市信息的数据表",
                        "search_metadata", "dim_user"),

                evaluationCase("schema-order-cn", "schema", "查看订单事实表字段",
                        "get_table_schema", "ORDER_AMOUNT", "CREATED_AT"),
                evaluationCase("schema-user-cn", "schema", "查看用户表结构",
                        "get_table_schema", "USER_LEVEL", "CITY"),
                evaluationCase("schema-order-en", "schema", "Show the fact_order schema",
                        "get_table_schema", "ORDER_ID", "STATUS"),
                evaluationCase("schema-user-en", "schema", "Show columns of dim_user",
                        "get_table_schema", "USER_ID", "USER_LEVEL"),
                evaluationCase("schema-order-amount", "schema", "订单金额对应哪个字段",
                        "get_table_schema", "ORDER_AMOUNT"),
                evaluationCase("schema-user-level", "schema", "用户等级字段是什么",
                        "get_table_schema", "USER_LEVEL"),

                evaluationCase("aggregate-city-amount", "sql_aggregation", "统计各城市已完成订单金额",
                        "run_readonly_sql", "北京", "200.00", "武汉", "150.00", "成都", "80.00"),
                evaluationCase("aggregate-completed-count", "sql_aggregation", "统计已完成订单数量",
                        "run_readonly_sql", "\"completed_count\":4"),
                evaluationCase("aggregate-average-amount", "sql_aggregation", "计算已完成订单的平均金额",
                        "run_readonly_sql", "107.500000"),
                evaluationCase("aggregate-total-amount", "sql_aggregation", "计算已完成订单总金额",
                        "run_readonly_sql", "430.00"),
                evaluationCase("aggregate-maximum-amount", "sql_aggregation", "查询已完成订单最高金额",
                        "run_readonly_sql", "200.00"),
                evaluationCase("aggregate-minimum-amount", "sql_aggregation", "查询已完成订单最低金额",
                        "run_readonly_sql", "50.00"),
                evaluationCase("aggregate-status-count", "sql_aggregation", "按状态统计订单数",
                        "run_readonly_sql", "CANCELLED", "COMPLETED", "PENDING"),
                evaluationCase("aggregate-user-city-count", "sql_aggregation", "统计各城市用户数",
                        "run_readonly_sql", "北京", "成都", "武汉", "\"user_count\":2"),
                evaluationCase("aggregate-completed-city-count", "sql_aggregation", "统计各城市已完成订单数",
                        "run_readonly_sql", "北京", "成都", "武汉", "\"completed_count\":2"),
                evaluationCase("aggregate-top-city", "sql_aggregation", "已完成订单金额最高的城市",
                        "run_readonly_sql", "北京", "200.00"),

                evaluationCase("filter-wuhan-amount", "sql_filtering", "统计武汉已完成订单金额",
                        "run_readonly_sql", "150.00"),
                evaluationCase("filter-high-value-count", "sql_filtering", "统计金额大于100的已完成订单数",
                        "run_readonly_sql", "\"high_value_count\":1"),
                evaluationCase("filter-pending-amount", "sql_filtering", "统计待处理订单总金额",
                        "run_readonly_sql", "20.00"),
                evaluationCase("filter-order-status", "sql_filtering", "查询订单101的状态",
                        "run_readonly_sql", "101", "COMPLETED")
        );
    }

    public EvaluationReport runOffline() {
        return run("offline");
    }

    public EvaluationReport runOpenAi() {
        return run("openai");
    }

    private EvaluationReport run(String plannerMode) {
        AgentPlanner planner = plannerRegistry.require(plannerMode);
        PlannerDescriptor descriptor = planner.descriptor();
        if (!descriptor.ready()) {
            throw new IllegalArgumentException("规划模式尚未配置：" + descriptor.mode());
        }

        List<EvaluationCase> evaluationCases = cases();
        List<EvaluationCaseResult> results = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;
        for (EvaluationCase evaluationCase : evaluationCases) {
            AgentRun run = runService.run(evaluationCase.input(), descriptor.mode());
            inputTokens += run.getPlannerUsage().inputTokens();
            outputTokens += run.getPlannerUsage().outputTokens();
            String actualTool = run.getExecutedTools().isEmpty() ? null : run.getExecutedTools().get(0);
            boolean toolCorrect = evaluationCase.expectedTool().equals(actualTool);
            List<String> missingFragments = missingFragments(run.getOutput(), evaluationCase.expectedOutputFragments());
            boolean outputCorrect = missingFragments.isEmpty();
            boolean passed = run.getStatus() == RunStatus.SUCCEEDED && toolCorrect && outputCorrect;
            String reason = passed ? null : failureReason(run, toolCorrect, missingFragments);
            results.add(new EvaluationCaseResult(evaluationCase.id(), evaluationCase.category(),
                    passed, toolCorrect, actualTool,
                    run.getDurationMs(), reason));
        }

        int passed = (int) results.stream().filter(EvaluationCaseResult::passed).count();
        int toolsCorrect = (int) results.stream().filter(EvaluationCaseResult::toolSelectionCorrect).count();
        double averageLatency = results.stream().mapToLong(EvaluationCaseResult::latencyMs).average().orElse(0);
        int total = results.size();
        List<EvaluationCategoryReport> categories = categoryReports(evaluationCases, results);
        return new EvaluationReport(
                descriptor.mode(),
                descriptor.promptVersion(),
                descriptor.model(),
                Instant.now(),
                total,
                passed,
                ratio(passed, total),
                ratio(toolsCorrect, total),
                averageLatency,
                inputTokens,
                outputTokens,
                categories,
                results
        );
    }

    private EvaluationCase evaluationCase(
            String id,
            String category,
            String input,
            String expectedTool,
            String... expectedOutputFragments
    ) {
        return new EvaluationCase(id, category, input, expectedTool, List.of(expectedOutputFragments));
    }

    private List<String> missingFragments(String output, List<String> expectedFragments) {
        if (output == null) {
            return expectedFragments;
        }
        return expectedFragments.stream().filter(fragment -> !output.contains(fragment)).toList();
    }

    private List<EvaluationCategoryReport> categoryReports(
            List<EvaluationCase> evaluationCases,
            List<EvaluationCaseResult> results
    ) {
        Set<String> categories = new LinkedHashSet<>();
        evaluationCases.forEach(evaluationCase -> categories.add(evaluationCase.category()));
        return categories.stream().map(category -> {
            int total = (int) results.stream().filter(result -> result.category().equals(category)).count();
            int passed = (int) results.stream()
                    .filter(result -> result.category().equals(category) && result.passed())
                    .count();
            return new EvaluationCategoryReport(category, total, passed, ratio(passed, total));
        }).toList();
    }

    private String failureReason(AgentRun run, boolean toolCorrect, List<String> missingFragments) {
        if (run.getStatus() == RunStatus.FAILED) {
            return run.getError();
        }
        if (!toolCorrect) {
            return "工具选择不符合预期";
        }
        if (!missingFragments.isEmpty()) {
            return "结果缺少预期内容：" + missingFragments;
        }
        return "未知评测失败";
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }
}
