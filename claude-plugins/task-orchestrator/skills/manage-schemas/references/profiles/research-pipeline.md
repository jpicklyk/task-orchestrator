# Profile — Deep-Research Pipeline

**Recommend when:** parallel research threads under a lead/orchestrator; a synthesized report
with sources is the deliverable; hallucinated or uncited claims are the concern.

```yaml
work_item_schemas:
  research-mission:
    lifecycle: manual           # the lead closes the mission after the citation gate
    notes:
      - key: research-plan
        role: queue
        required: true
        description: "Decomposition — subquestions, source strategy, output format, stop rule."
        guidance: "Each planned thread must map to a research-thread child with its own brief.
          State the stop rule: what makes coverage sufficient."
      - key: synthesis
        role: work
        required: true
        description: "The synthesized report draft, built from thread findings notes."
      - key: citation-audit
        role: review
        required: true
        description: "Claim→source mapping — every substantive claim traced or flagged."
        guidance: "Run as a separate agent from the synthesizer — self-citation is the failure
          mode. Attach a source pointer per claim, or flag the claim for removal."
  research-thread:
    lifecycle: auto
    notes:
      - key: thread-brief
        role: queue
        required: true
        description: "Objective, output format, tool guidance, boundaries."
        guidance: "Filled by the lead before dispatch. State what this thread owns and what it
          must NOT wander into — vague briefs cause duplicated and gapped coverage."
      - key: findings
        role: work
        required: true
        description: "Distilled findings with source pointers — this note IS the report."
        maxLength: 4000
```

**Rationale to present:** notes are the handoff medium — subagents write findings once, the
lead reads notes instead of replaying transcripts (the "game of telephone" cost in multi-agent
systems). The citation audit is a separate reviewer by design.
