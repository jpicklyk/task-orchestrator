package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetContextTool
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11Driver.Companion.transitioned
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.breakConfigReads
import io.github.jpicklyk.mcptask.current.test.configFaultFixture
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.snapshot
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Independent P11 r1 tests (item 919d379e), MCP surface, for fix-decision H1: the advanced item's per-root config is
 * resolved INSIDE the advance's write unit (and the get_context canAdvance preview's read unit), where there is no
 * last-known-good. A config read fault after the cache is warm therefore fails closed.
 *
 * Oracles: fix-decisions-r1 H1; api-reference "Read-error fallback" paragraph (advance_item, complete_tree and the previews
 * read config inside a unit, so a read fault answers a transient `config_unavailable` even with a warm cache); the existing
 * element shape of AdvanceItemToolConfigUnavailableTest (`applied=false`, `errorKind=transient`, `errorCode=config_unavailable`).
 * The fault is real: the project_config table is renamed away after the cache is warm (P5bPerRootConfigInUnitTest recipe). Every
 * fault test has a same-fixture control proving the advance would otherwise apply.
 *
 * Fail-closed means: no role change, no status-label / role-change-instant change, no transition row, no event row.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class AdvanceConfigFailClosedTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private fun driver(dir: Path) = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))

    @Test
    fun `H1 control - with healthy config the same fixture advances, records a row and an event`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val f = d.configFaultFixture()
            val before = d.snapshot(f.item)

            val r = d.advance(f.item, "start")

            assertEquals(true, r.flag("applied"), "control: the fixture must advance when config is readable: $r")
            assertEquals(Role.WORK, d.role(f.item))
            assertEquals(before.transitionRows + 1, d.transitions(f.item).size)
            assertEquals(
                1,
                d.rig
                    .rows()
                    .transitioned()
                    .size
            )
        }

    @Test
    fun `H1 an unkeyed advance_item fails closed as transient config_unavailable although the cache is warm`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val f = d.configFaultFixture()
            val before = d.snapshot(f.item)
            d.breakConfigReads()

            val r = d.advance(f.item, "start")

            assertEquals(false, r.flag("applied"), "$r")
            assertEquals(before, d.snapshot(f.item), "no role change, no transition row, no event: $r")
            assertEquals(Role.QUEUE, d.role(f.item))
            assertEquals("config_unavailable", r.text("errorCode"), "$r")
            assertEquals("transient", r.text("errorKind"), "$r")
        }

    @Test
    fun `H1 a keyed advance_item fails closed as transient config_unavailable although the cache is warm`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val f = d.configFaultFixture()
            val before = d.snapshot(f.item)
            d.breakConfigReads()

            val response =
                d.rig.call(
                    AdvanceItemTool(),
                    *d.advanceParams(f.item, "start", actor = "agent-h1", requestId = UUID.randomUUID().toString()),
                )
            val r = response["data"]!!.jsonObject["results"]!!.jsonArray[0].jsonObject

            assertEquals(false, r.flag("applied"), "$r")
            assertEquals("config_unavailable", r.text("errorCode"), "$r")
            assertEquals("transient", r.text("errorKind"), "$r")
            assertEquals(before, d.snapshot(f.item), "no role change, no transition row, no event")
        }

    @Test
    fun `H1 the get_context canAdvance preview fails closed with PerRootConfigUnavailableException on a direct execute`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val f = d.configFaultFixture()
            d.breakConfigReads()

            val e =
                assertFailsWith<PerRootConfigUnavailableException> {
                    GetContextTool().execute(
                        JsonObject(mapOf("itemId" to JsonPrimitive(f.item.id.toString()))),
                        d.rig.ctx,
                    )
                }

            assertEquals(f.root.id, e.rootId)
            assertTrue(
                e.message.orEmpty().contains("inside a unit of work"),
                "the fault names the unit-of-work read: ${e.message}"
            )
        }
}
