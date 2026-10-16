package io.github.jpicklyk.mcptask.current.application.api.docs

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ScopedIds
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.knowledge.document.PlanSlugs
import io.github.jpicklyk.mcptask.current.application.port.BodySource
import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.port.ListPage
import io.github.jpicklyk.mcptask.current.application.port.PageRequest
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.support.IdRef
import io.github.jpicklyk.mcptask.current.application.support.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallRoot
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallTargetVersion
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Document
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.EntityRef
import io.github.jpicklyk.mcptask.current.domain.model.Link
import io.github.jpicklyk.mcptask.current.domain.model.LinkRelation
import io.github.jpicklyk.mcptask.current.domain.model.RefKind
import java.util.UUID

/**
 * The handlers of `docs_read` and `docs_write`: transport-free (no JSON, no SDK, no Ktor), so MCP and REST run the
 * same code and the spec files only map results to the wire.
 *
 * Every handler follows one order: request-shape checks that need no data (`invalid_request`), root id resolution
 * (`not_found` / `ambiguous_id`), [ScopeGuard] on the root and on any item the request names (`forbidden`), only then
 * any read of the store or a file. A forbidden call therefore reads and writes nothing and records no event. Version
 * actors are shown only when [CallContext.attributionVisible]. Items reached through a document (adopters, link ends,
 * an attached item) are checked against the call's scope too, so a document never reveals an item the caller cannot see.
 */
object DocHandlers {
    /** The fixed reason of an `upsert` that sets `bodyFromFile` on a surface other than MCP. */
    const val BODY_FROM_FILE_MCP_ONLY = "bodyFromFile is available over MCP only; send the document text inline in body"

    /** Longest accepted slug of an `upsert`, in characters. */
    const val MAX_SLUG_LENGTH = 200

    private const val SLUG_FORBIDDEN_CHARS = "[]#"
    private const val MAX_ECHO = 200

    // ---- reads --------------------------------------------------------------------------------------------

    /** `docs_read get`. */
    suspend fun get(
        env: ToolExecutionContext,
        ctx: CallContext,
        req: DocsGetRequest
    ): Outcome<DocGetResult> {
        val version = req.version
        val ifVersion = req.ifVersion
        if (version != null && ifVersion != null) {
            return Outcome.Err(invalid("/version", "give either version or ifVersion, not both", version.toString()))
        }
        if (version != null && version < 1) return Outcome.Err(invalid("/version", "must be at least 1", version.toString()))
        if (ifVersion != null && ifVersion < 1) return Outcome.Err(invalid("/ifVersion", "must be at least 1", ifVersion.toString()))
        slugPresent(req.slug)?.let { return Outcome.Err(it) }
        return env.unitOfWork.read {
            val rootId =
                when (val admitted = admitRoot(stores, ctx, req.rootId)) {
                    is Outcome.Err -> return@read admitted
                    is Outcome.Ok -> admitted.value
                }
            recordCallRoot(rootId)
            val doc = stores.documentStore().get(rootId, req.kind.domain, req.slug) ?: return@read Outcome.Err(documentNotFound(req.slug))
            if (version != null) {
                val stored =
                    stores.documentStore().getVersion(doc.id, version)
                        ?: return@read Outcome.Err(documentNotFound("${req.slug}@$version"))
                // call_log joins document search hits to later reads (Q26): record only the version returned.
                recordCallTargetVersion(doc.id, stored.version.toLong())
                return@read Outcome.Ok(
                    DocGetResult.Version(
                        DocVersionView(
                            kind = doc.kind,
                            slug = doc.slug,
                            version = stored.version,
                            body = stored.body,
                            contentHash = stored.contentHash,
                            createdAt = stored.createdAt,
                            actor = if (ctx.attributionVisible) stored.actorId else null
                        )
                    )
                )
            }
            recordCallTargetVersion(doc.id, doc.version.toLong())
            if (ifVersion != null && ifVersion == doc.version) return@read Outcome.Ok(DocGetResult.Unchanged(doc.version))
            val adopter = adopters(stores, ctx, listOf(doc.id))[doc.id]
            Outcome.Ok(DocGetResult.Found(view(doc, adopter)))
        }
    }

