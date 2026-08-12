import { useEffect, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { connectSse } from '@/lib/sse-client'
import { issueStreamToken, decisionStreamUrl } from '@/api/decisions'

const STREAM_EVENT_NAMES = [
  'decision.status',
  'agent.completed',
  'agent.failed',
  'approval.required',
  'decision.completed',
  'decision.failed',
]
const TERMINAL_EVENT_NAMES = ['decision.completed', 'decision.failed']

/** Drives the Decision Detail page's live updates. Deliberately doesn't
 * parse event bodies into UI state itself — every event (including the
 * poll-fallback's own timer) just invalidates the `['decision', ...]`
 * query, and the already-progressive `GET .../decisions/{id}` response
 * (agent_executions grows one row per completed node) does the rendering.
 * This is what "reconcile with a fresh GET — never assume the stream was
 * complete" (docs/API/API_DESIGN.md) means in practice: the stream is a
 * signal to refetch, not a second source of truth. */
export function useDecisionStream(workspaceId: string, decisionId: string) {
  const queryClient = useQueryClient()
  const [isLive, setIsLive] = useState(true)

  useEffect(() => {
    let disconnect: (() => void) | null = null
    let pollTimer: ReturnType<typeof setInterval> | null = null
    let cancelled = false

    function invalidate() {
      void queryClient.invalidateQueries({ queryKey: ['decision', workspaceId, decisionId] })
    }

    function startPolling() {
      setIsLive(false)
      pollTimer = setInterval(invalidate, 5000)
    }

    async function start() {
      try {
        const token = await issueStreamToken(workspaceId, decisionId)
        if (cancelled) return
        disconnect = connectSse({
          url: decisionStreamUrl(workspaceId, decisionId, token),
          eventNames: STREAM_EVENT_NAMES,
          terminalEventNames: TERMINAL_EVENT_NAMES,
          onEvent: invalidate,
          onFallbackToPolling: startPolling,
        })
      } catch {
        if (!cancelled) startPolling()
      }
    }

    void start()

    return () => {
      cancelled = true
      disconnect?.()
      if (pollTimer) clearInterval(pollTimer)
    }
  }, [workspaceId, decisionId, queryClient])

  return { isLive }
}
