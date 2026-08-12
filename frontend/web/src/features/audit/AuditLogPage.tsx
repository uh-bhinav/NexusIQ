import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { listAuditEvents } from '@/api/audit'
import { AsyncState } from '@/components/async-state'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'

function formatMetadata(metadata: string | null | undefined): string | null {
  if (!metadata) return null
  try {
    return JSON.stringify(JSON.parse(metadata))
  } catch {
    return metadata
  }
}

export function AuditLogPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>()
  if (!workspaceId) throw new Error('AuditLogPage rendered outside a workspace route')

  const [page, setPage] = useState(0)
  const auditQuery = useQuery({
    queryKey: ['audit', workspaceId, page],
    queryFn: () => listAuditEvents(workspaceId, page),
  })

  return (
    <div className="flex flex-col gap-6">
      <h1 className="text-lg font-semibold">Audit Log</h1>

      <AsyncState
        isLoading={auditQuery.isLoading}
        isError={auditQuery.isError}
        error={auditQuery.error}
        onRetry={() => auditQuery.refetch()}
        isEmpty={auditQuery.data?.content.length === 0}
        emptyTitle="No audit events yet"
        emptyDescription="Every security- or decision-relevant action in this workspace appears here."
      >
        <div className="overflow-x-auto rounded-lg border">
          <table className="w-full text-sm">
            <thead className="bg-muted/50 text-left text-xs text-muted-foreground">
              <tr>
                <th className="p-2">When</th>
                <th className="p-2">Event</th>
                <th className="p-2">Resource</th>
                <th className="p-2">Actor</th>
                <th className="p-2">Correlation ID</th>
                <th className="p-2">Metadata</th>
              </tr>
            </thead>
            <tbody>
              {auditQuery.data?.content.map((event) => (
                <tr key={event.id} className="border-t align-top">
                  <td className="whitespace-nowrap p-2 text-muted-foreground">
                    {new Date(event.occurred_at).toLocaleString()}
                  </td>
                  <td className="p-2">
                    <Badge variant="outline">{event.event_type}</Badge>
                  </td>
                  <td className="p-2">
                    {event.resource_type}
                    {event.resource_id ? `/${event.resource_id.slice(0, 8)}…` : ''}
                  </td>
                  <td className="p-2">{event.actor_id ? `${event.actor_id.slice(0, 8)}…` : '—'}</td>
                  <td className="p-2 font-mono text-xs text-muted-foreground">
                    {event.correlation_id ? `${event.correlation_id.slice(0, 8)}…` : '—'}
                  </td>
                  <td className="max-w-xs truncate p-2 text-xs text-muted-foreground">
                    {formatMetadata(event.metadata) ?? '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className="mt-3 flex items-center justify-between">
          <Button
            variant="outline"
            size="sm"
            disabled={page === 0}
            onClick={() => setPage((p) => Math.max(0, p - 1))}
          >
            Previous
          </Button>
          <span className="text-xs text-muted-foreground">
            Page {(auditQuery.data?.page ?? 0) + 1} of {Math.max(1, auditQuery.data?.total_pages ?? 1)}
          </span>
          <Button
            variant="outline"
            size="sm"
            disabled={!auditQuery.data || page + 1 >= auditQuery.data.total_pages}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </Button>
        </div>
      </AsyncState>
    </div>
  )
}
