# Profile — Multi-Project Platform Layering

**Recommend when:** one server, several repos/teams with different workflows. This is a
layering strategy, not one YAML:

- **Global floor** (`AGENT_CONFIG_DIR` config): only process schemas every project should share
  (observations, retrospectives, generic containers) plus the `resources:` registry for
  genuinely server-wide keys (the registry is global-wins by design — a resource key is a
  server-wide lock namespace).
- **Per-root** (config-sync / `manage_project_config` push): each team's own types and traits —
  hot-reloads without restart, wins over the global floor for that root.
- **Per-team dispatch**: claim-mode fleets and orchestrated projects coexist on one server.

**Rationale to present:** walk the user toward per-root configs per team and a deliberately
minimal global floor. A gate-free team fences itself off with the empty `default` schema (see
`schema-free.md`). Resource keys should be project-scoped (`team-a/staging-db`) to avoid false
serialization across teams.
