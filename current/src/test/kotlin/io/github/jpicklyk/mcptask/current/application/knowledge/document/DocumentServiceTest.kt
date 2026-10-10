package io.github.jpicklyk.mcptask.current.application.knowledge.document

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.service.PlanDocumentService
import io.github.jpicklyk.mcptask.current.application.service.PlanDocumentStashResult
import io.github.jpicklyk.mcptask.current.application.service.RuleService
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Document
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.EntityRef
import io.github.jpicklyk.mcptask.current.domain.model.Link
import io.github.jpicklyk.mcptask.current.domain.model.LinkRelation
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocument
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentStatus
import io.github.jpicklyk.mcptask.current.domain.model.RefKind
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Behavioral tests for [DocumentService] and the [PlanSlugs] / [PlanDocumentService] facade over a real SQLite database
 * (production PRAGMAs: foreign keys ON, one unit per operation).
 *
 * Oracles are the frozen `task-scope` section D (service contract), `test-plan` S1-S21 and the pre-existing 3.x contracts
 * the plan keeps: `PlanDocumentService.MAX_BODY_BYTES` (65536), `RuleService.MAX_RULE_BODY_BYTES` (16384), SHA-256 of the
 * LF-normalized UTF-8 bytes (NIST vector for "abc"), and the 3.x `AdoptedConflict` / `AlreadyAdopted` / `NotFound` outcomes.
 * Event rows are read straight from the `events` table; only the payload keys that existing event tests already assert
 * (`slug`, `adoptedByItemId`) are inspected.
 */
class DocumentServiceTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private lateinit var provider: RepositoryProvider
    private lateinit var service: DocumentService
    private lateinit var rootId: UUID

    @BeforeEach
    fun setUp() =
        runBlocking {
            provider = db.repositoryProvider()
            service = DocumentService(provider, db.unitOfWork())
            rootId = newItem("Root")
        }

    // ---------------------------------------------------------------- helpers

    private suspend fun newItem(
        title: String,
        parent: UUID? = null,
        depth: Int = 0
    ): UUID = provider.workItemRepository().create(WorkItem(title = title, parentId = parent, depth = depth))!!.id

    private fun <T> Outcome<T>.value(): T =
        when (this) {
            is Outcome.Ok -> value
            is Outcome.Err -> fail("expected Ok but was Err: ${error.code} ${error.message}")
        }

    private fun Outcome<*>.error(): DomainError =
        when (this) {
            is Outcome.Ok -> fail("expected Err but was Ok: $value")
            is Outcome.Err -> error
        }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun bytes(id: UUID): ByteArray =
        ByteBuffer
            .allocate(16)
            .putLong(id.mostSignificantBits)
            .putLong(id.leastSignificantBits)
            .array()

    private fun uuid(raw: ByteArray): UUID = ByteBuffer.wrap(raw).let { UUID(it.long, it.long) }

    private fun scalar(
        sql: String,
        vararg params: UUID
    ): Int =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.prepareStatement(sql).use { ps ->
                params.forEachIndexed { i, p -> ps.setBytes(i + 1, bytes(p)) }
                ps.executeQuery().use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private data class VersionRow(
        val version: Int,
        val body: String,
        val hash: String,
        val actorId: String?
    )

    private fun versionRows(documentId: UUID): List<VersionRow> =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c
                .prepareStatement(
                    "SELECT version, body, content_hash, actor_id FROM document_versions WHERE document_id = ? ORDER BY version"
                ).use { ps ->
                    ps.setBytes(1, bytes(documentId))
                    ps.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(VersionRow(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4))) }
                    }
                }
        }

    private data class EventRow(
        val type: String,
        val entityKind: String,
        val entityId: UUID,
        val rootId: UUID,
        val data: String
    ) {
        fun str(key: String): String? =
            Json
                .parseToJsonElement(data)
                .jsonObject[key]
                ?.jsonPrimitive
                ?.content
    }

    private fun documentEvents(): List<EventRow> =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st
                    .executeQuery(
                        "SELECT type, entity_kind, entity_id, root_id, data FROM events WHERE type LIKE 'document.%' ORDER BY seq"
                    ).use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(EventRow(rs.getString(1), rs.getString(2), uuid(rs.getBytes(3)), uuid(rs.getBytes(4)), rs.getString(5)))
                            }
                        }
                    }
            }
        }

    private fun eventTypes(): List<String> = documentEvents().map { it.type }

    private fun ftsMatches(
        table: String,
        term: String
    ): Int = scalar("SELECT count(*) FROM $table WHERE $table MATCH '$term'")

    private suspend fun write(
        slug: String,
        body: String,
        kind: DocumentKind = DocumentKind.PLAN,
        expectedVersion: Int? = null,
        title: String? = null,
        actorId: String? = null
    ): Outcome<Document> = service.write(rootId, kind, slug, body, title = title, expectedVersion = expectedVersion, actorId = actorId)

    private suspend fun created(
        slug: String,
        body: String = "# $slug\n",
        kind: DocumentKind = DocumentKind.PLAN
    ): Document = write(slug, body, kind).value()

    private suspend fun assertNothingChanged(
        documentId: UUID,
        expectedVersion: Int,
        expectedBody: String,
        expectedEventTypes: List<String>
    ) {
        val current = assertNotNull(provider.documentStore().getById(documentId))
        assertEquals(expectedVersion, current.version, "version must not move")
        assertEquals(expectedBody, current.body, "body must not move")
        assertEquals((1..expectedVersion).toList(), versionRows(documentId).map { it.version }, "no version row may be added")
        assertEquals(expectedEventTypes, eventTypes(), "no event may be recorded")
    }

    private suspend fun adoptViaFacade(
        facade: PlanDocumentService,
        slug: String,
        adopter: UUID
    ): Outcome<PlanDocument> = db.unitOfWork().write<PlanDocument>("test.adoptInUnit") { facade.adoptInUnit(rootId, slug, adopter) }

    // ---------------------------------------------------------------- S4 / S5 / S6 happy writes

    @Test
    fun `S4 write of a new slug creates version 1 with the sha256 of the body and records document created`() =
        runBlocking {
            val doc = write("plan-a", "# Plan A\n", title = "Plan A title", actorId = "actor-1").value()

            assertEquals(rootId, doc.rootItemId)
            assertEquals(DocumentKind.PLAN, doc.kind)
            assertEquals("plan-a", doc.slug)
            assertEquals("Plan A title", doc.title)
            assertEquals("# Plan A\n", doc.body)
            assertEquals(1, doc.version)
            assertEquals(sha256("# Plan A\n"), doc.contentHash)
            assertNull(doc.archivedAt)

            val stored = assertNotNull(service.get(rootId, DocumentKind.PLAN, "plan-a"))
            assertEquals(doc.id, stored.id)
            assertEquals(doc.body, stored.body)
            assertEquals(doc.contentHash, stored.contentHash)

            assertEquals(listOf(VersionRow(1, "# Plan A\n", sha256("# Plan A\n"), "actor-1")), versionRows(doc.id))

            val events = documentEvents()
            assertEquals(listOf("document.created"), events.map { it.type })
            assertEquals("document", events.single().entityKind)
            assertEquals(doc.id, events.single().entityId)
            assertEquals(rootId, events.single().rootId)
            assertEquals("plan-a", events.single().str("slug"))
        }

    @Test
    fun `S4 contentHash is the lowercase sha256 hex of the UTF-8 bytes (NIST vector)`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", DocumentService.contentHash("abc"))
        assertEquals(sha256("héllo €\n"), DocumentService.contentHash("héllo €\n"))
    }

    @Test
    fun `S5 write with expectedVersion equal to the current version appends version 2 and records document updated`() =
        runBlocking {
            val v1 = created("plan-a", "first\n")

            val v2 = write("plan-a", "second\n", expectedVersion = 1, actorId = "actor-2").value()

            assertEquals(v1.id, v2.id, "an overwrite keeps the document id")
            assertEquals(2, v2.version)
            assertEquals("second\n", v2.body)
            assertEquals(sha256("second\n"), v2.contentHash)
            assertEquals(v1.createdAt, v2.createdAt)
            assertFalse(v2.modifiedAt.isBefore(v2.createdAt))

            val stored = assertNotNull(service.get(rootId, DocumentKind.PLAN, "plan-a"))
            assertEquals(2, stored.version)
            assertEquals("second\n", stored.body)

            assertEquals(
                listOf(
                    VersionRow(1, "first\n", sha256("first\n"), null),
                    VersionRow(2, "second\n", sha256("second\n"), "actor-2")
                ),
                versionRows(v1.id)
            )
            assertEquals(listOf("document.created", "document.updated"), eventTypes())
            assertEquals(v1.id, documentEvents().last().entityId)
        }

    @Test
    fun `S6 write with a null expectedVersion overwrites unconditionally and the same body twice still advances the version`() =
        runBlocking {
            val v1 = created("plan-a", "same\n")
            val v2 = write("plan-a", "same\n").value()
            val v3 = write("plan-a", "different\n").value()

            assertEquals(listOf(1, 2, 3), listOf(v1.version, v2.version, v3.version))
            assertEquals(v1.contentHash, v2.contentHash, "identical bodies hash identically")
            assertNotEquals(v2.contentHash, v3.contentHash)
            assertEquals(listOf(1, 2, 3), versionRows(v1.id).map { it.version })
            assertEquals(listOf("document.created", "document.updated", "document.updated"), eventTypes())
        }

    @Test
    fun `S6 expectedVersion 0 on an absent slug creates version 1`() =
        runBlocking {
            val doc = write("plan-a", "x\n", expectedVersion = 0).value()
            assertEquals(1, doc.version)
            assertEquals(listOf("document.created"), eventTypes())
        }

    @Test
    fun `S6 a page document is a first-class kind`(): Unit =
        runBlocking {
            val doc = created("notes-page", "# Page\n", DocumentKind.PAGE)
            assertEquals(DocumentKind.PAGE, doc.kind)
            assertNull(service.get(rootId, DocumentKind.PLAN, "notes-page"))
            assertNotNull(service.get(rootId, DocumentKind.PAGE, "notes-page"))
        }

    // ---------------------------------------------------------------- S12 version conflicts

    @Test
    fun `S12 a stale expectedVersion is version_conflict naming the document and changes nothing`() =
        runBlocking {
            val v1 = created("plan-a", "first\n")
            write("plan-a", "second\n", expectedVersion = 1).value()

            val err = write("plan-a", "late\n", expectedVersion = 1).error()

            assertEquals(ErrorCode.VERSION_CONFLICT, err.code)
            val detail = assertIs<ErrorDetail.VersionConflict>(err.detail)
            assertEquals(EntityKind.DOCUMENT, detail.kind)
            assertEquals(1L, detail.expected)
            assertEquals(2L, detail.actual)
            assertNothingChanged(v1.id, 2, "second\n", listOf("document.created", "document.updated"))
        }

    @Test
    fun `S12 an expectedVersion above the current version is also version_conflict`() =
        runBlocking {
            val v1 = created("plan-a", "first\n")
            val err = write("plan-a", "ahead\n", expectedVersion = 5).error()
            assertEquals(ErrorCode.VERSION_CONFLICT, err.code)
            val detail = assertIs<ErrorDetail.VersionConflict>(err.detail)
            assertEquals(5L, detail.expected)
            assertEquals(1L, detail.actual)
            assertNothingChanged(v1.id, 1, "first\n", listOf("document.created"))
        }

    @Test
    fun `S12 expectedVersion 0 on a present document is version_conflict`() =
        runBlocking {
            val v1 = created("plan-a", "first\n")
            val err = write("plan-a", "again\n", expectedVersion = 0).error()
            assertEquals(ErrorCode.VERSION_CONFLICT, err.code)
            val detail = assertIs<ErrorDetail.VersionConflict>(err.detail)
            assertEquals(0L, detail.expected)
            assertEquals(1L, detail.actual)
            assertNothingChanged(v1.id, 1, "first\n", listOf("document.created"))
        }

    @Test
    fun `S12 probe - a negative expectedVersion on an absent slug is rejected and creates nothing`() =
        runBlocking {
            assertIs<Outcome.Err>(write("plan-a", "x\n", expectedVersion = -1))
            assertNull(service.get(rootId, DocumentKind.PLAN, "plan-a"))
            assertEquals(emptyList(), eventTypes())
            assertEquals(0, scalar("SELECT count(*) FROM documents"))
            assertEquals(0, scalar("SELECT count(*) FROM document_versions"))
        }

    // ---------------------------------------------------------------- S13 size caps

    @Test
    fun `S13 plan cap is 65536 bytes - at the cap succeeds and one byte over is payload_too_large`() =
        runBlocking {
            assertEquals(PlanDocumentService.MAX_BODY_BYTES, DocumentService.maxBodyBytes(DocumentKind.PLAN))
            assertEquals(65536, DocumentService.maxBodyBytes(DocumentKind.PLAN))

            created("at-cap", "x".repeat(65536))

            val err = write("over-cap", "x".repeat(65537)).error()
            assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, err.code)
            assertNull(service.get(rootId, DocumentKind.PLAN, "over-cap"))
            assertEquals(listOf("document.created"), eventTypes(), "a rejected write records no event")
        }

    @Test
    fun `S13 the cap counts UTF-8 bytes not characters`() =
        runBlocking {
            // 65534 + 1 chars is under 65536 chars but 65534 + 3 = 65537 bytes; 65533 + 3 = 65536 bytes is exactly the cap.
            created("exact", "x".repeat(65533) + "€")
            val err = write("straddle", "x".repeat(65534) + "€").error()
            assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, err.code)
            assertNull(service.get(rootId, DocumentKind.PLAN, "straddle"))
        }

    @Test
    fun `S13 rule cap is 16384 bytes while plan and page documents may be larger`(): Unit =
        runBlocking {
            assertEquals(RuleService.MAX_RULE_BODY_BYTES, DocumentService.maxBodyBytes(DocumentKind.RULE))
            assertEquals(16384, DocumentService.maxBodyBytes(DocumentKind.RULE))

            created("rule-at-cap", "r".repeat(16384), DocumentKind.RULE)
            val err = write("rule-over", "r".repeat(16385), DocumentKind.RULE).error()
            assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, err.code)
            assertNull(service.get(rootId, DocumentKind.RULE, "rule-over"))

            created("plan-16385", "p".repeat(16385), DocumentKind.PLAN)
            created("page-16385", "p".repeat(16385), DocumentKind.PAGE)
        }

    @Test
    fun `S13 an oversize overwrite leaves the existing document untouched`() =
        runBlocking {
            val v1 = created("plan-a", "keep me\n")
            val err = write("plan-a", "x".repeat(65537)).error()
            assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, err.code)
            assertNothingChanged(v1.id, 1, "keep me\n", listOf("document.created"))
        }

    // ---------------------------------------------------------------- S14 state guards

    @Test
    fun `S14 a write to an archived document is invalid_request naming slug and archived`() =
        runBlocking {
            val v1 = created("plan-a", "first\n")
            service.archive(rootId, DocumentKind.PLAN, "plan-a").value()

            val err = write("plan-a", "second\n").error()

            assertEquals(ErrorCode.INVALID_REQUEST, err.code)
            val fields = assertIs<ErrorDetail.InvalidRequest>(err.detail).fields.toString()
            assertTrue(DocumentService.FIELD_SLUG in fields, "violation must name the slug field: $fields")
            assertTrue(DocumentService.REASON_ARCHIVED in fields, "violation must carry the archived reason: $fields")
            assertEquals("first\n", assertNotNull(service.get(rootId, DocumentKind.PLAN, "plan-a")).body)
            assertEquals(listOf(1), versionRows(v1.id).map { it.version })

            service.unarchive(rootId, DocumentKind.PLAN, "plan-a").value()
            assertEquals(2, write("plan-a", "second\n").value().version, "writing works again after unarchive")
        }

    @Test
    fun `S14 a write to an adopted document is duplicate naming the existing document and changes nothing`() =
        runBlocking {
            val v1 = created("plan-a", "first\n")
            val adopter = newItem("Adopter")
            service.adopt(rootId, "plan-a", adopter).value()

            val err = write("plan-a", "second\n").error()

            assertEquals(ErrorCode.DUPLICATE, err.code)
            val detail = assertIs<ErrorDetail.Duplicate>(err.detail)
            assertEquals(EntityKind.DOCUMENT, detail.kind)
            assertEquals(v1.id.toString(), detail.existingId)
            assertNothingChanged(v1.id, 1, "first\n", listOf("document.created", "document.adopted"))
        }

    @Test
    fun `S14 deleting an adopted document is invalid_request and keeps the document its versions and the link`() =
        runBlocking {
            val v1 = created("plan-a", "first\n")
            val adopter = newItem("Adopter")
            service.adopt(rootId, "plan-a", adopter).value()

            val err = service.delete(rootId, DocumentKind.PLAN, "plan-a").error()

            assertEquals(ErrorCode.INVALID_REQUEST, err.code)
            assertNotNull(service.get(rootId, DocumentKind.PLAN, "plan-a"))
            assertEquals(1, versionRows(v1.id).size)
            assertEquals(1, scalar("SELECT count(*) FROM links WHERE from_id = ?", v1.id))
            assertEquals(listOf("document.created", "document.adopted"), eventTypes(), "a refused delete records no event")
        }

    @Test
    fun `S14 adopting a rule document is invalid_request and creates no link`() =
        runBlocking {
            created("rule-a", "rule\n", DocumentKind.RULE)
            val adopter = newItem("Adopter")

            val err = service.adopt(rootId, "rule-a", adopter, DocumentKind.RULE).error()

            assertEquals(ErrorCode.INVALID_REQUEST, err.code)
            assertEquals(0, scalar("SELECT count(*) FROM links"))
            assertEquals(listOf("document.created"), eventTypes())
        }

    // ---------------------------------------------------------------- S7 adoption

    @Test
    fun `S7 adopt inserts one adopted_by link records document adopted and the facade reports ADOPTED`() =
        runBlocking {
            val doc = created("plan-a", "first\n")
            val adopter = newItem("Adopter")
            assertNull(service.adoptionOf(doc), "an unadopted document has no adoption link")

            service.adopt(rootId, "plan-a", adopter).value()

            val link = assertNotNull(service.adoptionOf(doc))
            assertEquals(LinkRelation.ADOPTED_BY, link.relation)
            assertEquals(EntityRef(RefKind.DOCUMENT, doc.id), link.from)
            assertEquals(EntityRef(RefKind.ITEM, adopter), link.to)
            assertEquals(rootId, link.rootItemId)
            assertEquals(1, scalar("SELECT count(*) FROM links WHERE relation = 'adopted_by'"))

            val events = documentEvents()
            assertEquals(listOf("document.created", "document.adopted"), events.map { it.type })
            assertEquals(doc.id, events.last().entityId)
            assertEquals("plan-a", events.last().str("slug"))
            assertEquals(adopter.toString(), events.last().str("adoptedByItemId"))

            val facade = assertNotNull(provider.planDocumentRepository().get(rootId, "plan-a"))
            assertEquals(PlanDocumentStatus.ADOPTED, facade.status)
            assertEquals(adopter, facade.adoptedByItemId)
            assertEquals("first\n", facade.body)
            assertEquals(doc.id, facade.id)
        }

    @Test
    fun `S14 probe - a replayed adopt is duplicate and the first adopter and link stay`() =
        runBlocking {
            val doc = created("plan-a", "first\n")
            val first = newItem("First adopter")
            val second = newItem("Second adopter")
            service.adopt(rootId, "plan-a", first).value()

            val sameAgain = service.adopt(rootId, "plan-a", first).error()
            val otherAdopter = service.adopt(rootId, "plan-a", second).error()

            assertEquals(ErrorCode.DUPLICATE, sameAgain.code)
            assertEquals(ErrorCode.DUPLICATE, otherAdopter.code)
            assertEquals(1, scalar("SELECT count(*) FROM links WHERE from_id = ?", doc.id))
            assertEquals(EntityRef(RefKind.ITEM, first), assertNotNull(service.adoptionOf(doc)).to)
            assertEquals(listOf("document.created", "document.adopted"), eventTypes())
        }

    @Test
    fun `S7 adopting a slug that does not exist is not_found`() =
        runBlocking {
            val adopter = newItem("Adopter")
            val err = service.adopt(rootId, "no-such-plan", adopter).error()
            assertEquals(ErrorCode.NOT_FOUND, err.code)
            assertEquals(0, scalar("SELECT count(*) FROM links"))
        }

    @Test
    fun `D16 deleting the adopting item keeps the adoption as provenance and the document stays protected`() =
        runBlocking {
            val doc = created("plan-a", "first\n")
            val adopter = newItem("Adopter")
            service.adopt(rootId, "plan-a", adopter).value()

            provider.workItemRepository().delete(adopter)

            val link = assertNotNull(service.adoptionOf(doc), "links outlive their targets")
            assertEquals(EntityRef(RefKind.ITEM, adopter), link.to)
            val facade = assertNotNull(provider.planDocumentRepository().get(rootId, "plan-a"))
            assertEquals(PlanDocumentStatus.ADOPTED, facade.status)
            assertEquals(adopter, facade.adoptedByItemId, "4.0 keeps the adopter id as provenance")
            assertEquals(ErrorCode.INVALID_REQUEST, service.delete(rootId, DocumentKind.PLAN, "plan-a").error().code)
            assertEquals(ErrorCode.DUPLICATE, write("plan-a", "second\n").error().code)
        }

    // ---------------------------------------------------------------- S8 archive

    @Test
    fun `S8 archive sets archivedAt hides the document from the default list and unarchive restores it`() =
        runBlocking {
            val doc = created("plan-a", "first\n")
            created("plan-b", "other\n")

            val archived = service.archive(rootId, DocumentKind.PLAN, "plan-a").value()

            assertEquals(doc.id, archived.id)
            assertNotNull(archived.archivedAt)
            assertNotNull(
                assertNotNull(service.get(rootId, DocumentKind.PLAN, "plan-a")).archivedAt,
                "get still returns an archived document"
            )
            assertEquals(listOf("plan-b"), service.list(rootId).map { it.slug })
            assertEquals(listOf("plan-a", "plan-b"), service.list(rootId, includeArchived = true).map { it.slug }.sorted())

            val restored = service.unarchive(rootId, DocumentKind.PLAN, "plan-a").value()

            assertNull(restored.archivedAt)
            assertNull(assertNotNull(service.get(rootId, DocumentKind.PLAN, "plan-a")).archivedAt)
            assertEquals(listOf("plan-a", "plan-b"), service.list(rootId).map { it.slug }.sorted())
            assertEquals(listOf("document.created", "document.created", "document.archived", "document.updated"), eventTypes())
            assertEquals(doc.id, documentEvents()[2].entityId)
            assertEquals(doc.id, documentEvents()[3].entityId)
        }

    @Test
    fun `S8 an adopted document may be archived`(): Unit =
        runBlocking {
            created("plan-a", "first\n")
            service.adopt(rootId, "plan-a", newItem("Adopter")).value()
            val archived = service.archive(rootId, DocumentKind.PLAN, "plan-a").value()
            assertNotNull(archived.archivedAt)
        }

    @Test
    fun `S8 archive and unarchive of a missing document are not_found`() =
        runBlocking {
            assertEquals(ErrorCode.NOT_FOUND, service.archive(rootId, DocumentKind.PLAN, "nope").error().code)
            assertEquals(ErrorCode.NOT_FOUND, service.unarchive(rootId, DocumentKind.PLAN, "nope").error().code)
            assertEquals(emptyList(), eventTypes())
        }

    @Test
    fun `S8 archive with a stale expectedVersion is version_conflict and archives nothing`() =
        runBlocking {
            val doc = created("plan-a", "first\n")
            write("plan-a", "second\n").value()

            val err = service.archive(rootId, DocumentKind.PLAN, "plan-a", expectedVersion = 1).error()

            assertEquals(ErrorCode.VERSION_CONFLICT, err.code)
            assertNull(assertNotNull(service.get(rootId, DocumentKind.PLAN, "plan-a")).archivedAt)
            assertEquals(listOf("document.created", "document.updated"), eventTypes())
            assertEquals(doc.id, documentEvents().first().entityId)
        }

    // ---------------------------------------------------------------- S9 delete

    @Test
    fun `S9 delete removes the document its versions and every link touching it but leaves unrelated links`() =
        runBlocking {
            val doc = created("plan-a", "first\n")
            write("plan-a", "second\n").value()
            val unrelated = created("plan-b", "other\n")
            val somebody = newItem("Somebody")
            val links = provider.linkStore()

            fun link(
                from: EntityRef,
                to: EntityRef,
                relation: LinkRelation
            ) = Link(UUID.randomUUID(), rootId, from, to, null, relation, null, Instant.now())
            links.insert(link(EntityRef(RefKind.DOCUMENT, doc.id), EntityRef(RefKind.ITEM, somebody), LinkRelation.REFERENCES))
            links.insert(link(EntityRef(RefKind.ITEM, somebody), EntityRef(RefKind.DOCUMENT, doc.id), LinkRelation.DERIVED_FROM))
            links.insert(link(EntityRef(RefKind.DOCUMENT, unrelated.id), EntityRef(RefKind.ITEM, somebody), LinkRelation.REFERENCES))

            val deleted = service.delete(rootId, DocumentKind.PLAN, "plan-a").value()

            assertEquals(doc.id, deleted.id)
            assertNull(service.get(rootId, DocumentKind.PLAN, "plan-a"))
            assertEquals(0, versionRows(doc.id).size, "versions go with the document")
            assertEquals(emptyList(), links.findFrom(EntityRef(RefKind.DOCUMENT, doc.id)))
            assertEquals(emptyList(), links.findTo(EntityRef(RefKind.DOCUMENT, doc.id)))
            assertEquals(1, links.findFrom(EntityRef(RefKind.DOCUMENT, unrelated.id)).size, "a link not touching the document stays")
            assertNotNull(service.get(rootId, DocumentKind.PLAN, "plan-b"))
            assertEquals(listOf("document.created", "document.updated", "document.created", "document.deleted"), eventTypes())
            assertEquals(doc.id, documentEvents().last().entityId)
            assertEquals("plan-a", documentEvents().last().str("slug"))
        }

    @Test
    fun `S9 delete of a missing document is not_found`() =
        runBlocking {
            assertEquals(ErrorCode.NOT_FOUND, service.delete(rootId, DocumentKind.PLAN, "nope").error().code)
            assertEquals(emptyList(), eventTypes())
        }

    // ---------------------------------------------------------------- S15 root validation

    @Test
    fun `S15 a write against an unknown root is not_found ROOT and stores nothing`() =
        runBlocking {
            val unknown = UUID.randomUUID()
            val err = service.write(unknown, DocumentKind.PLAN, "plan-a", "x\n").error()
            assertEquals(ErrorCode.NOT_FOUND, err.code)
            assertTrue("ROOT" in assertNotNull(err.detail).toString(), "detail must name the ROOT entity: ${err.detail}")
            assertEquals(0, scalar("SELECT count(*) FROM documents"))
            assertEquals(emptyList(), eventTypes())
        }

    @Test
    fun `S15 a write against a non-root item is invalid_request and stores nothing`() =
        runBlocking {
            val child = newItem("Child", parent = rootId, depth = 1)
            val err = service.write(child, DocumentKind.PLAN, "plan-a", "x\n").error()
            assertEquals(ErrorCode.INVALID_REQUEST, err.code)
            assertEquals(0, scalar("SELECT count(*) FROM documents"))
            assertEquals(emptyList(), eventTypes())
        }

    // ---------------------------------------------------------------- S19 CRLF

    @Test
    fun `S19 a CRLF body is stored with LF and hashed over the LF bytes on create and on overwrite`() =
        runBlocking {
            assertEquals("a\nb\n", DocumentService.normalize("a\r\nb\r\n"))

            val v1 = created("plan-a", "line one\r\nline two\r\n")
            assertEquals("line one\nline two\n", v1.body)
            assertEquals(sha256("line one\nline two\n"), v1.contentHash)
            assertEquals("line one\nline two\n", versionRows(v1.id).single().body)
            assertEquals(sha256("line one\nline two\n"), versionRows(v1.id).single().hash)

            val v2 = write("plan-a", "new one\r\nnew two\r\n").value()
            assertEquals("new one\nnew two\n", v2.body)
            assertEquals(sha256("new one\nnew two\n"), v2.contentHash)
            assertEquals("new one\nnew two\n", assertNotNull(service.get(rootId, DocumentKind.PLAN, "plan-a")).body)
        }

    @Test
    fun `S19 a CRLF body and its LF twin produce the same hash`() =
        runBlocking {
            val crlf = created("crlf", "x\r\ny\r\n")
            val lf = created("lf", "x\ny\n")
            assertEquals(lf.contentHash, crlf.contentHash)
        }

    @Test
    fun `S19 probe - a lone carriage return is kept and a whitespace-only body is stored verbatim`() =
        runBlocking {
            val lone = created("lone-cr", "a\rb\n")
            assertEquals("a\rb\n", lone.body)
            assertEquals(sha256("a\rb\n"), lone.contentHash)

            val blank = created("blank", "  \n\t \n")
            assertEquals("  \n\t \n", blank.body)
            assertEquals(sha256("  \n\t \n"), blank.contentHash)
        }

    // ---------------------------------------------------------------- S20 identity and scoping

    @Test
    fun `S20 a plan and a rule may share a slug and stay independent documents`() =
        runBlocking {
            val plan = created("shared", "plan body\n", DocumentKind.PLAN)
            val rule = created("shared", "rule body\n", DocumentKind.RULE)

            assertNotEquals(plan.id, rule.id)
            assertEquals("plan body\n", assertNotNull(service.get(rootId, DocumentKind.PLAN, "shared")).body)
            assertEquals("rule body\n", assertNotNull(service.get(rootId, DocumentKind.RULE, "shared")).body)
            assertEquals(listOf(plan.id), service.list(rootId, DocumentKind.PLAN).map { it.id })
            assertEquals(listOf(rule.id), service.list(rootId, DocumentKind.RULE).map { it.id })
            assertEquals(setOf(plan.id, rule.id), service.list(rootId).map { it.id }.toSet())
            assertEquals(2, scalar("SELECT count(*) FROM documents"))
        }

    @Test
    fun `S20 the same slug under two roots is two documents`() =
        runBlocking {
            val otherRoot = newItem("Other root")
            val here = created("plan-a", "here\n")
            val there = service.write(otherRoot, DocumentKind.PLAN, "plan-a", "there\n").value()
            assertNotEquals(here.id, there.id)
            assertEquals(1, there.version)
            assertEquals(listOf(here.id), service.list(rootId).map { it.id })
            assertEquals(listOf(there.id), service.list(otherRoot).map { it.id })
        }

    @Test
    fun `S20 deleting the root item cascades to its documents versions and links`() =
        runBlocking {
            val doc = created("plan-a", "first\n")
            write("plan-a", "second\n").value()
            created("rule-a", "rule\n", DocumentKind.RULE)
            service.adopt(rootId, "plan-a", newItem("Adopter")).value()
            assertEquals(1, scalar("SELECT count(*) FROM links"))

            provider.workItemRepository().delete(rootId)

            assertEquals(0, scalar("SELECT count(*) FROM documents"))
            assertEquals(0, scalar("SELECT count(*) FROM document_versions"))
            assertEquals(0, scalar("SELECT count(*) FROM links"))
            assertNull(service.get(rootId, DocumentKind.PLAN, "plan-a"))
            assertEquals(0, versionRows(doc.id).size)
        }

    // ---------------------------------------------------------------- history, list

    @Test
    fun `history lists every version and getVersion returns that version's bytes`() =
        runBlocking {
            val doc = created("plan-a", "one\n")
            write("plan-a", "two\n").value()
            write("plan-a", "three\n").value()

            val history = service.history(doc.id)
            assertEquals(listOf(1, 2, 3), history.map { it.version }.sorted())
            assertEquals(listOf("one\n", "two\n", "three\n"), history.sortedBy { it.version }.map { it.body })
            assertTrue(history.all { it.documentId == doc.id })

            val v2 = assertNotNull(service.getVersion(doc.id, 2))
            assertEquals("two\n", v2.body)
            assertEquals(sha256("two\n"), v2.contentHash)
            assertNull(service.getVersion(doc.id, 4))
            assertNull(service.getVersion(doc.id, 0))
            assertEquals(emptyList(), service.history(UUID.randomUUID()))
        }

    @Test
    fun `list returns summaries for the root only`() =
        runBlocking {
            val a = created("plan-a", "a\n")
            created("plan-b", "b\n")
            val summaries = service.list(rootId, DocumentKind.PLAN)
            assertEquals(listOf("plan-a", "plan-b"), summaries.map { it.slug }.sorted())
            val summaryA = summaries.single { it.slug == "plan-a" }
            assertEquals(a.id, summaryA.id)
            assertEquals(a.contentHash, summaryA.contentHash)
            assertEquals(1, summaryA.version)
            assertEquals(emptyList(), service.list(UUID.randomUUID()))
        }

    // ---------------------------------------------------------------- S21 FTS stays in step with writes

    @Test
    fun `S21 the document FTS indexes follow create overwrite and delete`() =
        runBlocking {
            created("plan-a", "the quagga crossed\n")
            for (fts in listOf("documents_fts_text", "documents_fts_trigram")) {
                assertEquals(1, ftsMatches(fts, "quagga"), "$fts must find the created body")
            }

            write("plan-a", "the okapi crossed\n").value()
            for (fts in listOf("documents_fts_text", "documents_fts_trigram")) {
                assertEquals(0, ftsMatches(fts, "quagga"), "$fts must forget the overwritten body")
                assertEquals(1, ftsMatches(fts, "okapi"), "$fts must find the new body")
            }

            service.delete(rootId, DocumentKind.PLAN, "plan-a").value()
            for (fts in listOf("documents_fts_text", "documents_fts_trigram")) {
                assertEquals(0, ftsMatches(fts, "okapi"), "$fts must forget the deleted document")
                assertEquals(0, scalar("SELECT count(*) FROM ${fts}_docsize"))
            }
        }

    // ---------------------------------------------------------------- PlanSlugs and the PlanDocumentService facade

    @Test
    fun `PlanSlugs maps rule slashes to the rule kind by exact case-sensitive prefix`() {
        assertEquals(KindAndSlug(DocumentKind.RULE, "x"), PlanSlugs.toKindAndSlug("rule/x"))
        assertEquals(KindAndSlug(DocumentKind.RULE, "a/b"), PlanSlugs.toKindAndSlug("rule/a/b"))
        assertEquals(KindAndSlug(DocumentKind.RULE, "rule/x"), PlanSlugs.toKindAndSlug("rule/rule/x"))
        assertEquals(KindAndSlug(DocumentKind.PLAN, "plan-a"), PlanSlugs.toKindAndSlug("plan-a"))
        assertEquals(KindAndSlug(DocumentKind.PLAN, "RULE/x"), PlanSlugs.toKindAndSlug("RULE/x"))
        assertEquals(KindAndSlug(DocumentKind.PLAN, "Rule/x"), PlanSlugs.toKindAndSlug("Rule/x"))
        assertEquals(KindAndSlug(DocumentKind.PLAN, "my-rule/x"), PlanSlugs.toKindAndSlug("my-rule/x"))
        assertEquals(KindAndSlug(DocumentKind.PLAN, "rules/x"), PlanSlugs.toKindAndSlug("rules/x"))
        assertEquals("rule/", PlanSlugs.RULE_PREFIX)
    }

    @Test
    fun `PlanSlugs fromKindAndSlug inverts toKindAndSlug for every slug including the empty rule key`() {
        for (slug in listOf("plan-a", "rule/x", "rule/a/b", "RULE/x", "my-rule/x", "rule/rule/x", "rule/", "rules")) {
            val mapped = PlanSlugs.toKindAndSlug(slug)
            assertEquals(slug, PlanSlugs.fromKindAndSlug(mapped.kind, mapped.slug), "round trip of '$slug'")
        }
        assertEquals("rule/x", PlanSlugs.fromKindAndSlug(DocumentKind.RULE, "x"))
        assertEquals("plan-a", PlanSlugs.fromKindAndSlug(DocumentKind.PLAN, "plan-a"))
    }

    @Test
    fun `S2 the facade stores a rule slash slug as a rule document and a RULE slash slug as a plan`() =
        runBlocking {
            val facade = PlanDocumentService(provider, db.unitOfWork())

            val rule = assertIs<PlanDocumentStashResult.Success>(facade.stash(rootId, "rule/my-rule", "rule body\n"))
            val upper = assertIs<PlanDocumentStashResult.Success>(facade.stash(rootId, "RULE/my-rule", "plan body\n"))

            assertEquals("rule/my-rule", rule.document.slug, "the facade keeps the 3.x slug")
            assertEquals(rule.document.id, assertNotNull(service.get(rootId, DocumentKind.RULE, "my-rule")).id)
            assertNull(service.get(rootId, DocumentKind.PLAN, "rule/my-rule"))
            assertEquals(upper.document.id, assertNotNull(service.get(rootId, DocumentKind.PLAN, "RULE/my-rule")).id)
            assertNull(service.get(rootId, DocumentKind.RULE, "RULE/my-rule"))
            assertEquals(sha256("rule body\n"), rule.document.contentHash)
            assertEquals("rule body\n", assertNotNull(facade.get(rootId, "rule/my-rule")).body)
        }

    @Test
    fun `S10 re-stashing through the facade advances the document version and records created then updated`() =
        runBlocking {
            val facade = PlanDocumentService(provider, db.unitOfWork())
            facade.stash(rootId, "plan-a", "v1\n")
            facade.stash(rootId, "plan-a", "v2\n")

            val doc = assertNotNull(service.get(rootId, DocumentKind.PLAN, "plan-a"))
            assertEquals(2, doc.version)
            assertEquals("v2\n", doc.body)
            assertEquals(listOf("v1\n", "v2\n"), versionRows(doc.id).map { it.body })
            assertEquals(listOf("document.created", "document.updated"), eventTypes())
            assertEquals(1, facade.list(rootId).size)
        }

    @Test
    fun `S10 facade adoption and stash conflict follow the document model`() =
        runBlocking {
            val facade = PlanDocumentService(provider, db.unitOfWork())
            facade.stash(rootId, "plan-a", "v1\n")
            facade.stash(rootId, "plan-b", "b\n")
            val adopter = newItem("Adopter")

            adoptViaFacade(facade, "plan-a", adopter).value()

            val conflict = assertIs<PlanDocumentStashResult.AdoptedConflict>(facade.stash(rootId, "plan-a", "v2\n"))
            assertEquals(adopter, conflict.existing.adoptedByItemId)
            assertEquals("v1\n", assertNotNull(facade.get(rootId, "plan-a")).body)
            assertEquals(listOf("plan-a"), facade.list(rootId, PlanDocumentStatus.ADOPTED).map { it.slug })
            assertEquals(listOf("plan-b"), facade.list(rootId, PlanDocumentStatus.PENDING).map { it.slug })
            assertEquals(ErrorCode.DUPLICATE, adoptViaFacade(facade, "plan-a", newItem("Late")).error().code)
            assertEquals(listOf("document.created", "document.created", "document.adopted"), eventTypes())
        }
}
