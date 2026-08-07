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
}

