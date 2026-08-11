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
        assertThat(result.summary()).contains("Only SELECT");
    }

    @Test
    void rejectsMultipleStatements() {
        var result = tool.execute(Map.of("sql", "SELECT * FROM fact_order; DELETE FROM fact_order"));

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("one SQL statement");
    }

    @Test
    void rejectsSeparatelyAggregatedPartitionsWithoutAnOuterAggregation() {
        var result = tool.execute(Map.of("sql", "SELECT status, SUM(order_amount) FROM fact_order_202607 "
                + "GROUP BY status UNION ALL SELECT status, SUM(order_amount) FROM fact_order_202608 GROUP BY status"));

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("aggregate once in an outer SELECT");
    }

    @Test
    void rejectsAccessToInternalAgentPersistenceTables() {
        var result = tool.execute(Map.of("sql", "SELECT input_text FROM agent_run"));

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("internal Agent persistence tables");
    }

    @Test
    void rejectsTablesOutsideTheBusinessMetadataCatalog() {
        var result = tool.execute(Map.of("sql", "SELECT * FROM unknown_business_table"));

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("not in the business metadata catalog");
    }

    @Test
    void rejectsCrossSchemaQueries() {
        var result = tool.execute(Map.of("sql", "SELECT * FROM mysql.user"));

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("Cross-schema");
    }
}