    /**
     * `docs_read list`: one page of the root's documents, metadata only, ordered by kind, slug, then id. `total` is the
     * whole filtered list; the cursor is built by [ListPage.of].
     */
    suspend fun list(
        env: ToolExecutionContext,
        ctx: CallContext,
        req: DocsListRequest
    ): Outcome<ListPage<DocListEntry>> {
        val page =
            when (val p = PageRequest.from(req.limit, req.cursor)) {
                is Outcome.Err -> return p
                is Outcome.Ok -> p.value
            }
        return env.unitOfWork.read {
            val rootId =
                when (val admitted = admitRoot(stores, ctx, req.rootId)) {
                    is Outcome.Err -> return@read admitted
                    is Outcome.Ok -> admitted.value
                }
            recordCallRoot(rootId)
            val rows =
                stores
                    .documentStore()
                    .list(rootId, req.kind?.domain, req.includeArchived == true)
                    .sortedWith(compareBy({ it.kind.toDbValue() }, { it.slug }, { it.id.toString() }))
            val slice = rows.drop(page.offset).take(page.limit)
            val adopted = adopters(stores, ctx, slice.map { it.id })
            val items =
                slice.map { row ->
                    recordCallTargetVersion(row.id, row.version.toLong())
                    DocListEntry(
                        id = row.id,
                        kind = row.kind,
                        slug = row.slug,
                        title = row.title,
                        contentHash = row.contentHash,
                        version = row.version,
                        archivedAt = row.archivedAt,
                        adoptedBy = adopted[row.id],
                        createdAt = row.createdAt,
                        modifiedAt = row.modifiedAt
                    )
                }
            Outcome.Ok(ListPage.of(items, total = rows.size, page = page))
        }
    }

    /** `docs_read history`: the versions of one document, oldest first. */
    suspend fun history(
        env: ToolExecutionContext,
        ctx: CallContext,
        req: DocsHistoryRequest
    ): Outcome<ListPage<DocHistoryEntry>> {
        slugPresent(req.slug)?.let { return Outcome.Err(it) }
        val page =
            when (val p = PageRequest.from(req.limit, req.cursor)) {
                is Outcome.Err -> return p
                is Outcome.Ok -> p.value
            }
        return env.unitOfWork.read {
            val rootId =
                when (val admitted = admitRoot(stores, ctx, req.rootId)) {
                    is Outcome.Err -> return@read admitted
                    is Outcome.Ok -> admitted.value
                }
            val doc = stores.documentStore().get(rootId, req.kind.domain, req.slug) ?: return@read Outcome.Err(documentNotFound(req.slug))
            val rows = stores.documentStore().listVersions(doc.id).sortedBy { it.version }
            val items =
                rows.drop(page.offset).take(page.limit).map {
                    DocHistoryEntry(
                        version = it.version,
                        contentHash = it.contentHash,
                        bodyLength = it.body.length,
                        createdAt = it.createdAt,
                        actor = if (ctx.attributionVisible) it.actorId else null
                    )
                }
            Outcome.Ok(ListPage.of(items, total = rows.size, page = page))
        }
    }

    /**
     * `docs_read links`: the links of one document, ordered by creation time then id. Item and note ends the call's scope
     * does not admit, or that no longer exist, drop their link before paging and are not counted.
     */
    suspend fun links(
        env: ToolExecutionContext,
        ctx: CallContext,
        req: DocsLinksRequest
    ): Outcome<ListPage<DocLinkEntry>> {
        slugPresent(req.slug)?.let { return Outcome.Err(it) }
        val page =
            when (val p = PageRequest.from(req.limit, req.cursor)) {
                is Outcome.Err -> return p
                is Outcome.Ok -> p.value
            }
        return env.unitOfWork.read {
            val rootId =
                when (val admitted = admitRoot(stores, ctx, req.rootId)) {
                    is Outcome.Err -> return@read admitted
                    is Outcome.Ok -> admitted.value
                }
            val doc = stores.documentStore().get(rootId, req.kind.domain, req.slug) ?: return@read Outcome.Err(documentNotFound(req.slug))
            val ref = EntityRef(RefKind.DOCUMENT, doc.id)
            val direction = req.direction ?: DocLinkDirection.ALL
            val raw = LinkedHashMap<UUID, Link>()
            if (direction != DocLinkDirection.INCOMING) stores.linkStore().findFrom(ref).forEach { raw[it.id] = it }
            if (direction != DocLinkDirection.OUTGOING) stores.linkStore().findTo(ref).forEach { raw[it.id] = it }
            val visible =
                visibleLinks(stores, ctx, raw.values.toList())
                    .sortedWith(compareBy<Link> { it.createdAt }.thenBy { it.id.toString() })
            val items =
                visible.drop(page.offset).take(page.limit).map {
                    DocLinkEntry(it.id, it.relation, it.from, it.to, it.toRef, it.anchor, it.createdAt)
                }
            Outcome.Ok(ListPage.of(items, total = visible.size, page = page))
        }
    }

