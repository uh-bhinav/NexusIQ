import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { getDecision } from '@/api/decisions'
import { listAuditForResource } from '@/api/audit'
import { useDecisionStream } from '@/features/decisions/use-decision-stream'
import { AsyncState } from '@/components/async-state'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'

function statusBadgeVariant(status: string): 'default' | 'secondary' | 'destructive' | 'success' | 'warning' {
  if (['SUCCESS', 'SATISFIED', 'APPROVED', 'AUTO_APPROVED', 'HUMAN_APPROVED'].includes(status)) return 'success'
  if (['FAILED', 'VIOLATED', 'REJECTED', 'HUMAN_REJECTED'].includes(status)) return 'destructive'
  if (['WAITING_FOR_APPROVAL', 'PARTIALLY_SATISFIED', 'PENDING'].includes(status)) return 'warning'
  return 'secondary'
}

export function DecisionDetailPage() {
  const { workspaceId, decisionId } = useParams<{ workspaceId: string; decisionId: string }>()
  if (!workspaceId || !decisionId) throw new Error('DecisionDetailPage rendered outside its route')

  const { isLive } = useDecisionStream(workspaceId, decisionId)

  const decisionQuery = useQuery({
    queryKey: ['decision', workspaceId, decisionId],
    queryFn: () => getDecision(workspaceId, decisionId),
    // The SSE hook invalidates this on every event; a short poll interval
    // is only a backstop for the very first load and for the (rare) window
    // before the stream connects.
    refetchInterval: 5000,
  })
  const auditQuery = useQuery({
    queryKey: ['audit', 'decision', decisionId],
    queryFn: () => listAuditForResource(workspaceId, 'decision', decisionId),
  })

  return (
    <AsyncState
      isLoading={decisionQuery.isLoading}
      isError={decisionQuery.isError}
      error={decisionQuery.error}
      onRetry={() => decisionQuery.refetch()}
      isEmpty={false}
      emptyTitle="Decision not found"
    >
      {decisionQuery.data ? (
        <div className="flex flex-col gap-6">
          <div className="flex items-start justify-between">
            <div>
              <h1 className="text-lg font-semibold">{decisionQuery.data.title}</h1>
              <p className="text-sm text-muted-foreground">{decisionQuery.data.question}</p>
            </div>
            <div className="flex items-center gap-2">
              <span
                className={`h-2 w-2 rounded-full ${isLive ? 'bg-success' : 'bg-warning'}`}
                title={isLive ? 'Live (SSE connected)' : 'Polling (SSE unavailable)'}
              />
              <span className="text-xs text-muted-foreground">{isLive ? 'Live' : 'Polling'}</span>
              <Badge variant={statusBadgeVariant(decisionQuery.data.status)}>{decisionQuery.data.status}</Badge>
            </div>
          </div>

          {decisionQuery.data.outcome ? (
            <Card>
              <CardHeader>
                <CardTitle className="text-foreground">Recommendation</CardTitle>
              </CardHeader>
              <CardContent className="flex flex-col gap-3">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge variant={statusBadgeVariant(decisionQuery.data.outcome.recommendation)}>
                    {decisionQuery.data.outcome.recommendation}
                  </Badge>
                  <Badge variant="outline">
                    confidence {decisionQuery.data.outcome.confidence != null ? decisionQuery.data.outcome.confidence.toFixed(2) : '—'}
                  </Badge>
                  <Badge variant="outline">risk {decisionQuery.data.outcome.risk_level}</Badge>
                  <Badge variant={statusBadgeVariant(decisionQuery.data.outcome.final_status)}>
                    {decisionQuery.data.outcome.final_status}
                  </Badge>
                </div>
                <p className="text-sm">{decisionQuery.data.outcome.reasoning_summary}</p>
                {decisionQuery.data.outcome.requires_human_approval ? (
                  <div className="rounded-md border border-warning/40 bg-warning/5 p-3 text-sm">
                    <p className="font-medium">Escalated for human approval</p>
                    <ul className="mt-1 list-disc pl-4 text-muted-foreground">
                      {decisionQuery.data.outcome.escalation_reasons.map((reason) => (
                        <li key={reason}>{reason}</li>
                      ))}
                    </ul>
                  </div>
                ) : null}
              </CardContent>
            </Card>
          ) : null}

          {decisionQuery.data.findings.length > 0 ? (
            <div>
              <h2 className="mb-2 text-sm font-medium text-muted-foreground">Policy findings</h2>
              <ul className="flex flex-col gap-2">
                {decisionQuery.data.findings.map((finding) => (
                  <li key={finding.id}>
                    <Card>
                      <CardContent className="flex flex-col gap-1 pt-4">
                        <div className="flex items-center gap-2">
                          <Badge variant="outline">{finding.category}</Badge>
                          {finding.status ? (
                            <Badge variant={statusBadgeVariant(finding.status)}>{finding.status}</Badge>
                          ) : null}
                          {finding.severity ? <Badge variant="outline">{finding.severity}</Badge> : null}
                          <span className="text-sm font-medium">{finding.title}</span>
                        </div>
                        <p className="text-sm text-muted-foreground">{finding.description}</p>
                      </CardContent>
                    </Card>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}

          {decisionQuery.data.evidence.length > 0 ? (
            <div>
              <h2 className="mb-2 text-sm font-medium text-muted-foreground">Evidence</h2>
              <ul className="flex flex-col gap-2">
                {decisionQuery.data.evidence.map((item) => (
                  <li key={item.id}>
                    <Card>
                      <CardContent className="flex flex-col gap-1 pt-4">
                        <div className="flex items-center justify-between">
                          <Link
                            to={`/w/${workspaceId}/documents/${item.document_id}?chunk=${item.chunk_id}`}
                            className="text-sm font-medium hover:underline"
                          >
                            {item.citation_reference}
                          </Link>
                          <Badge variant="secondary">
                            {item.relevance_score != null ? `${(item.relevance_score * 100).toFixed(0)}% relevant` : 'relevance unknown'}
                          </Badge>
                        </div>
                        <p className="text-sm text-muted-foreground">{item.evidence_text}</p>
                      </CardContent>
                    </Card>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}

          <div>
            <h2 className="mb-2 text-sm font-medium text-muted-foreground">Agent execution timeline</h2>
            {decisionQuery.data.agent_executions.length === 0 ? (
              <p className="text-sm text-muted-foreground">No agent has completed yet.</p>
            ) : (
              <div className="overflow-x-auto rounded-lg border">
                <table className="w-full text-sm">
                  <thead className="bg-muted/50 text-left text-xs text-muted-foreground">
                    <tr>
                      <th className="p-2">#</th>
                      <th className="p-2">Agent</th>
                      <th className="p-2">Status</th>
                      <th className="p-2">Model</th>
                      <th className="p-2">Tokens (in/out)</th>
                      <th className="p-2">Cost</th>
                      <th className="p-2">Latency</th>
                    </tr>
                  </thead>
                  <tbody>
                    {decisionQuery.data.agent_executions
                      .slice()
                      .sort((a, b) => a.sequence_index - b.sequence_index)
                      .map((exec) => (
                        <tr key={exec.id} className="border-t">
                          <td className="p-2">{exec.sequence_index}</td>
                          <td className="p-2">{exec.agent_name}</td>
                          <td className="p-2">
                            <Badge variant={statusBadgeVariant(exec.status)}>{exec.status}</Badge>
                          </td>
                          <td className="p-2">{exec.model ?? '—'}</td>
                          <td className="p-2">
                            {exec.input_tokens}/{exec.output_tokens}
                          </td>
                          <td className="p-2">
                            {exec.estimated_cost_usd != null ? `$${exec.estimated_cost_usd.toFixed(4)}` : '—'}
                          </td>
                          <td className="p-2">{exec.latency_ms}ms</td>
                        </tr>
                      ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          {decisionQuery.data.run ? (
            <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
              <Card>
                <CardHeader>
                  <CardTitle>Total tokens</CardTitle>
                </CardHeader>
                <CardContent>
                  {decisionQuery.data.run.total_input_tokens + decisionQuery.data.run.total_output_tokens}
                </CardContent>
              </Card>
              <Card>
                <CardHeader>
                  <CardTitle>Total cost</CardTitle>
                </CardHeader>
                <CardContent>
                  {decisionQuery.data.run.estimated_cost_usd != null
                    ? `$${decisionQuery.data.run.estimated_cost_usd.toFixed(4)}`
                    : '—'}
                </CardContent>
              </Card>
              <Card>
                <CardHeader>
                  <CardTitle>Latency</CardTitle>
                </CardHeader>
                <CardContent>
                  {decisionQuery.data.run.latency_ms != null ? `${decisionQuery.data.run.latency_ms}ms` : '—'}
                </CardContent>
              </Card>
              <Card>
                <CardHeader>
                  <CardTitle>Run status</CardTitle>
                </CardHeader>
                <CardContent>
                  <Badge variant={statusBadgeVariant(decisionQuery.data.run.status)}>
                    {decisionQuery.data.run.status}
                  </Badge>
                </CardContent>
              </Card>
            </div>
          ) : null}

          <div>
            <h2 className="mb-2 text-sm font-medium text-muted-foreground">Audit history</h2>
            <AsyncState
              isLoading={auditQuery.isLoading}
              isError={auditQuery.isError}
              error={auditQuery.error}
              onRetry={() => auditQuery.refetch()}
              isEmpty={auditQuery.data?.content.length === 0}
              emptyTitle="No audit events for this decision yet"
            >
              <ul className="flex flex-col gap-1 text-sm">
                {auditQuery.data?.content.map((event) => (
                  <li key={event.id} className="flex items-center justify-between rounded-md border p-2">
                    <span>{event.event_type}</span>
                    <span className="text-muted-foreground">{new Date(event.occurred_at).toLocaleString()}</span>
                  </li>
                ))}
              </ul>
            </AsyncState>
          </div>
        </div>
      ) : null}
    </AsyncState>
  )
}
