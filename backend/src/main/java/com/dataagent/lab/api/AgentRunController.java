package com.dataagent.lab.api;

import com.dataagent.lab.domain.AgentRun;
import com.dataagent.lab.planner.PlannerDescriptor;
import com.dataagent.lab.planner.PlannerRegistry;
import com.dataagent.lab.service.AgentRunService;
import com.dataagent.lab.tool.ToolDefinition;
import com.dataagent.lab.tool.ToolRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class AgentRunController {
    private final AgentRunService runService;
    private final ToolRegistry toolRegistry;
    private final PlannerRegistry plannerRegistry;

    public AgentRunController(AgentRunService runService, ToolRegistry toolRegistry, PlannerRegistry plannerRegistry) {
        this.runService = runService;
        this.toolRegistry = toolRegistry;
        this.plannerRegistry = plannerRegistry;
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentRun create(@RequestBody CreateRunRequest request) {
        return runService.run(request.input(), request.plannerMode());
    }

    @PostMapping("/runs/preview")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentRun preview(@RequestBody PreviewRunRequest request) {
        return runService.preview(request.input(), request.plannerMode(), request.parentRunId());
    }

    @PostMapping("/runs/{id}/clarify")
    public AgentRun clarify(@PathVariable String id, @RequestBody ClarifyRunRequest request) {
        return runService.clarify(id, request.resolvedInput());
    }

    @PostMapping("/runs/{id}/approve")
    public AgentRun approve(@PathVariable String id) {
        return runService.approve(id);
    }

    @PostMapping("/runs/{id}/revise")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentRun revise(@PathVariable String id, @RequestBody ReviseRunRequest request) {
        return runService.revise(id, request.input());
    }

    @PostMapping("/runs/{id}/feedback")
    public AgentRun feedback(@PathVariable String id, @RequestBody FeedbackRequest request) {
        return runService.recordFeedback(id, request.rating(), request.reason(), request.comment());
    }

    @GetMapping("/runs/{id}")
    public AgentRun get(@PathVariable String id) {
        return runService.require(id);
    }

    @GetMapping("/tools")
    public List<ToolDefinition> tools() {
        return toolRegistry.describe();
    }

    @GetMapping("/planners")
    public List<PlannerDescriptor> planners() {
        return plannerRegistry.describe();
    }

    public record CreateRunRequest(String input, String plannerMode) {
    }

    public record PreviewRunRequest(String input, String plannerMode, String parentRunId) {
    }

    public record ClarifyRunRequest(String resolvedInput) {
    }

    public record ReviseRunRequest(String input) {
    }

    public record FeedbackRequest(String rating, String reason, String comment) {
    }
}
