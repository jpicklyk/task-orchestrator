package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.port.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.application.port.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P7 (item beeef6f7) S2 and S13: every persisted timestamp is canonical UTC text no matter what the JVM default
 * time zone is.
 *
 * Oracles (frozen test-plan): plan 3.12 l.355-358 and AR-49 (one UTC column type; correctness must not depend on
 * the JVM default zone), task-scope D1 (canonical text `yyyy-MM-dd HH:mm:ss.SSS`, UTC, 23 characters, millisecond
 * TRUNCATION). The expected text is restated here from that spec (see [P7Raw]); the production formatter is never
 * used as an oracle. EXISTING-SURFACE: only declarations that predate the fix are used, so a plain revert of the
 * fix gives behavioral red (a local-time or fraction-less text), not a compile failure.
 *
 * Serial: the default time zone is JVM-global, so this class runs in the serial test task and always restores it.
 * The zone is switched AFTER the database is opened, so the guard that pins UTC at startup cannot mask the check.
 */
@Tag("serial")
class P7UtcTimestampPersistenceTest {
    private val originalZone: TimeZone = TimeZone.getDefault()

    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private val provider get() = sqliteDb.repositoryProvider()
    private val jdbc get() = sqliteDb.jdbcUrl

    @AfterEach
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    private val zones = listOf("Asia/Kolkata", "America/Los_Angeles", "Pacific/Kiritimati", "America/St_Johns", "UTC")

    private val instants =
        listOf(
            Instant.parse("2001-02-03T04:05:06.789Z"), // winter, mid-morning UTC
            Instant.parse("2001-07-04T23:59:59.001Z"), // summer (DST zones), crosses midnight in positive-offset zones
            Instant.parse("2001-12-31T23:30:00.000Z"), // year boundary in positive-offset zones
            Instant.parse("2001-04-01T09:30:00.500Z") // half an hour before the US spring-forward instant
        )

    // ---- S2 --------------------------------------------------------------------------------------------------

