package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStep
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepException
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepKind
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepPhase
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.UpgradeHarness
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent tests for item 6b998895 (Q2a): the data-step boot hook of [CurrentMcpServer].
 *
 * Oracle: the item's frozen `task-scope` AC6 (`CurrentMcpServer.runServer()` maps any `DataStepException` to
 * `Failed(Reason.DATA_STEPS, detail)` BEFORE the background services and any transport, so the readiness marker is never
 * written), AC7 (boot order: schema update, composition, data steps, background services, transport; a FLYWAY_REPAIR run
 * exits before composition so it never runs a step; SCHEMA_MODE=validate still runs steps, decision D4) and AC8 (the
 * `extraDataSteps` constructor seam), plus `test-plan` S9 and S14, and `current/docs/fleet-deployment.md`
 * "Startup, Readiness, and Health Checks".
 *
 * The control pattern: a passing ONCE step plus an `onBeforeTransportStart` hook that throws a sentinel. The hook is the
 * first statement of the transport start, so reaching it proves the step ran BEFORE the transport stage, and the outcome
 * is `Failed(TRANSPORT_START)`, without binding a port or reading stdin.
 */
@Timeout(60)
class DataStepsStartupTest {
    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private class Step(
        override val name: String,
        override val phase: DataStepPhase = DataStepPhase.ITEM_BACKFILL,
        override val kind: DataStepKind = DataStepKind.ONCE,
        private val body: suspend () -> Int = { 1 }
    ) : DataStep {
        val runs = AtomicInteger()

        override suspend fun run(scope: WriteScope): Int {
            runs.incrementAndGet()
            return body()
        }
    }

    private fun dbIn(tempDir: Path): Path = tempDir.resolve("data-steps-${System.nanoTime()}.db")

    private fun appConfig(
        tempDir: Path,
        db: Path = dbIn(tempDir),
        readinessFile: Path = tempDir.resolve("ready"),
        extraEnv: Map<String, String> = emptyMap()
    ): AppConfig {
        val configDir = tempDir.resolve("agent-config").also { Files.createDirectories(it) }
        val env =
            mapOf(
                "DATABASE_PATH" to db.toString(),
                "MCP_TRANSPORT" to "http",
                "MCP_HTTP_HOST" to "127.0.0.1",
                "MCP_HTTP_PORT" to "0",
                "READINESS_FILE" to readinessFile.toString(),
                "AGENT_CONFIG_DIR" to configDir.toString()
            ) + extraEnv
        return AppConfig.fromEnv { key -> env[key] }
    }

    private fun server(
        config: AppConfig,
        steps: List<DataStep>,
        onTransport: (String) -> Unit = {}
    ) = CurrentMcpServer(
        version = "test",
        shutdownCoordinator = ShutdownCoordinator(),
        appConfig = config,
        onBeforeTransportStart = onTransport,
        extraDataSteps = steps
    )

    /** A transport hook that records its call into [events] and then fails, so run() returns instead of serving. */
    private fun stopAtTransport(events: MutableList<String>): (String) -> Unit =
        { transport ->
            events += "transport:$transport"
            throw IllegalStateException("stop-at-transport")
        }

    private fun assertStoppedAtTransport(outcome: StartupOutcome) {
        assertTrue(outcome is Failed, "expected Failed(TRANSPORT_START), got $outcome")
        assertEquals(Reason.TRANSPORT_START, outcome.reason, "detail: ${outcome.detail}")
        assertTrue("stop-at-transport" in outcome.detail, "the sentinel hook must be what stopped the run: ${outcome.detail}")
    }

