package com.dataagent.lab.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class EvaluationServiceTest {
    @Autowired
    private EvaluationService evaluationService;

    @Test
    void offlineGoldenSetIsDeterministic() {
        var report = evaluationService.runOffline();

        assertThat(report.totalCases()).isEqualTo(24);
        assertThat(report.passedCases()).isEqualTo(24);
        assertThat(report.taskSuccessRate()).isEqualTo(1.0);
        assertThat(report.toolSelectionAccuracy()).isEqualTo(1.0);
        assertThat(report.categories()).hasSize(4);
        assertThat(report.categories()).extracting(category -> category.category())
                .containsExactly("metadata", "schema", "sql_aggregation", "sql_filtering");
        assertThat(report.categories()).extracting(category -> category.totalCases())
                .containsExactly(4, 6, 10, 4);
        assertThat(report.categories()).allSatisfy(category ->
                assertThat(category.successRate()).isEqualTo(1.0));
    }
}
