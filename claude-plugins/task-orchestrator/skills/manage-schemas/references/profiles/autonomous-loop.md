# Profile — Autonomous Queue-Drain Loop

**Recommend when:** solo dev running bounded agent loops (overnight runs, queue drains); the work
is coding tasks executed without supervision; review happens at the PR, outside the tracker.

```yaml
work_item_schemas:
  loop-task:
    lifecycle: auto
    notes:
      - key: completion-oracle
        role: queue
        required: true
        description: "Machine-checkable done signal — exact command and expected outcome."
        guidance: "State the exact command that proves completion (test suite, build, lint) and
          its expected result, plus the bound: max iterations or wall-clock budget before the
          item escalates to a human. The loop agent may not redefine this mid-run. A vague
          oracle ('improve the code') makes the loop non-convergent — reject it."
      - key: iteration-evidence
        role: work
        required: true
        description: "Final evidence — iterations used vs bound, test results, files touched."
        maxLength: 1500
  default:
    lifecycle: auto
    notes: []
```

**Rationale to present:** the top documented failure of autonomous loops is the agent
self-declaring done. Writing the done-signal into a queue gate *before* work starts puts it
where the loop can't rewrite it. No review phase — the human merge gate lives in the PR. If
multiple loops can run concurrently, recommend claim mode so a crashed run's lease expires
instead of stranding the item.
