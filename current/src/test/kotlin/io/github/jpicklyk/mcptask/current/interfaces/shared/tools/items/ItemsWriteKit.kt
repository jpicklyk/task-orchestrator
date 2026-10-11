package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.findByFilters
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.mcp.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.Dispatched
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.ParityHarness
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.callMcp
import io.github.jpicklyk.mcptask.current.parity.parityAccess
import io.github.jpicklyk.mcptask.current.test.TEST_ITEM_TYPE
import io.github.jpicklyk.mcptask.current.test.createCommand
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.test.assertEquals

/** Gated schema config (types test-item and feature-task) plus two declared traits. */
internal const val IW_CONFIG: String =
    EventLogRig.GATED_CONFIG + "traits:\n  t-one:\n    notes: []\n  t-two:\n    notes: []\n"

internal val IW_ACTOR: JsonObject =
    buildJsonObject {
        put("id", "agent-1")
        put("kind", "subagent")
    }

/** Thin plumbing over the real composition; no assertion logic lives here. */
internal class IwKit(
    val rig: EventLogRig
) {
    private val registry = ToolRegistry(buildToolSpecs())
    private val spec = checkNotNull(registry["items_write"]) { "items_write is not registered" }

    private fun args(
        ops: Array<out JsonObject>,
        atomic: Boolean,
        actor: JsonObject?
    ): JsonObject =
        buildJsonObject {
            actor?.let { put("actor", it) }
            if (atomic) put("atomic", true)
            put("ops", JsonArray(ops.toList()))
        }

    /** Unscoped dispatch over the real dispatcher (the MCP default path: the orchestration profile the server serves). */
    suspend fun call(
        vararg ops: JsonObject,
        atomic: Boolean = false,
        actor: JsonObject? = null
    ): Dispatched = buildToolDispatcher(rig.ctx).dispatch(spec, ToolProfile.ORCHESTRATION, args(ops, atomic, actor), "k7f3q9ab", "mcp")

    /** A real MCP client/server call under the token's principal, scope and capabilities; returns the envelope body. */
    suspend fun callScoped(
        token: ParityToken,
        vararg ops: JsonObject,
        atomic: Boolean = false
    ): JsonObject = callMcp(rig.ctx, registry, { parityAccess(token) }, "items_write", args(ops, atomic, null)).structured

    suspend fun item(id: UUID): WorkItem? = rig.raw.workItemRepository().getById(id)

    suspend fun all(): List<WorkItem> =
        rig.raw
            .workItemRepository()
            .findByFilters()
            .items

    suspend fun titles(): List<String> = all().map { it.title }.sorted()

    /** The comparable database content (work_items, notes, dependencies, events), timestamps and generated ids dropped. */
    fun snapshot(): Map<String, List<Map<String, Any?>>> = ParityHarness.snapshot(rig.db.jdbcUrl)

    /** Seeds through ItemCommandService (never the tool under test); depth and root derive from the parent. */
    suspend fun make(
        title: String,
        parent: WorkItem? = null,
        tags: String? = null,
        id: UUID = UUID.randomUUID(),
        type: String? = TEST_ITEM_TYPE,
        description: String? = null,
        summary: String = "",
        priority: Priority = Priority.MEDIUM
    ): WorkItem =
        when (
            val out =
                rig.ctx.itemCommandService.create(
                    createCommand(title, parent?.id, type, id, tags, summary, priority).copy(description = description)
                )
        ) {
            is Outcome.Ok -> out.value
            is Outcome.Err -> error("seed '$title' failed: ${out.error.code.wire}: ${out.error.message}")
        }
}

internal fun JsonObject.detail(): JsonObject = getValue("detail").jsonObject

/** The whole-call error object of a failed envelope body. */
internal fun JsonObject.wholeError(): JsonObject = getValue("error").jsonObject

internal fun JsonObject.summary(): String = getValue("data").jsonObject.getValue("summary").toString()

internal fun jstr(value: String): JsonElement = JsonPrimitive(value)

/** Asserts a `forbidden` element error with the given scope and `required` detail. */
internal fun assertForbidden(
    error: JsonObject,
    scope: String,
    required: String
) {
    assertEquals("forbidden", error.str("code"), "error: $error")
    assertEquals(scope, error.detail().str("scope"), "error: $error")
    assertEquals(required, error.detail().str("required"), "error: $error")
}
