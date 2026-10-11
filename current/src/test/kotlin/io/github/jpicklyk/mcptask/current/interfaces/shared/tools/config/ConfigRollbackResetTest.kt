package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.detail
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.str
import io.github.jpicklyk.mcptask.current.test.errorAt
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
 * Regression tests for the carry-in-q8 ruling (item bfdc296b): a rollback whose target is a RESET version (a reset means
 * "no per-root config") appends a RESET version equivalent to a direct reset, never a ROLLBACK version holding the reset's
 * empty content. Oracle: the ruling, and the ConfigService reset KDoc (a reset reads as absence, a reset on a reset head
 * writes nothing).
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ConfigRollbackResetTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val r1 = UUID.fromString("a1000001-0000-4000-8000-000000000001")
    private val r2 = UUID.fromString("b2000001-0000-4000-8000-000000000002")

    private val a = CfgYaml.rev(1)
    private val b = CfgYaml.rev(2)

    private suspend fun seeded(): CfgKit {
        val k = CfgKit(EventLogRig.build(db.db, dir))
        k.root("R1", r1)
        k.root("R2", r2)
        return k
    }

    /** push a (v1), reset (v2), push b (v3): the head is a normal version and version 2 is the reset. */
    private suspend fun CfgKit.build(root: UUID) {
        call(pushOp(root, a)).okResult()
        call(resetOp(root)).okResult()
        call(pushOp(root, b)).okResult()
    }

    @Test
    fun `rollback to a reset version appends a reset - the root reads as absent and falls through to the server layer`(): Unit =
        runBlocking {
            val k = seeded()
            k.build(r1)
            assertTrue(
                k
                    .call(effectiveOp(r1))
                    .okResult()
                    .names("types")
                    .contains("feature-task"),
                "control: the pushed layer is visible"
            )

            val rolled = k.call(rollbackOp(r1, 2)).okResult()
            assertTrue(rolled.bool("appended"))
            assertEquals(4, rolled.int("version"))
            assertEquals("reset", rolled.str("source"), "the new head is a reset, not a rollback copy of the empty content")

            val gone = k.call(getOp(r1)).errorAt()
            assertEquals("not_found", gone.str("code"))
            assertEquals("config", gone.detail().str("kind"))
            val view = k.call(effectiveOp(r1)).okResult()
            assertFalse("perRootFingerprint" in view, "no per-root layer shadows the server layer: $view")
            assertFalse(view.names("types").contains("feature-task"), "the pushed types are gone: $view")
            assertEquals(4, k.versions(r1), "history keeps every version")
        }

    @Test
    fun `rollback to a reset version matches a direct reset in history, events and effective view`(): Unit =
        runBlocking {
            val k = seeded()
            k.build(r1)
            k.build(r2)
            k.call(rollbackOp(r1, 2)).okResult()
            k.call(resetOp(r2)).okResult()

            val h1 =
                k
                    .call(historyOp(r1))
                    .okResult()
                    .list("versions")
                    .first()
            val h2 =
                k
                    .call(historyOp(r2))
                    .okResult()
                    .list("versions")
                    .first()
            assertEquals(h2.str("source"), h1.str("source"))
            assertEquals(h2.str("fingerprint"), h1.str("fingerprint"))
            assertEquals(4, h1.int("version"))
            assertEquals(4, h2.int("version"))
            val events = k.configEvents()
            assertEquals(4, events.count { it.rootId == r1 })
            assertEquals(4, events.count { it.rootId == r2 })
            assertEquals(
                k
                    .call(effectiveOp(r2))
                    .okResult()
                    .names("types")
                    .sorted(),
                k
                    .call(effectiveOp(r1))
                    .okResult()
                    .names("types")
                    .sorted()
            )
        }

    @Test
    fun `rollback to a reset version when the head is already a reset appends nothing and honours expectedVersion`(): Unit =
        runBlocking {
            val k = seeded()
            k.call(pushOp(r1, a)).okResult()
            k.call(resetOp(r1)).okResult()
            val before = k.snapshot()

            val noop = k.call(rollbackOp(r1, 2)).okResult()
            assertFalse(noop.bool("appended"))
            assertEquals(2, noop.int("version"))
            assertEquals(before, k.snapshot(), "nothing is written")

            val err = k.call(rollbackOp(r1, 2, "expectedVersion" to num(1))).errorAt()
            assertEquals("version_conflict", err.str("code"))
            assertEquals(1, err.detail().int("expected"))
            assertEquals(2, err.detail().int("actual"))
            assertEquals(before, k.snapshot())
        }

    @Test
    fun `rollback to a normal version after a reset restores that content`(): Unit =
        runBlocking {
            val k = seeded()
            k.call(pushOp(r1, a)).okResult()
            k.call(resetOp(r1)).okResult()

            val rolled = k.call(rollbackOp(r1, 1)).okResult()
            assertTrue(rolled.bool("appended"))
            assertEquals(3, rolled.int("version"))
            assertEquals("rollback", rolled.str("source"))
            assertEquals(a, k.call(getOp(r1)).okResult().str("configYaml"))
            assertTrue(
                k
                    .call(effectiveOp(r1))
                    .okResult()
                    .names("types")
                    .contains("feature-task")
            )
        }
}
