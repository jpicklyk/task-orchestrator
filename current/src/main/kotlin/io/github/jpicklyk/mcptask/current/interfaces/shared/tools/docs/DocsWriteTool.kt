package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.docs

import io.github.jpicklyk.mcptask.current.application.api.docs.DocAttachRequest
import io.github.jpicklyk.mcptask.current.application.api.docs.DocDeleteRequest
import io.github.jpicklyk.mcptask.current.application.api.docs.DocHandlers
import io.github.jpicklyk.mcptask.current.application.api.docs.DocStateRequest
import io.github.jpicklyk.mcptask.current.application.api.docs.DocUpsertRequest
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCodec
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

const val DOCS_WRITE = "docs_write"

private object JsonObjectCodec : IdempotencyCodec<JsonObject> {
    override fun encode(value: JsonObject): JsonElement = value

    override fun decode(json: JsonElement): JsonObject = json as JsonObject
}

private val DOC_ERRORS = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN)

/** `docs_write upsert`: create the document or write its next version. */
internal val docsUpsertOp =
    OpSpec<DocUpsertRequest, JsonObject>(
        name = "upsert",
        doc =
            "Create the document at (root, kind, slug) or write its next version; CRLF becomes LF. Give exactly one of " +
                "body and bodyFromFile. A plan or page body is at most 65536 bytes, a rule body 16384. An adopted " +
                "plan is duplicate and an archived document invalid_request. Result: {id, kind, slug, version, contentHash, created}.",
        capability = ApiCapability.WRITE_CONFIG,
        request = serializer<DocUpsertRequest>(),
        errors = DOC_ERRORS + setOf(ErrorCode.VERSION_CONFLICT, ErrorCode.PAYLOAD_TOO_LARGE, ErrorCode.DUPLICATE),
        execute = { env, ctx, req -> DocHandlers.upsert(this, env, ctx, req).map { DocJson.write(it) } }
    )

/** `docs_write archive`: hide a document from the default listing. */
internal val docsArchiveOp =
    OpSpec<DocStateRequest, JsonObject>(
        name = "archive",
        doc =
            "Archive the document; its version does not change. Archiving an archived document does nothing. " +
                "Result: {id, kind, slug, version, archived}.",
        capability = ApiCapability.WRITE_CONFIG,
        request = serializer<DocStateRequest>(),
        errors = DOC_ERRORS + ErrorCode.VERSION_CONFLICT,
        execute = { env, ctx, req -> DocHandlers.setArchived(this, env, ctx, req, archive = true).map { DocJson.write(it) } }
    )

/** `docs_write unarchive`: restore an archived document. */
internal val docsUnarchiveOp =
    OpSpec<DocStateRequest, JsonObject>(
        name = "unarchive",
        doc = "Unarchive the document. Unarchiving a live document does nothing. Result: {id, kind, slug, version, archived}.",
        capability = ApiCapability.WRITE_CONFIG,
        request = serializer<DocStateRequest>(),
        errors = DOC_ERRORS + ErrorCode.VERSION_CONFLICT,
        execute = { env, ctx, req -> DocHandlers.setArchived(this, env, ctx, req, archive = false).map { DocJson.write(it) } }
    )

/** `docs_write delete`: remove a document and its versions. */
internal val docsDeleteOp =
    OpSpec<DocDeleteRequest, JsonObject>(
        name = "delete",
        doc =
            "Delete the document and its versions. Links to it become dangling. An adopted plan cannot be deleted. " +
                "Result: {id, kind, slug, deleted}.",
        destructive = true,
        capability = ApiCapability.WRITE_CONFIG,
        request = serializer<DocDeleteRequest>(),
        errors = DOC_ERRORS,
        execute = { env, ctx, req -> DocHandlers.delete(this, env, ctx, req).map { DocJson.write(it) } }
    )

/** `docs_write attach`: link an item to a document. */
internal val docsAttachOp =
    OpSpec<DocAttachRequest, JsonObject>(
        name = "attach",
        doc =
            "Attach an item of the same root to the document. Attaching twice returns the same link. " +
                "Result: {linkId, documentId, itemId}.",
        capability = ApiCapability.WRITE_CONFIG,
        request = serializer<DocAttachRequest>(),
        errors = DOC_ERRORS,
        execute = { env, ctx, req -> DocHandlers.attach(this, env, ctx, req).map { DocJson.write(it) } }
    )

/** `docs_write detach`: remove an item attachment. */
internal val docsDetachOp =
    OpSpec<DocAttachRequest, JsonObject>(
        name = "detach",
        doc = "Remove the item's attachment to the document. Result: {detached: n}, 0 when it was not attached.",
        destructive = true,
        capability = ApiCapability.WRITE_CONFIG,
        request = serializer<DocAttachRequest>(),
        errors = DOC_ERRORS,
        execute = { env, ctx, req -> DocHandlers.detach(this, env, ctx, req).map { DocJson.write(it) } }
    )

/** The docs_write tool: write, archive, delete and attach documents. Every op needs the write-config capability. */
fun docsWriteTool(): OpsToolSpec<JsonObject> =
    OpsToolSpec(
        name = DOCS_WRITE,
        description =
            "Write the versioned documents (plans, rules, pages) of a project root, in batches: upsert, archive, " +
                "unarchive, delete, attach and detach. Every op needs the write-config capability.",
        ops = listOf(docsUpsertOp, docsArchiveOp, docsUnarchiveOp, docsDeleteOp, docsAttachOp, docsDetachOp),
        codec = JsonObjectCodec,
        element = { it }
    )
