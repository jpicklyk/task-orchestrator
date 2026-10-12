package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.api.IdResolution
import io.github.jpicklyk.mcptask.current.application.api.IdResolver
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.toOutcome
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.service.AdvanceCascadeEvent
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCodec
import io.github.jpicklyk.mcptask.current.application.service.withEventActor
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.PersistenceFaults
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/** One cascade the old parent of a moved or deleted item ran: [from] and [to] are role names, [reason] the blocking code. */
data class CascadeSummary(
    val itemId: UUID,
    val from: String,
    val to: String,
    val applied: Boolean,
    val reason: String? = null
)

/**
 * The result of one `items_write` element. [id] is the item the element wrote (the tree root for `create_tree`, the
 * `rootId` of a `complete_tree`; omitted when there is none). [version] is set by create, update, `create_tree` and
 * `repin`, [deleted] (the item plus its descendants) by delete; [cascade] lists the old parent's cascades and is
 * omitted from the wire when empty. [payload] holds an op's own members, spliced into `results[i]` after them
 * (`repin`: `repinned` and, for a pinned item, `schemaVersion`).
 */
data class ItemWriteResult(
    val id: UUID? = null,
    val version: Long? = null,
    val deleted: Int? = null,
    val cascade: List<CascadeSummary> = emptyList(),
    val payload: JsonObject = JsonObject(emptyMap())
)

/** Optional fields an `update` can set to null. */
@Serializable
enum class ClearField {
    @SerialName("description")
    DESCRIPTION,

    @SerialName("statusLabel")
    STATUS_LABEL,

    @SerialName("metadata")
    METADATA,

    @SerialName("complexity")
    COMPLEXITY,

    @SerialName("properties")
    PROPERTIES,

    @SerialName("parentId")
    PARENT_ID
}

/** Field documentation shared by the ops: a field in two ops must carry the same text (one generated schema). */
internal object ItemDocs {
    const val ID = "Item UUID or 8+ hex prefix."
    const val TITLE = "Item title."
    const val TYPE = "Declared item type; required on create."
    const val PARENT_ID = "Parent id; omit for a root. Update moves the item."
    const val DESCRIPTION = "Long description."
    const val SUMMARY = "Short summary."
    const val STATUS_LABEL = "Free-form status label."
    const val PRIORITY = "Create defaults to medium."
    const val COMPLEXITY = "1 to 10."
    const val REQUIRES_VERIFICATION = "Completion needs verification."
    const val METADATA = "Free-form metadata text."
    const val TAGS = "Lowercase, hyphenated. Update: [] clears."
    const val TRAITS = "Declared trait names. Update: [] clears."
    const val PROPERTIES = "Free-form JSON object without a traits key."
    const val EXPECTED_VERSION = "Fails version_conflict if the item is at another version."
    const val CLEAR = "Update only: optional fields to set to null."
    const val RECURSIVE = "Delete the subtree; required when the item has children."
}

internal const val ITEMS_WRITE_DESCRIPTION = "Create, update and delete work items or whole trees, one ops[] element each."

/**
 * The `items_write` tool: `create`, `update`, `delete`, `create_tree`, `complete_tree` and `repin` over one `ops[]`
 * array. `create_tree`, `complete_tree` and `repin` are orchestration-only, so the description names only the knowledge
 * ops and trees (each orchestration op documents itself on the orchestration profile).
 */
internal fun itemsWriteTool(): OpsToolSpec<ItemWriteResult> =
    OpsToolSpec(
        name = "items_write",
        description = ITEMS_WRITE_DESCRIPTION,
        ops = listOf(createItemOp(), updateItemOp(), deleteItemOp(), createTreeOp(), completeTreeOp(), repinItemOp()),
        codec = ItemWriteCodec,
        element = ::itemWriteElement
    )

/** The `results[i]` fields of a successful element. */
internal fun itemWriteElement(result: ItemWriteResult): JsonObject =
    buildJsonObject {
        result.id?.let { put("id", it.toString()) }
        result.version?.let { put("version", it) }
        result.deleted?.let { put("deleted", it) }
        if (result.cascade.isNotEmpty()) {
            put(
                "cascade",
                JsonArray(
                    result.cascade.map { c ->
                        buildJsonObject {
                            put("itemId", c.itemId.toString())
                            put("from", c.from)
                            put("to", c.to)
                            put("applied", c.applied)
                            c.reason?.let { put("reason", it) }
                        }
                    }
                )
            )
        }
        for ((key, value) in result.payload) put(key, value)
    }

