package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.mcp.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.make
import io.github.jpicklyk.mcptask.current.parity.ParityHarness
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.callMcp
import io.github.jpicklyk.mcptask.current.parity.parityAccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/** The tool under test (task-scope Contract: one OpsToolSpec named `config`). */
internal const val CFG = "config"

internal val CFG_ACTOR: JsonObject =
    buildJsonObject {
        put("id", "agent-cfg")
        put("kind", "subagent")
    }

/**
 * Fixture documents. Every revision is a distinct, valid ROOT-scope document (a gated type with one required note whose key
 * carries the revision), so two revisions never share a fingerprint.
 */
internal object CfgYaml {
    fun rev(n: Int): String =
        "work_item_schemas:\n  feature-task:\n    notes:\n      - key: spec-$n\n        role: queue\n        required: true\n"

    /** A document that is rejected at `/orchestration` (a plugin key at the top level is not admitted). */
    val INVALID: String = "orchestration:\n  mode: workflow\n" + rev(1)

    /** A seat-aware schema with a required note no seat owns: one advisory warning, no error. */
    val WITH_WARNING: String =
        rev(1) + "  seated:\n    seats:\n      - { name: impl, phase: work, enters: true }\n" +
            "    notes:\n      - key: unowned\n        role: work\n        required: true\n"

    /** Head and proposal of the diff scenario (names asserted by the validate oracle in ConfigServiceValidateTest). */
    val DIFF_HEAD: String =
        "work_item_schemas:\n" +
            "  feature-task:\n    notes:\n      - key: spec\n        role: queue\n        required: true\n" +
            "  bug-fix:\n    notes:\n      - key: diagnosis\n        role: queue\n        required: true\n" +
            "traits:\n" +
            "  needs-review:\n    notes:\n      - key: review-note\n        role: review\n        required: false\n" +
            "  needs-docs:\n    notes:\n      - key: docs-note\n        role: work\n        required: false\n"

    val DIFF_PROPOSED: String =
        "work_item_schemas:\n" +
            "  feature-task:\n    notes:\n      - key: spec\n        role: queue\n        required: false\n" +
            "  bug-fix:\n    notes:\n      - key: diagnosis\n        role: queue\n        required: true\n" +
            "  chore:\n    notes: []\n" +
            "traits:\n" +
            "  needs-review:\n    notes:\n      - key: review-note\n        role: review\n        required: false\n"

    /** [rev] 1 padded to exactly [bytes] UTF-8 bytes (ASCII padding in a trailing comment). */
    fun sized(bytes: Int): String {
        val base = rev(1)
        val pad = bytes - base.length - 1
        require(pad >= 0) { "target size $bytes is smaller than the base document" }
        return base + "#" + "x".repeat(pad)
    }
}

/** A JSON string primitive for a root / item reference given as a [UUID] or any other spelling. */
internal fun ref(value: Any): JsonElement = JsonPrimitive(value.toString())

internal fun cop(
    op: String,
    vararg fields: Pair<String, JsonElement>
): JsonObject =
    buildJsonObject {
        put("op", op)
        fields.forEach { (k, v) -> put(k, v) }
    }

internal fun getOp(
    root: Any,
    vararg extra: Pair<String, JsonElement>
) = cop("get", "rootId" to ref(root), *extra)

internal fun historyOp(
    root: Any,
    vararg extra: Pair<String, JsonElement>
) = cop("history", "rootId" to ref(root), *extra)

internal fun pushOp(
    root: Any,
    yaml: String,
    vararg extra: Pair<String, JsonElement>
) = cop("push", "rootId" to ref(root), "configYaml" to JsonPrimitive(yaml), *extra)

internal fun validateOp(
    root: Any,
    yaml: String,
    vararg extra: Pair<String, JsonElement>
) = cop("validate", "rootId" to ref(root), "configYaml" to JsonPrimitive(yaml), *extra)

