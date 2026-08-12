import { setupServer } from 'msw/node'

/** Handlers are registered per-test (server.use(...)) rather than one giant
 * shared fixture set — keeps each test's contract with the API explicit and
 * mirrors the real one (.claude/rules/testing.md). */
export const server = setupServer()
