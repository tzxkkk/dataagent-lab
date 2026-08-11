package com.dataagent.lab.repository;

import com.dataagent.lab.domain.RunStatus;
import com.dataagent.lab.service.AgentRunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AgentRunRepositoryTest {
    @Autowired
    private AgentRunService runService;

    @Autowired
    private AgentRunRepository runRepository;

    @Test
    void reloadsCompletedRunTraceToolsEvidenceAndFeedback() {
        var preview = runService.preview("统计各城市已完成订单金额", "offline", null);
        var completed = runService.approve(preview.getId());
        runService.recordFeedback(completed.getId(), "up", "CORRECT", "结果符合预期");

        var reloaded = runRepository.findById(completed.getId()).orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(reloaded.getExecutedTools()).containsExactly("run_readonly_sql");
        assertThat(reloaded.getEvidence().rowCount()).isEqualTo(3);
        assertThat(reloaded.getFeedback().rating()).isEqualTo("UP");
        assertThat(reloaded.getFeedback().comment()).isEqualTo("结果符合预期");
        assertThat(reloaded.getEvents()).extracting("type")
                .containsExactly("RUN_CREATED", "PLANNING_STARTED", "PLAN_CREATED", "PLAN_REVIEW_REQUIRED",
                        "APPROVAL_RECEIVED", "TOOL_STARTED", "TOOL_SUCCEEDED", "RUN_SUCCEEDED",
                        "FEEDBACK_RECORDED");
    }

    @Test
    void persistsPendingPlanForApprovalAfterProcessRestart() {
        var preview = runService.preview("统计已完成订单数量", "offline", null);

        var pendingPlan = runRepository.findPendingPlan(preview.getId()).orElseThrow();

        assertThat(preview.getStatus()).isEqualTo(RunStatus.WAITING_FOR_APPROVAL);
        assertThat(pendingPlan.invocations()).hasSize(1);
        assertThat(pendingPlan.invocations().get(0).toolName()).isEqualTo("run_readonly_sql");
    }
}
