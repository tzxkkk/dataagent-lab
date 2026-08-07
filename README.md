# DataAgent Lab

DataAgent Lab 是一个面向数据开发工作流的可复现智能体执行框架。
它专注于受控的工具调用、安全性、可观测性和评测，而不依赖私有企业数据集或特定模型供应商。

![DataAgent Lab 工作流结果](docs/images/workflow-result.png)

## 已实现功能

- 后端采用 Java 17 + Spring Boot 3，前端采用 Vue 3 + TypeScript。
- 基于注册表管理工具，运行时与模型提示词共享 JSON 输入模式。
- 支持元数据搜索、表结构查看和只读 SQL 执行。
- 使用 JSqlParser 进行校验，强制执行单条语句，并自动添加 `LIMIT 200` 防护。
- 从规划到工具执行均使用明确的运行状态和结构化 Trace 事件。
- 用户工作流会澄清含糊请求，预览数据表、筛选条件、SQL、假设和行数限制，并且只在用户批准后执行。
- 面向用户的证据卡片展示实际数据源、筛选条件、SQL、行数和表格结果，同时支持父子修订分支和结构化反馈。
- 确定性离线规划器和 OpenAI 兼容规划器使用同一套接口。
- 分层 Golden Set 包含 24 个用例，覆盖元数据检索、Schema 路由、SQL 聚合和筛选查询，并提供分类报告。
- 在 H2 中初始化合成订单数据仓库，因此运行基线不需要私有数据或 API 密钥。

## 仓库结构

```text
dataagent-lab/
├── backend/              Spring Boot 运行时、工具、规划器和测试
├── frontend/             Vue 3 工作流和评测控制台
├── docs/                 设计说明和截图
├── .github/workflows/    后端和前端 CI
├── run-backend.cmd       Windows 后端启动脚本
└── run-frontend.cmd      Windows 前端启动脚本
```

## 评测边界

离线规划器目前在四类任务的 24 个 Golden Case 中通过 24/24。这个 24/24 只是确定性工程基线，用于验证工具调用、SQL 防护、Trace 和结果断言。不能将其描述为 LLM 效果或模型准确率。

只有配置真实的 OpenAI 兼容模型端点后，才能生成可比较的模型结果。每份报告都会记录规划器模式、模型、Prompt 版本、输入/输出 Token 数、延迟、工具选择以及每个用例的失败原因。未配置真实模型端点时，前端会禁用模型模式，不生成虚假分数。

## 本地运行

前置条件：Java 17、Maven 3.9+ 和 Node.js 20+。

在原始工作区中，Windows 脚本可以使用 `work/tools` 下随附的 JDK 17 和 Maven 3.9.11。在 GitHub 克隆的仓库中，脚本会回退到 `PATH` 中安装的 Java 和 Maven，因此仓库不包含本地工具：

```powershell
.\run-backend.cmd
.\run-frontend.cmd
```

```bash
cd backend
mvn test
mvn spring-boot:run
```

在另一个终端中执行：

```bash
cd frontend
npm install
```

PowerShell：

```powershell
$env:VITE_USE_MOCK='false'
npm run dev
```

前端运行在 `http://127.0.0.1:4173`，并将 `/api` 代理到运行在 `http://127.0.0.1:8080` 的后端。

## 配置模型规划器

适配器使用 OpenAI 兼容的 `/chat/completions` 协议，并要求端点支持 JSON 模式。

```powershell
$env:AGENT_OPENAI_ENABLED='true'
$env:AGENT_OPENAI_BASE_URL='https://your-compatible-endpoint/v1'
$env:AGENT_OPENAI_API_KEY='your-key'
$env:AGENT_OPENAI_MODEL='your-model'
mvn -f backend/pom.xml spring-boot:run
```

应用不会提交或持久化任何密钥。

## API 示例

交互式工作流会先创建预览。返回的运行状态为 `WAITING_FOR_CLARIFICATION` 或 `WAITING_FOR_APPROVAL`，此时尚未执行任何工具。

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

直接端点仍可用于执行确定性 Golden Set：

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

产品边界、架构决策和评测设计请参阅 [docs/PROJECT_BRIEF.md](docs/PROJECT_BRIEF.md)。
