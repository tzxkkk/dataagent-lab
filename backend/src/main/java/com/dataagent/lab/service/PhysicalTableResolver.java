package com.dataagent.lab.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class PhysicalTableResolver {
    private final JdbcTemplate jdbcTemplate;

    public PhysicalTableResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> resolve(String datasetId, String startMonth, String endMonth) {
        String normalizedDatasetId = required(datasetId, "datasetId");
        Integer matches = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM logical_dataset WHERE dataset_id = ?",
                Integer.class,
                normalizedDatasetId
        );
        if (matches == null || matches == 0) {
            throw new IllegalArgumentException("Unknown logical dataset: " + normalizedDatasetId);
        }

        if ((startMonth == null || startMonth.isBlank()) && (endMonth == null || endMonth.isBlank())) {
            return allMappings(normalizedDatasetId);
        }
        if (startMonth == null || startMonth.isBlank() || endMonth == null || endMonth.isBlank()) {
            throw new IllegalArgumentException("startMonth and endMonth must be provided together");
        }

        YearMonth start = parseMonth(startMonth, "startMonth");
        YearMonth end = parseMonth(endMonth, "endMonth");
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("startMonth must not be after endMonth");
        }

        List<Map<String, Object>> mappings = jdbcTemplate.queryForList(
                "SELECT physical_table_name, partition_value, routing_priority "
                        + "FROM dataset_physical_table WHERE dataset_id = ? "
                        + "AND (partition_value = 'STATIC' OR partition_value BETWEEN ? AND ?) "
                        + "ORDER BY routing_priority, partition_value, physical_table_name",
                normalizedDatasetId,
                start.toString(),
                end.toString()
        );
        if (mappings.isEmpty()) {
            throw new IllegalArgumentException("No physical tables cover the requested month range");
        }
        return mappings;
    }

    private List<Map<String, Object>> allMappings(String datasetId) {
        List<Map<String, Object>> mappings = jdbcTemplate.queryForList(
                "SELECT physical_table_name, partition_value, routing_priority "
                        + "FROM dataset_physical_table WHERE dataset_id = ? "
                        + "ORDER BY routing_priority, partition_value, physical_table_name",
                datasetId
        );
        if (mappings.isEmpty()) {
            throw new IllegalArgumentException("Logical dataset has no physical table mappings: " + datasetId);
        }
        return mappings;
    }

    private String required(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private YearMonth parseMonth(String value, String fieldName) {
        try {
            return YearMonth.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(fieldName + " must use yyyy-MM format");
        }
    }
}
