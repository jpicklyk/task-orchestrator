# Profile — Support/Ticket Triage (Claim-Mode Worker Fleet)

**Recommend when:** multiple worker agents pull from a shared queue; auto-resolve vs escalate
routing; context loss at human handoff is the concern.

```yaml
work_item_schemas:
  support-ticket:
    lifecycle: auto
    notes:
      - key: triage
        role: queue
        required: true
        description: "Intent, urgency, confidence score, routing decision."
        guidance: "Filled by the triage agent. Below the auto-resolve confidence threshold, or
          on legal/frustration signals: apply the needs-escalation trait to this item and raise
          its priority — that adds the escalation gate to THIS ticket only."
      - key: resolution
        role: work
        required: true
        description: "What was done — resolution summary and customer-visible outcome."
  intake:                       # standing queue container
    lifecycle: permanent
    notes: []

traits:
  needs-escalation:
    notes:
      - key: escalation-packet
        role: review
        required: true
        description: "Full-context handoff bundle for the human agent."
        guidance: "Everything the human needs without re-reading the thread: the issue, what
          was tried, customer sentiment, recommended action."
```

**Rationale to present:** claim mode is the dispatch model — workers `claim_item` with a TTL,
so a crashed worker's lease expires and the ticket returns to the pool. Priority carries
urgency into `get_next_item` ordering. An unescalated ticket flows queue→work→terminal; an
escalated one grows a review phase — same schema, two shapes, via one per-item trait.
