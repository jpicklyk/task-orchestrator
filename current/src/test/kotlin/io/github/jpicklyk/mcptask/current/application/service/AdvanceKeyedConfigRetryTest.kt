package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11Driver.Companion.transitioned
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.breakConfigReads
import io.github.jpicklyk.mcptask.current.test.configFaultFixture
import io.github.jpicklyk.mcptask.current.test.errorCode
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.onlyResult
import io.github.jpicklyk.mcptask.current.test.rawExec
import io.github.jpicklyk.mcptask.current.test.snapshot
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * Independent P11 tests (item 919d379e, round r2): a keyed `advance` transition that failed with the shed `unavailable`
 * (an in-unit per-root config read fault; the removed 3.x `config_unavailable`) is NOT stored for replay, so a retry with the
 * same idempotencyKey, actor and payload after the fault clears EXECUTES and applies; it is not answered with the stored
 * failure and is not marked replayed.
 *
 * Oracle: plan v4-phase1-core.md section 3.9 (stores the result only on ok; transient failures are not stored) and the
 * api-reference idempotency paragraph (a config or database fault is not recorded, so a retry with the same key runs it
 * again). The fault is the real one of AdvanceConfigFailClosedTest (config_heads renamed away), cleared by
 * renaming it back.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class AdvanceKeyedConfigRetryTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @Test
    fun `a keyed advance that failed unavailable re-executes on retry with the same idempotencyKey and applies`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))
            val f = d.configFaultFixture()
            val before = d.snapshot(f.item)
            val key = UUID.randomUUID().toString()

            suspend fun call() = d.advanceEnvelope(f.item, "start", actor = "agent-retry", idempotencyKey = key).onlyResult()
            d.breakConfigReads()

            val first = call()

            assertEquals(false, first.flag("ok"), "$first")
            assertEquals("unavailable", first.errorCode(), "$first")
            assertEquals(before, d.snapshot(f.item), "fail-closed: nothing changed")

            rawExec(d.jdbcUrl, "ALTER TABLE config_heads_gone RENAME TO config_heads")
            val retry = call()

            assertEquals(true, retry.flag("ok"), "the retry EXECUTES instead of replaying the transient failure: $retry")
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

            val again = call()
            assertEquals(true, again.flag("replayed"), "control: once stored, the third identical call replays: $again")
            assertEquals(before.transitionRows + 1, d.transitions(f.item).size, "the replay executes nothing")
        }
}
