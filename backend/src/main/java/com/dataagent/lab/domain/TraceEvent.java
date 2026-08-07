package com.dataagent.lab.domain;

import java.time.Instant;
import java.util.Map;

public record TraceEvent(
        int sequence,
        String type,
        String message,
        Instant occurredAt,
        Map<String, Object> data
) {
}

