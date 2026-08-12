import type { ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'

/** Every async view needs these four states (.claude/rules/frontend.md) — this
 * component is the one place that decides which of them to render, so no
 * feature page can accidentally ship only the happy path. */
export function AsyncState({
  isLoading,
  isError,
  error,
  isEmpty,
  onRetry,
  emptyTitle,
  emptyDescription,
  loadingSkeleton,
  children,
}: {
  isLoading: boolean
  isError: boolean
  error?: unknown
  isEmpty: boolean
  onRetry?: () => void
  emptyTitle: string
  emptyDescription?: string
  loadingSkeleton?: ReactNode
  children: ReactNode
}) {
  if (isLoading) {
    return (
      loadingSkeleton ?? (
        <div className="space-y-3" role="status" aria-label="Loading">
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-8 w-3/4" />
        </div>
      )
    )
  }

  if (isError) {
    const message = error instanceof Error ? error.message : 'Something went wrong.'
    return (
      <div
        role="alert"
        className="flex flex-col items-start gap-3 rounded-lg border border-destructive/40 bg-destructive/5 p-4 text-sm"
      >
        <p className="font-medium text-destructive">Failed to load</p>
        <p className="text-muted-foreground">{message}</p>
        {onRetry ? (
          <Button variant="outline" size="sm" onClick={onRetry}>
            Retry
          </Button>
        ) : null}
      </div>
    )
  }

  if (isEmpty) {
    return (
      <div className="flex flex-col items-start gap-1 rounded-lg border border-dashed p-6 text-sm">
        <p className="font-medium">{emptyTitle}</p>
        {emptyDescription ? <p className="text-muted-foreground">{emptyDescription}</p> : null}
      </div>
    )
  }

  return <>{children}</>
}
