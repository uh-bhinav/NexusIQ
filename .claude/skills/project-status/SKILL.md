---
name: project-status
description: Produce a concise, evidence-based NexusIQ status report — current phase, what actually works, what is broken, test health, open decisions, next step — by inspecting the repository rather than trusting the status file. Use at session start or when asked where the project stands.
---

# Project status

Produce a short, accurate report. **Verify against the repository — do not simply recite
STATUS.md.** The status file can be stale; code and tests cannot.

---

## 1. Read the recorded state

`docs/IMPLEMENTATION/STATUS.md`, then `docs/IMPLEMENTATION/TODO.md` (open items only).

## 2. Verify against reality (cheap checks only)

```
git log --oneline -10
git status --short
```

Then confirm claims structurally — existence and shape, not full reads:

- Do the modules the status file claims exist actually exist? (glob for key files)
- How many Flyway migrations are there? (`ls backend/spring-api/src/main/resources/db/migration/`)
- Which graph nodes exist? (`ls ai-service/app/agents/`)
- Which frontend pages exist? (`ls frontend/web/src/features/`)
- Is the stack currently running? (`docker compose ps`)

If a claim in STATUS.md is contradicted by the repo, **flag it and correct the file.**

## 3. Test health

Run the fast gates only (compile + lint + unit). Do not boot the whole stack or run evaluation
just to report status. If tests were last run more than a commit or two ago, say the health is
unknown rather than guessing.

## 4. Report — keep it to roughly this length

```
NEXUSIQ STATUS — <date>

PHASE
  Current:    Phase N — <name> (<not started | in progress | complete>)
  Completed:  0,1,2...
  Next:       Phase N+1 — <name>

WORKING          (verified, not assumed)
  • ...

IN PROGRESS
  • ... — <what remains>

NOT STARTED
  • Phases N..13

BROKEN / KNOWN ISSUES
  • ... (or "none recorded")

TEST HEALTH
  Java: ... | Python: ... | Frontend: ... | Evaluation: ...

TECHNICAL DEBT
  • ... (deliberate shortcuts and their cost)

OPEN DECISIONS
  • ... (things needing an ADR or a user answer)

DISCREPANCIES
  • ... (where STATUS.md disagreed with the repo — and what you corrected)

RECOMMENDED NEXT
  <one concrete action>
```

## 5. Reconcile

If you corrected anything, write the correction into `STATUS.md` in the same pass so the next
session starts from truth.

---

## Rules

- Concise. This is a dashboard, not a narrative.
- Never claim something works because it was implemented. Working means verified.
- Never pad the "working" list to look productive. An honest short list is the point.
- One recommended next action, not a menu.
