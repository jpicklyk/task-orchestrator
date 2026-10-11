package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.service.ItemCreateCommand
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Fixture-only: creates one item through [ToolExecutionContext.itemCommandService] from a flat item spec (the shape
 * the removed `manage_items` create took: `title`, `type`, `parentId`, `priority`, comma-separated `tags` and
 * `traits`, `summary`, `statusLabel`, `properties`, ...), and returns the new id. Fails the test on any error, so a
 * fixture never silently misses an item.
 */
suspend fun seedItem(
    context: ToolExecutionContext,
    spec: JsonObject
): String {
    fun text(key: String): String? = (spec[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val command =
        ItemCreateCommand(
            id = UUID.randomUUID(),
            parentId = text("parentId")?.let { UUID.fromString(it) },
            title = text("title") ?: error("seedItem: 'title' is required"),
            description = text("description"),
            summary = text("summary") ?: "",
            statusLabel = text("statusLabel"),
            priority = text("priority")?.let { Priority.fromString(it) } ?: Priority.MEDIUM,
            complexity = (spec["complexity"] as? JsonPrimitive)?.content?.toIntOrNull(),
            requiresVerification = (spec["requiresVerification"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
            metadata = text("metadata"),
            tags = text("tags"),
            type = text("type") ?: TEST_ITEM_TYPE,
            properties = text("properties"),
            traits = text("traits")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        )
    return when (val created = context.itemCommandService.create(command)) {
        is Outcome.Ok -> created.value.id.toString()
        is Outcome.Err -> error("seedItem failed: ${created.error.code.wire}: ${created.error.message}")
    }
}
