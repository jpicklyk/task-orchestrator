# Profile — Document/Contract Review

**Recommend when:** intake → analysis → human sign-off pipelines over documents (contracts,
compliance, claims); an attributable approval record is the concern.

```yaml
work_item_schemas:
  contract-review:
    lifecycle: auto
    notes:
      - key: intake
        role: queue
        required: true
        description: "Counterparty, document type, deadline, playbook version to review against."
      - key: deviation-analysis
        role: work
        required: true
        description: "Clause table + deviations from playbook with risk flags."
        guidance: "The reviewer sees flags, not raw text — routine terms consuming expert time
          is the cost this pipeline removes."
      - key: reviewer-signoff
        role: review
        required: true
        description: "Human approval to release — attributable, verified if actor auth is on."
        guidance: "Filled only by the human reviewer, never by the analysis agent."
```

**Rationale to present:** pair with `actor_authentication` (global config; JWKS verifier) when
sign-off authorship must be verifiable, not just recorded. Be candid about the boundary: the
server verifies *who wrote* the note; requiring that a *specific actor class* fills a specific
note key is convention, not schema-enforced.
