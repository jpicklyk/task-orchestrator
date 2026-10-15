package io.github.jpicklyk.mcptask.current.domain.model

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
 * Seat names are opaque to the server, with two exceptions: the reserved bucket name `unowned` (see
 * [io.github.jpicklyk.mcptask.current.application.service.UNOWNED_SEAT_BUCKET]), which a seat may never be named (a
 * load-time structural error), and [COORDINATOR_WRITABLE_SEAT] (`orchestrator`), the one seat whose note keys a run's
 * coordinator token may write.
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
 */
data class SeatDefinition(
    val name: String,
    val phase: Role,
    val enters: Boolean = false,
    val after: List<String> = emptyList(),
    val readsExclude: List<String> = emptyList(),
    val transitions: List<String>? = null
) {
    /** The triggers (other than `start`) this seat may send: [transitions] when declared, else [DEFAULT_TRANSITIONS]. */
    val effectiveTransitions: List<String> get() = transitions ?: DEFAULT_TRANSITIONS

    companion object {
        /** The triggers a seat may send when it declares no `transitions:`. */
        val DEFAULT_TRANSITIONS: List<String> = listOf("block", "hold")

        /** The one seat name the server gives meaning to: a run's coordinator token may write the keys this seat owns. */
        const val COORDINATOR_WRITABLE_SEAT: String = "orchestrator"
    }
}
