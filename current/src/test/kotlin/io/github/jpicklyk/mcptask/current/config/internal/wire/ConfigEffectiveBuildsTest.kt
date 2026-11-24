package io.github.jpicklyk.mcptask.current.config.internal.wire

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.str
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * AC6 of item 6aa122da, the `effective` side: the `config` tool's `effective` op shows `builds: false` on a schema and on a
 * trait that set it, and omits `builds` where it is left at its default (task-scope R3: the flag is emitted only when false).
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ConfigEffectiveBuildsTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val yaml =
        "work_item_schemas:\n" +
            "  plan-only:\n    builds: false\n    notes: []\n" +
            "  build:\n    notes: []\n" +
            "traits:\n" +
            "  no-build:\n    builds: false\n" +
            "  notes-only:\n    notes:\n      - key: n\n        role: queue\n        required: false\n"

    private fun JsonObject.schema(type: String): JsonObject = list("schemas").first { it.str("type") == type }

    private fun JsonObject.trait(name: String): JsonObject = list("traits").first { it.str("name") == name }

    @Test
    fun `AC6 config effective shows builds false for a schema and a trait that set it and omits it otherwise`(): Unit =
        runBlocking {
            val view = CfgKit(EventLogRig.build(db.db, dir, yaml)).call(effectiveOp()).okResult()

            assertEquals("false", view.schema("plan-only")["builds"]?.toString(), "schema: ${view.schema("plan-only")}")
            assertFalse("builds" in view.schema("build"), "a building schema omits builds: ${view.schema("build")}")
            assertEquals("false", view.trait("no-build")["builds"]?.toString(), "trait: ${view.trait("no-build")}")
            assertFalse("builds" in view.trait("notes-only"), "a trait without builds omits it: ${view.trait("notes-only")}")
        }
}
