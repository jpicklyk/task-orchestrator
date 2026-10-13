package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.AdvanceParityScenarios
import io.github.jpicklyk.mcptask.current.parity.PARITY_NOW
import io.github.jpicklyk.mcptask.current.parity.ParityHarness
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.ProductionParity
import io.github.jpicklyk.mcptask.current.parity.callMcp
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.parity.parityAuthConfig
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.putNote
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.ItemContextProbe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 2cd17c00) for the `advance` tool: the four ops (transition, claim, release,
 * heartbeat) over MCP and `POST /api/v1/ops/advance`, scenarios S1 to S5 and S8 to S20 of the frozen test-plan.
 *
 * Oracles: task-scope TS1 (request and element shapes, error mapping, ownership rule D2, scope D4/D9, no-match D5), TS2 and TS3
 * (resource_unavailable names real resources; claim_held carries a non-null expiresAt), TS4 (U19 `violations`), the envelope
 * spec `plans/v4-response-envelope.md` sections 2 to 4 and 8 (batch results, per-element errors, `atomic`, the catalog detail
 * shapes, the status per code) and the config format in `config-format.md` (seats, `independence:`, trait `resources:`). Every
 * expected value below is written from those clauses; none is read from a run.
 *
 * Every twin call runs the SAME arguments over both surfaces and asserts byte-equal bodies and equal databases ([AdvanceTwin]).
 * The seeded items use fixed ids so the two legs agree; a claim held by "another agent" is seeded directly with an expiry far in
 * the future (2099) so it is live under any clock.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class AdvanceToolTest {
    private val agent = AdvanceParityScenarios.agent
    private val reader = AdvanceParityScenarios.reader
    private val other = ParityToken("adv-other", setOf(ApiCapability.READ, ApiCapability.ADVANCE))

    private val farFuture: Instant = Instant.parse("2099-01-01T00:00:00Z")

    // ------------------------------------------------------------------ fixtures

    private fun uid(n: Int): UUID = UUID.fromString("b%07d-0000-4000-8000-%012d".format(n, n))

    private suspend fun EventLogRig.addItem(
        id: UUID,
        title: String,
        parent: WorkItem? = null,
        role: Role = Role.QUEUE,
        type: String? = null,
        priority: Priority = Priority.MEDIUM,
        createdAt: Instant = PARITY_NOW,
        claimedBy: String? = null
    ): WorkItem {
        val claimedAt = if (claimedBy != null) farFuture.minusSeconds(60) else null
        val item =
            WorkItem(
                id = id,
                title = title,
                role = role,
                priority = priority,
                type = type,
                parentId = parent?.id,
                depth = (parent?.depth ?: -1) + 1,
                rootId = parent?.let { it.rootId ?: it.id },
                createdAt = createdAt,
                claimedBy = claimedBy,
                claimedAt = claimedAt,
                claimExpiresAt = if (claimedBy != null) farFuture else null,
                originalClaimedAt = claimedAt
            )
        raw.workItemRepository().create(item)
        return item
    }

    private fun AdvanceTwin.load(id: UUID): WorkItem = runBlocking { checkNotNull(rig.raw.workItemRepository().getById(id)) { "item $id" } }

    private fun transition(
        id: Any,
        trigger: String,
        extra: String = ""
    ) = """{"op":"transition","itemId":"$id","trigger":"$trigger"$extra}"""

    private fun claim(
        id: Any,
        extra: String = ""
    ) = """{"op":"claim","itemId":"$id"$extra}"""

    private fun claimSelect(select: String) = """{"op":"claim","select":$select}"""

    private fun release(id: Any) = """{"op":"release","itemId":"$id"}"""

    private fun heartbeat(
        id: Any,
        extra: String = ""
    ) = """{"op":"heartbeat","itemId":"$id"$extra}"""

    private fun ops(
        vararg elements: String,
        top: String = ""
    ) = """{"ops":[${elements.joinToString(",")}]$top}"""

    private val atomic = ""","atomic":true"""

    private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject

    private fun JsonObject.arr(name: String): JsonArray = getValue(name).jsonArray

    private fun JsonObject.instant(name: String): Instant = Instant.parse(str(name))

    // ------------------------------------------------------------------ the production parity catalog

    @Test
    fun `parity - every advance scenario of the production catalog agrees across MCP and REST`() {
        val harness = ParityHarness(ToolRegistry(listOf(advanceTool())))
        val scenarios = ProductionParity.scenarios.filter { it.tool == ADVANCE_TOOL }
        assertEquals(
            setOf("transition", "claim", "release", "heartbeat"),
            scenarios.map { it.member }.toSet(),
            "the catalog covers all four ops"
        )
        val failures = mutableListOf<String>()
        for (s in scenarios) {
            try {
                harness.run(s)
            } catch (e: Throwable) {
                failures += "${s.name}: ${e.message}"
            }
        }
        assertTrue(failures.isEmpty(), "failing parity scenarios:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `registry - advance is registered with exactly the four ops all gated by the advance capability`() {
        val spec = advanceTool()
        assertEquals("advance", ADVANCE_TOOL)
        assertEquals(ADVANCE_TOOL, spec.name)
        assertEquals(listOf("transition", "claim", "release", "heartbeat"), spec.ops.map { it.name })
        assertEquals(setOf(ApiCapability.ADVANCE), spec.ops.map { it.capability }.toSet())
        assertTrue(buildToolSpecs().any { it.name == ADVANCE_TOOL }, "buildToolSpecs registers advance")
    }

    // ------------------------------------------------------------------ S1 / S2 transition happy paths

    @Test
    fun `S1 transition start moves a queue item to work and answers the documented element over both surfaces`() {
        val item = uid(1)
        AdvanceTwin(seed = { addItem(item, "Queued") }).use { twin ->
            val result = twin.call(agent, ops(transition(item, "start")))

            assertEquals(200, result.status)
            assertTrue(result.isOk)
            val element = result.element(0)
            val documentedOrder =
                listOf(
                    "ok",
                    "itemId",
                    "previousRole",
                    "newRole",
                    "trigger",
                    "statusLabel",
                    "cascades",
                    "unblocked",
                    "expectedNotes",
                    "guidanceKey",
                    "skillPointer",
                    "dispatch",
                    "violations"
                )
            val keys = element.keys.toList()
            assertEquals(keys.sortedBy { documentedOrder.indexOf(it) }, keys, "members follow the documented order: $keys")
            assertTrue(keys.all { it in documentedOrder }, "no undocumented member: $keys")
            assertEquals(listOf("ok", "itemId", "previousRole", "newRole", "trigger"), keys.take(5))
            for (empty in listOf(
                "cascades",
                "unblocked",
                "violations"
            )) {
                assertFalse(empty in element, "$empty is empty here, so it is omitted")
            }
            assertEquals(item.toString(), element.str("itemId"))
            assertEquals("queue", element.str("previousRole"))
            assertEquals("work", element.str("newRole"))
            assertEquals("start", element.str("trigger"))
            assertEquals(Role.WORK, twin.load(item).role)
            assertEquals("""{"ok":1,"failed":0}""", result.data.getValue("summary").toString())
        }
    }

    @Test
    fun `S2 completing the last child auto-completes its parent and reports the unblocked dependent`() {
        val parent = uid(20)
        val child = uid(21)
        val dependent = uid(22)
        AdvanceTwin(
            seed = {
                val p = addItem(parent, "Parent", role = Role.WORK)
                addItem(child, "Child", parent = p, role = Role.WORK)
                addItem(dependent, "Dependent")
                raw.dependencyRepository().create(Dependency(fromItemId = child, toItemId = dependent, type = DependencyType.BLOCKS))
            }
        ).use { twin ->
            val result = twin.call(agent, ops(transition(child, "complete")))

            assertEquals(200, result.status)
            val element = result.element(0)
            assertEquals("work", element.str("previousRole"))
            assertEquals("terminal", element.str("newRole"))
            val cascade = element.arr("cascades").single().jsonObject
            assertEquals(parent.toString(), cascade.str("itemId"))
            assertEquals("work", cascade.str("previousRole"))
            assertEquals("terminal", cascade.str("targetRole"))
            assertEquals("true", cascade.str("applied"))
            val unblocked = element.arr("unblocked").single().jsonObject
            assertEquals(dependent.toString(), unblocked.str("itemId"))
            assertEquals("Dependent", unblocked.str("title"))
            assertEquals(Role.TERMINAL, twin.load(parent).role)
            assertEquals(Role.TERMINAL, twin.load(child).role)
        }
    }

    // ------------------------------------------------------------------ S3 / S4 / S5 claim, release, heartbeat

    @Test
    fun `S3 claiming by id holds the item for 900 seconds and the holder re-claim is a refresh that keeps the original claim time`() {
        val item = uid(30)
        AdvanceTwin(seed = { addItem(item, "Claimable") }).use { twin ->
            val first = twin.call(agent, ops(claim(item))).element(0)

            assertEquals("true", first.str("claimed"))
            assertEquals(item.toString(), first.str("itemId"))
            assertEquals(PARITY_NOW.plusSeconds(900), first.instant("expiresAt"), "default ttl is 900 seconds from the (fixed) clock")
            assertEquals(first.instant("claimedAt"), first.instant("originalClaimedAt"))
            assertFalse("refreshed" in first, "a first claim is not a refresh")
            assertFalse("selected" in first, "an id-mode claim is not a selection")
            assertEquals(
                "api:" + agent.id,
                twin.load(item).claimedBy,
                "an API caller is attributed as api:<tokenId> (api-rest.md actor rule)"
            )

            val again = twin.call(agent, ops(claim(item, ""","ttlSeconds":1800"""))).element(0)
            assertEquals("true", again.str("claimed"))
            assertEquals("true", again.str("refreshed"))
            assertEquals(PARITY_NOW.plusSeconds(1800), again.instant("expiresAt"))
            assertEquals(first.instant("originalClaimedAt"), again.instant("originalClaimedAt"))
        }
    }

    @Test
    fun `S3 claimRef is echoed and the ttl bounds 1 and 86400 are accepted`() {
        val a = uid(31)
        val b = uid(32)
        AdvanceTwin(
            seed = {
                addItem(a, "A")
                addItem(b, "B")
            }
        ).use { twin ->
            val low = twin.call(agent, ops(claim(a, ""","ttlSeconds":1,"claimRef":"ref-1""""))).element(0)
            assertEquals("ref-1", low.str("claimRef"))
            assertEquals(PARITY_NOW.plusSeconds(1), low.instant("expiresAt"))
            val high = twin.call(agent, ops(claim(b, ""","ttlSeconds":86400"""))).element(0)
            assertEquals(PARITY_NOW.plusSeconds(86400), high.instant("expiresAt"))
        }
    }

    @Test
    fun `S4 find-and-claim by priority takes the highest priority eligible child`() {
        val parent = uid(40)
        val low = uid(41)
        val high = uid(42)
        val medium = uid(43)
        AdvanceTwin(
            seed = {
                val p = addItem(parent, "Parent", role = Role.WORK)
                addItem(low, "Low", parent = p, priority = Priority.LOW, createdAt = PARITY_NOW.minusSeconds(3 * 86400))
                addItem(high, "High", parent = p, priority = Priority.HIGH, createdAt = PARITY_NOW.minusSeconds(1 * 86400))
                addItem(medium, "Medium", parent = p, priority = Priority.MEDIUM, createdAt = PARITY_NOW.minusSeconds(2 * 86400))
            }
        ).use { twin ->
            val element = twin.call(agent, ops(claimSelect("""{"parentId":"$parent","orderBy":"priority"}"""))).element(0)

            assertEquals(high.toString(), element.str("itemId"))
            assertEquals("true", element.str("claimed"))
            assertEquals("true", element.str("selected"))
            assertEquals("api:" + agent.id, twin.load(high).claimedBy)
            assertNull(twin.load(low).claimedBy)
            assertNull(twin.load(medium).claimedBy)
        }
    }

    @Test
    fun `S4 orderBy oldest and newest rank by creation time regardless of priority`() {
        val parent = uid(44)
        val oldest = uid(45)
        val newest = uid(46)
        val seed: suspend EventLogRig.() -> Unit = {
            val p = addItem(parent, "Parent", role = Role.WORK)
            addItem(oldest, "Oldest", parent = p, priority = Priority.LOW, createdAt = PARITY_NOW.minusSeconds(3 * 86400))
            addItem(newest, "Newest", parent = p, priority = Priority.HIGH, createdAt = PARITY_NOW.minusSeconds(86400))
        }
        AdvanceTwin(seed = seed).use { twin ->
            val element = twin.call(agent, ops(claimSelect("""{"parentId":"$parent","orderBy":"oldest"}"""))).element(0)
            assertEquals(oldest.toString(), element.str("itemId"))
        }
        AdvanceTwin(seed = seed).use { twin ->
            val element = twin.call(agent, ops(claimSelect("""{"parentId":"$parent","orderBy":"newest"}"""))).element(0)
            assertEquals(newest.toString(), element.str("itemId"))
        }
    }

    @Test
    fun `S5 the holder releases and heartbeats its claim and a released item can be claimed by another agent`() {
        val item = uid(50)
        AdvanceTwin(seed = { addItem(item, "Held") }).use { twin ->
            twin.call(agent, ops(claim(item)))

            val beat = twin.call(agent, ops(heartbeat(item, ""","ttlSeconds":1800"""))).element(0)
            assertEquals("true", beat.str("claimed"))
            assertEquals("true", beat.str("refreshed"))
            assertEquals(PARITY_NOW.plusSeconds(1800), beat.instant("expiresAt"), "the heartbeat extends the claim to the new ttl")
            assertEquals(item.toString(), beat.str("itemId"))
            assertTrue("originalClaimedAt" in beat)

            val released = twin.call(agent, ops(release(item))).element(0)
            assertEquals("true", released.str("released"))
            assertEquals(item.toString(), released.str("itemId"))
            assertNull(twin.load(item).claimedBy, "the claim is gone")

            val taken = twin.call(other, ops(claim(item))).element(0)
            assertEquals("true", taken.str("claimed"))
            assertFalse("refreshed" in taken)

            val lateBeat = twin.call(agent, ops(heartbeat(item)))
            assertEquals("not_claim_holder", lateBeat.elementCode(0), "the former holder can no longer heartbeat")
        }
    }

    // ------------------------------------------------------------------ S8 / S9 / S19 gates

    private val seatConfig =
        """
work_item_schemas:
  seat-gated:
    seats:
      - { name: planner, phase: queue, enters: true }
    notes:
      - key: diagnosis
        role: queue
        required: true
        seat: planner
      - key: task-scope
        role: queue
        required: true
        seat: planner
"""

    @Test
    fun `S8 a start with unfilled required notes is gate_blocked with the missing notes by seat and no violations member`() {
        val gated = uid(80)
        AdvanceTwin(configYaml = seatConfig, seed = { addItem(gated, "Gated", type = "seat-gated") }).use { twin ->
            val result = twin.call(agent, ops(transition(gated, "start")))

            assertEquals(207, result.status)
            assertEquals("partial_failure", result.code)
            assertEquals("gate_blocked", result.elementCode(0))
            val detail = result.elementDetail(0)
            assertEquals(gated.toString(), detail.str("itemId"))
            assertEquals("queue", detail.str("role"))
            val missing = detail.arr("missing").map { it.jsonObject }
            assertEquals(listOf("diagnosis", "task-scope"), missing.map { it.str("key") })
            for (m in missing) {
                assertEquals("queue", m.str("role"))
                assertEquals("planner", m.str("seat"))
            }
            assertFalse("violations" in detail, "a note-only block carries no violations member: $detail")
            assertEquals(Role.QUEUE, twin.load(gated).role, "a blocked start changes nothing")

            val context =
                runBlocking { twin.rig.callOk(ItemContextProbe(), "itemId" to JsonPrimitive(gated.toString())) }
                    .obj("data")
                    .obj("gateStatus")
            val bySeat = context.obj("missingBySeat").mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }
            val grouped = missing.groupBy({ it.str("seat") }, { it.str("key") })
            assertEquals(bySeat, grouped, "missing[].seat agrees with get_context missingBySeat")
        }
    }

    @Test
    fun `S19 an atomic batch rolls the earlier transition back and reports the failing element index`() {
        val ok = uid(81)
        val gated = uid(82)
        AdvanceTwin(
            configYaml = seatConfig,
            seed = {
                addItem(ok, "Plain")
                addItem(gated, "Gated", type = "seat-gated")
            }
        ).use { twin ->
            val result = twin.call(agent, ops(transition(ok, "start"), transition(gated, "start"), top = atomic))

            assertEquals(409, result.status)
            assertEquals("gate_blocked", result.code)
            assertEquals("1", result.error.str("index"))
            assertFalse("data" in result.body, "an atomic failure carries no data")
            assertEquals(Role.QUEUE, twin.load(ok).role, "the first transition is rolled back")
            assertEquals(Role.QUEUE, twin.load(gated).role)

            val loose = twin.call(agent, ops(transition(ok, "start"), transition(gated, "start")))
            assertEquals(207, loose.status, "control: without atomic the same batch keeps the first transition")
            assertEquals(Role.WORK, twin.load(ok).role)
        }
    }

    private val independenceSchema =
        """
work_item_schemas:
  indep-gate:
    seats:
      - { name: implementer, phase: work, enters: true }
      - { name: test-author, phase: work }
      - { name: reviewer, phase: review }
    notes:
      - { key: implementation-notes, role: work, required: true, seat: implementer }
      - { key: test-manifest, role: work, required: true, seat: test-author, independent_of: [implementer] }
      - { key: review-checklist, role: review, required: true, seat: reviewer }
"""

    private fun independenceConfig(mode: String): String = "independence:\n  mode: \"$mode\"\n$independenceSchema"

    private val sentinel = "sentinel-actor-Z9"

    private suspend fun EventLogRig.note(
        item: UUID,
        key: String,
        actorId: String
    ) {
        ctx.putNote(item, key, "work", "filled $key", actor = ActorClaim(actorId, ActorKind.SUBAGENT))
    }

    private fun independenceSeed(
        same: UUID,
        distinct: UUID
    ): suspend EventLogRig.() -> Unit =
        {
            val root = addItem(uid(90), "Independence root", role = Role.WORK, type = "project")
            addItem(same, "Same actor", parent = root, role = Role.WORK, type = "indep-gate")
            note(same, "implementation-notes", sentinel)
            note(same, "test-manifest", sentinel)
            addItem(distinct, "Distinct actors", parent = root, role = Role.WORK, type = "indep-gate")
            note(distinct, "implementation-notes", "actor-impl")
            note(distinct, "test-manifest", "actor-author")
        }

    @Test
    fun `S9 an independence-only block is gate_blocked with an empty missing list and one violation naming seats and never an actor`() {
        val same = uid(91)
        val distinct = uid(92)
        AdvanceTwin(configYaml = independenceConfig("reject"), seed = independenceSeed(same, distinct)).use { twin ->
            val blocked = twin.call(agent, ops(transition(same, "start")))

            assertEquals("gate_blocked", blocked.elementCode(0))
            val detail = blocked.elementDetail(0)
            assertEquals(same.toString(), detail.str("itemId"))
            assertTrue((detail["missing"]?.jsonArray ?: JsonArray(emptyList())).isEmpty(), "no note is missing: $detail")
            val violation = detail.arr("violations").single().jsonObject
            assertEquals("independence", violation.str("gate"))
            val reason = violation.str("reason")
            assertTrue(reason.startsWith("test-manifest: "), reason)
            assertTrue(reason.endsWith(" conflicts with seat implementer"), reason)
            assertFalse(sentinel in blocked.body.toString(), "an actor identity never appears in the body")
            assertEquals(Role.WORK, twin.load(same).role)

            val control = twin.call(agent, ops(transition(distinct, "start"))).element(0)
            assertEquals("review", control.str("newRole"), "control: distinct actors pass the same gate")
            assertFalse("violations" in control)
        }
    }

    @Test
    fun `S9 in warn mode the same conflict applies the transition and reports the violation on the element`() {
        val same = uid(93)
        val distinct = uid(94)
        AdvanceTwin(configYaml = independenceConfig("warn"), seed = independenceSeed(same, distinct)).use { twin ->
            val result = twin.call(agent, ops(transition(same, "start")))

            assertEquals(200, result.status)
            val element = result.element(0)
            assertEquals("review", element.str("newRole"))
            val violation = element.arr("violations").single().jsonObject
            assertEquals("test-manifest", violation.str("key"))
            assertEquals("implementer", violation.str("conflictingSeat"))
            assertTrue(violation.str("constraint").isNotBlank())
            assertFalse(sentinel in result.body.toString())
        }
    }

    // ------------------------------------------------------------------ S10 resource contention

    @Test
    fun `S10 a start into a contended exclusive key is resource_unavailable naming the real resource and mode`() {
        val leased = uid(100)
        val holder = uid(101)
        AdvanceTwin(
            configYaml = P11_BASE_YAML,
            seed = {
                addItem(leased, "Needs the db", type = "p11-leased")
                addItem(holder, "Holds the db")
                raw.resourceLeaseRepository().acquireAll(holder, "holder-agent", listOf("p11-db" to 600))
            }
        ).use { twin ->
            val result = twin.call(agent, ops(transition(leased, "start")))

            assertEquals(207, result.status)
            assertEquals("resource_unavailable", result.elementCode(0))
            val detail = result.elementDetail(0)
            assertEquals(leased.toString(), detail.str("itemId"))
            val resource = detail.arr("resources").single().jsonObject
            assertEquals("p11-db", resource.str("name"))
            assertEquals("exclusive", resource.str("mode"))
            val retry = result.elementError(0).str("retryAfterMs").toLong()
            assertTrue(retry > 0, "retryAfterMs is hoisted to the error: ${result.elementError(0)}")
            assertFalse("unknown" in result.body.toString(), "no fabricated resource name or mode")
            assertFalse("holder-agent" in result.body.toString(), "the lease holder is never named")
            assertEquals(Role.QUEUE, twin.load(leased).role)

            val strict = twin.call(agent, ops(transition(leased, "start"), top = atomic))
            assertEquals(409, strict.status)
            assertEquals("resource_unavailable", strict.code)
        }
    }

    // ------------------------------------------------------------------ S11 / S12 / S13 holders

    @Test
    fun `S11 claiming an item held by another agent is claim_held with its expiry and a retry hint and never the holder`() {
        val held = uid(110)
        AdvanceTwin(seed = { addItem(held, "Held elsewhere", claimedBy = "other-agent-77") }).use { twin ->
            val result = twin.call(agent, ops(claim(held)))

            assertEquals(207, result.status)
            assertEquals("claim_held", result.elementCode(0))
            val detail = result.elementDetail(0)
            assertEquals(held.toString(), detail.str("itemId"))
            assertEquals(farFuture, detail.instant("expiresAt"), "expiresAt is the active claim's expiry")
            assertTrue(detail.str("retryAfterMs").toLong() > 0)
            assertEquals(detail.str("retryAfterMs"), result.elementError(0).str("retryAfterMs"))
            assertEquals("transient", result.elementError(0).str("kind"))
            assertFalse("other-agent-77" in result.body.toString(), "the holder identity never leaves the server")
            assertEquals("other-agent-77", twin.load(held).claimedBy, "the foreign claim is untouched")
        }
    }

    @Test
    fun `S12 claiming a terminal item is invalid_transition and non-holders can neither release nor heartbeat`() {
        val done = uid(120)
        val held = uid(121)
        val free = uid(122)
        AdvanceTwin(
            seed = {
                addItem(done, "Done", role = Role.TERMINAL)
                addItem(held, "Held", claimedBy = "other-agent-77")
                addItem(free, "Free")
            }
        ).use { twin ->
            val terminal = twin.call(agent, ops(claim(done)))
            assertEquals("invalid_transition", terminal.elementCode(0))
            val detail = terminal.elementDetail(0)
            assertEquals(done.toString(), detail.str("itemId"))
            assertEquals("terminal", detail.str("fromRole"))
            assertEquals("claim", detail.str("trigger"))
            assertTrue((detail["allowed"]?.jsonArray ?: JsonArray(emptyList())).isEmpty())

            val results = twin.call(agent, ops(release(held), heartbeat(held), heartbeat(free)))
            val expectedIds = listOf(held, held, free)
            for (i in 0..2) {
                assertEquals("not_claim_holder", results.elementCode(i), "element $i")
                assertEquals(expectedIds[i].toString(), results.elementDetail(i).str("itemId"))
            }
            assertEquals("other-agent-77", twin.load(held).claimedBy, "a non-holder neither releases nor takes over")
            assertNull(twin.load(free).claimedBy, "a heartbeat never takes over an unclaimed item")
        }
    }

    @Test
    fun `S12 probe - releasing an item nobody holds is not_claim_holder`() {
        val free = uid(123)
        AdvanceTwin(seed = { addItem(free, "Free") }).use { twin ->
            val result = twin.call(agent, ops(release(free)))
            assertEquals("not_claim_holder", result.elementCode(0))
            assertNull(twin.load(free).claimedBy)
        }
    }

    @Test
    fun `S13 a transition by a non-holder of a claimed item is not_claim_holder on both surfaces while the holder may proceed`() {
        val held = uid(130)
        val mine = uid(131)
        AdvanceTwin(
            seed = {
                addItem(held, "Claimed by someone else", claimedBy = "other-agent-77")
                addItem(mine, "Mine")
            }
        ).use { twin ->
            val denied = twin.call(agent, ops(transition(held, "start")))
            assertEquals(207, denied.status)
            assertEquals("not_claim_holder", denied.elementCode(0))
            assertEquals(held.toString(), denied.elementDetail(0).str("itemId"))
            assertEquals(Role.QUEUE, twin.load(held).role, "the item did not move")

            val strict = twin.call(agent, ops(transition(held, "start"), top = atomic))
            assertEquals(403, strict.status)
            assertEquals("not_claim_holder", strict.code)

            twin.call(agent, ops(claim(mine)))
            val allowed = twin.call(agent, ops(transition(mine, "start")))
            assertEquals(200, allowed.status, "control: the holder of the claim may transition the item")
            assertEquals("work", allowed.element(0).str("newRole"))
        }
    }

    // ------------------------------------------------------------------ S14 / S20 request validation

    @Test
    fun `S14 claim release and heartbeat without an actor fail invalid_request on the actor field and a supplied actor is accepted`() {
        val item = uid(140)
        val plain = uid(141)
        AdvanceTwin(
            seed = {
                addItem(item, "Needs an actor")
                addItem(plain, "Transition without actor")
            }
        ).use { twin ->
            val registry = ToolRegistry(listOf(advanceTool()))

            fun mcp(args: String): JsonObject =
                runBlocking {
                    callMcp(
                        twin.rig.composition.toolContext,
                        registry,
                        null,
                        ADVANCE_TOOL,
                        Json.parseToJsonElement(args).jsonObject
                    )
                }.structured

            val bare = mcp(ops(claim(item), release(item), heartbeat(item)))
            val results = bare.obj("data").arr("results")
            for (i in 0..2) {
                val error = results[i].jsonObject.obj("error")
                assertEquals("invalid_request", error.str("code"), "element $i")
                val violation =
                    error
                        .obj("detail")
                        .arr("fields")
                        .single()
                        .jsonObject
                assertTrue(violation.str("field").endsWith("/actor"), "element $i: ${violation.str("field")}")
                assertTrue("required" in violation.str("reason"), violation.str("reason"))
            }
            assertNull(twin.load(item).claimedBy)

            val withActor = mcp("""{"actor":{"id":"agent-1","kind":"subagent"},"ops":[${claim(item)}]}""")
            assertEquals(
                "true",
                withActor
                    .obj("data")
                    .arr("results")[0]
                    .jsonObject
                    .str("claimed")
            )
            assertNotNull(twin.load(item).claimedBy, "the supplied actor became the holder")

            val transitionOnly = mcp(ops(transition(plain, "start")))
            assertEquals(
                "work",
                transitionOnly
                    .obj("data")
                    .arr("results")[0]
                    .jsonObject
                    .str("newRole"),
                "a transition needs no actor"
            )
        }
    }

    @Test
    fun `S14 an unknown or wrongly cased trigger is invalid_request at the trigger pointer and nothing changes`() {
        val item = uid(142)
        AdvanceTwin(seed = { addItem(item, "Trigger probe") }).use { twin ->
            for (bad in listOf("START", "bogus", "")) {
                val result = twin.call(agent, ops(transition(item, bad)))
                assertEquals("invalid_request", result.elementCode(0), "trigger '$bad'")
                assertEquals("/ops/0/trigger", result.elementField(0), "trigger '$bad'")
            }
            assertEquals(Role.QUEUE, twin.load(item).role)
        }
    }

    @Test
    fun `S20 out-of-range ttl, oversized claimRef and the itemId-select exclusivity are invalid_request`() {
        val item = uid(150)
        AdvanceTwin(seed = { addItem(item, "Validation probe") }).use { twin ->
            val tooLong = "x".repeat(65)
            val result =
                twin.call(
                    agent,
                    ops(
                        claim(item, ""","ttlSeconds":0"""),
                        claim(item, ""","ttlSeconds":86401"""),
                        claim(item, ""","ttlSeconds":-5"""),
                        claim(item, ""","claimRef":"$tooLong""""),
                        claim(item, ""","select":{}"""),
                        """{"op":"claim"}""",
                        heartbeat(item, ""","ttlSeconds":0""")
                    )
                )

            assertEquals(207, result.status)
            val expectedSuffixes = listOf("/ttlSeconds", "/ttlSeconds", "/ttlSeconds", "/claimRef", "/itemId", "/itemId", "/ttlSeconds")
            for ((i, suffix) in expectedSuffixes.withIndex()) {
                assertEquals("invalid_request", result.elementCode(i), "element $i")
                assertTrue(result.elementField(i).endsWith(suffix), "element $i field ${result.elementField(i)} should end with $suffix")
            }
            assertNull(twin.load(item).claimedBy, "no invalid claim took effect")

            val exact = "x".repeat(64)
            val boundary = twin.call(agent, ops(claim(item, ""","claimRef":"$exact"""")))
            assertTrue(boundary.isOk, "control: a 64-character claimRef is accepted")
        }
    }

    // ------------------------------------------------------------------ S15 / S16 scope and capability

    @Test
    fun `S15 under a root-scoped token every op on an item outside the root is forbidden and has no effect`() {
        val inside = uid(160)
        val outside = uid(161)
        val scoped = ParityToken("adv-scoped-160", setOf(ApiCapability.READ, ApiCapability.ADVANCE), rootIds = setOf(inside))
        AdvanceTwin(
            seed = {
                addItem(inside, "Inside root", role = Role.WORK)
                addItem(outside, "Outside root")
            }
        ).use { twin ->
            val result = twin.call(scoped, ops(transition(outside, "start"), claim(outside), release(outside), heartbeat(outside)))

            assertEquals(207, result.status)
            for (i in 0..3) {
                assertEquals("forbidden", result.elementCode(i), "element $i")
                val detail = result.elementDetail(i)
                assertEquals("root", detail.str("scope"))
                assertEquals(inside.toString(), detail.str("required"))
            }
            val after = twin.load(outside)
            assertEquals(Role.QUEUE, after.role, "the transition did not run before the scope check")
            assertNull(after.claimedBy, "the claim did not run before the scope check")
        }
    }

    @Test
    fun `S15 a find-and-claim under a restricted scope only considers items in scope and counts the rest as outOfScope`() {
        val rootIn = uid(162)
        val rootOut = uid(163)
        val inScope = uid(164)
        val outScope = uid(165)
        val scoped = ParityToken("adv-scoped-162", setOf(ApiCapability.READ, ApiCapability.ADVANCE), rootIds = setOf(rootIn))

        fun seed(withInside: Boolean): suspend EventLogRig.() -> Unit =
            {
                val a = addItem(rootIn, "Root in", role = Role.WORK)
                val b = addItem(rootOut, "Root out", role = Role.WORK)
                if (withInside) addItem(inScope, "In scope, low priority", parent = a, priority = Priority.LOW)
                addItem(outScope, "Out of scope, high priority", parent = b, priority = Priority.HIGH)
            }
        AdvanceTwin(seed = seed(withInside = true)).use { twin ->
            val element = twin.call(scoped, ops(claimSelect("{}"))).element(0)

            assertEquals(inScope.toString(), element.str("itemId"), "the higher-priority item outside the scope is never selected")
            assertEquals("true", element.str("selected"))
            assertNull(twin.load(outScope).claimedBy)
        }
        AdvanceTwin(seed = seed(withInside = false)).use { twin ->
            val result = twin.call(scoped, ops(claimSelect("{}")))

            assertEquals(200, result.status, "an out-of-scope-only selection is not an error")
            val element = result.element(0)
            assertEquals("false", element.str("claimed"))
            assertFalse("reason" in element, "no catalog reason applies: $element")
            assertFalse("retryAfterMs" in element)
            assertEquals("1", element.obj("excluded").str("outOfScope"))
            assertNull(twin.load(outScope).claimedBy)
        }
    }

    @Test
    fun `S16 a read-only token is forbidden on every advance op with the advance capability named`() {
        val item = uid(170)
        AdvanceTwin(seed = { addItem(item, "Read only probe") }).use { twin ->
            val result = twin.call(reader, ops(transition(item, "start"), claim(item), release(item), heartbeat(item)))

            assertEquals(207, result.status)
            for (i in 0..3) {
                assertEquals("forbidden", result.elementCode(i), "element $i")
                assertEquals("capability", result.elementDetail(i).str("scope"))
                assertEquals("advance", result.elementDetail(i).str("required"))
            }
            assertEquals(Role.QUEUE, twin.load(item).role)
            assertNull(twin.load(item).claimedBy)
        }
    }

    // ------------------------------------------------------------------ S17 no-match selects

    @Test
    fun `S17 a select whose matches are all claimed is ok with claim_held, a 30 second hint and the exclusion counts`() {
        val parent = uid(180)
        AdvanceTwin(
            seed = {
                val p = addItem(parent, "Parent", role = Role.WORK)
                addItem(uid(181), "Taken one", parent = p, claimedBy = "other-agent-77")
                addItem(uid(182), "Taken two", parent = p, claimedBy = "other-agent-78")
            }
        ).use { twin ->
            val result = twin.call(agent, ops(claimSelect("""{"parentId":"$parent"}""")))

            assertEquals(200, result.status, "no match is never an error")
            assertTrue(result.isOk)
            val element = result.element(0)
            assertEquals("false", element.str("claimed"))
            assertEquals("claim_held", element.str("reason"))
            assertEquals("30000", element.str("retryAfterMs"))
            assertEquals("2", element.obj("excluded").str("claimed"))
            assertFalse("other-agent" in result.body.toString())
        }
    }

    @Test
    fun `S17 a select whose only candidate is dependency-blocked reports dependency_unmet and an empty select reports no reason`() {
        val parent = uid(185)
        val blocker = uid(186)
        val blocked = uid(187)
        AdvanceTwin(
            seed = {
                val p = addItem(parent, "Parent", role = Role.WORK)
                addItem(blocker, "Blocker", role = Role.WORK)
                addItem(blocked, "Blocked child", parent = p)
                raw.dependencyRepository().create(Dependency(fromItemId = blocker, toItemId = blocked, type = DependencyType.BLOCKS))
            }
        ).use { twin ->
            val element = twin.call(agent, ops(claimSelect("""{"parentId":"$parent"}"""))).element(0)
            assertEquals("false", element.str("claimed"))
            assertEquals("dependency_unmet", element.str("reason"))
            assertEquals("30000", element.str("retryAfterMs"))
            assertEquals("1", element.obj("excluded").str("dependencyBlocked"))
            assertNull(twin.load(blocked).claimedBy)
        }
        val emptyParent = uid(188)
        AdvanceTwin(seed = { addItem(emptyParent, "Childless", role = Role.WORK) }).use { twin ->
            val element = twin.call(agent, ops(claimSelect("""{"parentId":"$emptyParent"}"""))).element(0)
            assertEquals("false", element.str("claimed"))
            assertFalse("reason" in element, "nothing matched: $element")
            assertFalse("retryAfterMs" in element)
        }
    }

    // ------------------------------------------------------------------ S18 removed surface

    @Test
    fun `S18 advance_item and claim_item are gone from the tool surface and POST items advance is not routed`() {
        val names = ToolRegistry(buildToolSpecs()).tools.map { it.name }
        assertFalse("advance_item" in names)
        assertFalse("claim_item" in names)
        assertTrue(ToolRegistry(buildToolSpecs()).tools.any { it.name == ADVANCE_TOOL })

        val admin = ParityToken("adv-admin", setOf(ApiCapability.READ, ApiCapability.ADVANCE, ApiCapability.ADMIN))
        SqliteTestDatabase.open().use { db ->
            val dir = Files.createTempDirectory("advance-s18-")
            try {
                val rig = EventLogRig.build(db, dir)
                var status = 0
                testApplication {
                    application { installParityRestApp(rig, parityAuthConfig(admin), ToolRegistry(listOf(advanceTool()))) }
                    val response =
                        client.post("/api/v1/items/${uid(190)}/advance") {
                            header(HttpHeaders.Authorization, "Bearer ${admin.raw}")
                            contentType(ContentType.Application.Json)
                            setBody("""{"trigger":"start"}""")
                        }
                    status = response.status.value
                }
                assertEquals(404, status, "the legacy advance route no longer exists")
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    // ------------------------------------------------------------------ probes: ids, duplicates, idempotency

    @Test
    fun `probe - id prefixes need 8 hex characters, are ambiguous_id when shared and not_found when unknown`() {
        val one = UUID.fromString("abcdef01-aaaa-4000-8000-000000000001")
        val two = UUID.fromString("abcdef01-bbbb-4000-8000-000000000002")
        AdvanceTwin(
            seed = {
                addItem(one, "Prefix one")
                addItem(two, "Prefix two")
            }
        ).use { twin ->
            val tooShort = twin.call(agent, ops(claim("abcdef0")))
            assertEquals("invalid_request", tooShort.elementCode(0), "a 7-hex prefix is below the 8-hex minimum")
            assertTrue(tooShort.elementField(0).endsWith("/itemId"), tooShort.elementField(0))

            val shared = twin.call(agent, ops(claim("abcdef01")))
            assertEquals("ambiguous_id", shared.elementCode(0))
            val candidates =
                shared
                    .elementDetail(0)
                    .arr("candidates")
                    .map { it.jsonPrimitive.content }
                    .toSet()
            assertEquals(setOf(one.toString(), two.toString()), candidates, "ids only")
            assertNull(twin.load(one).claimedBy)
            assertNull(twin.load(two).claimedBy)

            val unique = twin.call(agent, ops(claim("abcdef01a")))
            assertEquals(one.toString(), unique.element(0).str("itemId"), "a longer prefix resolves to the one item")

            val unknown = twin.call(agent, ops(claim("ffffffff")))
            assertEquals("not_found", unknown.elementCode(0))
            assertEquals("item", unknown.elementDetail(0).str("kind"))

            val full = twin.call(agent, ops(claim(two.toString())))
            assertEquals(two.toString(), full.element(0).str("itemId"))
        }
    }

    @Test
    fun `probe - the same item twice in one claim batch is a claim then a holder refresh`() {
        val item = uid(200)
        AdvanceTwin(seed = { addItem(item, "Twice") }).use { twin ->
            val result = twin.call(agent, ops(claim(item), claim(item)))

            assertEquals(200, result.status)
            assertFalse("refreshed" in result.element(0))
            assertEquals("true", result.element(1).str("refreshed"))
        }
    }

    @Test
    fun `probe - a replayed idempotencyKey returns the stored element and the same key with another payload is idempotency_mismatch`() {
        val item = uid(210)
        AdvanceTwin(seed = { addItem(item, "Idempotent") }).use { twin ->
            val first = twin.call(agent, ops(transition(item, "start", ""","idempotencyKey":"key-1"""")))
            assertEquals("work", first.element(0).str("newRole"))

            val replay = twin.call(agent, ops(transition(item, "start", ""","idempotencyKey":"key-1"""")))
            assertEquals("true", replay.element(0).str("replayed"))
            assertEquals("work", replay.element(0).str("newRole"))

            val mismatch = twin.call(agent, ops(transition(item, "hold", ""","idempotencyKey":"key-1"""")))
            assertEquals("idempotency_mismatch", mismatch.elementCode(0))
            assertEquals("key-1", mismatch.elementDetail(0).str("idempotencyKey"))
            assertEquals(Role.WORK, twin.load(item).role, "the mismatched payload did not run")
        }
    }

    @Test
    fun `probe - select tags empty and absent never produce an internal error`() {
        val parent = uid(220)
        for (select in listOf("""{"parentId":"$parent","tags":[]}""", """{"parentId":"$parent"}""")) {
            AdvanceTwin(
                seed = {
                    val p = addItem(parent, "Parent", role = Role.WORK)
                    addItem(uid(221), "Child", parent = p)
                }
            ).use { twin ->
                val result = twin.call(agent, ops(claimSelect(select)))
                assertFalse("internal" in result.body.toString(), "select $select: ${result.body}")
            }
        }
    }

    @Test
    fun `probe - an upper-case full UUID resolves to the same item as its lower-case form`() {
        val item = uid(230)
        AdvanceTwin(seed = { addItem(item, "Case probe") }).use { twin ->
            val element = twin.call(agent, ops(claim(item.toString().uppercase()))).element(0)
            assertEquals(item.toString(), element.str("itemId"))
            assertEquals("true", element.str("claimed"))
        }
    }
}
