package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceLease
import io.github.jpicklyk.mcptask.current.domain.model.ResourceLeaseInterval
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent test authorship for item `af37c467` (needs-test-author, stream IW): MCP
 * `advance_item` apply-failure / same-call lease-release coverage — S2, S3 of the frozen
 * `test-plan` note (queue phase, read before this file existed), plus an adversarial probe.
 *
 * CHARACTERIZATION-FIRST (dispatch contract, `plans/decompose-complexity-hotspots.md`): these
 * tests drive the CURRENT, pre-refactor `AdvanceItemTool.executeTransitions` and must be green on
 * base `dd26e9e2`. They are the safety net for an upcoming decomposition of that method's
 * per-transition loop.
 *
 * Oracles: `current/docs/api-reference.md` `advance_item` "Rejected-entry fields" / `errorCode`
 * paragraph, and `current/docs/workflow-guide.md`'s resource-lease same-call release rule ("any
 * lease(s) that call itself just acquired are released before the failure is returned — same for
 * a cascade whose own resource acquire succeeded but whose apply then failed").
 *
 * SEAMS (own file, H2 via [DefaultRepositoryProvider] + [DirectDatabaseSchemaManager] — the same
 * technique as `ManageItemsParentPlacementInTxnTest`, chosen over full MockK so the release
 * assertions read the REAL persisted lease/role state rather than a stubbed return value):
 * - [FailingRoleTransitionRepository] wraps the real `roleTransitionRepository()`, failing
 *   `create()` only for a chosen item id — per the dispatch declarations' "Behavioral seams" note,
 *   this is how the apply step (`workItemRepository.update` then `roleTransitionRepository.create`
 *   inside one transaction) is forced to fail.
 * - [SimpleLeaseFakeRepository] is an in-memory [ResourceLeaseRepository] fake, reimplemented in
 *   this file per the test-author "own file" rule (mirrors the established pattern in
 *   `AdvanceRouteResourceLeaseTest.LeaseGateFakeRepository` / `AdvanceItemToolLeaseBlockedTest`).
 * - [TraitSchemaService] maps the `needs-staging-db` trait onto one exclusive, 600s-TTL resource —
 *   same mapping `AdvanceItemToolLeaseBlockedTest` uses for its own trait fixture.
 *
 * BLINDNESS: authored from `task-scope`/`test-plan` (queue-phase, frozen, `keys`-filtered
 * `query_notes`), the verbatim declarations block supplied in the dispatch prompt, and existing
 * conventions in this package (`AdvanceItemToolLeaseBlockedTest.kt`,
 * `ManageItemsParentPlacementInTxnTest.kt`). No `src/main` file, diff, or commit was read.
 */
class AdvanceItemToolApplyFailureLeaseTest {
    /** Wraps a real [RoleTransitionRepository]; fails `create()` only for [failFor]'s transitions. */
    private class FailingRoleTransitionRepository(
        private val delegate: RoleTransitionRepository,
        private val failFor: UUID
    ) : RoleTransitionRepository by delegate {
        override suspend fun create(transition: RoleTransition): Result<RoleTransition> =
            if (transition.itemId == failFor) {
                Result.Error(RepositoryError.DatabaseError("simulated apply failure for $failFor"))
            } else {
                delegate.create(transition)
            }
    }

    private class RoleTransitionOverrideProvider(
        private val delegate: RepositoryProvider,
        private val roleTransitionRepo: RoleTransitionRepository
    ) : RepositoryProvider by delegate {
        override fun roleTransitionRepository(): RoleTransitionRepository = roleTransitionRepo
    }

    /** In-memory [ResourceLeaseRepository] fake; own-file reimplementation per test-author rule 8. */
    private class SimpleLeaseFakeRepository : ResourceLeaseRepository {
        val leases = mutableListOf<ResourceLease>()

        override suspend fun acquireAll(
            holderItemId: UUID,
            actorId: String?,
            requirements: List<Pair<String, Int>>
        ): LeaseAcquireResult {
            val now = Instant.now()
            val acquired =
                requirements.map { (key, ttl) ->
                    ResourceLease(
                        resourceKey = key,
                        holderItemId = holderItemId,
                        acquiredByActorId = actorId,
                        acquiredAt = now,
                        expiresAt = now.plusSeconds(ttl.toLong()),
                        originalAcquiredAt = now,
                        version = 0
                    )
                }
            leases += acquired
            return LeaseAcquireResult.Success(acquired)
        }

        override suspend fun releaseAllForItem(holderItemId: UUID): LeaseReleaseResult {
            val before = leases.size
            leases.removeAll { it.holderItemId == holderItemId }
            return LeaseReleaseResult.Success(before - leases.size)
        }

