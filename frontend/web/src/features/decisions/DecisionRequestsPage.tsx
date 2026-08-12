import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createDecision, listDecisions } from '@/api/decisions'
import type { DecisionPriority } from '@/api/schemas'
import { AsyncState } from '@/components/async-state'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { HttpError } from '@/api/client'

const PRIORITIES: DecisionPriority[] = ['LOW', 'NORMAL', 'HIGH', 'URGENT']

function NewDecisionForm({ workspaceId }: { workspaceId: string }) {
  const queryClient = useQueryClient()
  const [title, setTitle] = useState('')
  const [question, setQuestion] = useState('')
  const [priority, setPriority] = useState<DecisionPriority>('NORMAL')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => createDecision(workspaceId, title, question, priority),
    onSuccess: () => {
      setTitle('')
      setQuestion('')
      void queryClient.invalidateQueries({ queryKey: ['decisions', workspaceId] })
    },
    onError: (err) => setError(err instanceof HttpError ? err.message : 'Failed to submit decision.'),
  })

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    mutation.mutate()
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-foreground">Submit a decision request</CardTitle>
      </CardHeader>
      <CardContent>
        <form onSubmit={handleSubmit} className="flex flex-col gap-3" aria-label="Submit a decision request">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="title">Title</Label>
            <Input id="title" required value={title} onChange={(e) => setTitle(e.target.value)} />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="question">Question</Label>
            <textarea
              id="question"
              required
              rows={3}
              className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              placeholder="Should Vendor Alpha be approved for EU production?"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="priority">Priority</Label>
            <select
              id="priority"
              className="flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm"
              value={priority}
              onChange={(e) => setPriority(e.target.value as DecisionPriority)}
            >
              {PRIORITIES.map((p) => (
                <option key={p} value={p}>
                  {p}
                </option>
              ))}
            </select>
          </div>
          {error ? (
            <p role="alert" className="text-sm text-destructive">
              {error}
            </p>
          ) : null}
          <Button type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? 'Submitting…' : 'Submit'}
          </Button>
        </form>
      </CardContent>
    </Card>
  )
}

export function DecisionRequestsPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>()
  if (!workspaceId) throw new Error('DecisionRequestsPage rendered outside a workspace route')

  const decisionsQuery = useQuery({
    queryKey: ['decisions', workspaceId, 0],
    queryFn: () => listDecisions(workspaceId, 0, 20),
  })

  return (
    <div className="flex flex-col gap-6">
      <h1 className="text-lg font-semibold">Decision Requests</h1>

      <NewDecisionForm workspaceId={workspaceId} />

      <AsyncState
        isLoading={decisionsQuery.isLoading}
        isError={decisionsQuery.isError}
        error={decisionsQuery.error}
        onRetry={() => decisionsQuery.refetch()}
        isEmpty={decisionsQuery.data?.content.length === 0}
        emptyTitle="No decision requests yet"
        emptyDescription="Submit one above to get started."
      >
        <ul className="flex flex-col gap-2">
          {decisionsQuery.data?.content.map((decision) => (
            <li key={decision.id}>
              <Link
                to={`/w/${workspaceId}/decisions/${decision.id}`}
                className="flex items-center justify-between rounded-md border p-3 text-sm hover:bg-accent"
              >
                <div>
                  <p className="font-medium">{decision.title}</p>
                  <p className="text-muted-foreground">{decision.question}</p>
                </div>
                <div className="flex gap-2">
                  <Badge variant="outline">{decision.priority}</Badge>
                  <Badge>{decision.status}</Badge>
                </div>
              </Link>
            </li>
          ))}
        </ul>
      </AsyncState>
    </div>
  )
}
