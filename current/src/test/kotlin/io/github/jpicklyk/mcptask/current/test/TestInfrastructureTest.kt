package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class TestInfrastructureTest {

    // ── TestFixtures: makeItem ──

    @Test
    fun `makeItem creates valid WorkItem with defaults`(): Unit {
        val item = makeItem()
        assertEquals("Test Item", item.title)
        assertEquals(Role.QUEUE, item.role)
        assertEquals(Priority.MEDIUM, item.priority)
        assertNull(item.parentId)
        assertEquals(0, item.depth)
        assertNull(item.complexity)
        assertNull(item.tags)
        assertNull(item.statusLabel)
        assertNull(item.description)
        assertEquals("", item.summary)
        assertNull(item.metadata)
        assertFalse(item.requiresVerification)
    }

    @Test
    fun `makeItem with parentId sets depth to 1 by default`(): Unit {
        val parentId = UUID.randomUUID()
        val item = makeItem(parentId = parentId)
        assertEquals(parentId, item.parentId)
        assertEquals(1, item.depth)
    }

    @Test
    fun `makeItem allows overriding all fields`(): Unit {
        val id = UUID.randomUUID()
        val parentId = UUID.randomUUID()
        val item = makeItem(
            id = id,
            title = "Custom Title",
            role = Role.WORK,
            previousRole = Role.QUEUE,
            parentId = parentId,
            depth = 2,
            priority = Priority.HIGH,
            complexity = 7,
            tags = "feature,urgent",
            statusLabel = "in-progress",
            description = "A description",
            summary = "A summary",
            metadata = """{"key":"val"}""",
            requiresVerification = true
        )
        assertEquals(id, item.id)
        assertEquals("Custom Title", item.title)
        assertEquals(Role.WORK, item.role)
        assertEquals(Role.QUEUE, item.previousRole)
        assertEquals(parentId, item.parentId)
        assertEquals(2, item.depth)
        assertEquals(Priority.HIGH, item.priority)
        assertEquals(7, item.complexity)
        assertEquals("feature,urgent", item.tags)
        assertEquals("in-progress", item.statusLabel)
        assertEquals("A description", item.description)
        assertEquals("A summary", item.summary)
        assertEquals("""{"key":"val"}""", item.metadata)
        assertTrue(item.requiresVerification)
    }

    // ── TestFixtures: Dependency builders ──

    @Test
    fun `blocksDep creates BLOCKS dependency`(): Unit {
        val from = UUID.randomUUID()
        val to = UUID.randomUUID()
        val dep = blocksDep(from, to)
        assertEquals(from, dep.fromItemId)
        assertEquals(to, dep.toItemId)
        assertEquals(DependencyType.BLOCKS, dep.type)
        assertNull(dep.unblockAt)
    }

    @Test
    fun `blocksDep with unblockAt`(): Unit {
        val from = UUID.randomUUID()
        val to = UUID.randomUUID()
        val dep = blocksDep(from, to, unblockAt = "work")
        assertEquals("work", dep.unblockAt)
    }

    @Test
    fun `relatesDep creates RELATES_TO dependency`(): Unit {
        val from = UUID.randomUUID()
        val to = UUID.randomUUID()
        val dep = relatesDep(from, to)
        assertEquals(DependencyType.RELATES_TO, dep.type)
        assertNull(dep.unblockAt)
    }

    // ── TestFixtures: Note builder ──

    @Test
    fun `makeNote creates valid Note`(): Unit {
        val itemId = UUID.randomUUID()
        val note = makeNote(itemId = itemId, key = "design", role = "work", body = "Design details")
        assertEquals(itemId, note.itemId)
        assertEquals("design", note.key)
        assertEquals("work", note.role)
        assertEquals("Design details", note.body)
    }

    @Test
    fun `makeNote uses defaults`(): Unit {
        val itemId = UUID.randomUUID()
        val note = makeNote(itemId = itemId)
        assertEquals("test-note", note.key)
        assertEquals("queue", note.role)
        assertEquals("Test body", note.body)
    }

    // ── TestFixtures: JSON helpers ──

    @Test
    fun `params creates JsonObject`(): Unit {
        val obj = params(
            "name" to JsonPrimitive("test"),
            "count" to JsonPrimitive(42)
        )
        assertEquals("test", obj["name"]?.jsonPrimitive?.content)
        assertEquals(42, obj["count"]?.jsonPrimitive?.int)
    }

    @Test
    fun `transitionObj creates transition JSON`(): Unit {
        val id = UUID.randomUUID()
        val obj = transitionObj(id, "start", summary = "Starting work")
        assertEquals(id.toString(), obj["itemId"]?.jsonPrimitive?.content)
        assertEquals("start", obj["trigger"]?.jsonPrimitive?.content)
        assertEquals("Starting work", obj["summary"]?.jsonPrimitive?.content)
    }

    @Test
    fun `transitionObj without summary omits it`(): Unit {
        val id = UUID.randomUUID()
        val obj = transitionObj(id, "complete")
        assertNull(obj["summary"])
    }

    @Test
    fun `buildTransitionParams wraps transitions in array`(): Unit {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val result = buildTransitionParams(
            transitionObj(id1, "start"),
            transitionObj(id2, "complete")
        )
        val transitions = result["transitions"]?.jsonArray
        assertNotNull(transitions)
        assertEquals(2, transitions.size)
    }

    // ── TestFixtures: Response extraction ──

    @Test
    fun `extractSuccessData returns data from success response`(): Unit {
        val response = buildJsonObject {
            put("success", true)
            put("data", buildJsonObject {
                put("id", "abc")
            })
        }
        val data = extractSuccessData(response)
        assertEquals("abc", data["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `extractResults returns results array`(): Unit {
        val response = buildJsonObject {
            put("success", true)
            put("data", buildJsonObject {
                put("results", buildJsonArray {
                    add(buildJsonObject { put("x", 1) })
                    add(buildJsonObject { put("x", 2) })
                })
            })
        }
        val results = extractResults(response)
        assertEquals(2, results.size)
    }

    @Test
    fun `extractSummary returns summary object`(): Unit {
        val response = buildJsonObject {
            put("success", true)
            put("data", buildJsonObject {
                put("summary", buildJsonObject {
                    put("total", 5)
                })
            })
        }
        val summary = extractSummary(response)
        assertEquals(5, summary["total"]?.jsonPrimitive?.int)
    }

    @Test
    fun `assertErrorResponse validates error response`(): Unit {
        val response = buildJsonObject {
            put("success", false)
            put("error", "Item not found")
        }
        val obj = assertErrorResponse(response, "not found")
        assertEquals(false, obj["success"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `assertErrorResponse without expectedMessage`(): Unit {
        val response = buildJsonObject {
            put("success", false)
            put("error", "Something went wrong")
        }
        assertErrorResponse(response)
    }

    // ── MockRepositoryProvider ──

    @Test
    fun `MockRepositoryProvider wires up correctly`(): Unit {
        val mock = MockRepositoryProvider()
        // Verify provider returns the correct mock instances
        assertSame(mock.workItemRepo, mock.provider.workItemRepository())
        assertSame(mock.noteRepo, mock.provider.noteRepository())
        assertSame(mock.depRepo, mock.provider.dependencyRepository())
        assertSame(mock.roleTransitionRepo, mock.provider.roleTransitionRepository())
        assertSame(mock.workTreeExecutor, mock.provider.workTreeExecutor())
        assertNull(mock.provider.database())
    }

    @Test
    fun `MockRepositoryProvider context uses NoOp schema by default`(): Unit {
        val mock = MockRepositoryProvider()
        val ctx = mock.context()
        assertNotNull(ctx)
        assertSame(mock.provider.workItemRepository(), ctx.workItemRepository())
    }

    @Test
    fun `MockRepositoryProvider context with custom schema service`(): Unit {
        val mock = MockRepositoryProvider()
        val ctx = mock.context(TestNoteSchemaService.FEATURE_IMPLEMENTATION)
        assertNotNull(ctx)
        // Schema service should return entries for "feature-implementation" tag
        val schema = ctx.noteSchemaService().getSchemaForTags(listOf("feature-implementation"))
        assertNotNull(schema)
        assertEquals(4, schema.size)
    }

    @Test
    fun `MockRepositoryProvider noteRepo default returns empty lists`(): Unit = runBlocking {
        val mock = MockRepositoryProvider()
        val result = mock.noteRepo.findByItemId(UUID.randomUUID())
        assertIs<Result.Success<*>>(result)
        assertTrue((result as Result.Success).data.isEmpty())
    }

    // ── TestNoteSchemaService ──

    @Test
    fun `FEATURE_IMPLEMENTATION returns correct schema for matching tag`(): Unit {
        val schema = TestNoteSchemaService.FEATURE_IMPLEMENTATION.getSchemaForTags(listOf("feature-implementation"))
        assertNotNull(schema)
        assertEquals(4, schema.size)
        val keys = schema.map { it.key }
        assertTrue("requirements" in keys)
        assertTrue("design" in keys)
        assertTrue("implementation-notes" in keys)
        assertTrue("review-checklist" in keys)
    }

    @Test
    fun `FEATURE_IMPLEMENTATION has review phase`(): Unit {
        assertTrue(TestNoteSchemaService.FEATURE_IMPLEMENTATION.hasReviewPhase(listOf("feature-implementation")))
    }

    @Test
    fun `BUG_FIX returns correct schema for matching tag`(): Unit {
        val schema = TestNoteSchemaService.BUG_FIX.getSchemaForTags(listOf("bug-fix"))
        assertNotNull(schema)
        assertEquals(2, schema.size)
        val keys = schema.map { it.key }
        assertTrue("root-cause" in keys)
        assertTrue("fix-details" in keys)
    }

    @Test
    fun `BUG_FIX has no review phase`(): Unit {
        assertFalse(TestNoteSchemaService.BUG_FIX.hasReviewPhase(listOf("bug-fix")))
    }

    @Test
    fun `NONE returns null for any tags`(): Unit {
        assertNull(TestNoteSchemaService.NONE.getSchemaForTags(listOf("feature-implementation")))
        assertNull(TestNoteSchemaService.NONE.getSchemaForTags(listOf("bug-fix")))
        assertNull(TestNoteSchemaService.NONE.getSchemaForTags(emptyList()))
    }

    @Test
    fun `schema service returns null for unrecognized tags`(): Unit {
        assertNull(TestNoteSchemaService.FEATURE_IMPLEMENTATION.getSchemaForTags(listOf("unrelated-tag")))
    }

    // ── BaseRepositoryTest ──

    @Nested
    inner class BaseRepositoryTestValidation : BaseRepositoryTest() {

        @Test
        fun `can create and read back items`(): Unit = runBlocking {
            val item = createPersistedItem(title = "Persisted Item")
            assertEquals("Persisted Item", item.title)
            assertEquals(Role.QUEUE, item.role)

            // Read back via repository
            val result = repositoryProvider.workItemRepository().getById(item.id)
            assertIs<Result.Success<*>>(result)
            assertEquals("Persisted Item", (result as Result.Success).data.title)
        }

        @Test
        fun `can create item with parent`(): Unit = runBlocking {
            val parent = createPersistedItem(title = "Parent")
            val child = createPersistedItem(title = "Child", parentId = parent.id)
            assertEquals(parent.id, child.parentId)
            assertEquals(1, child.depth)
        }

        @Test
        fun `can create and read back notes`(): Unit = runBlocking {
            val item = createPersistedItem(title = "Item with note")
            val note = createPersistedNote(itemId = item.id, key = "design", role = "work", body = "Design doc")
            assertEquals("design", note.key)
            assertEquals("work", note.role)
            assertEquals("Design doc", note.body)

            val result = repositoryProvider.noteRepository().findByItemId(item.id)
            assertIs<Result.Success<*>>(result)
            assertEquals(1, (result as Result.Success).data.size)
        }

        @Test
        fun `can create and read back dependencies`(): Unit = runBlocking {
            val item1 = createPersistedItem(title = "Item 1")
            val item2 = createPersistedItem(title = "Item 2")
            val dep = createPersistedDependency(fromItemId = item1.id, toItemId = item2.id)
            assertEquals(item1.id, dep.fromItemId)
            assertEquals(item2.id, dep.toItemId)
            assertEquals(DependencyType.BLOCKS, dep.type)

            val deps = repositoryProvider.dependencyRepository().findByItemId(item1.id)
            assertEquals(1, deps.size)
        }
    }
}
