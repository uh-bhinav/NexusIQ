import { apiClient } from '@/api/client'
import { AuditEventPage } from '@/api/schemas'

export async function listAuditEvents(workspaceId: string, page = 0, size = 20) {
  const response = await apiClient.get('/audit', { params: { workspaceId, page, size } })
  return AuditEventPage.parse(response.data)
}

export async function listAuditForResource(
  workspaceId: string,
  resourceType: string,
  resourceId: string,
  page = 0,
  size = 20,
) {
  const response = await apiClient.get(`/audit/resource/${resourceType}/${resourceId}`, {
    params: { workspaceId, page, size },
  })
  return AuditEventPage.parse(response.data)
}
