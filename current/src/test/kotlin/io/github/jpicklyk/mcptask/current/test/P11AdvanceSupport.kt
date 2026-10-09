package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetContextTool
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.sql.DriverManager
import java.util.UUID

/**
 * Shared fixtures for the P11 (item 919d379e) advance tests, authored by the test-author seat. Everything here is
 * test-side: a YAML config (schemas, one exclusive-resource trait, optional status labels), a thin driver over the
 * production-composition [EventLogRig] and raw-JDBC helpers for fault injection.
 */
internal const val P11_LEASE_KEY = "p11-db"

private const val P11_SCHEMAS_YAML =
    "work_item_schemas:\n" +
        "  p11-plain:\n" +
        "    notes:\n" +
        "      - key: spec\n        role: queue\n        required: true\n" +
        "      - key: impl\n        role: work\n        required: true\n" +
        "  p11-review:\n" +
        "    notes:\n" +
        "      - key: spec\n        role: queue\n        required: true\n" +
        "      - key: impl\n        role: work\n        required: true\n" +
        "      - key: verdict\n        role: review\n        required: true\n" +
        "  p11-gated:\n" +
        "    notes:\n" +
        "      - key: spec\n        role: queue\n        required: true\n" +
        "  p11-optional:\n" +
        "    notes:\n" +
        "      - key: extra\n        role: queue\n        required: false\n" +
        "  p11-manual:\n" +
        "    lifecycle: manual\n" +
        "    notes:\n" +
        "      - key: extra\n        role: queue\n        required: false\n" +
        "  p11-permanent:\n" +
        "    lifecycle: permanent\n" +
        "    notes:\n" +
        "      - key: extra\n        role: queue\n        required: false\n" +
        "  p11-leased:\n" +
        "    default_traits: [p11-exclusive]\n" +
        "    notes:\n" +
        "      - key: extra\n        role: queue\n        required: false\n" +
        "  p11-order:\n" +
        "    default_traits: [p11-exclusive]\n" +
        "    notes:\n" +
        "      - key: spec\n        role: queue\n        required: true\n" +
        "  p11-matrix:\n" +
        "    default_traits: [p11-exclusive]\n" +
        "    notes:\n" +
        "      - key: spec\n        role: queue\n        required: true\n" +
        "      - key: impl\n        role: work\n        required: true\n" +
        "      - key: verdict\n        role: review\n        required: true\n" +
        "traits:\n" +
        "  p11-exclusive:\n" +
        "    resources:\n" +
        "      - key: p11-db\n        mode: exclusive\n        ttlSeconds: 600\n"

/** Global config with no status_labels section. */
internal const val P11_BASE_YAML = P11_SCHEMAS_YAML

/** Global config with distinct, test-chosen labels for the triggers the label scenarios assert on. */
internal const val P11_LABELS_YAML =
    P11_SCHEMAS_YAML +
        "status_labels:\n" +
        "  start: p11-start-label\n" +
        "  complete: p11-complete-label\n" +
        "  cancel: p11-cancel-label\n" +
        "  cascade: p11-cascade-label\n"

internal fun JsonObject.text(key: String): String? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

internal fun JsonObject.flag(key: String): Boolean? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.boolean

internal fun JsonObject.arr(key: String): JsonArray = this[key]?.takeIf { it !is JsonNull }?.jsonArray ?: JsonArray(emptyList())

internal fun p11Actor(id: String) =
    buildJsonObject {
        put("id", id)
        put("kind", "subagent")
    }

/** Raw JDBC against the test database file (fault injection and out-of-band fixtures). */
internal fun rawExec(
    jdbcUrl: String,
    vararg sql: String,
) {
    DriverManager.getConnection(jdbcUrl).use { c ->
        c.createStatement().use { st -> sql.forEach { st.execute(it) } }
    }
}

