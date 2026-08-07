package com.dataagent.lab.evaluation;

public record EvaluationCategoryReport(
        String category,
        int totalCases,
        int passedCases,
        double successRate
) {
}
