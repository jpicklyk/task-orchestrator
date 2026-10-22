package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.parity.PARITY_NOW
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 46a83aca) for the claim and heartbeat result additions: scenario S9 of the frozen
 * test-plan, on both surfaces (every call runs the same arguments over MCP and `POST /api/v1/ops/advance` and asserts equal bodies).
 *
 * Oracle (frozen, planning phase): task-scope item 6 and AC4 - the claim element gains `version` (the item's row version after
 * the claim; claim writes never bump it, so it is the item's version at claim time) and `released: [<itemId>...]`, the ids the
 * claim auto-released (an agent holds one claim at a time, so claiming a second item releases the first), OMITTED when empty (the
 * house rule that empty members are omitted); a heartbeat answers `version` always and `released` only when non-empty; the
 * existing member names are unchanged (additive only).
 *
 * All cells are NEW-SURFACE. The version fixture is bumped by an update before the claim so a constant `1` cannot satisfy the
 * assertion; the expected `released` list is the id the test itself claimed first, never read from a response.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ClaimResultVersionReleasedTest {
    private val agent = ParityToken("claim-agent", setOf(ApiCapability.READ, ApiCapability.ADVANCE))

    private fun uid(n: Int): UUID = UUID.fromString("c%07d-0000-4000-8000-%012d".format(n, n))

    private val first = uid(1)
    private val second = uid(2)

    private suspend fun EventLogRig.addBumped(
        id: UUID,
        title: String
    ) {
        val created =
            raw.workItemRepository().create(
                WorkItem(id = id, title = title, role = Role.QUEUE, depth = 0, rootId = id, createdAt = PARITY_NOW)
            )
        val bumped = checkNotNull(raw.workItemRepository().update(created.copy(title = "$title (edited)"))) { "fixture: update of $title" }
        check(bumped.version > 1) { "fixture: the version of $title must be above the default 1, is ${bumped.version}" }
    }

    private val seed: suspend EventLogRig.() -> Unit = {
        addBumped(first, "First")
        addBumped(second, "Second")
    }

    private fun ops(element: String) = """{"ops":[$element]}"""

    private fun claim(id: UUID) = """{"op":"claim","itemId":"$id"}"""

    private fun heartbeat(id: UUID) = """{"op":"heartbeat","itemId":"$id"}"""

    private fun AdvanceTwin.version(id: UUID): String =
        runBlocking { checkNotNull(rig.raw.workItemRepository().getById(id)).version.toString() }

    private fun JsonObject.released(): List<String> = getValue("released").jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `S9 a first claim carries the item version and no released member`() {
        AdvanceTwin(seed = seed).use { twin ->
            val element = twin.call(agent, ops(claim(first))).element(0)

            assertEquals("true", element.getValue("claimed").jsonPrimitive.content)
            assertEquals(twin.version(first), element.getValue("version").jsonPrimitive.content, "version is the item's row version")
            assertTrue(
                element
                    .getValue("version")
                    .jsonPrimitive.content
                    .toInt() > 1,
                "the fixture's bumped version, not a constant 1"
            )
            assertFalse("released" in element, "nothing was auto-released, so the member is omitted: $element")
        }
    }

    @Test
    fun `S9 claiming a second item lists the auto-released first one in released and the second item's version`() {
        AdvanceTwin(seed = seed).use { twin ->
            twin.call(agent, ops(claim(first)))

            val element = twin.call(agent, ops(claim(second))).element(0)

            assertEquals(listOf(first.toString()), element.released(), "released names exactly the item the claim let go of")
            assertEquals(twin.version(second), element.getValue("version").jsonPrimitive.content)
            assertNull(
                runBlocking {
                    twin.rig.raw
                        .workItemRepository()
                        .getById(first)
                }?.claimedBy,
                "the first item really was released"
            )
        }
    }

    @Test
    fun `S9 a holder re-claim is a refresh that carries the version and omits released`() {
        AdvanceTwin(seed = seed).use { twin ->
            twin.call(agent, ops(claim(first)))

            val element = twin.call(agent, ops(claim(first))).element(0)

            assertEquals("true", element.getValue("refreshed").jsonPrimitive.content)
            assertEquals(twin.version(first), element.getValue("version").jsonPrimitive.content)
            assertFalse("released" in element, "$element")
        }
    }

    @Test
    fun `S9 a heartbeat carries the version and never a released member`() {
        AdvanceTwin(seed = seed).use { twin ->
            twin.call(agent, ops(claim(first)))

            val element = twin.call(agent, ops(heartbeat(first))).element(0)

            assertEquals("true", element.getValue("refreshed").jsonPrimitive.content)
            assertEquals(twin.version(first), element.getValue("version").jsonPrimitive.content, "version is always present on a heartbeat")
            assertFalse("released" in element, "$element")
        }
    }

    @Test
    fun `S9 the existing claim members keep their names - additive only`() {
        AdvanceTwin(seed = seed).use { twin ->
            val element = twin.call(agent, ops(claim(first))).element(0)

            for (name in listOf("ok", "itemId", "claimed", "expiresAt", "claimedAt", "originalClaimedAt")) {
                assertTrue(name in element, "existing member '$name' must remain: $element")
            }
            assertEquals(first.toString(), element.getValue("itemId").jsonPrimitive.content, "the id member stays itemId, not id")
            assertFalse("id" in element, "no renamed id member: $element")
        }
    }
}
