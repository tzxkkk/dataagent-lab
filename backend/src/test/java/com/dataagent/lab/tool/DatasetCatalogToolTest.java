package com.dataagent.lab.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DatasetCatalogToolTest {
    @Autowired
    private DatasetSearchTool datasetSearchTool;

    @Autowired
    private ResolveDatasetTablesTool resolveDatasetTablesTool;

    @Autowired
    private ToolRegistry toolRegistry;

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
}