        override suspend fun forceReleaseByKey(
            resourceKey: String,
            actorId: String?
        ): LeaseReleaseResult {
            val before = leases.size
            leases.removeAll { it.resourceKey == resourceKey }
            return LeaseReleaseResult.Success(before - leases.size)
        }

        override suspend fun findActiveByKeys(keys: List<String>): List<ResourceLease> = leases.filter { it.resourceKey in keys }

        override suspend fun findActiveForItem(holderItemId: UUID): List<ResourceLease> = leases.filter { it.holderItemId == holderItemId }

        override suspend fun findAllActive(): List<ResourceLease> = leases.toList()

        override suspend fun findHoldersAt(
            resourceKey: String?,
            at: Instant
        ): List<ResourceLeaseInterval> = emptyList()

        override suspend fun findRecentIntervals(
            resourceKey: String?,
            limit: Int
        ): List<ResourceLeaseInterval> = emptyList()
    }

    private class LeaseOverrideProvider(
        private val delegate: RepositoryProvider,
        private val leaseRepo: ResourceLeaseRepository
    ) : RepositoryProvider by delegate {
        override fun resourceLeaseRepository(): ResourceLeaseRepository = leaseRepo
    }

    /** Maps `needs-staging-db` onto one exclusive resource with a 600s TTL. */
    private class TraitSchemaService : WorkItemSchemaService {
        override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = null

        override fun getTraitResources(traitName: String): List<ResourceRequirement> =
            if (traitName == "needs-staging-db") {
                listOf(ResourceRequirement("staging-db-credential", ResourceMode.EXCLUSIVE, 600))
            } else {
                emptyList()
            }
    }

    private fun buildProvider(): DefaultRepositoryProvider {
        val dbName = "advance_apply_failure_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        DirectDatabaseSchemaManager().updateSchema()
        return DefaultRepositoryProvider(DatabaseManager(database))
    }

