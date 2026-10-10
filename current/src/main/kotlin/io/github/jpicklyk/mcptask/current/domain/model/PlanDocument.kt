package io.github.jpicklyk.mcptask.current.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Lifecycle state of a [PlanDocument].
 *
 * A document starts [PENDING] on its first stash. Once adopted into a work item it becomes
 * [ADOPTED] permanently — the transition is one-way and enforced by the service layer
 * ([io.github.jpicklyk.mcptask.current.application.service.PlanDocumentService]), not a DB
 * constraint: a further stash to an adopted slug is rejected rather than silently overwriting it.
 *
 * Since 4.0 the status is DERIVED, not stored: a document is [ADOPTED] iff an `adopted_by` link
 * ([LinkRelation.ADOPTED_BY]) runs from it to an item, and [PENDING] otherwise.
 */
enum class PlanDocumentStatus {
    PENDING,
    ADOPTED,
    ;

    /** Lowercase wire form (`pending` / `adopted`), as reported by `manage_plan_documents` and the REST plan routes. */
    fun toDbValue(): String = name.lowercase()

    companion object {
        /** Parses the lowercase DB/wire form. Throws [IllegalArgumentException] for any other value. */
        fun fromDbValue(value: String): PlanDocumentStatus =
            when (value.lowercase()) {
                "pending" -> PENDING
                "adopted" -> ADOPTED
                else -> throw IllegalArgumentException("Unknown plan document status: $value")
            }
    }
}

/**
 * A stashed planning document scoped to a project root (a depth-0 WorkItem), identified within
 * that root by a caller-chosen [slug].
 *
 * The 3.x view of a [Document]: a `rule/<key>` slug is the document of kind [DocumentKind.RULE] and
 * slug `<key>`, every other slug the document of kind [DocumentKind.PLAN] with the same slug.
 * [status] and [adoptedByItemId] are derived from the document's `adopted_by` link.
 *
 * @property id Stable identifier for this document row.
 * @property rootItemId The depth-0 WorkItem this document is scoped to.
 * @property slug Caller-chosen identifier, unique within [rootItemId].
 * @property body The raw document text (markdown or plain text), capped at 64KB by the service layer.
 * @property contentHash SHA-256 hex digest of [body]'s UTF-8 bytes — lets both ingestion paths
 *   (REST PUT and MCP stash) verify they landed identical bytes.
 * @property status [PlanDocumentStatus.PENDING] until adopted into a work item, then
 *   [PlanDocumentStatus.ADOPTED] permanently.
 * @property adoptedByItemId The WorkItem that adopted this document (the `adopted_by` link's
 *   target), once [status] is [PlanDocumentStatus.ADOPTED]; null while pending. Since 4.0 the link
 *   is provenance and outlives its target, so this id may name a since-deleted WorkItem (3.x set it
 *   null through an FK).
 * @property createdAt When this document was first stashed.
 * @property modifiedAt When this document was last written (stash or adoption).
 */
data class PlanDocument(
    val id: UUID,
    val rootItemId: UUID,
    val slug: String,
    val body: String,
    val contentHash: String,
    val status: PlanDocumentStatus,
    val adoptedByItemId: UUID?,
    val createdAt: Instant,
    val modifiedAt: Instant
)

/**
 * Metadata-only projection of a [PlanDocument] — every field except [PlanDocument.body]. Used by
 * list endpoints (REST `GET /roots/{rootId}/plans`, MCP `manage_plan_documents` `list`) so scanning
 * a root's documents never pays for reading every document's full TEXT body.
 */
data class PlanDocumentSummary(
    val id: UUID,
    val rootItemId: UUID,
    val slug: String,
    val contentHash: String,
    val status: PlanDocumentStatus,
    val adoptedByItemId: UUID?,
    val createdAt: Instant,
    val modifiedAt: Instant
)
