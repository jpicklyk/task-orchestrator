package io.github.jpicklyk.mcptask.current.parity

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.service.NoteUpsertCommand
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Document
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.EntityRef
import io.github.jpicklyk.mcptask.current.domain.model.RefKind
import java.security.MessageDigest
import java.util.UUID

/** What a docs parity scenario needs to exist beforehand, besides the work-item tree. */
internal typealias DocSeed = suspend DocSeeder.() -> Unit

/**
 * Seeds documents, notes and links into one leg through the composition's own services (the stock `ParitySeeder` seeds work
 * items only and the shared harness is not edited by this item). The ids those services generate differ between the two legs, so
 * every id created here is remembered in creation order ([generatedIds]); the runner maps the n-th id of each leg to one token.
 */
internal class DocSeeder(
    private val ctx: ToolExecutionContext,
    private val raw: RepositoryProvider
) {
    private val sources = mutableListOf<EntityRef>()
    private val ids = mutableListOf<UUID>()

    /** Every generated id (documents and notes in creation order, then links), identical in role on both legs. Complete after [finish]. */
    val generatedIds: List<UUID> get() = ids.toList()

    private fun <T> Outcome<T>.value(): T =
        when (this) {
            is Outcome.Ok -> value
            is Outcome.Err -> error("seeding failed: ${error.code} ${error.message}")
        }

    private fun remember(
        id: UUID,
        ref: EntityRef
    ) {
        if (id !in ids) ids += id
        if (ref !in sources) sources += ref
    }

    suspend fun doc(
        root: UUID,
        kind: DocumentKind,
        slug: String,
        body: String,
        actorId: String? = null
    ): Document {
        val written = ctx.documentService.write(root, kind, slug, body, actorId = actorId).value()
        remember(written.id, EntityRef(RefKind.DOCUMENT, written.id))
        return written
    }

    suspend fun archive(
        root: UUID,
        kind: DocumentKind,
        slug: String
    ) {
        ctx.documentService.archive(root, kind, slug).value()
    }

    suspend fun adopt(
        root: UUID,
        slug: String,
        adopter: UUID
    ) {
        ctx.documentService.adopt(root, slug, adopter).value()
    }

    suspend fun note(
        itemId: UUID,
        key: String,
        body: String
    ): UUID {
        val id =
            ctx.noteCommandService
                .upsert(NoteUpsertCommand(itemId, key, "work", body, null, null, caller = null))
                .value()
                .note.id
        remember(id, EntityRef(RefKind.NOTE, id))
        return id
    }

    suspend fun attach(
        root: UUID,
        kind: DocumentKind,
        slug: String,
        item: UUID
    ) {
        ctx.linkService.attach(root, kind, slug, item).value()
    }

    /** Deletes a work item through the undecorated repository; links that point from its notes stay behind as orphans. */
    suspend fun deleteItem(id: UUID) {
        raw.workItemRepository().delete(id)
    }

    /** Collects the ids of the links the seeding produced, ordered by their content (never by their random ids). */
    suspend fun finish() {
        for (ref in sources.toList()) {
            val found =
                raw
                    .linkStore()
                    .findFrom(ref)
                    .sortedWith(
                        compareBy({ it.relation.name }, { it.toRef ?: "" }, { it.anchor ?: "" }, { it.to?.toString() ?: "" })
                    )
            for (link in found) if (link.id !in ids) ids += link.id
        }
    }
}

/** One docs parity scenario plus the documents it needs seeded beforehand. */
internal data class DocsParityCase(
    val scenario: ParityScenario,
    val docs: DocSeed
)

/**
 * The parity catalog of `docs_read` (get, list, history, links) and `docs_write` (upsert, archive, unarchive, delete, attach,
 * detach), item 8e99f1e2 (Q25). Scenario ids S1..S22 refer to the frozen test-plan; names without an S-id are probes.
 *
 * Seeded items (no event rows): R1 "Alpha root" (tag alpha) with children C1 "Alpha child" (tag alpha), P1 "Plain child" (no
 * tag) and T1 "Temp child" (tag alpha); R2 "Beta root" (tag beta) with child X2 (tag beta). Root-scoped tokens are limited to R1
 * and tag-scoped tokens to `alpha`, so R2 is the out-of-scope root and P1 the in-root but not admitted item. Documents are
 * seeded through the services (see [DocSeeder]).
 *
 * Oracles: the response-envelope spec for statuses and shapes (200 ok, 207 partial_failure, 400 invalid_request /
 * unknown_parameter / invalid_cursor, 403 forbidden, 404 not_found), the `DocumentService` KDoc (SHA-256 of the LF-normalized
 * UTF-8 body, CRLF to LF and nothing else, created iff version 1, REASON_ARCHIVED / REASON_ADOPTED, size caps 65536 / 16384),
 * and the frozen task-scope decisions D1-D9 (kind required, WRITE_DOCS for every write op (WRITE_CONFIG before R10), slug guard on upsert only, scope
 * checked on the root and on secondary ends, a forbidden call writes nothing).
 */
internal object DocsParityScenarios {
    val r1: UUID = UUID.fromString("a0000001-0000-4000-8000-000000000001")
    val c1: UUID = UUID.fromString("a0000002-0000-4000-8000-000000000002")
    val p1: UUID = UUID.fromString("a0000003-0000-4000-8000-000000000003")
    val t1: UUID = UUID.fromString("a0000004-0000-4000-8000-000000000004")
    val r2: UUID = UUID.fromString("b0000001-0000-4000-8000-000000000001")
    val x2: UUID = UUID.fromString("b0000002-0000-4000-8000-000000000002")

    /** A well-formed item id no item carries. */
    val ghost: UUID = UUID.fromString("99000000-0000-4000-8000-0000000000ff")

    val admin = ParityToken("docs-admin", setOf(ApiCapability.ADMIN))
    val reader = ParityToken("docs-reader", setOf(ApiCapability.READ))
    val writer = ParityToken("docs-writer", setOf(ApiCapability.READ, ApiCapability.WRITE_DOCS))
    val notesOnly = ParityToken("docs-notes-only", setOf(ApiCapability.READ, ApiCapability.WRITE_NOTES, ApiCapability.WRITE_ITEMS))
    val rootReader = ParityToken("docs-root-reader", setOf(ApiCapability.READ), rootIds = setOf(r1))
    val rootWriter = ParityToken("docs-root-writer", setOf(ApiCapability.READ, ApiCapability.WRITE_DOCS), rootIds = setOf(r1))
    val tagReader = ParityToken("docs-tag-reader", setOf(ApiCapability.READ), tagsInclude = setOf("alpha"))
    val tagWriter = ParityToken("docs-tag-writer", setOf(ApiCapability.READ, ApiCapability.WRITE_DOCS), tagsInclude = setOf("alpha"))

    const val READ_TOOL = "docs_read"
    const val WRITE_TOOL = "docs_write"

