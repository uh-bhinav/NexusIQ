import { NavLink, Outlet, useParams } from 'react-router-dom'
import { cn } from '@/lib/utils'
import { useAuth } from '@/features/auth/auth-context'
import { Button } from '@/components/ui/button'

const NAV_ITEMS: Array<{ to: string; label: string; end?: boolean }> = [
  { to: '', label: 'Dashboard', end: true },
  { to: 'knowledge', label: 'Knowledge Base' },
  { to: 'decisions', label: 'Decision Requests' },
  { to: 'approvals', label: 'Approval Queue' },
  { to: 'audit', label: 'Audit Log' },
  { to: 'metrics', label: 'System Metrics' },
]

export function AppLayout() {
  const { user, logout } = useAuth()
  const { workspaceId } = useParams<{ workspaceId: string }>()

  return (
    <div className="flex min-h-screen flex-col">
      <header className="flex items-center justify-between border-b px-6 py-3">
        <span className="text-sm font-semibold">NexusIQ</span>
        <div className="flex items-center gap-3 text-sm text-muted-foreground">
          {user ? (
            <>
              <span>
                {user.name} · {user.role}
              </span>
              <Button variant="ghost" size="sm" onClick={logout}>
                Log out
              </Button>
            </>
          ) : null}
        </div>
      </header>
      <div className="flex flex-1">
        <nav className="w-56 shrink-0 border-r p-3">
          <ul className="flex flex-col gap-1">
            {NAV_ITEMS.map((item) => (
              <li key={item.label}>
                <NavLink
                  to={`/w/${workspaceId}/${item.to}`}
                  end={item.end}
                  className={({ isActive }) =>
                    cn(
                      'block rounded-md px-3 py-2 text-sm hover:bg-accent',
                      isActive && 'bg-accent font-medium text-accent-foreground',
                    )
                  }
                >
                  {item.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
        <main className="flex-1 p-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
