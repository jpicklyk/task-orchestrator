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
        description: "Human approval to release — attributable, verified under a jwks credential."
        guidance: "Filled only by the human reviewer, never by the analysis agent."
```

**Rationale to present:** when sign-off authorship must be verifiable, not just recorded, run the
server with `API_AUTH_MODE=jwks` (a server setting, not config): the principal on the note is then
the credential's holder. To make the approval a decision only a person can give, route it through a
decision class with `answerers: human-only` (answered with a `kind: human` credential, which a stdio
server does not have; see
[Decision classes](../../../../../../current/docs/config-reference.md#decision-classes-decision_classes)).
Be candid about the boundary: requiring that a *specific actor class* fills a specific
note key is convention, not schema-enforced.
