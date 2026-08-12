package com.dataagent.lab.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReadOnlySqlToolTest {
    @Autowired
    private ReadOnlySqlTool tool;

    @Test
    void executesSelectAndAddsLimit() {
        var result = tool.execute(Map.of("sql", "SELECT order_id FROM fact_order ORDER BY order_id"));

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("sql").toString()).contains("LIMIT 200");
    }

    @Test
    void rejectsMutation() {
        var result = tool.execute(Map.of("sql", "DELETE FROM fact_order"));

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("只允许 SELECT");
    }

    @Test
    void rejectsMultipleStatements() {
        var result = tool.execute(Map.of("sql", "SELECT * FROM fact_order; DELETE FROM fact_order"));

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("只允许一条 SQL 语句");
    }

    @Test
    void rejectsSeparatelyAggregatedPartitionsWithoutAnOuterAggregation() {
        var result = tool.execute(Map.of("sql", "SELECT status, SUM(order_amount) FROM fact_order_202607 "
                + "GROUP BY status UNION ALL SELECT status, SUM(order_amount) FROM fact_order_202608 GROUP BY status"));

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("外层 SELECT 统一聚合");
    }

    @Test
    void rejectsAccessToInternalAgentPersistenceTables() {
        var result = tool.execute(Map.of("sql", "SELECT input_text FROM agent_run"));

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("Agent 内部持久化表");
    }

    @Test
    void rejectsTablesOutsideTheBusinessMetadataCatalog() {
        var result = tool.execute(Map.of("sql", "SELECT * FROM unknown_business_table"));

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("不在业务目录白名单");
    }

    @Test
    void rejectsCrossSchemaQueries() {
        var result = tool.execute(Map.of("sql", "SELECT * FROM mysql.user"));

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("跨 Schema");
    }

    @Test
    void rejectsUnknownColumnsBeforeExecution() {
        var result = tool.execute(Map.of("sql", "SELECT missing_column FROM fact_order"));

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("SQL 语义校验失败");
        assertThat(result.summary()).containsIgnoringCase("missing_column");
    }

    @Test
    void rejectsColumnsNotProjectedByDerivedTablesBeforeExecution() {
        var result = tool.execute(Map.of(
                "sql",
                "SELECT o.status FROM (SELECT order_id FROM fact_order) o WHERE o.status = 'COMPLETED'"
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("SQL 语义校验失败");
        assertThat(result.summary()).containsIgnoringCase("status");
    }
}
