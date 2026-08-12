package com.dataagent.lab.repository;

import com.dataagent.lab.domain.AgentPlan;
import com.dataagent.lab.domain.AgentRun;
import com.dataagent.lab.domain.ClarificationPrompt;
import com.dataagent.lab.domain.PlanPreview;
import com.dataagent.lab.domain.PlannerUsage;
import com.dataagent.lab.domain.RunEvidence;
import com.dataagent.lab.domain.RunFeedback;
import com.dataagent.lab.domain.RunStatus;
import com.dataagent.lab.domain.TraceEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AgentRunRepository {
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> DATA_MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentRunRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(AgentRun run) {
        // 保存当前 Run 快照；首次保存走 INSERT，之后的状态变化走 UPDATE。
        Instant updatedAt = Instant.now();
        int updated = jdbcTemplate.update(
                "UPDATE agent_run SET input_text = ?, parent_run_id = ?, updated_at = ?, status = ?, "
                        + "effective_input = ?, planner_mode = ?, planner_usage_json = ?, clarification_json = ?, "
                        + "plan_preview_json = ?, evidence_json = ?, executed_tools_json = ?, output_text = ?, "
                        + "error_text = ?, duration_ms = ? WHERE run_id = ?",
                run.getInput(),
                run.getParentRunId(),
                Timestamp.from(updatedAt),
                run.getStatus().name(),
                run.getEffectiveInput(),
                run.getPlannerMode(),
                writeNullable(run.getPlannerUsage()),
                writeNullable(run.getClarification()),
                writeNullable(run.getPlanPreview()),
                writeNullable(run.getEvidence()),
                write(run.getExecutedTools()),
                run.getOutput(),
                run.getError(),
                run.getDurationMs(),
                run.getId()
        );
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO agent_run(run_id, input_text, parent_run_id, created_at, updated_at, status, "
                            + "effective_input, planner_mode, planner_usage_json, clarification_json, "
                            + "plan_preview_json, evidence_json, executed_tools_json, output_text, error_text, "
                            + "duration_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    run.getId(),
                    run.getInput(),
                    run.getParentRunId(),
                    Timestamp.from(run.getCreatedAt()),
                    Timestamp.from(updatedAt),
                    run.getStatus().name(),
                    run.getEffectiveInput(),
                    run.getPlannerMode(),
                    writeNullable(run.getPlannerUsage()),
                    writeNullable(run.getClarification()),
                    writeNullable(run.getPlanPreview()),
                    writeNullable(run.getEvidence()),
                    write(run.getExecutedTools()),
                    run.getOutput(),
                    run.getError(),
                    run.getDurationMs()
            );
        }
        saveFeedback(run);
    }

    public Optional<AgentRun> findById(String id) {
        List<AgentRun> runs = jdbcTemplate.query(
                "SELECT run_id, input_text, parent_run_id, created_at, status, effective_input, planner_mode, "
                        + "planner_usage_json, clarification_json, plan_preview_json, evidence_json, "
                        + "executed_tools_json, output_text, error_text, duration_ms "
                        + "FROM agent_run WHERE run_id = ?",
                (resultSet, rowNumber) -> {
                    AgentRun run = new AgentRun(
                            resultSet.getString("run_id"),
                            resultSet.getString("input_text"),
                            resultSet.getString("parent_run_id"),
                            resultSet.getTimestamp("created_at").toInstant()
                    );
                    run.setStatus(RunStatus.valueOf(resultSet.getString("status")));
                    run.setEffectiveInput(resultSet.getString("effective_input"));
                    run.setPlannerMode(resultSet.getString("planner_mode"));
                    run.setPlannerUsage(readNullable(resultSet.getString("planner_usage_json"), PlannerUsage.class));
                    run.setClarification(readNullable(
                            resultSet.getString("clarification_json"), ClarificationPrompt.class
                    ));
                    run.setPlanPreview(readNullable(resultSet.getString("plan_preview_json"), PlanPreview.class));
                    run.setEvidence(readNullable(resultSet.getString("evidence_json"), RunEvidence.class));
                    run.setOutput(resultSet.getString("output_text"));
                    run.setError(resultSet.getString("error_text"));
                    run.setDurationMs(resultSet.getLong("duration_ms"));
                    read(resultSet.getString("executed_tools_json"), STRING_LIST_TYPE)
                            .forEach(run::addExecutedTool);
                    return run;
                },
                id
        );
        if (runs.isEmpty()) {
            return Optional.empty();
        }

        AgentRun run = runs.get(0);
        jdbcTemplate.query(
                "SELECT sequence_number, event_type, message_text, occurred_at, data_json "
                        + "FROM agent_trace_event WHERE run_id = ? ORDER BY sequence_number",
                (RowCallbackHandler) resultSet -> run.addEvent(new TraceEvent(
                        resultSet.getInt("sequence_number"),
                        resultSet.getString("event_type"),
                        resultSet.getString("message_text"),
                        resultSet.getTimestamp("occurred_at").toInstant(),
                        read(resultSet.getString("data_json"), DATA_MAP_TYPE)
                )),
                id
        );
        jdbcTemplate.query(
                "SELECT rating, reason, comment_text, submitted_at FROM agent_run_feedback WHERE run_id = ?",
                resultSet -> {
                    if (resultSet.next()) {
                        run.setFeedback(new RunFeedback(
                                resultSet.getString("rating"),
                                resultSet.getString("reason"),
                                resultSet.getString("comment_text"),
                                resultSet.getTimestamp("submitted_at").toInstant()
                        ));
                    }
                    return null;
                },
                id
        );
        return Optional.of(run);
    }

    public void appendEvent(String runId, TraceEvent event) {
        // Trace 使用追加写，保留每一步发生时的类型、消息、时间和结构化数据。
        jdbcTemplate.update(
                "INSERT INTO agent_trace_event(run_id, sequence_number, event_type, message_text, occurred_at, "
                        + "data_json) VALUES (?, ?, ?, ?, ?, ?)",
                runId,
                event.sequence(),
                event.type(),
                event.message(),
                Timestamp.from(event.occurredAt()),
                write(event.data())
        );
    }

    public void savePendingPlan(String runId, AgentPlan plan) {
        // 待审批计划独立持久化，审批接口只凭 runId 读取，防止客户端篡改预览后的参数。
        int updated = jdbcTemplate.update(
                "UPDATE agent_pending_plan SET plan_json = ?, created_at = ? WHERE run_id = ?",
                write(plan), Timestamp.from(Instant.now()), runId
        );
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO agent_pending_plan(run_id, plan_json, created_at) VALUES (?, ?, ?)",
                    runId, write(plan), Timestamp.from(Instant.now())
            );
        }
    }

    public Optional<AgentPlan> findPendingPlan(String runId) {
        List<AgentPlan> plans = jdbcTemplate.query(
                "SELECT plan_json FROM agent_pending_plan WHERE run_id = ?",
                (resultSet, rowNumber) -> read(resultSet.getString("plan_json"), AgentPlan.class),
                runId
        );
        return plans.stream().findFirst();
    }

    public void deletePendingPlan(String runId) {
        jdbcTemplate.update("DELETE FROM agent_pending_plan WHERE run_id = ?", runId);
    }

    private void saveFeedback(AgentRun run) {
        RunFeedback feedback = run.getFeedback();
        if (feedback == null) {
            return;
        }
        int updated = jdbcTemplate.update(
                "UPDATE agent_run_feedback SET rating = ?, reason = ?, comment_text = ?, submitted_at = ? "
                        + "WHERE run_id = ?",
                feedback.rating(), feedback.reason(), feedback.comment(), Timestamp.from(feedback.submittedAt()),
                run.getId()
        );
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO agent_run_feedback(run_id, rating, reason, comment_text, submitted_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    run.getId(), feedback.rating(), feedback.reason(), feedback.comment(),
                    Timestamp.from(feedback.submittedAt())
            );
        }
    }

    private String writeNullable(Object value) {
        return value == null ? null : write(value);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化持久化的 Agent 请求数据", exception);
        }
    }

    private <T> T readNullable(String json, Class<T> type) {
        return json == null || json.isBlank() ? null : read(json, type);
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法反序列化持久化的 Agent 请求数据", exception);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法反序列化持久化的 Agent 请求数据", exception);
        }
    }
}
