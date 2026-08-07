package com.dataagent.lab.domain;

import java.time.Instant;

public record RunFeedback(String rating, String reason, String comment, Instant submittedAt) {
}
