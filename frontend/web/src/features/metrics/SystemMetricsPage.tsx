import { useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Bar, BarChart, CartesianGrid, XAxis, YAxis, ResponsiveContainer, Tooltip } from 'recharts'
import { getMetricsSummary } from '@/api/metrics'
import { AsyncState } from '@/components/async-state'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'

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

function toChartData(record: Record<string, number>) {
  return Object.entries(record).map(([name, count]) => ({ name, count }))
}

export function SystemMetricsPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>()
  if (!workspaceId) throw new Error('SystemMetricsPage rendered outside a workspace route')

  const metricsQuery = useQuery({
    queryKey: ['metrics-summary', workspaceId],
    queryFn: () => getMetricsSummary(workspaceId),
    refetchInterval: 15000,
  })

  return (
    <div className="flex flex-col gap-6">
      <h1 className="text-lg font-semibold">System Metrics</h1>

      <AsyncState
        isLoading={metricsQuery.isLoading}
        isError={metricsQuery.isError}
        error={metricsQuery.error}
        onRetry={() => metricsQuery.refetch()}
        isEmpty={metricsQuery.data?.total_decisions === 0}
        emptyTitle="No decisions yet"
        emptyDescription="Metrics populate once at least one decision has been processed."
      >
        {metricsQuery.data ? (
          <div className="flex flex-col gap-6">
            <div className="grid grid-cols-2 gap-4 md:grid-cols-5">
              <StatCard title="Total decisions" value={metricsQuery.data.total_decisions} />
              <StatCard title="Pending approvals" value={metricsQuery.data.pending_approvals} />
              <StatCard
                title="Avg confidence"
                value={metricsQuery.data.avg_confidence?.toFixed(2) ?? '—'}
              />
              <StatCard
                title="Avg cost / decision"
                value={
                  metricsQuery.data.avg_cost_usd != null ? `$${metricsQuery.data.avg_cost_usd.toFixed(4)}` : '—'
                }
              />
              <StatCard
                title="Avg latency"
                value={
                  metricsQuery.data.avg_latency_ms != null
                    ? `${Math.round(metricsQuery.data.avg_latency_ms)}ms`
                    : '—'
                }
              />
            </div>

            <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
              <Card>
                <CardHeader>
                  <CardTitle className="text-foreground">Decisions by status</CardTitle>
                </CardHeader>
                <CardContent className="h-64">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={toChartData(metricsQuery.data.decisions_by_status)}>
                      <CartesianGrid strokeDasharray="3 3" />
                      <XAxis dataKey="name" fontSize={12} />
                      <YAxis allowDecimals={false} fontSize={12} />
                      <Tooltip />
                      <Bar dataKey="count" fill="var(--color-primary)" />
                    </BarChart>
                  </ResponsiveContainer>
                </CardContent>
              </Card>
              <Card>
                <CardHeader>
                  <CardTitle className="text-foreground">Decisions by recommendation</CardTitle>
                </CardHeader>
                <CardContent className="h-64">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={toChartData(metricsQuery.data.decisions_by_recommendation)}>
                      <CartesianGrid strokeDasharray="3 3" />
                      <XAxis dataKey="name" fontSize={12} />
                      <YAxis allowDecimals={false} fontSize={12} />
                      <Tooltip />
                      <Bar dataKey="count" fill="var(--color-primary)" />
                    </BarChart>
                  </ResponsiveContainer>
                </CardContent>
              </Card>
            </div>
          </div>
        ) : null}
      </AsyncState>
    </div>
  )
}
