import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { Routes, Route } from 'react-router-dom'
import { server } from '@/test/msw-server'
import { renderWithProviders } from '@/test/render'
import { DocumentDetailPage } from '@/features/documents/DocumentDetailPage'

function renderPage(route = '/w/ws1/documents/doc1') {
  return renderWithProviders(
    <Routes>
      <Route path="/w/:workspaceId/documents/:documentId" element={<DocumentDetailPage />} />
    </Routes>,
    { route },
  )
}

const populatedDocument = {
  id: 'doc1',
  workspace_id: 'ws1',
  name: 'Security Policy v2',
  document_type: 'SECURITY_POLICY',
  version: 2,
  is_current: true,
  status: 'READY',
  failure_reason: null,
  chunk_count: 2,
  uploaded_by: 'user-12345678-abcd',
  created_at: new Date().toISOString(),
  updated_at: new Date().toISOString(),
}

describe('DocumentDetailPage', () => {
  it('renders populated state: metadata and chunk content', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/documents/doc1', () => HttpResponse.json(populatedDocument)),
      http.get('/api/v1/workspaces/ws1/documents/doc1/chunks', () =>
        HttpResponse.json({
          content: [
            {
              id: 'c1',
              document_id: 'doc1',
              chunk_index: 0,
              content: 'EU/EEA data residency is documented for all in-scope vendors.',
              section: 'Data Residency',
              subsection: null,
              page_number: 3,
              is_flagged: false,
            },
          ],
          page: 0,
          size: 20,
          total_elements: 1,
          total_pages: 1,
        }),
      ),
    )
    renderPage()

    expect(await screen.findByText('Security Policy v2')).toBeInTheDocument()
    expect(screen.getByText('READY')).toBeInTheDocument()
    expect(screen.getByText(/EU\/EEA data residency/)).toBeInTheDocument()
    expect(screen.getByText('§Data Residency')).toBeInTheDocument()
  })

  it('renders an empty state when the document has no chunks yet', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/documents/doc1', () =>
        HttpResponse.json({ ...populatedDocument, status: 'PROCESSING', chunk_count: 0 }),
      ),
      http.get('/api/v1/workspaces/ws1/documents/doc1/chunks', () =>
        HttpResponse.json({ content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 }),
      ),
    )
    renderPage()

    expect(await screen.findByText('No content yet')).toBeInTheDocument()
  })

  it('renders an error state when the document fails to load', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/documents/doc1', () =>
        HttpResponse.json(
          {
            timestamp: new Date().toISOString(),
            status: 404,
            error: 'NOT_FOUND',
            message: 'Document not found',
            path: '/api/v1/workspaces/ws1/documents/doc1',
          },
          { status: 404 },
        ),
      ),
    )
    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent(/document not found/i)
  })
})
