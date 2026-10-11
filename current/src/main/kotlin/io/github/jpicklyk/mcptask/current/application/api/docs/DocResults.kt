package io.github.jpicklyk.mcptask.current.application.api.docs

import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.EntityRef
import io.github.jpicklyk.mcptask.current.domain.model.LinkRelation
import java.time.Instant
import java.util.UUID

/** A document as `docs_read get` shows it; [adoptedBy] is already filtered by the call's scope. */
data class DocView(
    val id: UUID,
    val rootId: UUID,
    val kind: DocumentKind,
    val slug: String,
    val title: String?,
    val body: String,
    val contentHash: String,
    val version: Int,
    val archivedAt: Instant?,
    val adoptedBy: UUID?,
    val createdAt: Instant,
    val modifiedAt: Instant
)

/** One stored version as `docs_read get` with `version` shows it; [actor] is null when attribution is hidden. */
data class DocVersionView(
    val kind: DocumentKind,
    val slug: String,
    val version: Int,
    val body: String,
    val contentHash: String,
    val createdAt: Instant,
    val actor: String?
)

/** What `docs_read get` answers. */
sealed interface DocGetResult {
    data class Found(
        val doc: DocView
    ) : DocGetResult

    data class Version(
        val version: DocVersionView
    ) : DocGetResult

    data class Unchanged(
        val version: Int
    ) : DocGetResult
}

/** A document as `docs_read list` shows it: no body. */
data class DocListEntry(
    val id: UUID,
    val kind: DocumentKind,
    val slug: String,
    val title: String?,
    val contentHash: String,
    val version: Int,
    val archivedAt: Instant?,
    val adoptedBy: UUID?,
    val createdAt: Instant,
    val modifiedAt: Instant
)

/** One row of `docs_read history`. */
data class DocHistoryEntry(
    val version: Int,
    val contentHash: String,
    val bodyLength: Int,
    val createdAt: Instant,
    val actor: String?
)

/** One row of `docs_read links`; exactly one of [to] and [toRef] is normally set. */
data class DocLinkEntry(
    val id: UUID,
    val relation: LinkRelation,
    val from: EntityRef,
    val to: EntityRef?,
    val toRef: String?,
    val anchor: String?,
    val createdAt: Instant
)

/** What one `docs_write` op answered. */
sealed interface DocWriteOutcome {
    data class Upserted(
        val id: UUID,
        val kind: DocumentKind,
        val slug: String,
        val version: Int,
        val contentHash: String,
        val created: Boolean
    ) : DocWriteOutcome

    data class Archived(
        val id: UUID,
        val kind: DocumentKind,
        val slug: String,
        val version: Int,
        val archived: Boolean
    ) : DocWriteOutcome

    data class Deleted(
        val id: UUID,
        val kind: DocumentKind,
        val slug: String
    ) : DocWriteOutcome

    data class Attached(
        val linkId: UUID,
        val documentId: UUID,
        val itemId: UUID
    ) : DocWriteOutcome

    data class Detached(
        val detached: Int
    ) : DocWriteOutcome
}
