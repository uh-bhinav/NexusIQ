import { z } from 'zod'
import { apiClient } from '@/api/client'
import { DecisionSummary, DecisionPage, DecisionDetail, type DecisionPriority } from '@/api/schemas'

const StreamTokenResponse = z.object({ token: z.string() })

export async function listDecisions(workspaceId: string, page = 0, size = 20) {
  const response = await apiClient.get(`/workspaces/${workspaceId}/decisions`, { params: { page, size } })
  return DecisionPage.parse(response.data)
}

export async function getDecision(workspaceId: string, decisionId: string) {
  const response = await apiClient.get(`/workspaces/${workspaceId}/decisions/${decisionId}`)
  return DecisionDetail.parse(response.data)
}

export async function createDecision(
  workspaceId: string,
  title: string,
  question: string,
  priority: DecisionPriority,
) {
  const response = await apiClient.post(`/workspaces/${workspaceId}/decisions`, { title, question, priority })
  return DecisionSummary.parse(response.data)
}

/** EventSource can't set an Authorization header, so the stream route takes
 * a short-lived, decision-scoped token instead (docs/API/API_DESIGN.md
 * "SSE"). Fetched fresh right before opening the connection — it expires in
 * 30s server-side. */
export async function issueStreamToken(workspaceId: string, decisionId: string) {
  const response = await apiClient.post(`/workspaces/${workspaceId}/decisions/${decisionId}/stream-token`)
  return StreamTokenResponse.parse(response.data).token
}

export function decisionStreamUrl(workspaceId: string, decisionId: string, token: string) {
  return `/api/v1/workspaces/${workspaceId}/decisions/${decisionId}/stream?token=${encodeURIComponent(token)}`
}
