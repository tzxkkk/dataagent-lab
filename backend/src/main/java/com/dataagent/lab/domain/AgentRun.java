package com.dataagent.lab.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AgentRun {
    // 身份信息
    private final String id;
    private final String input;
    private final String parentRunId;
    private final Instant createdAt;

    // 过程轨迹
    private final List<TraceEvent> events = new ArrayList<>();
    private final List<String> executedTools = new ArrayList<>();

    // 运行中上下文
    private RunStatus status;
    private String effectiveInput;
    private String plannerMode;
    private PlannerUsage plannerUsage;

    // 用户交互数据
    private ClarificationPrompt clarification;
    private PlanPreview planPreview;


    private RunEvidence evidence;
    // 反馈
    private RunFeedback feedback;
    private String output;
    private String error;
    private long durationMs;

    public AgentRun(String id, String input) {
        this(id, input, null);
    }

    public AgentRun(String id, String input, String parentRunId) {
        this(id, input, parentRunId, Instant.now());
    }

    public AgentRun(String id, String input, String parentRunId, Instant createdAt) {
        this.id = id;
        this.input = input;
        this.effectiveInput = input;
        this.parentRunId = parentRunId;
        this.createdAt = createdAt;
        this.status = RunStatus.CREATED;
    }

    public String getId() {
        return id;
    }

    public String getInput() {
        return input;
    }

    public String getParentRunId() {
        return parentRunId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public RunStatus getStatus() {
        return status;
    }

    public void setStatus(RunStatus status) {
        this.status = status;
    }

    public String getEffectiveInput() {
        return effectiveInput;
    }

    public void setEffectiveInput(String effectiveInput) {
        this.effectiveInput = effectiveInput;
    }

    public String getPlannerMode() {
        return plannerMode;
    }

    public void setPlannerMode(String plannerMode) {
        this.plannerMode = plannerMode;
    }

    public PlannerUsage getPlannerUsage() {
        return plannerUsage;
    }

    public void setPlannerUsage(PlannerUsage plannerUsage) {
        this.plannerUsage = plannerUsage;
    }

    public ClarificationPrompt getClarification() {
        return clarification;
    }

    public void setClarification(ClarificationPrompt clarification) {
        this.clarification = clarification;
    }

    public PlanPreview getPlanPreview() {
        return planPreview;
    }

    public void setPlanPreview(PlanPreview planPreview) {
        this.planPreview = planPreview;
    }

    public RunEvidence getEvidence() {
        return evidence;
    }

    public void setEvidence(RunEvidence evidence) {
        this.evidence = evidence;
    }

    public RunFeedback getFeedback() {
        return feedback;
    }

    public void setFeedback(RunFeedback feedback) {
        this.feedback = feedback;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public List<TraceEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public List<String> getExecutedTools() {
        return Collections.unmodifiableList(executedTools);
    }

    public void addEvent(TraceEvent event) {
        events.add(event);
    }

    public void addExecutedTool(String toolName) {
        executedTools.add(toolName);
    }
}
