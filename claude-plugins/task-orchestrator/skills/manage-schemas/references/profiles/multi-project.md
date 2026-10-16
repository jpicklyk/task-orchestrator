# Profile — Multi-Project Platform Layering

**Recommend when:** one server, several repos/teams with different workflows. This is a
layering strategy, not one YAML:

- **Global floor** (`AGENT_CONFIG_DIR` config): only process schemas every project should share
  (observations, retrospectives, generic containers) plus the `resources:` registry for
  genuinely server-wide keys (the registry is global-wins by design — a resource key is a
  server-wide lock namespace).
- **Per-root** (config-sync / `config` `push`): each team's own types and traits —
  hot-reloads without restart, and a per-root exact type wins over the same type on the server.
- **Per-team dispatch**: claim-mode fleets and orchestrated projects coexist on one server.

**Rationale to present:** walk the user toward per-root configs per team and a deliberately
minimal global floor. A gate-free team declares each of its types with `notes: []` (see `schema-free.md`). An empty `default` alone admits no new type, and the server's typed schemas still win over a root `default`. Resource
keys should be project-scoped (`team-a/staging-db`) to avoid false serialization across teams. Read the
queue with `items_read` `next` / `blocked` scoped by `ancestorId` to the team's root.