    @Test
    fun `S2 created, modified and role-changed instants persist as canonical UTC text under every default zone`(): Unit =
        runBlocking {
            val repo = provider.workItemRepository()
            for (zone in zones) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                for (instant in instants) {
                    val item =
                        repo.create(
                            WorkItem(
                                title = "tz $zone $instant",
                                createdAt = instant,
                                modifiedAt = instant.plusSeconds(1),
                                roleChangedAt = instant.plusSeconds(2)
                            )
                        )

                    val label = "zone=$zone instant=$instant"
                    assertEquals(
                        listOf(P7Raw.canon(instant), P7Raw.canon(instant.plusSeconds(1)), P7Raw.canon(instant.plusSeconds(2))),
                        P7Raw
                            .query(
                                jdbc,
                                "SELECT created_at, modified_at, role_changed_at FROM work_items WHERE id = ?",
                                item.id
                            ) { listOf(it.getString(1), it.getString(2), it.getString(3)) }
                            .single(),
                        label
                    )
                    val reread = assertNotNull(repo.getById(item.id), label)
                    assertEquals(instant, reread.createdAt, label)
                    assertEquals(instant.plusSeconds(1), reread.modifiedAt, label)
                    assertEquals(instant.plusSeconds(2), reread.roleChangedAt, label)
                }
            }
        }

    @Test
    fun `S2 probe sub-millisecond precision is truncated, never rounded`(): Unit =
        runBlocking {
            val repo = provider.workItemRepository()
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
            val base = Instant.parse("2001-02-03T04:05:06.789Z")
            val withNanos = base.plusNanos(999_999) // 06.789999999

            val item = repo.create(WorkItem(title = "nanos", createdAt = withNanos, modifiedAt = withNanos, roleChangedAt = withNanos))

            assertEquals(
                "2001-02-03 04:05:06.789",
                P7Raw.text(jdbc, "SELECT created_at FROM work_items WHERE id = ?", item.id),
                "06.789999999 must be stored as .789 (truncation), not .790"
            )
            assertEquals(base, assertNotNull(repo.getById(item.id)).createdAt)
        }

    // ---- S13 -------------------------------------------------------------------------------------------------

    private data class Col(
        val table: String,
        val column: String,
        val offsetSeconds: Long = 0
    )

    @Test
    fun `S13 one row written by each store has all nineteen timestamp columns as canonical UTC text within the write window`(): Unit =
        runBlocking {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata")) // 5h30m away from UTC: a local-time write is far outside the window
            val ttl = 600L
            val before = Instant.now().truncatedTo(ChronoUnit.MILLIS)

            val repo = provider.workItemRepository()
            val root = repo.create(WorkItem(title = "Root"))
            val other = repo.create(WorkItem(title = "Other"))
            assertIs<ClaimResult.Success>(repo.claim(root.id, "agent-a", ttl.toInt()))
            provider.noteRepository().upsert(Note(itemId = root.id, key = "k", role = "queue", body = "b"))
            provider.dependencyRepository().create(Dependency(fromItemId = root.id, toItemId = other.id))
            provider.roleTransitionRepository().create(
                RoleTransition(itemId = root.id, fromRole = "queue", toRole = "work", trigger = "start")
            )
            provider.planDocumentRepository().stash(root.id, "plan-a", "# Plan\n")
            provider.projectConfigRepository().upsert(root.id, "work_item_schemas:\n  default: {}\n")
            val leases = provider.resourceLeaseRepository()
            assertIs<LeaseAcquireResult.Success>(leases.acquireAll(root.id, "agent-a", listOf("k1" to ttl.toInt())))
            assertIs<LeaseReleaseResult.Success>(leases.releaseAllForItem(root.id))
            assertIs<LeaseAcquireResult.Success>(leases.acquireAll(other.id, "agent-b", listOf("k2" to ttl.toInt())))

            val after = Instant.now()

            val columns =
                listOf(
                    Col("work_items", "created_at"),
                    Col("work_items", "modified_at"),
                    Col("work_items", "role_changed_at"),
                    Col("work_items", "claimed_at"),
                    Col("work_items", "claim_expires_at", ttl),
                    Col("work_items", "original_claimed_at"),
                    Col("notes", "created_at"),
                    Col("notes", "modified_at"),
                    Col("dependencies", "created_at"),
                    Col("plan_documents", "created_at"),
                    Col("plan_documents", "modified_at"),
                    Col("project_config", "updated_at"),
                    Col("role_transitions", "transitioned_at"),
                    Col("resource_leases", "acquired_at"),
                    Col("resource_leases", "expires_at", ttl),
                    Col("resource_leases", "original_acquired_at"),
                    Col("resource_lease_history", "acquired_at"),
                    Col("resource_lease_history", "expires_at", ttl),
                    Col("resource_lease_history", "released_at")
                )
            assertEquals(19, columns.size, "the migration normalizes exactly nineteen timestamp columns")

            for (col in columns) {
                val name = "${col.table}.${col.column}"
                val offending =
                    P7Raw
                        .query(
                            jdbc,
                            "SELECT count(*) FROM ${col.table} WHERE ${col.column} IS NOT NULL AND " +
                                "NOT (typeof(${col.column}) = 'text' AND ${col.column} GLOB '${P7Raw.CANONICAL_GLOB}' AND length(${col.column}) = 23)"
                        ) { it.getInt(1) }
                        .single()
                assertEquals(0, offending, "$name holds a value that is not canonical 23-character text")

                val values = P7Raw.query(jdbc, "SELECT ${col.column} FROM ${col.table} WHERE ${col.column} IS NOT NULL") { it.getString(1) }
                assertTrue(values.isNotEmpty(), "$name was never written, so the scenario did not exercise it")
                val low = before.plusSeconds(col.offsetSeconds)
                val high = after.plusSeconds(col.offsetSeconds)
                values.forEach { text ->
                    val parsed = P7Raw.parseCanon(text)
                    assertTrue(
                        !parsed.isBefore(low) && !parsed.isAfter(high),
                        "$name = $text is outside the write window [$low, $high]; a local-time write shows up as a multi-hour shift"
                    )
                }
            }
        }
}
