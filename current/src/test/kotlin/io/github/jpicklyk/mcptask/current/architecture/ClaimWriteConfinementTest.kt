package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Claim-write confinement guard (item 5cd1086c, task-scope AC6, test-plan S16): the only production caller of the
 * store-level claim and lease writes is ClaimService, so the claim.* and lease.* rows it records cannot be bypassed.
 * In production code under `application/` and `interfaces/`, outside ClaimService.kt and the port declarations
 * (`application/port/`), there must be no call to
 *  - the lease-store writes `acquireAll`, `releaseAllForItem`, `releaseAllForItems`, `forceReleaseByKey`, or
 *  - the work-item store claim writes `claim(...)`, `release(...)`, `clear(...)` made on a work-item repository / store
 *    receiver (`workItemRepository()`, `...Repo`, `...Repository`, `...ClaimStore`, ...). Calls on a ClaimService
 *    receiver (`claimService.claim(...)`) and a collection `clear()` with no argument are not store writes.
 *
 * The scan is zero-tolerance (no baseline): the acceptance criterion is that no such caller exists.
 *
 * Vacuity: [violations] is proven on the shapes it must flag and the shapes it must ignore, and a control proves the
 * scan does see the real store calls inside ClaimService.kt (the excluded file) by their unique lease-store names.
 */
class ClaimWriteConfinementTest {
    companion object {
        const val CLAIM_SERVICE = "application/service/ClaimService.kt"
        const val PORT_DIR = "application/port/"
        val SCOPES = listOf("application/", "interfaces/")

        /** Names that exist only on the lease store; any call by name is a lease write outside ClaimService. */
        val LEASE_WRITES = Regex("""\b(acquireAll|releaseAllForItems?|forceReleaseByKey)\s*\(""")

        /** claim / release / clear with at least one argument, called on a work-item store receiver. */
        val CLAIM_WRITES =
            Regex(
                """\b(?:workItem\w*|item\w*|repo|repository|\w*Repo|\w*Repository|\w*[Cc]laimStore)\s*(?:\(\s*\))?\s*\??\.\s*(?:claim|release|clear)\s*\(\s*[^)\s]"""
            )

        /** Violating call sites in [text]: comment lines are dropped and string contents blanked before matching. */
        fun violations(text: String): Int {
            val code =
                text
                    .lines()
                    .filter { GuardSupport.isCodeLine(it) }
                    .joinToString("\n") { GuardSupport.stripStrings(it) }
            return LEASE_WRITES.findAll(code).count() + CLAIM_WRITES.findAll(code).count()
        }
    }

    @Test
    fun `no production code outside ClaimService calls a store level claim or lease write`() {
        val inScope =
            GuardSupport.productionSources().filter { source ->
                source.path != CLAIM_SERVICE && !source.path.startsWith(PORT_DIR) && SCOPES.any { source.path.startsWith(it) }
            }
        assertTrue(inScope.size >= 50, "fixture: the scan covers the application and interfaces layers, found ${inScope.size} files")
        val actual = GuardSupport.countByFile(inScope) { violations(it.text) }
        assertEquals(emptyMap(), actual, "store-level claim/lease writes outside ClaimService (file to call count): $actual")
    }

    @Test
    fun `the scan sees the real lease store calls inside ClaimService itself`() {
        val claimService = GuardSupport.productionSources().singleOrNull { it.path == CLAIM_SERVICE }
        assertTrue(claimService != null, "$CLAIM_SERVICE must exist as the single owner of claim and lease writes")
        val code =
            claimService.text
                .lines()
                .filter { GuardSupport.isCodeLine(it) }
                .joinToString("\n")
        assertTrue(LEASE_WRITES.containsMatchIn(code), "control: ClaimService.kt calls the lease store writes the guard looks for")
    }

    @Test
    fun `the guard flags each store write shape and ignores ClaimService calls comments and strings`() {
        val flagged =
            listOf(
                "repositoryProvider.workItemRepository().claim(id, agent, ttl)",
                "stores.workItemRepository()?.release(id, agent)",
                "ctx.workItemRepository().clear(item.id)",
                "workItemRepo.claim(itemId, agentId, ttlSeconds = 60)",
                "claimStore.clear(id)",
                "leaseRepo.acquireAll(id, actor, requirements)",
                "x.resourceLeaseRepository().releaseAllForItem(id)",
                "provider.resourceLeaseRepository().releaseAllForItems(ids)",
                "store.forceReleaseByKey(key, actor)",
                "            .workItemRepository()\n            .claim(id, agent, 5)"
            )
        flagged.forEach { assertEquals(1, violations(it), "not flagged: $it") }
        listOf(
            "claimService.claim(id, agent, ttl)",
            "ctx.claimService.release(id, agent)",
            "claims.clear(id)",
            "pending.clear()",
            "workItemRepository().getById(id)",
            "claimService.acquireLeases(id, actor, reqs)",
            "// repo.claim(id, agent, ttl)",
            "     * leaseRepo.acquireAll(id, actor, requirements)",
            "val msg = \"never call repo.claim(id) directly\"",
            "suspend fun release(itemId: UUID, agentId: String): Outcome<ReleaseResult>"
        ).forEach { assertEquals(0, violations(it), "wrongly flagged: $it") }
    }
}
