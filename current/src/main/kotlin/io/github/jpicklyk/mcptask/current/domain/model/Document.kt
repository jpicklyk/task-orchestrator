package io.github.jpicklyk.mcptask.current.domain.model

import java.time.Instant
import java.util.UUID

/**
 * The closed set of document kinds stored in `documents.kind`. The set lives here rather than in a SQL CHECK so a new
 * kind never needs a table recreation.
 *
 * - [PLAN]: a planning document, adoptable into a work item (the 3.x plan documents).
 * - [RULE]: git-tracked rule text served by `query_rules` (the 3.x `rule/<key>` plan documents).
 * - [PAGE]: a free-standing knowledge page (written by later surfaces; nothing in 4.0 phase 2 creates one yet).
 */
enum class DocumentKind(
    private val dbValue: String
) {
    PLAN("plan"),
    RULE("rule"),
    PAGE("page"),
    ;

    /** Lowercase wire/DB form stored in `documents.kind`. */
    fun toDbValue(): String = dbValue

    companion object {
        /** Parses the lowercase DB/wire form. Throws [IllegalArgumentException] for any other value. */
        fun fromDbValue(value: String): DocumentKind =
            entries.firstOrNull { it.dbValue == value.lowercase() }
                ?: throw IllegalArgumentException("Unknown document kind: $value")
    }
}

/**
 * A versioned document scoped to a project root (a depth-0 WorkItem), identified within that root by
 * `(kind, slug)`.
 *
 * @property id Stable identity of the document (unchanged across versions, and preserved from 3.x plan documents).
 * @property rootItemId The depth-0 WorkItem this document is scoped to.
 * @property kind The document kind.
 * @property slug Caller-chosen identifier, unique within `(rootItemId, kind)`. A 3.x `rule/<key>` slug is stored as
 *   kind [DocumentKind.RULE] with slug `<key>`.
 * @property title Optional display title (indexed by the document FTS together with [body]).
 * @property body The CURRENT body (the latest version's body, denormalized).
 * @property contentHash SHA-256 hex digest of [body]'s UTF-8 bytes.
 * @property version The current version number; 1 for a newly created document, +1 per write.
 * @property archivedAt When the document was archived, or null while it is live.
 * @property createdAt When the document was first written.
 * @property modifiedAt When the document was last written.
 */
data class Document(
    val id: UUID,
    val rootItemId: UUID,
    val kind: DocumentKind,
    val slug: String,
    val title: String?,
    val body: String,
    val contentHash: String,
    val version: Int,
    val archivedAt: Instant?,
    val createdAt: Instant,
    val modifiedAt: Instant
)

/** Metadata-only projection of a [Document]: every field except [Document.body]. */
data class DocumentSummary(
    val id: UUID,
    val rootItemId: UUID,
    val kind: DocumentKind,
    val slug: String,
    val title: String?,
    val contentHash: String,
    val version: Int,
    val archivedAt: Instant?,
    val createdAt: Instant,
    val modifiedAt: Instant
)

/**
 * One immutable version of a document. Every write appends one, version 1 included, so the history of a document is
 * its complete list of versions.
 *
 * @property actorId The writing actor's id when the write carried one; null otherwise (and for migrated 3.x rows).
 */
data class DocumentVersion(
    val id: UUID,
    val documentId: UUID,
    val version: Int,
    val body: String,
    val contentHash: String,
    val actorId: String?,
    val createdAt: Instant
)
