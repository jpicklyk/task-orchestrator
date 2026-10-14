package io.github.jpicklyk.mcptask.current.parity

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.TwinResult
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.test.TEST_ITEM_TYPE
import io.github.jpicklyk.mcptask.current.test.rawCount
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Two identically seeded databases, one driven over `POST /api/v1/ops/{tool}` and one through an MCP client, for any tool of
 * the production registry (the generalisation of the advance-only twin, kept private to this suite). Every [call] sends the SAME
 * arguments to both legs under the SAME token, asserts the bodies equal byte for byte once `meta.reqId` is masked, the REST status
 * equal to the catalog status of the body's error code (200 for an ok body), and the two databases still equal row for row.
 */
private class FenceTwin(
    seed: suspend EventLogRig.() -> Unit
) : AutoCloseable {
    private class Leg(
        val db: SqliteTestDatabase,
        val dir: Path,
        val rig: EventLogRig
    ) : AutoCloseable {
        override fun close() {
            runCatching { db.close() }
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    private val registry = ToolRegistry(buildToolSpecs())

    private fun open(seed: suspend EventLogRig.() -> Unit): Leg {
        val db = SqliteTestDatabase.open(clock = PARITY_CLOCK)
        val dir = Files.createTempDirectory("fence-twin-")
        try {
            val rig = EventLogRig.build(db, dir, EventLogRig.GATED_CONFIG, clock = PARITY_CLOCK)
            runBlocking { rig.seed() }
            return Leg(db, dir, rig)
        } catch (e: Throwable) {
            runCatching { db.close() }
            runCatching { dir.toFile().deleteRecursively() }
            throw e
        }
    }

    private val restLeg: Leg = open(seed)
    private val mcpLeg: Leg =
        try {
            open(seed)
        } catch (e: Throwable) {
            restLeg.close()
            throw e
        }

    val rig: EventLogRig get() = mcpLeg.rig

    private val uuidText = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    private fun idsOf(leg: Leg): Set<String> =
        ParityHarness
            .rawTables(leg.db.jdbcUrl)
            .values
            .flatten()
            .flatMap { row -> row.values.flatMap { cell -> uuidsIn(cell) } }
            .toSet()

    /** The dashed lowercase UUIDs a stored cell carries: a dashed UUID inside text, or a whole-cell 32-hex blob (ids are stored as blobs). */
    private fun uuidsIn(cell: Any?): List<String> {
        val text = cell?.toString() ?: return emptyList()
        if (text.length == 32 && text.all { it in "0123456789abcdef" }) {
            return listOf(
                "${text.substring(0, 8)}-${text.substring(8, 12)}-${text.substring(12, 16)}-${text.substring(16, 20)}-${text.substring(20)}"
            )
        }
        return uuidText.findAll(text).map { it.value.lowercase() }.toList()
    }

    /** The ids both seeded databases share by construction (fixed item and edge ids); a generated id (a note id) differs per leg. */
    private val sharedIds: Set<String> = idsOf(restLeg) intersect idsOf(mcpLeg)

    /** [body] with every generated id (not seeded identically on both legs, not named by the call) replaced by a token. */
    private fun unify(
        body: String,
        args: String
    ): String {
        val known = sharedIds + uuidText.findAll(args).map { it.value.lowercase() }
        return uuidText.replace(body) { if (it.value.lowercase() in known) it.value else "<GEN>" }
    }

    override fun close() {
        restLeg.close()
        mcpLeg.close()
    }

    fun count(table: String): Int = rawCount(mcpLeg.db.jdbcUrl, "SELECT COUNT(*) FROM $table")

    fun maxSeq(): Long = runBlocking { mcpLeg.rig.maxSeq() }

    private fun rest(
        token: ParityToken,
        tool: String,
        args: String
    ): Pair<Int, String> {
        var status = 0
        var body = ""
        testApplication {
            application { installParityRestApp(restLeg.rig, parityAuthConfig(token), registry) }
            val response =
                client.post("/api/v1/ops/$tool") {
                    header(HttpHeaders.Authorization, "Bearer ${token.raw}")
                    contentType(ContentType.Application.Json)
                    setBody(args)
                }
            status = response.status.value
            body = response.bodyAsText()
        }
        return status to body
    }

    private fun mask(body: String): String {
        val reqId =
            assertNotNull(
                Json
                    .parseToJsonElement(body)
                    .jsonObject["meta"]
                    ?.jsonObject
                    ?.get("reqId")
                    ?.jsonPrimitive
                    ?.content,
                "body carries meta.reqId: $body"
            )
        return body.replace("\"reqId\":\"$reqId\"", "\"reqId\":\"<REQ>\"")
    }

    private fun comparable(snapshot: Map<String, List<Map<String, Any?>>>): Map<String, List<Map<String, Any?>>> =
        snapshot.mapValues { (_, rows) ->
            rows.map { row ->
                // a note or dependency event names the generated row id, which differs between two seeded databases
                if (row["entity_kind"] == "note" || row["entity_kind"] == "dependency") {
                    row - "host" - "entity_id"
                } else {
                    row - "host"
                }
            }
        }

    /** Sends [args] to [tool] on both legs under [token] and returns the (equal) outcome. [member] picks the MCP profile. */
    fun call(
        token: ParityToken,
        tool: String,
        member: String,
        args: String
    ): TwinResult {
        val (status, restBody) = rest(token, tool, args)
        val profile = checkNotNull(ParityCoverage.mcpLegProfile(registry, tool, member)) { "$tool.$member has no MCP leg" }
        val mcp =
            runBlocking {
                callMcp(
                    mcpLeg.rig.composition.toolContext,
                    registry,
                    { parityAccess(token) },
                    tool,
                    Json.parseToJsonElement(args).jsonObject,
                    profile
                )
            }
        val restNorm = unify(mask(restBody), args)
        val mcpNorm = unify(mask(mcp.structured.toString()), args)
        assertEquals(mcpNorm, restNorm, "body parity between MCP and REST for $args")

        val parsed = Json.parseToJsonElement(restNorm).jsonObject
        val ok =
            parsed
                .getValue("ok")
                .jsonPrimitive.content
                .toBoolean()
        assertEquals(!ok, mcp.isError, "MCP isError is the negation of ok")
        val code =
            parsed["error"]
                ?.jsonObject
                ?.get("code")
                ?.jsonPrimitive
                ?.content
        if (code == null) {
            assertEquals(200, status, "an ok body is 200")
        } else {
            assertEquals(ErrorCode.entries.single { it.wire == code }.httpStatus, status, "REST status equals the catalog status of $code")
        }
        val restSnapshot = comparable(ParityHarness.snapshot(restLeg.db.jdbcUrl))
        val mcpSnapshot = comparable(ParityHarness.snapshot(mcpLeg.db.jdbcUrl))
        for (table in restSnapshot.keys) {
            assertEquals(restSnapshot.getValue(table), mcpSnapshot.getValue(table), "$table rows equal across the two legs after $args")
        }
        return TwinResult(status, parsed)
    }
}

/**
 * Independent tests (test-author seat, item 46a83aca) that the write fence is the same on both surfaces: scenarios S2, S3 and S4
 * of the frozen test-plan over `POST /api/v1/ops/{tool}` and MCP, the atomic-batch rollback and replay probes, and the holder
 * identity consistency between a real `advance claim` and a later write.
 *
 * Oracles (frozen, planning phase): AC1 (the notes/items/dependency write ops naming an item with an active claim by another
 * principal answer `not_claim_holder{itemId}`, write nothing and record no event; the holder, a lapsed claim and an unclaimed item
 * proceed), decisions D3/D4/D5/D10 (see FencedWriteServicesTest), and the pre-existing envelope rules: a batch with a failing
 * element is 207 `partial_failure` with the element's `error`; `atomic: true` makes the failing element the whole-call error with
 * `error.index` and the catalog status of its code (403 for not_claim_holder) and rolls the whole call back. Over HTTP/MCP every
 * caller is a distinct bearer principal (decision D10), audited and compared as `api:<tokenId>`.
 *
 * All cells are NEW-SURFACE. Claim fixtures are seeded with explicit instants against the fixed parity clock: active until the year
 * 2099, "E - 1 ms" (expires one millisecond after the clock), and "E" (expires exactly at the clock, therefore lapsed).
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class FencedWriteParityTest {
    private val tokenA = ParityToken("fence-a", setOf(ApiCapability.ADMIN))
    private val tokenB = ParityToken("fence-b", setOf(ApiCapability.ADMIN))
    private val holderB = "api:fence-b"
    private val far: Instant = Instant.parse("2099-01-01T00:00:00Z")

    private fun uid(n: Int): UUID = UUID.fromString("f%07d-0000-4000-8000-%012d".format(n, n))

    private val held = uid(1)
    private val free = uid(2)
    private val lapsedAtE = uid(3)
    private val almostLapsed = uid(4)
    private val parentOfHeld = uid(5)
    private val heldChild = uid(6)
    private val other = uid(7)
    private val another = uid(8)

    private suspend fun EventLogRig.addItem(
        id: UUID,
        title: String,
        parent: WorkItem? = null,
        role: Role = Role.QUEUE,
        heldBy: String? = null,
        expiresAt: Instant = far
    ): WorkItem {
        val claimedAt = if (heldBy != null) PARITY_NOW.minusSeconds(60) else null
        val item =
            WorkItem(
                id = id,
                title = title,
                role = role,
                type = TEST_ITEM_TYPE,
                parentId = parent?.id,
                depth = (parent?.depth ?: -1) + 1,
                rootId = parent?.let { it.rootId ?: it.id } ?: id,
                createdAt = PARITY_NOW,
                claimedBy = heldBy,
                claimedAt = claimedAt,
                claimExpiresAt = if (heldBy != null) expiresAt else null,
                originalClaimedAt = claimedAt
            )
        raw.workItemRepository().create(item)
        return item
    }

    private suspend fun EventLogRig.edge(
        id: UUID,
        from: UUID,
        to: UUID
    ) {
        raw.dependencyRepository().create(Dependency(id = id, fromItemId = from, toItemId = to, createdAt = PARITY_NOW))
    }

    private val seedAll: suspend EventLogRig.() -> Unit = {
        addItem(held, "Held by B", heldBy = holderB)
        addItem(free, "Free")
        addItem(lapsedAtE, "Lapsed at E", heldBy = holderB, expiresAt = PARITY_NOW)
        addItem(almostLapsed, "E minus 1 ms", heldBy = holderB, expiresAt = PARITY_NOW.plusMillis(1))
        val p = addItem(parentOfHeld, "Parent of a held child")
        addItem(heldChild, "Held child", parent = p, heldBy = holderB)
        addItem(other, "Other")
        addItem(another, "Another")
    }

    private fun upsert(
        item: UUID,
        key: String = "k"
    ) = """{"op":"upsert","itemId":"$item","key":"$key","role":"queue","body":"b"}"""

    private fun ops(
        vararg elements: String,
        top: String = ""
    ) = """{"ops":[${elements.joinToString(",")}]$top}"""

    private fun itemOnly(id: UUID): JsonObject = buildJsonObject { put("itemId", id.toString()) }

    private fun TwinResult.assertFenced(
        item: UUID,
        i: Int = 0
    ) {
        assertEquals(207, status, "a batch with a refused element is 207 partial_failure: $body")
        assertEquals("not_claim_holder", elementCode(i), "element $i: $body")
        assertEquals(itemOnly(item), elementDetail(i), "the detail names the item and nothing else: $body")
        assertFalse("fence-b" in body.toString(), "the holder is never echoed: $body")
        assertFalse("fence-a" in body.toString(), "the caller is never echoed: $body")
    }

    // ------------------------------------------------------------------ S2 notes

    @Test
    fun `S2 notes_write upsert on a held item is 403-class not_claim_holder on both surfaces, writes nothing and records no event`() {
        FenceTwin(seedAll).use { twin ->
            val seq = twin.maxSeq()

            val refused = twin.call(tokenA, "notes_write", "upsert", ops(upsert(held)))

            refused.assertFenced(held)
            assertEquals(0, twin.count("notes"), "the refused upsert stored no note")
            assertEquals(seq, twin.maxSeq(), "the refused upsert recorded no event row")

            val holder = twin.call(tokenB, "notes_write", "upsert", ops(upsert(held)))
            assertEquals(200, holder.status, "control: the holder's identical upsert is accepted: ${holder.body}")
            assertEquals(1, twin.count("notes"))
        }
    }

    @Test
    fun `S2 notes_write upsert proceeds on an unclaimed item, on a claim expiring exactly now and is refused one millisecond before`() {
        FenceTwin(seedAll).use { twin ->
            assertEquals(200, twin.call(tokenA, "notes_write", "upsert", ops(upsert(free))).status, "unclaimed")
            assertEquals(200, twin.call(tokenA, "notes_write", "upsert", ops(upsert(lapsedAtE))).status, "expiresAt == now is lapsed")
            twin.call(tokenA, "notes_write", "upsert", ops(upsert(almostLapsed))).assertFenced(almostLapsed)
            assertEquals(2, twin.count("notes"))
        }
    }

    @Test
    fun `S2 notes_write delete on a held item is refused and the note survives`() {
        FenceTwin {
            seedAll()
            val item = raw.workItemRepository().getById(held)!!
            raw.noteRepository().upsert(Note(itemId = item.id, key = "k", role = "queue", body = "kept"))
        }.use { twin ->
            val refused = twin.call(tokenA, "notes_write", "delete", ops("""{"op":"delete","itemId":"$held","key":"k"}"""))

            refused.assertFenced(held)
            assertEquals(1, twin.count("notes"), "the note survives")

            val holder = twin.call(tokenB, "notes_write", "delete", ops("""{"op":"delete","itemId":"$held","key":"k"}"""))
            assertEquals(200, holder.status, "control: the holder may delete: ${holder.body}")
            assertEquals(0, twin.count("notes"))
        }
    }

    @Test
    fun `probe an atomic notes batch with one refused element rolls the whole call back and reports the refused index`() {
        FenceTwin(seedAll).use { twin ->
            val seq = twin.maxSeq()

            val result = twin.call(tokenA, "notes_write", "upsert", ops(upsert(free), upsert(held), top = ""","atomic":true"""))

            assertEquals(403, result.status, "atomic: the refused element is the whole-call error with its catalog status: ${result.body}")
            assertEquals("not_claim_holder", result.code)
            assertTrue("\"index\":1" in result.body.toString(), "the refused element's index is reported: ${result.body}")
            assertEquals(0, twin.count("notes"), "the unfenced first element was rolled back")
            assertEquals(seq, twin.maxSeq(), "no event row survives the rollback")
        }
    }

    @Test
    fun `probe replaying the same refused write repeats the refusal and still writes nothing`() {
        FenceTwin(seedAll).use { twin ->
            val seq = twin.maxSeq()

            repeat(2) { twin.call(tokenA, "notes_write", "upsert", ops(upsert(held))).assertFenced(held) }

            assertEquals(0, twin.count("notes"))
            assertEquals(seq, twin.maxSeq())
        }
    }

    // ------------------------------------------------------------------ S3 items

    @Test
    fun `S3 items_write update and delete of a held item are refused, the row is unchanged and no event is recorded`() {
        FenceTwin(seedAll).use { twin ->
            val seq = twin.maxSeq()
            val rows = twin.count("work_items")

            twin.call(tokenA, "items_write", "update", ops("""{"op":"update","id":"$held","title":"renamed"}""")).assertFenced(held)
            twin.call(tokenA, "items_write", "delete", ops("""{"op":"delete","id":"$held"}""")).assertFenced(held)

            assertEquals(rows, twin.count("work_items"), "nothing deleted")
            assertEquals(seq, twin.maxSeq(), "no event row")
            assertEquals(0, rawCount(twin.rig.db.jdbcUrl, "SELECT COUNT(*) FROM work_items WHERE title = 'renamed'"), "nothing renamed")

            val holder = twin.call(tokenB, "items_write", "update", ops("""{"op":"update","id":"$held","title":"renamed"}"""))
            assertEquals(200, holder.status, "control: the holder's update is accepted: ${holder.body}")
            assertEquals(1, rawCount(twin.rig.db.jdbcUrl, "SELECT COUNT(*) FROM work_items WHERE title = 'renamed'"))
        }
    }

    @Test
    fun `S3 items_write recursive delete of an unheld parent over a held descendant reports the descendant and deletes nothing`() {
        FenceTwin(seedAll).use { twin ->
            val rows = twin.count("work_items")

            twin
                .call(tokenA, "items_write", "delete", ops("""{"op":"delete","id":"$parentOfHeld","recursive":true}"""))
                .assertFenced(heldChild)

            assertEquals(rows, twin.count("work_items"), "nothing in the subtree was deleted")

            val holder = twin.call(tokenB, "items_write", "delete", ops("""{"op":"delete","id":"$parentOfHeld","recursive":true}"""))
            assertEquals(200, holder.status, "control: the descendant's holder deletes the subtree: ${holder.body}")
            assertEquals(rows - 2, twin.count("work_items"))
        }
    }

    @Test
    fun `S3 items_write update proceeds on an unclaimed item and on a claim expiring exactly now`() {
        FenceTwin(seedAll).use { twin ->
            assertEquals(200, twin.call(tokenA, "items_write", "update", ops("""{"op":"update","id":"$free","title":"one"}""")).status)
            assertEquals(
                200,
                twin.call(tokenA, "items_write", "update", ops("""{"op":"update","id":"$lapsedAtE","title":"two"}""")).status,
                "expiresAt == now is lapsed"
            )
            twin
                .call(tokenA, "items_write", "update", ops("""{"op":"update","id":"$almostLapsed","title":"three"}"""))
                .assertFenced(almostLapsed)
        }
    }

    // ------------------------------------------------------------------ S4 dependencies

    private val d1 = UUID.fromString("de100000-0000-4000-8000-000000000001")
    private val d2 = UUID.fromString("de200000-0000-4000-8000-000000000002")
    private val d3 = UUID.fromString("de300000-0000-4000-8000-000000000003")

    private val seedEdges: suspend EventLogRig.() -> Unit = {
        seedAll()
        edge(d1, other, held)
        edge(d2, free, another)
        edge(d3, held, another)
    }

    @Test
    fun `S4 deps_write create linear fan_out and fan_in naming a held item are refused and store nothing`() {
        FenceTwin(seedAll).use { twin ->
            val seq = twin.maxSeq()

            twin.call(tokenA, "deps_write", "create", ops("""{"op":"create","from":"$free","to":"$held"}""")).assertFenced(held)
            twin.call(tokenA, "deps_write", "create", ops("""{"op":"create","from":"$held","to":"$free"}""")).assertFenced(held)
            twin.call(tokenA, "deps_write", "linear", ops("""{"op":"linear","ids":["$free","$other","$held"]}""")).assertFenced(held)
            twin
                .call(
                    tokenA,
                    "deps_write",
                    "fan_out",
                    ops("""{"op":"fan_out","from":"$free","toIds":["$other","$held"]}""")
                ).assertFenced(held)
            twin
                .call(
                    tokenA,
                    "deps_write",
                    "fan_in",
                    ops("""{"op":"fan_in","fromIds":["$other","$held"],"to":"$free"}""")
                ).assertFenced(held)

            assertEquals(0, twin.count("dependencies"))
            assertEquals(seq, twin.maxSeq(), "no event row")

            val holder = twin.call(tokenB, "deps_write", "create", ops("""{"op":"create","from":"$free","to":"$held"}"""))
            assertEquals(200, holder.status, "control: the holder may create an edge naming its item: ${holder.body}")
            assertEquals(1, twin.count("dependencies"))
        }
    }

    @Test
    fun `S4 deps_write delete by from and to, by item and by edge id is fenced by the named or stored endpoints`() {
        FenceTwin(seedEdges).use { twin ->
            val rows = twin.count("dependencies")

            twin
                .call(tokenA, "deps_write", "delete", ops("""{"op":"delete","from":"$other","to":"$held"}"""))
                .assertFenced(held)
            twin.call(tokenA, "deps_write", "delete", ops("""{"op":"delete","item":"$held"}""")).assertFenced(held)
            twin.call(tokenA, "deps_write", "delete", ops("""{"op":"delete","id":"$d1"}""")).assertFenced(held)
            twin.call(tokenA, "deps_write", "delete", ops("""{"op":"delete","id":"$d3"}""")).assertFenced(held)
            assertEquals(rows, twin.count("dependencies"), "no refused delete removed an edge")

            val unfenced = twin.call(tokenA, "deps_write", "delete", ops("""{"op":"delete","id":"$d2"}"""))
            assertEquals(200, unfenced.status, "control: an edge between unheld items is deleted: ${unfenced.body}")
            assertEquals(rows - 1, twin.count("dependencies"))
        }
    }

    @Test
    fun `S4 deps_write delete by item fences only the named item - an unheld item whose counterparty is held proceeds`() {
        FenceTwin(seedEdges).use { twin ->
            val result = twin.call(tokenA, "deps_write", "delete", ops("""{"op":"delete","item":"$other"}"""))

            assertEquals(200, result.status, "the held counterparty of $other's edge does not fence the delete: ${result.body}")
            assertEquals(2, twin.count("dependencies"), "the edge other->held is gone")
        }
    }

    @Test
    fun `S4 deps_write on a claim expiring exactly now and on unclaimed items proceeds, one millisecond before it is refused`() {
        FenceTwin(seedAll).use { twin ->
            assertEquals(200, twin.call(tokenA, "deps_write", "create", ops("""{"op":"create","from":"$free","to":"$lapsedAtE"}""")).status)
            twin
                .call(tokenA, "deps_write", "create", ops("""{"op":"create","from":"$free","to":"$almostLapsed"}"""))
                .assertFenced(almostLapsed)
            assertEquals(1, twin.count("dependencies"))
        }
    }

    // ------------------------------------------------------------------ holder identity: a real claim, then writes

    @Test
    fun `S2 a real advance claim by one bearer principal fences the other on notes_write and admits the claimant`() {
        FenceTwin(seedAll).use { twin ->
            val claim = twin.call(tokenB, "advance", "claim", ops("""{"op":"claim","itemId":"$free"}"""))
            assertEquals(200, claim.status, "fixture: B claims the free item: ${claim.body}")

            twin.call(tokenA, "notes_write", "upsert", ops(upsert(free))).assertFenced(free)
            assertEquals(0, twin.count("notes"))

            val holder = twin.call(tokenB, "notes_write", "upsert", ops(upsert(free)))
            assertEquals(200, holder.status, "the claimant is recognised as the holder: ${holder.body}")
            assertEquals(1, twin.count("notes"))
        }
    }
}
