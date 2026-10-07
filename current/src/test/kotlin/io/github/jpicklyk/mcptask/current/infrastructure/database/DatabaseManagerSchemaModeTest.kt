package io.github.jpicklyk.mcptask.current.infrastructure.database

import ch.qos.logback.classic.Level
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.SchemaMode
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.SchemaTestSupport.at
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.SchemaTestSupport.captureLogs
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.SchemaTestSupport.tableExists
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.SchemaTestSupport.urlFor
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.SchemaTestSupport.userTableCount
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SCHEMA_MODE and the ignored USE_FLYWAY at the [DatabaseManager] level, plus [SchemaMode.parse].
 *
 * Oracle: plan v4-phase1-core section 6 ("USE_FLYWAY is ignored", one WARN) and 3.10 (SCHEMA_MODE
 * migrate|validate; validate runs no migrate and no baseline; an unknown value fails startup);
 * the declarations' parse contract (trimmed, case-insensitive, blank = MIGRATE, else throws).
 */
class DatabaseManagerSchemaModeTest {
    private val managers = mutableListOf<DatabaseManager>()

    @AfterEach
    fun tearDown() {
        managers.forEach { it.shutdown() }
        managers.clear()
    }

    private fun manager(
        dir: Path,
        env: Map<String, String?>,
        file: File = dir.resolve("m-${System.nanoTime()}.db").toFile()
    ): Pair<DatabaseManager, File> {
        val full = mapOf("DATABASE_PATH" to file.absolutePath, "READINESS_FILE" to dir.resolve("ready").toString()) + env
        val config = AppConfig.fromEnv { key -> full[key] }
        val m = DatabaseManager(appConfig = config)
        managers += m
        assertTrue(m.initialize(config.databasePath), "initialize must succeed")
        return m to file
    }

    // ---- SchemaMode.parse ----

    @Test
    fun `parse accepts migrate and validate case-insensitively with surrounding whitespace`() {
        assertEquals(SchemaMode.MIGRATE, SchemaMode.parse("migrate"))
        assertEquals(SchemaMode.MIGRATE, SchemaMode.parse("MIGRATE"))
        assertEquals(SchemaMode.VALIDATE, SchemaMode.parse("validate"))
        assertEquals(SchemaMode.VALIDATE, SchemaMode.parse(" Validate "))
        assertEquals(SchemaMode.VALIDATE, SchemaMode.parse("\tVALIDATE\n"))
    }

    @Test
    fun `parse treats null and blank as MIGRATE`() {
        assertEquals(SchemaMode.MIGRATE, SchemaMode.parse(null))
        assertEquals(SchemaMode.MIGRATE, SchemaMode.parse(""))
        assertEquals(SchemaMode.MIGRATE, SchemaMode.parse("   "))
    }

    @Test
    fun `parse rejects unknown values`() {
        assertFailsWith<IllegalArgumentException> { SchemaMode.parse("foo") }
        assertFailsWith<IllegalArgumentException> { SchemaMode.parse("migrate validate") }
        assertFailsWith<IllegalArgumentException> { SchemaMode.parse("validat") }
        assertFailsWith<IllegalArgumentException> { SchemaMode.parse("true") }
    }

    // ---- S2: USE_FLYWAY is ignored with one WARN ----

    @Test
    fun `S2 USE_FLYWAY=false on an empty file logs one WARN naming it and still migrates`(
        @TempDir dir: Path
    ) {
        val (m, file) = manager(dir, mapOf("USE_FLYWAY" to "false"))
        var ok = false
        val warns = captureLogs { ok = m.updateSchema() }.at(Level.WARN).filter { it.contains("USE_FLYWAY") }

        assertTrue(ok, "USE_FLYWAY=false must not change the outcome")
        assertEquals(1, warns.size, "exactly one WARN naming USE_FLYWAY expected, got $warns")
        assertTrue(tableExists(urlFor(file), "flyway_schema_history"), "Flyway must have run")
    }

