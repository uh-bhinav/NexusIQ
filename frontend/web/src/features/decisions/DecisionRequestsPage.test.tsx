import { describe, expect, it } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { Routes, Route } from 'react-router-dom'
import { server } from '@/test/msw-server'
import { renderWithProviders } from '@/test/render'
import { DecisionRequestsPage } from '@/features/decisions/DecisionRequestsPage'

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/w/:workspaceId" element={<DecisionRequestsPage />} />
    </Routes>,
    { route: '/w/ws1' },
  )
}

describe('DecisionRequestsPage', () => {
  it('renders populated decisions with priority and status badges', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/decisions', () =>
        HttpResponse.json({
          content: [
            {
              id: 'd1',
              title: 'Vendor Alpha approval',
              question: 'Should Vendor Alpha be approved for EU production?',
              priority: 'HIGH',
              status: 'PROCESSING',
              created_at: new Date().toISOString(),
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

    const link = (await screen.findByText('Vendor Alpha approval')).closest('a')
    expect(link).not.toBeNull()
    expect(within(link as HTMLElement).getByText('HIGH')).toBeInTheDocument()
    expect(within(link as HTMLElement).getByText('PROCESSING')).toBeInTheDocument()
  })

  it('renders an empty state when there are no decision requests', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/decisions', () =>
        HttpResponse.json({ content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 }),
      ),
    )
    renderPage()

    expect(await screen.findByText('No decision requests yet')).toBeInTheDocument()
  })

  it('renders an error state when the list request fails', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/decisions', () =>
        HttpResponse.json(
          {
            timestamp: new Date().toISOString(),
            status: 500,
            error: 'INTERNAL_ERROR',
            message: 'Unexpected error',
            path: '/api/v1/workspaces/ws1/decisions',
          },
          { status: 500 },
        ),
      ),
    )
    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent(/unexpected error/i)
  })

  it('primary action: submitting the form calls POST /decisions with the entered fields', async () => {
    let requestBody: unknown = null
    server.use(
      http.get('/api/v1/workspaces/ws1/decisions', () =>
        HttpResponse.json({ content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 }),
      ),
      http.post('/api/v1/workspaces/ws1/decisions', async ({ request }) => {
        requestBody = await request.json()
        return HttpResponse.json(
          {
            id: 'd2',
            title: 'New vendor question',
            question: 'Is this vendor compliant?',
            priority: 'NORMAL',
            status: 'PENDING',
            created_at: new Date().toISOString(),
          },
          { status: 202 },
        )
      }),
    )
    renderPage()
    await screen.findByText('No decision requests yet')

    await userEvent.type(screen.getByLabelText(/^title$/i), 'New vendor question')
    await userEvent.type(screen.getByLabelText(/^question$/i), 'Is this vendor compliant?')
    await userEvent.click(screen.getByRole('button', { name: /^submit$/i }))

    await screen.findByRole('button', { name: /^submit$/i })
    expect(requestBody).toEqual({
      title: 'New vendor question',
      question: 'Is this vendor compliant?',
      priority: 'NORMAL',
    })
  })
})
