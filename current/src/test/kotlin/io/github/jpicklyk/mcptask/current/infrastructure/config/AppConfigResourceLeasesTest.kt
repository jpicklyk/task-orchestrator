package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.mcp.ServerComposition
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.P11_LEASE_KEY
import io.github.jpicklyk.mcptask.current.test.contendedNames
import io.github.jpicklyk.mcptask.current.test.errorCode
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * Independent tests (test-author seat, item ad684e46 / Q17, scenario S6) for the enforcement kill switch moving into
 * [AppConfig]: `RESOURCE_LEASES_ENFORCED` is parsed once at startup by the shared boolean rules, and `false` skips the lease
 * gate and the lease acquisition on every advance while releases still run.
 *
 * Oracles: task-scope 1.6 (`AppConfig.resourceLeasesEnforced: Boolean = true`, parsed in `fromEnv` by the EnvBoolean rules:
 * `true`/`1`/`yes` and `false`/`0`/`no`, trimmed and case-insensitive, an unrecognised value keeps the default `true`; "Semantics
 * once read are unchanged: false skips the lease gate and acquisition on every advance; releases still run"), AC7 and decision
 * D12 (restart required). The behaviour half drives the real composition over the P11 fixtures (a type whose default trait
 * takes an exclusive lease on [P11_LEASE_KEY]); the rejected shape (`resource_unavailable` with `contendedResources`) is the
 * documented gate answer pinned by AdvanceGateOrderTest.
 *
 * Every "the gate was skipped" assertion has a same-fixture control with the switch unset in which the gate rejects, so a
 * fixture that never contended would fail the control first.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class AppConfigResourceLeasesTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    // ------------------------------------------------------------------------------------------------ parsing

    private fun parsed(value: String?): Boolean =
        AppConfig.fromEnv { key -> if (key == "RESOURCE_LEASES_ENFORCED") value else null }.resourceLeasesEnforced

    @Test
    fun `S6 the switch defaults to enforced when the variable is unset`() {
        assertEquals(true, parsed(null))
        assertEquals(true, AppConfig.fromEnv { null }.resourceLeasesEnforced)
    }

    @Test
    fun `S6 true, 1 and yes keep enforcement on, in any case and with surrounding whitespace`() {
        for (value in listOf("true", "TRUE", "True", "1", "yes", "YES", " true ", "\t1\n", " Yes ")) {
            assertEquals(true, parsed(value), "value '$value'")
        }
    }

    @Test
    fun `S6 false, 0 and no turn enforcement off, in any case and with surrounding whitespace`() {
        for (value in listOf("false", "FALSE", "False", "0", "no", "NO", " FALSE ", "\tno\n", " 0 ")) {
            assertEquals(false, parsed(value), "value '$value'")
        }
    }

    @Test
    fun `S6 an unrecognised or empty value falls back to enforced`() {
        for (value in listOf("off", "on", "disabled", "2", "-1", "falsey", "yes!", "nope", "")) {
            assertEquals(true, parsed(value), "value '$value'")
        }
    }

    @Test
    fun `S6 only the exact upper-case variable name is read`() {
        val config = AppConfig.fromEnv { key -> if (key == "resource_leases_enforced") "false" else null }

        assertEquals(true, config.resourceLeasesEnforced, "a differently cased name is not the switch")
    }

    // ------------------------------------------------------------------------------------------------ behaviour

    private fun driver(
        name: String,
        switch: String?
    ): P11Driver {
        val agent = dir.resolve(name).also { Files.createDirectories(it) }
        val configDir = agent.resolve(".taskorchestrator").also { Files.createDirectories(it) }
        Files.write(configDir.resolve("config.yaml"), P11_BASE_YAML.toByteArray(Charsets.UTF_8))
        val appConfig =
            AppConfig.fromEnv { key ->
                when (key) {
                    "AGENT_CONFIG_DIR" -> agent.toString()
                    "RESOURCE_LEASES_ENFORCED" -> switch
                    else -> null
                }
            }
        val composition =
            ServerComposition(appConfig = appConfig, databaseManager = db.databaseManager, shutdownCoordinator = ShutdownCoordinator())
                .build()
                .also { runBlocking { it.dataStepRunner.run() } }
        return P11Driver(EventLogRig(db.db, composition))
    }

    private suspend fun leasedItem(d: P11Driver): WorkItem = d.item("leased ${UUID.randomUUID()}", Role.QUEUE, type = "p11-leased")

    @Test
    fun `S6 with the switch unset a start into WORK past a held exclusive lease is rejected as resource_unavailable`(): Unit =
        runBlocking {
            val d = driver("default", null)
            val holder = d.item("lease holder", Role.QUEUE)
            d.holdLease(holder)
            val item = leasedItem(d)

            val result = d.advance(item, "start")

            assertEquals(false, result.flag("ok"), "$result")
            assertEquals("resource_unavailable", result.errorCode(), "$result")
            assertEquals(listOf(P11_LEASE_KEY), result.contendedNames(), "$result")
            assertEquals(Role.QUEUE, d.role(item))
        }

    @Test
    fun `S6 with the switch false the same advance is applied and takes no lease`(): Unit =
        runBlocking {
            val enforced = driver("control", "true")
            val holder = enforced.item("lease holder", Role.QUEUE)
            enforced.holdLease(holder)
            val controlItem = leasedItem(enforced)
            assertEquals("resource_unavailable", enforced.advance(controlItem, "start").errorCode(), "control: the gate rejects")

            for ((index, value) in listOf("false", "0", "no", " FALSE ").withIndex()) {
                val d = driver("off-$index", value)
                val item = leasedItem(d)

                val result = d.advance(item, "start")

                assertEquals(true, result.flag("ok"), "switch '$value': the gate is skipped: $result")
                assertEquals(Role.WORK, d.role(item), "switch '$value'")
                assertEquals(
                    0,
                    d.raw
                        .resourceLeaseRepository()
                        .findActiveForItem(item.id)
                        .size,
                    "switch '$value': acquisition is skipped too"
                )
            }
        }

    @Test
    fun `S6 an unrecognised switch value keeps the gate on`(): Unit =
        runBlocking {
            val d = driver("bogus", "off")
            val holder = d.item("lease holder", Role.QUEUE)
            d.holdLease(holder)
            val item = leasedItem(d)

            val result = d.advance(item, "start")

            assertEquals("resource_unavailable", result.errorCode(), "$result")
            assertEquals(Role.QUEUE, d.role(item))
        }

    @Test
    fun `S6 releases still run when the switch is false`(): Unit =
        runBlocking {
            val d = driver("release", "false")
            val holding = d.item("holds the lease in WORK", Role.WORK, type = "p11-leased")
            d.holdLease(holding)
            assertEquals(
                1,
                d.raw
                    .resourceLeaseRepository()
                    .findActiveForItem(holding.id)
                    .size,
                "fixture: the item holds the lease before it completes"
            )

            val result = d.advance(holding, "complete")

            assertEquals(true, result.flag("ok"), "$result")
            assertEquals(Role.TERMINAL, d.role(holding))
            assertEquals(
                0,
                d.raw
                    .resourceLeaseRepository()
                    .findActiveForItem(holding.id)
                    .size,
                "completing releases the lease even with enforcement off"
            )
        }
}