/** Stores an element's result in an idempotency record: exactly the fields [itemWriteElement] emits. */
internal object ItemWriteCodec : IdempotencyCodec<ItemWriteResult> {
    /** The members [itemWriteElement] writes from the typed fields; every other member is [ItemWriteResult.payload]. */
    private val RESULT_MEMBERS = setOf("id", "version", "deleted", "cascade")

    override fun encode(value: ItemWriteResult): JsonElement = itemWriteElement(value)

    override fun decode(json: JsonElement): ItemWriteResult {
        val obj = json.jsonObject
        return ItemWriteResult(
            id = obj["id"]?.jsonPrimitive?.contentOrNull?.let(UUID::fromString),
            version = obj["version"]?.jsonPrimitive?.longOrNull,
            deleted = obj["deleted"]?.jsonPrimitive?.intOrNull,
            cascade =
                (obj["cascade"] as? JsonArray).orEmpty().map { element ->
                    val c = element.jsonObject
                    CascadeSummary(
                        itemId = UUID.fromString(c.getValue("itemId").jsonPrimitive.content),
                        from = c.getValue("from").jsonPrimitive.content,
                        to = c.getValue("to").jsonPrimitive.content,
                        applied = c.getValue("applied").jsonPrimitive.booleanOrNull ?: false,
                        reason = c["reason"]?.jsonPrimitive?.contentOrNull
                    )
                },
            payload = JsonObject(obj.filterKeys { it !in RESULT_MEMBERS })
        )
    }
}

/** The cascades an old parent ran; an unapplied one carries the catalog code that blocked it. */
internal fun cascadeSummaries(events: List<AdvanceCascadeEvent>): List<CascadeSummary> =
    events.map { event ->
        CascadeSummary(
            itemId = event.itemId,
            from = event.previousRole.name.lowercase(),
            to = event.targetRole.name.lowercase(),
            applied = event.applied,
            reason =
                when {
                    event.applied -> null
                    event.gateBlocked -> ErrorCode.GATE_BLOCKED.wire
                    event.resourceBlocked -> ErrorCode.RESOURCE_UNAVAILABLE.wire
                    event.dependencyBlocked -> ErrorCode.DEPENDENCY_UNMET.wire
                    else -> ErrorCode.INVALID_TRANSITION.wire
                }
        )
    }

/**
 * Runs [block] as the element's body: the caller's actor is installed for the `item.*` event rows the writes record
 * (the dispatcher installs none), and a per-root config fault escaping the service fails this element as
 * `unavailable` instead of `internal`.
 */
internal suspend fun <T> itemWriteBody(
    ctx: CallContext,
    block: suspend () -> Outcome<T>
): Outcome<T> =
    try {
        withEventActor(ctx.principal?.claim, block)
    } catch (e: PerRootConfigUnavailableException) {
        Outcome.Err(
            DomainError(
                ErrorCode.UNAVAILABLE,
                e.message,
                ErrorDetail.Unavailable(PersistenceFaults.BUSY_RETRY_AFTER_MS)
            )
        )
    }

/**
 * Resolves [ref] to an item id. Under a restricted scope a prefix that names nothing, or several items, is
 * `forbidden` (fail closed, like [ScopeGuard]): scope must not be an existence oracle or list foreign candidates.
 */
internal suspend fun resolveItemId(
    env: ToolExecutionContext,
    scope: AccessScope,
    ref: IdRef,
    field: String
): Outcome<UUID> {
    val resolution = IdResolver(env.workItemRepository()).resolve(ref)
    if (!scope.isUnrestricted && (resolution is IdResolution.NotFound || resolution is IdResolution.Ambiguous)) {
        return Outcome.Err(scopeDenied(scope))
    }
    return resolution.toOutcome(field)
}

/** Checks the caller's scope admits item [id] (an unrestricted scope reads nothing). */
internal suspend fun checkItemScope(
    env: ToolExecutionContext,
    ctx: CallContext,
    id: UUID
): Outcome<Unit> = ScopeGuard.check(ctx.scope, id, env.workItemRepository())

/** Joins [tags] as the stored comma-separated column; an empty list is no tags. */
internal fun joinTags(tags: List<String>): String? = tags.joinToString(",").takeIf { it.isNotBlank() }

/** True when [tags] (trimmed, exact match) carry a tag the scope lists, or the scope lists none. */
internal fun tagsSatisfyScope(
    tags: List<String>,
    scope: AccessScope
): Boolean = scope.tagsInclude.isEmpty() || tags.map { it.trim() }.any { it in scope.tagsInclude }
