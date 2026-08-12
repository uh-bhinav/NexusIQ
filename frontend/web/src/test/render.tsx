import type { ReactElement } from 'react'
import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthProvider } from '@/features/auth/auth-context'

/** Every query retries by default (TanStack Query's own default), which
 * turns a deliberately-erroring test into a slow one waiting out retries —
 * disabled here, matching the common test-only override. */
function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
}

export function renderWithProviders(ui: ReactElement, { route = '/' }: { route?: string } = {}) {
  const queryClient = createTestQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}
