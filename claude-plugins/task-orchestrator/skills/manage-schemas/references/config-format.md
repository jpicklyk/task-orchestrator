# Config Format Reference

YAML format and field rules for `.taskorchestrator/config.yaml`.

---

## File Location

- Path: `.taskorchestrator/config.yaml` in the project root (alongside `.claude/`, `.git/`, etc.)
- Create the directory if it doesn't exist: `.taskorchestrator/`
- This file is typically gitignored (runtime/project config, not source code)
- The server reads and caches this file on first schema access — changes require MCP reconnect (`/mcp`)

---

## YAML Structure (Preferred — work_item_schemas)

```yaml
work_item_schemas:

  your-schema-type:            # Matches items whose type field equals "your-schema-type"
    lifecycle: auto            # Optional: auto | manual | auto-reopen | permanent (default: auto)
    default_traits:            # Optional: traits applied to every item matching this schema
      - needs-security-review
    notes:
      - key: note-key          # Stable identifier; kebab-case
        role: queue            # Phase: "queue", "work", or "review"
        required: true         # true = blocks advance_item gate | false = shown but not enforced
        description: "..."     # Short label — shown in expectedNotes response
        guidance: "..."        # Optional — shown as guidancePointer in get_context()
        skill: "review-quality" # Optional — skill to invoke when filling this note (shown as skillPointer)

traits:

  needs-security-review:       # Trait name — referenced by default_traits or per-item traits parameter
    notes:
      - key: security-assessment
        role: review
        required: true
        description: "Security review"
        skill: "security-review"
        guidance: "Evaluate input validation, injection risks, access control..."
```

---

## YAML Structure (Legacy — note_schemas)

The `note_schemas` format is still supported for backward compatibility. New configs should use `work_item_schemas`.

```yaml
note_schemas:

  your-schema-tag:             # Matches items whose tags include "your-schema-tag"
    - key: note-key
      role: queue
      required: true
      description: "..."
      guidance: "..."
```

When using `note_schemas`, the `lifecycle` field is not available — all schemas default to `AUTO` lifecycle mode.

---

## Field Reference

### Schema-level fields (work_item_schemas only)

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `lifecycle` | no | string | `auto`, `manual`, `auto-reopen`, or `permanent`. Defaults to `auto` |
| `default_traits` | no | list | Trait names applied to every item matching this schema |

### Note-level fields

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `key` | yes | string | kebab-case, unique within schema, stable after creation |
| `role` | yes | string | `queue`, `work`, or `review` only |
| `required` | yes | boolean | `true` = blocks gate, `false` = shown but not enforced |
| `description` | yes | string | Keep under 80 chars — quick label agents scan |
| `guidance` | no | string | Project-specific authoring instructions — shown as `guidancePointer` in `get_context` |
| `skill` | no | string | Reusable evaluation framework — shown as `skillPointer` in `get_context` |

### `guidance` vs `skill` — when to use which

These fields serve different purposes and work together:

- **`guidance`** provides project-specific instructions for *what* the note should cover. It's free text you write in config — "Cover: problem statement, acceptance criteria, alternatives considered, blast radius, test strategy." The agent sees this as `guidancePointer` when it's about to fill the note.

- **`skill`** references a reusable evaluation framework that defines *how* to produce the note. The agent invokes the skill (e.g., `/spec-quality`) before writing, getting a structured methodology — rubrics, checklists, evaluation dimensions. The agent sees this as `skillPointer`.

| Combination | When to use |
|-------------|------------|
| `guidance` only | Most notes. You know what content you want — just tell the agent. |
| `skill` only | The skill is self-contained and doesn't need project-specific tailoring. |
| Both | The skill provides the structured process; guidance adds project-specific requirements the skill doesn't cover. The agent invokes the skill first, then uses guidance to fill in domain-specific details. |
| Neither | The note key and description are self-explanatory — no additional direction needed. |

---

## Lifecycle Modes

The `lifecycle` field on a schema controls automatic cascade behavior when children reach terminal:

