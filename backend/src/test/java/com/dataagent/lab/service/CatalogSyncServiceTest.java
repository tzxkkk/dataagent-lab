package com.dataagent.lab.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CatalogSyncServiceTest {
    @Autowired
    private CatalogSyncService catalogSyncService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void discoversMissingPhysicalTablesWithoutOverwritingManualMetadata() {
        Integer discoveredInventory = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM metadata_catalog "
                        + "WHERE table_name = 'fact_inventory_snapshot' "
                        + "AND maintenance_mode = 'AUTO_DISCOVERED'",
                Integer.class
        );
        String factOrderDescription = jdbcTemplate.queryForObject(
                "SELECT description FROM metadata_catalog WHERE table_name = 'fact_order'",
                String.class
        );

        assertThat(discoveredInventory).isEqualTo(1);
        assertThat(factOrderDescription).isEqualTo("用于原有 Golden Set 的订单基线表");
        assertThat(catalogSyncService.syncMissingTables()).isZero();
    }
}
