# Profile — Data Pipeline Ops (Resource Leases + Recurring Runs)

**Recommend when:** recurring pipeline/ETL runs; a shared resource (warehouse, cluster, staging
slot) tolerates only one heavy job at a time; bad data propagating silently is the concern.

```yaml
resources:
  analytics-warehouse:
    description: "Shared warehouse — one heavy transform/backfill at a time"
    defaultTtlSeconds: 7200
    maxHolders: 1

work_item_schemas:
  pipeline-run:
    lifecycle: auto
    default_traits: [needs-warehouse-slot]
    notes:
      - key: run-scope
        role: queue
        required: false
        description: "What this run covers — date range, tables, backfill vs incremental."
      - key: quality-scorecard
        role: work
        required: true
        description: "Row counts, anomaly score, schema-drift check results."
        guidance: "On a threshold breach: apply the needs-anomaly-review trait so a data
          engineer disposition gate is added before the run closes."
  data-product:                 # container per recurring pipeline; new runs reopen it
    lifecycle: auto-reopen
    notes: []

traits:
  needs-warehouse-slot:
    resources:
      - key: analytics-warehouse
        mode: exclusive         # WORK entry takes the lease; contention = transient rejection
      - key: prod-read-credential
        mode: advisory          # recorded in consumedCredentials, never locks
  needs-anomaly-review:
    notes:
      - key: anomaly-review
        role: review
        required: true
        description: "Data engineer's disposition of the flagged anomaly."
```

**Rationale to present:** the exclusive lease lives on the **leaf** run type — never the
container (anti-pattern 2 in `workflow-patterns.md` §4). A second run entering WORK while the
warehouse is held gets a transient `resource_unavailable` with retry semantics, not a gate
error. Scope resource keys per team (`analytics/warehouse`, not `db`) — generic keys falsely
serialize unrelated projects on a shared server.