| Mode | Behavior |
|------|----------|
| `auto` | Default — parent cascades to terminal when all children are terminal |
| `manual` | Terminal cascade suppressed — parent must be explicitly completed. Reopen cascade also suppressed. |
| `auto-reopen` | Terminal cascade allowed. Parent also auto-reopens when a new child is created under it. |
| `permanent` | Parent never auto-terminates and never auto-reopens — always manual lifecycle. |

---

## Matching Rules

Schema resolution uses **type-first lookup with tag fallback**:

1. If the item has a `type` field, look up the schema by type in `work_item_schemas` → direct lookup (exact match)
2. If no type or no type-based schema found, look up by first tag match in `note_schemas` (legacy)
3. If no tag matches, fall back to the schema named `default` (in either section) if one exists
4. If nothing matches, the item is schema-free — no gate enforcement

**Key points:**
- Setting `type` on an item is the preferred way to activate a schema
- Tags can still be used for schema matching (legacy), but `type` takes precedence
- Only one schema applies per item
- Matching is exact and case-sensitive

---

## Phase Flow

```
queue ──(start)──► work ──(start)──► review ──(start)──► terminal
```

- If the schema has NO `role: review` notes, `start` from work goes directly to terminal (review is skipped)
- Required notes for the *current* phase gate the `start` trigger
- Optional notes (`required: false`) are shown but do not block advancement

---

## Design Principles

- **Keep it lean.** 2-3 required notes per phase is the practical maximum before agents find workarounds
- **Queue gates = pre-work contract.** Requirements, design, root cause — filled before agents touch code
- **Work gates = evidence of completion.** Implementation notes, test results — filled after implementation
- **Review phase is optional.** Only add `role: review` notes for genuine deploy/verify/sign-off steps
- **Optional notes are reminders.** `required: false` notes appear in `expectedNotes` without blocking
- **Use `type` for schema selection, tags for categorization.** Mixing them causes confusion.

---

## Multiple Schemas

```yaml
work_item_schemas:

  schema-one:
    lifecycle: auto
    notes:
      - key: ...

  schema-two:
    lifecycle: manual
    notes:
      - key: ...
```

Each schema is independent. An item matches at most one schema.

---

## Mixed Legacy Example

If you have an existing config using `note_schemas`, you can add new schemas as `work_item_schemas` alongside it:

```yaml
work_item_schemas:

  feature-task:
    lifecycle: auto
    notes:
      - key: implementation-notes
        role: work
        required: true
        description: "What was built and why"

note_schemas:

  bug-fix:
    - key: root-cause
      role: queue
      required: true
      description: "Root cause analysis"
```

Items with `type: feature-task` use the `work_item_schemas` entry. Items with tag `bug-fix` use the legacy `note_schemas` entry.

---

## Actor authentication

The `actor_authentication` section is a top-level key alongside `work_item_schemas` and `traits`. It controls whether actor attribution is enforced on write operations.

```yaml
actor_authentication:
  enabled: true

work_item_schemas:
  # ...
```

Default: `false` when absent — actor authentication is opt-in.

### Fields

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `enabled` | yes | boolean | `true` = block write calls missing actor claims. `false` = no enforcement |

### Behavior

When `actor_authentication.enabled` is `true`, the plugin's PreToolUse hook blocks `advance_item` and `manage_notes(upsert)` calls that are missing an `actor` object on any element. The agent must retry with actor attribution included.

When `false` or absent, actor claims are optional — calls pass through with no enforcement. Actor claims can still be provided voluntarily.

### Verifier

The optional `verifier` sub-key enables server-side JWT validation of actor claims. It operates independently of `enabled` — a call can pass client-side enforcement (actor present) but still fail server-side verification (bad or expired JWT).

**Fields:**

