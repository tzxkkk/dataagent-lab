<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  Activity,
  Beaker,
  Check,
  ChevronRight,
  CircleAlert,
  Clock3,
  Cpu,
  Database,
  Eye,
  FlaskConical,
  Gauge,
  ListTree,
  MessageCircleQuestion,
  PencilLine,
  Play,
  Search,
  ShieldCheck,
  TerminalSquare,
  ThumbsDown,
  ThumbsUp,
  Wrench,
  X,
} from 'lucide-vue-next'
import {
  approveRun,
  clarifyRun,
  listPlanners,
  previewRun,
  reviseRun,
  runEvaluation,
  submitFeedback,
} from './api'
import type { AgentRun, EvaluationReport, PlannerDescriptor, TraceEvent } from './types'

type ViewName = 'run' | 'evaluation'

const examples = [
  { label: '模糊订单问题', prompt: '帮我看看订单情况', icon: MessageCircleQuestion },
  { label: '用户城市订单金额', prompt: '按下单用户所在城市统计已完成订单金额', icon: Gauge },
  { label: '跨月分表查询', prompt: '按成交店铺所在城市统计 2026 年 7 月到 8 月已完成订单金额', icon: Database },
  { label: '订单表结构', prompt: '查看订单事实表字段', icon: ListTree },
  { label: '搜索元数据', prompt: '搜索订单主题数据表', icon: Search },
]

const feedbackReasons = [
  { value: 'WRONG_TABLE', label: '选错数据表' },
  { value: 'WRONG_METRIC', label: '指标口径不对' },
  { value: 'MISSING_FILTER', label: '缺少筛选条件' },
  { value: 'SQL_ERROR', label: 'SQL 有问题' },
  { value: 'BAD_FORMAT', label: '结果形式不合适' },
]

const view = ref<ViewName>('run')
const input = ref(examples[0].prompt)
const busy = ref(false)
const evaluating = ref(false)
const revising = ref(false)
const showNegativeFeedback = ref(false)
const feedbackReason = ref('')
const feedbackComment = ref('')
const run = ref<AgentRun | null>(null)
const report = ref<EvaluationReport | null>(null)
const error = ref('')
const selectedPlanner = ref('openai')
const evaluationPlanner = ref('offline')
const planners = ref<PlannerDescriptor[]>([
  { mode: 'offline', promptVersion: 'offline-rules-v2', model: 'deterministic', ready: true },
  { mode: 'openai', promptVersion: 'data-planner-v1', model: 'unconfigured', ready: false },
])

const selectedDescriptor = computed(() => planners.value.find((item) => item.mode === selectedPlanner.value))
const runtimeReady = computed(() => selectedDescriptor.value?.ready ?? false)
const resultRows = computed<Record<string, unknown>[]>(() => {
  const data = run.value?.evidence?.resultData
  const rows = data?.rows ?? data?.columns
  if (!Array.isArray(rows)) return []
  return rows.filter((item): item is Record<string, unknown> => typeof item === 'object' && item !== null)
})
const resultColumns = computed(() => resultRows.value.length > 0 ? Object.keys(resultRows.value[0]) : [])

onMounted(async () => {
  try {
    planners.value = await listPlanners()
    const readyModelPlanner = planners.value.find((planner) => planner.mode === 'openai' && planner.ready)
    selectedPlanner.value = readyModelPlanner?.mode ?? 'offline'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Planner 列表加载失败'
  }
})

async function perform(task: () => Promise<AgentRun>, fallbackMessage: string) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    run.value = await task()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : fallbackMessage
  } finally {
    busy.value = false
  }
}

async function generatePlan() {
  if (!input.value.trim()) return
  revising.value = false
  showNegativeFeedback.value = false
  await perform(() => previewRun(input.value.trim(), selectedPlanner.value), '计划生成失败')
}

async function chooseClarification(resolvedInput: string) {
  if (!run.value) return
  input.value = resolvedInput
  await perform(() => clarifyRun(run.value!.id, resolvedInput), '澄清请求失败')
}

