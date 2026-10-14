package io.github.jpicklyk.mcptask.current.application.api.notes

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ListPage
import io.github.jpicklyk.mcptask.current.application.api.PageRequest
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.api.ScopedIds
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.port.BodySource
import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.port.NoteSummary
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.service.ClaimHolder
import io.github.jpicklyk.mcptask.current.application.service.NoteCommandService
import io.github.jpicklyk.mcptask.current.application.service.NoteUpsertCommand
import io.github.jpicklyk.mcptask.current.application.support.toDomainError
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallRoot
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallTargetVersion
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigInvalidException
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import java.util.UUID

/**
 * The handlers of `notes_read` and `notes_write`: transport-free (no JSON, no SDK, no Ktor), so MCP and REST run
 * the same code and the spec files only map results to the wire.
 *
 * Every handler follows one order: request-shape checks that need no data (`invalid_request`), item id resolution
 * (`not_found` / `ambiguous_id`), [ScopeGuard] (`forbidden`), only then any read of the store or a file. A forbidden call
 * therefore reads and writes nothing and records no event. Note attribution (`actor`, `verification`) is shown only when
 * [CallContext.attributionVisible].
 */
object NoteHandlers {
    private val ROLES = setOf("queue", "work", "review")

    /** `retryAfterMs` of an `unavailable` caused by an unreadable per-root config. */
    private const val CONFIG_RETRY_MS = 1000L

    /** The fixed reason of an `upsert` that sets `bodyFromFile` on a surface other than MCP. */
    const val BODY_FROM_FILE_MCP_ONLY = "bodyFromFile is available over MCP only; send the note text inline in body"

    // ---- reads --------------------------------------------------------------------------------------------

    /** `notes_read get`. */
    suspend fun get(
        env: ToolExecutionContext,
        ctx: CallContext,
        req: NotesGetRequest
    ): Outcome<NoteGetResult> {
        val ifVersion = req.ifVersion
        if (ifVersion != null && ifVersion < 1) {
            return Outcome.Err(invalid("/ifVersion", "must be at least 1", ifVersion.toString()))
        }
        val itemId =
            when (val admitted = admit(env.repositoryProvider, ctx, req.itemId.raw, recordRoot = true)) {
                is Outcome.Err -> return admitted
                is Outcome.Ok -> admitted.value
            }
        val note =
            env.unitOfWork.read { stores.noteRepository().findByItemIdAndKey(itemId, req.key) }
                ?: return Outcome.Err(noteNotFound(itemId, req.key))
        recordCallTargetVersion(note.id, note.version)
        if (ifVersion != null && ifVersion == note.version) return Outcome.Ok(NoteGetResult.Unchanged(note.version))
        return Outcome.Ok(NoteGetResult.Found(view(note, ctx)))
    }

    /**
     * `notes_read list`: one page of the item's notes, metadata only, ordered by key then id. Every page's `total` is the
     * whole filtered list; `truncated` and the cursor say whether more notes follow this page (envelope U22), the cursor
     * encoding `offset + items.size` (built by [ListPage.of]).
     */
    suspend fun list(
        env: ToolExecutionContext,
        ctx: CallContext,
        req: NotesListRequest
    ): Outcome<ListPage<NoteListEntry>> {
        val role = req.role?.lowercase()
        if (role != null && role !in ROLES) {
            return Outcome.Err(invalid("/role", "must be one of: queue, work, review", req.role))
        }
        val page =
            when (val p = PageRequest.from(req.limit, req.cursor)) {
                is Outcome.Err -> return p
                is Outcome.Ok -> p.value
            }
        val itemId =
            when (val admitted = admit(env.repositoryProvider, ctx, req.itemId.raw, recordRoot = true)) {
                is Outcome.Err -> return admitted
                is Outcome.Ok -> admitted.value
            }
        val rows =
            env.unitOfWork
                .read { stores.noteRepository().findSummariesByItemId(itemId, role, req.keys?.toSet()) }
                .sortedWith(compareBy<NoteSummary> { it.key }.thenBy { it.id.toString() })
        val items = rows.drop(page.offset).take(page.limit)
        for (row in items) recordCallTargetVersion(row.id, row.version)
        return Outcome.Ok(ListPage.of(items.map { entry(it, ctx) }, total = rows.size, page = page))
    }

