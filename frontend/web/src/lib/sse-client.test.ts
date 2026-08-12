import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { connectSse } from '@/lib/sse-client'

/** A fully controllable stand-in for the browser's EventSource — the
 * project's global test stub (test/setup.ts) deliberately never connects
 * (so page-level tests exercise the poll fallback instead), which means
 * connectSse's own reconnect-with-backoff and terminal-close logic
 * (.claude/rules/frontend.md's "SSE" section) had never actually been
 * exercised by any test. This one drives it directly. */
class ControllableEventSource {
  static instances: ControllableEventSource[] = []
  url: string
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  closed = false
  private listeners = new Map<string, ((event: MessageEvent<string>) => void)[]>()

  constructor(url: string) {
    this.url = url
    ControllableEventSource.instances.push(this)
  }

  addEventListener(type: string, listener: (event: MessageEvent<string>) => void) {
    const existing = this.listeners.get(type) ?? []
    existing.push(listener)
    this.listeners.set(type, existing)
  }

  close() {
    this.closed = true
  }

  emit(type: string, data: string) {
    for (const listener of this.listeners.get(type) ?? []) {
      listener({ data } as MessageEvent<string>)
    }
  }
}

describe('connectSse', () => {
  const originalEventSource = globalThis.EventSource

  beforeEach(() => {
    ControllableEventSource.instances = []
    // @ts-expect-error -- test-only substitution, narrower than the real EventSource
    globalThis.EventSource = ControllableEventSource
    vi.useFakeTimers()
  })

  afterEach(() => {
    globalThis.EventSource = originalEventSource
    vi.useRealTimers()
  })

  it('reconnects after a dropped connection, with backoff, and resumes delivering events', () => {
    const events: Array<[string, string]> = []
    const disconnect = connectSse({
      url: '/stream',
      eventNames: ['decision.status'],
      terminalEventNames: ['decision.completed'],
      onEvent: (name, data) => events.push([name, data]),
      onFallbackToPolling: vi.fn(),
    })

    expect(ControllableEventSource.instances).toHaveLength(1)
    const first = ControllableEventSource.instances[0]
    expect(first).toBeDefined()

    // A live connection: an event arrives fine before anything drops.
    first?.emit('decision.status', '{"status":"PROCESSING"}')
    expect(events).toEqual([['decision.status', '{"status":"PROCESSING"}']])

    // The network drops.
    first?.onerror?.()
    expect(first?.closed).toBe(true)
    // Not yet reconnected — the first backoff delay (1000ms) hasn't elapsed.
    expect(ControllableEventSource.instances).toHaveLength(1)

    vi.advanceTimersByTime(1000)
    expect(ControllableEventSource.instances).toHaveLength(2)

    // The reconnected stream delivers events again — proving reconnect isn't
    // just "a new EventSource object exists" but that the app actually
    // resumes receiving live updates through it.
    const second = ControllableEventSource.instances[1]
    second?.emit('decision.status', '{"status":"WAITING_FOR_APPROVAL"}')
    expect(events).toEqual([
      ['decision.status', '{"status":"PROCESSING"}'],
      ['decision.status', '{"status":"WAITING_FOR_APPROVAL"}'],
    ])

    disconnect()
  })

  it('backs off with increasing delay across repeated drops before falling back to polling', () => {
    const onFallbackToPolling = vi.fn()
    connectSse({
      url: '/stream',
      eventNames: [],
      terminalEventNames: [],
      onEvent: vi.fn(),
      onFallbackToPolling,
    })

    // 3 consecutive failures triggers the poll fallback
    // (MAX_CONNECT_FAILURES_BEFORE_POLL_FALLBACK). Each failure's delay must
    // actually elapse before the next attempt — proving this isn't a tight
    // retry loop hammering the server.
    ControllableEventSource.instances[0]?.onerror?.()
    expect(onFallbackToPolling).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1000)
    expect(ControllableEventSource.instances).toHaveLength(2)

    ControllableEventSource.instances[1]?.onerror?.()
    expect(onFallbackToPolling).not.toHaveBeenCalled()
    vi.advanceTimersByTime(2000)
    expect(ControllableEventSource.instances).toHaveLength(3)

    ControllableEventSource.instances[2]?.onerror?.()
    expect(onFallbackToPolling).toHaveBeenCalledOnce()
    // No further reconnect attempts once polling has taken over.
    vi.advanceTimersByTime(60000)
    expect(ControllableEventSource.instances).toHaveLength(3)
  })

  it('closes the stream and stops reconnecting on a terminal event', () => {
    const onFallbackToPolling = vi.fn()
    connectSse({
      url: '/stream',
      eventNames: ['decision.completed'],
      terminalEventNames: ['decision.completed'],
      onEvent: vi.fn(),
      onFallbackToPolling,
    })

    const first = ControllableEventSource.instances[0]
    first?.emit('decision.completed', '{"status":"APPROVED"}')
    expect(first?.closed).toBe(true)

    // A terminal event is not a drop — no reconnect attempt follows it.
    vi.advanceTimersByTime(60000)
    expect(ControllableEventSource.instances).toHaveLength(1)
    expect(onFallbackToPolling).not.toHaveBeenCalled()
  })

  it('disconnect() stops any pending reconnect and closes the current stream', () => {
    const disconnect = connectSse({
      url: '/stream',
      eventNames: [],
      terminalEventNames: [],
      onEvent: vi.fn(),
      onFallbackToPolling: vi.fn(),
    })

    const first = ControllableEventSource.instances[0]
    first?.onerror?.()
    disconnect()

    // The scheduled reconnect must not fire after disconnect — unmounting
    // the Decision Detail page must not leak a stream (.claude/rules/
    // frontend.md: "unmount -> close (no leaks)").
    vi.advanceTimersByTime(60000)
    expect(ControllableEventSource.instances).toHaveLength(1)
  })
})
