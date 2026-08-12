import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { Routes, Route } from 'react-router-dom'
import { useEffect } from 'react'
import { server } from '@/test/msw-server'
import { renderWithProviders } from '@/test/render'
import { ApprovalQueuePage } from '@/features/approvals/ApprovalQueuePage'
import { useAuth } from '@/features/auth/auth-context'
import type { UserSummary } from '@/api/schemas'

/** Drives the real `login()` flow (mocked network) rather than reaching
 * into AuthContext's internals, so the resulting `user.role` the page reads
 * is populated exactly the way a real signed-in user's would be. */
function LoginAs({ children }: { children: React.ReactNode }) {
  const { login, isAuthenticated } = useAuth()
  useEffect(() => {
    void login('user@example.com', 'password')
  }, [login])
  return isAuthenticated ? <>{children}</> : null
}

/** `workspaceRole` defaults to `globalRole` so most tests can pass one role
 * and get the old "both the same" behaviour; the dedicated mismatch test
 * below passes them independently — that's the case that actually proves
 * ApprovalService's real authorization boundary (workspace-level role, not
 * the global one) is what the page's button-visibility follows. */
function renderPage(globalRole: UserSummary['role'] = 'APPROVER', workspaceRole?: UserSummary['role']) {
  const wsRole = workspaceRole ?? globalRole
  server.use(
    http.post('/api/v1/auth/login', () =>
      HttpResponse.json({
        access_token: 'token',
        refresh_token: 'refresh',
        expires_in: 3600,
        user: { id: 'u1', email: 'user@example.com', name: 'Test User', role: globalRole },
      }),
    ),
    http.get('/api/v1/auth/me', () =>
      HttpResponse.json({
        user: { id: 'u1', email: 'user@example.com', name: 'Test User', role: globalRole },
        workspaces: [{ id: 'ws1', name: 'Acme', slug: 'acme', role: wsRole }],
      }),
    ),
  )
  return renderWithProviders(
    <LoginAs>
      <Routes>
        <Route path="/w/:workspaceId" element={<ApprovalQueuePage />} />
      </Routes>
    </LoginAs>,
    { route: '/w/ws1' },
  )
}

const pendingApproval = {
  id: 'appr1',
  decision_run_id: 'run1',
  decision_request_id: 'd1',
  decision_title: 'Vendor Alpha approval',
  status: 'PENDING',
  reasons: ['evidence_coverage=0.0 < HITL_MIN_EVIDENCE_COVERAGE=0.80'],
  requested_at: new Date().toISOString(),
  resolved_by: null,
  resolved_at: null,
  resolution_notes: null,
}

describe('ApprovalQueuePage', () => {
  it('renders populated pending approvals with reasons', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/approvals', () =>
        HttpResponse.json({ content: [pendingApproval], page: 0, size: 20, total_elements: 1, total_pages: 1 }),
      ),
    )
    renderPage()

    expect(await screen.findByText('Vendor Alpha approval')).toBeInTheDocument()
    expect(screen.getByText(/evidence_coverage=0.0/)).toBeInTheDocument()
  })

  it('renders an empty state when the filter has no matches', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/approvals', () =>
        HttpResponse.json({ content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 }),
      ),
    )
    renderPage()

    expect(await screen.findByText('Nothing here')).toBeInTheDocument()
  })

  it('renders an error state when the queue fails to load', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/approvals', () =>
        HttpResponse.json(
          {
            timestamp: new Date().toISOString(),
            status: 500,
            error: 'INTERNAL_ERROR',
            message: 'Unexpected error',
            path: '/api/v1/workspaces/ws1/approvals',
          },
          { status: 500 },
        ),
      ),
    )
    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent(/unexpected error/i)
  })

  it('primary action: an APPROVER can approve, which calls POST .../approve', async () => {
    let approveCalled = false
    server.use(
      http.get('/api/v1/workspaces/ws1/approvals', () =>
        HttpResponse.json({ content: [pendingApproval], page: 0, size: 20, total_elements: 1, total_pages: 1 }),
      ),
      http.post('/api/v1/workspaces/ws1/approvals/appr1/approve', async ({ request }) => {
        approveCalled = true
        const body = (await request.json()) as { notes?: string }
        expect(body.notes).toBe('looks good')
        return HttpResponse.json({ ...pendingApproval, status: 'APPROVED' })
      }),
    )
    renderPage('APPROVER')

    await screen.findByText('Vendor Alpha approval')
    await userEvent.type(screen.getByLabelText(/approval notes/i), 'looks good')
    await userEvent.click(screen.getByRole('button', { name: /^approve$/i }))

    expect(approveCalled).toBe(true)
  })

  it('a VIEWER sees the queue but no approve/reject buttons', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/approvals', () =>
        HttpResponse.json({ content: [pendingApproval], page: 0, size: 20, total_elements: 1, total_pages: 1 }),
      ),
    )
    renderPage('VIEWER')

    await screen.findByText('Vendor Alpha approval')
    expect(screen.queryByRole('button', { name: /^approve$/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^reject$/i })).not.toBeInTheDocument()
    expect(screen.getByText(/only an Approver or Admin/i)).toBeInTheDocument()
  })

  it('button visibility follows the workspace-level role, not the global one', async () => {
    // ApprovalService.approve/reject authorize on WorkspaceAccessService's
    // per-workspace role (backend/spring-api/.../ApprovalService.java),
    // exactly like WorkspaceService.addMember — not the user's global role.
    // A globally-ANALYST user who is this workspace's ADMIN (e.g. its
    // creator) must see the buttons; a globally-APPROVER user who is only a
    // VIEWER of this particular workspace must not. Getting this backwards
    // either hides a real capability or (worse) shows a button the server
    // will 403 on.
    server.use(
      http.get('/api/v1/workspaces/ws1/approvals', () =>
        HttpResponse.json({ content: [pendingApproval], page: 0, size: 20, total_elements: 1, total_pages: 1 }),
      ),
    )
    renderPage('ANALYST', 'ADMIN')

    await screen.findByText('Vendor Alpha approval')
    expect(screen.getByRole('button', { name: /^approve$/i })).toBeInTheDocument()
  })

  it('hides the buttons for a globally-privileged user who is only a VIEWER of this workspace', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/approvals', () =>
        HttpResponse.json({ content: [pendingApproval], page: 0, size: 20, total_elements: 1, total_pages: 1 }),
      ),
    )
    renderPage('APPROVER', 'VIEWER')

    await screen.findByText('Vendor Alpha approval')
    expect(screen.queryByRole('button', { name: /^approve$/i })).not.toBeInTheDocument()
  })
})
