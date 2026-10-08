package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.support.UnscopedUnitOfWork
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.SqliteUnitOfWork
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Review follow-up B3 (item 9343ad8d): production wiring. The composition root must wire the real SQLite-backed
 * unit of work (a transaction boundary), not the no-transaction [UnscopedUnitOfWork] default of ToolExecutionContext.
 * Oracle: the P5a declarations, ServerComposition.build(): `unitOfWork = SqliteUnitOfWork(databaseManager,
 * effectiveProvider, SystemClock)` handed to both CompositionResult and ToolExecutionContext.
 */
class ServerCompositionUnitOfWorkTest {
    private fun buildDatabaseManager(): DatabaseManager {
        val database = Database.connect("jdbc:h2:mem:composition_uow_${System.nanoTime()};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        DirectDatabaseSchemaManager().updateSchema()
        return DatabaseManager(database)
    }

    @Test
    fun `the composition wires a SqliteUnitOfWork shared by the result and the tool context`() {
        val composition =
            ServerComposition(
                appConfig = AppConfig.fromEnv { null },
                databaseManager = buildDatabaseManager(),
                shutdownCoordinator = ShutdownCoordinator(),
            ).build()

        assertIs<SqliteUnitOfWork>(composition.unitOfWork, "CompositionResult.unitOfWork must be the SQLite unit of work")
        assertIs<SqliteUnitOfWork>(composition.toolContext.unitOfWork, "ToolExecutionContext must get the SQLite unit of work")
        assertSame(composition.unitOfWork, composition.toolContext.unitOfWork, "one unit of work instance is shared")
        assertFalse(composition.toolContext.unitOfWork is UnscopedUnitOfWork, "the no-transaction default must not be wired")
    }
}
