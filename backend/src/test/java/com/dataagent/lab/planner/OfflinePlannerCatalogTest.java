package com.dataagent.lab.planner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OfflinePlannerCatalogTest {
    private final OfflinePlanner planner = new OfflinePlanner();

    @Test
    void selectsLogicalDatasetSearchForBusinessDomainQuestions() {
        var plan = planner.plan("搜索交易主题域的逻辑数据集");

        assertThat(plan.invocations()).hasSize(1);
        assertThat(plan.invocations().get(0).toolName()).isEqualTo("search_datasets");
        assertThat(plan.invocations().get(0).arguments()).containsEntry("query", "交易");
    }

    @Test
    void extractsMonthRangeForPhysicalTableRouting() {
        var plan = planner.plan("订单数据从 2026-07 到 2026-08 应该路由到哪些物理表");

        assertThat(plan.invocations()).hasSize(1);
        assertThat(plan.invocations().get(0).toolName()).isEqualTo("resolve_dataset_tables");
        assertThat(plan.invocations().get(0).arguments())
                .containsEntry("datasetId", "orders")
                .containsEntry("startMonth", "2026-07")
                .containsEntry("endMonth", "2026-08");
    }
}
