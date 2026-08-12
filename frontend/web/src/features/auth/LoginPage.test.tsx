import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { Routes, Route } from 'react-router-dom'
import { server } from '@/test/msw-server'
import { renderWithProviders } from '@/test/render'
import { LoginPage } from '@/features/auth/LoginPage'

function renderLoginPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/w/:workspaceId" element={<div>workspace home</div>} />
      <Route path="/" element={<div>landing</div>} />
    </Routes>,
    { route: '/login' },
  )
}

describe('LoginPage', () => {
  it('renders the sign-in form (populated state)', () => {
    renderLoginPage()
    expect(screen.getByRole('form', { name: /sign in/i })).toBeInTheDocument()
    expect(screen.getByLabelText(/email/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/password/i)).toBeInTheDocument()
  })

  it('renders an error state when login fails', async () => {
    server.use(
      http.post('/api/v1/auth/login', () =>
        HttpResponse.json(
          {
            timestamp: new Date().toISOString(),
            status: 401,
            error: 'UNAUTHORIZED',
            message: 'Invalid email or password.',
            path: '/api/v1/auth/login',
          },
          { status: 401 },
        ),
      ),
    )
    renderLoginPage()

    await userEvent.type(screen.getByLabelText(/email/i), 'user@example.com')
    await userEvent.type(screen.getByLabelText(/password/i), 'wrong-password')
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/invalid email or password/i)
  })

  it('primary action: submitting valid credentials calls POST /auth/login and navigates on success', async () => {
    let loginCalled = false
    server.use(
      http.post('/api/v1/auth/login', async ({ request }) => {
        loginCalled = true
        const body = (await request.json()) as { email: string; password: string }
        expect(body).toEqual({ email: 'user@example.com', password: 'correct-password' })
        return HttpResponse.json({
          access_token: 'access-token',
          refresh_token: 'refresh-token',
          expires_in: 3600,
          user: { id: 'u1', email: 'user@example.com', name: 'Test User', role: 'ANALYST' },
        })
      }),
      http.get('/api/v1/auth/me', () =>
        HttpResponse.json({
          user: { id: 'u1', email: 'user@example.com', name: 'Test User', role: 'ANALYST' },
          workspaces: [{ id: 'ws1', name: 'Acme', slug: 'acme', role: 'ANALYST' }],
        }),
      ),
    )
    renderLoginPage()

    await userEvent.type(screen.getByLabelText(/email/i), 'user@example.com')
    await userEvent.type(screen.getByLabelText(/password/i), 'correct-password')
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }))

    await waitFor(() => expect(loginCalled).toBe(true))
    expect(await screen.findByText('workspace home')).toBeInTheDocument()
  })
})
