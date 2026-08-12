import { apiClient } from '@/api/client'
import { DocumentSummary, DocumentPage, ChunkPage, type DocumentType } from '@/api/schemas'

export async function listDocuments(workspaceId: string, page = 0, size = 20) {
  const response = await apiClient.get(`/workspaces/${workspaceId}/documents`, { params: { page, size } })
  return DocumentPage.parse(response.data)
}

export async function getDocument(workspaceId: string, documentId: string) {
  const response = await apiClient.get(`/workspaces/${workspaceId}/documents/${documentId}`)
  return DocumentSummary.parse(response.data)
}

export async function listChunks(workspaceId: string, documentId: string, page = 0, size = 20) {
  const response = await apiClient.get(`/workspaces/${workspaceId}/documents/${documentId}/chunks`, {
    params: { page, size },
  })
  return ChunkPage.parse(response.data)
}

export async function uploadDocument(
  workspaceId: string,
  file: File,
  metadata: { name: string; documentType: DocumentType; supersedesDocumentId?: string },
) {
  const form = new FormData()
  form.append('file', file)
  form.append(
    'metadata',
    new Blob(
      [
        JSON.stringify({
          name: metadata.name,
          document_type: metadata.documentType,
          supersedes_document_id: metadata.supersedesDocumentId ?? null,
        }),
      ],
      { type: 'application/json' },
    ),
  )
  const response = await apiClient.post(`/workspaces/${workspaceId}/documents`, form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return DocumentSummary.parse(response.data)
}

export async function deleteDocument(workspaceId: string, documentId: string) {
  await apiClient.delete(`/workspaces/${workspaceId}/documents/${documentId}`)
}