    // ---- writes (each runs inside the element's write unit) ----------------------------------------------

    /** `notes_write upsert`. */
    suspend fun upsert(
        scope: WriteScope,
        env: ToolExecutionContext,
        ctx: CallContext,
        req: NoteUpsertRequest
    ): Outcome<NoteWriteOutcome> {
        val body = req.body
        val file = req.bodyFromFile
        if ((body == null) == (file == null)) {
            return Outcome.Err(invalid("/body", "exactly one of body and bodyFromFile is required", null))
        }
        if (file != null && ctx.surface != CallLogRecord.SURFACE_MCP) {
            // bodyFromFile reads a file on the server's host, so it is MCP-only (3.x REST note writes took an inline body
            // only). Refused before the body source is consulted: no file is ever opened for a REST caller.
            return Outcome.Err(invalid(BodySource.FIELD, BODY_FROM_FILE_MCP_ONLY, null))
        }
        val expected = req.expectedVersion
        if (expected != null && expected < 0) {
            return Outcome.Err(invalid("/expectedVersion", "must be 0 or greater", expected.toString()))
        }
        val itemId =
            when (val admitted = admit(scope.stores, ctx, req.itemId.raw)) {
                is Outcome.Err -> return admitted
                is Outcome.Ok -> admitted.value
            }
        val caller =
            when (val resolved = caller(env, ctx)) {
                is Outcome.Err -> return resolved
                is Outcome.Ok -> resolved.value
            }
        val text =
            if (file != null) {
                when (val read = env.bodySource.read(file)) {
                    is Outcome.Err -> return read
                    is Outcome.Ok -> read.value
                }
            } else {
                body ?: ""
            }
        val principal = ctx.principal
        val written =
            try {
                env.noteCommandService.upsert(
                    NoteUpsertCommand(
                        itemId = itemId,
                        key = req.key,
                        role = req.role,
                        body = text,
                        actorClaim = principal.claim,
                        verification = principal.verification,
                        caller = caller,
                        bodyFromFile = file,
                        expectedVersion = expected
                    )
                )
            } catch (e: PerRootConfigUnavailableException) {
                // Resolving the item's schema (maxLength, note limits) failed with no last-known-good config: nothing was stored.
                return Outcome.Err(
                    DomainError(
                        code = ErrorCode.UNAVAILABLE,
                        message = "The project config could not be read; retry shortly.",
                        detail = ErrorDetail.Unavailable(CONFIG_RETRY_MS)
                    )
                )
            } catch (e: PerRootConfigInvalidException) {
                // The head config cannot be loaded (fail closed): nothing was stored.
                return Outcome.Err(e.toDomainError())
            }
        return when (written) {
            is Outcome.Err -> written
            is Outcome.Ok -> {
                val result = written.value
                recordCallTargetVersion(result.note.id, result.note.version)
                Outcome.Ok(NoteWriteOutcome.Upserted(result.note.id, result.note.version, result.created, result.warning))
            }
        }
    }

    /** `notes_write delete`. */
    suspend fun delete(
        scope: WriteScope,
        env: ToolExecutionContext,
        ctx: CallContext,
        req: NoteDeleteRequest
    ): Outcome<NoteWriteOutcome> {
        val expected = req.expectedVersion
        if (expected != null && expected < 0) {
            return Outcome.Err(invalid("/expectedVersion", "must be 0 or greater", expected.toString()))
        }
        val itemId =
            when (val admitted = admit(scope.stores, ctx, req.itemId.raw)) {
                is Outcome.Err -> return admitted
                is Outcome.Ok -> admitted.value
            }
        val caller =
            when (val resolved = caller(env, ctx)) {
                is Outcome.Err -> return resolved
                is Outcome.Ok -> resolved.value
            }
        val deleted = env.noteCommandService.delete(itemId, req.key, expected, caller)
        return when (deleted) {
            is Outcome.Err -> deleted
            is Outcome.Ok -> Outcome.Ok(NoteWriteOutcome.Deleted(deleted.value.id))
        }
    }

