package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.event.EventTools
import io.github.jpicklyk.mcptask.current.item.ItemTools
import io.github.jpicklyk.mcptask.current.knowledge.KnowledgeTools
import io.github.jpicklyk.mcptask.current.lifecycle.LifecycleTools
import io.github.jpicklyk.mcptask.current.platform.tool.SelectToolSpec
import io.github.jpicklyk.mcptask.current.platform.tool.ToolProfile

/**
 * The 4.0 `items_read` tool (plan section 4.1, Q19, Q22): one select per file (`ItemsRead<Select>.kt`), assembled here,
 * so later items add a select by adding a file. Every select needs READ; `get`, `list`, `schema`, `search` and
 * `overview` are KNOWLEDGE-profile, `context`, `next`, `blocked` and `history` ORCHESTRATION-only (plan section 3.1).
 *
 * Shared request fields (a name used by two selects) take their documentation from `ItemsReadDocs`, so every select
 * generates the identical schema for them (the registry rejects a mismatch). The shared strings name only knowledge
 * selects: what an orchestration select does with a shared field (a default limit, the `fields` it takes) is in its own
 * select `doc`, which the knowledge profile never renders.
 */
object ItemsReadTool {
    const val NAME = "items_read"

    /** The knowledge selects, the opening every profile's text shares (select enumeration only: doc single-source policy). */
    private const val KNOWLEDGE_SELECTS =
        "Read work items. Selects: get (one item), list (a filtered page), schema (the note schema of a type or an " +
            "item, or the declared types), search (full text over item, note and document content), " +
            "overview (a hierarchy summary), related " +
            "(items and documents related to an item by dependencies, links, shared tags and similar text)"

    /** The shared (full) description, served to orchestration and REST: every select. */
    const val DESCRIPTION =
        KNOWLEDGE_SELECTS +
            ", context (an item's gate state, or a health view without itemId), next (ranked items ready to pick up), " +
            "blocked (blocked items with their blockers), history (the transition audit trail)."

    /** The knowledge profile's description: it serves no context, next, blocked or history. */
    const val KNOWLEDGE_DESCRIPTION = "$KNOWLEDGE_SELECTS."

    /** The director profile's description: it serves get, list, overview and blocked only. */
    const val DIRECTOR_DESCRIPTION =
        "Read work items. Selects: get (one item), list (a filtered page), overview (a hierarchy summary), " +
            "blocked (blocked items with their blockers)."

    val spec: SelectToolSpec =
        SelectToolSpec(
            name = NAME,
            description = DESCRIPTION,
            profileDescriptions = mapOf(ToolProfile.KNOWLEDGE to KNOWLEDGE_DESCRIPTION, ToolProfile.DIRECTOR to DIRECTOR_DESCRIPTION),
            selects =
                listOf(
                    ItemTools.itemsReadGet,
                    ItemTools.itemsReadList,
                    LifecycleTools.itemsReadSchema,
                    KnowledgeTools.itemsReadSearch,
                    ItemTools.itemsReadOverview,
                    KnowledgeTools.itemsReadRelated,
                    LifecycleTools.itemsReadContext,
                    LifecycleTools.itemsReadNext,
                    LifecycleTools.itemsReadBlocked,
                    EventTools.itemsReadHistory,
                )
        )
}
