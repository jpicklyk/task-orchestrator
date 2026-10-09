package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11Driver.Companion.transitioned
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.breakConfigReads
import io.github.jpicklyk.mcptask.current.test.configFaultFixture
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.rawExec
import io.github.jpicklyk.mcptask.current.test.snapshot
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.text
import kotlinx.coroutines.runBlocking
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

/**
 * Independent P11 tests (item 919d379e, round r2): a keyed advance_item that failed with the transient config_unavailable
 * (an in-unit per-root config read fault) is NOT stored for replay, so a retry with the same requestId, actor and payload
 * after the fault clears EXECUTES and applies; it is not answered with the stored failure and is not marked replayed.
 *
 * Oracle: plan v4-phase1-core.md section 3.9 (stores the result only on ok; transient failures are not stored) and the
 * api-reference idempotency paragraph (a config or database fault is not recorded, so a retry with the same requestId
 * runs it again). The fault is the real one of AdvanceConfigFailClosedTest (project_config renamed away), cleared by
 * renaming it back.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class AdvanceKeyedConfigRetryTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @Test
    fun `a keyed advance that failed config_unavailable re-executes on retry with the same requestId and applies`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))
            val f = d.configFaultFixture()
            val before = d.snapshot(f.item)
            val params = d.advanceParams(f.item, "start", actor = "agent-retry", requestId = UUID.randomUUID().toString())
            d.breakConfigReads()

            val first =
                d.rig
                    .call(AdvanceItemTool(), *params)["data"]!!
                    .jsonObject["results"]!!
                    .jsonArray[0]
                    .jsonObject

            assertEquals(false, first.flag("applied"), "$first")
            assertEquals("config_unavailable", first.text("errorCode"), "$first")
            assertEquals(before, d.snapshot(f.item), "fail-closed: nothing changed")

            rawExec(d.jdbcUrl, "ALTER TABLE project_config_gone RENAME TO project_config")
            val retry =
                d.rig
                    .call(AdvanceItemTool(), *params)["data"]!!
                    .jsonObject["results"]!!
                    .jsonArray[0]
                    .jsonObject

            assertEquals(true, retry.flag("applied"), "the retry EXECUTES instead of replaying the transient failure: $retry")
            assertEquals(null, retry.flag("replayed"), "an executed retry is not a replay: $retry")
            assertEquals(Role.WORK, d.role(f.item))
            assertEquals(before.transitionRows + 1, d.transitions(f.item).size, "exactly one row")
            assertEquals(
                1,
                d.rig
                    .rows()
                    .transitioned()
                    .size,
                "exactly one event"
            )

            val again =
                d.rig
                    .call(AdvanceItemTool(), *params)["data"]!!
                    .jsonObject["results"]!!
                    .jsonArray[0]
                    .jsonObject
            assertEquals(true, again.flag("replayed"), "control: once stored, the third identical call replays: $again")
            assertEquals(before.transitionRows + 1, d.transitions(f.item).size, "the replay executes nothing")
        }
}
