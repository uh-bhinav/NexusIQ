import { apiClient } from '@/api/client'
import { Approval, ApprovalPage, type ApprovalStatus } from '@/api/schemas'

export async function listApprovals(workspaceId: string, status?: ApprovalStatus, page = 0, size = 20) {
  const response = await apiClient.get(`/workspaces/${workspaceId}/approvals`, {
    params: { status, page, size },
  })
  return ApprovalPage.parse(response.data)
}

export async function approveDecision(workspaceId: string, approvalId: string, notes?: string) {
  const response = await apiClient.post(`/workspaces/${workspaceId}/approvals/${approvalId}/approve`, {
    notes,
  })
  return Approval.parse(response.data)
}

export async function rejectDecision(workspaceId: string, approvalId: string, reason: string) {
  const response = await apiClient.post(`/workspaces/${workspaceId}/approvals/${approvalId}/reject`, {
    reason,
  })
  return Approval.parse(response.data)
}
