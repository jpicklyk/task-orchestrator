package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.application.port.PageRequest
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.ByteBuffer
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item a7fa21a6, Q28b) for the new [EventStore] reads and the seq floor over the
 * production SQLite store: scenarios S4 (floor), S5/S6 (entity and global history reads, paging, inclusive time bounds), S7
 * (root and tag scope) and S9 (backfilled rows are history, not stream) of the frozen test-plan.
 *
 * Oracles (frozen task-scope section 1, decisions D3, D8, D10, D11, migration-assessment s7; none read from an
 * implementation): `findByEntity` returns the rows of one entity whose type is in the set, newest seq first, with an exact
 * total, and history reads are NOT floored (a row below [EventStore.SEQ_FLOOR] is included); `findByTypes` bounds are
 * inclusive on `occurred_at`, a root scope matches `root_id`, a tag scope matches the entity's current work item by exact
 * tag membership so an item that is gone is invisible to a tag-scoped caller but visible to a root-scoped or unrestricted
 * one; `maxSeq()` is `max(SEQ_FLOOR, MAX(seq))`, `readAfter` reads `seq > max(afterSeq, SEQ_FLOOR)`, `latestOfType` has no
 * floor. Every expected value below is an input this test chose (row ids, seqs, instants, tags), never read back from a run.
 *
 * Rows below the floor are inserted with raw SQL at an explicit seq (the shape the V29 migration writes), because no
 * production writer produces them. Forbidden-construct declaration: none.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SqliteEventStoreHistoryReadsTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val transitioned = DomainEvent.ITEM_TRANSITIONED
    private val floor = EventStore.SEQ_FLOOR
    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

    private val store get() = db.repositoryProvider().eventStore()

    private fun idBytes(id: UUID): ByteArray =
        ByteBuffer
            .allocate(16)
            .putLong(id.mostSignificantBits)
            .putLong(id.leastSignificantBits)
            .array()

    /** Inserts one row at an explicit [seq] (a backfilled row sits below the floor) and returns its id. */
    private fun rawRow(
        seq: Long,
        entityId: UUID,
        rootId: UUID,
        at: Instant,
        type: String = transitioned,
        id: UUID = UUID.randomUUID()
    ): UUID {
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c
                .prepareStatement(
                    "INSERT INTO events (seq, id, occurred_at, root_id, entity_kind, entity_id, type, data) VALUES (?,?,?,?,?,?,?,?)"
                ).use { ps ->
                    ps.setLong(1, seq)
                    ps.setBytes(2, idBytes(id))
                    ps.setString(3, stamp.format(at))
                    ps.setBytes(4, idBytes(rootId))
                    ps.setString(5, DomainEvent.KIND_ITEM)
                    ps.setBytes(6, idBytes(entityId))
                    ps.setString(7, type)
                    ps.setString(8, "{}")
                    ps.executeUpdate()
                }
        }
        return id
    }

    /** Appends one live row through the store (the store assigns a seq above the floor) and returns it with its seq. */
    private suspend fun live(
        entityId: UUID,
        rootId: UUID,
        at: Instant,
        type: String = transitioned
    ): EventRecord =
        store
            .append(
                listOf(
                    EventRecord(
                        id = UUID.randomUUID(),
                        occurredAt = at,
                        rootId = rootId,
                        entityKind = DomainEvent.KIND_ITEM,
                        entityId = entityId,
                        type = type,
                        data = "{}"
                    )
                )
            ).single()

    private suspend fun item(
        title: String,
        tags: String? = null,
        parent: WorkItem? = null,
        id: UUID = UUID.randomUUID()
    ): WorkItem =
        db.repositoryProvider().workItemRepository().create(
            WorkItem(
                title = title,
                parentId = parent?.id,
                depth = (parent?.depth ?: -1) + 1,
                id = id,
                rootId = parent?.let { it.rootId ?: it.id } ?: id,
                tags = tags,
                type = "test-item"
            )
        )

    private fun page(
        limit: Int = 100,
        offset: Int = 0
    ) = PageRequest(limit, offset)

    private val types = setOf(transitioned)

    // ------------------------------------------------------------------ findByEntity

    @Test
    fun `S5 findByEntity returns the rows of the asked types newest first with an exact total, backfilled rows included`(): Unit =
        runBlocking {
            val e = UUID.randomUUID()
            val other = UUID.randomUUID()
            val root = UUID.randomUUID()
            val oldBackfilled = rawRow(1, e, root, t0)
            val liveOne = live(e, root, t0.plusSeconds(10))
            live(e, root, t0.plusSeconds(11), type = DomainEvent.ITEM_CREATED)
            live(other, root, t0.plusSeconds(12))
            val liveTwo = live(e, root, t0.plusSeconds(20))

            val got = store.findByEntity(e, types, page())

            assertEquals(
                listOf(liveTwo.id, liveOne.id, oldBackfilled),
                got.items.map { it.id },
                "newest seq first, only this entity and type"
            )
            assertEquals(3, got.total)
            assertTrue(got.items.last().seq < floor, "the backfilled row (seq below the floor) is part of history")
            assertEquals(listOf(1L), got.items.map { it.seq }.filter { it < floor })
        }

    @Test
    fun `S5 findByEntity of an entity with no matching rows is an empty page with total zero`(): Unit =
        runBlocking {
            val e = UUID.randomUUID()
            val root = UUID.randomUUID()
            live(e, root, t0, type = DomainEvent.ITEM_CREATED)
            live(UUID.randomUUID(), root, t0)

            val got = store.findByEntity(e, types, page())

            assertEquals(emptyList(), got.items)
            assertEquals(0, got.total)
        }

    @Test
    fun `S5 findByEntity pages by limit and offset and keeps the total at the full match count`(): Unit =
        runBlocking {
            val e = UUID.randomUUID()
            val root = UUID.randomUUID()
            val ids = mutableListOf<UUID>()
            ids += rawRow(1, e, root, t0)
            ids += rawRow(2, e, root, t0.plusSeconds(1))
            ids += live(e, root, t0.plusSeconds(2)).id
            ids += live(e, root, t0.plusSeconds(3)).id
            ids += live(e, root, t0.plusSeconds(4)).id
            val newestFirst = ids.reversed()

            val first = store.findByEntity(e, types, page(limit = 2, offset = 0))
            val second = store.findByEntity(e, types, page(limit = 2, offset = 2))
            val third = store.findByEntity(e, types, page(limit = 2, offset = 4))
            val beyond = store.findByEntity(e, types, page(limit = 2, offset = 5))

            assertEquals(newestFirst.subList(0, 2), first.items.map { it.id })
            assertEquals(newestFirst.subList(2, 4), second.items.map { it.id })
            assertEquals(newestFirst.subList(4, 5), third.items.map { it.id })
            assertEquals(emptyList(), beyond.items.map { it.id })
            for (p in listOf(first, second, third, beyond)) assertEquals(5, p.total, "the total is the whole match count on every page")
        }

    @Test
    fun `S6 findByEntity time bounds are inclusive at both ends and a single instant matches exactly the rows at it`(): Unit =
        runBlocking {
            val e = UUID.randomUUID()
            val root = UUID.randomUUID()
            val r0 = live(e, root, t0)
            val r1 = live(e, root, t0.plusSeconds(1))
            val r2 = live(e, root, t0.plusSeconds(2))
            val r3 = live(e, root, t0.plusSeconds(3))

            val window = store.findByEntity(e, types, page(), since = t0.plusSeconds(1), until = t0.plusSeconds(2))
            val point = store.findByEntity(e, types, page(), since = t0.plusSeconds(1), until = t0.plusSeconds(1))
            val open = store.findByEntity(e, types, page(), since = t0.plusSeconds(2), until = null)
            val closed = store.findByEntity(e, types, page(), since = null, until = t0.plusSeconds(1))

            assertEquals(listOf(r2.id, r1.id), window.items.map { it.id }, "both bounds are inclusive")
            assertEquals(2, window.total)
            assertEquals(listOf(r1.id), point.items.map { it.id }, "since == until matches the rows at that instant")
            assertEquals(listOf(r3.id, r2.id), open.items.map { it.id })
            assertEquals(listOf(r1.id, r0.id), closed.items.map { it.id })
            assertEquals(2, closed.total)
        }

    // ------------------------------------------------------------------ findByTypes

    @Test
    fun `S5 findByTypes spans entities, filters by type, orders newest first and totals exactly`(): Unit =
        runBlocking {
            val root = UUID.randomUUID()
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            val backfilled = rawRow(1, a, root, t0)
            val liveA = live(a, root, t0.plusSeconds(5))
            live(b, root, t0.plusSeconds(6), type = DomainEvent.ITEM_CREATED)
            val liveB = live(b, root, t0.plusSeconds(7))

            val got = store.findByTypes(types, null, null, AccessScope.unrestricted(), page())

            assertEquals(listOf(liveB.id, liveA.id, backfilled), got.items.map { it.id })
            assertEquals(3, got.total)
        }

    @Test
    fun `S6 findByTypes pages with an exact total and the time bounds are inclusive on occurred_at`(): Unit =
        runBlocking {
            val root = UUID.randomUUID()
            val e = UUID.randomUUID()
            val rows = (0 until 6).map { live(e, root, t0.plusSeconds(it.toLong())) }

            val windowFirst = store.findByTypes(types, t0.plusSeconds(1), t0.plusSeconds(4), AccessScope.unrestricted(), page(limit = 2))
            val windowSecond =
                store.findByTypes(types, t0.plusSeconds(1), t0.plusSeconds(4), AccessScope.unrestricted(), page(limit = 2, offset = 2))

            assertEquals(listOf(rows[4].id, rows[3].id), windowFirst.items.map { it.id }, "until is inclusive")
            assertEquals(listOf(rows[2].id, rows[1].id), windowSecond.items.map { it.id }, "since is inclusive")
            assertEquals(4, windowFirst.total)
            assertEquals(4, windowSecond.total)
        }

    @Test
    fun `S7 a root scope matches root_id exactly, an empty root set matches nothing and an unrestricted scope matches all`(): Unit =
        runBlocking {
            val rootA = UUID.randomUUID()
            val rootB = UUID.randomUUID()
            val oldB = rawRow(1, UUID.randomUUID(), rootB, t0)
            val a = live(UUID.randomUUID(), rootA, t0.plusSeconds(1))
            val b = live(UUID.randomUUID(), rootB, t0.plusSeconds(2))

            val onlyA = store.findByTypes(types, null, null, AccessScope.principal(setOf(rootA), emptySet()), page())
            val both = store.findByTypes(types, null, null, AccessScope.principal(setOf(rootA, rootB), emptySet()), page())
            val none = store.findByTypes(types, null, null, AccessScope.principal(emptySet(), emptySet()), page())
            val all = store.findByTypes(types, null, null, AccessScope.unrestricted(), page())

            assertEquals(listOf(a.id), onlyA.items.map { it.id })
            assertEquals(1, onlyA.total, "the total counts visible rows only")
            assertEquals(listOf(b.id, a.id, oldB), both.items.map { it.id })
            assertEquals(3, both.total)
            assertEquals(emptyList(), none.items)
            assertEquals(0, none.total)
            assertEquals(3, all.total)
        }

    @Test
    fun `S7 a tag scope shows only live items carrying a listed tag exactly, not untagged, prefix-tagged or deleted`(): Unit =
        runBlocking {
            val root = item("Root", tags = "api")
            val tagged = item("Tagged", tags = "x,api", parent = root)
            val untagged = item("Untagged", parent = root)
            val prefixed = item("Prefixed", tags = "api-v2", parent = root)
            val gone = item("Gone", tags = "api", parent = root)
            val rows =
                listOf(root, tagged, untagged, prefixed, gone)
                    .mapIndexed { i, it -> it.title to live(it.id, root.id, t0.plusSeconds(i.toLong())) }
                    .toMap()
            db.repositoryProvider().workItemRepository().delete(gone.id)

            val tagScope = AccessScope.principal(null, setOf("api"))
            val got = store.findByTypes(types, null, null, tagScope, page())

            assertEquals(
                listOf(rows.getValue("Tagged").id, rows.getValue("Root").id),
                got.items.map { it.id },
                "only the rows whose item is alive and carries the tag api exactly"
            )
            assertEquals(2, got.total)
            val rootScoped = store.findByTypes(types, null, null, AccessScope.principal(setOf(root.id), emptySet()), page())
            assertEquals(5, rootScoped.total, "control: a root-scoped caller still sees the deleted item's row")
            val unrestricted = store.findByTypes(types, null, null, AccessScope.unrestricted(), page())
            assertEquals(5, unrestricted.total, "control: so does an unrestricted caller")
        }

    @Test
    fun `S7 a scope with both roots and tags needs both to hold`(): Unit =
        runBlocking {
            val rootA = item("A", tags = "api")
            val rootB = item("B", tags = "api")
            val aPlain = item("A-plain", parent = rootA)
            val rowA = live(rootA.id, rootA.id, t0)
            live(rootB.id, rootB.id, t0.plusSeconds(1))
            live(aPlain.id, rootA.id, t0.plusSeconds(2))

            val got = store.findByTypes(types, null, null, AccessScope.principal(setOf(rootA.id), setOf("api")), page())

            assertEquals(listOf(rowA.id), got.items.map { it.id })
            assertEquals(1, got.total)
        }

    // ------------------------------------------------------------------ floor

    @Test
    fun `S4 maxSeq of an empty table is the floor`(): Unit =
        runBlocking {
            assertEquals(floor, store.maxSeq())
        }

    @Test
    fun `S4 maxSeq with only backfilled rows is still the floor and readAfter zero streams nothing`(): Unit =
        runBlocking {
            val root = UUID.randomUUID()
            rawRow(1, UUID.randomUUID(), root, t0)
            rawRow(2, UUID.randomUUID(), root, t0.plusSeconds(1))
            rawRow(3, UUID.randomUUID(), root, t0.plusSeconds(2))

            assertEquals(floor, store.maxSeq(), "a backfill-only table must not report a high-water mark below the floor")
            assertEquals(emptyList(), store.readAfter(0L, null, 100))
            assertEquals(emptyList(), store.readAfter(2L, null, 100), "an afterSeq below the floor is lifted to the floor")
            assertEquals(emptyList(), store.readAfter(floor, null, 100))
        }

    @Test
    fun `S4 readAfter below the floor skips backfilled rows but returns every live row in ascending order`(): Unit =
        runBlocking {
            val root = UUID.randomUUID()
            val other = UUID.randomUUID()
            rawRow(1, UUID.randomUUID(), root, t0)
            rawRow(2, UUID.randomUUID(), root, t0.plusSeconds(1))
            val first = live(UUID.randomUUID(), root, t0.plusSeconds(10))
            val second = live(UUID.randomUUID(), other, t0.plusSeconds(11))
            val third = live(UUID.randomUUID(), root, t0.plusSeconds(12))

            assertEquals(listOf(first.id, second.id, third.id), store.readAfter(0L, null, 100).map { it.id })
            assertEquals(listOf(first.id, second.id, third.id), store.readAfter(1L, null, 100).map { it.id })
            assertEquals(listOf(second.id, third.id), store.readAfter(first.seq, null, 100).map { it.id }, "exclusive of afterSeq")
            assertEquals(listOf(first.id, third.id), store.readAfter(0L, setOf(root), 100).map { it.id }, "the root filter still applies")
            assertEquals(listOf(first.id), store.readAfter(0L, null, 1).map { it.id }, "the limit still applies")
            assertEquals(third.seq, store.maxSeq(), "with live rows present maxSeq is the newest live seq")
            assertTrue(first.seq > floor && second.seq > first.seq && third.seq > second.seq)
        }

    @Test
    fun `S4 latestOfType is not floored`(): Unit =
        runBlocking {
            val e = UUID.randomUUID()
            val root = UUID.randomUUID()
            val id = rawRow(1, e, root, t0)

            val got = store.latestOfType(transitioned, setOf(e))

            assertEquals(id, got.getValue(e).id, "latestOfType reads the whole table, backfilled rows included")
        }
}