| Field | Required | Type | Default | Notes |
|-------|----------|------|---------|-------|
| `type` | yes | string | `"noop"` | `"noop"` or `"jwks"` |
| `oidc_discovery` | no | string | — | OIDC discovery URL; auto-populates `jwks_uri` and `issuer` |
| `jwks_uri` | no | string | — | Direct JWKS endpoint URL (overrides OIDC-discovered value) |
| `jwks_path` | no | string | — | Local JWKS file path (relative to `AGENT_CONFIG_DIR`) |
| `issuer` | no | string | — | Expected `iss` claim (overrides OIDC-discovered value) |
| `audience` | no | string | — | Expected `aud` claim |
| `algorithms` | no | list | `[]` | Allowed signing algorithms; empty = accept any |
| `cache_ttl_seconds` | no | number | `300` | JWKS cache TTL in seconds |
| `require_sub_match` | no | boolean | `true` | JWT `sub` must match `actor.id` |
| `stale_on_error` | no | boolean | `true` | Serve stale cached key set if JWKS endpoint is unreachable during refresh. Set `false` to propagate the fetch exception |
| `did_allowlist` | no | list | `[]` | List of trusted DID strings (exact match against JWT `iss` claim). Non-empty activates DID-trust mode |
| `did_pattern` | no | string | — | Glob or regex pattern matching trusted DIDs. Non-null activates DID-trust mode. Mutually exclusive with `did_allowlist` |
| `did_strict_relationship` | no | boolean | `true` | When true, only verification methods referenced from the resolved DID document's `assertionMethod` array are eligible. Set false to allow any key in the document |
| `did_loose_kid_match` | no | boolean | `true` | Allow single-key fallback when JWT `kid` not found in the resolved DID document AND the eligible-key set has exactly one entry. Multi-key documents always require exact `kid` match |

When `type: jwks`, at least one of `oidc_discovery`, `jwks_uri`, or `jwks_path` is required for static-JWKS mode. Explicit `jwks_uri` and `issuer` values override OIDC-discovered values when both are present.

> **DID trust fields** apply only to `type: jwks`. Either `did_allowlist` (non-empty) or `did_pattern` (non-null) activates DID-trust mode — they are mutually exclusive. In DID-trust mode, `oidc_discovery`, `jwks_uri`, and `jwks_path` must all be null. The two trust modes are validated at startup.

**Example — OIDC discovery (simplest):**

```yaml
actor_authentication:
  enabled: true
  verifier:
    type: jwks
    oidc_discovery: "https://agentlair.dev/.well-known/openid-configuration"
```

**Example — File-based (air-gapped):**

```yaml
actor_authentication:
  enabled: true
  verifier:
    type: jwks
    jwks_path: ".agentlair/jwks.json"
```

**Example — Full config (all options):**

> Static-JWKS mode requires exactly one of `oidc_discovery`, `jwks_uri`, or `jwks_path`. The other two source fields are shown commented out below to illustrate the available options.

```yaml
actor_authentication:
  enabled: true
  verifier:
    type: jwks
    oidc_discovery: "https://agentlair.dev/.well-known/openid-configuration"
    # jwks_uri: "https://provider.example/.well-known/jwks.json"   # alternative source
    # jwks_path: ".agentlair/jwks.json"                            # alternative source
    issuer: "https://provider.example"
    audience: "task-orchestrator"
    algorithms: ["EdDSA", "RS256"]
    cache_ttl_seconds: 300
    require_sub_match: true
```

**Example — DID-rooted trust (per-agent identities):**

```yaml
actor_authentication:
  enabled: true
  verifier:
    type: jwks
    algorithms: ["EdDSA", "RS256"]
    audience: "task-orchestrator"
    did_allowlist:
      - "did:web:agent.example.com"
      - "did:web:lair.dev"
    did_loose_kid_match: true
```

When `did_allowlist` or `did_pattern` is set, the verifier resolves the JWT's `iss` claim as a DID and validates the signing key against the resolved DID document's `verificationMethod` entries (subject to `did_strict_relationship`). See `current/docs/fleet-deployment.md` for the full deployment guide.

> **Note:** `enabled` (client-side enforcement) and `verifier` (server-side validation) are independent concerns. A call can pass enforcement (actor present) but have verification fail (bad JWT).

---

## Key Stability Warning

Changing a `key` after notes have been written orphans existing notes under the old key. There is no automatic migration — orphaned notes become ad-hoc notes on their items.
