package io.github.jpicklyk.mcptask.current.model.schema

import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.note.OwningSeat

/**
 * One entry in a schema's or trait's `seats:` list: a role within a phase.
 *
 * Declared in `.taskorchestrator/config.yaml`, either at schema level or trait level:
 *
 * ```yaml
 * work_item_schemas:
 *   bug-fix:
 *     seats:
 *       - { name: planner,     phase: queue }
 *       - { name: implementer, phase: work, enters: true }
 *       - { name: extractor,   phase: work, after: [implementer], transitions: [block, hold, resume] }
 * traits:
 *   needs-test-author:
 *     seats:
 *       - { name: test-author, phase: work, after: [extractor], reads_exclude: [implementation-notes] }
 * ```
 *
 * The server enforces seats only inside an open run (R23): on an item that is a member of a run that is not closed, and
 * whose pinned schema is seat-aware, a seat token may only `start` the item into the phase its seat [enters], may send
 * any other trigger only when [effectiveTransitions] lists it, and may write only the note keys its seat owns (plus
 * unowned keys). Outside an open run seats stay an orchestration signal.
 *
 * Seat names are opaque to the server except two reserved names:
 * - `unowned`: the bucket (see [io.github.jpicklyk.mcptask.current.application.service.UNOWNED_SEAT_BUCKET]), which a
 *   seat may never be named (a load-time structural error).
 * - `orchestrator` ([ORCHESTRATOR_WRITABLE_SEAT]): the orchestrator seat, for notes the orchestrator writes itself; the
 *   plugin's run planner dispatches no agent to it (the server does not refuse an `assign`). In an open run the run token may write its keys, and it owns its labelled notes in any
 *   phase it is declared (see [OwningSeat]).
 *
 * Every other seat owns only notes of its own phase.
 *
 * @property name Seat identifier, unique within its declaring scope (a load-time structural error
 *   otherwise — see `YamlSchemaParser.parseRoot`'s F1-F5 checks).
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
 *   test-author-style blindness; never enforced).
 * @property transitions The user triggers other than `start` this seat may send inside an open run, as wire names
 *   (`complete`, `block`, `hold`, `resume`, `cancel`, `reopen`); null when not declared ([DEFAULT_TRANSITIONS] apply).
 *   `start` is governed by [enters], never listed here. An entering seat may not list `complete` or `cancel` (F5).
 * @property rules The rule keys this seat works under, served so a dispatching plugin can fetch them for the seat; null
 *   when not declared (the plugin's defaults apply), `[]` when the seat declares none. Never enforced by the server and
 *   not checked for existence: rules are a separate store, so a key that is not served is the plugin's finding.
 * @property skills The skill names this seat works under, served so a dispatching plugin can load them for the seat; null
 *   when not declared (the plugin's defaults apply), `[]` when the seat declares none. Names are kept verbatim (a plugin
 *   skill carries a `plugin:` prefix). Never enforced by the server and not checked for existence.
 */
data class SeatDefinition(
    val name: String,
    val phase: Role,
    val enters: Boolean = false,
    val after: List<String> = emptyList(),
    val readsExclude: List<String> = emptyList(),
    val transitions: List<String>? = null,
    val rules: List<String>? = null,
    val skills: List<String>? = null
) {
    /** The triggers (other than `start`) this seat may send: [transitions] when declared, else [DEFAULT_TRANSITIONS]. */
    val effectiveTransitions: List<String> get() = transitions ?: DEFAULT_TRANSITIONS

    companion object {
        /** The triggers a seat may send when it declares no `transitions:`. */
        val DEFAULT_TRANSITIONS: List<String> = listOf("block", "hold")

        /** The reserved `orchestrator` seat name: a run's token may write the keys this seat owns. */
        const val ORCHESTRATOR_WRITABLE_SEAT: String = "orchestrator"
    }
}
