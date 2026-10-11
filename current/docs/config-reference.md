# Config reference

The canonical description of a Task Orchestrator config document: which keys exist, who owns them, what the server
rejects, and how the server reports a rejection. The structure itself is published as a JSON Schema (draft 2020-12):
[`config.schema.json`](../src/main/resources/config/config.schema.json), served by `GET /api/v1/config/schema`
(see [REST API Reference](api-rest.md)).

A config document is YAML. It reaches the server two ways: the server-wide file `.taskorchestrator/config.yaml`
(read once at startup from `AGENT_CONFIG_DIR`, see [Fleet Deployment](fleet-deployment.md)), and a per-root document
pushed with `manage_project_config` or `PUT /api/v1/roots/{rootId}/config`, which layers over the server file for
that project root's items and hot-reloads without a restart.

## Key ownership

Every top-level key has one owner. The server validates what it owns and stores the rest without reading it.

| Key | Owner | Server file | Per-root push | Notes |
|-----|-------|-------------|---------------|-------|
| `work_item_schemas` | server | yes | yes | Note schemas by item `type`: `lifecycle`, `default_traits`, `notes`, `seats`. |
| `note_schemas` | server | yes | yes | The legacy 3.x form of `work_item_schemas`. Cannot appear in the same document as `work_item_schemas`. |
| `traits` | server | yes | yes | Composable signals: `notes`, `resources`, `dispatch`, `seats`. |
| `note_limits` | server | yes | yes | `mode`: `warn` or `reject`. |
| `status_labels` | server | yes | yes | Trigger to label map; an explicit `null` clears a label. |
| `independence` | server | yes | yes | `mode` (`off`, `warn`, `reject`) and `require_verified`. |
| `project` | server (per-root) | ignored | yes | `rootId` and `name`. A `rootId` that differs from the push target is rejected unless `force`. |
| `resources` | server only | yes | rejected | The resource registry is one per server. |
| `actor_authentication` | server only | yes | rejected | Validated by the actor-authentication service. |
| `extensions` | extension | allowed | allowed | A mapping of namespaces. Stored, never read by the server. |
| `schema_resolution` | removed | warning | rejected | Removed in 4.0; schemas resolve by one fixed precedence rule. |

### `extensions`

Plugin and hook settings belong under `extensions.<tool>`, not at the top level. `extensions` must be a mapping
(`null`, a list or a scalar is an error); the namespaces and everything under them are opaque to the server and may
change without a server release. The Claude Code plugin keeps its settings under `extensions.claude-code`:

```yaml
extensions:
  claude-code:
    orchestration:
      mode: workflow
    retrospective:
      mode: dispatch
    actor_attribution:
      required: true
```

In 3.x these three sections sat at the top level (`orchestration`, `retrospective`, `actor_attribution`). The strict
parser no longer knows them there: pushing them at the top level is rejected (`/orchestration` and so on) and the
fix is to move them under `extensions.claude-code`. Documents already stored from 3.x keep loading (see
[Parse modes](#parse-modes)).

## Strict validation

A pushed document is validated before it is stored, and nothing is stored if any error is found. Validation has
three parts, merged into one result:

1. **Structure.** The document is checked against the JSON Schema. Every modeled level sets
   `additionalProperties: false`, so an unknown key is an error at the unknown key's own path.
2. **The parser's findings.** Everything the 3.x parser used to skip with a warning is an error: a note entry with no
   `role` or an invalid one, an invalid `lifecycle` (including the removed `auto-reopen`), a seat with no `name`, an
   invalid dispatch `effort`, a `maxHolders` above 1, a reserved budget key, and so on.
3. **Semantics.** `resources` and `actor_authentication` are rejected in a per-root document; `work_item_schemas` and
   `note_schemas` cannot be combined; every `default_traits` entry must name a trait defined in the document or in
   the server config; every trait `resources[].key` must be declared in the server resource registry.

A structural error in the seat declarations (two seats entering one phase, a duplicate or reserved seat name, an
`after` cycle) is fatal in every mode and is reported with the path of the offending `seats` list.

### Errors and warnings

The result is `{errors: [{path, reason}], warnings: [{path, reason}]}`, in a stable order (sorted by path, then
reason). `path` is an RFC 6901 JSON Pointer into the document you sent: `/work_item_schemas/feature-task/notes/0/role`.
The root is the empty string. A key containing `/` or `~` is escaped as `~1` and `~0`
(`a/b~c` becomes `a~1b~0c`). An unknown key is reported at the key itself.

Warnings never reject a push. They are advisory: a resource key declared by three or more traits (contention is
likely), and a required note that no declared seat owns.

How a rejection reaches you:

| Surface | Rejection |
|---------|-----------|
| REST `PUT /api/v1/roots/{rootId}/config` | `422` with error `parse_error`; `details.errors` holds the list (and `details.warnings` any advisory findings). |
| MCP `manage_project_config` push | `VALIDATION_ERROR`; the same list is in `details` (text) and `data.errors`. |

`force` bypasses the rootId-mismatch and superseded guards. It does not bypass validation.

### Checking before you push

`ConfigService.validate` runs the push pipeline without the write, the compare-and-set and the superseded guard. It
returns the errors and warnings and, when the document is valid, a diff against the root's head version:
`{types, traits, settings}`, each with `added`, `removed` and `changed` entry names. An entry is `changed` when its
canonical JSON differs. With no head (or a reset head) everything is `added`. This is a service method today; the MCP
and REST entry points for it arrive with the 4.0 `config` tool.

## Parse modes

The parser has two modes. Extraction is identical in both: the same document comes out of the same input. The mode
only classifies a finding.

| Mode | A former soft skip is | Used for |
|------|-----------------------|----------|
| `STRICT` | an error | pushing and validating new config |
| `MIGRATION` | a warning | reading config that already exists: stored versions, the server file, canonicalizing the rows copied from 3.x, restoring a version on rollback |

Reading a stored version is never an admission point, so a root whose 3.x config carries the old top-level plugin
sections keeps working. Only a new push has to be clean. Advisory findings are warnings in both modes.

## Related

- [config-format.md](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md): the
  schema-authoring guide (note entries, traits, seats, dispatch, resources) the plugin ships.
- [REST API Reference](api-rest.md), section "Project config", for the routes.