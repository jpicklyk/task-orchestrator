package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Claim-write confinement guard (item 5cd1086c, task-scope AC6, test-plan S16; strengthened in round r2): the only
 * production caller of the store-level claim and lease writes is ClaimService, so the claim.* and lease.* rows it records
 * cannot be bypassed. In production code under `application/`, `interfaces/` and `infrastructure/`, outside ClaimService.kt,
 * the port declarations (`application/port/`) and the SQLite store implementations themselves, there must be no call to
 *  - the lease-store writes `acquireAll`, `releaseAllForItem`, `releaseAllForItems`, `forceReleaseByKey`, or
 *  - a work-item store claim write `claim(...)`, `release(...)`, `clear(...)` WITH an argument, on ANY receiver other than a
 *    ClaimService (`claimService.claim(...)`), however the receiver is named (`inner.claim(...)`, `store.clear(id)`), however
 *    the arguments are laid out (including a line break right after the opening parenthesis), and also as a bare call inside
 *    a `with(repo) { claim(...) }` / `run` / `apply` scope. A collection `clear()` with no argument is not a store write.
 *
 * The scan is zero-tolerance (no baseline): the acceptance criterion is that no such caller exists.
 *
 * Vacuity: [violations] and [inScope] are proven on the shapes they must flag and the shapes they must ignore (each new
 * pattern has a positive string and a negative control), and a control proves the scan does see the real store calls inside
 * ClaimService.kt (the excluded file) by their unique lease-store names.
 */
class ClaimWriteConfinementTest {
    companion object {
        const val CLAIM_SERVICE = "application/service/ClaimService.kt"
        const val PORT_DIR = "application/port/"
        val SCOPES = listOf("application/", "interfaces/", "infrastructure/")

        /** The SQLite classes that implement the claim and lease stores: the only infrastructure allowed to hold the SQL writes. */
        val STORE_IMPLEMENTATIONS =
            Regex("""^infrastructure/sqlite/repository/(SQLiteWorkItemRepository|SQLiteResourceLeaseRepository|SqliteClaimStore)\.kt$""")

        fun inScope(path: String): Boolean =
            path != CLAIM_SERVICE &&
                !path.startsWith(PORT_DIR) &&
                !STORE_IMPLEMENTATIONS.matches(path) &&
                SCOPES.any { path.startsWith(it) }

        /** Names that exist only on the lease store; any call by name is a lease write outside ClaimService. */
        val LEASE_WRITES = Regex("""\b(acquireAll|releaseAllForItems?|forceReleaseByKey)\s*\(""")

        /** `<receiver>[()][?].claim|release|clear(<at least one argument>`: the receiver is group 1, the line layout is free. */
        val RECEIVER_WRITES = Regex("""(\w+)\s*(?:\(\s*\))?\s*\??\.\s*(?:claim|release|clear)\s*\(\s*[^)\s]""")

        /** A bare `claim(` / `release(` / `clear(` call with an argument (a `with(repo) { ... }` body), not a declaration. */
        val BARE_WRITES = Regex("""(?<![\w.])(?<!fun )(?:claim|release|clear)\s*\(\s*[^)\s]""")

        /** Receivers that are not stores: the service itself. */
        val ALLOWED_RECEIVERS = setOf("claimService")

        /** Violating call sites in [text]: comment lines are dropped and string contents blanked before matching. */
        fun violations(text: String): Int {
            val code =
                text
                    .lines()
                    .filter { GuardSupport.isCodeLine(it) }
                    .joinToString("\n") { GuardSupport.stripStrings(it) }
            val receiverCalls = RECEIVER_WRITES.findAll(code).count { it.groupValues[1] !in ALLOWED_RECEIVERS }
            return LEASE_WRITES.findAll(code).count() + receiverCalls + BARE_WRITES.findAll(code).count()
        }
    }

    @Test
    fun `no production code outside ClaimService calls a store level claim or lease write`() {
        val inScope = GuardSupport.productionSources().filter { inScope(it.path) }
        assertTrue(inScope.size >= 80, "fixture: the scan covers application, interfaces and infrastructure, found ${inScope.size} files")
        assertTrue(
            inScope.any { it.path.startsWith("infrastructure/") && !it.path.startsWith("infrastructure/sqlite/") },
            "fixture: non-store infrastructure files are scanned"
        )
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
            "pending.clear()",
            "workItemRepository().getById(id)",
            "claimService.acquireLeases(id, actor, reqs)",
            "// repo.claim(id, agent, ttl)",
            "     * leaseRepo.acquireAll(id, actor, requirements)",
            "val msg = \"never call repo.claim(id) directly\"",
            "suspend fun release(itemId: UUID, agentId: String): Outcome<ReleaseResult>"
        ).forEach { assertEquals(0, violations(it), "wrongly flagged: $it") }
    }

    @Test
    fun `the guard flags a multi line argument list and keeps the negative control`() {
        listOf(
            "repo.claim(\n    id,\n    agent,\n    ttl\n)",
            "workItemRepository()\n    .release(\n        id,\n        agent\n    )",
            "leaseStore.acquireAll(\n    holder,\n    actor,\n    requirements\n)"
        ).forEach { assertEquals(1, violations(it), "multi line call not flagged: $it") }
        listOf(
            "claimService.claim(\n    id,\n    agent,\n    ttl\n)",
            "pending.clear(\n)",
            "// repo.claim(\n//    id\n"
        ).forEach { assertEquals(0, violations(it), "multi line control wrongly flagged: $it") }
    }

    @Test
    fun `the guard flags receivers that carry no store name and bare calls in a scope function`() {
        listOf(
            "inner.claim(id, agent, ttl)",
            "store.clear(id)",
            "delegate.release(id, agent)",
            "it.claim(id, agent, 60)",
            "with(repo) { claim(id, agent, 60) }",
            "with(repository) {\n    clear(id)\n}",
            "repo.run { release(id, agent) }"
        ).forEach { assertEquals(1, violations(it), "non store named receiver not flagged: $it") }
        listOf(
            "claimService.clear(id)",
            "store.clear()",
            "inner.clearAll(id)",
            "with(claimService) { acquireLeases(id, actor, reqs) }",
            "private suspend fun release(itemId: UUID, agentId: String): Outcome<Unit> {",
            "fun claim(itemId: UUID, agentId: String) = Unit"
        ).forEach { assertEquals(0, violations(it), "non store control wrongly flagged: $it") }
    }

    @Test
    fun `the scope covers infrastructure callers and excludes only the SQLite store implementations`() {
        listOf(
            "infrastructure/ExpirySweeper.kt",
            "infrastructure/IdempotencyPruner.kt",
            "infrastructure/sqlite/SomethingElse.kt",
            "infrastructure/sqlite/repository/Helper.kt",
            "infrastructure/sqlite/repository/SqliteEventStore.kt",
            "infrastructure/sqlite/repository/SQLiteNoteRepository.kt",
            "application/tools/ClaimItemTool.kt",
            "interfaces/api/v1/routes/ResourceLeaseRoutes.kt"
        ).forEach { assertTrue(inScope(it), "must be scanned: $it") }
        listOf(
            CLAIM_SERVICE,
            "application/port/ClaimStore.kt",
            "infrastructure/sqlite/repository/SQLiteWorkItemRepository.kt",
            "infrastructure/sqlite/repository/SQLiteResourceLeaseRepository.kt",
            "infrastructure/sqlite/repository/SqliteClaimStore.kt",
            "CurrentMain.kt"
        ).forEach { assertFalse(inScope(it), "must be excluded: $it") }
    }
}
