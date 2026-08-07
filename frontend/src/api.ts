import {
  mockApproveRun,
  mockClarifyRun,
  mockEvaluate,
  mockFeedback,
  mockPlanners,
  mockPreviewRun,
  mockReviseRun,
  mockRun,
} from './mock'
import type { AgentRun, EvaluationReport, PlannerDescriptor } from './types'

const useMock = import.meta.env.VITE_USE_MOCK !== 'false'

async function readResponse<T>(response: Response, fallbackMessage: string): Promise<T> {
  let body: unknown
  try {
    body = await response.json()
  } catch {
    body = null
  }
  if (!response.ok) {
    const message = body && typeof body === 'object' && 'error' in body && typeof body.error === 'string'
      ? body.error
      : fallbackMessage
    throw new Error(message)
  }
  return body as T
}

export async function listPlanners(): Promise<PlannerDescriptor[]> {
  if (useMock) return mockPlanners()
  const response = await fetch('/api/planners')
  return readResponse(response, 'Planner 列表加载失败')
}

export async function createRun(input: string, plannerMode: string): Promise<AgentRun> {
  if (useMock) return mockRun(input, plannerMode)
  const response = await fetch('/api/runs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ input, plannerMode }),
  })
  return readResponse(response, '运行请求失败')
}

export async function previewRun(
  input: string,
  plannerMode: string,
  parentRunId: string | null = null,
): Promise<AgentRun> {
  if (useMock) return mockPreviewRun(input, plannerMode, parentRunId)
  const response = await fetch('/api/runs/preview', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ input, plannerMode, parentRunId }),
  })
  return readResponse(response, '计划生成失败')
}

export async function clarifyRun(id: string, resolvedInput: string): Promise<AgentRun> {
  if (useMock) return mockClarifyRun(id, resolvedInput)
  const response = await fetch(`/api/runs/${encodeURIComponent(id)}/clarify`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ resolvedInput }),
  })
  return readResponse(response, '澄清请求失败')
}

export async function approveRun(id: string): Promise<AgentRun> {
  if (useMock) return mockApproveRun(id)
  const response = await fetch(`/api/runs/${encodeURIComponent(id)}/approve`, { method: 'POST' })
  return readResponse(response, '执行计划失败')
}

export async function reviseRun(id: string, input: string): Promise<AgentRun> {
  if (useMock) return mockReviseRun(id, input)
  const response = await fetch(`/api/runs/${encodeURIComponent(id)}/revise`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ input }),
  })
  return readResponse(response, '修改计划失败')
}

export async function submitFeedback(
  id: string,
  rating: 'UP' | 'DOWN',
  reason: string | null,
  comment: string | null,
): Promise<AgentRun> {
  if (useMock) return mockFeedback(id, rating, reason, comment)
  const response = await fetch(`/api/runs/${encodeURIComponent(id)}/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ rating, reason, comment }),
  })
  return readResponse(response, '反馈提交失败')
}

export async function runEvaluation(plannerMode: string): Promise<EvaluationReport> {
  if (useMock) return mockEvaluate(plannerMode)
  const response = await fetch(`/api/evaluations/${encodeURIComponent(plannerMode)}`, { method: 'POST' })
  return readResponse(response, '评测请求失败')
}
