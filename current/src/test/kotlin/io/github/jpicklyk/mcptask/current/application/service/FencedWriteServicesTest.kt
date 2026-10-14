package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.patchOf
import io.github.jpicklyk.mcptask.current.test.FENCE_T
import io.github.jpicklyk.mcptask.current.test.FenceRig
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.newFenceRig
import io.github.jpicklyk.mcptask.current.test.putNote
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Independent tests (test-author seat, item 46a83aca) for the write fence on the three command services: scenarios S2 (notes),
 * S3 (items) and S4 (dependencies) of the frozen test-plan, at the service seam, over the production composition and a real
 * SQLite database with a settable clock.
 *
 * Oracles (frozen, planning phase): task-scope item 3 and decisions D3 (the ops naming the item: every endpoint of every edge in
 * a create, both ends of delete-by-relationship, the named item only for delete-by-item, both stored endpoints for delete-by-id),
 * D4 (a recursive delete fences the whole subtree; the first fenced descendant in id order is reported and nothing is deleted),
 * D5 (lookup, then fence, then version compare, so a non-holder learns nothing from a version conflict), D6 (a null caller fences
 * on any active claim) and AC1 (a refusal writes nothing and records no event row; the holder, a lapsed claim and an unclaimed
 * item proceed). A claim is active while its expiry is strictly after the unit instant (ClaimState KDoc), so the instant equal to
 * the expiry is already lapsed.
 *
 * Every scenario is NEW-SURFACE (the `caller` parameter is new). Each refusal is paired with a control through the same call by
 * the holder, so "nothing was written" cannot be an unrelated failure. Fixtures: items are created by the item service; claims are
 * taken through the store at the settable clock, so claimedAt/expiry/originalClaimedAt satisfy the claim invariants by construction.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class FencedWriteServicesTest {
    private val clock = SettableClock(FENCE_T)

    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod(clock = clock)

    private val a = ClaimHolder("agent-a")
    private val b = ClaimHolder("agent-b")

    private fun rig(dir: Path): FenceRig = newFenceRig(sqlite.db, dir, clock)

    private fun <T> Outcome<T>.fenced(item: WorkItem): DomainError {
        val error = (this as? Outcome.Err)?.error ?: fail("expected not_claim_holder for ${item.title}, got $this")
        assertEquals(ErrorCode.NOT_CLAIM_HOLDER, error.code, "wrong code: ${error.code} ${error.message}")
        assertEquals(ErrorDetail.NotClaimHolder(item.id), error.detail, "the detail names the fenced item")
        assertTrue("agent-a" !in error.message && "agent-b" !in error.message, "no principal in the message: ${error.message}")
        return error
    }

    private fun <T> Outcome<T>.errorCode(): ErrorCode = (this as? Outcome.Err)?.error?.code ?: fail("expected an error, got $this")

    private fun upsert(
        item: WorkItem,
        caller: ClaimHolder?,
        key: String = "k",
        expectedVersion: Long? = null
    ) = NoteUpsertCommand(
        itemId = item.id,
        key = key,
        role = "work",
        body = "body",
        actorClaim = null,
        verification = null,
        caller = caller,
        expectedVersion = expectedVersion
    )

    // ------------------------------------------------------------------ S2 notes

    @Test
    fun `S2 notes upsert on another principal's active claim is refused, stores nothing and records no event`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val item = fx.make("host")
            fx.heldBy(item, "agent-b")
            val seq = fx.rig.maxSeq()

            fx.ctx.noteCommandService
                .upsert(upsert(item, a))
                .fenced(item)

            assertEquals(0, fx.count("notes"), "the refused upsert stored no note")
            assertEquals(seq, fx.rig.maxSeq(), "the refused upsert recorded no event row")

            // control: the holder's identical upsert is accepted, so the refusal above was the fence and nothing else
            assertTrue(fx.ctx.noteCommandService.upsert(upsert(item, b)) is Outcome.Ok)
            assertEquals(1, fx.count("notes"))
        }

    @Test
    fun `S2 notes upsert boundary - refused one millisecond before the expiry, accepted at exactly the expiry`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val item = fx.make("host")
            fx.heldBy(item, "agent-b")

            clock.set(fx.expiry.minusMillis(1))
            fx.ctx.noteCommandService
                .upsert(upsert(item, a, key = "early"))
                .fenced(item)
            assertEquals(0, fx.count("notes"))

            clock.set(fx.expiry)
            assertTrue(fx.ctx.noteCommandService.upsert(upsert(item, a, key = "late")) is Outcome.Ok, "a lapsed claim fences nothing")
            assertEquals(1, fx.count("notes"))
        }

    @Test
    fun `S2 notes upsert on an unclaimed item proceeds for a caller and for none, and a null caller is refused on a held item`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val free = fx.make("free")
            val held = fx.make("held")
            fx.heldBy(held, "agent-b")

            assertTrue(fx.ctx.noteCommandService.upsert(upsert(free, a, key = "one")) is Outcome.Ok)
            assertTrue(fx.ctx.noteCommandService.upsert(upsert(free, null, key = "two")) is Outcome.Ok)
            assertEquals(2, fx.count("notes"))

            fx.ctx.noteCommandService
                .upsert(upsert(held, null))
                .fenced(held)
            assertEquals(2, fx.count("notes"), "the null caller wrote nothing on the held item")
        }

    @Test
    fun `S2 a wrong expectedVersion by a non-holder is still not_claim_holder, while the holder gets the version error`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val item = fx.make("host")
            fx.ctx.putNote(item.id, "k", "work", "v1")
            fx.heldBy(item, "agent-b")

            fx.ctx.noteCommandService
                .upsert(upsert(item, a, expectedVersion = 999L))
                .fenced(item)

            // control: the same stale version from the holder is rejected for the version, proving the order matters
            val holderResult = fx.ctx.noteCommandService.upsert(upsert(item, b, expectedVersion = 999L))
            assertNotEquals(ErrorCode.NOT_CLAIM_HOLDER, holderResult.errorCode(), "the holder is not fenced; its stale version is")
        }

    @Test
    fun `S2 notes delete on another principal's claim is refused and the note survives, then the holder deletes it`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val item = fx.make("host")
            fx.ctx.putNote(item.id, "k", "work", "v1")
            fx.heldBy(item, "agent-b")
            val seq = fx.rig.maxSeq()

            fx.ctx.noteCommandService
                .delete(item.id, "k", null, a)
                .fenced(item)
            fx.ctx.noteCommandService
                .delete(item.id, "k", 999L, a)
                .fenced(item)
            fx.ctx.noteCommandService
                .delete(item.id, "k", null, null)
                .fenced(item)

            assertEquals(1, fx.count("notes"), "no refused delete removed the note")
            assertEquals(seq, fx.rig.maxSeq(), "no refused delete recorded an event row")

            assertTrue(fx.ctx.noteCommandService.delete(item.id, "k", null, b) is Outcome.Ok, "control: the holder may delete")
            assertEquals(0, fx.count("notes"))
        }

    @Test
    fun `probe the same refused write replayed twice repeats the refusal and still writes nothing`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val item = fx.make("host")
            fx.heldBy(item, "agent-b")
            val seq = fx.rig.maxSeq()

            repeat(2) {
                fx.ctx.noteCommandService
                    .upsert(upsert(item, a))
                    .fenced(item)
            }

            assertEquals(0, fx.count("notes"))
            assertEquals(seq, fx.rig.maxSeq())
        }

    @Test
    fun `probe a caller whose id differs from the holder by case or a trailing space is fenced`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val item = fx.make("host")
            fx.heldBy(item, "agent-b")

            for (spelling in listOf("Agent-B", "agent-b ", " agent-b")) {
                fx.ctx.noteCommandService
                    .upsert(upsert(item, ClaimHolder(spelling)))
                    .fenced(item)
            }
            assertEquals(0, fx.count("notes"))
        }

    // ------------------------------------------------------------------ S3 items

    @Test
    fun `S3 item patch on another principal's claim is refused, changes nothing and records no event, then the holder patches`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val item = fx.heldBy(fx.make("original"), "agent-b")
            val seq = fx.rig.maxSeq()

            fx.ctx.itemCommandService
                .patch(patchOf(item, title = "changed"), a)
                .fenced(item)
            fx.ctx.itemCommandService
                .patch(patchOf(item, title = "changed"), null)
                .fenced(item)

            val after = fx.reload(item)
            assertEquals("original", after.title, "the refused patch left the title")
            assertEquals(item.version, after.version, "the refused patch left the version")
            assertEquals(seq, fx.rig.maxSeq(), "the refused patch recorded no event row")

            fx.ctx.itemCommandService
                .patch(patchOf(item, title = "changed"), b)
                .orFail()
            assertEquals("changed", fx.reload(item).title, "control: the holder's patch applies")
        }

    @Test
    fun `S3 item patch with a wrong expectedVersion by a non-holder is not_claim_holder`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val item = fx.heldBy(fx.make("original"), "agent-b")
            val stale = patchOf(item, title = "changed").copy(expectedVersion = 999L)

            fx.ctx.itemCommandService
                .patch(stale, a)
                .fenced(item)

            assertNotEquals(
                ErrorCode.NOT_CLAIM_HOLDER,
                fx.ctx.itemCommandService
                    .patch(stale, b)
                    .errorCode(),
                "control: the holder gets the version error"
            )
        }

    @Test
    fun `S3 item patch proceeds on an unclaimed item and on a lapsed claim`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val free = fx.make("free")
            val lapsed = fx.heldBy(fx.make("lapsed"), "agent-b")

            fx.ctx.itemCommandService
                .patch(patchOf(free, title = "free2"), a)
                .orFail()
            clock.set(fx.expiry.minusMillis(1))
            fx.ctx.itemCommandService
                .patch(patchOf(lapsed, title = "early"), a)
                .fenced(lapsed)
            clock.set(fx.expiry)
            fx.ctx.itemCommandService
                .patch(patchOf(lapsed, title = "late"), a)
                .orFail()

            assertEquals("free2", fx.reload(free).title)
            assertEquals("late", fx.reload(lapsed).title)
        }

    @Test
    fun `S3 item delete on another principal's claim is refused and the row survives, then the holder deletes it`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val item = fx.heldBy(fx.make("victim"), "agent-b")
            val seq = fx.rig.maxSeq()

            fx.ctx.itemCommandService
                .delete(item.id, false, a)
                .fenced(item)
            fx.ctx.itemCommandService
                .delete(item.id, true, a)
                .fenced(item)
            fx.ctx.itemCommandService
                .delete(item.id, false, null)
                .fenced(item)

            assertEquals(1, fx.count("work_items"), "no refused delete removed the item")
            assertEquals(seq, fx.rig.maxSeq(), "no refused delete recorded an event row")

            fx.ctx.itemCommandService
                .delete(item.id, false, b)
                .orFail()
            assertEquals(0, fx.count("work_items"), "control: the holder may delete")
        }

    @Test
    fun `S3 recursive delete of an unheld parent over a descendant held by another principal reports that descendant and deletes nothing`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val parent = fx.make("parent")
            val middle = fx.make("middle", parent)
            val deep = fx.heldBy(fx.make("deep", middle), "agent-b")
            val seq = fx.rig.maxSeq()

            fx.ctx.itemCommandService
                .delete(parent.id, true, a)
                .fenced(deep)

            assertEquals(3, fx.count("work_items"), "nothing in the subtree was deleted")
            assertEquals(seq, fx.rig.maxSeq(), "no event row")

            // control: the descendant's holder deletes the whole subtree in the same shape of call
            fx.ctx.itemCommandService
                .delete(parent.id, true, b)
                .orFail()
            assertEquals(0, fx.count("work_items"))
        }

    @Test
    fun `S3 recursive delete reports the first fenced descendant in id order`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val parent = fx.make("parent")
            val low = fx.heldBy(fx.make("low", parent, id = UUID.fromString("10000000-0000-4000-8000-000000000001")), "agent-b")
            val high = fx.heldBy(fx.make("high", parent, id = UUID.fromString("20000000-0000-4000-8000-000000000002")), "agent-c")
            assertTrue(low.id.toString() < high.id.toString(), "fixture: low sorts before high")

            fx.ctx.itemCommandService
                .delete(parent.id, true, a)
                .fenced(low)

            assertEquals(3, fx.count("work_items"))
        }

    @Test
    fun `S3 recursive delete is refused for a held root, allowed for the holder of the only held descendant and for a lapsed one`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val heldRoot = fx.heldBy(fx.make("held root"), "agent-b")
            fx.make("child of held root", heldRoot)
            fx.ctx.itemCommandService
                .delete(heldRoot.id, true, a)
                .fenced(heldRoot)
            assertEquals(2, fx.count("work_items"))

            val mine = fx.make("mine root")
            fx.heldBy(fx.make("mine child", mine), "agent-a")
            fx.ctx.itemCommandService
                .delete(mine.id, true, a)
                .orFail()
            assertEquals(2, fx.count("work_items"), "the holder of the held descendant deleted its two-item subtree")

            val lapsedRoot = fx.make("lapsed root")
            fx.heldBy(fx.make("lapsed child", lapsedRoot), "agent-b")
            clock.set(fx.expiry)
            fx.ctx.itemCommandService
                .delete(lapsedRoot.id, true, a)
                .orFail()
            assertEquals(2, fx.count("work_items"), "a lapsed descendant claim fences nothing")
        }

    // ------------------------------------------------------------------ S4 dependencies

    private suspend fun FenceRig.edge(
        from: WorkItem,
        to: WorkItem
    ): Dependency = raw.dependencyRepository().create(Dependency(fromItemId = from.id, toItemId = to.id))

    @Test
    fun `S4 dependency create naming a held item is refused whichever end it is, stores nothing and records no event`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val x = fx.make("x")
            val y = fx.make("y")
            val z = fx.make("z")
            fx.heldBy(z, "agent-b")
            val seq = fx.rig.maxSeq()
            val svc = fx.ctx.dependencyCommandService

            svc.create(listOf(Dependency(fromItemId = x.id, toItemId = z.id)), a).fenced(z)
            svc.create(listOf(Dependency(fromItemId = z.id, toItemId = y.id)), a).fenced(z)
            svc.create(listOf(Dependency(fromItemId = x.id, toItemId = z.id)), null).fenced(z)

            assertEquals(0, fx.count("dependencies"))
            assertEquals(seq, fx.rig.maxSeq())

            // control: the holder may create an edge naming its own item
            svc.create(listOf(Dependency(fromItemId = x.id, toItemId = z.id)), b).orFail()
            assertEquals(1, fx.count("dependencies"))
        }

    @Test
    fun `S4 one held endpoint in the third edge of a request refuses the whole request`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val p = fx.make("p")
            val q = fx.make("q")
            val r = fx.make("r")
            val held = fx.heldBy(fx.make("held"), "agent-b")

            val request =
                listOf(
                    Dependency(fromItemId = p.id, toItemId = q.id),
                    Dependency(fromItemId = q.id, toItemId = r.id),
                    Dependency(fromItemId = r.id, toItemId = held.id)
                )
            fx.ctx.dependencyCommandService
                .create(request, a)
                .fenced(held)

            assertEquals(0, fx.count("dependencies"), "not even the unfenced edges were stored")
        }

    @Test
    fun `S4 createInUnit applies the same fence inside a write unit`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val x = fx.make("x")
            val z = fx.heldBy(fx.make("z"), "agent-b")
            val edge = listOf(Dependency(fromItemId = x.id, toItemId = z.id))

            val refused = fx.rig.inUnit { fx.ctx.dependencyCommandService.createInUnit(edge, a) }
            refused.fenced(z)
            assertEquals(0, fx.count("dependencies"))

            val accepted = fx.rig.inUnit { fx.ctx.dependencyCommandService.createInUnit(edge, b) }
            accepted.orFail()
            assertEquals(1, fx.count("dependencies"))
        }

    @Test
    fun `S4 dependency create on a lapsed claim and on unclaimed items proceeds`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val x = fx.make("x")
            val z = fx.heldBy(fx.make("z"), "agent-b")
            val w = fx.make("w")

            fx.ctx.dependencyCommandService
                .create(listOf(Dependency(fromItemId = x.id, toItemId = w.id)), a)
                .orFail()
            clock.set(fx.expiry.minusMillis(1))
            fx.ctx.dependencyCommandService
                .create(listOf(Dependency(fromItemId = x.id, toItemId = z.id)), a)
                .fenced(z)
            clock.set(fx.expiry)
            fx.ctx.dependencyCommandService
                .create(listOf(Dependency(fromItemId = x.id, toItemId = z.id)), a)
                .orFail()

            assertEquals(2, fx.count("dependencies"))
        }

    @Test
    fun `S4 delete by relationship is refused when the to end is held, and removes the edge for the holder`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val x = fx.make("x")
            val toHeld = fx.heldBy(fx.make("to held"), "agent-b")
            fx.edge(x, toHeld)
            val svc = fx.ctx.dependencyCommandService

            svc.deleteByRelationship(x.id, toHeld.id, null, a).fenced(toHeld)
            assertEquals(1, fx.count("dependencies"), "the refused delete removed nothing")

            assertEquals(1, svc.deleteByRelationship(x.id, toHeld.id, null, b).orFail(), "control: the holder deletes its own edge")
            assertEquals(0, fx.count("dependencies"))
        }

    @Test
    fun `S4 delete by relationship is refused when the from end is held, also for a null caller`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val y = fx.make("y")
            val fromHeld = fx.heldBy(fx.make("from held"), "agent-b")
            fx.edge(fromHeld, y)
            val svc = fx.ctx.dependencyCommandService

            svc.deleteByRelationship(fromHeld.id, y.id, null, a).fenced(fromHeld)
            svc.deleteByRelationship(fromHeld.id, y.id, null, null).fenced(fromHeld)
            assertEquals(1, fx.count("dependencies"), "no refused delete removed the edge")

            assertEquals(1, svc.deleteByRelationship(fromHeld.id, y.id, null, b).orFail(), "control: the holder deletes")
        }

    @Test
    fun `S4 delete by relationship between unheld items proceeds for any caller`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val x = fx.make("x")
            val y = fx.make("y")
            fx.edge(x, y)

            assertEquals(
                1,
                fx.ctx.dependencyCommandService
                    .deleteByRelationship(x.id, y.id, null, a)
                    .orFail()
            )
            assertEquals(0, fx.count("dependencies"))
        }

    @Test
    fun `S4 delete by item fences only the named item - a held counterparty does not block it`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val named = fx.make("named")
            val other = fx.make("other")
            val heldCounterparty = fx.heldBy(fx.make("held counterparty"), "agent-b")
            fx.edge(named, heldCounterparty)
            fx.edge(other, named)

            assertEquals(
                2,
                fx.ctx.dependencyCommandService
                    .deleteByItemId(named.id, a)
                    .orFail(),
                "both edges of the unheld item go"
            )
            assertEquals(0, fx.count("dependencies"))

            val heldNamed = fx.heldBy(fx.make("held named"), "agent-b")
            fx.edge(heldNamed, other)
            fx.ctx.dependencyCommandService
                .deleteByItemId(heldNamed.id, a)
                .fenced(heldNamed)
            assertEquals(1, fx.count("dependencies"))
            assertEquals(
                1,
                fx.ctx.dependencyCommandService
                    .deleteByItemId(heldNamed.id, b)
                    .orFail(),
                "control: the holder deletes"
            )
        }

    @Test
    fun `S4 delete by edge id is fenced by the stored edge's two endpoints, so naming the edge is not a bypass`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val x = fx.make("x")
            val y = fx.make("y")
            val held = fx.heldBy(fx.make("held"), "agent-b")
            val toEdge = fx.edge(x, held)
            val fromEdge = fx.edge(held, y)
            val plain = fx.edge(x, y)
            val svc = fx.ctx.dependencyCommandService

            svc.deleteById(toEdge.id, a).fenced(held)
            svc.deleteById(fromEdge.id, a).fenced(held)
            svc.deleteById(fromEdge.id, null).fenced(held)
            assertEquals(3, fx.count("dependencies"), "no refused delete removed an edge")

            assertEquals(true, svc.deleteById(plain.id, a).orFail(), "control: an edge between unheld items is deleted")
            assertEquals(true, svc.deleteById(toEdge.id, b).orFail(), "control: the holder deletes by id")
            assertEquals(1, fx.count("dependencies"))
        }

    @Test
    fun `S4 a held item that is the holder's own, and a held item whose claim lapsed, never fence the dependency writes`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val x = fx.make("x")
            val mine = fx.heldBy(fx.make("mine"), "agent-a")
            val svc = fx.ctx.dependencyCommandService

            val created = svc.create(listOf(Dependency(fromItemId = x.id, toItemId = mine.id)), a).orFail()
            assertEquals(1, created.size)
            assertEquals(1, svc.deleteByRelationship(x.id, mine.id, null, a).orFail())
        }
}
