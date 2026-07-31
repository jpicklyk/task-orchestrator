# Profile — Incident Response

**Recommend when:** on-call/runbook workflows; severity tiers; the postmortem discipline is
what the user wants enforced.

```yaml
work_item_schemas:
  incident:
    lifecycle: auto
    notes:
      - key: severity-triage
        role: queue
        required: true
        description: "Severity tier, blast radius, on-call paged yes/no."
        guidance: "Critical severity always pages — agents do not resolve critical incidents
          autonomously."
      - key: containment-log
        role: work
        required: true
        description: "Actions taken with timestamps; who approved production access."
      - key: postmortem
        role: review
        required: true
        description: "Root cause, timeline, action items — the incident can't close without it."
        guidance: "Blameless. Create a follow-up item per action item and link it as a
          dependency — unfollowed action items are how the same incident repeats."
  incidents:                    # standing intake container
    lifecycle: permanent
    notes: []

traits:
  needs-prod-access:
    notes:
      - key: access-justification
        role: work
        required: true
        description: "Why production access was needed and who approved it."
    resources:
      - key: prod-change-credential
        mode: advisory          # audit trail without serializing incident work
```

**Rationale to present:** the review phase here is not code review — it's "the incident is not
closed until the postmortem exists." Advisory resources give the credential audit trail without
locking. Map priority to severity so `get_next_item` surfaces the hottest incident.