    /** SHA-256 hex (lowercase) of [text]'s UTF-8 bytes, computed here independently (NIST FIPS 180-4). */
    fun hashOf(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    val tree: suspend ParitySeeder.() -> Unit =
        {
            item(r1, "Alpha root", tags = "alpha")
            item(c1, "Alpha child", parent = r1, tags = "alpha")
            item(p1, "Plain child", parent = r1)
            item(t1, "Temp child", parent = r1, tags = "alpha")
            item(r2, "Beta root", tags = "beta")
            item(x2, "Beta child", parent = r2, tags = "beta")
        }

    // ------------------------------------------------------------------ seeds

    /** R1: page alpha-page, plan alpha-plan v1; R2: plan beta-plan. */
    val baseDocs: DocSeed =
        {
            doc(r1, DocumentKind.PAGE, "alpha-page", "alpha page body")
            doc(r1, DocumentKind.PLAN, "alpha-plan", "alpha plan v1")
            doc(r2, DocumentKind.PLAN, "beta-plan", "beta secret body")
        }

    /** baseDocs, with alpha-plan at version 2. */
    private val twoVersions: DocSeed =
        {
            baseDocs(this)
            doc(r1, DocumentKind.PLAN, "alpha-plan", "alpha plan v2")
        }

    /** One outgoing resolved link of alpha-plan (to alpha-page) and one incoming note link on it (note on C1). */
    val linkDocs: DocSeed =
        {
            doc(r1, DocumentKind.PAGE, "alpha-page", "alpha page body")
            doc(r1, DocumentKind.PLAN, "alpha-plan", "see [[page:alpha-page]]")
            doc(r2, DocumentKind.PLAN, "beta-plan", "beta secret body")
            note(c1, "k1", "see [[plan:alpha-plan]]")
        }

    /** Exactly one link (alpha-plan to alpha-page): a body that lists several links of one instant is not comparable across legs. */
    private val outgoingOnly: DocSeed =
        {
            doc(r1, DocumentKind.PAGE, "alpha-page", "alpha page body")
            doc(r1, DocumentKind.PLAN, "alpha-plan", "see [[page:alpha-page]]")
        }

    /** linkDocs plus a note on T1 that references alpha-plan, whose item is then deleted (the link outlives it). */
    private val linkDocsWithOrphan: DocSeed =
        {
            linkDocs(this)
            note(t1, "k3", "orphan [[plan:alpha-plan]]")
            deleteItem(t1)
        }

    private val archivedPage: DocSeed =
        {
            baseDocs(this)
            archive(r1, DocumentKind.PAGE, "alpha-page")
        }

    private val adoptedByC1: DocSeed =
        {
            baseDocs(this)
            adopt(r1, "alpha-plan", c1)
        }

    private val adoptedByP1: DocSeed =
        {
            baseDocs(this)
            adopt(r1, "alpha-plan", p1)
        }

    private val attachedToC1: DocSeed =
        {
            baseDocs(this)
            attach(r1, DocumentKind.PLAN, "alpha-plan", c1)
        }

    private val attachedToP1: DocSeed =
        {
            baseDocs(this)
            attach(r1, DocumentKind.PLAN, "alpha-plan", p1)
        }

    private val actorWritten: DocSeed =
        {
            doc(r1, DocumentKind.PLAN, "alpha-plan", "alpha plan v1", actorId = "seed-agent-77")
            doc(r1, DocumentKind.PLAN, "alpha-plan", "alpha plan v2", actorId = "seed-agent-77")
        }

    private val migratedSlug: DocSeed =
        {
            doc(r1, DocumentKind.PLAN, "migrated plan with spaces", "old 3.x slug")
        }

    private val ruleDoc: DocSeed =
        {
            doc(r1, DocumentKind.RULE, "alpha-rule", "alpha rule body")
        }

    // ------------------------------------------------------------------ argument builders

    fun get(
        root: Any,
        kind: String,
        slug: String,
        extra: String = ""
    ) = """{"select":"get","rootId":"$root","kind":"$kind","slug":"$slug"$extra}"""

    fun list(
        root: Any,
        extra: String = ""
    ) = """{"select":"list","rootId":"$root"$extra}"""

    fun history(
        root: Any,
        kind: String,
        slug: String,
        extra: String = ""
    ) = """{"select":"history","rootId":"$root","kind":"$kind","slug":"$slug"$extra}"""

    fun links(
        root: Any,
        kind: String,
        slug: String,
        extra: String = ""
    ) = """{"select":"links","rootId":"$root","kind":"$kind","slug":"$slug"$extra}"""

    fun op(
        name: String,
        root: Any,
        kind: String,
        slug: String,
        extra: String = ""
    ) = """{"op":"$name","rootId":"$root","kind":"$kind","slug":"$slug"$extra}"""

    fun upsert(
        root: Any,
        kind: String,
        slug: String,
        body: String,
        extra: String = ""
    ) = op("upsert", root, kind, slug, ""","body":"$body"$extra""")

    fun attach(
        name: String,
        root: Any,
        kind: String,
        slug: String,
        item: Any
    ) = op(name, root, kind, slug, ""","itemId":"$item"""")

    fun ops(
        vararg elements: String,
        top: String = ""
    ) = """{"ops":[${elements.joinToString(",")}]$top}"""

    private fun case(
        name: String,
        tool: String,
        member: String,
        kind: ParityKind,
        token: ParityToken,
        arguments: String,
        status: Int,
        code: String? = null,
        contains: List<String> = emptyList(),
        absent: List<String> = emptyList(),
        dbChanged: Boolean? = null,
        docs: DocSeed = baseDocs
    ) = DocsParityCase(
        ParityScenario(name, tool, member, kind, token, arguments, status, code, contains, absent, dbChanged, tree),
        docs
    )

    private val happyKind = ParityKind.HAPPY
    private val scopeKind = ParityKind.SCOPE_FORBIDDEN
    private val otherKind = ParityKind.OTHER

    private fun scopeRoot() = listOf("\"scope\":\"root\"", "\"required\":\"$r1\"")

    private fun scopeTag() = listOf("\"scope\":\"tag\"", "\"required\":\"alpha\"")

    private fun capability() = listOf("\"scope\":\"capability\"", "\"required\":\"write-docs\"")

    private val partial = "partial_failure"

    // ------------------------------------------------------------------ HAPPY

    val happy: List<DocsParityCase> =
        listOf(
            // S1 oracle: DS KDoc (the hash is SHA-256 of the LF-normalized body), created iff version 1
            case(
                "S1 docs_read get happy",
                READ_TOOL,
                "get",
                happyKind,
                reader,
                get(r1, "plan", "alpha-plan"),
                200,
                contains =
                    listOf(
                        "\"rootId\":\"$r1\"",
                        "\"kind\":\"plan\"",
                        "\"slug\":\"alpha-plan\"",
                        "\"version\":1",
                        "\"body\":\"alpha plan v1\"",
                        "\"contentHash\":\"${hashOf("alpha plan v1")}\""
                    ),
                absent = listOf("unchanged", "beta secret body"),
                dbChanged = false
            ),
            // S4 oracle: task-scope list (metadata only, archived hidden by default, every document of the root)
            case(
                "S4 docs_read list happy",
                READ_TOOL,
                "list",
                happyKind,
                reader,
                list(r1),
                200,
                contains = listOf("\"slug\":\"alpha-plan\"", "\"slug\":\"alpha-page\"", "\"total\":2", "\"version\":1"),
                absent = listOf("beta-plan", "alpha plan v1", "alpha page body", "\"body\""),
                dbChanged = false
            ),
            // S2 oracle: task-scope history (every version, oldest first, bodyLength not body)
            case(
                "S2 docs_read history happy",
                READ_TOOL,
                "history",
                happyKind,
                reader,
                history(r1, "plan", "alpha-plan"),
                200,
                contains = listOf("\"version\":1", "\"version\":2", "\"bodyLength\":13", "\"total\":2"),
                absent = listOf("alpha plan v1", "alpha plan v2", "\"body\""),
                dbChanged = false,
                docs = twoVersions
            ),
            // S8 oracle: task-scope links (outgoing = what the document links to, incoming = what points at it, default all)
            case(
                "S8 docs_read links happy",
                READ_TOOL,
                "links",
                happyKind,
                reader,
                links(r1, "plan", "alpha-plan"),
                200,
                contains = listOf("\"total\":1", "\"relation\":\"references\""),
                dbChanged = false,
                docs = outgoingOnly
            ),
            // S1: CRLF becomes LF before hashing; expectedVersion 0 means the document must not exist yet
            case(
                "S1 docs_write upsert happy",
                WRITE_TOOL,
                "upsert",
                happyKind,
                writer,
                ops(upsert(r1, "plan", "new-plan", """line1\r\nline2""", ""","expectedVersion":0""")),
                200,
                contains =
                    listOf(
                        "\"slug\":\"new-plan\"",
                        "\"kind\":\"plan\"",
                        "\"version\":1",
                        "\"created\":true",
                        "\"contentHash\":\"${hashOf("line1\nline2")}\""
                    ),
                absent = listOf(hashOf("line1\r\nline2")),
                dbChanged = true
            ),
            case(
                "S5 docs_write archive happy",
                WRITE_TOOL,
                "archive",
                happyKind,
                writer,
                ops(op("archive", r1, "plan", "alpha-plan")),
                200,
                contains = listOf("\"slug\":\"alpha-plan\"", "\"archived\":true"),
                dbChanged = true
            ),
            case(
                "S5 docs_write unarchive happy",
                WRITE_TOOL,
                "unarchive",
                happyKind,
                writer,
                ops(op("unarchive", r1, "page", "alpha-page")),
                200,
                contains = listOf("\"slug\":\"alpha-page\"", "\"archived\":false"),
                dbChanged = true,
                docs = archivedPage
            ),
            case(
                "S6 docs_write delete happy",
                WRITE_TOOL,
                "delete",
                happyKind,
                writer,
                ops(op("delete", r1, "page", "alpha-page")),
                200,
                contains = listOf("\"slug\":\"alpha-page\"", "\"deleted\":true"),
                dbChanged = true
            ),
            case(
                "S7 docs_write attach happy",
                WRITE_TOOL,
                "attach",
                happyKind,
                writer,
                ops(attach("attach", r1, "plan", "alpha-plan", c1)),
                200,
                contains = listOf("\"itemId\":\"$c1\"", "\"linkId\":", "\"documentId\":"),
                dbChanged = true
            ),
            case(
                "S7 docs_write detach happy",
                WRITE_TOOL,
                "detach",
                happyKind,
                writer,
                ops(attach("detach", r1, "plan", "alpha-plan", c1)),
                200,
                contains = listOf("\"detached\":1"),
                dbChanged = true,
                docs = attachedToC1
            )
        )

    // ------------------------------------------------------------------ SCOPE_FORBIDDEN (S15, S18)

    val scopeForbidden: List<DocsParityCase> =
        listOf(
            case(
                "S15 docs_read get scope root",
                READ_TOOL,
                "get",
                scopeKind,
                rootReader,
                get(r2, "plan", "beta-plan"),
                403,
                code = "forbidden",
                contains = scopeRoot(),
                absent = listOf("beta secret body"),
                dbChanged = false
            ),
            case(
                "S15 docs_read get scope tag",
                READ_TOOL,
                "get",
                scopeKind,
                tagReader,
                get(r2, "plan", "beta-plan"),
                403,
                code = "forbidden",
                contains = scopeTag(),
                absent = listOf("beta secret body"),
                dbChanged = false
            ),
            case(
                "S15 docs_read list scope root",
                READ_TOOL,
                "list",
                scopeKind,
                rootReader,
                list(r2),
                403,
                code = "forbidden",
                contains = scopeRoot(),
                absent = listOf("beta-plan"),
                dbChanged = false
            ),
            case(
                "S15 docs_read history scope root",
                READ_TOOL,
                "history",
                scopeKind,
                rootReader,
                history(r2, "plan", "beta-plan"),
                403,
                code = "forbidden",
                contains = scopeRoot(),
                dbChanged = false
            ),
            case(
                "S15 docs_read links scope root",
                READ_TOOL,
                "links",
                scopeKind,
                rootReader,
                links(r2, "plan", "beta-plan"),
                403,
                code = "forbidden",
                contains = scopeRoot(),
                dbChanged = false
            ),
            case(
                "S15 docs_write upsert scope root",
                WRITE_TOOL,
                "upsert",
                scopeKind,
                rootWriter,
                ops(upsert(r2, "plan", "beta-plan", "hijacked")),
                207,
                code = partial,
                contains = listOf("\"code\":\"forbidden\"") + scopeRoot(),
                dbChanged = false
            ),
            case(
                "S15 docs_write archive scope root",
                WRITE_TOOL,
                "archive",
                scopeKind,
                rootWriter,
                ops(op("archive", r2, "plan", "beta-plan")),
                207,
                code = partial,
                contains = listOf("\"code\":\"forbidden\"") + scopeRoot(),
                dbChanged = false
            ),
            case(
                "S15 docs_write unarchive scope root",
                WRITE_TOOL,
                "unarchive",
                scopeKind,
                rootWriter,
                ops(op("unarchive", r2, "plan", "beta-plan")),
                207,
                code = partial,
                contains = listOf("\"code\":\"forbidden\"") + scopeRoot(),
                dbChanged = false
            ),
            case(
                "S15 docs_write delete scope root",
                WRITE_TOOL,
                "delete",
                scopeKind,
                rootWriter,
                ops(op("delete", r2, "plan", "beta-plan")),
                207,
                code = partial,
                contains = listOf("\"code\":\"forbidden\"") + scopeRoot(),
                dbChanged = false
            ),
            case(
                "S15 docs_write attach scope root",
                WRITE_TOOL,
                "attach",
                scopeKind,
                rootWriter,
                ops(attach("attach", r2, "plan", "beta-plan", x2)),
                207,
                code = partial,
                contains = listOf("\"code\":\"forbidden\"") + scopeRoot(),
                dbChanged = false
            ),
            case(
                "S15 docs_write detach scope root",
                WRITE_TOOL,
                "detach",
                scopeKind,
                rootWriter,
                ops(attach("detach", r2, "plan", "beta-plan", x2)),
                207,
                code = partial,
                contains = listOf("\"code\":\"forbidden\"") + scopeRoot(),
                dbChanged = false
            ),
            // S18 / D7: the root is admitted, the named item is not, so nothing is written
            case(
                "S18 attach of an item that is not admitted is forbidden",
                WRITE_TOOL,
                "attach",
                scopeKind,
                tagWriter,
                ops(attach("attach", r1, "plan", "alpha-plan", p1)),
                207,
                code = partial,
                contains = listOf("\"code\":\"forbidden\"") + scopeTag(),
                dbChanged = false
            ),
            case(
                "S18 control - attach of an admitted item by the same token succeeds",
                WRITE_TOOL,
                "attach",
                otherKind,
                tagWriter,
                ops(attach("attach", r1, "plan", "alpha-plan", c1)),
                200,
                contains = listOf("\"itemId\":\"$c1\"", "\"linkId\":"),
                dbChanged = true
            ),
            case(
                "S18 detach of an item that is not admitted is forbidden and keeps the attachment",
                WRITE_TOOL,
                "detach",
                scopeKind,
                tagWriter,
                ops(attach("detach", r1, "plan", "alpha-plan", p1)),
                207,
                code = partial,
                contains = listOf("\"code\":\"forbidden\"") + scopeTag(),
                dbChanged = false,
                docs = attachedToP1
            )
        )

    // ------------------------------------------------------------------ S17 slug guard (D6)

    private val longSlug = "s".repeat(200)
    private val tooLongSlug = "s".repeat(201)

    /** (name, JSON-escaped slug, kind): slugs the upsert guard must reject. */
    private val rejectedSlugs =
        listOf(
            Triple("empty", "", "plan"),
            Triple("leading space", " a", "plan"),
            Triple("trailing space", "a ", "plan"),
            Triple("bell control character", """a\u0007""", "plan"),
            Triple("delete control character", """a\u007f""", "plan"),
            Triple("newline", """a\nb""", "plan"),
            Triple("left bracket", "a[b", "plan"),
            Triple("right bracket", "a]b", "plan"),
            Triple("hash", "a#b", "plan"),
            Triple("201 characters", tooLongSlug, "plan"),
            Triple("plan with rule prefix", "rule/x", "plan"),
            Triple("rule kind with bracket", "r[x", "rule"),
            Triple("page kind with hash", "p#x", "page")
        )

    /** (name, JSON-escaped slug, kind): slugs the upsert guard must accept. */
    private val acceptedSlugs =
        listOf(
            Triple("200 characters", longSlug, "plan"),
            Triple("rule kind rule slash x", "rule/x", "rule"),
            Triple("page kind rule slash x", "rule/x", "page"),
            Triple("single character", "x", "plan"),
            Triple("inner space", "a b", "plan"),
            Triple("slash inside a plan slug", "a/b", "plan"),
            Triple("unicode", """plan-ü-日本""", "plan")
        )

    val slugGuard: List<DocsParityCase> =
        rejectedSlugs.map { (name, slug, kind) ->
            case(
                "S17 upsert rejects slug: $name ($kind)",
                WRITE_TOOL,
                "upsert",
                otherKind,
                writer,
                ops(upsert(r1, kind, slug, "body")),
                207,
                code = partial,
                contains = listOf("\"code\":\"invalid_request\"", "slug"),
                dbChanged = false
            )
        } +
            acceptedSlugs.map { (name, slug, kind) ->
                case(
                    "S17 upsert accepts slug: $name ($kind)",
                    WRITE_TOOL,
                    "upsert",
                    otherKind,
                    writer,
                    ops(upsert(r1, kind, slug, "body")),
                    200,
                    contains = listOf("\"created\":true", "\"version\":1"),
                    dbChanged = true
                )
            } +
            listOf(
                // D6: get, archive and delete accept any non-empty slug, so a migrated 3.x slug stays reachable
                case(
                    "S17 get on a migrated slug with spaces works",
                    READ_TOOL,
                    "get",
                    otherKind,
                    reader,
                    get(r1, "plan", "migrated plan with spaces"),
                    200,
                    contains = listOf("\"slug\":\"migrated plan with spaces\"", "\"body\":\"old 3.x slug\""),
                    dbChanged = false,
                    docs = migratedSlug
                ),
                case(
                    "S17 archive on a migrated slug with spaces works",
                    WRITE_TOOL,
                    "archive",
                    otherKind,
                    writer,
                    ops(op("archive", r1, "plan", "migrated plan with spaces")),
                    200,
                    contains = listOf("\"archived\":true"),
                    dbChanged = true,
                    docs = migratedSlug
                ),
                case(
                    "S17 delete on a migrated slug with spaces works",
                    WRITE_TOOL,
                    "delete",
                    otherKind,
                    writer,
                    ops(op("delete", r1, "plan", "migrated plan with spaces")),
                    200,
                    contains = listOf("\"deleted\":true"),
                    dbChanged = true,
                    docs = migratedSlug
                )
            )

    // ------------------------------------------------------------------ the other outcomes

    val other: List<DocsParityCase> =
        listOf(
            // S2 / S10: expectedVersion n requires the current version to be n
            case(
                "S2 re-upsert with the current expectedVersion makes version 2",
                WRITE_TOOL,
                "upsert",
                otherKind,
                writer,
                ops(upsert(r1, "plan", "alpha-plan", "alpha plan v2", ""","expectedVersion":1""")),
                200,
                contains = listOf("\"version\":2", "\"created\":false", "\"contentHash\":\"${hashOf("alpha plan v2")}\""),
                dbChanged = true
            ),
            case(
                "S2 unconditional re-upsert (no expectedVersion) makes version 2",
                WRITE_TOOL,
                "upsert",
                otherKind,
                writer,
                ops(upsert(r1, "plan", "alpha-plan", "alpha plan v2")),
                200,
                contains = listOf("\"version\":2", "\"created\":false"),
                dbChanged = true
            ),
            case(
                "S2 get version 1 of a document at version 2 returns the old body",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                get(r1, "plan", "alpha-plan", ""","version":1"""),
                200,
                contains = listOf("\"version\":1", "\"body\":\"alpha plan v1\"", "\"contentHash\":\"${hashOf("alpha plan v1")}\""),
                absent = listOf("alpha plan v2"),
                dbChanged = false,
                docs = twoVersions
            ),
            case(
                "S2 get without a version returns the current one",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                get(r1, "plan", "alpha-plan"),
                200,
                contains = listOf("\"version\":2", "\"body\":\"alpha plan v2\""),
                absent = listOf("alpha plan v1"),
                dbChanged = false,
                docs = twoVersions
            ),
            case(
                "S10 stale expectedVersion is version_conflict and writes nothing",
                WRITE_TOOL,
                "upsert",
                otherKind,
                writer,
                ops(upsert(r1, "plan", "alpha-plan", "stale", ""","expectedVersion":7""")),
                207,
                code = partial,
                contains = listOf("\"code\":\"version_conflict\"", "\"expected\":7", "\"actual\":1"),
                dbChanged = false
            ),
            case(
                "S10 expectedVersion 0 on an existing document is version_conflict",
                WRITE_TOOL,
                "upsert",
                otherKind,
                writer,
                ops(upsert(r1, "plan", "alpha-plan", "stale", ""","expectedVersion":0""")),
                207,
                code = partial,
                contains = listOf("\"code\":\"version_conflict\"", "\"expected\":0", "\"actual\":1"),
                dbChanged = false
            ),
            case(
                "S10 expectedVersion 1 on a missing document is version_conflict",
                WRITE_TOOL,
                "upsert",
                otherKind,
                writer,
                ops(upsert(r1, "plan", "no-such-plan", "stale", ""","expectedVersion":1""")),
                207,
                code = partial,
                contains = listOf("\"code\":\"version_conflict\""),
                dbChanged = false
            ),
            case(
                "S10 archive with a stale expectedVersion is version_conflict",
                WRITE_TOOL,
                "archive",
                otherKind,
                writer,
                ops(op("archive", r1, "plan", "alpha-plan", ""","expectedVersion":3""")),
                207,
                code = partial,
                contains = listOf("\"code\":\"version_conflict\""),
                dbChanged = false
            ),
            case(
                "S10 unarchive with a stale expectedVersion is version_conflict",
                WRITE_TOOL,
                "unarchive",
                otherKind,
                writer,
                ops(op("unarchive", r1, "page", "alpha-page", ""","expectedVersion":3""")),
                207,
                code = partial,
                contains = listOf("\"code\":\"version_conflict\""),
                dbChanged = false,
                docs = archivedPage
            ),
            // S3
            case(
                "S3 get with ifVersion equal to the current version is unchanged and carries no body",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                get(r1, "plan", "alpha-plan", ""","ifVersion":1"""),
                200,
                contains = listOf("\"unchanged\":true", "\"version\":1"),
                absent = listOf("alpha plan v1", "\"body\"", "contentHash"),
                dbChanged = false
            ),
            case(
                "S3 control - get with an older ifVersion returns the document",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                get(r1, "plan", "alpha-plan", ""","ifVersion":1"""),
                200,
                contains = listOf("\"version\":2", "\"body\":\"alpha plan v2\""),
                absent = listOf("unchanged"),
                dbChanged = false,
                docs = twoVersions
            ),
            // S20
            case(
                "S20 get with both version and ifVersion is invalid_request",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                get(r1, "plan", "alpha-plan", ""","version":1,"ifVersion":1"""),
                400,
                code = "invalid_request"
            ),
            case(
                "S20 get with a version above the current one is not_found",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                get(r1, "plan", "alpha-plan", ""","version":3"""),
                404,
                code = "not_found",
                dbChanged = false,
                docs = twoVersions
            ),
            case(
                "S20 get with version 0 is invalid_request",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                get(r1, "plan", "alpha-plan", ""","version":0"""),
                400,
                code = "invalid_request"
            ),
            case(
                "S20 get with ifVersion 0 is invalid_request",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                get(r1, "plan", "alpha-plan", ""","ifVersion":0"""),
                400,
                code = "invalid_request"
            ),
            case(
                "get of a missing slug is not_found",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                get(r1, "plan", "no-such-plan"),
                404,
                code = "not_found",
                dbChanged = false
            ),
            case(
                "get with the wrong kind is not_found because kind is part of the key",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                get(r1, "rule", "alpha-plan"),
                404,
                code = "not_found",
                dbChanged = false
            ),
            case(
                "history of a missing slug is not_found",
                READ_TOOL,
                "history",
                otherKind,
                reader,
                history(r1, "plan", "no-such-plan"),
                404,
                code = "not_found"
            ),
            case(
                "links of a missing slug is not_found",
                READ_TOOL,
                "links",
                otherKind,
                reader,
                links(r1, "plan", "no-such-plan"),
                404,
                code = "not_found"
            ),
            // S4 list
            case(
                "S4 list hides archived documents by default",
                READ_TOOL,
                "list",
                otherKind,
                reader,
                list(r1),
                200,
                contains = listOf("\"slug\":\"alpha-plan\"", "\"total\":1"),
                absent = listOf("alpha-page"),
                dbChanged = false,
                docs = archivedPage
            ),
            case(
                "S4 list with includeArchived shows them",
                READ_TOOL,
                "list",
                otherKind,
                reader,
                list(r1, ""","includeArchived":true"""),
                200,
                contains = listOf("\"slug\":\"alpha-plan\"", "\"slug\":\"alpha-page\"", "\"total\":2", "\"archivedAt\":"),
                dbChanged = false,
                docs = archivedPage
            ),
            case(
                "S4 list with a kind filter returns only that kind",
                READ_TOOL,
                "list",
                otherKind,
                reader,
                list(r1, ""","kind":"page""""),
                200,
                contains = listOf("\"slug\":\"alpha-page\"", "\"total\":1"),
                absent = listOf("alpha-plan"),
                dbChanged = false
            ),
            case(
                "S4 list reports adoptedBy for an adopted plan",
                READ_TOOL,
                "list",
                otherKind,
                reader,
                list(r1, ""","kind":"plan""""),
                200,
                contains = listOf("\"slug\":\"alpha-plan\"", "\"adoptedBy\":\"$c1\""),
                dbChanged = false,
                docs = adoptedByC1
            ),
            case(
                "S4 get reports adoptedBy for an adopted plan",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                get(r1, "plan", "alpha-plan"),
                200,
                contains = listOf("\"adoptedBy\":\"$c1\""),
                dbChanged = false,
                docs = adoptedByC1
            ),
            case(
                "S4 an unadopted plan has no adoptedBy",
                READ_TOOL,
                "list",
                otherKind,
                reader,
                list(r1),
                200,
                contains = listOf("\"slug\":\"alpha-plan\""),
                absent = listOf("adoptedBy"),
                dbChanged = false
            ),
            // S18 / D7: an adopter that the token does not admit is omitted
            case(
                "S18 control - adoptedBy of an untagged adopter is shown to an unrestricted reader",
                READ_TOOL,
                "list",
                otherKind,
                reader,
                list(r1, ""","kind":"plan""""),
                200,
                contains = listOf("\"adoptedBy\":\"$p1\""),
                dbChanged = false,
                docs = adoptedByP1
            ),
            case(
                "S18 adoptedBy of an adopter that is not admitted is omitted on list",
                READ_TOOL,
                "list",
                otherKind,
                tagReader,
                list(r1, ""","kind":"plan""""),
                200,
                contains = listOf("\"slug\":\"alpha-plan\""),
                absent = listOf("adoptedBy", "$p1"),
                dbChanged = false,
                docs = adoptedByP1
            ),
            case(
                "S18 adoptedBy of an adopter that is not admitted is omitted on get",
                READ_TOOL,
                "get",
                otherKind,
                tagReader,
                get(r1, "plan", "alpha-plan"),
                200,
                contains = listOf("\"slug\":\"alpha-plan\"", "\"body\":\"alpha plan v1\""),
                absent = listOf("adoptedBy", "$p1"),
                dbChanged = false,
                docs = adoptedByP1
            ),
            case(
                "S18 adoptedBy of an admitted adopter is shown to the tag-scoped reader",
                READ_TOOL,
                "list",
                otherKind,
                tagReader,
                list(r1, ""","kind":"plan""""),
                200,
                contains = listOf("\"adoptedBy\":\"$c1\""),
                dbChanged = false,
                docs = adoptedByC1
            ),
            // S8 links directions
            case(
                "S8 links outgoing returns what the document links to",
                READ_TOOL,
                "links",
                otherKind,
                reader,
                links(r1, "plan", "alpha-plan", ""","direction":"outgoing""""),
                200,
                contains = listOf("\"total\":1", "\"relation\":\"references\""),
                dbChanged = false,
                docs = linkDocs
            ),
            case(
                "S8 links incoming returns what points at the document",
                READ_TOOL,
                "links",
                otherKind,
                reader,
                links(r1, "plan", "alpha-plan", ""","direction":"incoming""""),
                200,
                contains = listOf("\"total\":1", "\"relation\":\"references\""),
                dbChanged = false,
                docs = linkDocs
            ),
            case(
                "S8 links with an unknown direction is invalid_request",
                READ_TOOL,
                "links",
                otherKind,
                reader,
                links(r1, "plan", "alpha-plan", ""","direction":"sideways""""),
                400,
                code = "invalid_request",
                contains = listOf("direction")
            ),
            case(
                "S8 links of a document without links is an empty page",
                READ_TOOL,
                "links",
                otherKind,
                reader,
                links(r1, "plan", "alpha-plan"),
                200,
                contains = listOf("\"items\":[]", "\"total\":0"),
                dbChanged = false
            ),
            case(
                "S8 links shows an attachment as an outgoing link",
                READ_TOOL,
                "links",
                otherKind,
                reader,
                links(r1, "plan", "alpha-plan", ""","direction":"outgoing""""),
                200,
                contains = listOf("\"total\":1", "\"relation\":\"attached\""),
                dbChanged = false,
                docs = attachedToC1
            ),
            // S18 (link ends the token does not admit are dropped before paging and not counted) is asserted over MCP in
            // DocsToolsBehaviorTest: several links share the fixed parity clock's timestamp, so their order (createdAt, id) falls to
            // random ids that differ between the two legs and a byte-equal body comparison would be flaky.
            // S19
            case(
                "S19 an orphan link whose source note's item was deleted is skipped without error",
                READ_TOOL,
                "links",
                otherKind,
                reader,
                links(r1, "plan", "alpha-plan", ""","direction":"incoming""""),
                200,
                contains = listOf("\"total\":1"),
                dbChanged = false,
                docs = linkDocsWithOrphan
            ),
            // S5
            case(
                "S5 archive of an already archived document succeeds",
                WRITE_TOOL,
                "archive",
                otherKind,
                writer,
                ops(op("archive", r1, "page", "alpha-page")),
                200,
                contains = listOf("\"archived\":true"),
                docs = archivedPage
            ),
            case(
                "S8 an adopted document may be archived",
                WRITE_TOOL,
                "archive",
                otherKind,
                writer,
                ops(op("archive", r1, "plan", "alpha-plan")),
                200,
                contains = listOf("\"archived\":true"),
                dbChanged = true,
                docs = adoptedByC1
            ),
            case(
                "archive of a missing document is not_found",
                WRITE_TOOL,
                "archive",
                otherKind,
                writer,
                ops(op("archive", r1, "plan", "no-such-plan")),
                207,
                code = partial,
                contains = listOf("\"code\":\"not_found\""),
                dbChanged = false
            ),
            case(
                "delete of a missing document is not_found",
                WRITE_TOOL,
                "delete",
                otherKind,
                writer,
                ops(op("delete", r1, "plan", "no-such-plan")),
                207,
                code = partial,
                contains = listOf("\"code\":\"not_found\""),
                dbChanged = false
            ),
            // S11
            case(
                "S11 upsert of an adopted document is duplicate",
                WRITE_TOOL,
                "upsert",
                otherKind,
                writer,
                ops(upsert(r1, "plan", "alpha-plan", "second")),
                207,
                code = partial,
                contains = listOf("\"code\":\"duplicate\""),
                dbChanged = false,
                docs = adoptedByC1
            ),
            case(
                "S11 delete of an adopted document is invalid_request naming the adoption",
                WRITE_TOOL,
                "delete",
                otherKind,
                writer,
                ops(op("delete", r1, "plan", "alpha-plan")),
                207,
                code = partial,
                contains = listOf("\"code\":\"invalid_request\"", "adopted"),
                dbChanged = false,
                docs = adoptedByC1
            ),
            case(
                "S11 upsert of an archived document is invalid_request naming the archive",
                WRITE_TOOL,
                "upsert",
                otherKind,
                writer,
                ops(upsert(r1, "page", "alpha-page", "second")),
                207,
                code = partial,
                contains = listOf("\"code\":\"invalid_request\"", "archived"),
                dbChanged = false,
                docs = archivedPage
            ),
            // S12
            case(
                "S12 get on a rootId at depth 1 is invalid_request",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                get(c1, "plan", "alpha-plan"),
                400,
                code = "invalid_request",
                dbChanged = false
            ),
            case(
                "S12 upsert on a rootId at depth 1 is invalid_request and writes nothing",
                WRITE_TOOL,
                "upsert",
                otherKind,
                writer,
                ops(upsert(c1, "plan", "alpha-plan", "nested")),
                207,
                code = partial,
                contains = listOf("\"code\":\"invalid_request\""),
                dbChanged = false
            ),
            case(
                "S12 get on an unknown rootId is not_found",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                get(ghost, "plan", "alpha-plan"),
                404,
                code = "not_found",
                dbChanged = false
            ),
            case(
                "S12 upsert on an unknown rootId is not_found",
                WRITE_TOOL,
                "upsert",
                otherKind,
                writer,
                ops(upsert(ghost, "plan", "alpha-plan", "ghost")),
                207,
                code = partial,
                contains = listOf("\"code\":\"not_found\""),
                dbChanged = false
            ),
            case(
                "S12 probe - an 8 hex prefix names the root",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                get(r1.toString().replace("-", "").take(8), "plan", "alpha-plan"),
                200,
                contains = listOf("\"rootId\":\"$r1\"", "\"slug\":\"alpha-plan\""),
                dbChanged = false
            ),
            case(
                "S12 probe - a 7 hex prefix is invalid_request",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                get(r1.toString().replace("-", "").take(7), "plan", "alpha-plan"),
                400,
                code = "invalid_request",
                dbChanged = false
            ),
            case(
                "S12 probe - a 32 hex id without dashes names the root",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                get(r1.toString().replace("-", ""), "plan", "alpha-plan"),
                200,
                contains = listOf("\"rootId\":\"$r1\""),
                dbChanged = false
            ),
            // S14 capability: every write op needs write-docs
            case(
                "S14 reader cannot upsert",
                WRITE_TOOL,
                "upsert",
                otherKind,
                reader,
                ops(upsert(r1, "plan", "new-plan", "x")),
                207,
                code = partial,
                contains = capability(),
                dbChanged = false
            ),
            case(
                "S14 reader cannot archive",
                WRITE_TOOL,
                "archive",
                otherKind,
                reader,
                ops(op("archive", r1, "plan", "alpha-plan")),
                207,
                code = partial,
                contains = capability(),
                dbChanged = false
            ),
            case(
                "S14 reader cannot unarchive",
                WRITE_TOOL,
                "unarchive",
                otherKind,
                reader,
                ops(op("unarchive", r1, "page", "alpha-page")),
                207,
                code = partial,
                contains = capability(),
                dbChanged = false,
                docs = archivedPage
            ),
            case(
                "S14 reader cannot delete",
                WRITE_TOOL,
                "delete",
                otherKind,
                reader,
                ops(op("delete", r1, "page", "alpha-page")),
                207,
                code = partial,
                contains = capability(),
                dbChanged = false
            ),
            case(
                "S14 reader cannot attach",
                WRITE_TOOL,
                "attach",
                otherKind,
                reader,
                ops(attach("attach", r1, "plan", "alpha-plan", c1)),
                207,
                code = partial,
                contains = capability(),
                dbChanged = false
            ),
            case(
                "S14 reader cannot detach",
                WRITE_TOOL,
                "detach",
                otherKind,
                reader,
                ops(attach("detach", r1, "plan", "alpha-plan", c1)),
                207,
                code = partial,
                contains = capability(),
                dbChanged = false,
                docs = attachedToC1
            ),
            // D2: a token that may write notes and items may not rewrite documents (a rule document is served to agents as instructions)
            case(
                "S14 a write-notes and write-items token cannot upsert a rule document",
                WRITE_TOOL,
                "upsert",
                otherKind,
                notesOnly,
                ops(upsert(r1, "rule", "alpha-rule", "hijacked rule")),
                207,
                code = partial,
                contains = capability(),
                dbChanged = false,
                docs = ruleDoc
            ),
            case(
                "S14 control - a write-docs token upserts a rule document",
                WRITE_TOOL,
                "upsert",
                otherKind,
                writer,
                ops(upsert(r1, "rule", "alpha-rule", "revised rule")),
                200,
                contains = listOf("\"kind\":\"rule\"", "\"version\":2", "\"created\":false"),
                dbChanged = true,
                docs = ruleDoc
            ),
            case(
                "S14 a reader can read a rule document",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                get(r1, "rule", "alpha-rule"),
                200,
                contains = listOf("\"body\":\"alpha rule body\""),
                dbChanged = false,
                docs = ruleDoc
            ),
            // S7
            case(
                "S7 attach twice in one batch succeeds twice",
                WRITE_TOOL,
                "attach",
                otherKind,
                writer,
                ops(attach("attach", r1, "plan", "alpha-plan", c1), attach("attach", r1, "plan", "alpha-plan", c1)),
                200,
                contains = listOf("\"summary\":{\"ok\":2,\"failed\":0}"),
                dbChanged = true
            ),
            case(
                "S7 attach of an item that is already attached changes nothing",
                WRITE_TOOL,
                "attach",
                otherKind,
                writer,
                ops(attach("attach", r1, "plan", "alpha-plan", c1)),
                200,
                dbChanged = false,
                docs = attachedToC1
            ),
            case(
                "S7 detach of a never attached item returns 0",
                WRITE_TOOL,
                "detach",
                otherKind,
                writer,
                ops(attach("detach", r1, "plan", "alpha-plan", c1)),
                200,
                contains = listOf("\"detached\":0"),
                dbChanged = false
            ),
            case(
                "S7 detach twice returns 1 then 0",
                WRITE_TOOL,
                "detach",
                otherKind,
                writer,
                ops(attach("detach", r1, "plan", "alpha-plan", c1), attach("detach", r1, "plan", "alpha-plan", c1)),
                200,
                contains = listOf("\"detached\":1", "\"detached\":0"),
                dbChanged = true,
                docs = attachedToC1
            ),
            case(
                "S7 detach of a missing document is not_found",
                WRITE_TOOL,
                "detach",
                otherKind,
                writer,
                ops(attach("detach", r1, "plan", "no-such-plan", c1)),
                207,
                code = partial,
                contains = listOf("\"code\":\"not_found\""),
                dbChanged = false
            ),
            case(
                "S7 attach to a missing document is not_found",
                WRITE_TOOL,
                "attach",
                otherKind,
                writer,
                ops(attach("attach", r1, "plan", "no-such-plan", c1)),
                207,
                code = partial,
                contains = listOf("\"code\":\"not_found\""),
                dbChanged = false
            ),
            case(
                "S7 attach of an unknown item is not_found",
                WRITE_TOOL,
                "attach",
                otherKind,
                writer,
                ops(attach("attach", r1, "plan", "alpha-plan", ghost)),
                207,
                code = partial,
                contains = listOf("\"code\":\"not_found\""),
                dbChanged = false
            ),
            case(
                "S7 attach of an item in another root is invalid_request",
                WRITE_TOOL,
                "attach",
                otherKind,
                writer,
                ops(attach("attach", r1, "plan", "alpha-plan", x2)),
                207,
                code = partial,
                contains = listOf("\"code\":\"invalid_request\""),
                dbChanged = false
            ),
            case(
                "S7 attach of the root item itself is allowed",
                WRITE_TOOL,
                "attach",
                otherKind,
                writer,
                ops(attach("attach", r1, "plan", "alpha-plan", r1)),
                200,
                contains = listOf("\"itemId\":\"$r1\""),
                dbChanged = true
            ),
            // S22
            case(
                "S22 upsert with kind in upper case is invalid_request",
                WRITE_TOOL,
                "upsert",
                otherKind,
                writer,
                ops(upsert(r1, "PLAN", "new-plan", "x")),
                207,
                code = partial,
                contains = listOf("\"code\":\"invalid_request\"", "kind"),
                dbChanged = false
            ),
            case(
                "S22 upsert with an unknown kind is invalid_request",
                WRITE_TOOL,
                "upsert",
                otherKind,
                writer,
                ops(upsert(r1, "doc", "new-plan", "x")),
                207,
                code = partial,
                contains = listOf("\"code\":\"invalid_request\"", "kind"),
                dbChanged = false
            ),
            case(
                "S22 upsert without a kind is invalid_request",
                WRITE_TOOL,
                "upsert",
                otherKind,
                writer,
                ops("""{"op":"upsert","rootId":"$r1","slug":"new-plan","body":"x"}"""),
                207,
                code = partial,
                contains = listOf("\"code\":\"invalid_request\"", "kind"),
                dbChanged = false
            ),
            case(
                "S22 get with kind in upper case is invalid_request",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                get(r1, "PLAN", "alpha-plan"),
                400,
                code = "invalid_request",
                contains = listOf("kind")
            ),
            case(
                "S22 get with an unknown kind is invalid_request",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                get(r1, "doc", "alpha-plan"),
                400,
                code = "invalid_request",
                contains = listOf("kind")
            ),
            case(
                "S22 get without a kind is invalid_request",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                """{"select":"get","rootId":"$r1","slug":"alpha-plan"}""",
                400,
                code = "invalid_request",
                contains = listOf("kind")
            ),
            case(
                "S22 list with an unknown kind filter is invalid_request",
                READ_TOOL,
                "list",
                otherKind,
                reader,
                list(r1, ""","kind":"doc""""),
                400,
                code = "invalid_request",
                contains = listOf("kind")
            ),
            case(
                "S22 get with a field of another select is unknown_parameter",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                get(r1, "plan", "alpha-plan", ""","direction":"incoming""""),
                400,
                code = "unknown_parameter"
            ),
            case(
                "S22 get with an unknown field is unknown_parameter",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                get(r1, "plan", "alpha-plan", ""","foo":1"""),
                400,
                code = "unknown_parameter"
            ),
            case(
                "S22 history with includeArchived is unknown_parameter",
                READ_TOOL,
                "history",
                otherKind,
                reader,
                history(r1, "plan", "alpha-plan", ""","includeArchived":true"""),
                400,
                code = "unknown_parameter"
            ),
            case(
                "S22 upsert with a field of another op is unknown_parameter",
                WRITE_TOOL,
                "upsert",
                otherKind,
                writer,
                ops(upsert(r1, "plan", "new-plan", "x", ""","itemId":"$c1"""")),
                207,
                code = partial,
                contains = listOf("\"code\":\"unknown_parameter\""),
                dbChanged = false
            ),
            case(
                "S22 attach with a body is unknown_parameter",
                WRITE_TOOL,
                "attach",
                otherKind,
                writer,
                ops(op("attach", r1, "plan", "alpha-plan", ""","itemId":"$c1","body":"x"""")),
                207,
                code = partial,
                contains = listOf("\"code\":\"unknown_parameter\""),
                dbChanged = false
            ),
            // body: empty, absent, CR handling
            case(
                "probe upsert with an empty body is accepted",
                WRITE_TOOL,
                "upsert",
                otherKind,
                writer,
                ops(upsert(r1, "plan", "empty-plan", "")),
                200,
                contains = listOf("\"created\":true", "\"contentHash\":\"${hashOf("")}\""),
                dbChanged = true
            ),
            case(
                "probe upsert without body and without bodyFromFile is invalid_request",
                WRITE_TOOL,
                "upsert",
                otherKind,
                writer,
                ops(op("upsert", r1, "plan", "empty-plan")),
                207,
                code = partial,
                contains = listOf("\"code\":\"invalid_request\""),
                dbChanged = false
            ),
            case(
                "probe a lone CR is kept and only CRLF becomes LF",
                WRITE_TOOL,
                "upsert",
                otherKind,
                writer,
                ops(upsert(r1, "plan", "cr-plan", """a\rb""")),
                200,
                contains = listOf("\"contentHash\":\"${hashOf("a\rb")}\""),
                absent = listOf(hashOf("a\nb")),
                dbChanged = true
            ),
            case(
                "probe CRLF text hashes as the LF text",
                WRITE_TOOL,
                "upsert",
                otherKind,
                writer,
                ops(upsert(r1, "plan", "crlf-plan", """a\r\nb\r\nc""")),
                200,
                contains = listOf("\"contentHash\":\"${hashOf("a\nb\nc")}\""),
                dbChanged = true
            ),
            // S9 sizes are exercised over MCP in DocsToolsBehaviorTest (a 64 KiB body may exceed the REST body limit)
            // paging and cursors
            case(
                "paging list with limit 1 over two documents is truncated with a cursor",
                READ_TOOL,
                "list",
                otherKind,
                reader,
                list(r1, ""","limit":1"""),
                200,
                contains = listOf("\"total\":2", "\"truncated\":true", "\"next\":"),
                dbChanged = false
            ),
            case(
                "list with limit 0 is invalid_request",
                READ_TOOL,
                "list",
                otherKind,
                reader,
                list(r1, ""","limit":0"""),
                400,
                code = "invalid_request",
                contains = listOf("limit")
            ),
            case(
                "list with limit 201 is invalid_request",
                READ_TOOL,
                "list",
                otherKind,
                reader,
                list(r1, ""","limit":201"""),
                400,
                code = "invalid_request",
                contains = listOf("limit")
            ),
            case(
                "list with limit 200 is accepted",
                READ_TOOL,
                "list",
                otherKind,
                reader,
                list(r1, ""","limit":200"""),
                200,
                contains = listOf("\"total\":2"),
                dbChanged = false
            ),
            case(
                "list with an undecodable cursor is invalid_cursor",
                READ_TOOL,
                "list",
                otherKind,
                reader,
                list(r1, ""","cursor":"not-a-cursor!""""),
                400,
                code = "invalid_cursor"
            ),
            case(
                "history with an undecodable cursor is invalid_cursor",
                READ_TOOL,
                "history",
                otherKind,
                reader,
                history(r1, "plan", "alpha-plan", ""","cursor":"not-a-cursor!""""),
                400,
                code = "invalid_cursor"
            ),
            case(
                "links with an undecodable cursor is invalid_cursor",
                READ_TOOL,
                "links",
                otherKind,
                reader,
                links(r1, "plan", "alpha-plan", ""","cursor":"not-a-cursor!""""),
                400,
                code = "invalid_cursor"
            ),
            case(
                "paging history with limit 1 over two versions is truncated",
                READ_TOOL,
                "history",
                otherKind,
                reader,
                history(r1, "plan", "alpha-plan", ""","limit":1"""),
                200,
                contains = listOf("\"total\":2", "\"truncated\":true", "\"version\":1"),
                absent = listOf("\"version\":2"),
                dbChanged = false,
                docs = twoVersions
            ),
            // D5: attribution of version history follows the caller's visibility; the parity access redacts it on both surfaces
            case(
                "D5 history does not show the writer of a version to a caller that cannot see attribution",
                READ_TOOL,
                "history",
                otherKind,
                reader,
                history(r1, "plan", "alpha-plan"),
                200,
                contains = listOf("\"total\":2"),
                absent = listOf("seed-agent-77"),
                dbChanged = false,
                docs = actorWritten
            ),
            case(
                "D5 get of a stored version does not show the writer to a caller that cannot see attribution",
                READ_TOOL,
                "get",
                otherKind,
                reader,
                get(r1, "plan", "alpha-plan", ""","version":1"""),
                200,
                contains = listOf("\"body\":\"alpha plan v1\""),
                absent = listOf("seed-agent-77"),
                dbChanged = false,
                docs = actorWritten
            ),
            // S6: deleting a document that others link to
            case(
                "S6 delete of a plan that a note links to succeeds",
                WRITE_TOOL,
                "delete",
                otherKind,
                writer,
                ops(op("delete", r1, "plan", "alpha-plan")),
                200,
                contains = listOf("\"deleted\":true"),
                dbChanged = true,
                docs = linkDocs
            ),
            // task-scope 1A order: shape checks, then root resolution, then the scope check, then store work (D9: a forbidden call
            // reads nothing). Whether a full-UUID root that does not exist answers not_found or forbidden for a root-scoped token is
            // not stated by any oracle, so it is not asserted (arbitration record in the manifest).
            case(
                "D9 shape is checked before scope: a bad kind on an out-of-scope root is invalid_request",
                READ_TOOL,
                "get",
                otherKind,
                rootReader,
                get(r2, "PLAN", "beta-plan"),
                400,
                code = "invalid_request",
                absent = listOf("beta secret body", "forbidden")
            ),
            case(
                "D9 scope precedes store work: a missing slug under an out-of-scope root is forbidden, not not_found",
                READ_TOOL,
                "get",
                otherKind,
                rootReader,
                get(r2, "plan", "no-such-plan"),
                403,
                code = "forbidden",
                contains = scopeRoot(),
                dbChanged = false
            ),
            case(
                "D9 scope precedes store work on writes: a stale expectedVersion under an out-of-scope root is forbidden",
                WRITE_TOOL,
                "upsert",
                otherKind,
                rootWriter,
                ops(upsert(r2, "plan", "beta-plan", "x", ""","expectedVersion":9""")),
                207,
                code = partial,
                contains = listOf("\"code\":\"forbidden\"") + scopeRoot(),
                absent = listOf("version_conflict"),
                dbChanged = false
            ),
            case(
                "D9 scope precedes store work on writes: deleting an adopted document under an out-of-scope root is forbidden",
                WRITE_TOOL,
                "delete",
                otherKind,
                rootWriter,
                ops(op("delete", r2, "plan", "beta-plan")),
                207,
                code = partial,
                contains = listOf("\"code\":\"forbidden\"") + scopeRoot(),
                absent = listOf("adopted"),
                dbChanged = false,
                docs = {
                    baseDocs(this)
                    adopt(r2, "beta-plan", x2)
                }
            ),
            // U18: an atomic batch rolls back every op when one fails
            case(
                "atomic batch with a failing second op rolls the first back",
                WRITE_TOOL,
                "upsert",
                otherKind,
                writer,
                ops(
                    upsert(r1, "plan", "first-plan", "x"),
                    upsert(r1, "plan", "second-plan", "y", ""","expectedVersion":9"""),
                    top = ""","atomic":true"""
                ),
                ErrorCode.VERSION_CONFLICT.httpStatus,
                code = "version_conflict",
                contains = listOf("\"index\":1"),
                dbChanged = false
            )
        )

    val cases: List<DocsParityCase> = happy + scopeForbidden + slugGuard + other

    val scenarios: List<ParityScenario> get() = cases.map { it.scenario }

    /** Every docs select and op names a document under a project root, so none is exempt from the scope-forbidden requirement. */
    val exemptions: List<ScopeExempt> = emptyList()
}
