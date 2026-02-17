package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Read-only MCP tool that identifies WorkItems blocked by dependencies or explicitly in BLOCKED role.
 *
 * An item is considered "blocked" if:
 * 1. It is explicitly in [Role.BLOCKED] (set via block/hold trigger) -- blockType = "explicit"
 * 2. It is in QUEUE, WORK, or REVIEW role and has unsatisfied blocking dependencies -- blockType = "dependency"
 *
 * Items in TERMINAL role are never included (they are done).
 *
 * For each blocked item, the tool resolves the full blocker chain with satisfaction status,
 * enabling agents to identify bottlenecks and plan unblocking actions.
 */
class GetBlockedItemsTool : BaseToolDefinition() {

    override val name = "get_blocked_items"

    override val description = """
Identifies WorkItems blocked by dependencies or explicitly in BLOCKED role.

**Parameters:**
- `parentId` (optional UUID): scope results to items under this parent
- `includeItemDetails` (optional boolean, default false): include summary and tags for each blocked item

**What counts as blocked:**
1. Items explicitly in BLOCKED role (blockType = "explicit")
2. Items in QUEUE/WORK/REVIEW with unsatisfied blocking dependencies (blockType = "dependency")

Items in TERMINAL role are never included.

**Response shape:**
```json
{
  "blockedItems": [
    {
      "itemId": "uuid",
      "title": "...",
      "role": "blocked",
      "priority": "high",
      "complexity": 5,
      "blockType": "explicit",
      "blockedBy": [
        {
          "itemId": "uuid",
          "title": "...",
          "role": "queue",
          "unblockAt": "terminal",
          "effectiveUnblockRole": "terminal",
          "satisfied": false
        }
      ],
      "blockerCount": 2
    }
  ],
  "total": N
}
```
    """.trimIndent()

    override val category = ToolCategory.WORKFLOW

    override val toolAnnotations = ToolAnnotations(
        readOnlyHint = true,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = false
    )

