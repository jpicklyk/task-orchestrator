package io.github.jpicklyk.mcptask.current.domain.model

/**
 * One entry in a schema's or trait's `seats:` list — an orchestration signal describing a role
 * within a phase, not enforced by the server itself (A1a: parsed, validated, and served; A2 adds
 * enforcement of `readsExclude`/independence).
 *
 * Declared in `.taskorchestrator/config.yaml`, either at schema level or trait level:
 *
 * ```yaml
 * work_item_schemas:
 *   bug-fix:
 *     seats:
 *       - { name: planner,     phase: queue }
 *       - { name: implementer, phase: work, enters: true }
 *       - { name: extractor,   phase: work, after: [implementer] }
 * traits:
 *   needs-test-author:
 *     seats:
 *       - { name: test-author, phase: work, after: [extractor], reads_exclude: [implementation-notes] }
 * ```
 *
 * Seat names are opaque to the server — no meaning is attached to any particular name (including
 * `orchestrator`), except the reserved bucket name `unowned` (see
 * [io.github.jpicklyk.mcptask.current.application.service.UNOWNED_SEAT_BUCKET]), which a seat may
 * never be named (a load-time structural error).
 *
 * @property name Seat identifier, unique within its declaring scope (a load-time structural error
 *   otherwise — see `YamlSchemaParser.parseRoot`'s F1-F4 checks).
 * @property phase The workflow phase (QUEUE, WORK, or REVIEW) this seat operates in.
 * @property enters Whether this seat is the one that transitions the item INTO [phase]. At most one
 *   seat may set this true per phase within a single seats list, or within a schema's own seats
 *   plus the seats of its same-document default traits (load-time structural error, F1); a conflict
 *   discovered only at resolve time (per-item traits, or traits supplied by a different config
 *   layer) is resolved by first-wins with the later seat demoted to `false` and a WARN logged.
 * @property after Seat names this seat's work logically follows (an orchestration ordering hint;
 *   not enforced by the server). A name outside the declaring scope, or in a different phase, is
 *   served as declared with no validation failure.
 * @property readsExclude Note keys this seat should NOT read (an orchestration hint powering
 *   test-author-style blindness; A2 enforces it — A1a only parses and serves it).
 */
data class SeatDefinition(
    val name: String,
    val phase: Role,
    val enters: Boolean = false,
    val after: List<String> = emptyList(),
    val readsExclude: List<String> = emptyList()
)
