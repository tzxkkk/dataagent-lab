package com.dataagent.lab.service;

import com.dataagent.lab.domain.RunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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
                .hasMessageContaining("expected WAITING_FOR_APPROVAL");
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