async function approve() {
  if (!run.value) return
  await perform(() => approveRun(run.value!.id), '执行计划失败')
}

function openRevision() {
  if (!run.value) return
  input.value = run.value.effectiveInput
  revising.value = true
}

async function applyRevision() {
  if (!run.value || !input.value.trim()) return
  const parentId = run.value.id
  await perform(() => reviseRun(parentId, input.value.trim()), '修改计划失败')
  revising.value = false
  showNegativeFeedback.value = false
}

async function sendPositiveFeedback() {
  if (!run.value) return
  await perform(() => submitFeedback(run.value!.id, 'UP', null, null), '反馈提交失败')
}

async function sendNegativeFeedback() {
  if (!run.value || !feedbackReason.value) return
  await perform(
    () => submitFeedback(run.value!.id, 'DOWN', feedbackReason.value, feedbackComment.value.trim() || null),
    '反馈提交失败',
  )
  showNegativeFeedback.value = false
}

async function evaluate() {
  if (evaluating.value) return
  evaluating.value = true
  error.value = ''
  try {
    report.value = await runEvaluation(evaluationPlanner.value)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '评测失败'
  } finally {
    evaluating.value = false
  }
}

function selectExample(prompt: string) {
  input.value = prompt
  run.value = null
  error.value = ''
  revising.value = false
  view.value = 'run'
}

function selectRunPlanner(mode: string) {
  selectedPlanner.value = mode
  run.value = null
  error.value = ''
}

function selectEvaluationPlanner(mode: string) {
  evaluationPlanner.value = mode
  report.value = null
  error.value = ''
}

function plannerLabel(mode: string) {
  return mode === 'offline' ? '离线基线' : '模型 Planner'
}

function categoryLabel(category: string) {
  const labels: Record<string, string> = {
    metadata: '元数据检索', schema: 'Schema 路由', sql_aggregation: 'SQL 聚合', sql_filtering: '条件查询',
  }
  return labels[category] ?? category
}

function statusLabel(status: AgentRun['status']) {
  const labels: Record<AgentRun['status'], string> = {
    CREATED: '已创建', PLANNING: '规划中', WAITING_FOR_CLARIFICATION: '等待澄清',
    WAITING_FOR_APPROVAL: '等待确认', RUNNING: '执行中', SUCCEEDED: '已完成', FAILED: '失败',
  }
  return labels[status]
}

function displayValue(value: unknown) {
  return typeof value === 'object' && value !== null ? JSON.stringify(value) : String(value ?? '')
}

