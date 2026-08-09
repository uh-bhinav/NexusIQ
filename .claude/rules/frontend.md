# Rules: Frontend

Scope: `frontend/web/**`.

## Purpose

The UI exists to make the backend and AI system **demonstrable end-to-end**. Professional, clean,
consistent — not award-winning. Zero time on custom branding, animation, or decorative work.

## Stack

React 18+ · TypeScript (strict) · Vite · TanStack Query (server state) · React Router ·
Tailwind + shadcn/ui · Recharts (simple charts only) · Zod (validate API responses at the boundary).

No Redux, no MobX, no custom state framework. Server state = TanStack Query; UI state = local
`useState`/context. Adding a state library requires an ADR.

## Structure

```
src/
├── api/          # typed client, one module per resource; Zod schemas
├── components/   # ui/ (shadcn primitives) + shared app components
├── features/     # one folder per page-domain: auth, dashboard, knowledge,
│                 #   decisions, approvals, audit, metrics
├── hooks/
├── lib/          # utils, formatters, sse client
├── types/        # generated/derived API types
└── routes/
```

Feature-first. No dumping ground `components/` folder of 60 files.

## Pages (all required)

Login · Dashboard · Knowledge Base · Document detail · Decision Requests · Decision Detail ·
Approval Queue · Audit Log · System Metrics.

## Hard rules

- **No mock UI.** Every rendered value comes from the real API. No placeholder charts, no fake
  numbers, no "coming soon" panels presented as features. If the backend does not provide it yet,
  the page does not ship it yet.
- **Never enforce security in the UI.** Hiding a button is UX. The server decides. Assume any
  request can be replayed.
- **Never hold secrets.** Anything `VITE_`-prefixed is public and in the bundle.
- **Every async view has four states**: loading (skeleton), empty (explains what to do), error
  (message + retry), populated. A component with only the happy path is incomplete.
- Validate API responses with Zod at the boundary; a contract drift should surface as a clear
  error, not `undefined is not an object` three components deep.

## Auth

- Token in memory + refresh flow; if `localStorage` is used, document the XSS trade-off.
- One Axios/fetch interceptor: attach bearer, handle `401` → refresh → retry once → else logout.
- Route guards by role for approver/admin routes — mirroring, not replacing, server checks.

## SSE (the live workflow view)

- `EventSource` against `GET /api/v1/decisions/{id}/stream`.
- Must handle: reconnect with backoff, terminal event → close, unmount → close (no leaks), and a
  **poll fallback** if SSE fails.
- On reconnect, reconcile with a fresh `GET /decisions/{id}` — never assume the stream was complete.
- Render the agent timeline progressively: node, status, latency, tokens, cost.

## Decision detail page

Must show, all from real data: question, recommendation, confidence, risk, policy findings with
status, evidence with document name + section + page + similarity, agent execution timeline,
token usage, cost, latency, validation result, approval status, audit history.

Evidence must be clickable through to the source document and the exact chunk. Citations that
cannot be resolved must render as an explicit warning, never silently.

## Rendering model output

Model-generated text is untrusted. Render as text, or sanitised markdown. **Never**
`dangerouslySetInnerHTML` on anything derived from a document or an LLM.

## Quality

- TypeScript `strict: true`. No `any` — use `unknown` and narrow.
- No inline styles for layout; Tailwind utilities. Extract a component before a 12th utility repeat.
- Components small; extract when a file passes ~200 lines or a hook grows a second responsibility.
- Accessible basics: labels on inputs, keyboard-reachable actions, focus states, sufficient
  contrast in both themes.
- Charts: simple, labelled axes, no custom visualisation work.

## Testing

Vitest + RTL + MSW. Per feature: renders populated state, renders empty state, renders error state,
primary user action calls the right endpoint. See `.claude/rules/testing.md`.