internal fun rawCount(
    jdbcUrl: String,
    sql: String,
): Int =
    DriverManager.getConnection(jdbcUrl).use { c ->
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

internal class P11Driver(
    val rig: EventLogRig,
) {
    val raw get() = rig.raw
    val jdbcUrl get() = rig.db.jdbcUrl

    suspend fun item(
        title: String,
        role: Role = Role.QUEUE,
        type: String? = null,
        parent: WorkItem? = null,
        previousRole: Role? = null,
        statusLabel: String? = null,
        rootId: UUID? = null,
        properties: String? = null,
    ): WorkItem =
        raw.workItemRepository().create(
            WorkItem(
                title = title,
                role = role,
                previousRole = previousRole,
                type = type,
                tags = type,
                parentId = parent?.id,
                depth = (parent?.depth ?: -1) + 1,
                rootId = rootId ?: parent?.let { it.rootId ?: it.id },
                statusLabel = statusLabel,
                properties = properties,
            ),
        )

    suspend fun note(
        item: WorkItem,
        key: String,
        role: String,
    ) {
        raw.noteRepository().upsert(Note(itemId = item.id, key = key, role = role, body = "filled $key"))
    }

    /** [blocker] BLOCKS [blocked] (the blocked item cannot start until the blocker reaches its unblock role). */
    suspend fun blocks(
        blocker: WorkItem,
        blocked: WorkItem,
    ) {
        raw.dependencyRepository().create(Dependency(fromItemId = blocker.id, toItemId = blocked.id, type = DependencyType.BLOCKS))
    }

    suspend fun setRole(
        item: WorkItem,
        role: Role,
    ) {
        raw.workItemRepository().update(raw.workItemRepository().getById(item.id)!!.copy(role = role))
    }

    suspend fun claim(
        item: WorkItem,
        agent: String,
    ) {
        raw.workItemRepository().claim(item.id, agent, ttlSeconds = 900)
    }

    /** Takes the exclusive lease on [P11_LEASE_KEY] for [holder] directly in the store. */
    suspend fun holdLease(holder: WorkItem) {
        raw.resourceLeaseRepository().acquireAll(holder.id, "holder-agent", listOf(P11_LEASE_KEY to 600))
    }

    suspend fun freeLease(holder: WorkItem) {
        raw.resourceLeaseRepository().releaseAllForItem(holder.id)
    }

    suspend fun role(item: WorkItem): Role = raw.workItemRepository().getById(item.id)!!.role

    suspend fun reload(item: WorkItem): WorkItem = raw.workItemRepository().getById(item.id)!!

    suspend fun transitions(item: WorkItem) = raw.roleTransitionRepository().findByItemId(item.id, limit = 500)

    fun advanceParams(
        item: WorkItem,
        trigger: String,
        actor: String? = null,
        requestId: String? = null,
    ): Array<Pair<String, JsonElement>> =
        arrayOf(
            "transitions" to
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemId", item.id.toString())
                            put("trigger", trigger)
                            if (actor != null) put("actor", p11Actor(actor))
                        },
                    )
                },
            *(if (requestId != null) arrayOf("requestId" to JsonPrimitive(requestId)) else emptyArray()),
        )

    /** advance_item for one transition; returns the single result entry. */
    suspend fun advance(
        item: WorkItem,
        trigger: String,
        actor: String? = null,
    ): JsonObject {
        val response = rig.call(AdvanceItemTool(), *advanceParams(item, trigger, actor))
        val data = response["data"]?.jsonObject ?: error("advance_item returned no data: $response")
        return data["results"]!!.jsonArray[0].jsonObject
    }

    companion object {
        val REJECTION_TYPES = setOf("transition.rejected", "lease.rejected")

        fun List<EventRecord>.rejections() = filter { it.type in REJECTION_TYPES }

        fun List<EventRecord>.transitioned() = filter { it.type == "item.transitioned" }
    }
}

internal enum class P11Dep { NONE, UNMET, MET }

/** One generated state of the preview-parity matrix: the item's role plus whether each gate is open or closed. */
internal data class P11MatrixState(
    val role: Role,
    val dep: P11Dep,
    val notesFilled: Boolean,
    val leaseHeld: Boolean,
) {
    /**
     * The gate that should reject `start` first, derived independently from the documented gate order (TABLE, DEPENDENCY,
     * NOTE, LEASE; the lease gate guards WORK entry only, i.e. a start from QUEUE) -- "terminal" for a terminal item (no
     * blockedBy is reported for it) and null when `start` is allowed.
     */
    val expectedBlockedBy: String?
        get() =
            when {
                role == Role.TERMINAL -> "terminal"
                role == Role.BLOCKED -> "table"
                dep == P11Dep.UNMET -> "dependency"
                !notesFilled -> "note"
                leaseHeld && role == Role.QUEUE -> "lease"
                else -> null
            }

    val allowed: Boolean get() = expectedBlockedBy == null

    val label: String get() = "$role/dep=$dep/notes=$notesFilled/lease=$leaseHeld"

    companion object {
        fun all(): List<P11MatrixState> =
            Role.entries.flatMap { role ->
                P11Dep.entries.flatMap { dep ->
                    listOf(true, false).flatMap { notes -> listOf(true, false).map { lease -> P11MatrixState(role, dep, notes, lease) } }
                }
            }
    }
}

