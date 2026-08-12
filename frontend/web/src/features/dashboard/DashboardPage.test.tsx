import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { Routes, Route } from 'react-router-dom'
import { useEffect } from 'react'
import { server } from '@/test/msw-server'
import { renderWithProviders } from '@/test/render'
import { DashboardPage } from '@/features/dashboard/DashboardPage'
import { useAuth } from '@/features/auth/auth-context'
import type { UserSummary } from '@/api/schemas'

/** Drives the real `login()` flow (mocked network) rather than reaching
 * into AuthContext's internals, so the per-workspace `role` MembersSection
 * reads is populated exactly the way a real signed-in user's would be
 * (mirrors ApprovalQueuePage.test.tsx's pattern). */
function LoginAs({ children }: { children: React.ReactNode }) {
  const { login, isAuthenticated } = useAuth()
  useEffect(() => {
    void login('user@example.com', 'password')
  }, [login])
  return isAuthenticated ? <>{children}</> : null
}

function renderDashboard(role: UserSummary['role'] = 'VIEWER') {
  server.use(
    http.post('/api/v1/auth/login', () =>
      HttpResponse.json({
        access_token: 'token',
        refresh_token: 'refresh',
        expires_in: 3600,
        user: { id: 'u1', email: 'user@example.com', name: 'Test User', role },
      }),
    ),
    http.get('/api/v1/auth/me', () =>
      HttpResponse.json({
        user: { id: 'u1', email: 'user@example.com', name: 'Test User', role },
        workspaces: [{ id: 'ws1', name: 'Acme', slug: 'acme', role }],
      }),
    ),
  )
  return renderWithProviders(
    <LoginAs>
      <Routes>
        <Route path="/w/:workspaceId" element={<DashboardPage />} />
      </Routes>
    </LoginAs>,
    { route: '/w/ws1' },
  )
}

const metricsResponse = {
  total_decisions: 12,
  decisions_by_status: { APPROVED: 10, REJECTED: 2 },
  decisions_by_recommendation: { APPROVE: 10, REJECT: 2 },
  pending_approvals: 3,
  avg_confidence: 0.81,
  avg_cost_usd: 0.0123,
  avg_latency_ms: 4200,
}

const emptyDecisions = { content: [], page: 0, size: 5, total_elements: 0, total_pages: 0 }
const emptyMembers = () => HttpResponse.json([])