    private fun startParams(vararg itemIds: UUID): JsonObject =
        buildJsonObject {
            put(
                "transitions",
                buildJsonArray {
                    itemIds.forEach { id ->
                        add(
                            buildJsonObject {
                                put("itemId", id.toString())
                                put("trigger", "start")
                            }
                        )
                    }
                }
            )
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S2 — single-item apply failure via advance_item
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S2 apply failure via advance_item is reported as apply_failed with same-call lease release`() =
        runBlocking {
            val repositoryProvider = buildProvider()
            val item =
                (
                    repositoryProvider.workItemRepository().create(
                        WorkItem(title = "Traited S2", role = Role.QUEUE, depth = 0, properties = """{"traits":["needs-staging-db"]}""")
                    ) as Result.Success
                ).data
            val failingRoleTx = FailingRoleTransitionRepository(repositoryProvider.roleTransitionRepository(), failFor = item.id)
            val leaseFake = SimpleLeaseFakeRepository()
            val provider = LeaseOverrideProvider(RoleTransitionOverrideProvider(repositoryProvider, failingRoleTx), leaseFake)
            val context = ToolExecutionContext(provider, TraitSchemaService())

            val result = AdvanceItemTool().execute(startParams(item.id), context) as JsonObject
            val data = result["data"]!!.jsonObject
            val transition = data["results"]!!.jsonArray[0].jsonObject

            assertEquals(false, transition["applied"]!!.jsonPrimitive.boolean, "actual: $transition")
            assertEquals("apply_failed", transition["errorCode"]!!.jsonPrimitive.content, "actual: $transition")
            assertEquals("transient", transition["errorKind"]!!.jsonPrimitive.content, "actual: $transition")
            val summary = data["summary"]!!.jsonObject
            assertEquals(1, summary["total"]!!.jsonPrimitive.int, "actual: $summary")
            assertEquals(0, summary["succeeded"]!!.jsonPrimitive.int, "actual: $summary")
            assertEquals(1, summary["failed"]!!.jsonPrimitive.int, "actual: $summary")

            val persisted = (repositoryProvider.workItemRepository().getById(item.id) as Result.Success).data
            assertEquals(Role.QUEUE, persisted.role, "the failed apply must not leave the item in WORK")
            assertTrue(leaseFake.findActiveForItem(item.id).isEmpty(), "the same-call fresh lease must be released on apply failure")
        }

    @Test
    fun `probe S2 the same item twice in one batch both report apply_failed with leases released`() =
        runBlocking {
            val repositoryProvider = buildProvider()
            val item =
                (
                    repositoryProvider.workItemRepository().create(
                        WorkItem(
                            title = "Traited S2 batch",
                            role = Role.QUEUE,
                            depth = 0,
                            properties = """{"traits":["needs-staging-db"]}"""
                        )
                    ) as Result.Success
                ).data
            val failingRoleTx = FailingRoleTransitionRepository(repositoryProvider.roleTransitionRepository(), failFor = item.id)
            val leaseFake = SimpleLeaseFakeRepository()
            val provider = LeaseOverrideProvider(RoleTransitionOverrideProvider(repositoryProvider, failingRoleTx), leaseFake)
            val context = ToolExecutionContext(provider, TraitSchemaService())

            val result = AdvanceItemTool().execute(startParams(item.id, item.id), context) as JsonObject
            val results = result["data"]!!.jsonObject["results"]!!.jsonArray
            assertEquals(2, results.size, "actual: $result")
            results.forEach { r ->
                val obj = r.jsonObject
                assertEquals(false, obj["applied"]!!.jsonPrimitive.boolean, "actual: $obj")
                assertEquals("apply_failed", obj["errorCode"]!!.jsonPrimitive.content, "actual: $obj")
            }
            assertTrue(leaseFake.findActiveForItem(item.id).isEmpty(), "leases must be empty after both failed attempts")
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S3 — cascade apply failure: child applies, parent cascade fails, parent lease released
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S3 starting a child while the parent cascade apply fails still applies the child`() =
        runBlocking {
            val repositoryProvider = buildProvider()
            val p =
                (
                    repositoryProvider.workItemRepository().create(
                        WorkItem(title = "P S3", role = Role.QUEUE, depth = 0, properties = """{"traits":["needs-staging-db"]}""")
                    ) as Result.Success
                ).data
            val pStamped = (repositoryProvider.workItemRepository().update(p.copy(rootId = p.id)) as Result.Success).data
            val c =
                (
                    repositoryProvider.workItemRepository().create(
                        WorkItem(title = "C S3", role = Role.QUEUE, parentId = pStamped.id, depth = 1, rootId = pStamped.id)
                    ) as Result.Success
                ).data
            val failingRoleTx = FailingRoleTransitionRepository(repositoryProvider.roleTransitionRepository(), failFor = pStamped.id)
            val leaseFake = SimpleLeaseFakeRepository()
            val provider = LeaseOverrideProvider(RoleTransitionOverrideProvider(repositoryProvider, failingRoleTx), leaseFake)
            val context = ToolExecutionContext(provider, TraitSchemaService())

            val result = AdvanceItemTool().execute(startParams(c.id), context) as JsonObject
            val data = result["data"]!!.jsonObject
            val transition = data["results"]!!.jsonArray[0].jsonObject

            assertEquals(true, transition["applied"]!!.jsonPrimitive.boolean, "actual: $transition")
            assertEquals("work", transition["newRole"]!!.jsonPrimitive.content, "actual: $transition")

            val cascadeEvents = transition["cascadeEvents"]?.jsonArray
            assertTrue(cascadeEvents != null && cascadeEvents.isNotEmpty(), "actual: $transition")
            val cascade = cascadeEvents!![0].jsonObject
            assertEquals(pStamped.id.toString(), cascade["itemId"]?.jsonPrimitive?.content, "actual: $cascade")
            assertEquals(false, cascade["applied"]?.jsonPrimitive?.boolean, "actual: $cascade")
            assertTrue(cascade["error"]?.jsonPrimitive?.content?.isNotBlank() == true, "actual: $cascade")
            assertTrue(
                "gateBlocked" !in cascade,
                "an apply-failure cascade event (not a gate failure) must omit gateBlocked entirely: $cascade"
            )

            val persistedP = (repositoryProvider.workItemRepository().getById(pStamped.id) as Result.Success).data
            assertEquals(Role.QUEUE, persistedP.role, "the failed parent cascade must not leave P in WORK")
            assertTrue(
                leaseFake.findActiveForItem(pStamped.id).isEmpty(),
                "the cascade's own fresh lease must be released on apply failure"
            )
        }

    // Probe catalog, recorded per skill §6 (every probe attempted, including N/A ones):
    // - boundary/suffix, alternate separators, encoded/UNC forms, mixed case: N/A - itemId is a
    //   UUID identifying an existing row, not a path/string surface.
    // - empty vs absent vs null: N/A - both scenarios target an existing traited item.
    // - duplicates/ordering: covered above ("probe S2 the same item twice in one batch...").
    // - replay/idempotency: `advance_item` has no idempotency-key surface (REST-only); N/A here,
    //   covered on the REST surface by `ItemWriteRoutesFailurePathTest`'s S1 replay probe.
}
