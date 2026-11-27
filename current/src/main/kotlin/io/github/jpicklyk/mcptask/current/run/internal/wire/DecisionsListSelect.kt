package io.github.jpicklyk.mcptask.current.run.internal.wire

import io.github.jpicklyk.mcptask.current.lifecycle.AdvanceSupport
import io.github.jpicklyk.mcptask.current.model.config.PerRootConfigInvalidException
import io.github.jpicklyk.mcptask.current.model.config.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.FieldViolation
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequest
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.call.ScopedIds
import io.github.jpicklyk.mcptask.current.platform.port.ItemScopeReads
import io.github.jpicklyk.mcptask.current.platform.port.ListPage
import io.github.jpicklyk.mcptask.current.platform.port.PageRequest
import io.github.jpicklyk.mcptask.current.platform.support.IdRef
import io.github.jpicklyk.mcptask.current.platform.support.ScopeGuard
import io.github.jpicklyk.mcptask.current.platform.support.eventRootOf
import io.github.jpicklyk.mcptask.current.platform.support.toDomainError
import io.github.jpicklyk.mcptask.current.platform.tool.PageWith
import io.github.jpicklyk.mcptask.current.platform.tool.SelectSpec
import io.github.jpicklyk.mcptask.current.platform.tool.director
import io.github.jpicklyk.mcptask.current.platform.tool.invalidFields
import io.github.jpicklyk.mcptask.current.platform.tool.orchestration
import io.github.jpicklyk.mcptask.current.run.RunToolServices
import io.github.jpicklyk.mcptask.current.run.internal.app.DecisionsListRequest
import io.github.jpicklyk.mcptask.current.run.internal.app.RunsSupport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import java.util.UUID

/** The most ids `decisionIds` takes. */
private const val MAX_DECISION_IDS = 50

private val FULL_UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** `decisions_read list`: the decision inbox, newest first, with the decision classes in force on the roots it shows. */
internal val decisionsListSelect =
    SelectSpec.PagedWith<RunToolServices, DecisionsListRequest, JsonObject>(
        name = "list",
        doc =
            "List decisions, newest first (raisedAt descending, then id), each in full (the context only with " +
                "includeContext). Filters combine: itemId, ancestorId (the item, its descendants, and the item-less " +
                "decisions of it as a root), runId, rootId, decisionIds (read specific decisions), status (absent: any), " +
                "waitingOn, class, severity (the effective one, one step up while overdue) and overdue. data.classes maps " +
                "each root the page shows, and the root a filter names, to its decision classes: answerers, severity, " +
                "expiresAfterSeconds, freeform, serverRaised and autonomy (answer or ask). Decisions on roots outside the call scope are left out.",
        profiles = orchestration() + director(),
        services = RunToolServices::class,
        request = serializer<DecisionsListRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        handler = { env, ctx, req -> listDecisions(env, ctx, req) },
        item = { it }
    )

