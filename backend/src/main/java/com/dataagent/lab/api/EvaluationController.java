package com.dataagent.lab.api;

import com.dataagent.lab.evaluation.EvaluationCase;
import com.dataagent.lab.evaluation.EvaluationReport;
import com.dataagent.lab.service.EvaluationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {
    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @GetMapping("/cases")
    public List<EvaluationCase> cases() {
        return evaluationService.cases();
    }

    @PostMapping("/offline")
    public EvaluationReport runOffline() {
        return evaluationService.runOffline();
    }

    @PostMapping("/openai")
    public EvaluationReport runOpenAi() {
        return evaluationService.runOpenAi();
    }
}
