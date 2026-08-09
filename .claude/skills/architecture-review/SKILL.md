---
name: architecture-review
description: Evaluate a proposed NexusIQ architecture or design change before implementing it — problem framing, alternatives, trade-offs, failure modes, complexity cost — and recommend the simplest viable option, producing an ADR. Use before adding infrastructure, changing service boundaries, event contracts, persistence, auth, or the AI orchestration model.
---

# Architecture review

Use before implementing any structural change. Output is a recommendation and, if accepted, an ADR.

**Triggers (mandatory):** new infrastructure component · new service · changed service boundary ·
changed persistence model · changed event contract or topic semantics · changed auth model ·
different AI orchestration framework · new agent in the graph · introducing Kubernetes ·
any new runtime dependency that is not trivially replaceable.

Not needed for: naming, file layout within a module, adding an endpoint that fits existing
conventions, adding a test, refactoring inside one class.

---

## Method

### 1. State the actual problem

In two sentences, what problem is being solved — the *problem*, not the proposed solution. If the
problem cannot be stated without naming the technology, that is a signal the change is
technology-driven. Say so.

Ask: is this problem real and present, or anticipated? NexusIQ optimises for the simplest thing
that satisfies the **actual** requirement.

### 2. Inspect what already exists

Read the current implementation of the area. Check `docs/DECISIONS/` for an ADR that already
covers it — if one does, this review must either comply with it or explicitly supersede it.

**Ask first: does the existing stack already solve this?** Postgres, Redis, Kafka and Spring cover
an enormous amount of ground. Most "we need X" turns out to be "we did not use what we have".

### 3. Generate alternatives

At least three, always including:
- **Do nothing / defer.** What breaks if we don't? Often nothing yet.
- **The simplest change to existing components.**
- The proposed change.

### 4. Compare on these axes

| Axis | Question |
|---|---|
| Complexity | New concepts, new moving parts, new failure modes |
| Operational burden | Another container? Another thing to run, upgrade, debug at 2am? |
| Cost | **Must stay $0 recurring (ADR-010).** Paid service = automatic rejection |
| Failure modes | What breaks, how is it detected, how does it degrade |
| Security | New trust boundary? New secret? New tenant-isolation surface? |
| Observability | Can it be traced and measured, or is it a black box? |
| Testability | Can it run in CI, in Testcontainers, deterministically? |
| Reversibility | How expensive is it to undo in three weeks? |
| Alignment | Does it violate a CLAUDE.md non-negotiable or an accepted ADR? |

### 5. Call out over-engineering explicitly

Say it plainly when the proposal adds infrastructure that the existing stack already handles, adds
a service that could be a package, adds an agent that could be deterministic code, adds an
abstraction with exactly one implementation and no second one in sight, or exists mainly to appear
on a résumé. This project's credibility comes from restraint, not from component count.

Equally: do not under-engineer past a real requirement. If the simple option genuinely cannot meet
a stated need, say that too.

### 6. Recommend

One option. Clearly. With the reasoning in three or four lines, the main trade-off accepted, and
what would have to change for the answer to flip.

Note explicitly whether this needs the user's approval (structural/irreversible) or can be
executed autonomously (contained/reversible).

### 7. Write the ADR

If accepted, create `docs/DECISIONS/ADR-{nnn}-{slug}.md` using the template in
`docs/DECISIONS/README.md`, add it to the index table, and — if it changes a documented design —
update the affected doc in the same pass.

If the decision supersedes an earlier ADR, mark the old one `Superseded by ADR-nnn`. **Never
silently contradict an accepted ADR.**

---

## Output format

Keep it under a page:

```
PROBLEM       — 2 sentences
CURRENT STATE — what exists now
OPTIONS       — A / B / C, one line each
COMPARISON    — the 3-4 axes that actually differentiate them
RECOMMENDATION— one option + why + accepted trade-off
RISKS         — what could go wrong
DECISION TYPE — autonomous | needs user approval
NEXT          — ADR number to write, or "no change"
```
