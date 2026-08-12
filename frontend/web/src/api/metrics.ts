import { apiClient } from '@/api/client'
import { MetricsSummary } from '@/api/schemas'

export async function getMetricsSummary(workspaceId: string) {
  const response = await apiClient.get(`/workspaces/${workspaceId}/metrics/summary`)
  return MetricsSummary.parse(response.data)
}
