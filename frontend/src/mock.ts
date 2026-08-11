import type {
  AgentRun,
  EvaluationReport,
  PlanPreview,
  PlannerDescriptor,
  RunEvidence,
  TraceEvent,
} from './types'

const delay = (ms: number) => new Promise((resolve) => window.setTimeout(resolve, ms))
const storedRuns = new Map<string, AgentRun>()

const cityRows = [
  { CITY: '北京', TOTAL_AMOUNT: 200 },
  { CITY: '武汉', TOTAL_AMOUNT: 150 },
  { CITY: '成都', TOTAL_AMOUNT: 80 },
]

function event(sequence: number, type: string, message: string, data: Record<string, unknown> = {}): TraceEvent {
  return { sequence, type, message, occurredAt: new Date().toISOString(), data }
}

function addEvent(run: AgentRun, type: string, message: string, data: Record<string, unknown> = {}) {
  run.events.push(event(run.events.length + 1, type, message, data))
}

function isAmbiguous(input: string) {
  const broad = ['看看', '分析', '情况', '概况', '怎么样'].some((word) => input.includes(word))
  const metric = ['数量', '订单数', '金额', '平均', '状态', '字段', '表结构'].some((word) => input.includes(word))
  return input.includes('订单') && broad && !metric
}

function createPlan(input: string): PlanPreview {
  if (input.includes('结构') || input.includes('字段')) {
    return {
      interpretation: '查看指定数据表的结构',
      toolName: 'get_table_schema',
      arguments: { tableName: 'fact_order' },
      sourceTables: ['fact_order'],
      filters: [],
      sql: null,
      assumptions: ['仅查询元数据，不读取或修改业务数据'],
      riskLevel: 'LOW_READ_ONLY',
      rowLimit: 0,
    }
  }
  if (input.includes('搜索') || input.includes('有哪些')) {
    return {
      interpretation: '在操作数据前搜索元数据',
      toolName: 'search_metadata',
      arguments: { query: '订单' },
      sourceTables: ['metadata_catalog'],
      filters: [],
      sql: null,
      assumptions: ['仅查询元数据，不读取或修改业务数据'],
      riskLevel: 'LOW_READ_ONLY',
      rowLimit: 10,
    }
  }

  const cityAmount = input.includes('城市') && input.includes('金额')
  const statusCount = input.includes('状态')
  const average = input.includes('平均')
  const sql = cityAmount
    ? "SELECT u.city, SUM(o.order_amount) AS total_amount FROM fact_order o JOIN dim_user u ON u.user_id = o.user_id WHERE o.status = 'COMPLETED' GROUP BY u.city ORDER BY total_amount DESC"
    : statusCount
      ? 'SELECT status, COUNT(*) AS order_count FROM fact_order GROUP BY status ORDER BY status'
      : average
        ? "SELECT AVG(order_amount) AS average_amount FROM fact_order WHERE status = 'COMPLETED'"
        : "SELECT COUNT(*) AS completed_count FROM fact_order WHERE status = 'COMPLETED'"
  return {
    interpretation: cityAmount
      ? '按城市汇总已完成订单金额'
      : statusCount
        ? '按状态统计订单数量'
        : average
          ? '计算已完成订单的平均金额'
          : '统计已完成订单数量',
    toolName: 'run_readonly_sql',
    arguments: { sql },
    sourceTables: cityAmount ? ['fact_order', 'dim_user'] : ['fact_order'],
    filters: statusCount ? ['无筛选条件'] : ["status = 'COMPLETED'"],
    sql,
    assumptions: [
      ...(statusCount ? [] : ['将 COMPLETED 作为已完成订单口径']),
      ...(cityAmount || average ? ['金额指标使用 fact_order.order_amount'] : []),
      '仅执行单条只读 SELECT，结果最多返回 200 行',
    ],
    riskLevel: 'LOW_READ_ONLY',
    rowLimit: 200,
  }
}

