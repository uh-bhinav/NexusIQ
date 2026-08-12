import { useState, type FormEvent } from 'react'
import { Navigate } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '@/features/auth/auth-context'
import { createWorkspace } from '@/api/workspaces'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { HttpError } from '@/api/client'

/** Landing route for an authenticated user with no workspace in the URL yet
 * — picks the first membership automatically, or offers to create one if
 * this is a brand new account (no mock/placeholder workspace ever shown). */
export function WorkspaceLandingPage() {
  const { workspaces } = useAuth()
  const queryClient = useQueryClient()
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => createWorkspace(name),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['me'] })
      window.location.reload()
    },
    onError: (err) => setError(err instanceof HttpError ? err.message : 'Failed to create workspace.'),
  })

  if (workspaces.length > 0) {
    const first = workspaces[0]
    if (!first) throw new Error('unreachable: workspaces.length > 0')
    return <Navigate to={`/w/${first.id}`} replace />
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    mutation.mutate()
  }

  return (
    <div className="flex min-h-screen items-center justify-center p-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle className="text-foreground">Create your first workspace</CardTitle>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit} className="flex flex-col gap-4" aria-label="Create workspace">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="workspace-name">Workspace name</Label>
              <Input id="workspace-name" required value={name} onChange={(e) => setName(e.target.value)} />
            </div>
            {error ? (
              <p role="alert" className="text-sm text-destructive">
                {error}
              </p>
            ) : null}
            <Button type="submit" disabled={mutation.isPending || name.trim().length === 0}>
              {mutation.isPending ? 'Creating…' : 'Create workspace'}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
