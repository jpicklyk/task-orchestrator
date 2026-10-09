package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.support.UnitResult
import io.github.jpicklyk.mcptask.current.application.support.writeUnit
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DeleteCause
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import java.util.UUID

/**
 * One note write: the raw, un-normalized request. [bodyFromFile] is the server-side path the caller supplied
 * when [body] was read from a file (recorded on the
ote.upserted row; never the file contents); null for an
 * inline body.
 */
data class NoteUpsertCommand(
    val itemId: UUID,
    val key: String,
    val role: String,
    val body: String,
    val actorClaim: ActorClaim?,
    val verification: VerificationResult?,
    val bodyFromFile: String? = null
)

/** A note that passed the write policy: the normalized [role] and [body], plus any soft-limit [warning]. */
data class PreparedNote(
    val role: String,
    val body: String,
    val warning: NoteLengthWarning?
)

/** A body over the schema `maxLength` that `note_limits.mode: warn` accepted. */
data class NoteLengthWarning(
    val key: String,
    val maxLength: Int,
    val actualLength: Int
) {
    /** The human-readable text surfaced to callers (MCP `warning`, REST `warning`). */
    fun message(): String = "body length $actualLength exceeds maxLength $maxLength for key '$key'"
}

/** The stored note, whether this write created it, and any soft-limit [warning]. */
data class NoteWriteResult(
    val note: Note,
    val created: Boolean,
    val warning: NoteLengthWarning?
)

/**
 * The single owner of the note write policy, shared by `manage_notes`, the REST note routes and
 * `create_work_tree`. JSON-free and transport-free: callers map the returned [DomainError] codes to
 * their own wire shapes.
 *
 * Policy, in this order: role normalization (locale-invariant lowercase, no trim; must be
 * queue|work|review), the absolute byte cap ([MAX_NOTE_BODY_BYTES], measured on the raw body, any
 * `note_limits` mode), CRLF-to-LF normalization (a lone CR is kept), the schema-role rule (a key the
 * item's resolved schema declares must carry the schema's role), and the schema `maxLength` (measured
 * on the normalized body; `note_limits.mode: reject` fails, `warn` accepts with a [NoteLengthWarning]).
 *
 * Every write records its own `events` rows through the unit's sink, in the same unit: `note.upserted` (the
 * principal is the note's own actor claim and verification) and `note.deleted` (cause `explicit`), under the
 * owning item's root ([eventRootOf]). A write called inside an ambient unit joins it.
 * A `PerRootConfigUnavailableException` from the config resolver propagates; it is not an [Outcome].
 */