function evidenceFor(plan: PlanPreview): RunEvidence {
  if (plan.toolName === 'get_table_schema') {
    const columns = [
      { COLUMN_NAME: 'ORDER_ID', DATA_TYPE: 'BIGINT', IS_NULLABLE: 'NO' },
      { COLUMN_NAME: 'USER_ID', DATA_TYPE: 'BIGINT', IS_NULLABLE: 'NO' },
      { COLUMN_NAME: 'ORDER_AMOUNT', DATA_TYPE: 'DECIMAL', IS_NULLABLE: 'NO' },
      { COLUMN_NAME: 'STATUS', DATA_TYPE: 'VARCHAR', IS_NULLABLE: 'NO' },
      { COLUMN_NAME: 'CREATED_AT', DATA_TYPE: 'TIMESTAMP', IS_NULLABLE: 'NO' },
    ]
    return { summary: '已读取 fact_order 的 5 个字段', sourceTables: plan.sourceTables, filters: [], sql: null, rowCount: 5, resultData: { columns } }
  }
  if (plan.toolName === 'search_metadata') {
    const rows = [
      { TABLE_NAME: 'fact_order', DISPLAY_NAME: '订单事实表', DESCRIPTION: '记录订单金额、状态和创建时间' },
    ]
    return { summary: '找到 1 条目录记录', sourceTables: plan.sourceTables, filters: [], sql: null, rowCount: 1, resultData: { rows } }
  }

  let rows: Record<string, unknown>[] = [{ COMPLETED_COUNT: 4 }]
  if (plan.interpretation.includes('city')) rows = cityRows
  if (plan.interpretation.includes('status')) rows = [
    { STATUS: 'CANCELLED', ORDER_COUNT: 1 },
    { STATUS: 'COMPLETED', ORDER_COUNT: 4 },
    { STATUS: 'PENDING', ORDER_COUNT: 1 },
  ]
  if (plan.interpretation.includes('average')) rows = [{ AVERAGE_AMOUNT: 107.5 }]
  return {
    summary: `查询返回 ${rows.length} 行`,
    sourceTables: plan.sourceTables,
    filters: plan.filters,
    sql: `${plan.sql} LIMIT 200`,
    rowCount: rows.length,
    resultData: { sql: `${plan.sql} LIMIT 200`, rows },
  }
}

function baseRun(input: string, plannerMode: string, parentRunId: string | null): AgentRun {
  return {
    id: crypto.randomUUID(),
    input,
    parentRunId,
    effectiveInput: input,
    createdAt: new Date().toISOString(),
    status: 'CREATED',
    plannerMode,
    plannerUsage: { promptVersion: 'offline-rules-v2', model: 'deterministic', inputTokens: 0, outputTokens: 0 },
    clarification: null,
    planPreview: null,
    evidence: null,
    feedback: null,
    output: null,
    error: null,
    durationMs: 0,
    executedTools: [],
    events: [],
  }
}

export async function mockPlanners(): Promise<PlannerDescriptor[]> {
  await delay(80)
  return [
    { mode: 'offline', promptVersion: 'offline-rules-v2', model: 'deterministic', ready: true },
    { mode: 'openai', promptVersion: 'data-planner-v1', model: 'gpt-4.1-mini', ready: false },
  ]
}

export async function mockPreviewRun(input: string, plannerMode: string, parentRunId: string | null): Promise<AgentRun> {
  if (plannerMode !== 'offline') throw new Error(`规划模式尚未配置：${plannerMode}`)
  await delay(260)
  const run = baseRun(input, plannerMode, parentRunId)
  addEvent(run, 'RUN_CREATED', '已接收分析请求', { mode: plannerMode, parentRunId })
  if (isAmbiguous(input)) {
    run.status = 'WAITING_FOR_CLARIFICATION'
    run.clarification = {
      question: '你想从哪个角度查看订单？先确认指标可以避免 Agent 自行猜测口径。',
      options: [
        { label: '已完成订单数量', resolvedInput: '统计已完成订单数量' },
        { label: '各城市已完成订单金额', resolvedInput: '统计各城市已完成订单金额' },
        { label: '已完成订单平均金额', resolvedInput: '统计已完成订单平均金额' },
        { label: '按状态统计订单数', resolvedInput: '按状态统计订单数' },
      ],
    }
    addEvent(run, 'CLARIFICATION_REQUIRED', run.clarification.question)
  } else {
    run.planPreview = createPlan(input)
    run.status = 'WAITING_FOR_APPROVAL'
    addEvent(run, 'PLANNING_STARTED', '规划器正在选择工具')
    addEvent(run, 'PLAN_CREATED', run.planPreview.interpretation, { tools: [run.planPreview.toolName] })
    addEvent(run, 'PLAN_REVIEW_REQUIRED', '等待用户确认后执行工具')
  }
  storedRuns.set(run.id, run)
  return run
}

export async function mockClarifyRun(id: string, resolvedInput: string): Promise<AgentRun> {
  await delay(180)
  const run = storedRuns.get(id)
  if (!run || run.status !== 'WAITING_FOR_CLARIFICATION') throw new Error('当前请求不在等待澄清状态')
  run.effectiveInput = resolvedInput
  addEvent(run, 'CLARIFICATION_RESOLVED', '用户已选择明确的分析目标', { effectiveInput: resolvedInput })
  run.planPreview = createPlan(resolvedInput)
  run.status = 'WAITING_FOR_APPROVAL'
  addEvent(run, 'PLANNING_STARTED', '规划器正在选择工具')
  addEvent(run, 'PLAN_CREATED', run.planPreview.interpretation, { tools: [run.planPreview.toolName] })
  addEvent(run, 'PLAN_REVIEW_REQUIRED', '等待用户确认后执行工具')
  return run
}

