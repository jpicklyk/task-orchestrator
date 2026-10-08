package io.github.jpicklyk.mcptask.current.perf

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.tools.items.ManageItemsTool
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.interfaces.mcp.ServerComposition
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Recursive-delete latency through the production composition (event-recording decorator, P8 review finding 4).
 * Platform-gated: runs only with TO_PERF=1. Seeds a 500-item tree (2 notes of 1500 chars per item, 100 edges,
 * 10 lease holders) through the undecorated provider, then times `manage_items delete recursive=true` of the root.
 * Prints the per-run and median wall time; the only assertion is a generous sanity bound.
 */
@Tag("serial")
class RecursiveDeleteEventsBench {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `recursive delete of a 500-item tree`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("TO_PERF") == "1")
            val configDir = tempDir.resolve(".taskorchestrator")
            Files.createDirectories(configDir)
            Files.write(configDir.resolve("config.yaml"), "work_item_schemas: {}\n".toByteArray(Charsets.UTF_8))
            val appConfig = AppConfig.fromEnv { key -> if (key == "AGENT_CONFIG_DIR") tempDir.toString() else null }
            val composition = ServerComposition(appConfig, sqlite.databaseManager, ShutdownCoordinator()).build()
            val ctx = composition.toolContext
            val raw = sqlite.repositoryProvider()

            val timings = mutableListOf<Long>()
            repeat(RUNS + 1) { run ->
                val rootId = seedTree(raw, run)
                val started = System.nanoTime()
                val result =
                    ManageItemsTool().execute(
                        JsonObject(
                            mapOf(
                                "operation" to JsonPrimitive("delete"),
                                "itemIds" to JsonArray(listOf(JsonPrimitive(rootId.toString()))),
                                "recursive" to JsonPrimitive(true),
                            ),
                        ),
                        ctx,
                    ) as JsonObject
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertTrue(result["success"]!!.jsonPrimitive.boolean, "delete must succeed: $result")
                assertEquals(ITEMS, result["data"]!!.jsonObject["deleted"]!!.jsonPrimitive.int)
                if (run > 0) timings += elapsedMs // run 0 is the warm-up
            }
            val sorted = timings.sorted()
            println("RecursiveDeleteEventsBench runs(ms)=$timings median=${sorted[sorted.size / 2]}")
            assertTrue(sorted[sorted.size / 2] < 60_000, "sanity bound only")
        }

    private suspend fun seedTree(
        raw: RepositoryProvider,
        run: Int,
    ): UUID {
        val items = raw.workItemRepository()
        val root = items.create(WorkItem(title = "bench root $run", role = Role.QUEUE, depth = 0))
        val all = mutableListOf(root)
        val children = mutableListOf<WorkItem>()
        repeat(CHILDREN) { c ->
            children += items.create(WorkItem(title = "c$c", parentId = root.id, rootId = root.id, depth = 1, role = Role.QUEUE))
        }
        all += children
        var index = 0
        while (all.size < ITEMS) {
            val parent = children[index % CHILDREN]
            all += items.create(WorkItem(title = "g$index", parentId = parent.id, rootId = root.id, depth = 2, role = Role.QUEUE))
            index++
        }
        val body = "x".repeat(NOTE_BODY)
        val notes = raw.noteRepository()
        for (item in all) {
            notes.upsert(Note(itemId = item.id, key = "spec", role = "queue", body = body))
            notes.upsert(Note(itemId = item.id, key = "log", role = "work", body = body))
        }
        val deps = raw.dependencyRepository()
        for (i in 0 until EDGES) {
            deps.create(Dependency(fromItemId = all[1 + i].id, toItemId = all[1 + i + EDGES].id))
        }
        val leases = raw.resourceLeaseRepository()
        for (i in 0 until LEASE_HOLDERS) {
            leases.acquireAll(all[ITEMS - 1 - i].id, "bench", listOf("bench-$run-$i" to 600))
        }
        return root.id
    }

    companion object {
        private const val RUNS = 5
        private const val ITEMS = 500
        private const val CHILDREN = 20
        private const val NOTE_BODY = 1500
        private const val EDGES = 100
        private const val LEASE_HOLDERS = 10
    }
}
