package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11Driver.Companion.transitioned
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.P11_LEASE_KEY
import io.github.jpicklyk.mcptask.current.test.advanceDirect
import io.github.jpicklyk.mcptask.current.test.failure
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.success
import io.github.jpicklyk.mcptask.current.test.toRoleTransition
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Lease TTL, credentialRefs and a registry for the M1/M2 tests (a standalone constant: P11_BASE_YAML already owns `traits:`). */
private const val LEASE_GATE_YAML = """
work_item_schemas:
  ttl-item:
    default_traits: [ttl-keys]
    notes: []
  cred-item:
    default_traits: [cred-keys]
    notes: []
traits:
  ttl-keys:
    resources:
      - { key: ttl-override, mode: exclusive, ttlSeconds: 120 }
      - { key: ttl-registry, mode: exclusive }
      - { key: ttl-nodefault, mode: exclusive }
      - { key: ttl-zero, mode: exclusive, ttlSeconds: 0 }
      - { key: ttl-huge, mode: exclusive, ttlSeconds: 999999 }
  cred-keys:
    resources:
      - { key: cred-adv, mode: advisory }
      - { key: cred-excl, mode: exclusive, ttlSeconds: 60 }
resources:
  ttl-override:
    description: requirement ttl wins over this default
    defaultTtlSeconds: 999
  ttl-registry:
    description: registry default applies
    defaultTtlSeconds: 1800
  ttl-nodefault:
    description: registered with no default, so the server default applies
  ttl-zero:
    description: zero is clamped up
  ttl-huge:
    description: huge is clamped down
  cred-adv:
    description: advisory
  cred-excl:
    description: exclusive
  cred-registered:
    description: registered but not declared by the item
"""

/**
 * The resource-lease gate of [AdvanceService] on real SQLite: lease TTL resolution, the credentialRefs membership check and
 * derivation, release on every exit from WORK, and the start-cascade lease. Every oracle is a persisted lease row, item row
 * or `item.transitioned` event, never a mock call.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class AdvanceServiceLeaseGateTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private fun driver(
        dir: Path,
        yaml: String = P11_BASE_YAML,
    ) = P11Driver(EventLogRig.build(db.db, dir, yaml))

    private suspend fun P11Driver.leases(item: WorkItem) = raw.resourceLeaseRepository().findActiveForItem(item.id)

    @Test
    fun `M1 lease ttl is the requirement override, then the registry default, then the server default, clamped`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir, LEASE_GATE_YAML)
            val item = d.item("ttl", Role.QUEUE, type = "ttl-item")

            d.advanceService(item, "start").success()

            val ttls = d.leases(item).associate { it.resourceKey to Duration.between(it.acquiredAt, it.expiresAt).seconds }
            assertEquals(
                mapOf(
                    "ttl-override" to 120L,
                    "ttl-registry" to 1800L,
                    "ttl-nodefault" to 3600L,
                    "ttl-zero" to 1L,
                    "ttl-huge" to 86400L,
                ),
                ttls,
            )
        }

    @Test
    fun `M2 a credentialRef neither declared nor registered is rejected before any lease is taken`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir, LEASE_GATE_YAML)
            val item = d.item("cred", Role.QUEUE, type = "cred-item")

            val failure =
                d.rig.ctx
                    .advanceDirect(item, "start", credentialRefs = listOf("nobody-knows-this"))
                    .failure()

            val rejected = assertIs<AdvanceFailure.ValidationFailed>(failure, "$failure")
            assertTrue("nobody-knows-this" in rejected.message, rejected.message)
            assertEquals(Role.QUEUE, d.role(item))
            assertTrue(d.leases(item).isEmpty(), "no lease row after the rejection")
        }

    @Test
    fun `M2 a registry-only credentialRef is accepted and the consumed credentials are exclusive, advisory, then extras, deduplicated`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir, LEASE_GATE_YAML)
            val item = d.item("cred", Role.QUEUE, type = "cred-item")
            val mark = d.rig.maxSeq()

            d.rig.ctx
                .advanceDirect(item, "start", credentialRefs = listOf("cred-registered", "cred-excl", "cred-registered"))
                .success()

            assertEquals(Role.WORK, d.role(item))
            val consumed =
                d.rig
                    .rowsAfter(mark)
                    .transitioned()
                    .single()
                    .toRoleTransition()
                    .consumedCredentials
            assertEquals(listOf("cred-excl", "cred-adv", "cred-registered"), consumed)
            assertEquals(listOf("cred-excl"), d.leases(item).map { it.resourceKey }, "the advisory key takes no lease row")
        }

    @Test
    fun `M3 cancel, block and hold from work release the lease`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            for (trigger in listOf("cancel", "block", "hold")) {
                val item = d.item("leased $trigger", Role.WORK, type = "p11-leased")
                d.holdLease(item)
                assertEquals(1, d.leases(item).size, "$trigger: fixture holds the lease")

                d.advanceService(item, trigger).success()

                assertTrue(d.leases(item).isEmpty(), "$trigger releases the lease on leaving WORK")
            }
        }

    @Test
    fun `M3 control - entering work takes the lease and leaving work for review releases it`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val item = d.item("matrix", Role.QUEUE, type = "p11-matrix")
            d.note(item, "spec", "queue")
            d.advanceService(item, "start").success()
            assertEquals(1, d.leases(item).size, "entering WORK took the lease")
            d.note(item, "impl", "work")

            d.advanceService(item, "start").success()

            assertEquals(Role.REVIEW, d.role(item))
            assertEquals(0, d.leases(item).size)
        }

    @Test
    fun `M4 a start cascade whose lease is held by another item is reported as contended and the parent stays queued`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val parent = d.item("parent", Role.QUEUE, type = "p11-leased")
            val child = d.item("child", Role.QUEUE, parent = parent)
            d.holdLease(d.item("holder", Role.QUEUE))

            val r = d.advanceService(child, "start").success()

            assertEquals(Role.WORK, d.role(child))
            val cascade = r.cascadeEvents.single()
            assertEquals(false, cascade.applied, "$cascade")
            assertEquals(true, cascade.resourceBlocked, "$cascade")
            assertEquals(listOf(P11_LEASE_KEY), cascade.contendedResources)
            assertEquals(null, cascade.error)
            assertEquals(Role.QUEUE, d.role(parent))
            assertTrue(d.leases(parent).isEmpty())
        }

    @Test
    fun `M4 a start cascade with a free lease starts the parent and acquires it`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val parent = d.item("parent", Role.QUEUE, type = "p11-leased")
            val child = d.item("child", Role.QUEUE, parent = parent)

            val r = d.advanceService(child, "start").success()

            val cascade = r.cascadeEvents.single()
            assertEquals(true, cascade.applied, "$cascade")
            assertEquals(Role.WORK, d.role(parent))
            assertEquals(listOf(P11_LEASE_KEY), d.leases(parent).map { it.resourceKey })
        }
}
