package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.EligibilityQuery
import io.github.jpicklyk.mcptask.current.application.port.ItemSlice
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.NoOpActorVerifier
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.advanceTool
import io.github.jpicklyk.mcptask.current.test.claimSelectOp
import io.github.jpicklyk.mcptask.current.test.errorCode
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.onlyResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.testAccess
import io.github.jpicklyk.mcptask.current.test.text
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Independent tests (test-author seat, item 46a83aca) for the selector-claim ancestor re-check: scenario S10 of the frozen
 * test-plan.
 *
 * Oracle (frozen, planning phase): task-scope item 7 and AC5 - in a find-and-claim, for each candidate and inside the claim's
 * write unit the candidate's ancestors are re-read; an ancestor with an active claim held by another principal skips the candidate
 * and counts it; the loop continues with the next candidate; when none is claimable the element is `{claimed: false, reason:
 * "claim_held", retryAfterMs: 30000, excluded: {claimed, ancestorClaimed (+ the skipped count), dependencyBlocked, outOfScope}}`
 * with nothing written; an ancestor held by the caller never skips; a claimed selection reports `selected: true`. The no-match
 * element shape, `retryAfterMs` 30000 and the `excluded` object are the pre-existing contract of api-reference.md.
 *
 * Harness (the stale read): the read-side eligibility query already hides a candidate whose ancestor is claimed, so the re-check
 * is observable only when the claim lands AFTER that read. The recommender reads through `WorkItemRepository.findEligible` (the
 * public seam NextItemRecommenderTest pins). [InterloperRepository] returns that read's genuine result and then, once, takes the
 * interloping claim through the store, exactly as a concurrent claimer would between the read and the claim unit. The claim unit
 * itself runs on the real stores. Each scenario asserts the interloper fired, so a changed read path cannot make it vacuous.
 *
 * Cells: S10 (a), (b), (c) NEW-SURFACE (the re-check is new); the pre-held control is EXISTING-SURFACE. The context is a minimal
 * [ToolExecutionContext] over the decorated provider (the shape P5bWriteUnitToolsTest uses); the clock starts at the wall clock so
 * the 900 s claims are active under any component clock.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class SelectorAncestorClaimTest {
    private val clock = SettableClock(Instant.now())

    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod(clock = clock)

    private val real: WorkItemRepository get() = sqlite.db.repositoryProvider().workItemRepository()

    /** Returns the genuine eligibility read, then runs [interlope] once after the first read that excludes claimed items. */
    private class InterloperRepository(
        private val delegate: WorkItemRepository,
        private val interlope: suspend () -> Unit
    ) : WorkItemRepository by delegate {
        var fired = 0
            private set

        override suspend fun findEligible(
            query: EligibilityQuery,
            limit: Int,
            offset: Int,
            scope: AccessScope
        ): ItemSlice {
            val slice = delegate.findEligible(query, limit, offset, scope)
            if (fired == 0 && !query.includeClaimed) {
                fired++
                interlope()
            }
            return slice
        }
    }

    private class InterloperProvider(
        private val delegate: RepositoryProvider,
        private val repository: WorkItemRepository
    ) : RepositoryProvider by delegate {
        override fun workItemRepository(): WorkItemRepository = repository
    }

    private fun contextOver(repository: WorkItemRepository): ToolExecutionContext =
        ToolExecutionContext(
            InterloperProvider(sqlite.db.repositoryProvider(), repository),
            actorVerifier = NoOpActorVerifier,
            unitOfWork = sqlite.db.unitOfWork()
        )

    private suspend fun root(
        title: String,
        role: Role = Role.WORK
    ): WorkItem = real.create(WorkItem(title = title, role = role, depth = 0).let { it.copy(rootId = it.id) })

    private suspend fun child(
        title: String,
        parent: WorkItem,
        priority: Priority = Priority.MEDIUM
    ): WorkItem =
        real.create(
            WorkItem(
                title = title,
                role = Role.QUEUE,
                parentId = parent.id,
                depth = parent.depth + 1,
                rootId = parent.rootId ?: parent.id,
                priority = priority
            )
        )

    private suspend fun reload(item: WorkItem): WorkItem = checkNotNull(real.getById(item.id)) { "item ${item.title}" }

    private fun under(parent: WorkItem): JsonObject = buildJsonObject { put("parentId", parent.id.toString()) }

    private suspend fun select(
        ctx: ToolExecutionContext,
        by: String,
        filters: JsonObject
    ): JsonObject = ctx.advanceTool(claimSelectOp(filters), access = testAccess(by)).onlyResult()

    private fun JsonObject.excluded(name: String): String = getValue("excluded").jsonObject.text(name) ?: "absent"

    // ------------------------------------------------------------------ control (existing surface)

    @Test
    fun `S10 control - a parent already held by another principal hides its child from the read, so the select is claim_held`(): Unit =
        runBlocking {
            val parent = root("P")
            val c = child("C", parent)
            real.claim(parent.id, "agent-b", ttlSeconds = 900)
            val repo = InterloperRepository(real) { }

            val element = select(contextOver(repo), "agent-a", under(parent))

            assertEquals(true, element.flag("ok"))
            assertEquals(false, element.flag("claimed"))
            assertEquals("claim_held", element.text("reason"))
            assertEquals("30000", element.text("retryAfterMs"))
            assertEquals("1", element.excluded("ancestorClaimed"))
            assertNull(reload(c).claimedBy)
        }

    // ------------------------------------------------------------------ S10 (a): the stale read

    @Test
    fun `S10 an ancestor claimed by another principal after the eligibility read skips the candidate inside the claim unit`(): Unit =
        runBlocking {
            val parent = root("P")
            val c = child("C", parent)
            val repo = InterloperRepository(real) { real.claim(parent.id, "agent-b", ttlSeconds = 900) }

            val element = select(contextOver(repo), "agent-a", under(parent))

            assertEquals(1, repo.fired, "the interloper claimed the parent after the read, as the harness intends")
            assertEquals("agent-b", reload(parent).claimedBy, "fixture: the parent really is held by the other principal")
            assertEquals(true, element.flag("ok"), "no match is not an error: $element")
            assertEquals(false, element.flag("claimed"), "the candidate under a held ancestor is not claimed: $element")
            assertEquals("claim_held", element.text("reason"))
            assertEquals("30000", element.text("retryAfterMs"))
            assertEquals("1", element.excluded("ancestorClaimed"), "the skipped candidate is counted: $element")
            assertNull(reload(c).claimedBy, "nothing was written for the candidate")
            assertFalse("selected" in element, "$element")
        }

    @Test
    fun `S10 an ancestor held by the caller itself never skips the candidate`(): Unit =
        runBlocking {
            val parent = root("P")
            val c = child("C", parent)
            val repo = InterloperRepository(real) { }
            real.claim(parent.id, "agent-a", ttlSeconds = 900)

            val element = select(contextOver(repo), "agent-a", under(parent))

            assertEquals(true, element.flag("claimed"), "$element")
            assertEquals(true, element.flag("selected"), "$element")
            assertEquals(c.id.toString(), element.text("itemId"))
            assertEquals("agent-a", reload(c).claimedBy)
        }

    @Test
    fun `S10 the caller's own claim landing after the read does not skip the candidate either`(): Unit =
        runBlocking {
            val parent = root("P")
            val c = child("C", parent)
            val repo = InterloperRepository(real) { real.claim(parent.id, "agent-a", ttlSeconds = 900) }

            val element = select(contextOver(repo), "agent-a", under(parent))

            assertEquals(1, repo.fired)
            assertEquals(true, element.flag("claimed"), "$element")
            assertEquals(c.id.toString(), element.text("itemId"))
            assertEquals("agent-a", reload(c).claimedBy)
        }

    @Test
    fun `S10 when the first candidate is shielded the loop continues and claims the second`(): Unit =
        runBlocking {
            val shieldedParent = root("P1")
            val openParent = root("P2")
            val first = child("first, high priority", shieldedParent, Priority.HIGH)
            val second = child("second, medium priority", openParent, Priority.MEDIUM)
            val repo = InterloperRepository(real) { real.claim(shieldedParent.id, "agent-b", ttlSeconds = 900) }

            val element = select(contextOver(repo), "agent-a", buildJsonObject { })

            assertEquals(1, repo.fired)
            assertEquals(true, element.flag("claimed"), "the second candidate is claimed: $element")
            assertEquals(true, element.flag("selected"))
            assertEquals(second.id.toString(), element.text("itemId"))
            assertEquals("agent-a", reload(second).claimedBy)
            assertNull(reload(first).claimedBy, "the shielded first candidate stays unclaimed")
        }

    @Test
    fun `S10 an ancestor whose claim lapsed before the call does not skip the candidate`(): Unit =
        runBlocking {
            val parent = root("P")
            val c = child("C", parent)
            real.claim(parent.id, "agent-b", ttlSeconds = 1)
            // both the settable clock and the wall clock pass the one-second expiry, whichever one the claim unit reads
            clock.advanceSeconds(2)
            delay(1_600)
            val repo = InterloperRepository(real) { }

            val element = select(contextOver(repo), "agent-a", under(parent))

            assertEquals("agent-b", reload(parent).claimedBy, "fixture: the lapsed claim row is still stored")
            assertEquals(true, element.flag("claimed"), "a lapsed ancestor claim fences nothing: $element")
            assertEquals(c.id.toString(), element.text("itemId"))
            assertEquals("agent-a", reload(c).claimedBy)
        }
    // ------------------------------------------------------------------ probe

    @Test
    fun `probe the candidate itself claimed after the read stays with its claimant and is not taken`(): Unit =
        runBlocking {
            val parent = root("P")
            val c = child("C", parent)
            val repo = InterloperRepository(real) { real.claim(c.id, "agent-b", ttlSeconds = 900) }

            val element = select(contextOver(repo), "agent-a", under(parent))

            assertEquals(1, repo.fired)
            assertEquals(false, element.flag("ok"), "the lost race on the candidate itself is the claim_held element error: $element")
            assertEquals("claim_held", element.errorCode(), "$element")
            assertNotEquals("agent-a", reload(c).claimedBy, "the candidate is not taken from its claimant")
        }
}