    // ---- writes (each runs inside the element's write unit) ----------------------------------------------

    /** `docs_write upsert`. */
    suspend fun upsert(
        scope: WriteScope,
        env: ToolExecutionContext,
        ctx: CallContext,
        req: DocUpsertRequest
    ): Outcome<DocWriteOutcome> {
        val body = req.body
        val file = req.bodyFromFile
        if ((body == null) == (file == null)) {
            return Outcome.Err(invalid("/body", "exactly one of body and bodyFromFile is required", null))
        }
        if (file != null && ctx.surface != CallLogRecord.SURFACE_MCP) {
            // bodyFromFile reads a file on the server's host, so it is MCP-only. Refused before the body source is
            // consulted: no file is ever opened for a REST caller.
            return Outcome.Err(invalid(BodySource.FIELD, BODY_FROM_FILE_MCP_ONLY, null))
        }
        val expected = req.expectedVersion
        if (expected != null && expected < 0) {
            return Outcome.Err(invalid("/expectedVersion", "must be 0 or greater", expected.toString()))
        }
        slugViolation(req.kind.domain, req.slug)?.let { return Outcome.Err(invalid("/slug", it, req.slug.take(MAX_ECHO))) }
        val rootId =
            when (val admitted = admitRoot(scope.stores, ctx, req.rootId)) {
                is Outcome.Err -> return admitted
                is Outcome.Ok -> admitted.value
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
            env.documentService.write(rootId, req.kind.domain, req.slug, text, req.title, expected, principal.claim.id)
        return when (written) {
            is Outcome.Err -> written
            is Outcome.Ok -> {
                val doc = written.value
                recordCallTargetVersion(doc.id, doc.version.toLong())
                Outcome.Ok(DocWriteOutcome.Upserted(doc.id, doc.kind, doc.slug, doc.version, doc.contentHash, created = doc.version == 1))
            }
        }
    }

    /** `docs_write archive` (when [archive]) or `unarchive`. */
    suspend fun setArchived(
        scope: WriteScope,
        env: ToolExecutionContext,
        ctx: CallContext,
        req: DocStateRequest,
        archive: Boolean
    ): Outcome<DocWriteOutcome> {
        val expected = req.expectedVersion
        if (expected != null && expected < 0) {
            return Outcome.Err(invalid("/expectedVersion", "must be 0 or greater", expected.toString()))
        }
        slugPresent(req.slug)?.let { return Outcome.Err(it) }
        val rootId =
            when (val admitted = admitRoot(scope.stores, ctx, req.rootId)) {
                is Outcome.Err -> return admitted
                is Outcome.Ok -> admitted.value
            }
        val result =
            if (archive) {
                env.documentService.archive(rootId, req.kind.domain, req.slug, expected)
            } else {
                env.documentService.unarchive(rootId, req.kind.domain, req.slug, expected)
            }
        return when (result) {
            is Outcome.Err -> result
            is Outcome.Ok -> {
                val doc = result.value
                recordCallTargetVersion(doc.id, doc.version.toLong())
                Outcome.Ok(DocWriteOutcome.Archived(doc.id, doc.kind, doc.slug, doc.version, archived = doc.archivedAt != null))
            }
        }
    }

    /** `docs_write delete`. */
    suspend fun delete(
        scope: WriteScope,
        env: ToolExecutionContext,
        ctx: CallContext,
        req: DocDeleteRequest
    ): Outcome<DocWriteOutcome> {
        slugPresent(req.slug)?.let { return Outcome.Err(it) }
        val rootId =
            when (val admitted = admitRoot(scope.stores, ctx, req.rootId)) {
                is Outcome.Err -> return admitted
                is Outcome.Ok -> admitted.value
            }
        val result = env.documentService.delete(rootId, req.kind.domain, req.slug)
        return when (result) {
            is Outcome.Err -> result
            is Outcome.Ok -> Outcome.Ok(DocWriteOutcome.Deleted(result.value.id, result.value.kind, result.value.slug))
        }
    }

    /** `docs_write attach`. */
    suspend fun attach(
        scope: WriteScope,
        env: ToolExecutionContext,
        ctx: CallContext,
        req: DocAttachRequest
    ): Outcome<DocWriteOutcome> {
        slugPresent(req.slug)?.let { return Outcome.Err(it) }
        val rootId =
            when (val admitted = admitRoot(scope.stores, ctx, req.rootId)) {
                is Outcome.Err -> return admitted
                is Outcome.Ok -> admitted.value
            }
        val itemId =
            when (val admitted = admitItem(scope.stores, ctx, req.itemId)) {
                is Outcome.Err -> return admitted
                is Outcome.Ok -> admitted.value
            }
        val result = env.linkService.attach(rootId, req.kind.domain, req.slug, itemId)
        return when (result) {
            is Outcome.Err -> result
            is Outcome.Ok -> Outcome.Ok(DocWriteOutcome.Attached(result.value.id, result.value.from.id, itemId))
        }
    }

    /** `docs_write detach`: not_found when the document is absent, else the number of attachments removed. */
    suspend fun detach(
        scope: WriteScope,
        env: ToolExecutionContext,
        ctx: CallContext,
        req: DocAttachRequest
    ): Outcome<DocWriteOutcome> {
        slugPresent(req.slug)?.let { return Outcome.Err(it) }
        val rootId =
            when (val admitted = admitRoot(scope.stores, ctx, req.rootId)) {
                is Outcome.Err -> return admitted
                is Outcome.Ok -> admitted.value
            }
        val itemId =
            when (val admitted = admitItem(scope.stores, ctx, req.itemId)) {
                is Outcome.Err -> return admitted
                is Outcome.Ok -> admitted.value
            }
        scope.stores.documentStore().get(rootId, req.kind.domain, req.slug) ?: return Outcome.Err(documentNotFound(req.slug))
        val removed = env.linkService.detach(rootId, req.kind.domain, req.slug, itemId)
        return Outcome.Ok(DocWriteOutcome.Detached(removed))
    }

    // ---- shared steps -------------------------------------------------------------------------------------

    /**
     * Resolves [ref] to a project root and runs [ScopeGuard] on it: `not_found` / `ambiguous_id` for an id that names no
     * single item, `forbidden` for a root outside the call's scope, `not_found` (root) for a missing one,
     * `invalid_request` for an item that is not a depth-0 root. A restricted scope never learns whether a root exists.
     */
    private suspend fun admitRoot(
        stores: RepositoryProvider,
        ctx: CallContext,
        ref: IdRef
    ): Outcome<UUID> {
        val rootId =
            when (val resolved = resolveScoped(stores, ctx, ref, "/rootId")) {
                is Outcome.Err -> return resolved
                is Outcome.Ok -> resolved.value
            }
        val workItems = stores.workItemRepository()
        when (val guard = ScopeGuard.check(ctx.scope, rootId, workItems)) {
            is Outcome.Err -> return guard
            is Outcome.Ok -> Unit
        }
        val root =
            workItems.getById(rootId)
                ?: return Outcome.Err(
                    DomainError(
                        code = ErrorCode.NOT_FOUND,
                        message = "No root matches $rootId.",
                        detail = ErrorDetail.NotFound(EntityKind.ROOT, rootId.toString()),
                        fixArgs = mapOf("kind" to "root", "id" to rootId.toString())
                    )
                )
        if (root.depth != 0) {
            return Outcome.Err(
                invalid("/rootId", "documents anchor to depth-0 project roots only (depth ${root.depth})", rootId.toString())
            )
        }
        return Outcome.Ok(rootId)
    }

    /** Resolves [ref] under the call's scope by the one rule every tool uses ([ScopedIds.resolve]). */
    private suspend fun resolveScoped(
        stores: RepositoryProvider,
        ctx: CallContext,
        ref: IdRef,
        field: String
    ): Outcome<UUID> = ScopedIds.resolve(stores.workItemRepository(), ctx.scope, ref, field)

    /** Resolves [ref] to an item the call's scope admits: `not_found` / `ambiguous_id` / `forbidden`. */
    private suspend fun admitItem(
        stores: RepositoryProvider,
        ctx: CallContext,
        ref: IdRef
    ): Outcome<UUID> {
        val itemId =
            when (val resolved = resolveScoped(stores, ctx, ref, "/itemId")) {
                is Outcome.Err -> return resolved
                is Outcome.Ok -> resolved.value
            }
        return when (val guard = ScopeGuard.check(ctx.scope, itemId, stores.workItemRepository())) {
            is Outcome.Err -> guard
            is Outcome.Ok -> Outcome.Ok(itemId)
        }
    }

    /** document id -> id of the item that adopted it, for the documents of [docIds] whose adopter the scope admits. */
    private suspend fun adopters(
        stores: RepositoryProvider,
        ctx: CallContext,
        docIds: List<UUID>
    ): Map<UUID, UUID> {
        if (docIds.isEmpty()) return emptyMap()
        val adopted =
            stores
                .linkStore()
                .findToAny(docIds.map { EntityRef(RefKind.DOCUMENT, it) }, LinkRelation.ADOPTED_BY)
                .mapNotNull { link -> link.to?.takeIf { it.kind == RefKind.ITEM }?.let { link.from.id to it.id } }
                .toMap()
        if (adopted.isEmpty()) return emptyMap()
        val admitted = ScopeGuard.admitted(ctx.scope, adopted.values.toSet(), stores.workItemRepository())
        return adopted.filterValues { it in admitted }
    }

    /**
     * The [links] whose item and note ends all exist and are admitted by the scope (document ends and dangling
     * references always stay). The adopter end of an `adopted_by` link is the one exception to existence: an adoption
     * outlives the adopting item, so that end needs only the scope's admission, the same rule [adopters] applies to
     * `adoptedBy` (unrestricted: shown even after the adopter is deleted; restricted: a missing adopter fails closed).
     * One batched item lookup; notes are read one at a time (no batched port).
     */
    private suspend fun visibleLinks(
        stores: RepositoryProvider,
        ctx: CallContext,
        links: List<Link>
    ): List<Link> {
        val ends = links.flatMap { listOfNotNull(it.from, it.to) }
        val itemIds = ends.filter { it.kind == RefKind.ITEM }.mapTo(HashSet()) { it.id }
        val noteItems = HashMap<UUID, UUID?>()
        for (noteId in ends.filter { it.kind == RefKind.NOTE }.map { it.id }.distinct()) {
            noteItems[noteId] = stores.noteRepository().getById(noteId)?.itemId
        }
        val adopterIds = links.mapNotNullTo(HashSet()) { it.adopterEnd()?.id }
        val candidates = itemIds + noteItems.values.filterNotNull()
        val existing = if (candidates.isEmpty()) emptySet() else stores.itemStore().findByIdSet(candidates).mapTo(HashSet()) { it.id }
        val admitted = ScopeGuard.admitted(ctx.scope, existing + adopterIds, stores.workItemRepository())
        val ok = { ref: EntityRef ->
            when (ref.kind) {
                RefKind.DOCUMENT -> true
                RefKind.ITEM -> ref.id in existing && ref.id in admitted
                RefKind.NOTE -> noteItems[ref.id]?.let { it in existing && it in admitted } ?: false
            }
        }
        return links.filter { link ->
            val to = link.to
            val toOk =
                when {
                    to == null -> true
                    link.adopterEnd() != null -> to.id in admitted
                    else -> ok(to)
                }
            ok(link.from) && toOk
        }
    }

    /** The item end of an `adopted_by` link, or null for any other link. */
    private fun Link.adopterEnd(): EntityRef? = to?.takeIf { relation == LinkRelation.ADOPTED_BY && it.kind == RefKind.ITEM }

    /** Null when [slug] is acceptable for an `upsert`, else the reason (the D6 guard). Reads accept any non-empty slug. */
    fun slugViolation(
        kind: DocumentKind,
        slug: String
    ): String? =
        when {
            slug.isEmpty() || slug.length > MAX_SLUG_LENGTH -> "must be 1 to $MAX_SLUG_LENGTH characters"
            slug.first().isWhitespace() || slug.last().isWhitespace() -> "must not start or end with whitespace"
            slug.any { it < ' ' || it == '\u007f' } -> "must not contain control characters"
            slug.any { it in SLUG_FORBIDDEN_CHARS } -> "must not contain [, ] or #"
            kind == DocumentKind.PLAN &&
                slug.startsWith(
                    PlanSlugs.RULE_PREFIX
                ) -> "a plan slug must not start with ${PlanSlugs.RULE_PREFIX}"
            else -> null
        }

    private fun slugPresent(slug: String): DomainError? = if (slug.isEmpty()) invalid("/slug", "must not be empty", slug) else null

    private fun view(
        doc: Document,
        adoptedBy: UUID?
    ): DocView =
        DocView(
            id = doc.id,
            rootId = doc.rootItemId,
            kind = doc.kind,
            slug = doc.slug,
            title = doc.title,
            body = doc.body,
            contentHash = doc.contentHash,
            version = doc.version,
            archivedAt = doc.archivedAt,
            adoptedBy = adoptedBy,
            createdAt = doc.createdAt,
            modifiedAt = doc.modifiedAt
        )

    private fun documentNotFound(id: String): DomainError =
        DomainError(
            code = ErrorCode.NOT_FOUND,
            message = "Document '$id' not found.",
            detail = ErrorDetail.NotFound(EntityKind.DOCUMENT, id),
            fixArgs = mapOf("kind" to "document", "id" to id)
        )

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
