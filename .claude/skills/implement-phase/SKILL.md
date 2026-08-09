---
name: implement-phase
description: Disciplined build loop for one NexusIQ roadmap phase — inspect current state, plan, implement incrementally, verify against acceptance criteria, update STATUS/TODO/ADRs. Use whenever starting or continuing implementation work on a phase.
---

# Implement a phase

Argument: a phase number (`4`) or a phase name. If none given, read
`docs/IMPLEMENTATION/STATUS.md` and continue the current phase.

**Never implement a whole phase blind. Never skip step 1.**

---

## 1. Orient (cheap — do not skip, do not over-read)

Read, in this order, stopping as soon as you know enough:

1. `docs/IMPLEMENTATION/STATUS.md` — the durable truth about where the project is.
2. The phase's section in `docs/IMPLEMENTATION/ROADMAP.md` — objective, deliverables,
   acceptance criteria, risks.
3. `docs/IMPLEMENTATION/TODO.md` — open items for this phase.
4. The **one** rules file matching what you will touch (`.claude/rules/*.md`).
5. Only the specific doc sections the phase needs (e.g. `docs/AI/AGENTS.md` for agent work,
   `docs/DATABASE/SCHEMA.md` for schema work). Not the whole `docs/` tree.

Then inspect reality, not documentation:

```
git log --oneline -15
git status
```
Glob/grep the code that already exists in the area you are about to change.

**If STATUS.md and the code disagree, the code wins — and fix STATUS.md as part of this work.**

## 2. Check dependencies

Confirm every prior phase this one depends on is genuinely complete (its acceptance criteria
verifiably met — not merely marked done). If a dependency is incomplete, say so and stop; do not
paper over it with a stub.

Confirm required infrastructure is up (`docker compose ps`) and required tooling exists.

## 3. Plan before writing code

Produce a short plan — bullets, not an essay:

- What will be built, as a list of concrete files/components.
- The order of work, smallest coherent increments first.
- Which failure modes from `.claude/rules/architecture.md` apply and how each is handled.
- Which security implications from `.claude/rules/security.md` apply.
- Which tests will prove it works.
- Any architectural decision this forces → does it need an ADR *before* implementing?
- Risks and unknowns.

If the plan requires a structural change (new infra, changed boundary, new dependency, changed
event contract): **run `/architecture-review` first and get approval.** Do not proceed.

## 4. Implement incrementally

Work in vertical slices that can each be verified, not layer-by-layer across the whole phase.

While implementing:
- Follow the rules file. It is not advisory.
- Handle the failure cases you listed. Explicitly, in code.
- Wire observability as you go — correlation id, metrics, spans. Not "later".
- Write the test alongside the code, not after the phase.
- **Never** fake a result, hardcode a demo value, or stub something and describe it as working.
  A mock is acceptable only behind a named interface, clearly labelled, and reported as a mock.
- Do not rewrite working architecture because a different approach would be easier for you.
- Do not build the next phase's work because you are "already in there".

## 5. Verify

Run `/test-and-verify`. Then, explicitly, walk the phase's **acceptance criteria** from the
roadmap one by one and demonstrate each — with actual command output, an API response, a log line,
or a query result. Not with an assertion that it should work.

If something fails: fix it or record it honestly in STATUS.md as a known issue. Never report a
phase complete with failing acceptance criteria.

## 6. Document

Update, in this order:

- `docs/IMPLEMENTATION/STATUS.md` — phase state, what was completed, what is still open, known
  issues, technical debt taken on, last verified date.
- `docs/IMPLEMENTATION/TODO.md` — tick off completed items, add discovered ones.
- The affected reference doc **only if the design changed** (`docs/DATABASE/SCHEMA.md`,
  `docs/API/API_DESIGN.md`, `docs/AI/*`). Do not restate what code already says.
- A new ADR in `docs/DECISIONS/` for any significant decision made during implementation, plus a
  line in `docs/DECISIONS/README.md`.
- `.env.example` if any new configuration was introduced. Always.

Do not create new documents to record progress. STATUS.md and TODO.md are the only progress files.

## 7. Report

Close with a **short** summary:

- What was built (bullets).
- Acceptance criteria: met / not met, with evidence.
- Test results: actual numbers, including failures.
- Decisions made and why (or ADR reference).
- Known issues and deliberate debt.
- The single recommended next step.

No architecture recap. No essay. If everything passed, say so plainly.

---

## Guardrails

- One phase at a time. Building ahead creates rework and untested surface area.
- If the phase turns out to be much larger than the roadmap suggested, split it, update the
  roadmap, and say so — do not silently deliver half.
- If you are blocked on a decision only the user can make, do everything not blocked by it first,
  then ask one precise question.
