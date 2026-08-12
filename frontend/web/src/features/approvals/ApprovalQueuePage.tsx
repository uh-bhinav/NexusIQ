import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { listApprovals, approveDecision, rejectDecision } from '@/api/approvals'
import type { ApprovalStatus } from '@/api/schemas'
import { useAuth } from '@/features/auth/auth-context'
import { AsyncState } from '@/components/async-state'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { HttpError } from '@/api/client'

const CAN_ACT_ROLES = new Set(['APPROVER', 'ADMIN'])

function ApprovalActions({ workspaceId, approvalId }: { workspaceId: string; approvalId: string }) {
  const queryClient = useQueryClient()
  const [notes, setNotes] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [mode, setMode] = useState<'idle' | 'rejecting'>('idle')

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ['approvals', workspaceId] })
  }

  const approveMutation = useMutation({
    mutationFn: () => approveDecision(workspaceId, approvalId, notes || undefined),
    onSuccess: invalidate,
    onError: (err) => setError(err instanceof HttpError ? err.message : 'Failed to approve.'),
  })
  const rejectMutation = useMutation({
    mutationFn: () => rejectDecision(workspaceId, approvalId, reason),
    onSuccess: invalidate,
    onError: (err) => setError(err instanceof HttpError ? err.message : 'Failed to reject.'),
  })

  if (mode === 'rejecting') {
    return (
      <div className="flex flex-col gap-2">
        <Input
          placeholder="Reason for rejection (required)"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          aria-label="Rejection reason"
        />
        {error ? <p className="text-xs text-destructive">{error}</p> : null}
        <div className="flex gap-2">
          <Button
            variant="destructive"
            size="sm"
            disabled={reason.trim().length === 0 || rejectMutation.isPending}
            onClick={() => {
              setError(null)
              rejectMutation.mutate()
            }}
          >
            Confirm reject
          </Button>
          <Button variant="ghost" size="sm" onClick={() => setMode('idle')}>
            Cancel
          </Button>
        </div>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-2">
      <Input
        placeholder="Approval notes (optional)"
        value={notes}
        onChange={(e) => setNotes(e.target.value)}
        aria-label="Approval notes"
      />
      {error ? <p className="text-xs text-destructive">{error}</p> : null}
      <div className="flex gap-2">
        <Button
          size="sm"
          disabled={approveMutation.isPending}
          onClick={() => {
            setError(null)
            approveMutation.mutate()
          }}
        >
          Approve
        </Button>
        <Button variant="outline" size="sm" onClick={() => setMode('rejecting')}>
          Reject
        </Button>
      </div>
    </div>
  )
}

export function ApprovalQueuePage() {
  const { workspaceId } = useParams<{ workspaceId: string }>()
  if (!workspaceId) throw new Error('ApprovalQueuePage rendered outside a workspace route')
  const { workspaces } = useAuth()
  // ApprovalService.approve/reject authorize on the *workspace-level* role
  // (WorkspaceAccessService.requireRole), not the global one — checking
  // user.role here would hide the buttons from someone the server would
  // actually let act (e.g. globally ANALYST but workspace ADMIN), or show
  // them to someone the server would 403 (globally APPROVER but only a
  // VIEWER in this specific workspace). Mirrors MembersSection.tsx.
  const currentWorkspace = workspaces.find((w) => w.id === workspaceId)
  const canAct = currentWorkspace !== undefined && CAN_ACT_ROLES.has(currentWorkspace.role)

  const [status, setStatus] = useState<ApprovalStatus | undefined>('PENDING')

  const approvalsQuery = useQuery({
    queryKey: ['approvals', workspaceId, status],
    queryFn: () => listApprovals(workspaceId, status),
  })

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold">Approval Queue</h1>
        <div className="flex gap-2">
          {(['PENDING', 'APPROVED', 'REJECTED', undefined] as const).map((s) => (
            <Button
              key={s ?? 'all'}
              variant={status === s ? 'default' : 'outline'}
              size="sm"
              onClick={() => setStatus(s)}
            >
              {s ?? 'All'}
            </Button>
          ))}
        </div>
      </div>

      {!canAct ? (
        <p className="text-sm text-muted-foreground">
          You can view the queue, but only an Approver or Admin may act on it.
        </p>
      ) : null}

      <AsyncState
        isLoading={approvalsQuery.isLoading}
        isError={approvalsQuery.isError}
        error={approvalsQuery.error}
        onRetry={() => approvalsQuery.refetch()}
        isEmpty={approvalsQuery.data?.content.length === 0}
        emptyTitle="Nothing here"
        emptyDescription="No approvals match this filter."
      >
        <ul className="flex flex-col gap-3">
          {approvalsQuery.data?.content.map((approval) => (
            <li key={approval.id}>
              <Card>
                <CardContent className="flex flex-col gap-3 pt-4">
                  <div className="flex items-start justify-between">
                    <div>
                      <Link
                        to={`/w/${workspaceId}/decisions/${approval.decision_request_id}`}
                        className="text-sm font-medium hover:underline"
                      >
                        {approval.decision_title}
                      </Link>
                      <ul className="mt-1 list-disc pl-4 text-xs text-muted-foreground">
                        {approval.reasons.map((reason) => (
                          <li key={reason}>{reason}</li>
                        ))}
                      </ul>
                    </div>
                    <Badge
                      variant={
                        approval.status === 'APPROVED'
                          ? 'success'
                          : approval.status === 'REJECTED'
                            ? 'destructive'
                            : 'warning'
                      }
                    >
                      {approval.status}
                    </Badge>
                  </div>
                  {approval.status === 'PENDING' && canAct ? (
                    <ApprovalActions workspaceId={workspaceId} approvalId={approval.id} />
                  ) : null}
                  {approval.status !== 'PENDING' ? (
                    <p className="text-xs text-muted-foreground">
                      {approval.status === 'APPROVED' ? 'Approved' : 'Rejected'} at{' '}
                      {approval.resolved_at ? new Date(approval.resolved_at).toLocaleString() : '—'}
                      {approval.resolution_notes ? ` — "${approval.resolution_notes}"` : ''}
                    </p>
                  ) : null}
                </CardContent>
              </Card>
            </li>
          ))}
        </ul>
      </AsyncState>
    </div>
  )
}
