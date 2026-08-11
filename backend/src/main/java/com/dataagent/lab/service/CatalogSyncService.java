package com.dataagent.lab.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class CatalogSyncService implements ApplicationRunner {
    private static final Set<String> INFRASTRUCTURE_TABLES = Set.of(
            "metadata_catalog",
            "logical_dataset",
            "dataset_field_catalog",
            "dataset_physical_table",
            "dataset_relation",
            "agent_run",
            "agent_trace_event",
            "agent_run_feedback",
            "agent_pending_plan"
    );

    private final JdbcTemplate jdbcTemplate;

    public CatalogSyncService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        syncMissingTables();
    }

    public int syncMissingTables() {
        jdbcTemplate.update(
                "DELETE FROM metadata_catalog WHERE maintenance_mode = 'AUTO_DISCOVERED' "
                        + "AND LOWER(table_name) IN ('agent_run', 'agent_trace_event', "
                        + "'agent_run_feedback', 'agent_pending_plan')"
        );
        List<String> physicalTables = jdbcTemplate.queryForList(
                "SELECT LOWER(table_name) FROM information_schema.tables "
                        + "WHERE LOWER(table_schema) = LOWER(SCHEMA()) AND table_type = 'BASE TABLE' "
                        + "ORDER BY table_name",
                String.class
        );
        int inserted = 0;
        for (String tableName : physicalTables) {
            if (INFRASTRUCTURE_TABLES.contains(tableName)) {
                continue;
            }
            inserted += jdbcTemplate.update(
                    "INSERT INTO metadata_catalog(" 
                            + "table_name, display_name, description, business_domain, grain_description, "
                            + "owner_name, trust_status, maintenance_mode, last_synced_at) "
                            + "SELECT ?, ?, ?, '未分类', '待人工确认', '待认领', "
                            + "'DISCOVERED', 'AUTO_DISCOVERED', CURRENT_TIMESTAMP "
                            + "WHERE NOT EXISTS (SELECT 1 FROM metadata_catalog WHERE LOWER(table_name) = ?)",
                    tableName,
                    tableName,
                    "启动时自动发现的物理表：" + tableName,
                    tableName
            );
        }
        return inserted;
    }
}
