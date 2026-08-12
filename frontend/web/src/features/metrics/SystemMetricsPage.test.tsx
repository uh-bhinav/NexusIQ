import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { Routes, Route } from 'react-router-dom'
import { server } from '@/test/msw-server'
import { renderWithProviders } from '@/test/render'
import { SystemMetricsPage } from '@/features/metrics/SystemMetricsPage'

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/w/:workspaceId" element={<SystemMetricsPage />} />
    </Routes>,
    { route: '/w/ws1' },
  )
}

describe('SystemMetricsPage', () => {
  it('renders populated metrics: totals and both charts', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/metrics/summary', () =>
        HttpResponse.json({
          total_decisions: 12,
          decisions_by_status: { APPROVED: 10, WAITING_FOR_APPROVAL: 2 },
          decisions_by_recommendation: { APPROVE: 9, REJECT: 3 },
          pending_approvals: 2,
          avg_confidence: 0.81,
          avg_cost_usd: 0.0034,
          avg_latency_ms: 4213.5,
        }),
      ),
    )
    renderPage()

    expect(await screen.findByText('12')).toBeInTheDocument()
    expect(screen.getByText('0.81')).toBeInTheDocument()
    expect(screen.getByText('$0.0034')).toBeInTheDocument()
    expect(screen.getByText('4214ms')).toBeInTheDocument()
    expect(screen.getByText('Decisions by status')).toBeInTheDocument()
    expect(screen.getByText('Decisions by recommendation')).toBeInTheDocument()
  })

  it('renders an empty state with zero decisions', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/metrics/summary', () =>
        HttpResponse.json({
          total_decisions: 0,
          decisions_by_status: {},
          decisions_by_recommendation: {},
          pending_approvals: 0,
          avg_confidence: null,
          avg_cost_usd: null,
          avg_latency_ms: null,
        }),
      ),
    )
    renderPage()

    expect(await screen.findByText('No decisions yet')).toBeInTheDocument()
  })

  it('renders an error state when metrics fail to load', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/metrics/summary', () =>
        HttpResponse.json(
          {
            timestamp: new Date().toISOString(),
            status: 500,
            error: 'INTERNAL_ERROR',
            message: 'Unexpected error',
            path: '/api/v1/workspaces/ws1/metrics/summary',
          },
          { status: 500 },
        ),
      ),
    )
    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent(/unexpected error/i)
  })
})
