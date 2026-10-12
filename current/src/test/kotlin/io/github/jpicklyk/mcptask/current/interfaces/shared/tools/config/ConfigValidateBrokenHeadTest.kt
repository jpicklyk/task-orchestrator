package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.application.service.ConfigPushResult
import io.github.jpicklyk.mcptask.current.application.service.ItemCreateCommand
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `validate` must not depend on decoding the current ROOT head: a user repairing a root whose stored head is undecodable
 * validates the fixed candidate and gets a verdict, not `config_invalid`.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ConfigValidateBrokenHeadTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private fun kit() = CfgKit(EventLogRig.build(db.db, dir, "work_item_schemas: {}\n"))

    private val good =
        "work_item_schemas:\n  feature-task:\n    notes:\n      - key: spec\n        role: queue\n        required: true\n"

    private suspend fun brokenRoot(k: CfgKit): WorkItem {
        val root =
            k.rig.ctx.itemCommandService
                .create(ItemCreateCommand(parentId = null, title = "R", type = "project", tags = null))
                .orFail()
        assertIs<ConfigPushResult.Success>(checkNotNull(k.rig.ctx.configService).push(root.id, good))
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { it.execute("UPDATE config_versions SET canonical_json = 'not json {' WHERE scope = 'root'") }
        }
        return root
    }

    @Test
    fun `a broken root head with a valid candidate and no in-flight items is valid with count 0`(): Unit =
        runBlocking {
            val k = kit()
            val r = brokenRoot(k)

            val result = k.call(validateOp(r.id, good + "      - key: design\n        role: queue\n        required: true\n")).okResult()

            assertEquals(true, result["valid"]!!.jsonPrimitive.boolean, "$result")
            assertEquals(0, result["affectedInFlight"]!!.jsonObject["count"]!!.jsonPrimitive.int, "$result")
        }

    @Test
    fun `a broken root head with an invalid candidate reports its errors, not config_invalid`(): Unit =
        runBlocking {
            val k = kit()
            val r = brokenRoot(k)

            val result =
                k
                    .call(
                        validateOp(r.id, "work_item_schemas:\n  feature-task:\n    notes:\n      - key: spec\n        role: nonsense\n")
                    ).okResult()

            assertEquals(false, result["valid"]!!.jsonPrimitive.boolean, "$result")
            assertTrue((result["errors"] as JsonArray).jsonArray.isNotEmpty(), "$result")
            assertNull(result["affectedInFlight"], "$result")
        }
}
