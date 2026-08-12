import { apiClient } from '@/api/client'
import { Workspace, WorkspacePage, Member, Role } from '@/api/schemas'

export async function listWorkspaces(page = 0, size = 20) {
  const response = await apiClient.get('/workspaces', { params: { page, size } })
  return WorkspacePage.parse(response.data)
}

export async function getWorkspace(id: string) {
  const response = await apiClient.get(`/workspaces/${id}`)
  return Workspace.parse(response.data)
}

export async function createWorkspace(name: string, description?: string) {
  const response = await apiClient.post('/workspaces', { name, description })
  return Workspace.parse(response.data)
}

export async function listMembers(workspaceId: string) {
  const response = await apiClient.get(`/workspaces/${workspaceId}/members`)
  return Member.array().parse(response.data)
}

export async function addMember(workspaceId: string, email: string, role: Role) {
  const response = await apiClient.post(`/workspaces/${workspaceId}/members`, { email, role })
  return Member.parse(response.data)
}

export async function removeMember(workspaceId: string, userId: string) {
  await apiClient.delete(`/workspaces/${workspaceId}/members/${userId}`)
}
