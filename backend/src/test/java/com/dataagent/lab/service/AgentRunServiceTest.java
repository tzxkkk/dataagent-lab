package com.dataagent.lab.service;

import com.dataagent.lab.domain.RunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AgentRunServiceTest {
    @Autowired
    private AgentRunService runService;

    @Test
    void recordsStructuredTraceForSuccessfulRun() {
        var run = runService.run("统计各城市已完成订单金额");

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getExecutedTools()).containsExactly("run_readonly_sql");
        assertThat(run.getEvents()).extracting("type")
                .containsExactly("RUN_CREATED", "PLANNING_STARTED", "PLAN_CREATED",
                        "TOOL_STARTED", "TOOL_SUCCEEDED", "RUN_SUCCEEDED");
        assertThat(run.getOutput()).contains("武汉", "150.00", "北京", "200.00");
    }
}

