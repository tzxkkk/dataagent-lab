# Project Brief

## User problem

Data developers move repeatedly between metadata discovery, schema inspection,
SQL authoring, validation, and task execution. A useful Agent must do more than
generate text: it needs controlled tools, observable execution, deterministic
failure handling, and an evaluation loop. It also cannot assume that a user
knows the exact metric or will trust an opaque result.

## Product boundary

The current version supports five representative tasks:

1. Search physical-table and logical-dataset catalogs.
2. Resolve logical datasets to maintained physical partitions.
3. Load maintained field semantics, dataset relationships, and physical schemas.
4. Ask a model-generated clarification when the request is materially ambiguous.
5. Submit and execute one guarded read-only analytical query after approval.

It does not attempt to reproduce a full warehouse, scheduler, or BI product.
The local runtime now executes against MySQL through stable tool contracts that
can later point to a governed warehouse, Hive, or HTTP services.

## Harness design

The runtime separates decision-making from execution:

```text
User request
    -> PlannerRegistry (offline | openai)
    -> model clarification, or bounded inspect-tool loop
    -> validated AgentPlan + user-visible preview
    -> user approval or revision branch
    -> ToolRegistry + guarded execution
    -> evidence card for users + Trace for developers
    -> optional feedback + Golden Case assertions
```

The planner receives the same registered tool names and JSON input schemas used
by the executor. During model planning it may call only allow-listed metadata,
dataset-context, partition-resolution, and schema tools. Their results are fed
back into the next model turn and recorded in Trace. Planning is bounded by a
maximum step count. A final plan is accepted only when it contains exactly one
known tool, schema-valid arguments, and usage metadata. The final tool is not
executed until approval.

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

Run snapshots, ordered Trace events, executed tools, evidence, feedback, and
pending approval plans are persisted in MySQL. An application restart can load
an existing Run through the same API and can continue a previously pending
approval without asking the model to plan again.

The deterministic clarifier remains only for the offline regression planner.
The model planner can return a structured clarification question with two to
five complete request options, so the interactive model path no longer relies
on the order-specific clarification rules.

The current OpenAI-compatible adapter uses Prompt version `data-planner-v2` and
records cumulative model input/output tokens across all planning turns. It can
read a local API-key file without returning the secret through APIs or Trace.
Its protocol is tested against a local HTTP server for clarification, inspection
loops, final tool selection, unknown tools, and malformed JSON.

## Why MySQL still uses synthetic data

This project evaluates Agent engineering, not the commercial value of a private
dataset. A small order-domain warehouse provides deterministic expected
answers, repeatable safety cases, and no privacy dependency. The interactive
runtime uses a local MySQL database, while isolated automated tests use H2 in
MySQL compatibility mode. Startup initialization is idempotent and does not
drop existing MySQL tables.

## Safety boundary

`run_readonly_sql` parses SQL before execution and enforces:

- exactly one statement and no semicolon;
- `SELECT` statements only;
- rejection of unsafe clauses such as `FOR UPDATE` and `INTO OUTFILE`;
- rejection of reads from internal Run, Trace, feedback, and pending-plan tables;
- allow-list enforcement against the business metadata catalog and rejection of
  explicit cross-schema queries;
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

- Feedback is recorded per Run but no aggregate analytics or implicit behavior
  metrics are persisted yet.
- Model planning is bounded and sequential; it does not yet support parallel
  tool calls, durable conversation memory, or automatic retry/backoff policy.
- Cross-partition SQL can be generated as one `SELECT` containing `UNION ALL`,
  but there is no distributed query engine or production shard middleware.
- Local MySQL startup uses one credential for schema initialization and query
  execution; production should separate migrations from a least-privilege,
  read-only Agent account.
- The local runtime uses an in-process cache over the MySQL repository and does
  not yet implement optimistic locking for concurrent writes from multiple
  application replicas.
- The 24-case Golden Set is still domain-specific and is not a statistical
  benchmark for general model capability.
- Authentication, tenant isolation, retries, and rate limiting are outside the
  local-lab scope.
