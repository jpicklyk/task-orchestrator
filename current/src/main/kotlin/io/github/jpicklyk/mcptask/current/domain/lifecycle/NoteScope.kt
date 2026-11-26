package io.github.jpicklyk.mcptask.current.domain.lifecycle

import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger.Cascade
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger.User
import io.github.jpicklyk.mcptask.current.model.item.Role

/** Which required notes and independence findings a trigger's note gate covers. */
internal enum class NoteScope { CURRENT_PHASE, ALL_PHASES }

/**
 * START and the start cascade check the current phase; COMPLETE and a non-cancel-origin complete
 * cascade check every phase; every other trigger skips the note gate.
 */
internal fun noteScopeOf(trigger: Trigger): NoteScope? =
    when (trigger) {
        User.START -> NoteScope.CURRENT_PHASE
        User.COMPLETE -> NoteScope.ALL_PHASES
        is Cascade.Complete -> if (trigger.cancelOrigin) null else NoteScope.ALL_PHASES
        Cascade.Start -> NoteScope.CURRENT_PHASE
        else -> null
    }

/**
 * The entries a note gate of [scope] covers: those of [role] for [NoteScope.CURRENT_PHASE], all of them for
 * [NoteScope.ALL_PHASES]. The one selector every note gate and phase view uses.
 */
internal fun <E> List<E>.inScope(
    scope: NoteScope,
    role: Role?,
    roleOf: (E) -> Role
): List<E> =
    when (scope) {
        NoteScope.CURRENT_PHASE -> filter { roleOf(it) == role }
        NoteScope.ALL_PHASES -> this
    }
