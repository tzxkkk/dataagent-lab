package com.dataagent.lab.service;

import com.dataagent.lab.domain.AgentPlan;
import com.dataagent.lab.domain.AgentRun;
import com.dataagent.lab.domain.PlannerUsage;
import com.dataagent.lab.domain.RunEvidence;
import com.dataagent.lab.domain.RunFeedback;
import com.dataagent.lab.domain.RunStatus;
import com.dataagent.lab.domain.ToolInvocation;
import com.dataagent.lab.domain.ToolResult;
import com.dataagent.lab.domain.TraceEvent;
import com.dataagent.lab.planner.AgentPlanner;
import com.dataagent.lab.planner.PlannerDescriptor;
import com.dataagent.lab.planner.PlannerRegistry;
import com.dataagent.lab.tool.AgentTool;
import com.dataagent.lab.tool.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentRunService {
    private final PlannerRegistry plannerRegistry;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final UserIntentClarifier intentClarifier;
    private final PlanPreviewFactory previewFactory;
    private final Map<String, AgentRun> runs = new ConcurrentHashMap<>();
    private final Map<String, AgentPlan> pendingPlans = new ConcurrentHashMap<>();

    public AgentRunService(
            PlannerRegistry plannerRegistry,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            UserIntentClarifier intentClarifier,
            PlanPreviewFactory previewFactory
    ) {
        this.plannerRegistry = plannerRegistry;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.intentClarifier = intentClarifier;
        this.previewFactory = previewFactory;
    }

    public AgentRun run(String input) {
        return run(input, null);
    }

    public AgentRun run(String input, String plannerMode) {
        validateInput(input);
        long startedAt = System.nanoTime();
        AgentRun run = createRun(input, plannerMode, null);
        try {
            AgentPlan plan = plan(run, false);
            execute(run, plan);
        } catch (RuntimeException exception) {
            fail(run, exception);
        } finally {
            run.setDurationMs((System.nanoTime() - startedAt) / 1_000_000);
        }
        return run;
    }

    public AgentRun preview(String input, String plannerMode, String parentRunId) {
        validateInput(input);
        if (parentRunId != null && !parentRunId.isBlank()) {
            require(parentRunId);
        }
        long startedAt = System.nanoTime();
        AgentRun run = createRun(input, plannerMode, normalizeNullable(parentRunId));
        try {
            var clarification = intentClarifier.clarify(run.getEffectiveInput());
            if (clarification.isPresent()) {
                run.setClarification(clarification.get());
                run.setStatus(RunStatus.WAITING_FOR_CLARIFICATION);
                event(run, "CLARIFICATION_REQUIRED", clarification.get().question(),
                        Map.of("options", clarification.get().options()));
            } else {
                plan(run, true);
            }
        } catch (RuntimeException exception) {
            fail(run, exception);
        } finally {
            run.setDurationMs((System.nanoTime() - startedAt) / 1_000_000);
        }
        return run;
    }

    public AgentRun clarify(String id, String resolvedInput) {
        validateInput(resolvedInput);
        AgentRun run = require(id);
        synchronized (run) {
            requireStatus(run, RunStatus.WAITING_FOR_CLARIFICATION);
            long startedAt = System.nanoTime();
            run.setEffectiveInput(resolvedInput.trim());
            event(run, "CLARIFICATION_RESOLVED", "User selected a concrete analysis goal",
                    Map.of("effectiveInput", run.getEffectiveInput()));
            try {
                plan(run, true);
            } catch (RuntimeException exception) {
                fail(run, exception);
            } finally {
                run.setDurationMs((System.nanoTime() - startedAt) / 1_000_000);
            }
            return run;
        }
    }

    public AgentRun approve(String id) {
        AgentRun run = require(id);
        synchronized (run) {
            requireStatus(run, RunStatus.WAITING_FOR_APPROVAL);
            AgentPlan plan = pendingPlans.remove(id);
            if (plan == null) {
                throw new IllegalStateException("Pending plan is missing for run: " + id);
            }
            long startedAt = System.nanoTime();
            event(run, "APPROVAL_RECEIVED", "User approved the plan", Map.of());
            try {
                execute(run, plan);
            } catch (RuntimeException exception) {
                fail(run, exception);
            } finally {
                run.setDurationMs((System.nanoTime() - startedAt) / 1_000_000);
            }
            return run;
        }
    }

    public AgentRun revise(String id, String input) {
        AgentRun previous = require(id);
        validateInput(input);
        synchronized (previous) {
            event(previous, "REVISION_REQUESTED", "User changed the analysis request",
                    Map.of("revisedInput", input.trim()));
        }
        return preview(input, previous.getPlannerMode(), previous.getId());
    }

    public AgentRun recordFeedback(String id, String rating, String reason, String comment) {
        AgentRun run = require(id);
        String normalizedRating = rating == null ? "" : rating.trim().toUpperCase();
        if (!Set.of("UP", "DOWN").contains(normalizedRating)) {
            throw new IllegalArgumentException("Rating must be UP or DOWN");
        }
        if (run.getStatus() != RunStatus.SUCCEEDED && run.getStatus() != RunStatus.FAILED) {
            throw new IllegalArgumentException("Feedback can only be recorded for a completed run");
        }
        synchronized (run) {
            RunFeedback feedback = new RunFeedback(
                    normalizedRating,
                    normalizeNullable(reason),
                    normalizeNullable(comment),
                    Instant.now()
            );
            run.setFeedback(feedback);
            event(run, "FEEDBACK_RECORDED", "User feedback recorded",
                    Map.of("rating", feedback.rating(), "reason", feedback.reason() == null ? "" : feedback.reason()));
            return run;
        }
    }

    public AgentRun require(String id) {
        AgentRun run = runs.get(id);
        if (run == null) {
            throw new IllegalArgumentException("Run not found: " + id);
        }
        return run;
    }

    private AgentRun createRun(String input, String plannerMode, String parentRunId) {
        AgentPlanner planner = plannerRegistry.require(plannerMode);
        PlannerDescriptor descriptor = planner.descriptor();
        AgentRun run = new AgentRun(UUID.randomUUID().toString(), input.trim(), parentRunId);
        run.setPlannerMode(descriptor.mode());
        run.setPlannerUsage(new PlannerUsage(descriptor.promptVersion(), descriptor.model(), 0, 0));
        runs.put(run.getId(), run);
        event(run, "RUN_CREATED", "Run accepted", Map.of(
                "mode", descriptor.mode(),
                "promptVersion", descriptor.promptVersion(),
                "model", descriptor.model(),
                "parentRunId", parentRunId == null ? "" : parentRunId
        ));
        return run;
    }

    private AgentPlan plan(AgentRun run, boolean waitForApproval) {
        AgentPlanner planner = plannerRegistry.require(run.getPlannerMode());
        run.setStatus(RunStatus.PLANNING);
        event(run, "PLANNING_STARTED", "Planner is selecting tools", Map.of());
        AgentPlan plan = planner.plan(run.getEffectiveInput());
        validatePlan(plan);
        run.setPlannerUsage(plan.usage());
        run.setPlanPreview(previewFactory.create(plan));
        event(run, "PLAN_CREATED", plan.rationale(), Map.of(
                "tools", plan.invocations().stream().map(ToolInvocation::toolName).toList(),
                "promptVersion", plan.usage().promptVersion(),
                "model", plan.usage().model(),
                "inputTokens", plan.usage().inputTokens(),
                "outputTokens", plan.usage().outputTokens()
        ));
        if (waitForApproval) {
            pendingPlans.put(run.getId(), plan);
            run.setStatus(RunStatus.WAITING_FOR_APPROVAL);
            event(run, "PLAN_REVIEW_REQUIRED", "Waiting for user approval before tool execution", Map.of(
                    "riskLevel", run.getPlanPreview().riskLevel(),
                    "rowLimit", run.getPlanPreview().rowLimit()
            ));
        }
        return plan;
    }

    private void execute(AgentRun run, AgentPlan plan) {
        run.setStatus(RunStatus.RUNNING);
        ToolResult lastResult = null;
        for (ToolInvocation invocation : plan.invocations()) {
            AgentTool tool = toolRegistry.require(invocation.toolName());
            run.addExecutedTool(tool.name());
            event(run, "TOOL_STARTED", "Executing " + tool.name(),
                    Map.of("tool", tool.name(), "arguments", invocation.arguments()));
            lastResult = tool.execute(invocation.arguments());
            if (!lastResult.success()) {
                throw new ToolExecutionException(lastResult.summary());
            }
            event(run, "TOOL_SUCCEEDED", lastResult.summary(),
                    Map.of("tool", tool.name(), "result", lastResult.data()));
        }

        run.setOutput(render(lastResult));
        run.setEvidence(evidence(run, lastResult));
        run.setStatus(RunStatus.SUCCEEDED);
        event(run, "RUN_SUCCEEDED", "Run completed", Map.of(
                "rowCount", run.getEvidence().rowCount(),
                "sourceTables", run.getEvidence().sourceTables()
        ));
    }

    private RunEvidence evidence(AgentRun run, ToolResult result) {
        int rowCount = 0;
        Object rows = result.data().get("rows");
        Object columns = result.data().get("columns");
        if (rows instanceof List<?> rowList) {
            rowCount = rowList.size();
        } else if (columns instanceof List<?> columnList) {
            rowCount = columnList.size();
        }
        return new RunEvidence(
                result.summary(),
                run.getPlanPreview().sourceTables(),
                run.getPlanPreview().filters(),
                run.getPlanPreview().sql(),
                rowCount,
                result.data()
        );
    }

    private void fail(AgentRun run, RuntimeException exception) {
        pendingPlans.remove(run.getId());
        String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        run.setStatus(RunStatus.FAILED);
        run.setError(message);
        event(run, "RUN_FAILED", message, Map.of());
    }

    private void requireStatus(AgentRun run, RunStatus expected) {
        if (run.getStatus() != expected) {
            throw new IllegalArgumentException(
                    "Run " + run.getId() + " is " + run.getStatus() + ", expected " + expected
            );
        }
    }

    private void validateInput(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Input is required");
        }
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String render(ToolResult result) {
        if (result == null) {
            return "No tool result";
        }
        try {
            return result.summary() + "\n" + objectMapper.writeValueAsString(result.data());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize tool result", exception);
        }
    }

    private void validatePlan(AgentPlan plan) {
        if (plan == null || plan.invocations() == null || plan.invocations().size() != 1) {
            throw new IllegalStateException("Planner must return exactly one tool invocation");
        }
        if (plan.usage() == null) {
            throw new IllegalStateException("Planner usage metadata is required");
        }
    }

    private void event(AgentRun run, String type, String message, Map<String, Object> data) {
        run.addEvent(new TraceEvent(run.getEvents().size() + 1, type, message, Instant.now(), data));
    }

    private static final class ToolExecutionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private ToolExecutionException(String message) {
            super(message);
        }
    }
}
