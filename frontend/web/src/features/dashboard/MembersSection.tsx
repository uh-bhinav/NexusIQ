import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { listMembers, addMember } from '@/api/workspaces'
import { useAuth } from '@/features/auth/auth-context'
import type { Role } from '@/api/schemas'
import { AsyncState } from '@/components/async-state'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { HttpError } from '@/api/client'

const ROLES: Role[] = ['ADMIN', 'ANALYST', 'APPROVER', 'VIEWER']

function AddMemberForm({ workspaceId }: { workspaceId: string }) {
  const queryClient = useQueryClient()
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<Role>('VIEWER')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => addMember(workspaceId, email, role),
    onSuccess: () => {
      setEmail('')
      setRole('VIEWER')
      void queryClient.invalidateQueries({ queryKey: ['members', workspaceId] })
    },
    onError: (err) => setError(err instanceof HttpError ? err.message : 'Failed to add member.'),
  })

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    mutation.mutate()
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-wrap items-end gap-2" aria-label="Add a member">
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="member-email">Email</Label>
        <Input
          id="member-email"
          type="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="member-role">Role</Label>
        <select
          id="member-role"
          className="flex h-9 rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm"
          value={role}
          onChange={(e) => setRole(e.target.value as Role)}
        >
          {ROLES.map((r) => (
            <option key={r} value={r}>
              {r}
            </option>
          ))}
        </select>
      </div>
      <Button type="submit" disabled={email.trim().length === 0 || mutation.isPending}>
        {mutation.isPending ? 'Adding…' : 'Add member'}
      </Button>
      {error ? (
        <p role="alert" className="w-full text-sm text-destructive">
          {error}
        </p>
      ) : null}
    </form>
  )
}

export function MembersSection({ workspaceId }: { workspaceId: string }) {
  const { workspaces } = useAuth()
  const currentWorkspace = workspaces.find((w) => w.id === workspaceId)
  const isAdmin = currentWorkspace?.role === 'ADMIN'

  const membersQuery = useQuery({
    queryKey: ['members', workspaceId],
    queryFn: () => listMembers(workspaceId),
  })

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-foreground">Members</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {/* The server enforces ADMIN-only on the add-member endpoint
            regardless of this check — hiding the form for non-admins is UX,
            not the security boundary (.claude/rules/frontend.md). */}
        {isAdmin ? <AddMemberForm workspaceId={workspaceId} /> : null}

        <AsyncState
          isLoading={membersQuery.isLoading}
          isError={membersQuery.isError}
          error={membersQuery.error}
          onRetry={() => membersQuery.refetch()}
          isEmpty={membersQuery.data?.length === 0}
          emptyTitle="No members yet"
        >
          <ul className="flex flex-col gap-2">
            {membersQuery.data?.map((member) => (
              <li
                key={member.user_id}
                className="flex items-center justify-between rounded-md border p-2 text-sm"
              >
                <div>
                  <p className="font-medium">{member.name}</p>
                  <p className="text-muted-foreground">{member.email}</p>
                </div>
                <Badge variant="outline">{member.role}</Badge>
              </li>
            ))}
          </ul>
        </AsyncState>
      </CardContent>
    </Card>
  )
}