/** Builds the item for [state] (type p11-matrix) with its blocker, notes and lease holder; returns the item and the holder. */
internal suspend fun P11Driver.setup(state: P11MatrixState): Pair<WorkItem, WorkItem?> {
    val item =
        item(
            "matrix ${state.label}",
            state.role,
            type = "p11-matrix",
            previousRole = if (state.role == Role.BLOCKED) Role.WORK else null,
        )
    when (state.dep) {
        P11Dep.NONE -> Unit
        P11Dep.UNMET -> blocks(item("unmet blocker", Role.QUEUE), item)
        P11Dep.MET -> blocks(item("met blocker", Role.TERMINAL), item)
    }
    val phaseKey = mapOf(Role.QUEUE to "spec", Role.WORK to "impl", Role.REVIEW to "verdict")[state.role]
    if (state.notesFilled && phaseKey != null) note(item, phaseKey, state.role.name.lowercase())
    var holder: WorkItem? = null
    if (state.leaseHeld) {
        holder = item("lease holder", Role.QUEUE)
        holdLease(holder)
    }
    return item to holder
}

/** Releases whatever [setup] and the advance under test left behind, so the next state starts with a free lease. */
internal suspend fun P11Driver.cleanup(
    item: WorkItem,
    holder: WorkItem?,
) {
    freeLease(item)
    if (holder != null) freeLease(holder)
}

// ---------------------------------------------------------------------------------------------------------------------
// Config-fault fixtures (r1, H1): a per-root config is pushed and warmed, then the project_config table is renamed away so
// every later config read raises a real store fault.
// ---------------------------------------------------------------------------------------------------------------------

private const val P11_ROOT_YAML =
    "work_item_schemas:\n" +
        "  p11-gated:\n" +
        "    notes:\n" +
        "      - key: spec\n        role: queue\n        required: true\n"

/** A rooted item whose only start gate (the queue note `spec`) is open, under a pushed and warmed per-root config. */
internal data class P11ConfigFixture(
    val root: WorkItem,
    val item: WorkItem,
)

/** What an advance must leave untouched when it fails closed: role, label, previous role, role-change instant, rows, events. */
internal data class P11Snapshot(
    val role: Role,
    val previousRole: Role?,
    val statusLabel: String?,
    val roleChangedAt: java.time.Instant?,
    val transitionRows: Int,
    val eventRows: Int,
)

internal suspend fun P11Driver.snapshot(item: WorkItem): P11Snapshot {
    val current = reload(item)
    return P11Snapshot(
        role = current.role,
        previousRole = current.previousRole,
        statusLabel = current.statusLabel,
        roleChangedAt = current.roleChangedAt,
        transitionRows = transitions(item).size,
        eventRows = rig.rows().size,
    )
}

/**
 * Creates root + rooted item (type p11-gated, `spec` filled so a healthy `start` is allowed), pushes the per-root config and
 * warms the shared config cache through a successful get_context (asserted: canAdvance is true, so the fixture is healthy).
 */
internal suspend fun P11Driver.configFaultFixture(): P11ConfigFixture {
    val root = item("config root", Role.WORK)
    val child = item("config item", Role.QUEUE, type = "p11-gated", parent = root)
    note(child, "spec", "queue")
    raw.projectConfigRepository().upsert(root.id, P11_ROOT_YAML)
    val warm = rig.callOk(GetContextTool(), "itemId" to JsonPrimitive(child.id.toString()))
    val gate = warm["data"]!!.jsonObject["gateStatus"]!!.jsonObject
    check(gate.flag("canAdvance") == true) { "fixture: a healthy warm read must report canAdvance=true: $gate" }
    return P11ConfigFixture(root, child)
}

/** Every later per-root config read now fails with a real SQL error (the cache stays warm). */
internal fun P11Driver.breakConfigReads() {
    rawExec(jdbcUrl, "ALTER TABLE project_config RENAME TO project_config_gone")
}
