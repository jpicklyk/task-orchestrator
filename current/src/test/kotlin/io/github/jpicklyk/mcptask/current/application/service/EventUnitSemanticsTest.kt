package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.service.ItemCreateCommand
import io.github.jpicklyk.mcptask.current.application.tools.items.ManageItemsTool
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorFixtures
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent P8 unit semantics (item ea2b9b63), driven through the real SQLite unit of work of the production
 * composition: S8 (a rolled-back unit leaves zero rows; a rejection leaves exactly one), S12 (concurrent units: each
 * unit's rows are contiguous and seq follows commit order) and the idempotent-replay probe.
 *
 * Oracles: plan section 3.7 ("Failures are recorded too", "seq order = commit order"), the declared
 * [EventRecorder.REJECTION_CODES], the declared `WriteScope.afterRollback` contract (runs after the rollback, with the
 * translated error) and carry-in F1 (a rejection is one follow-up write unit; the afterRollback helper ships unit
 * tested). No expected value is read from the implementation.
 */
class EventUnitSemanticsTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private fun List<EventRecord>.types() = map { it.type }

    // ---------------------------------------------------------------------------------------------
    // S8 -- rollback leaves nothing, a rejection leaves exactly one row
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S8 a unit that rolls back leaves zero event rows while the same unit committed leaves its rows`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val uow = rig.composition.unitOfWork
            var rolledBackId: UUID? = null

            val failed =
                uow.write<Unit>("S8.rollback") {
                    val item =
                        (
                            rig.ctx.itemCommandService.createInUnit(
                                ItemCreateCommand(parentId = null, title = "rolled back")
                            ) as Outcome.Ok
                        ).value
                    rolledBackId = item.id
                    events.record(DomainEvent.ItemUpdated(item.id, item.id, listOf("title")))
                    Outcome.Err(DomainError(ErrorCode.INTERNAL, "boom"))
                }

            assertIs<Outcome.Err>(failed)
            assertEquals(emptyList(), rig.rows(), "a rolled-back unit appends no rows")
            assertNull(rig.raw.workItemRepository().getById(rolledBackId!!), "fixture: the item write really rolled back")

            // Control: the identical unit returning Ok commits the item and both rows, so zero above is attributable
            // to the rollback and not to a fixture that can never write.
            val committed =
                uow.write<UUID>("S8.commit") {
                    val item =
                        (
                            rig.ctx.itemCommandService.createInUnit(
                                ItemCreateCommand(parentId = null, title = "committed")
                            ) as Outcome.Ok
                        ).value
                    events.record(DomainEvent.ItemUpdated(item.id, item.id, listOf("title")))
                    Outcome.Ok(item.id)
                }
            assertIs<Outcome.Ok<UUID>>(committed)
            assertEquals(listOf("item.created", "item.updated"), rig.rows().types())
        }

    @Test
    fun `S8 a rejection helper records exactly one row for each rejection code and none for any other code`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val uow = rig.composition.unitOfWork
            val itemId = UUID.randomUUID()
            val rootId = UUID.randomUUID()

            fun eventFor(code: ErrorCode): DomainEvent =
                when (code) {
                    ErrorCode.CLAIM_HELD -> DomainEvent.ClaimRejected(itemId, rootId, 1000L)
                    ErrorCode.RESOURCE_UNAVAILABLE -> DomainEvent.LeaseRejected(itemId, rootId, listOf("res"), 1000L)
                    else -> DomainEvent.TransitionRejected(itemId, rootId, "start", code.name.lowercase(), missingKeys = listOf("spec"))
                }

            val expectedType =
                mapOf(
                    ErrorCode.GATE_BLOCKED to "transition.rejected",
                    ErrorCode.DEPENDENCY_UNMET to "transition.rejected",
                    ErrorCode.CLAIM_HELD to "claim.rejected",
                    ErrorCode.RESOURCE_UNAVAILABLE to "lease.rejected",
                )
            assertEquals(expectedType.keys, EventRecorder.REJECTION_CODES, "fixture: the table covers every declared rejection code")

            for ((code, type) in expectedType) {
                val before = rig.maxSeq()
                val outcome =
                    uow.write<Unit>("S8.reject.${code.name.lowercase()}") {
                        recordRejectionOnRollback(uow) { error ->
                            assertEquals(code, error.code, "the helper hands the unit's translated error to the builder")
                            eventFor(code)
                        }
                        // A real write in the same unit, so "exactly one row" cannot be the item row of a half-rolled-back unit.
                        rig.provider.workItemRepository().create(WorkItem(title = "rejected-${code.name.lowercase()}"))
                        Outcome.Err(ErrorFixtures.error(code))
                    }
                assertIs<Outcome.Err>(outcome)
                val rows = rig.rowsAfter(before)
                assertEquals(
                    listOf(type),
                    rows.types(),
                    "$code: exactly one rejection row, no item.created from the rolled-back write: $rows"
                )
                assertEquals(itemId, rows.single().entityId)
            }

            // Control: a code that is not a rejection code records nothing, even though the builder would return an event.
            val before = rig.maxSeq()
            uow.write<Unit>("S8.reject.internal") {
                recordRejectionOnRollback(uow) { eventFor(ErrorCode.GATE_BLOCKED) }
                Outcome.Err(DomainError(ErrorCode.INTERNAL, "not a rejection"))
            }
            assertEquals(emptyList(), rig.rowsAfter(before), "an internal error is not a rejection")

            // A builder that declines (null) records nothing.
            uow.write<Unit>("S8.reject.null") {
                recordRejectionOnRollback(uow) { null }
                Outcome.Err(ErrorFixtures.error(ErrorCode.GATE_BLOCKED))
            }
            assertEquals(emptyList(), rig.rowsAfter(before), "a builder returning null records nothing")

            // A rejection helper on a unit that commits records nothing.
            uow.write<Unit>("S8.reject.committed") {
                recordRejectionOnRollback(uow) { eventFor(ErrorCode.GATE_BLOCKED) }
                Outcome.Ok(Unit)
            }
            assertEquals(emptyList(), rig.rowsAfter(before), "a committed unit is not a rejection")
        }

    @Test
    fun `S8 recordRejection appends exactly one row of the given event`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val itemId = UUID.randomUUID()

            val (_, rows) =
                rig.written {
                    rig.composition.unitOfWork.recordRejection(
                        DomainEvent.TransitionRejected(itemId, itemId, "start", "gate_blocked", missingKeys = listOf("a", "b")),
                    )
                }

            val row = rows.single()
            assertEquals("transition.rejected", row.type)
            assertEquals(itemId, row.entityId)
            assertEquals(itemId, row.rootId)
            assertEquals(listOf("a", "b"), row.payload()["missingKeys"]!!.jsonArray.map { it.jsonPrimitive.content })
        }

    // ---------------------------------------------------------------------------------------------
    // S12 -- concurrent units: contiguous per unit, commit order
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S12 concurrent units each get a contiguous ascending seq run and the runs do not interleave`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val uow = rig.composition.unitOfWork
            val roots = List(6) { UUID.randomUUID() }

            roots
                .map { root ->
                    async(Dispatchers.IO) {
                        uow.write<Unit>("S12.$root") {
                            repeat(3) { i ->
                                events.record(DomainEvent.ItemUpdated(UUID.randomUUID(), root, listOf("f$i")))
                                delay(25)
                            }
                            Outcome.Ok(Unit)
                        }
                    }
                }.awaitAll()

            val rows = rig.rows()
            assertEquals(18, rows.size, "fixture: every unit committed its three rows")
            val byRoot = rows.groupBy { it.rootId }
            assertEquals(roots.toSet(), byRoot.keys)
            val ranges = mutableListOf<LongRange>()
            for ((root, unitRows) in byRoot) {
                val seqs = unitRows.map { it.seq }
                assertEquals(3, seqs.size, "unit $root")
                assertEquals((seqs.first()..seqs.last()).toList(), seqs, "unit $root: seqs contiguous and ascending in read order")
                assertEquals(
                    listOf("f0", "f1", "f2"),
                    unitRows.map {
                        it
                            .payload()["changedFields"]!!
                            .jsonArray
                            .single()
                            .jsonPrimitive.content
                    },
                    "unit $root: program order is seq order",
                )
                ranges += seqs.first()..seqs.last()
            }
            val ordered = ranges.sortedBy { it.first }
            ordered.zipWithNext().forEach { (a, b) -> assertTrue(a.last < b.first, "unit runs interleave: $a vs $b") }
            val all = rows.map { it.seq }
            assertEquals(all.sorted(), all, "readAfter returns ascending seq")
            assertEquals(all.toSet().size, all.size, "seq is unique")
        }

    // ---------------------------------------------------------------------------------------------
    // Probe -- idempotent replay records nothing
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `an idempotent replay of a create with the same actor and requestId records no second row`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val requestId = UUID.randomUUID().toString()
            val params =
                arrayOf(
                    "operation" to JsonPrimitive("create"),
                    "items" to buildJsonArray { add(buildJsonObject { put("title", "idem") }) },
                    "requestId" to JsonPrimitive(requestId),
                    "actor" to
                        buildJsonObject {
                            put("id", "idem-agent")
                            put("kind", "subagent")
                        },
                )

            val (_, first) = rig.written { rig.callOk(ManageItemsTool(), *params) }
            assertEquals(listOf("item.created"), first.types(), "fixture: the first call executes and records")

            val (_, replay) = rig.written { rig.callOk(ManageItemsTool(), *params) }

            assertEquals(emptyList(), replay, "a replayed request re-executes nothing and records nothing")
        }
}
