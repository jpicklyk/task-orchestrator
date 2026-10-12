package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f9fab3df) for ItemView over a lifecycle-free item (role == null).
 *
 * Oracle: O4, the ItemView lean rule (a null member is omitted) with the declared compact member list
 * (`id`, `parentId`, `type`, `title`, `role`, `statusLabel`, `priority`, `depth`, `tags`, `traits`, `version`), and O5 task-scope
 * Build 5 (full and compact omit `role` when null; nothing substitutes "queue"). A non-null role renders as its lowercase wire name.
 */
class ItemViewNullRoleTest {
    private val itemId = UUID.fromString("12345678-0000-0000-0000-000000000001")

    private fun none() = WorkItem(id = itemId, title = "Lifecycle free", role = null)

    private fun JsonObject.hasValue(text: String): Boolean = values.any { it is JsonPrimitive && it.isString && it.content == text }

    // S12
    @Test
    fun `S12 full omits the role member for a null role`() {
        val view = ItemView.full(none())

        assertFalse(view.containsKey("role"), "role must be omitted, got $view")
        assertEquals("Lifecycle free", view.getValue("title").jsonPrimitive.content)
        assertFalse(view.hasValue("queue"), "no member may fall back to queue, got $view")
    }

    @Test
    fun `S12 full with timestamps omits the role member for a null role`() {
        val view = ItemView.full(none(), timestamps = true)

        assertFalse(view.containsKey("role"), "role must be omitted, got $view")
        assertFalse(view.hasValue("queue"), "no member may fall back to queue, got $view")
    }

    @Test
    fun `S12 compact omits the role member for a null role`() {
        val view = ItemView.compact(none())

        assertFalse(view.containsKey("role"), "role must be omitted, got $view")
        assertEquals("Lifecycle free", view.getValue("title").jsonPrimitive.content)
        assertEquals("12345678", view.getValue("id").jsonPrimitive.content)
        assertFalse(view.hasValue("queue"), "no member may fall back to queue, got $view")
    }

    @Test
    fun `S12 compact with summary and timestamps omits the role member for a null role`() {
        val view = ItemView.compact(none(), summary = true, timestamps = true)

        assertFalse(view.containsKey("role"), "role must be omitted, got $view")
        assertFalse(view.hasValue("queue"), "no member may fall back to queue, got $view")
    }

    @Test
    fun `S12 a queue item still renders its role as queue in full and compact`() {
        val queued = WorkItem(id = itemId, title = "Queued", role = Role.QUEUE)

        assertEquals(
            "queue",
            ItemView
                .full(queued)
                .getValue("role")
                .jsonPrimitive.content
        )
        assertEquals(
            "queue",
            ItemView
                .compact(queued)
                .getValue("role")
                .jsonPrimitive.content
        )
    }

    @Test
    fun `S12 every non-null role renders as its lowercase wire name in full and compact`() {
        for (role in Role.entries) {
            val item = WorkItem(id = itemId, title = "Row", role = role)
            val expected = role.name.lowercase()
            assertEquals(
                expected,
                ItemView
                    .full(item)
                    .getValue("role")
                    .jsonPrimitive.content,
                "full role=$role"
            )
            assertEquals(
                expected,
                ItemView
                    .compact(item)
                    .getValue("role")
                    .jsonPrimitive.content,
                "compact role=$role"
            )
        }
    }

    @Test
    fun `S12 the null role view keeps the other members that a queue view has`() {
        val queued = WorkItem(id = itemId, title = "Same", role = Role.QUEUE)
        val none = WorkItem(id = itemId, title = "Same", role = null)

        assertEquals(ItemView.full(queued).keys - "role", ItemView.full(none).keys)
        assertEquals(ItemView.compact(queued).keys - "role", ItemView.compact(none).keys)
        assertTrue(ItemView.full(none).keys.isNotEmpty())
    }
}
