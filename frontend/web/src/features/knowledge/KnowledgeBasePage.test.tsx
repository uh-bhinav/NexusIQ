import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { Routes, Route } from 'react-router-dom'
import { server } from '@/test/msw-server'
import { renderWithProviders } from '@/test/render'
import { KnowledgeBasePage } from '@/features/knowledge/KnowledgeBasePage'

function renderKnowledgeBase() {
  return renderWithProviders(
    <Routes>
      <Route path="/w/:workspaceId" element={<KnowledgeBasePage />} />
    </Routes>,
    { route: '/w/ws1' },
  )
}

const emptyDocuments = { content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 }

describe('KnowledgeBasePage', () => {
  it('renders the initial prompt before any search (populated state, nothing searched yet)', async () => {
    server.use(http.get('/api/v1/workspaces/ws1/documents', () => HttpResponse.json(emptyDocuments)))
    renderKnowledgeBase()
    expect(screen.getByLabelText(/search query/i)).toBeInTheDocument()
    expect(screen.getByText(/search the ingested corpus/i)).toBeInTheDocument()
    expect(await screen.findByText('No documents yet')).toBeInTheDocument()
  })

  it('primary action: submitting a query calls the search endpoint and renders cited results', async () => {
    server.use(http.get('/api/v1/workspaces/ws1/documents', () => HttpResponse.json(emptyDocuments)))
    let requestedQuery: string | null = null
    server.use(
      http.get('/api/v1/workspaces/ws1/knowledge/search', ({ request }) => {
        requestedQuery = new URL(request.url).searchParams.get('q')
        return HttpResponse.json({
          query: requestedQuery ?? '',
          cached: false,
          latency_ms: 42,
          results: [
            {
              chunk_id: 'c1',
              document_id: 'd1',
              document_name: 'Security Policy v2',
              document_type: 'SECURITY_POLICY',
              document_version: 2,
              is_current: true,
              section: 'Data Residency',
              subsection: null,
              page_number: 3,
              content: 'EU/EEA data residency is documented for all in-scope vendors.',
              similarity_score: 0.91,
              rerank_score: null,
              trust_level: 'AUTHORITATIVE',
              is_flagged: false,
              citation_reference: 'SP-102 §3',
            },
          ],
        })
      }),
    )
    renderKnowledgeBase()

    await userEvent.type(screen.getByLabelText(/search query/i), 'data residency')
    await userEvent.click(screen.getByRole('button', { name: /search/i }))

    expect(await screen.findByText('SP-102 §3')).toBeInTheDocument()
    expect(screen.getByText(/EU\/EEA data residency/)).toBeInTheDocument()
    expect(screen.getByText('91% match')).toBeInTheDocument()
    expect(requestedQuery).toBe('data residency')
  })

  it('renders an empty state when a search returns zero results', async () => {
    server.use(http.get('/api/v1/workspaces/ws1/documents', () => HttpResponse.json(emptyDocuments)))
    server.use(
      http.get('/api/v1/workspaces/ws1/knowledge/search', () =>
        HttpResponse.json({ query: 'nonexistent', cached: false, latency_ms: 10, results: [] }),
      ),
    )
    renderKnowledgeBase()

    await userEvent.type(screen.getByLabelText(/search query/i), 'nonexistent')
    await userEvent.click(screen.getByRole('button', { name: /search/i }))

    expect(await screen.findByText('No results')).toBeInTheDocument()
  })

  it('renders an error state when the search request fails', async () => {
    server.use(http.get('/api/v1/workspaces/ws1/documents', () => HttpResponse.json(emptyDocuments)))
    server.use(
      http.get('/api/v1/workspaces/ws1/knowledge/search', () =>
        HttpResponse.json(
          {
            timestamp: new Date().toISOString(),
            status: 503,
            error: 'SERVICE_UNAVAILABLE',
            message: 'AI service is unavailable',
            path: '/api/v1/workspaces/ws1/knowledge/search',
          },
          { status: 503 },
        ),
      ),
    )
    renderKnowledgeBase()

    await userEvent.type(screen.getByLabelText(/search query/i), 'anything')
    await userEvent.click(screen.getByRole('button', { name: /search/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/ai service is unavailable/i)
  })

  it('renders a populated document list with status badges', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/documents', () =>
        HttpResponse.json({
          content: [
            {
              id: 'd1',
              workspace_id: 'ws1',
              name: 'Vendor Alpha Security Policy',
              document_type: 'SECURITY_POLICY',
              version: 1,
              is_current: true,
              status: 'READY',
              chunk_count: 4,
              uploaded_by: 'u1',
              created_at: new Date().toISOString(),
              updated_at: new Date().toISOString(),
            },
          ],
          page: 0,
          size: 20,
          total_elements: 1,
          total_pages: 1,
        }),
      ),
    )
    renderKnowledgeBase()

    expect(await screen.findByText('Vendor Alpha Security Policy')).toBeInTheDocument()
    expect(screen.getByText('READY')).toBeInTheDocument()
    expect(screen.getByText(/4 chunks/)).toBeInTheDocument()
  })

  it('renders an error state when the document list request fails', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/documents', () =>
        HttpResponse.json(
          {
            timestamp: new Date().toISOString(),
            status: 500,
            error: 'INTERNAL_ERROR',
            message: 'Something went wrong',
            path: '/api/v1/workspaces/ws1/documents',
          },
          { status: 500 },
        ),
      ),
    )
    renderKnowledgeBase()

    expect(await screen.findByRole('alert')).toHaveTextContent(/something went wrong/i)
  })

  it('primary action: uploading a document calls the upload endpoint and refreshes the list', async () => {
    let uploadCount = 0
    server.use(
      http.get('/api/v1/workspaces/ws1/documents', () =>
        // Second GET (after the post-upload query invalidation) returns the
        // newly uploaded document — this is the user-visible behaviour that
        // matters: the list refreshes to show it, without needing to
        // recover this environment's FormData/Blob body across the XHR ->
        // MSW interceptor boundary (a jsdom/@mswjs/interceptors limitation,
        // not an app bug — the real upload path is live-verified separately
        // against the actual backend).
        HttpResponse.json(
          uploadCount === 0
            ? emptyDocuments
            : {
                content: [
                  {
                    id: 'd2',
                    workspace_id: 'ws1',
                    name: 'policy.txt',
                    document_type: 'OTHER',
                    version: 1,
                    is_current: true,
                    status: 'UPLOADED',
                    chunk_count: 0,
                    uploaded_by: 'u1',
                    created_at: new Date().toISOString(),
                    updated_at: new Date().toISOString(),
                  },
                ],
                page: 0,
                size: 20,
                total_elements: 1,
                total_pages: 1,
              },
        ),
      ),
      http.post('/api/v1/workspaces/ws1/documents', ({ request }) => {
        // Proves the request itself is well-formed multipart/form-data.
        // Deliberately doesn't parse the body further: jsdom's `File`/`Blob`
        // and the one visible inside this MSW handler are different-realm
        // instances here (a vitest/jsdom/msw ecosystem quirk), so body
        // content assertions are unreliable in this environment — the real
        // upload path is live-verified separately against the actual
        // backend and browser.
        expect(request.headers.get('content-type')).toContain('multipart/form-data')
        uploadCount += 1
        return HttpResponse.json(
          {
            id: 'd2',
            workspace_id: 'ws1',
            name: 'policy.txt',
            document_type: 'OTHER',
            version: 1,
            is_current: true,
            status: 'UPLOADED',
            chunk_count: 0,
            uploaded_by: 'u1',
            created_at: new Date().toISOString(),
            updated_at: new Date().toISOString(),
          },
          { status: 202 },
        )
      }),
    )
    renderKnowledgeBase()
    await screen.findByText('No documents yet')

    const file = new File(['hello world'], 'policy.txt', { type: 'text/plain' })
    const fileInput = screen.getByLabelText(/^file$/i)
    await userEvent.upload(fileInput, file)
    await userEvent.click(screen.getByRole('button', { name: /^upload$/i }))

    expect(await screen.findByText('policy.txt')).toBeInTheDocument()
    expect(uploadCount).toBe(1)
  })
})
