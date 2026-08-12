import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/msw-server'
import { apiClient, HttpError } from '@/api/client'
import { setTokens, clearTokens, getAccessToken } from '@/lib/auth-storage'

/** No test exercised this interceptor's actual refresh path before — only
 * indirectly via LoginPage's own 401 case, which never reaches the
 * refresh/retry branch at all (.claude/rules/frontend.md: "One Axios/fetch
 * interceptor: attach bearer, handle 401 -> refresh -> retry-once -> else
 * logout"). This drives it directly against a mocked /auth/refresh. */
describe('apiClient 401 interceptor', () => {
  // jsdom's window.location.assign isn't directly spy-able (it's a
  // non-configurable property) — replace the whole location object for the
  // duration of each test instead, restoring the original afterward.
  const originalLocation = window.location

  beforeEach(() => {
    clearTokens()
    vi.restoreAllMocks()
  })

  function mockLocationAssign(): ReturnType<typeof vi.fn> {
    const assign = vi.fn()
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...originalLocation, assign },
    })
    return assign
  }

  afterEach(() => {
    Object.defineProperty(window, 'location', { configurable: true, value: originalLocation })
  })

  it('on 401, refreshes the token once and retries the original request', async () => {
    setTokens('expired-token', 'valid-refresh-token')
    let protectedCallCount = 0

    server.use(
      http.get('/api/v1/workspaces', ({ request }) => {
        protectedCallCount += 1
        const auth = request.headers.get('Authorization')
        if (auth === 'Bearer expired-token') {
          return HttpResponse.json(
            { timestamp: 'x', status: 401, error: 'UNAUTHORIZED', message: 'expired', path: '/x' },
            { status: 401 },
          )
        }
        expect(auth).toBe('Bearer new-access-token')
        return HttpResponse.json({ content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 })
      }),
      http.post('/api/v1/auth/refresh', async ({ request }) => {
        const body = (await request.json()) as { refresh_token: string }
        expect(body.refresh_token).toBe('valid-refresh-token')
        return HttpResponse.json({ access_token: 'new-access-token' })
      }),
    )

    const response = await apiClient.get('/workspaces')

    expect(response.status).toBe(200)
    expect(protectedCallCount).toBe(2) // original 401 + one retry, not more
    expect(getAccessToken()).toBe('new-access-token')
  })

  it('concurrent 401s from two in-flight requests trigger only one refresh call', async () => {
    setTokens('expired-token', 'valid-refresh-token')
    let refreshCallCount = 0

    server.use(
      http.get('/api/v1/workspaces', ({ request }) => {
        const auth = request.headers.get('Authorization')
        if (auth === 'Bearer expired-token') {
          return HttpResponse.json(
            { timestamp: 'x', status: 401, error: 'UNAUTHORIZED', message: 'expired', path: '/x' },
            { status: 401 },
          )
        }
        return HttpResponse.json({ content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 })
      }),
      http.get('/api/v1/audit', ({ request }) => {
        const auth = request.headers.get('Authorization')
        if (auth === 'Bearer expired-token') {
          return HttpResponse.json(
            { timestamp: 'x', status: 401, error: 'UNAUTHORIZED', message: 'expired', path: '/x' },
            { status: 401 },
          )
        }
        return HttpResponse.json({ content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 })
      }),
      http.post('/api/v1/auth/refresh', () => {
        refreshCallCount += 1
        return HttpResponse.json({ access_token: 'new-access-token' })
      }),
    )

    const [a, b] = await Promise.all([
      apiClient.get('/workspaces'),
      apiClient.get('/audit', { params: { workspaceId: 'w1' } }),
    ])

    expect(a.status).toBe(200)
    expect(b.status).toBe(200)
    expect(refreshCallCount).toBe(1)
  })

  it('when refresh itself fails, clears tokens and redirects to /login instead of looping', async () => {
    setTokens('expired-token', 'stale-refresh-token')
    const assignSpy = mockLocationAssign()

    server.use(
      http.get('/api/v1/workspaces', () =>
        HttpResponse.json(
          { timestamp: 'x', status: 401, error: 'UNAUTHORIZED', message: 'expired', path: '/x' },
          { status: 401 },
        ),
      ),
      http.post('/api/v1/auth/refresh', () =>
        HttpResponse.json(
          { timestamp: 'x', status: 401, error: 'UNAUTHORIZED', message: 'refresh token expired', path: '/x' },
          { status: 401 },
        ),
      ),
    )

    await expect(apiClient.get('/workspaces')).rejects.toBeTruthy()

    expect(getAccessToken()).toBeNull()
    expect(assignSpy).toHaveBeenCalledWith('/login')
  })

  it('a retried request that gets a second 401 does not retry again (bounded, not a loop)', async () => {
    setTokens('expired-token', 'valid-refresh-token')
    let protectedCallCount = 0

    server.use(
      // Every call returns 401, even after "refresh" — proves the second
      // 401 is not retried a second time (original._retried guards this).
      http.get('/api/v1/workspaces', () => {
        protectedCallCount += 1
        return HttpResponse.json(
          { timestamp: 'x', status: 401, error: 'UNAUTHORIZED', message: 'still expired', path: '/x' },
          { status: 401 },
        )
      }),
      http.post('/api/v1/auth/refresh', () => HttpResponse.json({ access_token: 'new-access-token' })),
    )
    mockLocationAssign()

    await expect(apiClient.get('/workspaces')).rejects.toBeTruthy()

    expect(protectedCallCount).toBe(2) // original attempt + exactly one retry
  })

  it('a non-401 error response is wrapped as a typed HttpError, not swallowed', async () => {
    setTokens('valid-token', 'valid-refresh-token')
    server.use(
      http.get('/api/v1/workspaces/does-not-exist', () =>
        HttpResponse.json(
          { timestamp: 'x', status: 404, error: 'NOT_FOUND', message: 'Workspace not found', path: '/x' },
          { status: 404 },
        ),
      ),
    )

    await expect(apiClient.get('/workspaces/does-not-exist')).rejects.toMatchObject({
      status: 404,
      code: 'NOT_FOUND',
      message: 'Workspace not found',
    } satisfies Partial<HttpError>)
  })
})
