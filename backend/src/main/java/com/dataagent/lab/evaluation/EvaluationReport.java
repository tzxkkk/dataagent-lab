package com.dataagent.lab.evaluation;

import java.time.Instant;
import java.util.List;

public record EvaluationReport(
        String mode,
        String promptVersion,
        String model,
        Instant generatedAt,
        int totalCases,
        int passedCases,
        double taskSuccessRate,
        double toolSelectionAccuracy,
        double averageLatencyMs,
        int inputTokens,
        int outputTokens,
        List<EvaluationCategoryReport> categories,
        List<EvaluationCaseResult> cases
) {
}