    // ---- shared steps -------------------------------------------------------------------------------------

    /** The claim fence's caller: the call principal's trusted id ([ClaimHolder.resolve]); a policy rejection is `unauthenticated`. */
    private fun caller(
        env: ToolExecutionContext,
        ctx: CallContext
    ): Outcome<ClaimHolder> = ClaimHolder.resolve(ctx.principal.claim, ctx.principal.verification, env.degradedModePolicy)

    /**
     * Resolves [rawItemId] by the one scope-aware rule ([ScopedIds.resolveTarget]): `not_found` / `ambiguous_id` for an
     * id that names no single item, `forbidden` for an item outside the call's scope, else the item id. A restricted
     * scope never learns whether an item exists (no match and out-of-scope matches are both `forbidden`, and an
     * ambiguous prefix lists only admitted candidates); an unrestricted one gets `not_found` for a missing item.
     */
    private suspend fun admit(
        stores: RepositoryProvider,
        ctx: CallContext,
        rawItemId: String,
        recordRoot: Boolean = false
    ): Outcome<UUID> {
        val workItems = stores.workItemRepository()
        val itemId =
            when (val resolved = ScopedIds.resolveTarget(workItems, ctx.scope, rawItemId, "/itemId")) {
                is Outcome.Err -> return resolved
                is Outcome.Ok -> resolved.value
            }
        val item = workItems.getById(itemId)
        if (item == null) {
            return Outcome.Err(
                DomainError(
                    code = ErrorCode.NOT_FOUND,
                    message = "No item matches $itemId.",
                    detail = ErrorDetail.NotFound(EntityKind.ITEM, itemId.toString()),
                    fixArgs = mapOf("kind" to "item", "id" to itemId.toString())
                )
            )
        }
        // The item is already loaded, so a read's root costs no query.
        if (recordRoot) recordCallRoot(item.rootId ?: item.id.takeIf { item.parentId == null })
        return Outcome.Ok(itemId)
    }

    private fun view(
        note: Note,
        ctx: CallContext
    ): NoteView =
        NoteView(
            id = note.id,
            key = note.key,
            role = note.role,
            version = note.version,
            body = note.body,
            modifiedAt = note.modifiedAt,
            actor = if (ctx.attributionVisible) note.actorClaim else null,
            verification = if (ctx.attributionVisible) note.verification else null
        )

    private fun entry(
        row: NoteSummary,
        ctx: CallContext
    ): NoteListEntry =
        NoteListEntry(
            shortId =
                row.id
                    .toString()
                    .replace("-", "")
                    .take(NoteListEntry.SHORT_ID_LENGTH),
            key = row.key,
            role = row.role,
            version = row.version,
            bodyLength = row.bodyLength,
            modifiedAt = row.modifiedAt,
            actor = if (ctx.attributionVisible) row.actorClaim else null,
            verification = if (ctx.attributionVisible) row.verification else null
        )

    private fun noteNotFound(
        itemId: UUID,
        key: String
    ): DomainError {
        val id = NoteCommandService.noteRef(itemId, key)
        return DomainError(
            code = ErrorCode.NOT_FOUND,
            message = "Note '$key' not found on item $itemId.",
            detail = ErrorDetail.NotFound(EntityKind.NOTE, id),
            fixArgs = mapOf("kind" to "note", "id" to id)
        )
    }

    private fun invalid(
        field: String,
        reason: String,
        received: String?
    ): DomainError =
        DomainError(
            code = ErrorCode.INVALID_REQUEST,
            message = "The request has 1 invalid field(s): $field.",
            detail = ErrorDetail.InvalidRequest(listOf(FieldViolation(field, reason, received)))
        )
}
