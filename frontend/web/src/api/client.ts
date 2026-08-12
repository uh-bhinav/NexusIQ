import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { z } from 'zod'
import { getAccessToken, setTokens, clearTokens, getRefreshToken } from '@/lib/auth-storage'
import { ApiError } from '@/api/schemas'

// Only the two fields this path needs — the full AuthResponse shape is
// validated separately wherever login/register actually happens.
const z_authRefresh = z.object({ access_token: z.string() })

/** Thrown for any non-2xx response after the Zod-validated error envelope
 * (docs/API/API_DESIGN.md "Errors") is parsed — callers get a typed shape
 * instead of reaching into an Axios error by hand. */
export class HttpError extends Error {
  status: number
  code: string
  details?: ApiError['details']

  constructor(status: number, code: string, message: string, details?: ApiError['details']) {
    super(message)
    this.name = 'HttpError'
    this.status = status
    this.code = code
    this.details = details
  }
}

export const apiClient = axios.create({ baseURL: '/api/v1' })

apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getAccessToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

let refreshPromise: Promise<string> | null = null

async function refreshAccessToken(): Promise<string> {
  const refreshToken = getRefreshToken()
  if (!refreshToken) {
    throw new Error('No refresh token available')
  }
  // axios directly (not apiClient) — must not recurse through the 401
  // interceptor below, and must not carry a (possibly expired) bearer header.
  const response = await axios.post('/api/v1/auth/refresh', { refresh_token: refreshToken })
  const parsed = z_authRefresh.parse(response.data)
  setTokens(parsed.access_token, refreshToken)
  return parsed.access_token
}

apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const original = error.config as (InternalAxiosRequestConfig & { _retried?: boolean }) | undefined

    if (error.response?.status === 401 && original && !original._retried) {
      original._retried = true
      try {
        refreshPromise ??= refreshAccessToken().finally(() => {
          refreshPromise = null
        })
        const newToken = await refreshPromise
        original.headers.set('Authorization', `Bearer ${newToken}`)
        return apiClient(original)
      } catch {
        clearTokens()
        window.location.assign('/login')
        return Promise.reject(error)
      }
    }

    const parsedError = ApiError.safeParse(error.response?.data)
    if (parsedError.success) {
      return Promise.reject(
        new HttpError(
          parsedError.data.status,
          parsedError.data.error,
          parsedError.data.message,
          parsedError.data.details,
        ),
      )
    }
    return Promise.reject(error)
  },
)
