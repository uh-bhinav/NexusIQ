import { useEffect, useState } from 'react'
import { useParams, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { getDocument, listChunks } from '@/api/documents'
import { AsyncState } from '@/components/async-state'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { cn } from '@/lib/utils'

export function DocumentDetailPage() {
  const { workspaceId, documentId } = useParams<{ workspaceId: string; documentId: string }>()
  if (!workspaceId || !documentId) throw new Error('DocumentDetailPage rendered outside its route')
  const [searchParams] = useSearchParams()
  const highlightedChunkId = searchParams.get('chunk')

  const [page, setPage] = useState(0)

  const documentQuery = useQuery({
    queryKey: ['document', workspaceId, documentId],
    queryFn: () => getDocument(workspaceId, documentId),
  })
  const chunksQuery = useQuery({
    queryKey: ['chunks', workspaceId, documentId, page],
    queryFn: () => listChunks(workspaceId, documentId, page),
  })

  useEffect(() => {
    if (!highlightedChunkId) return
    // Only scrolls if the linked chunk is on the currently-loaded page
    // (page 0 by default) — a citation from a later page of a very long
    // document won't auto-paginate to find it. A known, small limitation,
    // not attempted here.
    document.getElementById(`chunk-${highlightedChunkId}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }, [highlightedChunkId, chunksQuery.data])

  return (
    <AsyncState
      isLoading={documentQuery.isLoading}
      isError={documentQuery.isError}
      error={documentQuery.error}
      onRetry={() => documentQuery.refetch()}
      isEmpty={false}
      emptyTitle="Document not found"
    >
      {documentQuery.data ? (
        <div className="flex flex-col gap-6">
          <div className="flex items-start justify-between">
            <div>
              <h1 className="text-lg font-semibold">{documentQuery.data.name}</h1>
              <p className="text-sm text-muted-foreground">
                {documentQuery.data.document_type} · v{documentQuery.data.version}
                {documentQuery.data.is_current ? '' : ' (superseded)'}
              </p>
            </div>
            <div className="flex gap-2">
              <Badge
                variant={
                  documentQuery.data.status === 'READY'
                    ? 'success'
                    : documentQuery.data.status === 'FAILED'
                      ? 'destructive'
                      : 'warning'
                }
              >
                {documentQuery.data.status}
              </Badge>
            </div>
          </div>

          {documentQuery.data.failure_reason ? (
            <p role="alert" className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm text-destructive">
              {documentQuery.data.failure_reason}
            </p>
          ) : null}

          <Card>
            <CardHeader>
              <CardTitle className="text-foreground">Details</CardTitle>
            </CardHeader>
            <CardContent className="grid grid-cols-2 gap-3 text-sm md:grid-cols-4">
              <div>
                <p className="text-muted-foreground">Chunks</p>
                <p>{documentQuery.data.chunk_count}</p>
              </div>
              <div>
                <p className="text-muted-foreground">Uploaded</p>
                <p>{new Date(documentQuery.data.created_at).toLocaleString()}</p>
              </div>
              <div>
                <p className="text-muted-foreground">Updated</p>
                <p>{new Date(documentQuery.data.updated_at).toLocaleString()}</p>
              </div>
              <div>
                <p className="text-muted-foreground">Uploaded by</p>
                <p>{documentQuery.data.uploaded_by.slice(0, 8)}…</p>
              </div>
            </CardContent>
          </Card>

          <div>
            <h2 className="mb-2 text-sm font-medium text-muted-foreground">Content</h2>
            <AsyncState
              isLoading={chunksQuery.isLoading}
              isError={chunksQuery.isError}
              error={chunksQuery.error}
              onRetry={() => chunksQuery.refetch()}
              isEmpty={chunksQuery.data?.content.length === 0}
              emptyTitle="No content yet"
              emptyDescription="This document hasn't finished ingesting, or ingestion produced no chunks."
            >
              <ul className="flex flex-col gap-2">
                {chunksQuery.data?.content.map((chunk) => (
                  <li key={chunk.id} id={`chunk-${chunk.id}`}>
                    <Card
                      className={cn(chunk.id === highlightedChunkId && 'border-primary ring-2 ring-primary/30')}
                    >
                      <CardContent className="flex flex-col gap-1 pt-4">
                        <div className="flex items-center gap-2 text-xs text-muted-foreground">
                          <span>#{chunk.chunk_index}</span>
                          {chunk.section ? <span>§{chunk.section}</span> : null}
                          {chunk.page_number != null ? <span>p.{chunk.page_number}</span> : null}
                          {chunk.is_flagged ? <Badge variant="destructive">Flagged</Badge> : null}
                        </div>
                        <p className="text-sm">{chunk.content}</p>
                      </CardContent>
                    </Card>
                  </li>
                ))}
              </ul>

              <div className="mt-3 flex items-center justify-between">
                <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
                  Previous
                </Button>
                <span className="text-xs text-muted-foreground">
                  Page {(chunksQuery.data?.page ?? 0) + 1} of {Math.max(1, chunksQuery.data?.total_pages ?? 1)}
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={!chunksQuery.data || page + 1 >= chunksQuery.data.total_pages}
                  onClick={() => setPage((p) => p + 1)}
                >
                  Next
                </Button>
              </div>
            </AsyncState>
          </div>
        </div>
      ) : null}
    </AsyncState>
  )
}
