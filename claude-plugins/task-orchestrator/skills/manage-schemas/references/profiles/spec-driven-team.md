# Profile — Spec-Driven Development Team

**Recommend when:** a team works plan-first (spec → plan → tasks → implement); epics decompose
into child tasks; drift between spec and code is the concern.

```yaml
work_item_schemas:
  sdd-epic:
    lifecycle: manual          # a human closes the epic after merge — no auto-cascade
    notes:
      - key: specification
        role: queue
        required: true
        description: "The WHAT — user stories, acceptance criteria, non-goals."
      - key: implementation-plan
        role: queue
        required: true
        description: "The HOW — architecture, task decomposition, dependency edges."
        guidance: "Derived from the approved specification. Map each spec item to a child
          sdd-task; record dependency edges; flag risky areas needing review traits."
      - key: integration-notes
        role: work
        required: true
        description: "Cross-task deviations and decisions discovered during implementation."
      - key: spec-alignment
        role: review
        required: true
        description: "Diff-vs-spec audit before the epic closes."
        guidance: "Walk the final result against the specification: flag spec items not built,
          and built things not in the spec. Catching drift is this note's entire job."
  sdd-task:
    lifecycle: auto
    notes:
      - key: task-definition
        role: queue
        required: true
        description: "Scope slice from the plan — files, acceptance criteria, constraints."
      - key: implementation-evidence
        role: work
        required: true
        description: "What was built, test results, deviations from the task definition."
```

**Rationale to present:** each stage transition is a re-readable document instead of chat
history — the mechanism practitioners credit for large rework reductions. `manual` lifecycle
keeps the epic open after children finish so the alignment review and the human close decision
still happen. Both queue notes gate the same transition; spec-before-plan ordering is guidance,
not server-enforced — the gate guarantees neither is skipped.
