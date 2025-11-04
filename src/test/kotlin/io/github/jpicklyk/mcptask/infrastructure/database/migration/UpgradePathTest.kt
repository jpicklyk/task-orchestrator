package io.github.jpicklyk.mcptask.infrastructure.database.migration

import io.github.jpicklyk.mcptask.infrastructure.database.schema.management.FlywayDatabaseSchemaManager
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import java.sql.Connection

/**
 * Tests upgrading from v1.0.1 to v2.0.0.
 *
 * This simulates the real production upgrade scenario where:
 * - Production has V1-V4 already applied (v1.0.1)
 * - V5-V7 add all v2.0 enhancements (templates and tag taxonomy)
 */
class UpgradePathTest {

    private lateinit var database: Database
    private lateinit var tempFile: java.io.File

    @BeforeEach
    fun setUp() {
        tempFile = java.io.File.createTempFile("test_upgrade_path", ".db")
        tempFile.deleteOnExit()

        database = Database.connect(
            url = "jdbc:sqlite:${tempFile.absolutePath}",
            driver = "org.sqlite.JDBC",
            setupConnection = { connection ->
                connection.createStatement().executeUpdate("PRAGMA foreign_keys = ON")
            }
        )
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
    }

    @Test
    fun `should successfully upgrade from v1_0_1 to v2_0_0`() {
        // Step 1: Apply all migrations from v1.0.1 baseline through v2.0.0 enhancements
        // V1-V4: v1.0.1 schema, V5-V7: v2.0.0 templates and tag taxonomy

        val schemaManager = FlywayDatabaseSchemaManager(database)

        // Apply all migrations
        val result = schemaManager.updateSchema()
        assertTrue(result, "Migration should succeed")

        // Verify we reach version 7 (V1 + V2 + V3 + V4 + V5 + V6 + V7)
        val currentVersion = schemaManager.getCurrentVersion()
        assertEquals(7, currentVersion, "Should reach version 7 after all migrations")

        // Verify v2.0 features are present
        val connection = database.connector().connection as Connection

        // Check that version columns exist (from V3)
        val stmt = connection.createStatement()
        val rs = stmt.executeQuery("PRAGMA table_info(projects)")
        var hasVersionColumn = false
        while (rs.next()) {
            if (rs.getString("name") == "version") {
                hasVersionColumn = true
            }
        }
        rs.close()
        stmt.close()

        assertTrue(hasVersionColumn, "Projects table should have version column from V3")

        // Verify templates were initialized (from V5 and V7)
        val templateStmt = connection.createStatement()
        val templateRs = templateStmt.executeQuery("SELECT COUNT(*) as count FROM templates")
        templateRs.next()
        val templateCount = templateRs.getInt("count")
        templateRs.close()
        templateStmt.close()

        assertTrue(templateCount >= 9, "Should have at least 9 templates from V5+V7 (found $templateCount)")

        connection.close()
    }
}
