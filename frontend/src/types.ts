export type RunStatus =
  | 'CREATED'
  | 'PLANNING'
  | 'WAITING_FOR_CLARIFICATION'
  | 'WAITING_FOR_APPROVAL'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'NOT_IMPLEMENTED'
  | 'FAILED'

export interface TraceEvent {
  sequence: number
  type: string
  message: string
  occurredAt: string
  data: Record<string, unknown>
}

export interface PlannerUsage {
  promptVersion: string
  model: string
  inputTokens: number
  outputTokens: number
}

export interface PlannerDescriptor {
  mode: string
  promptVersion: string
  model: string
  ready: boolean
}

export interface ClarificationOption {
  label: string
  resolvedInput: string
}

export interface ClarificationPrompt {
  question: string
  options: ClarificationOption[]
}

export interface PlanPreview {
  interpretation: string
  toolName: string
  arguments: Record<string, unknown>
  sourceTables: string[]
  filters: string[]
  sql: string | null
  assumptions: string[]
  riskLevel: string
  rowLimit: number
}

export interface RunEvidence {
  summary: string
  sourceTables: string[]
  filters: string[]
  sql: string | null
  rowCount: number
  resultData: Record<string, unknown>
}

export interface RunFeedback {
  rating: 'UP' | 'DOWN'
  reason: string | null
  comment: string | null
  submittedAt: string
}

export interface AgentRun {
  id: string
  input: string
  parentRunId: string | null
  effectiveInput: string
  createdAt: string
  status: RunStatus
  plannerMode: string
  plannerUsage: PlannerUsage
  clarification: ClarificationPrompt | null
  planPreview: PlanPreview | null
  evidence: RunEvidence | null
  feedback: RunFeedback | null
  output: string | null
  error: string | null
  durationMs: number
  events: TraceEvent[]
  executedTools: string[]
}

export interface EvaluationCaseResult {
  caseId: string
  category: string
  passed: boolean
  toolSelectionCorrect: boolean
  actualTool: string | null
  latencyMs: number
  failureReason: string | null
}

export interface EvaluationCategoryReport {
  category: string
  totalCases: number
  passedCases: number
  successRate: number
}

export interface EvaluationReport {
  mode: string
  promptVersion: string
  model: string
  generatedAt: string
  totalCases: number
  passedCases: number
  taskSuccessRate: number
  toolSelectionAccuracy: number
  averageLatencyMs: number
  inputTokens: number
  outputTokens: number
  categories: EvaluationCategoryReport[]
  cases: EvaluationCaseResult[]
}
