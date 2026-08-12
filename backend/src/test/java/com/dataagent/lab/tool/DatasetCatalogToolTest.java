package com.dataagent.lab.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class DatasetCatalogToolTest {
    @Autowired
    private DatasetSearchTool datasetSearchTool;

    @Autowired
    private ResolveDatasetTablesTool resolveDatasetTablesTool;

    @Autowired
    private DatasetContextTool datasetContextTool;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private TableSchemaTool tableSchemaTool;

    @Test
    void searchesLogicalDatasetsInsteadOfScanningEveryPhysicalTable() {
        var result = datasetSearchTool.execute(Map.of("query", "交易"));

        assertThat(result.success()).isTrue();
        assertThat(result.data().toString()).contains("orders", "payments", "refunds");
        assertThat(toolRegistry.describe()).extracting(ToolDefinition::name)
                .contains("search_datasets", "resolve_dataset_tables");
    }

    @Test
    void resolvesOnlyMonthlyPartitionsCoveredByTheRequestedRange() {
        var result = resolveDatasetTablesTool.execute(Map.of(
                "datasetId", "orders",
                "startMonth", "2026-07",
                "endMonth", "2026-08"
        ));

        assertThat(result.success()).isTrue();
        assertThat((List<?>) result.data().get("physicalTables")).hasSize(2);
        assertThat(result.data().toString())
                .contains("fact_order_202607", "fact_order_202608")
                .doesNotContain("fact_order_202606");
    }

    @Test
    void rejectsIncompleteOrInvalidMonthRanges() {
        var incomplete = resolveDatasetTablesTool.execute(Map.of(
                "datasetId", "orders",
                "startMonth", "2026-07"
        ));
        var reversed = resolveDatasetTablesTool.execute(Map.of(
                "datasetId", "orders",
                "startMonth", "2026-08",
                "endMonth", "2026-07"
        ));

        assertThat(incomplete.success()).isFalse();
        assertThat(incomplete.summary()).contains("provided together");
        assertThat(reversed.success()).isFalse();
        assertThat(reversed.summary()).contains("must not be after");
    }

    @Test
    void loadsGroundedDatasetMappingsAndJoinRelationshipsForTheModel() {
        var result = datasetContextTool.execute(Map.of(
                "datasetId", "orders",
                "startMonth", "2026-07",
                "endMonth", "2026-08"
        ));

        assertThat(result.success()).isTrue();
        assertThat((List<?>) result.data().get("physicalTables")).hasSize(2);
        assertThat(result.data().toString())
                .contains("fact_order_202607", "fact_order_202608")
                .contains("orders.user_id = users.user_id")
                .contains("COMPLETED=已完成", "订单应付金额");
    }

    @Test
    void rejectsDisplayNamesBeforeSchemaPlansReachApproval() {
        assertThat(tableSchemaTool.validate(Map.of("tableName", "订单事实表")))
                .contains("技术表名", "中文展示名");
        assertThatThrownBy(() -> toolRegistry.requireValidated(
                "get_table_schema",
                Map.of("tableName", "订单事实表")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("技术表名");
    }
}
