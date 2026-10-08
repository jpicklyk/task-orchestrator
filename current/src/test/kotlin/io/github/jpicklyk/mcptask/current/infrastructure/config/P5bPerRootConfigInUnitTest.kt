package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.database.countRows
import io.github.jpicklyk.mcptask.current.infrastructure.database.createProbeTables
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.writeTx
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Independent P5b test S11 (item c01d2e90): the last-known-good config layer is served only OUTSIDE a unit.
 *
 * Oracle: task-scope S11 / test-plan "config read fault: outside a unit, warm cache -> cached parse; inside ->
 * PerRootConfigUnavailableException" (the read fault is a real one: the project_config table is renamed away after the
 * cache is warm). A unit that served a stale cached layer after a store fault would act on config its own transaction
 * could not confirm, and the F8 poison forbids committing after a fault anyway.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class P5bPerRootConfigInUnitTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val db get() = sqlite.db

    private val yaml =
        """
        work_item_schemas:
          bug-fix:
            notes:
              - key: repro-steps
                role: queue
                required: true
                description: "Repro steps"
        """.trimIndent()

    private fun breakConfigReads() {
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { it.execute("ALTER TABLE project_config RENAME TO project_config_gone") }
        }
    }

    private suspend fun warmService(): Pair<PerRootConfigService, java.util.UUID> {
        val root = db.repositoryProvider().workItemRepository().create(WorkItem(title = "S11 root", type = "project"))
        SQLiteProjectConfigRepository(db.databaseManager).upsert(root.id, yaml)
        val service = PerRootConfigService(SQLiteProjectConfigRepository(db.databaseManager))
        val warm = assertNotNull(service.layer(root.id), "the first read parses the stored row")
        assertEquals(
            "repro-steps",
            warm.document.workItemSchemas["bug-fix"]
                ?.notes
                ?.get(0)
                ?.key
        )
        return service to root.id
    }

    @Test
    fun `S11 outside a unit a read fault on a warm cache serves the cached parse`(): Unit =
        runBlocking {
            val (service, rootId) = warmService()
            breakConfigReads()
            val layer = assertNotNull(service.layer(rootId), "last-known-good must be served outside a unit")
            assertEquals(
                "repro-steps",
                layer.document.workItemSchemas["bug-fix"]
                    ?.notes
                    ?.get(0)
                    ?.key
            )
        }

    @Test
    fun `S11 inside a write unit the config read throws and the unit ends in the cause-translated Err, nothing committed`(): Unit =
        runBlocking {
            val (service, rootId) = warmService()
            db.createProbeTables()
            breakConfigReads()
            var inBlock: Throwable? = null

            val result =
                db.unitOfWork().write<Unit>("S11.write") {
                    db.databaseManager.writeTx("S11.probe") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") }
                    try {
                        service.layer(rootId)
                    } catch (e: PerRootConfigUnavailableException) {
                        inBlock = e
                        throw e
                    }
                    Outcome.Ok(Unit)
                }

            val thrown = assertIs<PerRootConfigUnavailableException>(inBlock, "last-known-good must NOT be served inside a unit")
            assertEquals(rootId, thrown.rootId)
            val err = assertIs<Outcome.Err>(result, "$result")
            assertEquals(ErrorCode.INTERNAL, err.error.code, "translated by its SQL cause (no such table)")
            assertEquals(0, db.databaseManager.countRows("p5a_probe"), "nothing the block wrote may be committed")
        }

    @Test
    fun `S11 inside a read unit the same read fault raises PerRootConfigUnavailableException`(): Unit =
        runBlocking {
            val (service, rootId) = warmService()
            breakConfigReads()
            val e = assertFailsWith<PerRootConfigUnavailableException> { db.unitOfWork().read { service.layer(rootId) } }
            assertEquals(rootId, e.rootId)
        }

    @Test
    fun `S11 control - inside a unit with a healthy table the layer is served normally`(): Unit =
        runBlocking {
            val (service, rootId) = warmService()
            val layer = db.unitOfWork().read { service.layer(rootId) }
            assertEquals(
                "repro-steps",
                assertNotNull(layer)
                    .document.workItemSchemas["bug-fix"]
                    ?.notes
                    ?.get(0)
                    ?.key
            )
        }
}