describe('DashboardPage', () => {
  it('renders populated metrics and recent decisions', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/metrics/summary', () => HttpResponse.json(metricsResponse)),
      http.get('/api/v1/workspaces/ws1/decisions', () =>
        HttpResponse.json({
          content: [
            {
              id: 'd1',
              title: 'Vendor Alpha approval',
              question: 'Should Vendor Alpha be approved?',
              priority: 'HIGH',
              status: 'APPROVED',
              created_at: new Date().toISOString(),
            },
          ],
          page: 0,
          size: 5,
          total_elements: 1,
          total_pages: 1,
        }),
      ),
      http.get('/api/v1/workspaces/ws1/members', emptyMembers),
    )
    renderDashboard()

    expect(await screen.findByText('12')).toBeInTheDocument()
    expect(await screen.findByText('Vendor Alpha approval')).toBeInTheDocument()
  })

  it('renders an empty state when there are no decisions yet', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/metrics/summary', () => HttpResponse.json(metricsResponse)),
      http.get('/api/v1/workspaces/ws1/decisions', () => HttpResponse.json(emptyDecisions)),
      http.get('/api/v1/workspaces/ws1/members', emptyMembers),
    )
    renderDashboard()

    expect(await screen.findByText('No decisions yet')).toBeInTheDocument()
  })

  it('renders an error state with retry when metrics fail to load', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/metrics/summary', () =>
        HttpResponse.json(
          {
            timestamp: new Date().toISOString(),
            status: 500,
            error: 'INTERNAL_ERROR',
            message: 'Something broke',
            path: '/api/v1/workspaces/ws1/metrics/summary',
          },
          { status: 500 },
        ),
      ),
      http.get('/api/v1/workspaces/ws1/decisions', () => HttpResponse.json(emptyDecisions)),
      http.get('/api/v1/workspaces/ws1/members', emptyMembers),
    )
    renderDashboard()

    expect(await screen.findByRole('alert')).toHaveTextContent(/failed to load/i)
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument()
  })

  it('renders a populated member list, with no add-member form for a VIEWER', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/metrics/summary', () => HttpResponse.json(metricsResponse)),
      http.get('/api/v1/workspaces/ws1/decisions', () => HttpResponse.json(emptyDecisions)),
      http.get('/api/v1/workspaces/ws1/members', () =>
        HttpResponse.json([
          {
            user_id: 'u2',
            email: 'analyst@nexusiq.local',
            name: 'Ana Lyst',
            role: 'ANALYST',
            joined_at: new Date().toISOString(),
          },
        ]),
      ),
    )
    renderDashboard('VIEWER')

    expect(await screen.findByText('Ana Lyst')).toBeInTheDocument()
    expect(screen.getByText('analyst@nexusiq.local')).toBeInTheDocument()
    expect(screen.queryByRole('form', { name: /add a member/i })).not.toBeInTheDocument()
  })

  it('renders an empty state when the workspace has no members', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/metrics/summary', () => HttpResponse.json(metricsResponse)),
      http.get('/api/v1/workspaces/ws1/decisions', () => HttpResponse.json(emptyDecisions)),
      http.get('/api/v1/workspaces/ws1/members', emptyMembers),
    )
    renderDashboard()

    expect(await screen.findByText('No members yet')).toBeInTheDocument()
  })

  it('renders an error state when the member list fails to load', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/metrics/summary', () => HttpResponse.json(metricsResponse)),
      http.get('/api/v1/workspaces/ws1/decisions', () => HttpResponse.json(emptyDecisions)),
      http.get('/api/v1/workspaces/ws1/members', () =>
        HttpResponse.json(
          {
            timestamp: new Date().toISOString(),
            status: 500,
            error: 'INTERNAL_ERROR',
            message: 'Could not load members',
            path: '/api/v1/workspaces/ws1/members',
          },
          { status: 500 },
        ),
      ),
    )
    renderDashboard()

    expect(await screen.findByText('Could not load members')).toBeInTheDocument()
  })

  it('primary action: an ADMIN can add a member, which calls POST .../members and refreshes the list', async () => {
    let addedEmail: string | null = null
    let memberCallCount = 0
    server.use(
      http.get('/api/v1/workspaces/ws1/metrics/summary', () => HttpResponse.json(metricsResponse)),
      http.get('/api/v1/workspaces/ws1/decisions', () => HttpResponse.json(emptyDecisions)),
      http.get('/api/v1/workspaces/ws1/members', () => {
        memberCallCount += 1
        return HttpResponse.json(
          memberCallCount === 1
            ? []
            : [
                {
                  user_id: 'u3',
                  email: 'new@nexusiq.local',
                  name: 'New Member',
                  role: 'VIEWER',
                  joined_at: new Date().toISOString(),
                },
              ],
        )
      }),
      http.post('/api/v1/workspaces/ws1/members', async ({ request }) => {
        const body = (await request.json()) as { email: string; role: string }
        addedEmail = body.email
        return HttpResponse.json(
          {
            user_id: 'u3',
            email: body.email,
            name: 'New Member',
            role: body.role,
            joined_at: new Date().toISOString(),
          },
          { status: 201 },
        )
      }),
    )
    renderDashboard('ADMIN')

    await screen.findByText('No members yet')
    await userEvent.type(screen.getByLabelText(/^email$/i), 'new@nexusiq.local')
    await userEvent.click(screen.getByRole('button', { name: /add member/i }))

    expect(await screen.findByText('New Member')).toBeInTheDocument()
    expect(addedEmail).toBe('new@nexusiq.local')
  })
})
