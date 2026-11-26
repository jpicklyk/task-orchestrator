package io.github.jpicklyk.mcptask.current.itemwrite

import io.github.jpicklyk.mcptask.current.itemwrite.internal.wire.completeTreeOp
import io.github.jpicklyk.mcptask.current.itemwrite.internal.wire.createItemOp
import io.github.jpicklyk.mcptask.current.itemwrite.internal.wire.createTreeOp
import io.github.jpicklyk.mcptask.current.itemwrite.internal.wire.deleteItemOp
import io.github.jpicklyk.mcptask.current.itemwrite.internal.wire.repinItemOp
import io.github.jpicklyk.mcptask.current.itemwrite.internal.wire.updateItemOp
import io.github.jpicklyk.mcptask.current.platform.tool.OpsToolSpec
import io.github.jpicklyk.mcptask.current.platform.tool.ToolProfile

internal const val ITEMS_WRITE_DESCRIPTION = "Create, update and delete work items or whole trees, one ops[] element each."

/** The knowledge profile's description: it serves no tree op. */
internal const val ITEMS_WRITE_KNOWLEDGE_DESCRIPTION = "Create, update and delete items, one ops[] element each."

/** The item write slice's contribution to the tool registry: the `items_write` tool. */
object ItemWriteTools {
    /**
     * The `items_write` tool: `create`, `update`, `delete`, `create_tree`, `complete_tree` and `repin` over one `ops[]`
     * array. `create_tree`, `complete_tree` and `repin` are orchestration-only, so the knowledge profile gets its own
     * description without trees (each orchestration op documents itself on the orchestration profile).
     */
    fun tool(): OpsToolSpec<ItemWriteResult> =
        OpsToolSpec(
            name = "items_write",
            description = ITEMS_WRITE_DESCRIPTION,
            ops = listOf(createItemOp(), updateItemOp(), deleteItemOp(), createTreeOp(), completeTreeOp(), repinItemOp()),
            codec = ItemWriteCodec,
            element = ::itemWriteElement,
            profileDescriptions = mapOf(ToolProfile.KNOWLEDGE to ITEMS_WRITE_KNOWLEDGE_DESCRIPTION)
        )
}