    override val parameterSchema = ToolSchema(
        properties = buildJsonObject {
            put("parentId", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Scope results to items under this parent UUID"))
            })
            put("includeItemDetails", buildJsonObject {
                put("type", JsonPrimitive("boolean"))
                put("description", JsonPrimitive("Include summary and tags for each blocked item (default: false)"))
            })
        },
        required = emptyList()
    )

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val parentId = extractUUID(params, "parentId", required = false)
        val includeDetails = optionalBoolean(params, "includeItemDetails", false)

        val workItemRepo = context.workItemRepository()
        val depRepo = context.dependencyRepository()

        // Gather candidate items across all non-terminal roles
        val candidateRoles = listOf(Role.BLOCKED, Role.QUEUE, Role.WORK, Role.REVIEW)
        val allCandidates = mutableListOf<WorkItem>()

        for (role in candidateRoles) {
            val items = if (parentId != null) {
                when (val result = workItemRepo.findByFilters(parentId = parentId, role = role, limit = 500)) {
                    is Result.Success -> result.data
                    is Result.Error -> {
                        logger.warn("Failed to query items for role $role: ${result.error.message}")
                        emptyList()
                    }
                }
            } else {
                when (val result = workItemRepo.findByRole(role, limit = 500)) {
                    is Result.Success -> result.data
                    is Result.Error -> {
                        logger.warn("Failed to query items for role $role: ${result.error.message}")
                        emptyList()
                    }
                }
            }
            allCandidates.addAll(items)
        }

        // Deduplicate by ID (in case findByFilters returns overlapping results)
        val candidateMap = allCandidates.associateBy { it.id }

        // Process each candidate to determine if it is truly blocked
        val blockedItems = mutableListOf<JsonObject>()

        for ((_, item) in candidateMap) {
            // Get all deps where this item is the target (BLOCKS deps pointing at this item)
            val incomingDeps = depRepo.findByToItemId(item.id)
            // Get all deps where this item is the source with IS_BLOCKED_BY type
            val outgoingDeps = depRepo.findByFromItemId(item.id)

            // Collect all blocking dependencies:
            // 1. BLOCKS deps where dep.toItemId == item.id -> blocker is dep.fromItemId
            // 2. IS_BLOCKED_BY deps where dep.fromItemId == item.id -> blocker is dep.toItemId
            val blockerInfos = mutableListOf<BlockerInfo>()

            for (dep in incomingDeps) {
                if (dep.type == DependencyType.BLOCKS) {
                    blockerInfos.add(
                        BlockerInfo(
                            blockerItemId = dep.fromItemId,
                            unblockAt = dep.unblockAt,
                            effectiveUnblockRole = dep.effectiveUnblockRole()
                        )
                    )
                }
            }

            for (dep in outgoingDeps) {
                if (dep.type == DependencyType.IS_BLOCKED_BY) {
                    blockerInfos.add(
                        BlockerInfo(
                            blockerItemId = dep.toItemId,
                            unblockAt = dep.unblockAt,
                            effectiveUnblockRole = dep.effectiveUnblockRole()
                        )
                    )
                }
            }

            // For items explicitly in BLOCKED role, always include them
            if (item.role == Role.BLOCKED) {
                val blockedByArray = resolveBlockerDetails(blockerInfos, workItemRepo)
                blockedItems.add(buildBlockedItemJson(item, "explicit", blockedByArray, includeDetails))
                continue
            }

            // For QUEUE/WORK/REVIEW items, check if any blocking dep is unsatisfied
            if (blockerInfos.isEmpty()) continue

            val blockedByArray = resolveBlockerDetails(blockerInfos, workItemRepo)
            val unsatisfiedCount = countUnsatisfied(blockedByArray)

            if (unsatisfiedCount > 0) {
                blockedItems.add(
                    buildBlockedItemJson(
                        item, "dependency",
                        blockedByArray, includeDetails,
                        blockerCount = unsatisfiedCount
                    )
                )
            }
        }

        val data = buildJsonObject {
            put("blockedItems", JsonArray(blockedItems))
            put("total", JsonPrimitive(blockedItems.size))
        }

        return successResponse(data)
    }

    override fun userSummary(params: JsonElement, result: JsonElement, isError: Boolean): String {
        if (isError) return "get_blocked_items failed"
        val data = (result as? JsonObject)?.get("data") as? JsonObject
        val total = data?.get("total")?.let {
            if (it is JsonPrimitive) it.intOrNull else null
        } ?: 0
        return "Found $total blocked item(s)"
    }

    // ──────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────

    /**
     * Resolves blocker details by looking up each blocker item and computing satisfaction status.
     */
    private suspend fun resolveBlockerDetails(
        blockerInfos: List<BlockerInfo>,
        workItemRepo: WorkItemRepository
    ): JsonArray {
        val entries = blockerInfos.map { info ->
            val blockerItem = when (val result = workItemRepo.getById(info.blockerItemId)) {
                is Result.Success -> result.data
                is Result.Error -> null
            }
            val blockerRole = blockerItem?.role ?: Role.QUEUE
            val thresholdRole = info.effectiveUnblockRole?.let { Role.fromString(it) } ?: Role.TERMINAL
            val satisfied = Role.isAtOrBeyond(blockerRole, thresholdRole)

            buildJsonObject {
                put("itemId", JsonPrimitive(info.blockerItemId.toString()))
                put("title", JsonPrimitive(blockerItem?.title ?: "Unknown"))
                put("role", JsonPrimitive(blockerRole.name.lowercase()))
                info.unblockAt?.let { put("unblockAt", JsonPrimitive(it)) }
                info.effectiveUnblockRole?.let { put("effectiveUnblockRole", JsonPrimitive(it)) }
                put("satisfied", JsonPrimitive(satisfied))
            }
        }
        return JsonArray(entries)
    }

    /**
     * Builds the JSON representation of a blocked item with its blockers.
     */
    private fun buildBlockedItemJson(
        item: WorkItem,
        blockType: String,
        blockedBy: JsonArray,
        includeDetails: Boolean,
        blockerCount: Int? = null
    ): JsonObject {
        val count = blockerCount ?: countUnsatisfied(blockedBy)
        return buildJsonObject {
            put("itemId", JsonPrimitive(item.id.toString()))
            put("title", JsonPrimitive(item.title))
            put("role", JsonPrimitive(item.role.name.lowercase()))
            put("priority", JsonPrimitive(item.priority.name.lowercase()))
            put("complexity", JsonPrimitive(item.complexity))
            put("blockType", JsonPrimitive(blockType))
            put("blockedBy", blockedBy)
            put("blockerCount", JsonPrimitive(count))
            if (includeDetails) {
                put("summary", JsonPrimitive(item.summary))
                item.tags?.let { put("tags", JsonPrimitive(it)) }
            }
        }
    }

    private fun countUnsatisfied(blockedBy: JsonArray): Int {
        return blockedBy.count { entry ->
            val entryObj = entry as? JsonObject
            val satisfied = entryObj?.get("satisfied")?.jsonPrimitive?.boolean ?: true
            !satisfied
        }
    }

    /**
     * Internal data holder for collected blocker information before resolution.
     */
    private data class BlockerInfo(
        val blockerItemId: UUID,
        val unblockAt: String?,
        val effectiveUnblockRole: String?
    )
}
