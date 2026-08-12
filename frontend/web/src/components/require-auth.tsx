import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '@/features/auth/auth-context'

/** Mirrors, never replaces, server-side auth (.claude/rules/frontend.md) —
 * every route this guards is still independently authorized by spring-api;
 * this only avoids flashing an authenticated page before a 401 bounces the
 * user back. */
export function RequireAuth() {
  const { isAuthenticated } = useAuth()
  const location = useLocation()

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />
  }
  return <Outlet />
}

/** Same mirroring principle, for role-gated routes (e.g. the approval queue's
 * action buttons) — the server still rejects a VIEWER's direct API call
 * regardless of what this renders. */
export function RequireRole({ roles }: { roles: Array<'ADMIN' | 'ANALYST' | 'APPROVER' | 'VIEWER'> }) {
  const { user } = useAuth()

  if (!user || !roles.includes(user.role)) {
    return <Navigate to="/" replace />
  }
  return <Outlet />
}