private suspend fun listDecisions(
    env: RunToolServices,
    ctx: CallContext,
    req: DecisionsListRequest
): Outcome<PageWith<JsonObject>> {
    val page =
        when (val p = PageRequest.from(req.limit, req.cursor)) {
            is Outcome.Ok -> p.value
            is Outcome.Err -> return p
        }
    val ids =
        when (val r = decisionIds(req.decisionIds)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val repo = env.stores.workItemRepository()
    val rootId: UUID? =
        when (val r = resolveItem(repo, ctx, req.rootId, "/rootId")) {
            null -> null
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val itemId: UUID? =
        when (val r = resolveItem(repo, ctx, req.itemId, "/itemId")) {
            null -> null
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val ancestorId: UUID? =
        when (val r = resolveItem(repo, ctx, req.ancestorId, "/ancestorId")) {
            null -> null
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    return env.unitOfWork.read {
        val run =
            req.runId?.let { ref ->
                when (val r = RunsSupport.loadRun(stores, ctx, ref)) {
                    is Outcome.Ok -> r.value
                    is Outcome.Err -> return@read r
                }
            }
        val now = env.clock.now()
        val hierarchy = stores.hierarchyStore()

        // The subtree of ancestorId and its root; a decision about any of these, or an item-less one on that root, matches.
        val ancestorRoot = ancestorId?.let { id -> repo.getById(id)?.let { eventRootOf(it, hierarchy) } ?: id }
        val subtree = ancestorId?.let { hierarchy.descendantIds(it) + it }
        val itemRoot = itemId?.let { id -> repo.getById(id)?.let { eventRootOf(it, hierarchy) } ?: id }

        val rootSet: Set<UUID>? =
            listOfNotNull(rootId, ancestorRoot).takeIf { it.isNotEmpty() }?.let { named ->
                if (named.toSet().size > 1) emptySet() else setOf(named.first())
            }
        val statuses = req.status?.mapTo(HashSet()) { it.domain }
        val store = stores.decisionRequestStore()
        val found: List<DecisionRequest> =
            if (ids != null) {
                store
                    .findByIds(ids.toSet())
                    .filter { d ->
                        (rootSet == null || d.rootId in rootSet) &&
                            (run == null || d.runId == run.id) &&
                            (itemId == null || d.itemId == itemId) &&
                            (statuses == null || d.status in statuses)
                    }.sortedWith(compareByDescending<DecisionRequest> { it.raisedAt }.thenBy { it.id })
            } else {
                store.list(rootSet, run?.id, itemId, statuses)
            }
        val admitted = ScopeGuard.admitted(ctx.scope, found.mapTo(HashSet()) { it.rootId }, repo)
        val rows =
            found.filter { d ->
                d.rootId in admitted &&
                    (subtree == null || (d.itemId?.let { it in subtree } ?: (d.rootId == ancestorId))) &&
                    (req.waitingOn == null || d.waitingOn == req.waitingOn.domain) &&
                    (req.`class` == null || d.decisionClass == req.`class`) &&
                    (req.severity == null || d.effectiveSeverity(now) == req.severity.domain) &&
                    (req.overdue != true || d.isOverdue(now))
            }
        val shown = rows.drop(page.offset).take(page.limit)
        val items = shown.map { DecisionView.full(it, now, ctx.principalsVisible, context = req.includeContext == true) }

        val classRoots = LinkedHashSet<UUID>()
        shown.mapTo(classRoots) { it.rootId }
        classRoots.addAll(listOfNotNull(rootId, itemRoot, ancestorRoot, run?.rootId))
        val classes =
            try {
                classesOf(env, classRoots)
            } catch (e: PerRootConfigUnavailableException) {
                return@read Outcome.Err(AdvanceSupport.configUnavailable(e.message))
            } catch (e: PerRootConfigInvalidException) {
                return@read Outcome.Err(e.toDomainError())
            }
        Outcome.Ok(PageWith(ListPage.of(items, rows.size, page), buildJsonObject { put("classes", classes) }))
    }
}

/** The decision classes of each root in [roots]: `{rootId: {name: {answerers, severity, expiresAfterSeconds?, freeform, serverRaised, autonomy}}}`. */
private suspend fun classesOf(
    env: RunToolServices,
    roots: Set<UUID>
): JsonObject =
    buildJsonObject {
        for (root in roots) {
            put(
                root.toString(),
                buildJsonObject {
                    for ((name, cls) in env.decisionService.classes(root)) {
                        put(
                            name,
                            buildJsonObject {
                                put("answerers", cls.answerers.wire)
                                put("severity", cls.severity.wire)
                                cls.expiresAfterSeconds?.let { put("expiresAfterSeconds", it) }
                                put("freeform", cls.freeform)
                                put("serverRaised", cls.serverRaised)
                                put("autonomy", cls.autonomy.wire)
                            }
                        )
                    }
                }
            )
        }
    }

/** Resolves an optional item [ref] under the call scope (forbidden before not_found), or null when it is absent. */
private suspend fun resolveItem(
    repo: ItemScopeReads,
    ctx: CallContext,
    ref: IdRef?,
    field: String
): Outcome<UUID>? = ref?.let { ScopedIds.resolveTarget(repo, ctx.scope, it, field) }

/** The `decisionIds` filter as UUIDs: at most [MAX_DECISION_IDS], each a full UUID; null when absent. */
private fun decisionIds(raw: List<String>?): Outcome<List<UUID>?> {
    if (raw == null) return Outcome.Ok(null)
    if (raw.size > MAX_DECISION_IDS) {
        return Outcome.Err(RunsSupport.invalid("/decisionIds", "takes at most $MAX_DECISION_IDS ids", raw.size.toString()))
    }
    val faults = ArrayList<FieldViolation>()
    val out = ArrayList<UUID>(raw.size)
    raw.forEachIndexed { i, text ->
        if (FULL_UUID.matches(text)) {
            out += UUID.fromString(text)
        } else {
            faults += FieldViolation("/decisionIds/$i", "must be a full UUID", text)
        }
    }
    return if (faults.isEmpty()) Outcome.Ok(out) else Outcome.Err(invalidFields(faults))
}
