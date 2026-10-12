package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.failure
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.success
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.streams.toList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Independent P11 tests (item 919d379e) for S14: the retired vocabularies are gone and the single trigger vocabulary
 * accepts and rejects the right strings.
 *
 * Oracles: AC6 (a grep for UserTrigger, VALID_TRIGGERS, USER_TRIGGERS, RoleTransitionHandler and CascadeDetector over
 * the production source returns 0) and task-scope 1.4 (callers parse with `Trigger.User.parse`; the cascade trigger
 * is not a user trigger).
 *
 * Q32 port: the trigger vocabulary is the AdvanceService's (`Trigger.User.parse`), so the two advance scenarios drive the
 * shared service directly; the `advance` tool decodes its `trigger` as a strict enum before the service runs (AdvanceToolTest S14).
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class LegacyTransitionVocabularyGuardTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val retired = Regex("\\b(UserTrigger|VALID_TRIGGERS|USER_TRIGGERS|RoleTransitionHandler|CascadeDetector)\\b")

    private fun mainSourceRoot(): Path =
        listOf(Paths.get("src/main/kotlin"), Paths.get("current/src/main/kotlin"))
            .firstOrNull { Files.isDirectory(it) }
            ?: error("production source root not found from ${Paths.get("").toAbsolutePath()}")

    @Test
    fun `S14 no production source mentions a retired trigger or transition-handler vocabulary`() {
        val root = mainSourceRoot()
        val files = Files.walk(root).use { s -> s.filter { it.toString().endsWith(".kt") }.toList() }
        assertTrue(files.size > 100, "vacuity control: the scan really covers the production tree (${files.size} files)")
        assertTrue(files.any { it.fileName.toString() == "CurrentMain.kt" }, "vacuity control: the known entry point is scanned")

        val hits =
            files.flatMap { file ->
                Files.readAllLines(file, Charsets.UTF_8).withIndex().mapNotNull { (i, line) ->
                    retired.find(line)?.let { "${root.relativize(file)}:${i + 1}: ${it.value}" }
                }
            }

        assertEquals(emptyList(), hits, "retired vocabulary still present in production source")
    }

    @Test
    fun `S14 the advance service accepts a capitalised trigger and records the canonical wire form`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))
            val item = d.item("case", Role.QUEUE)

            val r = d.advanceService(item, "Start").success()

            assertEquals(Role.WORK, r.newRole, "'Start' is the start trigger: $r")
            assertEquals(Role.WORK, d.role(item))
            assertEquals("start", d.transitions(item).single().trigger)
        }

    @Test
    fun `S14 the cascade trigger is not a user trigger`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))
            val item = d.item("cascade attempt", Role.QUEUE)

            val r = assertIs<AdvanceFailure.ResolutionFailed>(d.advanceService(item, "cascade").failure())

            assertEquals(ErrorCode.INVALID_REQUEST, r.error.code, "an unknown user trigger is an invalid request: $r")
            assertEquals(Role.QUEUE, d.role(item))
            assertEquals(0, d.transitions(item).size)
        }
}