    private fun ledger(db: Path): List<String> =
        DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath().toString().replace('\\', '/')).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT name FROM data_steps ORDER BY name").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    // ---- S9: a failing step stops startup as DATA_STEPS, before any transport and with no readiness marker ----

    @Test
    fun `S9 a throwing ONCE step fails startup as DATA_STEPS naming the step, before the transport`(
        @TempDir tempDir: Path
    ) {
        val readinessFile = tempDir.resolve("ready")
        val failing = Step("exploding-step") { throw IllegalStateException("kaboom") }
        var transportReached = false

        val outcome = server(appConfig(tempDir, readinessFile = readinessFile), listOf(failing)) { transportReached = true }.run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.DATA_STEPS, outcome.reason)
        assertTrue("exploding-step" in outcome.detail, "detail must name the step: ${outcome.detail}")
        assertEquals(1, failing.runs.get())
        assertFalse(transportReached, "the transport stage must never start after a data-step failure")
        assertFalse(Files.exists(readinessFile), "the readiness marker must never be written")
    }

    @Test
    fun `S9 a stale readiness marker is cleared by a data-step failure`(
        @TempDir tempDir: Path
    ) {
        val readinessFile = tempDir.resolve("ready")
        Files.writeString(readinessFile, "ready")
        assertTrue(Files.exists(readinessFile), "precondition: the stale marker exists before run()")
        val failing = Step("exploding-step") { throw IllegalStateException("kaboom") }

        val outcome = server(appConfig(tempDir, readinessFile = readinessFile), listOf(failing)).run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.DATA_STEPS, outcome.reason)
        assertFalse(Files.exists(readinessFile), "a process that never became ready must not look healthy")
    }

    @Test
    fun `a throwing EVERY_BOOT step fails startup as DATA_STEPS too`(
        @TempDir tempDir: Path
    ) {
        val failing = Step("boot-explodes", DataStepPhase.CONFIG_IMPORT, DataStepKind.EVERY_BOOT) { throw IllegalStateException("kaboom") }

        val outcome = server(appConfig(tempDir), listOf(failing)).run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.DATA_STEPS, outcome.reason)
        assertTrue("boot-explodes" in outcome.detail, "detail must name the step: ${outcome.detail}")
    }

    @Test
    fun `a step that throws DataStepException itself also maps to DATA_STEPS`(
        @TempDir tempDir: Path
    ) {
        val failing = Step("self-reporting") { throw DataStepException("self-reporting refuses: marker-text-123") }

        val outcome = server(appConfig(tempDir), listOf(failing)).run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.DATA_STEPS, outcome.reason)
        assertTrue("marker-text-123" in outcome.detail, "detail must carry the exception message: ${outcome.detail}")
    }

    @Test
    fun `an invalid step graph fails startup as DATA_STEPS before any step runs`(
        @TempDir tempDir: Path
    ) {
        val one = Step("dup-step")
        val two = Step("dup-step")
        var transportReached = false

        val outcome = server(appConfig(tempDir), listOf(one, two)) { transportReached = true }.run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.DATA_STEPS, outcome.reason)
        assertTrue("dup-step" in outcome.detail, "detail must name the step: ${outcome.detail}")
        assertEquals(0, one.runs.get() + two.runs.get(), "a bad graph must fail before any step runs")
        assertFalse(transportReached)
    }

    // ---- AC7: boot order (control: the step runs, then the transport stage is reached) ----

    @Test
    fun `a passing ONCE step runs before the transport stage and its row is committed`(
        @TempDir tempDir: Path
    ) {
        val db = dbIn(tempDir)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val step =
            Step("ordered-step") {
                events += "step"
                2
            }

        val outcome = server(appConfig(tempDir, db = db), listOf(step), stopAtTransport(events)).run()

        assertStoppedAtTransport(outcome)
        assertEquals(listOf("step", "transport:http"), events.toList(), "the data step must run before the transport stage")
        assertEquals(listOf("ordered-step"), ledger(db))
    }

    @Test
    fun `a second boot on the same database skips the ONCE step and reruns the EVERY_BOOT step`(
        @TempDir tempDir: Path
    ) {
        val db = dbIn(tempDir)
        val once1 = Step("once-step")
        val boot1 = Step("boot-step", DataStepPhase.CONFIG_IMPORT, DataStepKind.EVERY_BOOT)
        assertStoppedAtTransport(server(appConfig(tempDir, db = db), listOf(once1, boot1), stopAtTransport(mutableListOf())).run())

        val once2 = Step("once-step")
        val boot2 = Step("boot-step", DataStepPhase.CONFIG_IMPORT, DataStepKind.EVERY_BOOT)
        assertStoppedAtTransport(server(appConfig(tempDir, db = db), listOf(once2, boot2), stopAtTransport(mutableListOf())).run())

        assertEquals(1, once1.runs.get())
        assertEquals(0, once2.runs.get(), "the second boot must find the first boot's row and skip the ONCE step")
        assertEquals(1, boot1.runs.get())
        assertEquals(1, boot2.runs.get(), "an EVERY_BOOT step runs on every boot")
        assertEquals(listOf("once-step"), ledger(db), "only the ONCE step is ever recorded")
    }

    @Test
    fun `with no extra steps startup reaches the transport stage and the ledger stays empty`(
        @TempDir tempDir: Path
    ) {
        val db = dbIn(tempDir)
        val events = Collections.synchronizedList(mutableListOf<String>())

        val outcome = server(appConfig(tempDir, db = db), emptyList(), stopAtTransport(events)).run()

        assertStoppedAtTransport(outcome)
        assertEquals(listOf("transport:http"), events.toList())
        assertEquals(emptyList(), ledger(db))
    }

    // ---- S14: a FLYWAY_REPAIR run exits before composition and never runs a step ----

    @Test
    fun `S14 FLYWAY_REPAIR=true returns RepairCompleted and never runs a data step`(
        @TempDir tempDir: Path
    ) {
        val once = Step("repair-once")
        val boot = Step("repair-boot", DataStepPhase.CONFIG_IMPORT, DataStepKind.EVERY_BOOT)
        var transportReached = false
        val readinessFile = tempDir.resolve("ready")

        val outcome =
            server(
                appConfig(tempDir, readinessFile = readinessFile, extraEnv = mapOf("FLYWAY_REPAIR" to "true")),
                listOf(once, boot)
            ) { transportReached = true }.run()

        assertEquals(RepairCompleted, outcome, "expected RepairCompleted, got $outcome")
        assertEquals(0, once.runs.get() + boot.runs.get(), "a repair run must not run any data step")
        assertFalse(transportReached)
        assertFalse(Files.exists(readinessFile))
    }

    // ---- D4: SCHEMA_MODE=validate still runs steps ----

    @Test
    fun `D4 SCHEMA_MODE=validate on a current database still runs the data steps`(
        @TempDir tempDir: Path
    ) {
        val db = dbIn(tempDir)
        UpgradeHarness.migrate(UpgradeHarness.urlFor(db.toFile()))
        val once = Step("validate-once")
        val boot = Step("validate-boot", DataStepPhase.CONFIG_IMPORT, DataStepKind.EVERY_BOOT)
        val events = Collections.synchronizedList(mutableListOf<String>())

        val outcome =
            server(
                appConfig(tempDir, db = db, extraEnv = mapOf("SCHEMA_MODE" to "validate")),
                listOf(once, boot),
                stopAtTransport(events)
            ).run()

        assertStoppedAtTransport(outcome)
        assertEquals(1, once.runs.get(), "validate guards DDL, not data: the ONCE step must run")
        assertEquals(1, boot.runs.get())
        assertEquals(listOf("validate-once"), ledger(db))
    }

    @Test
    fun `D4 a failing step under SCHEMA_MODE=validate fails startup as DATA_STEPS`(
        @TempDir tempDir: Path
    ) {
        val db = dbIn(tempDir)
        UpgradeHarness.migrate(UpgradeHarness.urlFor(db.toFile()))
        val failing = Step("validate-explodes") { throw IllegalStateException("kaboom") }

        val outcome = server(appConfig(tempDir, db = db, extraEnv = mapOf("SCHEMA_MODE" to "validate")), listOf(failing)).run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.DATA_STEPS, outcome.reason)
        assertEquals(1, failing.runs.get())
    }

    // ---- no regression: earlier failure reasons win, and no step runs behind a failed schema update ----

    @Test
    fun `a schema update failure still reports SCHEMA_UPDATE and runs no data step`(
        @TempDir tempDir: Path
    ) {
        mockkConstructor(DatabaseManager::class)
        every { anyConstructed<DatabaseManager>().initialize(any()) } returns true
        every { anyConstructed<DatabaseManager>().updateSchema() } returns false
        val step = Step("never-runs")

        val outcome = server(appConfig(tempDir), listOf(step)).run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.SCHEMA_UPDATE, outcome.reason)
        assertEquals(0, step.runs.get(), "data steps come after the schema update")
    }

    @Test
    fun `Reason declares DATA_STEPS next to the existing startup reasons`() {
        val names = Reason.entries.map { it.name }.toSet()
        assertTrue(
            names.containsAll(
                listOf("DATABASE_INIT", "SCHEMA_UPDATE", "UNKNOWN_TRANSPORT", "READINESS_MARKER", "TRANSPORT_START", "DATA_STEPS")
            ),
            "Reason must declare DATA_STEPS and keep every earlier name, got $names"
        )
    }
}
