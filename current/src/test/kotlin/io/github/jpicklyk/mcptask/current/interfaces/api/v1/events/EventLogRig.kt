package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.tools.ToolDefinition
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.time.SystemClock
import io.github.jpicklyk.mcptask.current.interfaces.mcp.CompositionResult
import io.github.jpicklyk.mcptask.current.interfaces.mcp.ServerComposition
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Files
import java.nio.file.Path

/** The `data` column of an event row as a JSON object. */
internal fun EventRecord.payload(): JsonObject = Json.parseToJsonElement(data).jsonObject

/** A string-valued key of the `data` column, null when the key is absent or JSON null. */
internal fun EventRecord.str(key: String): String? = payload()[key]?.jsonPrimitive?.contentOrNull

/**
 * Test rig for the P8 event log: the REAL production composition ([ServerComposition], API off, so no bus is
 * installed) over a [SqliteTestDatabase], with fixtures seeded through the UNDECORATED provider so the table holds
 * only rows that the operation under test wrote. Rows are read back through the undecorated event store.
 */
internal class EventLogRig(
    val db: SqliteTestDatabase,
    val composition: CompositionResult,
) {
    val ctx get() = composition.toolContext

    /** The decorated provider: every write through it records event rows in the caller's unit. */
    val provider: RepositoryProvider get() = ctx.repositoryProvider

    /** The undecorated provider used for fixtures and for reading the table back. */
    val raw get() = db.repositoryProvider()

    suspend fun rows(): List<EventRecord> = raw.eventStore().readAfter(0L, null, 100_000)

    suspend fun rowsAfter(seq: Long): List<EventRecord> = raw.eventStore().readAfter(seq, null, 100_000)

    suspend fun maxSeq(): Long = raw.eventStore().maxSeq()

    /** Runs [block] and returns the rows it wrote (the rows with seq above the high-water mark taken before). */
    suspend fun <T> written(block: suspend () -> T): Pair<T, List<EventRecord>> {
        val mark = maxSeq()
        val result = block()
        return result to rowsAfter(mark)
    }

    suspend fun call(
        tool: ToolDefinition,
        vararg params: Pair<String, JsonElement>,
    ): JsonObject = tool.execute(JsonObject(mapOf(*params)), ctx) as JsonObject

    suspend fun callOk(
        tool: ToolDefinition,
        vararg params: Pair<String, JsonElement>,
    ): JsonObject {
        val result = call(tool, *params)
        assertTrue(result["success"]!!.jsonPrimitive.boolean, "${tool.name} must succeed: $result")
        return result
    }

    /** Runs [block] inside one write unit of the composition (the decorated provider's rows join that unit). */
    suspend fun <T> inUnit(
        op: String = "EventLogRig.inUnit",
        block: suspend () -> T,
    ): T =
        when (val outcome = composition.unitOfWork.write(op) { Outcome.Ok(block()) }) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("unit '$op' failed: ${outcome.error.message}")
        }

    /** Seeds an item through the undecorated provider (no event rows). A child inherits its parent's root. */
    suspend fun seed(
        title: String,
        parent: WorkItem? = null,
        role: Role = Role.QUEUE,
        tags: String? = null,
    ): WorkItem =
        raw.workItemRepository().create(
            WorkItem(
                title = title,
                parentId = parent?.id,
                depth = (parent?.depth ?: -1) + 1,
                rootId = parent?.let { it.rootId ?: it.id },
                role = role,
                tags = tags,
            ),
        )

    companion object {
        const val GATED_TAG = "feature-task"

        const val GATED_CONFIG =
            "work_item_schemas:\n" +
                "  feature-task:\n" +
                "    notes:\n" +
                "      - key: spec\n" +
                "        role: queue\n" +
                "        required: true\n"

        fun build(
            db: SqliteTestDatabase,
            tempDir: Path,
            configYaml: String = "work_item_schemas: {}\n",
            clock: Clock = SystemClock,
            databaseManager: DatabaseManager = db.databaseManager,
        ): EventLogRig {
            val configDir = tempDir.resolve(".taskorchestrator")
            Files.createDirectories(configDir)
            Files.write(configDir.resolve("config.yaml"), configYaml.toByteArray(Charsets.UTF_8))
            val appConfig = AppConfig.fromEnv { key -> if (key == "AGENT_CONFIG_DIR") tempDir.toString() else null }
            val composition =
                ServerComposition(
                    appConfig = appConfig,
                    databaseManager = databaseManager,
                    shutdownCoordinator = ShutdownCoordinator(),
                    clock = clock,
                ).build()
            return EventLogRig(db, composition)
        }
    }
}

internal fun str(value: String): JsonElement = JsonPrimitive(value)
