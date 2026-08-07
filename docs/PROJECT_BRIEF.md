# Project Brief

## User problem

Data developers move repeatedly between metadata discovery, schema inspection,
SQL authoring, validation, and task execution. A useful Agent must do more than
generate text: it needs controlled tools, observable execution, deterministic
failure handling, and an evaluation loop. It also cannot assume that a user
knows the exact metric or will trust an opaque result.

## Product boundary

The first version supports three representative tasks:

1. Search the data catalog.
2. Inspect a table schema.
3. Execute one read-only analytical query.

It does not attempt to reproduce a full warehouse, scheduler, or BI product.
Those systems are represented by stable tool contracts that can later point to
MySQL, Hive, or HTTP services.

## Harness design

The runtime separates decision-making from execution:

```text
User request
    -> clarify an ambiguous metric when needed
    -> PlannerRegistry (offline | openai)
    -> validated AgentPlan + user-visible preview
    -> user approval or revision branch
    -> ToolRegistry + guarded execution
    -> evidence card for users + Trace for developers
    -> optional feedback + Golden Case assertions
```

The planner receives the same registered tool names and JSON input schemas used
by the executor. A plan is accepted only when it contains exactly one known
tool and usage metadata. Tool or parser failures become explicit failed runs
instead of unstructured exceptions.

## User workflow

The interactive API deliberately separates planning from execution:

1. `POST /api/runs/preview` returns a clarification prompt for broad requests
   such as “帮我看看订单情况”, rather than choosing an arbitrary metric.
2. A concrete request produces a Plan Preview with the interpretation, tool,
   source tables, filters, SQL, assumptions, risk level, and row limit.
3. Only `POST /api/runs/{id}/approve` executes the pending plan. Repeated or
   out-of-order approvals are rejected by the state machine.
4. A successful run produces separate Evidence containing the actual source
   tables, filters, executed SQL, row count, and structured result data.
5. `revise` creates a new Run with `parentRunId` instead of overwriting the
   original, so users can correct a filter or metric while retaining history.
6. Optional feedback records up/down rating, a structured reason, and a comment.
   Feedback is an input to evaluation, not the only signal of Agent quality.

The deterministic clarifier currently covers an intentionally narrow set of
ambiguous order questions. It demonstrates the workflow without claiming an
unimplemented general intent-understanding capability.

The current OpenAI-compatible adapter uses Prompt version `data-planner-v1` and
records model name plus input/output tokens on every run. Its contract is tested
against a local fake HTTP server for valid output, unknown tools, and malformed
JSON.

## Why synthetic data is sufficient

This project evaluates Agent engineering, not the commercial value of a private
dataset. A small order-domain warehouse provides deterministic expected
answers, repeatable safety cases, no privacy dependency, and fast local tests.
The data can be regenerated on each startup.

## Safety boundary

`run_readonly_sql` parses SQL before execution and enforces:

- exactly one statement and no semicolon;
- `SELECT` statements only;
- rejection of unsafe clauses such as `FOR UPDATE` and `INTO OUTFILE`;
- automatic `LIMIT 200` when no numeric limit is present.

These checks are application guardrails, not a replacement for database-level
least-privilege credentials in production.

## Evaluation design

The 24 Golden Cases are split into metadata retrieval (4), Schema routing (6),
SQL aggregation (10), and filtered queries (4). Every case asserts run success,
expected tool selection, and one or more deterministic output fragments. A
multi-row aggregation must satisfy all configured fragments rather than pass on
one coincidental value.

Reported metrics are task success rate, tool-selection accuracy, end-to-end
latency, Prompt version, model identity, input/output tokens, and per-case
failure reason.

The offline 24/24 result is intentionally labeled a deterministic baseline. It
validates orchestration and assertions but says nothing about LLM generalization.
A model comparison is valid only when a real endpoint runs the same case set and
the resulting report is retained with its model and Prompt metadata.

## Current limitations

- Runs are stored in memory rather than a durable database.
- Feedback is recorded per Run but no aggregate analytics or implicit behavior
  metrics are persisted yet.
- The planner selects one tool per request; multi-step planning is a future
  experiment, not an implied current capability.
- The 24-case Golden Set is still domain-specific and is not a statistical
  benchmark for general model capability.
- Authentication, tenant isolation, retries, and rate limiting are outside the
  local-lab scope.
