package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f9fab3df) for point reads of a lifecycle-free row (stored role NULL).
 *
 * Oracles (frozen test-plan): O2 plan U13a (a NULL role column IS the lifecycle-free representation; V26 CHECK
 * `(role IS NULL) = (lifecycle = 'none')`) and O5 task-scope Build 4 (point reads stop folding NULL to QUEUE;
 * `rolesOf` returns null for such a row; an id with no row is absent from the result, per the declared ItemStore KDoc).
 * Fixture rows are inserted with raw SQL because the store has no lifecycle-free write path yet; every fixture satisfies the
 * V26 relation by construction (role NULL with lifecycle 'none', or role non-NULL with lifecycle NULL). Column names are the ones
 * of the work_items table in migration V26.
 */
class NullRoleReadTest {
    private val t: Instant = Instant.parse("2001-02-03T04:05:06.789Z")

    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private val provider get() = sqliteDb.repositoryProvider()
    private val items get() = provider.itemStore()
    private val jdbc get() = sqliteDb.jdbcUrl

    /** Inserts a row with the V26 relation CHECK satisfied: a null [role] is paired with lifecycle 'none'. */
    private fun insertRaw(
        id: UUID,
        title: String,
        role: String?,
        parentId: UUID? = null,
        depth: Int = 0
    ) {
        val ts = P7Raw.canon(t)
        P7Raw.exec(
            jdbc,
            "INSERT INTO work_items (id, parent_id, title, role, lifecycle, depth, created_at, modified_at, role_changed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id,
            parentId,
            title,
            role,
            if (role == null) "none" else null,
            depth,
            ts,
            ts,
            ts
        )
    }

    // S10: the point read does not fold a NULL role to QUEUE.
    @Test
    fun `S10 getById of a row stored with role NULL and lifecycle none reads back a null role`(): Unit =
        runBlocking {
            val noneId = UUID.randomUUID()
            insertRaw(noneId, "Lifecycle free", role = null)

            val item = assertNotNull(items.getById(noneId))

            assertNull(item.role)
            assertEquals("Lifecycle free", item.title)
        }

    @Test
    fun `S10 getById of a row stored with role work reads back WORK`(): Unit =
        runBlocking {
            val workId = UUID.randomUUID()
            insertRaw(workId, "Working", role = "work")

            assertEquals(Role.WORK, assertNotNull(items.getById(workId)).role)
        }

    @Test
    fun `S10 an item created through the store with the default role still reads back QUEUE`(): Unit =
        runBlocking {
            val created = items.create(WorkItem(title = "Default role"))

            assertEquals(Role.QUEUE, created.role)
            assertEquals(Role.QUEUE, assertNotNull(items.getById(created.id)).role)
        }

    @Test
    fun `S10 findByIds returns the null role row with a null role and its neighbour unchanged`(): Unit =
        runBlocking {
            val noneId = UUID.randomUUID()
            val reviewId = UUID.randomUUID()
            insertRaw(noneId, "None", role = null)
            insertRaw(reviewId, "Review", role = "review")

            val byId = items.findByIds(setOf(noneId, reviewId)).associateBy { it.id }

            assertEquals(setOf(noneId, reviewId), byId.keys)
            assertNull(byId.getValue(noneId).role)
            assertEquals(Role.REVIEW, byId.getValue(reviewId).role)
        }

    @Test
    fun `S10 a null role child is returned by findChildren with a null role`(): Unit =
        runBlocking {
            val parent = items.create(WorkItem(title = "Parent"))
            val childId = UUID.randomUUID()
            insertRaw(childId, "Child", role = null, parentId = parent.id, depth = 1)

            val child = items.findChildren(parent.id).single { it.id == childId }

            assertNull(child.role)
        }

    // S11: rolesOf keeps a null for the lifecycle-free row, distinct from an absent id.
    @Test
    fun `S11 rolesOf maps the lifecycle-free id to null and the queue id to QUEUE`(): Unit =
        runBlocking {
            val noneId = UUID.randomUUID()
            val queueId = UUID.randomUUID()
            insertRaw(noneId, "None", role = null)
            insertRaw(queueId, "Queue", role = "queue")

            val roles: Map<UUID, Role?> = items.rolesOf(setOf(noneId, queueId))

            assertEquals(mapOf<UUID, Role?>(noneId to null, queueId to Role.QUEUE), roles)
            assertTrue(roles.containsKey(noneId), "a null-role id must be present with a null value, not absent")
        }

    @Test
    fun `S11 rolesOf leaves an id with no row absent while keeping the null role id present`(): Unit =
        runBlocking {
            val noneId = UUID.randomUUID()
            val missingId = UUID.randomUUID()
            insertRaw(noneId, "None", role = null)

            val roles = items.rolesOf(setOf(noneId, missingId))

            assertTrue(roles.containsKey(noneId))
            assertNull(roles[noneId])
            assertEquals(false, roles.containsKey(missingId))
            assertEquals(1, roles.size)
        }

    @Test
    fun `S11 rolesOf reports every stored non-null role unchanged`(): Unit =
        runBlocking {
            val ids = Role.entries.associateWith { UUID.randomUUID() }
            ids.forEach { (role, id) -> insertRaw(id, "Row ${role.name}", role = role.name.lowercase()) }

            val roles = items.rolesOf(ids.values.toSet())

            assertEquals(ids.entries.associate { (role, id) -> id to role }, roles)
        }
}