class NoteCommandService(
    private val repositoryProvider: RepositoryProvider,
    private val configResolver: EffectiveConfigResolver,
    private val unitOfWork: UnitOfWork
) {
    /** Validate-only: runs the write policy for [item] and stores nothing. */
    suspend fun prepare(
        item: WorkItem,
        key: String,
        role: String,
        body: String
    ): Outcome<PreparedNote> {
        val normalizedRole = role.lowercase()
        if (normalizedRole !in VALID_ROLES) {
            return Outcome.Err(invalidRequest("role", "role must be one of: queue, work, review", role))
        }

        val bytes = body.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_NOTE_BODY_BYTES) {
            return Outcome.Err(
                DomainError(
                    code = ErrorCode.PAYLOAD_TOO_LARGE,
                    message = "Note '$key' body is $bytes bytes, exceeds the $MAX_NOTE_BODY_BYTES byte cap",
                    detail = ErrorDetail.PayloadTooLarge(MAX_NOTE_BODY_BYTES.toLong(), bytes.toLong()),
                    fixArgs = mapOf("max" to "$MAX_NOTE_BODY_BYTES bytes")
                )
            )
        }

        val normalizedBody = body.replace("\r\n", "\n")

        val schemaEntry = configResolver.resolveSchema(item)?.notes?.firstOrNull { it.key == key }
        if (schemaEntry != null) {
            val expectedRole = schemaEntry.role.name.lowercase()
            if (normalizedRole != expectedRole) {
                val reason =
                    "key '$key' is declared in the schema with role '$expectedRole', " +
                        "but the note has role '$role'. Schema-declared keys must use the schema role."
                return Outcome.Err(
                    DomainError(
                        code = ErrorCode.SCHEMA_VIOLATION,
                        message = reason,
                        detail = ErrorDetail.SchemaViolation(type = item.type, reason = reason)
                    )
                )
            }
        }

        val maxLength = schemaEntry?.maxLength
        var warning: NoteLengthWarning? = null
        if (maxLength != null && normalizedBody.length > maxLength) {
            if (configResolver.resolveNoteLimitsMode(item.rootId) == "reject") {
                return Outcome.Err(
                    DomainError(
                        code = ErrorCode.NOTE_TOO_LONG,
                        message = "body length ${normalizedBody.length} exceeds maxLength $maxLength for key '$key'",
                        detail = ErrorDetail.NoteTooLong(key, maxLength, normalizedBody.length),
                        fixArgs = mapOf("key" to key, "max" to maxLength.toString())
                    )
                )
            }
            warning = NoteLengthWarning(key, maxLength, normalizedBody.length)
        }

        return Outcome.Ok(PreparedNote(normalizedRole, normalizedBody, warning))
    }

    /** Looks up the item, applies the write policy, and upserts the note in one write unit. */
    suspend fun upsert(cmd: NoteUpsertCommand): Outcome<NoteWriteResult> {
        val noteRepo = repositoryProvider.noteRepository()
        // writeUnit maps any thrown exception to a store fault; config-unavailable must still propagate (not an Outcome).
        var configUnavailable: PerRootConfigUnavailableException? = null
        val result =
            unitOfWork.writeUnit<Outcome<NoteWriteResult>>(
                "NoteCommandService.upsert",
                onFault = { Outcome.Err(it) }
            ) {
                val item =
                    repositoryProvider.workItemRepository().getById(cmd.itemId)
                        ?: return@writeUnit UnitResult.Rollback(
                            Outcome.Err(notFound(EntityKind.ITEM, cmd.itemId.toString(), "WorkItem '${cmd.itemId}' not found"))
                        )
                val prepared =
                    try {
                        when (val p = prepare(item, cmd.key, cmd.role, cmd.body)) {
                            is Outcome.Ok -> p.value
                            is Outcome.Err -> return@writeUnit UnitResult.Rollback(p)
                        }
                    } catch (e: PerRootConfigUnavailableException) {
                        configUnavailable = e
                        return@writeUnit UnitResult.Rollback(
                            Outcome.Err(notFound(EntityKind.ITEM, cmd.itemId.toString(), "config unavailable"))
                        )
                    }
                val existing = noteRepo.findByItemIdAndKey(cmd.itemId, cmd.key)
                val note =
                    try {
                        Note(
                            id = existing?.id ?: UUID.randomUUID(),
                            itemId = cmd.itemId,
                            key = cmd.key,
                            role = prepared.role,
                            body = prepared.body,
                            actorClaim = cmd.actorClaim,
                            verification = cmd.verification
                        )
                    } catch (e: ValidationException) {
                        return@writeUnit UnitResult.Rollback(
                            Outcome.Err(invalidRequest("key", e.message ?: "Invalid note", cmd.key))
                        )
                    }
                val stored = noteRepo.upsert(note)
                events.record(noteUpsertedEvent(stored, eventRootOf(item, repositoryProvider.workItemRepository()), cmd.bodyFromFile))
                UnitResult.Commit(Outcome.Ok(NoteWriteResult(stored, existing == null, prepared.warning)))
            }
        val unavailable = configUnavailable
        if (unavailable != null) throw unavailable
        return result
    }

    /** Deletes the note [id]; `Ok(false)` when it does not exist. */
    suspend fun deleteById(id: UUID): Outcome<Boolean> =
        writeOutcomeOf("NoteCommandService.deleteById") {
            val noteRepo = repositoryProvider.noteRepository()
            val note = noteRepo.getById(id)
            val deleted = noteRepo.delete(id)
            if (deleted && note != null) events.record(noteDeletedEvent(note, rootOfItem(note.itemId), DeleteCause.EXPLICIT))
            deleted
        }

    /** Deletes the note `(itemId, key)`, returning it, or `Ok(null)` when it does not exist. */
    suspend fun deleteByKey(
        itemId: UUID,
        key: String
    ): Outcome<Note?> =
        writeOutcomeOf("NoteCommandService.deleteByKey") {
            val noteRepo = repositoryProvider.noteRepository()
            val existing = noteRepo.findByItemIdAndKey(itemId, key)
            if (existing != null && noteRepo.delete(existing.id)) {
                events.record(noteDeletedEvent(existing, rootOfItem(existing.itemId), DeleteCause.EXPLICIT))
            }
            existing
        }

    /** Deletes every note on [itemId], returning how many were removed. */
    suspend fun deleteAllForItem(itemId: UUID): Outcome<Int> =
        writeOutcomeOf("NoteCommandService.deleteAllForItem") {
            val noteRepo = repositoryProvider.noteRepository()
            val notes = noteRepo.findByItemId(itemId)
            val count = noteRepo.deleteByItemId(itemId)
            if (count > 0 && notes.isNotEmpty()) {
                val root = rootOfItem(itemId)
                events.record(notes.map { noteDeletedEvent(it, root, DeleteCause.EXPLICIT) })
            }
            count
        }

    /** The root of the item [itemId] as it is NOW ([eventRootOf]; its own id when it cannot be resolved). */
    private suspend fun rootOfItem(itemId: UUID): UUID {
        val repo = repositoryProvider.workItemRepository()
        val item = repo.getById(itemId) ?: return eventRootOf(itemId, repo)
        return eventRootOf(item, repo)
    }

    private suspend fun <T> writeOutcomeOf(
        op: String,
        block: suspend WriteScope.() -> T
    ): Outcome<T> =
        unitOfWork.writeUnit<Outcome<T>>(op, onFault = { Outcome.Err(it) }) {
            UnitResult.Commit(Outcome.Ok(block()))
        }

    private fun invalidRequest(
        field: String,
        reason: String,
        received: String?
    ): DomainError =
        DomainError(
            code = ErrorCode.INVALID_REQUEST,
            message = reason,
            detail = ErrorDetail.InvalidRequest(listOf(FieldViolation(field, reason, received)))
        )

    private fun notFound(
        kind: EntityKind,
        id: String,
        message: String
    ): DomainError =
        DomainError(
            code = ErrorCode.NOT_FOUND,
            message = message,
            detail = ErrorDetail.NotFound(kind, id),
            fixArgs = mapOf("kind" to kind.name.lowercase(), "id" to id)
        )

    companion object {
        /** Absolute cap on a note body's UTF-8 size, measured on the raw body, in every `note_limits` mode. */
        const val MAX_NOTE_BODY_BYTES = 65536

        private val VALID_ROLES = setOf("queue", "work", "review")
    }
}