function eventIcon(traceEvent: TraceEvent) {
  if (traceEvent.type.includes('FAILED')) return CircleAlert
  if (traceEvent.type.includes('TOOL')) return Wrench
  if (traceEvent.type.includes('PLAN')) return ListTree
  if (traceEvent.type.includes('CLARIFICATION')) return MessageCircleQuestion
  return Check
}
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand-lockup">
        <div class="brand-mark"><TerminalSquare :size="19" /></div>
        <div><strong>DataAgent Lab</strong><span>user-centered agent harness</span></div>
      </div>
      <nav class="view-switch" aria-label="视图切换">
        <button :class="{ active: view === 'run' }" @click="view = 'run'"><Activity :size="16" />工作流</button>
        <button :class="{ active: view === 'evaluation' }" @click="view = 'evaluation'"><FlaskConical :size="16" />评测</button>
      </nav>
      <div class="runtime-status" :class="{ unavailable: !runtimeReady }">
        <span></span>{{ runtimeReady ? 'Harness ready' : 'Planner unavailable' }}
      </div>
    </header>

    <main v-if="view === 'run'" class="workspace">
      <aside class="scenario-rail">
        <div class="section-label">场景</div>
        <button
          v-for="example in examples"
          :key="example.label"
          class="scenario-button"
          :class="{ selected: input === example.prompt }"
          @click="selectExample(example.prompt)"
        >
          <component :is="example.icon" :size="17" /><span>{{ example.label }}</span><ChevronRight :size="15" />
        </button>
        <div class="rail-meta">
          <div><MessageCircleQuestion :size="16" />模糊意图先澄清</div>
          <div><Eye :size="16" />执行前预览计划</div>
          <div><ShieldCheck :size="16" />只读 SQL 防护</div>
          <div><Database :size="16" />结果附数据证据</div>
        </div>
      </aside>

      <section class="run-console">
        <div class="console-heading">
          <div><span class="section-label">User Workflow</span><h1>数据分析请求</h1></div>
          <span v-if="run" class="run-id">{{ run.id.slice(0, 8) }}</span>
        </div>

        <div class="planner-bar">
          <span>Planner</span>
          <div class="mode-switch" role="group" aria-label="运行 Planner">
            <button
              v-for="planner in planners"
              :key="planner.mode"
              :class="{ active: selectedPlanner === planner.mode }"
              :disabled="!planner.ready || busy"
              :title="planner.ready ? `${planner.model} · ${planner.promptVersion}` : '未配置兼容模型端点'"
              @click="selectRunPlanner(planner.mode)"
            >
              <TerminalSquare v-if="planner.mode === 'offline'" :size="15" /><Cpu v-else :size="15" />
              {{ plannerLabel(planner.mode) }}
            </button>
          </div>
        </div>

        <div class="prompt-box">
          <textarea v-model="input" aria-label="分析问题" rows="3" @keydown.ctrl.enter.prevent="generatePlan"></textarea>
          <button class="run-button" :disabled="busy || !input.trim()" @click="revising ? applyRevision() : generatePlan()">
            <Clock3 v-if="busy" :size="18" class="spin" /><PencilLine v-else-if="revising" :size="17" /><Play v-else :size="18" />
            {{ busy ? '处理中' : revising ? '重新生成计划' : '生成计划' }}
          </button>
        </div>
        <div v-if="error" class="error-banner"><CircleAlert :size="17" />{{ error }}</div>

        <section v-if="run?.status === 'WAITING_FOR_CLARIFICATION' && run.clarification" class="workflow-card clarification-card">
          <div class="card-kicker"><MessageCircleQuestion :size="16" />需要你确认</div>
          <h2>{{ run.clarification.question }}</h2>
          <p>当前问题缺少明确指标，Agent 不会直接猜一个口径执行。</p>
          <div class="option-grid">
            <button v-for="option in run.clarification.options" :key="option.label" :disabled="busy" @click="chooseClarification(option.resolvedInput)">
              {{ option.label }}<ChevronRight :size="15" />
            </button>
          </div>
        </section>

        <section v-if="run?.status === 'WAITING_FOR_APPROVAL' && run.planPreview" class="workflow-card plan-card">
          <div class="card-title-row">
            <div><span class="card-kicker"><Eye :size="16" />执行前预览</span><h2>{{ run.planPreview.interpretation }}</h2></div>
            <span class="risk-badge">{{ run.planPreview.riskLevel }}</span>
          </div>
          <div class="plan-grid">
            <div><span>使用工具</span><code>{{ run.planPreview.toolName }}</code></div>
            <div><span>数据来源</span><strong>{{ run.planPreview.sourceTables.join('、') }}</strong></div>
            <div><span>筛选条件</span><strong>{{ run.planPreview.filters.join('；') || '无' }}</strong></div>
            <div><span>返回上限</span><strong>{{ run.planPreview.rowLimit || '不适用' }}</strong></div>
          </div>
          <div v-if="run.planPreview.sql" class="sql-preview"><span>将要执行的 SQL</span><pre>{{ run.planPreview.sql }}</pre></div>
          <div class="assumption-list">
            <span>当前口径与约束</span>
            <ul><li v-for="item in run.planPreview.assumptions" :key="item">{{ item }}</li></ul>
          </div>
          <div class="card-actions">
            <button class="secondary-button" @click="openRevision"><PencilLine :size="16" />修改问题</button>
            <button class="run-button" :disabled="busy" @click="approve"><ShieldCheck :size="17" />确认并执行</button>
          </div>
        </section>

        <section v-if="run?.status === 'SUCCEEDED' && run.evidence" class="workflow-card evidence-card">
          <div class="card-title-row">
            <div><span class="card-kicker"><Check :size="16" />执行完成</span><h2>{{ run.evidence.summary }}</h2></div>
            <span class="success-badge">{{ run.durationMs }} ms</span>
          </div>
          <div class="evidence-strip">
            <div><span>数据来源</span><strong>{{ run.evidence.sourceTables.join('、') }}</strong></div>
            <div><span>实际筛选</span><strong>{{ run.evidence.filters.join('；') || '无' }}</strong></div>
            <div><span>返回行数</span><strong>{{ run.evidence.rowCount }}</strong></div>
            <div><span>执行工具</span><code>{{ run.executedTools[0] }}</code></div>
          </div>
          <div v-if="resultRows.length" class="result-table-wrap">
            <table>
              <thead><tr><th v-for="column in resultColumns" :key="column">{{ column }}</th></tr></thead>
              <tbody>
                <tr v-for="(row, index) in resultRows" :key="index">
                  <td v-for="column in resultColumns" :key="column">{{ displayValue(row[column]) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <details v-if="run.evidence.sql" class="evidence-details">
            <summary>查看实际执行 SQL</summary><pre>{{ run.evidence.sql }}</pre>
          </details>
          <div class="result-actions">
            <div>
              <strong>结果和你的目标一致吗？</strong>
              <span>反馈可选，不影响继续使用</span>
            </div>
            <div v-if="!run.feedback" class="feedback-buttons">
              <button title="结果有用" @click="sendPositiveFeedback"><ThumbsUp :size="16" /></button>
              <button title="结果需改进" @click="showNegativeFeedback = true"><ThumbsDown :size="16" /></button>
            </div>
            <span v-else class="feedback-saved"><Check :size="15" />已记录反馈</span>
            <button class="secondary-button" @click="openRevision"><PencilLine :size="16" />修正条件或继续追问</button>
          </div>
          <div v-if="showNegativeFeedback && !run.feedback" class="feedback-panel">
            <span>主要问题是什么？</span>
            <div class="reason-options">
              <button v-for="reason in feedbackReasons" :key="reason.value" :class="{ active: feedbackReason === reason.value }" @click="feedbackReason = reason.value">
                {{ reason.label }}
              </button>
            </div>
            <input v-model="feedbackComment" placeholder="补充说明（可选）" />
            <button class="run-button" :disabled="!feedbackReason || busy" @click="sendNegativeFeedback">提交反馈</button>
          </div>
        </section>

        <section v-if="run?.status === 'FAILED'" class="workflow-card failure-card">
          <span class="card-kicker"><CircleAlert :size="16" />执行失败</span><h2>{{ run.error }}</h2>
          <button class="secondary-button" @click="openRevision"><PencilLine :size="16" />修改问题后重试</button>
        </section>

        <div v-if="!run" class="workflow-empty">
          <Beaker :size="24" /><strong>从一个真实问题开始</strong><span>Agent 会先澄清和展示计划，不会直接黑盒执行。</span>
        </div>
      </section>

      <aside class="trace-panel">
        <div class="trace-heading">
          <div><span class="section-label">Developer Trace</span><h2>执行轨迹</h2></div>
          <span v-if="run">{{ run.events.length }} events</span>
        </div>
        <div v-if="run" class="run-summary">
          <span :class="`run-status ${run.status.toLowerCase()}`">{{ statusLabel(run.status) }}</span>
          <code>{{ run.plannerUsage.promptVersion }}</code>
          <small>{{ run.plannerUsage.inputTokens }} in / {{ run.plannerUsage.outputTokens }} out tokens</small>
          <small v-if="run.parentRunId">branch from {{ run.parentRunId.slice(0, 8) }}</small>
        </div>
        <ol v-if="run" class="trace-list">
          <li v-for="traceEvent in run.events" :key="traceEvent.sequence">
            <div class="trace-node" :class="{ tool: traceEvent.type.includes('TOOL') }"><component :is="eventIcon(traceEvent)" :size="14" /></div>
            <div class="trace-content"><strong>{{ traceEvent.type }}</strong><p>{{ traceEvent.message }}</p></div>
            <time>{{ traceEvent.sequence.toString().padStart(2, '0') }}</time>
          </li>
        </ol>
        <div v-else class="trace-empty"><ListTree :size="22" /><span>暂无轨迹</span></div>
      </aside>
    </main>

    <main v-else class="evaluation-workspace">
      <section class="evaluation-header">
        <div><span class="section-label">Golden Set</span><h1>{{ evaluationPlanner === 'offline' ? '离线评测' : '模型评测' }}</h1></div>
        <div class="evaluation-actions">
          <div class="mode-switch" role="group" aria-label="评测 Planner">
            <button
              v-for="planner in planners" :key="planner.mode" :class="{ active: evaluationPlanner === planner.mode }"
              :disabled="!planner.ready || evaluating" @click="selectEvaluationPlanner(planner.mode)"
            ><TerminalSquare v-if="planner.mode === 'offline'" :size="15" /><Cpu v-else :size="15" />{{ plannerLabel(planner.mode) }}</button>
          </div>
          <button class="run-button" :disabled="evaluating" @click="evaluate">
            <Clock3 v-if="evaluating" :size="18" class="spin" /><Play v-else :size="18" />{{ evaluating ? '评测中' : '运行评测' }}
          </button>
        </div>
      </section>
      <div v-if="error" class="error-banner"><CircleAlert :size="17" />{{ error }}</div>
      <section class="metric-strip">
        <div><span>任务成功率</span><strong>{{ report ? `${Math.round(report.taskSuccessRate * 100)}%` : '—' }}</strong></div>
        <div><span>工具选择准确率</span><strong>{{ report ? `${Math.round(report.toolSelectionAccuracy * 100)}%` : '—' }}</strong></div>
        <div><span>平均延迟</span><strong>{{ report ? `${report.averageLatencyMs.toFixed(1)} ms` : '—' }}</strong></div>
        <div><span>通过用例</span><strong>{{ report ? `${report.passedCases}/${report.totalCases}` : '—' }}</strong></div>
      </section>
      <section v-if="report" class="category-strip">
        <div v-for="category in report.categories" :key="category.category">
          <span>{{ categoryLabel(category.category) }}</span><strong>{{ category.passedCases }}/{{ category.totalCases }}</strong>
          <div class="category-progress"><i :style="{ width: `${Math.round(category.successRate * 100)}%` }"></i></div>
        </div>
      </section>
      <section class="case-table-wrap">
        <div class="table-heading"><span>评测用例</span><span v-if="report">{{ new Date(report.generatedAt).toLocaleTimeString('zh-CN') }}</span></div>
        <div v-if="report" class="evaluation-context">
          <span>mode <strong>{{ report.mode }}</strong></span><span>prompt <code>{{ report.promptVersion }}</code></span>
          <span>model <strong>{{ report.model }}</strong></span><span>tokens <strong>{{ report.inputTokens }} in / {{ report.outputTokens }} out</strong></span>
        </div>
        <table>
          <thead><tr><th>用例</th><th>类别</th><th>工具</th><th>工具选择</th><th>延迟</th><th>结果</th></tr></thead>
          <tbody v-if="report">
            <tr v-for="item in report.cases" :key="item.caseId">
              <td>{{ item.caseId }}</td><td><span class="category-badge">{{ categoryLabel(item.category) }}</span></td><td><code>{{ item.actualTool }}</code></td>
              <td>{{ item.toolSelectionCorrect ? '正确' : '错误' }}</td><td>{{ item.latencyMs }} ms</td>
              <td><span v-if="item.passed" class="pass-badge"><Check :size="14" />通过</span><span v-else class="fail-badge"><X :size="14" />失败</span><small v-if="item.failureReason" class="case-reason">{{ item.failureReason }}</small></td>
            </tr>
          </tbody>
          <tbody v-else><tr><td colspan="6" class="table-empty">尚未运行评测</td></tr></tbody>
        </table>
      </section>
    </main>
  </div>
</template>
