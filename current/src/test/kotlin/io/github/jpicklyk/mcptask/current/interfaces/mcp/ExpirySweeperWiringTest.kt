package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.UtcTimestamp
import io.mockk.every
import io.mockk.spyk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent wiring tests (test-author seat, item 5cd1086c, round r2, audit gap 4): the real [CurrentMcpServer]
 * composition starts the expiry sweeper with the server and registers its stop for shutdown.
 *
 * Oracle: task-scope "Sweep": the ExpirySweeper mirrors IdempotencyPruner (a pass at start) and is "wired in
 * CurrentMcpServer with a shutdown cleanup registered after Close Database"; AC4 "sweep at start + hourly ... stopped on
 * shutdown". Seam: the same entry point ShutdownDrainOrderTest uses (CurrentMcpServer over a temp database, stdio with
 * immediate EOF). Start is proven behaviourally: server 1 creates and migrates the database, the test then inserts an
 * item whose claim lapsed years ago straight through JDBC (columns per the work_items schema snapshot), server 2 starts
 * over the same file, and the events table must hold exactly one claim.expired for that item with no principal.
 * Stop is proven by the registered cleanup actions only (the shutdown coordinator is a spy): an action whose name
 * mentions the sweeper/expiry must be registered after "Close Database". The action name is not declared, so the match is
 * a case-insensitive "sweep|expir" pattern.
 */
class ExpirySweeperWiringTest {
    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun recordingCoordinator(names: MutableList<String>): ShutdownCoordinator {
        val spy = spyk(ShutdownCoordinator())
        every { spy.addCleanupAction(any(), any()) } answers {
            names += firstArg<String>()
            callOriginal()
        }
        return spy
    }

    private fun config(
        dbPath: Path,
        ready: Path
    ): AppConfig {
        val env =
            mapOf(
                "DATABASE_PATH" to dbPath.toString(),
                "MCP_TRANSPORT" to "stdio",
                "READINESS_FILE" to ready.toString()
            )
        return AppConfig.fromEnv { key -> env[key] }
    }

    private fun runServer(
        dbPath: Path,
        ready: Path,
        names: MutableList<String>
    ) {
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = recordingCoordinator(names),
                appConfig = config(dbPath, ready),
                stdioInput = { ByteArrayInputStream(ByteArray(0)) },
                stdioOutput = { ByteArrayOutputStream() }
            )
        assertEquals(Started, server.run())
    }

    private fun bytes(id: UUID): ByteArray =
        ByteBuffer
            .allocate(16)
            .putLong(id.mostSignificantBits)
            .putLong(id.leastSignificantBits)
            .array()

    private fun insertClaimedItem(
        dbPath: Path,
        id: UUID,
        expiredAt: Instant
    ) {
        val now = UtcTimestamp.format(Instant.parse("2020-01-01T00:00:00Z"))
        val claimed = UtcTimestamp.format(expiredAt.minusSeconds(900))
        val expires = UtcTimestamp.format(expiredAt)
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c
                .prepareStatement(
                    "INSERT INTO work_items (id, title, role, depth, created_at, modified_at, role_changed_at, " +
                        "claimed_by, claimed_at, claim_expires_at, original_claimed_at, root_id) " +
                        "VALUES (?, 'wiring lapsed claim', 'queue', 0, ?, ?, ?, 'agent-w', ?, ?, ?, ?)"
                ).use { ps ->
                    ps.setBytes(1, bytes(id))
                    ps.setString(2, now)
                    ps.setString(3, now)
                    ps.setString(4, now)
                    ps.setString(5, claimed)
                    ps.setString(6, expires)
                    ps.setString(7, claimed)
                    ps.setBytes(8, bytes(id))
                    ps.executeUpdate()
                }
        }
    }

    private data class Row(
        val entity: String,
        val principal: String?
    )

    private fun expiredRows(dbPath: Path): List<Row> =
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT hex(entity_id), principal_id FROM events WHERE type = 'claim.expired'").use { rs ->
                    buildList { while (rs.next()) add(Row(rs.getString(1).lowercase(), rs.getString(2))) }
                }
            }
        }

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun `the server starts the expiry sweeper with startup and registers its stop after Close Database`(
        @TempDir tempDir: Path
    ) {
        val dbPath = tempDir.resolve("wiring.db")
        val ready = tempDir.resolve("ready")
        runServer(dbPath, ready, CopyOnWriteArrayList())
        assertEquals(emptyList(), expiredRows(dbPath), "fixture: a fresh database has no expiry rows")
        val item = UUID.randomUUID()
        insertClaimedItem(dbPath, item, Instant.parse("2020-01-01T01:00:00Z"))
        assertEquals(emptyList(), expiredRows(dbPath), "control: inserting the lapsed claim records nothing by itself")

        val names = CopyOnWriteArrayList<String>()
        runServer(dbPath, ready, names)

        val rows = expiredRows(dbPath)
        assertEquals(
            listOf(item.toString().replace("-", "")),
            rows.map { it.entity.replace("-", "") },
            "the startup pass reported the lapsed claim exactly once: $rows"
        )
        assertNull(rows.single().principal, "a scheduled sweep row carries no actor")

        val db = names.indexOf("Close Database")
        assertTrue(db >= 0, "fixture: Close Database is registered, got $names")
        val sweeperStops =
            names.withIndex().filter { (idx, n) ->
                idx > db &&
                    Regex("sweep|expir", RegexOption.IGNORE_CASE).containsMatchIn(n)
            }
        assertTrue(sweeperStops.isNotEmpty(), "a sweeper shutdown cleanup must be registered after Close Database: $names")
    }
}