export async function mockApproveRun(id: string): Promise<AgentRun> {
  await delay(360)
  const run = storedRuns.get(id)
  if (!run || run.status !== 'WAITING_FOR_APPROVAL' || !run.planPreview) throw new Error('当前请求不在等待确认状态')
  addEvent(run, 'APPROVAL_RECEIVED', '用户已确认执行计划')
  run.status = 'RUNNING'
  addEvent(run, 'TOOL_STARTED', `Executing ${run.planPreview.toolName}`, { arguments: run.planPreview.arguments })
  run.evidence = evidenceFor(run.planPreview)
  run.executedTools = [run.planPreview.toolName]
  run.output = `${run.evidence.summary}\n${JSON.stringify(run.evidence.resultData)}`
  run.durationMs = 38
  addEvent(run, 'TOOL_SUCCEEDED', run.evidence.summary, { result: run.evidence.resultData })
  run.status = 'SUCCEEDED'
  addEvent(run, 'RUN_SUCCEEDED', '分析请求执行完成', { rowCount: run.evidence.rowCount })
  return run
}

export async function mockReviseRun(id: string, input: string): Promise<AgentRun> {
  const previous = storedRuns.get(id)
  if (!previous) throw new Error('找不到请求')
  addEvent(previous, 'REVISION_REQUESTED', '用户已修改分析请求', { revisedInput: input })
  return mockPreviewRun(input, previous.plannerMode, previous.id)
}

export async function mockFeedback(
  id: string,
  rating: 'UP' | 'DOWN',
  reason: string | null,
  comment: string | null,
): Promise<AgentRun> {
  await delay(100)
  const run = storedRuns.get(id)
  if (!run || !['SUCCEEDED', 'FAILED'].includes(run.status)) throw new Error('请求尚未结束')
  run.feedback = { rating, reason, comment, submittedAt: new Date().toISOString() }
  addEvent(run, 'FEEDBACK_RECORDED', '已记录用户反馈', { rating, reason })
  return run
}

export async function mockRun(input: string, plannerMode: string): Promise<AgentRun> {
  const preview = await mockPreviewRun(input, plannerMode, null)
  return preview.status === 'WAITING_FOR_APPROVAL' ? mockApproveRun(preview.id) : preview
}

export async function mockEvaluate(plannerMode: string): Promise<EvaluationReport> {
  if (plannerMode !== 'offline') throw new Error(`规划模式尚未配置：${plannerMode}`)
  await delay(700)
  const definitions = [
    ['metadata-order-cn', 'metadata', 'search_metadata'], ['metadata-user-cn', 'metadata', 'search_metadata'],
    ['metadata-order-en', 'metadata', 'search_metadata'], ['metadata-city-cn', 'metadata', 'search_metadata'],
    ['schema-order-cn', 'schema', 'get_table_schema'], ['schema-user-cn', 'schema', 'get_table_schema'],
    ['schema-order-en', 'schema', 'get_table_schema'], ['schema-user-en', 'schema', 'get_table_schema'],
    ['schema-order-amount', 'schema', 'get_table_schema'], ['schema-user-level', 'schema', 'get_table_schema'],
    ['aggregate-city-amount', 'sql_aggregation', 'run_readonly_sql'], ['aggregate-completed-count', 'sql_aggregation', 'run_readonly_sql'],
    ['aggregate-average-amount', 'sql_aggregation', 'run_readonly_sql'], ['aggregate-total-amount', 'sql_aggregation', 'run_readonly_sql'],
    ['aggregate-maximum-amount', 'sql_aggregation', 'run_readonly_sql'], ['aggregate-minimum-amount', 'sql_aggregation', 'run_readonly_sql'],
    ['aggregate-status-count', 'sql_aggregation', 'run_readonly_sql'], ['aggregate-user-city-count', 'sql_aggregation', 'run_readonly_sql'],
    ['aggregate-completed-city-count', 'sql_aggregation', 'run_readonly_sql'], ['aggregate-top-city', 'sql_aggregation', 'run_readonly_sql'],
    ['filter-wuhan-amount', 'sql_filtering', 'run_readonly_sql'], ['filter-high-value-count', 'sql_filtering', 'run_readonly_sql'],
    ['filter-pending-amount', 'sql_filtering', 'run_readonly_sql'], ['filter-order-status', 'sql_filtering', 'run_readonly_sql'],
  ]
  const cases = definitions.map(([caseId, category, actualTool], index) => ({
    caseId: String(caseId), category: String(category), passed: true, toolSelectionCorrect: true,
    actualTool: String(actualTool), latencyMs: 5 + (index % 7), failureReason: null,
  }))
  const categories = ['metadata', 'schema', 'sql_aggregation', 'sql_filtering'].map((category) => {
    const totalCases = cases.filter((item) => item.category === category).length
    return { category, totalCases, passedCases: totalCases, successRate: 1 }
  })
  return {
    mode: 'offline', promptVersion: 'offline-rules-v2', model: 'deterministic', generatedAt: new Date().toISOString(),
    totalCases: cases.length, passedCases: cases.length, taskSuccessRate: 1, toolSelectionAccuracy: 1,
    averageLatencyMs: 7.8, inputTokens: 0, outputTokens: 0, categories, cases,
  }
}
