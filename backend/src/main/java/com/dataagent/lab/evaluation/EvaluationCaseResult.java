package com.dataagent.lab.evaluation;

public record EvaluationCaseResult(
        String caseId,
        String category,
        boolean passed,
        boolean toolSelectionCorrect,
        String actualTool,
        long latencyMs,
        String failureReason
) {
}
