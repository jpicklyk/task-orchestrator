package io.github.jpicklyk.mcptask.current.application.api.docs

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.support.IdRef
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire form of a document kind: lowercase. */
@Serializable
enum class DocKind(
    val domain: DocumentKind
) {
    @SerialName("plan")
    PLAN(DocumentKind.PLAN),

    @SerialName("rule")
    RULE(DocumentKind.RULE),

    @SerialName("page")
    PAGE(DocumentKind.PAGE)
}

/** Which links of a document `docs_read links` returns. */
@Serializable
enum class DocLinkDirection {
    @SerialName("outgoing")
    OUTGOING,

    @SerialName("incoming")
    INCOMING,

    @SerialName("all")
    ALL
}

/**
 * The one documentation text of each field that appears in more than one request class of `docs_read` or
 * `docs_write`: the same field name must generate the same schema in every select/op of a tool.
 */
object DocDocs {
    const val ROOT_ID = "Id of the depth-0 project root that owns the document: a full UUID or 8 to 32 hex characters."
    const val KIND = "Document kind: plan, rule or page."
    const val KIND_READ = "$KIND On list it is optional and filters."
    const val SLUG_READ = "Document slug, unique per (root, kind)."
    const val SLUG =
        "Document slug, unique per (root, kind). On upsert: 1 to 200 characters, no leading or trailing whitespace, " +
            "no control characters, none of [ ] #; a plan slug must not start with rule/."
    const val LIMIT = "Page size, 1 to 200 (default 50)."
    const val CURSOR = "Opaque cursor from the previous page's page.next."
    const val EXPECTED_VERSION =
        "Guard on the document's version: omit to write unconditionally, 0 to require that it does not exist, " +
            "n to require that its current version is n. Anything else is version_conflict and nothing is written."
    const val ITEM_ID = "Id of the work item: a full UUID or 8 to 32 hex characters of one. It must be under the document's root."
}

/** `docs_read get`: one document with its body, or one stored version of it. */
@Serializable
data class DocsGetRequest(
    @Doc(DocDocs.ROOT_ID) val rootId: IdRef,
    @Doc(DocDocs.KIND_READ) val kind: DocKind,
    @Doc(DocDocs.SLUG_READ) val slug: String,
    @Doc("Read this stored version (at least 1) instead of the current one. Not together with ifVersion.")
    val version: Int? = null,
    @Doc(
        "The version the caller already holds (at least 1). When it equals the current version " +
            "the answer is {unchanged: true, version} with no body."
    )
    val ifVersion: Int? = null
)

/** `docs_read list`: the documents of one root, metadata only. */
@Serializable
data class DocsListRequest(
    @Doc(DocDocs.ROOT_ID) val rootId: IdRef,
    @Doc(DocDocs.KIND_READ) val kind: DocKind? = null,
    @Doc("Include archived documents. Default false.") val includeArchived: Boolean? = null,
    @Doc(DocDocs.LIMIT) val limit: Int? = null,
    @Doc(DocDocs.CURSOR) val cursor: String? = null
)

/** `docs_read history`: the versions of one document. */
@Serializable
data class DocsHistoryRequest(
    @Doc(DocDocs.ROOT_ID) val rootId: IdRef,
    @Doc(DocDocs.KIND_READ) val kind: DocKind,
    @Doc(DocDocs.SLUG_READ) val slug: String,
    @Doc(DocDocs.LIMIT) val limit: Int? = null,
    @Doc(DocDocs.CURSOR) val cursor: String? = null
)

/** `docs_read links`: the links of one document. */
@Serializable
data class DocsLinksRequest(
    @Doc(DocDocs.ROOT_ID) val rootId: IdRef,
    @Doc(DocDocs.KIND_READ) val kind: DocKind,
    @Doc(DocDocs.SLUG_READ) val slug: String,
    @Doc("outgoing = links the document makes, incoming = links that point at it. Default all.")
    val direction: DocLinkDirection? = null,
    @Doc(DocDocs.LIMIT) val limit: Int? = null,
    @Doc(DocDocs.CURSOR) val cursor: String? = null
)

/** `docs_write upsert`: create the document or write its next version. */
@Serializable
data class DocUpsertRequest(
    @Doc(DocDocs.ROOT_ID) val rootId: IdRef,
    @Doc(DocDocs.KIND) val kind: DocKind,
    @Doc(DocDocs.SLUG) val slug: String,
    @Doc("Inline document text (may be empty). Exactly one of body and bodyFromFile is required.") val body: String? = null,
    @Doc(
        "Server-side path whose text becomes the body (MCP only). Exactly one of body and bodyFromFile is required. " +
            "Resolved strictly inside the agent config root: no absolute path, no '..', no symlink out of it; " +
            "a regular UTF-8 file of at most 65536 bytes."
    )
    val bodyFromFile: String? = null,
    @Doc("Display title. Omit to keep the current title.") val title: String? = null,
    @Doc(DocDocs.EXPECTED_VERSION) val expectedVersion: Int? = null
)

/** `docs_write archive` and `unarchive`: set or clear the archived mark. */
@Serializable
data class DocStateRequest(
    @Doc(DocDocs.ROOT_ID) val rootId: IdRef,
    @Doc(DocDocs.KIND) val kind: DocKind,
    @Doc(DocDocs.SLUG) val slug: String,
    @Doc(DocDocs.EXPECTED_VERSION) val expectedVersion: Int? = null
)

/** `docs_write delete`: remove one document and its versions. */
@Serializable
data class DocDeleteRequest(
    @Doc(DocDocs.ROOT_ID) val rootId: IdRef,
    @Doc(DocDocs.KIND) val kind: DocKind,
    @Doc(DocDocs.SLUG) val slug: String
)

/** `docs_write attach` and `detach`: link one item to a document. */
@Serializable
data class DocAttachRequest(
    @Doc(DocDocs.ROOT_ID) val rootId: IdRef,
    @Doc(DocDocs.KIND) val kind: DocKind,
    @Doc(DocDocs.SLUG) val slug: String,
    @Doc(DocDocs.ITEM_ID) val itemId: IdRef
)