internal fun rollbackOp(
    root: Any,
    version: Int,
    vararg extra: Pair<String, JsonElement>
) = cop("rollback", "rootId" to ref(root), "version" to JsonPrimitive(version), *extra)

internal fun resetOp(
    root: Any,
    vararg extra: Pair<String, JsonElement>
) = cop("reset", "rootId" to ref(root), *extra)

internal fun effectiveOp(root: Any? = null) = if (root == null) cop("effective") else cop("effective", "rootId" to ref(root))

internal fun rulesOp(vararg fields: Pair<String, JsonElement>) = cop("rules", *fields)

internal fun num(value: Int): JsonElement = JsonPrimitive(value)

internal fun flag(value: Boolean): JsonElement = JsonPrimitive(value)

internal fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

internal fun JsonObject.bool(name: String): Boolean = getValue(name).jsonPrimitive.boolean

internal fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject

internal fun JsonObject.list(name: String): List<JsonObject> = getValue(name).jsonArray.map { it.jsonObject }

internal fun JsonObject.names(name: String): List<String> = getValue(name).jsonArray.map { it.jsonPrimitive.content }

/** The `field` pointers of an `invalid_request` element error. */
internal fun JsonObject.fieldPointers(): List<String> =
    getValue("detail").jsonObject.getValue("fields").jsonArray.map {
        it.jsonObject
            .getValue("field")
            .jsonPrimitive.content
    }

/** The `{path, reason}` pairs of a `config_invalid` error. */
internal fun JsonObject.violations(): List<Pair<String, String>> =
    getValue("detail").jsonObject.getValue("errors").jsonArray.map {
        val o = it.jsonObject
        o.getValue("path").jsonPrimitive.content to o.getValue("reason").jsonPrimitive.content
    }

/** Thin plumbing over the real composition; no assertion logic lives here. */
internal class CfgKit(
    val rig: EventLogRig
) {
    private val registry = ToolRegistry(buildToolSpecs())
    private val spec = checkNotNull(registry[CFG]) { "$CFG is not registered" }

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

    /** Unscoped dispatch over the real dispatcher in the orchestration profile (the MCP default path); the envelope body. */
    suspend fun call(
        vararg ops: JsonObject,
        atomic: Boolean = false,
        actor: JsonObject? = null
    ): JsonObject =
        buildToolDispatcher(rig.ctx)
            .dispatch(spec, ToolProfile.ORCHESTRATION, args(ops, atomic, actor), "k7f3q9ab", "mcp")
            .envelope.body

    /** A real MCP client/server call under the token's principal, scope and capabilities; the envelope body. */
    suspend fun callScoped(
        token: ParityToken,
        vararg ops: JsonObject,
        atomic: Boolean = false
    ): JsonObject = callMcp(rig.ctx, registry, { parityAccess(token) }, CFG, args(ops, atomic, null)).structured

    /** Seeds a depth-0 item through the undecorated repository (never through the tool under test). */
    suspend fun root(
        title: String,
        id: UUID = UUID.randomUUID(),
        tags: String? = null,
        type: String = "project"
    ): WorkItem = rig.make(title, id = id, tags = tags, type = type)

    /** Seeds a child of [parent]; depth and root derive from the parent. */
    suspend fun child(
        title: String,
        parent: WorkItem,
        id: UUID = UUID.randomUUID(),
        type: String = "test-item"
    ): WorkItem = rig.make(title, parent = parent, id = id, type = type)

    suspend fun versions(root: UUID): Int =
        rig.raw
            .configStore()
            .history(ConfigScope.ROOT, root, 200)
            .size

    suspend fun configEvents(): List<EventRecord> = rig.rows().filter { it.type == "config.version_created" }

    /** The comparable database content (work_items, notes, dependencies, events, config_versions, config_heads). */
    fun snapshot(): Map<String, List<Map<String, Any?>>> = ParityHarness.snapshot(rig.db.jdbcUrl)
}
