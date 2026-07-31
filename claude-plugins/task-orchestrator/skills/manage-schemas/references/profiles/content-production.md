# Profile — Content Production Pipeline

**Recommend when:** editorial workflows — research/brief → draft → fact-check → approve →
publish; evergreen pieces get refreshed later; unsupported claims reaching publication is the
concern.

```yaml
work_item_schemas:
  content-piece:
    lifecycle: auto-reopen      # a refresh child created later reopens the published piece
    notes:
      - key: content-brief
        role: queue
        required: true
        description: "Approved brief — audience, angle, keywords, claims that need support."
        guidance: "A human approves the brief BEFORE drafting starts. Rejecting a bad brief
          costs minutes; rejecting a bad draft costs the whole draft."
      - key: fact-check
        role: work
        required: true
        description: "Claims-supported audit — every factual claim sourced, softened, or cut."
      - key: editorial-signoff
        role: review
        required: true
        description: "Editor's publish decision — voice, structure, accuracy verified."
  revision-task:
    lifecycle: auto
    notes:
      - key: refresh-scope
        role: queue
        required: true
        description: "What decayed — rankings, stale facts, broken links — and what to update."
      - key: refresh-evidence
        role: work
        required: true
        description: "What was updated and re-verified."

traits:
  needs-legal-review:           # apply per-item at briefing time for regulated/YMYL claims
    notes:
      - key: legal-review
        role: review
        required: true
        description: "Legal/compliance sign-off for regulated claims."
```

**Rationale to present:** `auto-reopen` keeps an evergreen piece's history in one item across
refresh cycles instead of fragmenting it. The per-item `needs-legal-review` trait shows dynamic
routing: the briefing agent applies it only to pieces that make regulated claims.
