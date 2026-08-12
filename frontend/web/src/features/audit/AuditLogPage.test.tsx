import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { Routes, Route } from 'react-router-dom'
import { server } from '@/test/msw-server'
import { renderWithProviders } from '@/test/render'
import { AuditLogPage } from '@/features/audit/AuditLogPage'

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/w/:workspaceId" element={<AuditLogPage />} />
    </Routes>,
    { route: '/w/ws1' },
  )
}

describe('AuditLogPage', () => {
  it('renders populated audit events', async () => {
    server.use(
      http.get('/api/v1/audit', () =>
        HttpResponse.json({
          content: [
            {
              id: 'a1',
              workspace_id: 'ws1',
              actor_id: 'user-12345678-abcd',
              event_type: 'APPROVAL_GRANTED',
              resource_type: 'decision',
              resource_id: 'd-12345678-abcd',
              correlation_id: 'corr-12345678-abcd',
              metadata: '{"approval_id":"appr1"}',
              occurred_at: new Date().toISOString(),
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

    expect(await screen.findByText('APPROVAL_GRANTED')).toBeInTheDocument()
    expect(screen.getByText(/decision\/d-123456/)).toBeInTheDocument()
  })

  it('renders an empty state with no audit events', async () => {
    server.use(
      http.get('/api/v1/audit', () =>
        HttpResponse.json({ content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 }),
      ),
    )
    renderPage()

    expect(await screen.findByText('No audit events yet')).toBeInTheDocument()
  })

  it('renders an error state when the audit log fails to load', async () => {
    server.use(
      http.get('/api/v1/audit', () =>
        HttpResponse.json(
          {
            timestamp: new Date().toISOString(),
            status: 403,
            error: 'FORBIDDEN',
            message: 'Not a member of this workspace',
            path: '/api/v1/audit',
          },
          { status: 403 },
        ),
      ),
    )
    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent(/not a member/i)
  })
})
