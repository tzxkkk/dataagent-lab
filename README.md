# DataAgent Lab

DataAgent Lab is a reproducible Agent Harness for data-development workflows.
It focuses on controlled tool execution, safety, observability, and evaluation
rather than a private enterprise dataset or a specific model vendor.

![DataAgent Lab workflow result](docs/images/workflow-result.png)

## What is implemented

- Java 17 + Spring Boot 3 backend and Vue 3 + TypeScript frontend.
- Registry-based tools with JSON input schemas shared by the runtime and model
  prompt.
- Metadata search, table-schema inspection, and read-only SQL execution.
- JSqlParser validation, single-statement enforcement, and an automatic
  `LIMIT 200` guardrail.
- Explicit run states and structured Trace events from planning through tool
  execution.
- A user workflow that clarifies ambiguous requests, previews tables, filters,
  SQL, assumptions, and row limits, and executes only after approval.
- User-facing evidence cards with actual sources, filters, SQL, row count, and
  tabular results, plus parent-child revision branches and structured feedback.
- A deterministic offline planner and an OpenAI-compatible planner behind the
  same interface.
- A 24-case layered Golden Set covering metadata retrieval, Schema routing,
  SQL aggregation, and filtered queries, with category-level reporting.
- A synthetic order warehouse initialized in H2, so no private data or API key
  is required for the baseline.

## Repository structure

```text
dataagent-lab/
├── backend/              Spring Boot runtime, tools, planners, and tests
├── frontend/             Vue 3 workflow and evaluation console
├── docs/                 Design notes and screenshots
├── .github/workflows/    Backend and frontend CI
├── run-backend.cmd       Windows backend launcher
└── run-frontend.cmd      Windows frontend launcher
```

## Evaluation boundary

The offline planner currently passes 24/24 Golden Cases across four task
categories. This is a deterministic engineering baseline used to verify the
Harness, tools, SQL guardrails, Trace, and multi-fragment result assertions. It
is **not** presented as LLM effectiveness.

Comparable model results are produced only after a real OpenAI-compatible
endpoint is configured. Each report records the planner mode, model, Prompt
version, input/output tokens, latency, tool selection, and per-case failure
reason. The UI disables model mode while it is unconfigured and never generates
mock model scores.

## Run locally

Prerequisites: Java 17, Maven 3.9+, and Node.js 20+.

In the original workspace, the Windows scripts can use the bundled JDK 17 and
Maven 3.9.11 under `work/tools`. In a GitHub clone they fall back to Java and
Maven installed on your `PATH`, so the repository does not contain local tools:

```powershell
.\run-backend.cmd
.\run-frontend.cmd
```

```bash
cd backend
mvn test
mvn spring-boot:run
```

In another terminal:

```bash
cd frontend
npm install
```

PowerShell:

```powershell
$env:VITE_USE_MOCK='false'
npm run dev
```

The frontend is served at `http://127.0.0.1:4173` and proxies `/api` to the
backend at `http://127.0.0.1:8080`.

## Configure a model planner

The adapter targets the OpenAI-compatible `/chat/completions` contract and
expects JSON mode support.

```powershell
$env:AGENT_OPENAI_ENABLED='true'
$env:AGENT_OPENAI_BASE_URL='https://your-compatible-endpoint/v1'
$env:AGENT_OPENAI_API_KEY='your-key'
$env:AGENT_OPENAI_MODEL='your-model'
mvn -f backend/pom.xml spring-boot:run
```

No key is committed or persisted by the application.

## API examples

The interactive workflow creates a preview first. The returned run is either
`WAITING_FOR_CLARIFICATION` or `WAITING_FOR_APPROVAL`; no tool has executed yet.

```http
POST /api/runs/preview
Content-Type: application/json

{"input":"帮我看看订单情况","plannerMode":"offline","parentRunId":null}
```

```http
POST /api/runs/{id}/clarify
POST /api/runs/{id}/approve
POST /api/runs/{id}/revise
POST /api/runs/{id}/feedback
```

The direct endpoint remains available for deterministic Golden Set execution:

```http
POST /api/runs
Content-Type: application/json

{"input":"统计各城市已完成订单金额","plannerMode":"offline"}
```

```http
GET /api/planners
POST /api/evaluations/offline
POST /api/evaluations/openai
```

See [docs/PROJECT_BRIEF.md](docs/PROJECT_BRIEF.md) for product boundaries,
architecture decisions, and evaluation design.
