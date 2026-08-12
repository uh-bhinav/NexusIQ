import { apiClient } from '@/api/client'
import { KnowledgeSearchResponse } from '@/api/schemas'

export async function searchKnowledge(workspaceId: string, query: string, documentTypes?: string[]) {
  const response = await apiClient.get(`/workspaces/${workspaceId}/knowledge/search`, {
    params: { q: query, documentTypes },
  })
  return KnowledgeSearchResponse.parse(response.data)
}
