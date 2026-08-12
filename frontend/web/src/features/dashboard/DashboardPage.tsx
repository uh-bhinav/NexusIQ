import { useParams, Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { getMetricsSummary } from '@/api/metrics'
import { listDecisions } from '@/api/decisions'
import { MembersSection } from '@/features/dashboard/MembersSection'
import { AsyncState } from '@/components/async-state'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'

function StatCard({ title, value }: { title: string; value: string | number }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
      </CardHeader>
      <CardContent className="text-2xl font-semibold">{value}</CardContent>
    </Card>
  )
}

export function DashboardPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>()
  if (!workspaceId) throw new Error('DashboardPage rendered outside a workspace route')

  const metricsQuery = useQuery({
    queryKey: ['metrics-summary', workspaceId],
    queryFn: () => getMetricsSummary(workspaceId),
  })
  const decisionsQuery = useQuery({
    queryKey: ['decisions', workspaceId, 0],
    queryFn: () => listDecisions(workspaceId, 0, 5),
  })

  return (
    <div className="flex flex-col gap-6">
      <h1 className="text-lg font-semibold">Dashboard</h1>

      <AsyncState
        isLoading={metricsQuery.isLoading}
        isError={metricsQuery.isError}
        error={metricsQuery.error}
        onRetry={() => metricsQuery.refetch()}
        isEmpty={false}
        emptyTitle="No metrics yet"
      >
        {metricsQuery.data ? (
          <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
            <StatCard title="Total decisions" value={metricsQuery.data.total_decisions} />
            <StatCard title="Pending approvals" value={metricsQuery.data.pending_approvals} />
            <StatCard
              title="Avg confidence"
              value={
                metricsQuery.data.avg_confidence != null
                  ? metricsQuery.data.avg_confidence.toFixed(2)
                  : '—'
              }
            />
            <StatCard
              title="Avg cost / decision"
              value={
                metricsQuery.data.avg_cost_usd != null
                  ? `$${metricsQuery.data.avg_cost_usd.toFixed(4)}`
                  : '—'
              }
            />
          </div>
        ) : null}
      </AsyncState>

      <div>
        <h2 className="mb-2 text-sm font-medium text-muted-foreground">Recent decisions</h2>
        <AsyncState
          isLoading={decisionsQuery.isLoading}
          isError={decisionsQuery.isError}
          error={decisionsQuery.error}
          onRetry={() => decisionsQuery.refetch()}
          isEmpty={decisionsQuery.data?.content.length === 0}
          emptyTitle="No decisions yet"
          emptyDescription="Submit a decision request from the Decision Requests page to get started."
        >
          <ul className="flex flex-col gap-2">
            {decisionsQuery.data?.content.map((decision) => (
              <li key={decision.id}>
                <Link
                  to={`/w/${workspaceId}/decisions/${decision.id}`}
                  className="flex items-center justify-between rounded-md border p-3 text-sm hover:bg-accent"
                >
                  <span>{decision.title}</span>
                  <Badge variant="outline">{decision.status}</Badge>
                </Link>
              </li>
            ))}
          </ul>
        </AsyncState>
      </div>

      <MembersSection workspaceId={workspaceId} />
    </div>
  )
}
