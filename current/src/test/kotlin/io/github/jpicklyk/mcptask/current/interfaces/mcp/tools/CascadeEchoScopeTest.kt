package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.IwKit
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.parityAccess
import io.github.jpicklyk.mcptask.current.test.FENCE_T
import io.github.jpicklyk.mcptask.current.test.FenceRig
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.advanceTool
import io.github.jpicklyk.mcptask.current.test.arr
import io.github.jpicklyk.mcptask.current.test.completeTreeOp
import io.github.jpicklyk.mcptask.current.test.deleteOp
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.newFenceRig
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.onlyResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.strings
import io.github.jpicklyk.mcptask.current.test.testAccess
import io.github.jpicklyk.mcptask.current.test.text
import io.github.jpicklyk.mcptask.current.test.transitionOp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 46a83aca) for the scope filter on cascade and unblocked echoes: scenario S8 of the
 * frozen test-plan, on the three surfaces that echo them (the `advance` transition, `items_write` `complete_tree` and
 * `items_write` `delete`).
 *
 * Oracle (frozen, planning phase): task-scope item 5 and AC3 - under a restricted scope an out-of-scope cascade entry is emitted
 * as `{itemId}` only (no roles, applied, blocked, missing, contended, blockers; for `items_write` no from/to/applied/reason) and
 * an out-of-scope unblocked entry as `{itemId}` (no title); an unrestricted scope's output is unchanged (the full entry). The
 * entry shapes are the pre-existing ones of api-reference.md (`{itemId, previousRole, targetRole, applied, ...}`,
 * `{itemId, title}`, `{itemId, from, to, applied, reason?}`).
 *
 * All cells are NEW-SURFACE (the filter is new). Every reduction assertion is paired with the unrestricted control through the
 * same call shape on an identical forest, and with a partial case (a root scope admits the parent but not the unblocked item) so
 * a filter that reduces everything, or nothing, cannot pass.
 *
 * Fixtures: C (tag alpha, WORK) under P (tag beta, WORK) completes P by cascade and unblocks X (tag beta, QUEUE) through a BLOCKS
 * edge C->X; the restricted token admits only tag alpha, so P and X are out of scope while C is admitted.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class CascadeEchoScopeTest {
    private val clock = SettableClock(FENCE_T)

    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod(clock = clock)

    private fun rig(dir: Path): FenceRig = newFenceRig(sqlite.db, dir, clock)

    private val alphaOnly =
        ParityToken("echo-alpha", setOf(ApiCapability.READ, ApiCapability.ADVANCE, ApiCapability.WRITE_ITEMS), tagsInclude = setOf("alpha"))

    /** C (alpha, WORK) under P (beta, WORK); X (beta, QUEUE) is blocked by C. */
    private class Forest(
        val parent: WorkItem,
        val child: WorkItem,
        val unblocked: WorkItem
    )

    private suspend fun FenceRig.forest(suffix: String): Forest {
        val parent = make("P$suffix", role = Role.WORK, tags = "beta")
        val child = make("C$suffix", parent, role = Role.WORK, tags = "alpha")
        val unblocked = make("X$suffix", tags = "beta")
        raw.dependencyRepository().create(Dependency(fromItemId = child.id, toItemId = unblocked.id))
        return Forest(parent, child, unblocked)
    }

    private fun idOnly(item: WorkItem): JsonObject = buildJsonObject { put("itemId", item.id.toString()) }

    private fun assertFullCascade(
        entry: JsonObject,
        parent: WorkItem
    ) {
        assertEquals(parent.id.toString(), entry.text("itemId"))
        assertEquals("work", entry.text("previousRole"))
        assertEquals("terminal", entry.text("targetRole"))
        assertEquals("true", entry.text("applied"))
    }

    // ------------------------------------------------------------------ transition

    @Test
    fun `S8 transition under a tag scope reduces the out-of-scope cascade and unblocked entries to their item ids`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val f = fx.forest("-scoped")

            val element = fx.ctx.advanceTool(transitionOp(f.child.id, "complete"), access = parityAccess(alphaOnly)).onlyResult()

            assertEquals(listOf(idOnly(f.parent)), element.arr("cascades").map { it.jsonObject }, "cascade entry is {itemId} only")
            assertEquals(listOf(idOnly(f.unblocked)), element.arr("unblocked").map { it.jsonObject }, "unblocked entry is {itemId} only")
            assertEquals(Role.TERMINAL, fx.reload(f.parent).role, "the cascade really happened (the reduction hides, it does not suppress)")
        }

    @Test
    fun `S8 transition with no scope restriction returns the full cascade and unblocked entries`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val f = fx.forest("-open")

            val element = fx.ctx.advanceTool(transitionOp(f.child.id, "complete"), access = testAccess("agent-a")).onlyResult()

            assertFullCascade(element.arr("cascades").single().jsonObject, f.parent)
            val unblocked = element.arr("unblocked").single().jsonObject
            assertEquals(f.unblocked.id.toString(), unblocked.text("itemId"))
            assertEquals(f.unblocked.title, unblocked.text("title"), "the unrestricted entry still carries the title")
        }

    @Test
    fun `S8 a root scope admits the parent in the same root but reduces the unblocked item in another root`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val parent = fx.make("P-root", role = Role.WORK)
            val child = fx.make("C-root", parent, role = Role.WORK)
            val otherRoot = fx.make("X-other-root")
            fx.raw.dependencyRepository().create(Dependency(fromItemId = child.id, toItemId = otherRoot.id))
            val rootScoped =
                ParityToken("echo-root", setOf(ApiCapability.READ, ApiCapability.ADVANCE), rootIds = setOf(checkNotNull(parent.rootId)))

            val element = fx.ctx.advanceTool(transitionOp(child.id, "complete"), access = parityAccess(rootScoped)).onlyResult()

            assertFullCascade(element.arr("cascades").single().jsonObject, parent)
            assertEquals(listOf(idOnly(otherRoot)), element.arr("unblocked").map { it.jsonObject }, "only the out-of-root entry is reduced")
        }

    // ------------------------------------------------------------------ complete_tree

    @Test
    fun `S8 complete_tree under a tag scope reduces the out-of-scope entries inside the applied item`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val f = fx.forest("-ct")

            val body = IwKit(fx.rig).callScoped(alphaOnly, completeTreeOp("itemIds" to strings(f.child.id.toString())))

            val entry =
                body.okResult().getValue("items").jsonArray.map { it.jsonObject }.single {
                    it.text("itemId") ==
                        f.child.id.toString()
                }
            assertEquals("applied", entry.text("outcome"))
            assertEquals(listOf(idOnly(f.parent)), entry.arr("cascades").map { it.jsonObject })
            assertEquals(listOf(idOnly(f.unblocked)), entry.arr("unblocked").map { it.jsonObject })
        }

    @Test
    fun `S8 complete_tree with no scope restriction returns the full entries`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val f = fx.forest("-ct-open")
            val unrestricted = ParityToken("echo-open", setOf(ApiCapability.READ, ApiCapability.ADVANCE, ApiCapability.WRITE_ITEMS))

            val body = IwKit(fx.rig).callScoped(unrestricted, completeTreeOp("itemIds" to strings(f.child.id.toString())))

            val entry =
                body.okResult().getValue("items").jsonArray.map { it.jsonObject }.single {
                    it.text("itemId") ==
                        f.child.id.toString()
                }
            assertFullCascade(entry.arr("cascades").single().jsonObject, f.parent)
            assertEquals(
                f.unblocked.title,
                entry
                    .arr("unblocked")
                    .single()
                    .jsonObject
                    .text("title")
            )
        }

    // ------------------------------------------------------------------ items_write delete

    @Test
    fun `S8 items_write delete under a tag scope reduces the out-of-scope cascade entry to its item id`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val parent = fx.make("P-del", role = Role.WORK, tags = "beta")
            fx.make("done kid", parent, role = Role.TERMINAL, tags = "beta")
            val open = fx.make("open kid", parent, tags = "alpha")

            val body = IwKit(fx.rig).callScoped(alphaOnly, deleteOp(open.id))

            assertEquals(listOf(idOnly(parent)), body.okResult().arr("cascade").map { it.jsonObject }, "cascade entry is {itemId} only")
            assertEquals(Role.TERMINAL, fx.reload(parent).role, "the cascade happened; only its echo is reduced")
        }

    @Test
    fun `S8 items_write delete with no scope restriction returns the full cascade entry`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val parent = fx.make("P-del-open", role = Role.WORK, tags = "beta")
            fx.make("done kid", parent, role = Role.TERMINAL, tags = "beta")
            val open = fx.make("open kid", parent, tags = "alpha")
            val unrestricted = ParityToken("echo-open-del", setOf(ApiCapability.READ, ApiCapability.ADVANCE, ApiCapability.WRITE_ITEMS))

            val body = IwKit(fx.rig).callScoped(unrestricted, deleteOp(open.id))

            val cascade =
                body
                    .okResult()
                    .arr("cascade")
                    .single()
                    .jsonObject
            assertEquals(parent.id.toString(), cascade.text("itemId"))
            assertEquals("work", cascade.text("from"))
            assertEquals("terminal", cascade.text("to"))
            assertEquals("true", cascade.text("applied"))
            assertTrue("reason" !in cascade, "an applied cascade carries no reason: $cascade")
        }

    // ------------------------------------------------------------------ N5: recursive delete refusal under a tag scope

    private fun JsonObject.refusalItemId(): String? =
        (errorAt()["detail"] as? JsonObject)?.get("itemId")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }

    @Test
    fun `N5 recursive delete refused over a held descendant outside the tag scope names the deleted item, not the descendant`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val target = fx.make("N5-target", tags = "alpha")
            val hidden = fx.heldBy(fx.make("N5-hidden", target, tags = "beta"), "agent-b")

            val body = IwKit(fx.rig).callScoped(alphaOnly, deleteOp(target.id, recursive = true))

            assertEquals("not_claim_holder", body.errorAt().text("code"))
            assertEquals(target.id.toString(), body.refusalItemId(), "the out-of-scope descendant's id must not leak")
            assertTrue(hidden.id.toString() !in body.toString(), "the hidden descendant id appears nowhere in the response: $body")
            assertEquals(2, fx.count("work_items"), "nothing was deleted")
        }

    @Test
    fun `N5 recursive delete refused over a held descendant inside the tag scope names that descendant - the control`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val target = fx.make("N5c-target", tags = "alpha")
            val visible = fx.heldBy(fx.make("N5c-visible", target, tags = "alpha"), "agent-b")

            val body = IwKit(fx.rig).callScoped(alphaOnly, deleteOp(target.id, recursive = true))

            assertEquals("not_claim_holder", body.errorAt().text("code"))
            assertEquals(visible.id.toString(), body.refusalItemId())
            assertEquals(2, fx.count("work_items"), "nothing was deleted")
        }
}