    @Test
    fun `S2 probe USE_FLYWAY set to the empty string still warns once`(
        @TempDir dir: Path
    ) {
        val (m, _) = manager(dir, mapOf("USE_FLYWAY" to ""))
        var ok = false
        val warns = captureLogs { ok = m.updateSchema() }.at(Level.WARN).filter { it.contains("USE_FLYWAY") }
        assertTrue(ok)
        assertEquals(1, warns.size, "any set value, including empty, warns once: $warns")
    }

    @Test
    fun `S2 probe USE_FLYWAY true also warns once, and an unset variable never warns`(
        @TempDir dir: Path
    ) {
        val (m, _) = manager(dir, mapOf("USE_FLYWAY" to "true"))
        val warns = captureLogs { assertTrue(m.updateSchema()) }.at(Level.WARN).filter { it.contains("USE_FLYWAY") }
        assertEquals(1, warns.size, "USE_FLYWAY=true is ignored too: $warns")

        val (m2, _) = manager(dir, emptyMap())
        val none = captureLogs { assertTrue(m2.updateSchema()) }.at(Level.WARN).filter { it.contains("USE_FLYWAY") }
        assertEquals(emptyList(), none, "an unset USE_FLYWAY must not warn")
    }

    // ---- S11: SCHEMA_MODE through DatabaseManager ----

    @Test
    fun `S11 SCHEMA_MODE=validate on an empty database fails and creates no tables`(
        @TempDir dir: Path
    ) {
        val (m, file) = manager(dir, mapOf("SCHEMA_MODE" to "validate"))
        assertFalse(m.updateSchema())
        assertEquals(0, userTableCount(urlFor(file)), "validate must create nothing")
    }

    @Test
    fun `S11 SCHEMA_MODE=validate passes on a database already at the current schema`(
        @TempDir dir: Path
    ) {
        val file = dir.resolve("shared.db").toFile()
        val (migrate, _) = manager(dir, emptyMap(), file)
        assertTrue(migrate.updateSchema())
        migrate.shutdown()
        managers.remove(migrate)

        val (validate, _) = manager(dir, mapOf("SCHEMA_MODE" to "validate"), file)
        assertTrue(validate.updateSchema())
    }

    @Test
    fun `S11 an unknown SCHEMA_MODE fails startup with an ERROR and creates no tables`(
        @TempDir dir: Path
    ) {
        val (m, file) = manager(dir, mapOf("SCHEMA_MODE" to "foo"))
        var ok = true
        val errors = captureLogs { ok = m.updateSchema() }.at(Level.ERROR)
        assertFalse(ok)
        assertTrue(errors.any { it.contains("SCHEMA_MODE") || it.contains("foo") }, "ERROR must identify the bad setting: $errors")
        assertEquals(0, userTableCount(urlFor(file)), "an invalid mode must not migrate anything")
    }

    @Test
    fun `S11 probe padded mixed-case value is parsed as validate, not ignored`(
        @TempDir dir: Path
    ) {
        // If the padded value were ignored (treated as migrate) this empty database would migrate and pass.
        val (m, file) = manager(dir, mapOf("SCHEMA_MODE" to " Validate "))
        assertFalse(m.updateSchema())
        assertEquals(0, userTableCount(urlFor(file)))
    }

    @Test
    fun `S11 probe empty and explicit migrate values behave as the default`(
        @TempDir dir: Path
    ) {
        val (blank, _) = manager(dir, mapOf("SCHEMA_MODE" to ""))
        assertTrue(blank.updateSchema(), "an empty SCHEMA_MODE means MIGRATE")
        val (explicit, file) = manager(dir, mapOf("SCHEMA_MODE" to "MIGRATE"))
        assertTrue(explicit.updateSchema())
        assertTrue(tableExists(urlFor(file), "flyway_schema_history"))
    }
}
