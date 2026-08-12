import '@testing-library/jest-dom/vitest'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from '@/test/msw-server'

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

// jsdom has no EventSource implementation. This stub is enough for
// src/lib/sse-client.ts's real reconnect/event-listener logic to run
// unmodified in tests — it just never actually opens a connection, so
// pages using the Decision Detail SSE hook fall back to (and are tested
// via) their own polling path rather than the live-stream path, which is
// covered instead by this project's live verification against a real
// backend (see docs/IMPLEMENTATION/STATUS.md's Phase 9 entry).
class FakeEventSource {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSED = 2
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  url: string
  private listeners = new Map<string, Set<(event: MessageEvent) => void>>()

  constructor(url: string) {
    this.url = url
  }

  addEventListener(type: string, listener: (event: MessageEvent) => void) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set())
    this.listeners.get(type)?.add(listener)
  }

  removeEventListener(type: string, listener: (event: MessageEvent) => void) {
    this.listeners.get(type)?.delete(listener)
  }

  close() {
    // no-op: never actually connected
  }
}

// @ts-expect-error -- test-only global stub, not a full EventSource implementation
globalThis.EventSource = FakeEventSource
