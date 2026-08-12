/**
 * A thin `EventSource` wrapper implementing everything
 * .claude/rules/frontend.md's "SSE" section requires: reconnect with
 * backoff, terminal-event close, cleanup on unmount, and a poll fallback if
 * SSE itself never manages to connect. Callers supply the terminal event
 * names (server closes on those — docs/API/API_DESIGN.md) so this module
 * doesn't need to know decision-specific event semantics.
 */

const RECONNECT_DELAYS_MS = [1000, 2000, 5000, 10000, 15000] as const
const MAX_CONNECT_FAILURES_BEFORE_POLL_FALLBACK = 3

export interface SseClientOptions {
  url: string
  eventNames: string[]
  terminalEventNames: string[]
  onEvent: (eventName: string, data: string) => void
  /** Called once SSE itself is deemed unreliable (repeated connection
   * failures) so the caller can switch to polling instead. */
  onFallbackToPolling: () => void
}

export function connectSse(options: SseClientOptions): () => void {
  let eventSource: EventSource | null = null
  let reconnectAttempt = 0
  let consecutiveFailures = 0
  let stopped = false
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null

  function cleanupCurrent() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
  }

  function scheduleReconnect() {
    if (stopped) return
    consecutiveFailures += 1
    if (consecutiveFailures >= MAX_CONNECT_FAILURES_BEFORE_POLL_FALLBACK) {
      stopped = true
      cleanupCurrent()
      options.onFallbackToPolling()
      return
    }
    const delay =
      RECONNECT_DELAYS_MS[Math.min(reconnectAttempt, RECONNECT_DELAYS_MS.length - 1)]
    reconnectAttempt += 1
    reconnectTimer = setTimeout(connect, delay)
  }

  function connect() {
    if (stopped) return
    cleanupCurrent()
    const source = new EventSource(options.url)
    eventSource = source

    source.onopen = () => {
      reconnectAttempt = 0
      consecutiveFailures = 0
    }

    for (const name of options.eventNames) {
      source.addEventListener(name, (event) => {
        const messageEvent = event as MessageEvent<string>
        options.onEvent(name, messageEvent.data)
        if (options.terminalEventNames.includes(name)) {
          stopped = true
          cleanupCurrent()
        }
      })
    }

    source.onerror = () => {
      cleanupCurrent()
      scheduleReconnect()
    }
  }

  connect()

  return function disconnect() {
    stopped = true
    if (reconnectTimer) clearTimeout(reconnectTimer)
    cleanupCurrent()
  }
}
