import { createContext, useContext, useState, useCallback, type ReactNode } from 'react'
import * as authApi from '@/api/auth'
import { setTokens, clearTokens } from '@/lib/auth-storage'
import type { UserSummary, WorkspaceSummary } from '@/api/schemas'

interface AuthState {
  user: UserSummary | null
  workspaces: WorkspaceSummary[]
  isAuthenticated: boolean
  isLoading: boolean
  login: (email: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserSummary | null>(null)
  const [workspaces, setWorkspaces] = useState<WorkspaceSummary[]>([])
  const [isLoading, setIsLoading] = useState(false)

  const login = useCallback(async (email: string, password: string) => {
    setIsLoading(true)
    try {
      const auth = await authApi.login(email, password)
      setTokens(auth.access_token, auth.refresh_token)
      // user + workspaces set back-to-back (no await between them) so React
      // batches them into a single render — otherwise isAuthenticated flips
      // true for one render with workspaces still empty, and anything
      // redirecting on isAuthenticated (LoginPage) would briefly target the
      // no-workspaces fallback route instead of the real destination.
      const meResponse = await authApi.me()
      setUser(meResponse.user)
      setWorkspaces(meResponse.workspaces)
    } finally {
      setIsLoading(false)
    }
  }, [])

  const logout = useCallback(() => {
    clearTokens()
    setUser(null)
    setWorkspaces([])
  }, [])

  return (
    <AuthContext.Provider
      value={{ user, workspaces, isAuthenticated: user !== null, isLoading, login, logout }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) {
    throw new Error('useAuth must be used within AuthProvider')
  }
  return ctx
}
