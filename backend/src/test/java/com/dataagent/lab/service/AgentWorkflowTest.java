package com.dataagent.lab.service;

import com.dataagent.lab.domain.RunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AgentWorkflowTest {
    @Autowired
    private AgentRunService runService;

    @Test
    void asksForClarificationInsteadOfGuessingAnAmbiguousMetric() {
        var run = runService.preview("帮我看看订单情况", "offline", null);

        assertThat(run.getStatus()).isEqualTo(RunStatus.WAITING_FOR_CLARIFICATION);
        assertThat(run.getClarification().options()).hasSize(4);
        assertThat(run.getPlanPreview()).isNull();
        assertThat(run.getExecutedTools()).isEmpty();
    }

    @Test
    void marksTableCreationAsNotImplementedWithoutCallingThePlanner() {
        var unsupportedRequests = List.of(
                "新增表：表名=xxx，字段列表=字段名1 类型1 说明1",
                "新增订单表并定义字段",
                "INSERT INTO fact_order(order_id) VALUES (100)",
                "UPDATE fact_order SET status = 'COMPLETED'",
                "给 analyst 授予 fact_order 的查询权限",
                "回滚当前事务"
        );

        for (String input : unsupportedRequests) {
            var run = runService.preview(input, "offline", null);
            assertThat(run.getStatus()).isEqualTo(RunStatus.NOT_IMPLEMENTED);
            assertThat(run.getOutput()).contains("只支持 DQL", "DDL、DML、DCL 和 TCL");
            assertThat(run.getClarification()).isNull();
            assertThat(run.getPlanPreview()).isNull();
            assertThat(run.getExecutedTools()).isEmpty();
            assertThat(run.getPlannerUsage().inputTokens()).isZero();
            assertThat(run.getPlannerUsage().outputTokens()).isZero();
            assertThat(run.getEvents()).extracting("type")
                    .containsExactly("RUN_CREATED", "CAPABILITY_NOT_IMPLEMENTED");
        }
    }

    @Test
    void keepsReadOnlyQueriesInsideTheSupportedDqlBoundary() {
        var run = runService.preview("查询新增订单数量", "offline", null);

        assertThat(run.getStatus()).isNotEqualTo(RunStatus.NOT_IMPLEMENTED);
        assertThat(run.getEvents()).extracting("type")
                .doesNotContain("CAPABILITY_NOT_IMPLEMENTED");
    }

    @Test
    void clarifiedRequestWaitsForApprovalBeforeExecution() {
        var run = runService.preview("分析一下订单概况", "offline", null);
        var clarified = runService.clarify(run.getId(), "统计各城市已完成订单金额");

        assertThat(clarified.getStatus()).isEqualTo(RunStatus.WAITING_FOR_APPROVAL);
        assertThat(clarified.getEffectiveInput()).isEqualTo("统计各城市已完成订单金额");
        assertThat(clarified.getPlanPreview().sourceTables()).containsExactly("fact_order", "dim_user");
        assertThat(clarified.getPlanPreview().assumptions())
                .contains("将 COMPLETED 作为已完成订单口径", "金额指标使用目录和表结构中确认的 order_amount 字段");
        assertThat(clarified.getExecutedTools()).isEmpty();
    }

    @Test
    void approvalExecutesPlanAndCreatesUserFacingEvidence() {
        var preview = runService.preview("统计各城市已完成订单金额", "offline", null);

        assertThat(preview.getStatus()).isEqualTo(RunStatus.WAITING_FOR_APPROVAL);
        assertThat(preview.getExecutedTools()).isEmpty();

        var completed = runService.approve(preview.getId());

        assertThat(completed.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(completed.getEvidence().rowCount()).isEqualTo(3);
        assertThat(completed.getEvidence().sourceTables()).containsExactly("fact_order", "dim_user");
        assertThat(completed.getEvidence().resultData()).containsKey("rows");
        assertThatThrownBy(() -> runService.approve(preview.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("预期状态为 WAITING_FOR_APPROVAL");
    }

    @Test
    void revisionCreatesANewBranchWithoutOverwritingTheOriginalRun() {
        var original = runService.preview("统计已完成订单数量", "offline", null);
        var revised = runService.revise(original.getId(), "统计各城市已完成订单金额");

        assertThat(revised.getParentRunId()).isEqualTo(original.getId());
        assertThat(revised.getId()).isNotEqualTo(original.getId());
        assertThat(revised.getStatus()).isEqualTo(RunStatus.WAITING_FOR_APPROVAL);
        assertThat(original.getEvents()).extracting("type").contains("REVISION_REQUESTED");
    }

    @Test
    void recordsStructuredFeedbackForACompletedRun() {
        var preview = runService.preview("统计已完成订单数量", "offline", null);
        var completed = runService.approve(preview.getId());

        var updated = runService.recordFeedback(
                completed.getId(),
                "down",
                "MISSING_FILTER",
                "我实际只想看武汉"
        );

        assertThat(updated.getFeedback().rating()).isEqualTo("DOWN");
        assertThat(updated.getFeedback().reason()).isEqualTo("MISSING_FILTER");
        assertThat(updated.getFeedback().comment()).isEqualTo("我实际只想看武汉");
        assertThat(updated.getEvents()).extracting("type").contains("FEEDBACK_RECORDED");
    }
}
