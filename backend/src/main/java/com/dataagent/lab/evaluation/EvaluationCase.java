package com.dataagent.lab.evaluation;

import java.util.List;

public record EvaluationCase(
        String id,
        String category,
        String input,
        String expectedTool,
        List<String> expectedOutputFragments
) {
}
