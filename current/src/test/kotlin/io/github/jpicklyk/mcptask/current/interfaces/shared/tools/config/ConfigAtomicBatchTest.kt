package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.okResult
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S20 / D8 in one `atomic: true` call: an appending write (push, reset) is visible to the later ops of the SAME call. The
 * non-atomic S20 tests run each element in its own unit and so cannot tell whether a within-call config read is stale.
 * Oracle: test-plan S20 ("one call [push B, effective R] -> effective shows B") and task-scope D8, with the envelope rule that
 * an atomic call runs the whole array as one unit.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ConfigAtomicBatchTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val r1 = UUID.fromString("a1000001-0000-4000-8000-000000000001")

    private suspend fun seeded(): CfgKit {
        val k = CfgKit(EventLogRig.build(db.db, dir))
        k.root("R1", r1)
        return k
    }

    @Test
    fun `S20 atomic call - effective after a push in the same call shows the pushed content`(): Unit =
        runBlocking {
            val k = seeded()
            val body = k.call(effectiveOp(r1), pushOp(r1, CfgYaml.rev(2)), effectiveOp(r1), atomic = true)

            val before = body.okResult(0)
            assertFalse(before.names("types").contains("feature-task"), "control: nothing is pushed yet: $before")
            assertTrue(body.okResult(1).bool("appended"))
            val after = body.okResult(2)
            assertTrue(after.names("types").contains("feature-task"), "the same call sees its own push: $after")
            assertEquals(1, k.versions(r1))
        }

    @Test
    fun `S20 atomic call - effective after a reset in the same call shows no per-root layer`(): Unit =
        runBlocking {
            val k = seeded()
            k.call(pushOp(r1, CfgYaml.rev(2))).okResult()

            val body = k.call(effectiveOp(r1), resetOp(r1), effectiveOp(r1), atomic = true)

            val before = body.okResult(0)
            assertTrue(before.names("types").contains("feature-task"), "control: the pushed layer is visible first: $before")
            assertTrue(body.okResult(1).bool("appended"))
            val after = body.okResult(2)
            assertFalse("perRootFingerprint" in after, "no per-root layer after the reset: $after")
            assertFalse(after.names("types").contains("feature-task"), "the pushed types are gone: $after")
            assertEquals(2, k.versions(r1))
        }
}
