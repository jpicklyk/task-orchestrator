# Workflow Pattern Library — Schema Advisor Reference

Pattern library backing the CREATE workflow's advisor path (`create-workflow.md` Step 2A).

**Two-stage read — this matters for context cost.** This file carries everything needed to
*classify* a workflow: the interview dimensions, a capsule index of all ten profiles, selection
tables, the trait library, and anti-pattern warnings. The full profiles (YAML + rationale to
present) live one-per-file in `references/profiles/`. Classify against the capsule index first,
then read ONLY the matched profile file — two when the classification is ambiguous or the
workflow is a hybrid. Never read the whole profiles directory: the capsule index exists
precisely so candidates can be compared without opening files.

**Contents:**
1. [Classification — dimensions and profile index](#1-classification)
2. [Selection tables](#2-selection-tables)
3. [Cross-domain trait library](#3-cross-domain-trait-library)
4. [Anti-pattern warnings](#4-anti-pattern-warnings)

---

## 1. Classification

Classify from what the user has already said before asking anything — most conversations contain
the answers. Ask only for genuine gaps, batched into one `AskUserQuestion` round (the four
dimensions below fit its 4-question limit). Do not interrogate dimension by dimension.

| Dimension | What to determine | Why it matters |
|---|---|---|
| **Work shape** | What flows through: features/bugs, loop tasks, research questions, content pieces, tickets, pipeline runs, incidents, documents, generic tasks | Picks the primary profile |
| **Sign-off** | Does anything require human or second-agent approval before an item closes? Who reviews, and what evidence do they need? | Decides whether a `review` phase exists and what its gate note carries |
| **Executors** | One orchestrated agent, or multiple independent workers pulling from a pool? Crash-recovery needed? | Pull-based fleet → claim-mode conventions (TTL leases); single driver → orchestration mode |
| **Contention & recurrence** | Shared resources only one worker may touch at a time? Standing queues? Work that reopens? | Resource traits (`exclusive`/`advisory`); lifecycle (`permanent`, `auto-reopen`) |

### Profile index

| Profile file (`profiles/`) | Recommend when the user says things like | Config shape (capsule) |
|---|---|---|
| `autonomous-loop.md` | "overnight runs", "agent loop", "queue drain", "autonomous coding", "Ralph" | Leaf `loop-task`: queue completion-oracle gate (machine-checkable done signal + iteration bound), work iteration-evidence; NO review phase (PR is the human gate); claim mode if loops run concurrently |
| `spec-driven-team.md` | "spec first", "PRD", "plan before code", "team of devs + agents", "epics" | `sdd-epic` (manual lifecycle, dual queue gates spec+plan, work integration-notes, review spec-alignment audit) + `sdd-task` children (task-definition, implementation-evidence) |
| `research-pipeline.md` | "research", "deep dive", "report with sources", "parallel investigation" | `research-mission` (manual; plan gate, synthesis, review citation-audit by a separate agent) + `research-thread` children (brief gates dispatch; findings note IS the report, maxLength-bounded) |
| `content-production.md` | "blog", "articles", "editorial", "publish", "SEO", "content calendar" | `content-piece` (auto-reopen for refresh cycles; brief approval gate → work fact-check → review editorial-signoff) + `revision-task` children; per-item `needs-legal-review` trait for regulated claims |
| `support-triage.md` | "tickets", "triage", "worker pool", "escalate to a human", "support" | `support-ticket` (triage queue gate w/ confidence routing, work resolution) + permanent `intake` container; per-item `needs-escalation` trait adds review escalation-packet; claim-mode dispatch convention |
| `data-pipeline-ops.md` | "ETL", "data pipeline", "warehouse", "one run at a time", "data quality" | `pipeline-run` leaf with exclusive resource lease trait (+ advisory credential), work quality-scorecard; `data-product` auto-reopen container; `needs-anomaly-review` trait on threshold breach |
| `incident-response.md` | "incidents", "on-call", "runbook", "postmortem", "SRE" | `incident` (severity-triage gate, containment-log, review postmortem — can't close without it) + permanent `incidents` container; `needs-prod-access` trait with advisory credential audit |
| `document-review.md` | "contracts", "documents to review", "redline", "sign-off", "compliance", "audit trail" | `contract-review`: intake gate → work deviation-analysis (flags, not raw text) → review human sign-off; pair with global `actor_authentication` for verified authorship |
| `schema-free.md` | "just track tasks", "kanban", "no gates", "simple statuses" | Empty `default` schema — status/dependency/hierarchy tracking only; doubles as the per-root fence on a shared server |
| `multi-project.md` | "multiple projects", "shared server", "different teams, one database" | Layering strategy, not one YAML: minimal global floor (process schemas + resource registry) + per-root config per team; mixed dispatch models coexist |

Hybrids are normal: pick the closest primary profile, read its file, and pull traits from §3 or
a second profile's file to cover the rest. Say which parts came from where — the user should
understand the recommendation well enough to maintain it.

---

## 2. Selection Tables

Use these when explaining a recommendation or resolving a customization question.

**Lifecycle mode** (per schema type):

| The user wants | `lifecycle` |
|---|---|
| Tree closes itself when children finish | `auto` (default) |
| A human/lead decides when it closes | `manual` |
| Standing container that never closes (intake queues, category containers) | `permanent` |
| Closed parent reopens when new child work arrives (refresh cycles, recurring runs) | `auto-reopen` |

**Dispatch model** (usage convention — not a config key; mention it in the recommendation):

| Situation | Model |
|---|---|
| One orchestrator drives items through phases | Orchestration (`advance_item`) |
| Independent workers pull from a shared pool; crash-safety needed | Claim (`claim_item` + TTL lease) |
| Worker fleet needing verified identity on writes | Either + `actor_authentication` (global config only) |

**Gate strength** (per note):

| Intent | Mechanism |
|---|---|
| Must exist before the phase transition | `required: true` |
| Useful reminder, never blocks | `required: false` |
| Must follow a reusable methodology | `required: true` + `skill` |
| Must cover project specifics | `required: true` + `guidance` |
| Body must stay distilled | `maxLength` (+ top-level `note_limits.mode: reject` to enforce hard) |

---

## 3. Cross-Domain Trait Library

Offer these during customization when a need surfaces that the chosen profile doesn't cover.
Merge rules: base-schema note keys beat trait notes; resources union across traits with
`exclusive` winning mode conflicts.

```yaml
traits:
  human-approval:               # generic maker-checker gate
    notes:
      - key: approval-decision
        role: review
        required: true
        description: "Human approver's decision with rationale — approve / reject / conditions."
  needs-fact-check:
    notes:
      - key: fact-check
        role: work
        required: true
        description: "Every factual claim sourced, softened, or cut."
  needs-security-review:
    notes:
      - key: security-assessment
        role: review
        required: true
        description: "Input validation, injection risk, access control, data handling."
  needs-rollback-plan:
    notes:
      - key: rollback-plan
        role: queue
        required: true
        description: "How to undo this change if it fails after release."
  session-tracked:              # feeds /session-retrospective; recommend for agent-run projects
    notes:
      - key: session-tracking
        role: work
        required: true
        description: "What happened — outcome, deviations, friction; feeds retrospectives."
        maxLength: 2000
  needs-staging-slot:           # exclusive lease — leaf task types only
    resources:
      - key: staging-env
        mode: exclusive
  records-deploy-credential:    # audit-only credential trail
    resources:
      - key: deploy-credential
        mode: advisory
```

---

## 4. Anti-Pattern Warnings

Raise these during customization when the user steers toward one — with the reason, not just
the rule:

1. **Over-gating.** More than 2-3 required notes per phase produces approval fatigue and
   workarounds; reviewers drown and agents pad. Prefer `required: false` reminders; add the
   review phase only for genuine sign-off.
2. **Exclusive resources on containers.** A container sits in WORK for its children's whole
   duration — it would hold the lease the entire time, starving every leaf that actually needs
   it. Exclusive resources go on leaf task types only.
3. **Letting the worker define "done."** Completion criteria belong in a queue note written
   before work starts — agents quietly redefining done is a top documented failure of
   autonomous execution.
4. **Self-review.** The same agent filling work evidence and review disposition defeats the
   gate. The server enforces note existence, not authorship — keep the separation in dispatch
   discipline, and say so honestly when recommending review gates.
5. **Notes as dumping grounds.** Distilled prose in bodies; verbatim artifacts (logs, diffs)
   via `bodyFromFile`; `maxLength` as the backstop.
6. **Generic resource keys.** `db` on a shared server falsely serializes unrelated projects —
   scope keys (`team-a/staging-db`).
7. **Renaming note keys after use.** Orphans existing notes; keys are stable identifiers.
