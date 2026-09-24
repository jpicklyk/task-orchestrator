package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.computePhaseNoteContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.toJsonString
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.filterByTagScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.hasTagScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.DependenciesDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.GateStatusDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ItemDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ItemGateDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.PageDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.etag.respondWithEtagCheck
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.buildDependenciesDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.toDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination.PageParams
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination.buildPageDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination.pageParamsOrRespond
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.redaction.AttributionRedactor
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

private val logger = LoggerFactory.getLogger("ItemRoutes")

/**
 * Candidate-row cap for the list endpoints that must post-filter by `tags_include`.
 *
 * `tags_include` has no SQL representation, so a tag-scoped list request fetches a wider
 * candidate window, drops the out-of-scope rows in memory and paginates the survivors — the
 * same shape `GET /items/{id}/tree` already uses. Paginating in SQL first would hand the
 * caller short (or empty) pages whose `totalItems` counted rows they may not see. Only
 * tag-scoped principals take this path; every other caller keeps SQL-level pagination.
 */
private const val TAG_SCOPE_SCAN_LIMIT = 1000

/**
 * One page of an already scope-filtered, in-memory [visible] set: the page is sliced from the
 * survivors and totalItems is their count, so paging never counts an item the caller may not read.
 */
private fun pageOfVisible(
    visible: List<WorkItem>,
    pp: PageParams,
    skipped: Int? = null,
): PageDto<ItemDto> = buildPageDto(visible.drop(pp.offset).take(pp.pageSize).map { it.toDto() }, pp, visible.size.toLong(), skipped)

/**
 * Registers item-read routes under the `/api/v1` route prefix.
 *
 * Endpoints:
 * - `GET /items`                    — paginated list with query-param filters
 * - `GET /items/roots`              — root-level items in caller's scope
 * - `GET /items/{id}`               — single item; `?include=notes,deps,children` to inline
 * - `GET /items/{id}/tree`          — descendant tree
 * - `GET /items/{id}/breadcrumbs`   — ancestor chain root→item
 * - `GET /items/{id}/children`      — direct children only (paginated)
 *
 * All endpoints require [ApiCapability.READ]. `root_ids` scope filtering is applied at the SQL
 * level via `findInScope` / `countInScope` when the principal has a non-null `rootIds` set.
 *
 * `tags_include` scope has no SQL representation and is applied in memory by the shared
 * [filterByTagScope] predicate to EVERY item a response carries — list rows, root rows,
 * breadcrumb ancestors, tree descendants, direct children and `?include=children` inlines —
 * not just the one item [enforceScopeForItem] authorizes at the entry point. Where the filter
 * runs on a list, the list is filtered before it is paginated, so `totalItems` counts only
 * items the caller may read (see [TAG_SCOPE_SCAN_LIMIT]).
 */
fun Route.itemRoutes(repositoryProvider: RepositoryProvider) {
    val workItemRepo = repositoryProvider.workItemRepository()
    val noteRepo = repositoryProvider.noteRepository()
    val depRepo = repositoryProvider.dependencyRepository()
    val redactor = AttributionRedactor.fromEnv()

    requireCapability(ApiCapability.READ) {
        // ─── GET /items ─────────────────────────────────────────────────────
        get("/items") {
            val principal = call.attributes.getOrNull(ApiPrincipalKey)
            val pp = call.pageParamsOrRespond() ?: return@get
            val params = call.request.queryParameters

            val role =
                params["role"]?.let { r ->
                    Role.entries.find { it.name.equals(r, ignoreCase = true) }
                }
            val priority =
                params["priority"]?.let { p ->
                    Priority.entries.find { it.name.equals(p, ignoreCase = true) }
                }
            val tags = params["tag"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            val tagAny = params["tagAny"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            val effectiveTags = tagAny ?: tags
            val type = params["type"]?.takeIf { it.isNotBlank() }
            val parentId = params["parentId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val rootIdFilter = params["rootId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val modifiedAfter = params["modifiedAfter"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val modifiedBefore = params["modifiedBefore"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val createdAfter = params["createdAfter"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val createdBefore = params["createdBefore"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val claimStatus = params["claimStatus"]?.takeIf { it.isNotBlank() }
            val orderBy = params["orderBy"]?.takeIf { it.isNotBlank() }
            val orderDir = params["orderDir"]?.takeIf { it.isNotBlank() }

            val scopeRootIds = principal?.scope?.rootIds

            // Merge rootId query param with principal scope
            val effectiveScopeRootIds: Set<UUID>? =
                when {
                    scopeRootIds != null && rootIdFilter != null ->
                        scopeRootIds.intersect(setOf(rootIdFilter)).takeIf { it.isNotEmpty() }
                            ?: emptySet()
                    scopeRootIds != null -> scopeRootIds
                    rootIdFilter != null -> setOf(rootIdFilter)
                    else -> null
                }

            // Populated only by the unscoped findByFilters branch below — findInScope has no
            // ItemFetchResult/skipped-row tracking, so scoped requests always report null.
            var skippedCount: Int? = null

            // tags_include cannot be expressed in SQL, so a tag-scoped caller reads a wider
            // candidate window and is paginated in memory after the filter (see
            // TAG_SCOPE_SCAN_LIMIT). Callers without a tag scope keep SQL LIMIT/OFFSET exactly
            // as before.
            val tagScoped = principal.hasTagScope()
            val fetchLimit = if (tagScoped) TAG_SCOPE_SCAN_LIMIT else pp.pageSize
            val fetchOffset = if (tagScoped) 0 else pp.offset

            val items =
                if (effectiveScopeRootIds != null) {
                    if (effectiveScopeRootIds.isEmpty()) {
                        val total = 0L
                        call.respond(HttpStatusCode.OK, buildPageDto(emptyList<ItemDto>(), pp, total))
                        return@get
                    }
                    workItemRepo.findInScope(
                        rootIds = effectiveScopeRootIds,
                        parentId = parentId,
                        role = role,
                        priority = priority,
                        tags = effectiveTags,
                        createdAfter = createdAfter,
                        createdBefore = createdBefore,
                        modifiedAfter = modifiedAfter,
                        modifiedBefore = modifiedBefore,
                        sortBy = orderBy,
                        sortOrder = orderDir,
                        limit = fetchLimit,
                        offset = fetchOffset,
                        type = type,
                        claimStatus = claimStatus,
                    )
                } else {
                    // Unwrap ItemFetchResult to List<WorkItem> here so both branches of this
                    // if/else agree on Result<List<WorkItem>>; skippedCount above carries the
                    // dropped-row count forward for the response DTO.
                    when (
                        val r =
                            workItemRepo.findByFilters(
                                parentId = parentId,
                                role = role,
                                priority = priority,
                                tags = effectiveTags,
                                createdAfter = createdAfter,
                                createdBefore = createdBefore,
                                modifiedAfter = modifiedAfter,
                                modifiedBefore = modifiedBefore,
                                sortBy = orderBy,
                                sortOrder = orderDir,
                                limit = fetchLimit,
                                offset = fetchOffset,
                                type = type,
                                claimStatus = claimStatus,
                            )
                    ) {
                        is Result.Success -> {
                            skippedCount = r.data.skipped
                            Result.Success(r.data.items)
                        }
                        is Result.Error -> r
                    }
                }

            when (items) {
                is Result.Error -> {
                    logger.warn("GET /items DB error: {}", items.error.message)
                    call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Database query failed"))
                }
                is Result.Success -> {
                    if (tagScoped) {
                        // Filter first, paginate second: the surviving rows ARE the caller's
                        // universe, so totalItems is their count — never a DB count that
                        // includes items this token may not read.
                        call.respond(HttpStatusCode.OK, pageOfVisible(items.data.filterByTagScope(principal), pp, skippedCount))
                        return@get
                    }
                    val total =
                        if (effectiveScopeRootIds != null) {
                            workItemRepo
                                .countInScope(
                                    rootIds = effectiveScopeRootIds,
                                    parentId = parentId,
                                    role = role,
                                    priority = priority,
                                    tags = effectiveTags,
                                    createdAfter = createdAfter,
                                    createdBefore = createdBefore,
                                    modifiedAfter = modifiedAfter,
                                    modifiedBefore = modifiedBefore,
                                    type = type,
                                    claimStatus = claimStatus,
                                ).let { r -> if (r is Result.Success) r.data.toLong() else null }
                        } else {
                            workItemRepo
                                .countByFilters(
                                    parentId = parentId,
                                    role = role,
                                    priority = priority,
                                    tags = effectiveTags,
                                    createdAfter = createdAfter,
                                    createdBefore = createdBefore,
                                    modifiedAfter = modifiedAfter,
                                    modifiedBefore = modifiedBefore,
                                    type = type,
                                    claimStatus = claimStatus,
                                ).let { r -> if (r is Result.Success) r.data.toLong() else null }
                        }
                    val dtos = items.data.map { it.toDto() }
                    call.respond(HttpStatusCode.OK, buildPageDto(dtos, pp, total, skippedCount))
                }
            }
        }

        // ─── GET /items/roots ────────────────────────────────────────────────
        get("/items/roots") {
            val principal = call.attributes.getOrNull(ApiPrincipalKey)
            val pp = call.pageParamsOrRespond() ?: return@get
            val scopeRootIds = principal?.scope?.rootIds

            if (scopeRootIds != null) {
                // Scoped token: fetch only the specific root items the principal can see. The
                // token's scope set is the authoritative bound on visible roots, so `roots.size`
                // here is already the TRUE total for this principal — no separate count call or
                // 200-style cap applies to this branch.
                val roots =
                    scopeRootIds
                        .mapNotNull { rid ->
                            val r = workItemRepo.getById(rid)
                            if (r is Result.Success && r.data.parentId == null) r.data else null
                        }.filterByTagScope(principal)
                call.respond(HttpStatusCode.OK, pageOfVisible(roots, pp))
            } else if (principal.hasTagScope()) {
                // Unrestricted rootIds but a tag allowlist: read a bounded candidate window,
                // drop out-of-scope roots, then paginate the survivors (see TAG_SCOPE_SCAN_LIMIT).
                val result = workItemRepo.findRootItems(limit = TAG_SCOPE_SCAN_LIMIT, offset = 0)
                when (result) {
                    is Result.Error -> {
                        logger.warn("GET /items/roots DB error: {}", result.error.message)
                        call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Database query failed"))
                    }
                    is Result.Success -> {
                        call.respond(
                            HttpStatusCode.OK,
                            pageOfVisible(result.data.items.filterByTagScope(principal), pp, result.data.skipped),
                        )
                    }
                }
            } else {
                // Unscoped/admin: true total from countRootItems() (unaffected by limit/offset or
                // validation drops) and real limit/offset pagination — replaces the old silent
                // 200-row cap so callers can page through every root.
                val totalResult = workItemRepo.countRootItems()
                val total =
                    when (totalResult) {
                        is Result.Error -> {
                            logger.warn("GET /items/roots DB error (count): {}", totalResult.error.message)
                            call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Database query failed"))
                            return@get
                        }
                        is Result.Success -> totalResult.data
                    }

                val result = workItemRepo.findRootItems(limit = pp.pageSize, offset = pp.offset)
                when (result) {
                    is Result.Error -> {
                        logger.warn("GET /items/roots DB error: {}", result.error.message)
                        call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Database query failed"))
                    }
                    is Result.Success -> {
                        val dtos = result.data.items.map { it.toDto() }
                        call.respond(HttpStatusCode.OK, buildPageDto(dtos, pp, total, result.data.skipped))
                    }
                }
            }
        }

        // ─── GET /items/{id} ─────────────────────────────────────────────────
        get("/items/{id}") {
            val rawId =
                call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing item id"))
                    return@get
                }
            val id =
                runCatching { UUID.fromString(rawId) }.getOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Invalid UUID: $rawId"))
                    return@get
                }

            val itemResult = workItemRepo.getById(id)
            if (itemResult is Result.Error) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Item $id not found"))
                return@get
            }
            val item = (itemResult as Result.Success).data

            if (!enforceScopeForItem(call, id, workItemRepo)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for item $id"))
                return@get
            }

            if (call.respondWithEtagCheck(item.modifiedAt)) return@get

            val includes =
                call.request.queryParameters["include"]
                    ?.split(",")
                    ?.map { it.trim() } ?: emptyList()

            val noteDtos =
                if ("notes" in includes) {
                    noteRepo.findByItemId(id).let { r ->
                        if (r is Result.Success) r.data.map { n -> redactor.redact(n.toDto(), call) } else null
                    }
                } else {
                    null
                }

            val depDtos: DependenciesDto? =
                if ("deps" in includes) {
                    val deps = depRepo.findByItemId(id)
                    buildDependenciesDto(id.toString(), deps)
                } else {
                    null
                }

            // Inlined children get the same tag filter as GET /items/{id}/children — the
            // entry-point check above only authorized the parent.
            val principal = call.attributes.getOrNull(ApiPrincipalKey)
            val childrenDtos: List<ItemDto>? =
                if ("children" in includes) {
                    workItemRepo.findChildren(id).let { r ->
                        if (r is Result.Success) r.data.filterByTagScope(principal).map { it.toDto() } else null
                    }
                } else {
                    null
                }

            call.respond(
                HttpStatusCode.OK,
                item.toDto(
                    includeNotes = noteDtos,
                    includeChildren = childrenDtos,
                    includeDependencies = depDtos,
                )
            )
        }

        // ─── GET /items/{id}/tree ─────────────────────────────────────────────
        get("/items/{id}/tree") {
            val rawId =
                call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing item id"))
                    return@get
                }
            val id =
                runCatching { UUID.fromString(rawId) }.getOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Invalid UUID: $rawId"))
                    return@get
                }

            val itemResult = workItemRepo.getById(id)
            if (itemResult is Result.Error) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Item $id not found"))
                return@get
            }
            val root = (itemResult as Result.Success).data

            if (!enforceScopeForItem(call, id, workItemRepo)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for item $id"))
                return@get
            }

            val pp = call.pageParamsOrRespond() ?: return@get
            val maxDepth = call.request.queryParameters["depth"]?.toIntOrNull()

            val descendantsResult = workItemRepo.findDescendants(id)
            val descendants =
                when (descendantsResult) {
                    is Result.Error -> {
                        logger.warn("GET /items/{}/tree DB error: {}", id, descendantsResult.error.message)
                        call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Database query failed"))
                        return@get
                    }
                    is Result.Success -> descendantsResult.data
                }

            // Apply depth filter if requested
            val relativeMaxDepth = if (maxDepth != null) root.depth + maxDepth else null
            val depthFiltered =
                if (relativeMaxDepth != null) {
                    descendants.filter { it.depth <= relativeMaxDepth }
                } else {
                    descendants
                }

            // Post-filter by tagsInclude when the principal's scope carries a tag allowlist.
            // The entry-point check (enforceScopeForItem above) verified the ROOT item matches
            // the tag constraint. Descendants are NOT checked by enforceScopeForItem, so without
            // this filter a tag-scoped token would see ALL descendants regardless of tags.
            val principal = call.attributes.getOrNull(ApiPrincipalKey)
            val filtered = depthFiltered.filterByTagScope(principal)

            // Paginate the flat list
            val page = filtered.drop(pp.offset).take(pp.pageSize)
            val dtos = (listOf(root) + page).map { it.toDto() }
            call.respond(HttpStatusCode.OK, buildPageDto(dtos, pp, (filtered.size + 1).toLong()))
        }

        // ─── GET /items/{id}/breadcrumbs ────────────────────────────────────
        get("/items/{id}/breadcrumbs") {
            val principal = call.attributes.getOrNull(ApiPrincipalKey)
            val rawId =
                call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing item id"))
                    return@get
                }
            val id =
                runCatching { UUID.fromString(rawId) }.getOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Invalid UUID: $rawId"))
                    return@get
                }

            val itemResult = workItemRepo.getById(id)
            if (itemResult is Result.Error) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Item $id not found"))
                return@get
            }
            val item = (itemResult as Result.Success).data

            if (!enforceScopeForItem(call, id, workItemRepo)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for item $id"))
                return@get
            }

            val chainResult = workItemRepo.findAncestorChains(setOf(id))
            val ancestors =
                when (chainResult) {
                    is Result.Error -> {
                        logger.warn("GET /items/{}/breadcrumbs DB error: {}", id, chainResult.error.message)
                        call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Database query failed"))
                        return@get
                    }
                    is Result.Success -> chainResult.data[id] ?: emptyList()
                }

            // ancestors is root-first (excludes item itself), so append item at end
            val chain = ancestors + item

            // Scope truncation: if the principal has rootIds, trim the chain to start at
            // the first ancestor that is within the scope. This prevents exposing ancestors
            // that are above the caller's scope root.
            // If the scope root does not appear in the chain (e.g., scoped to a non-root mid-
            // tree item that isn't a direct ancestor), fall back to showing only the target item.
            val scopeRootIds = principal?.scope?.rootIds
            val visible =
                if (scopeRootIds != null) {
                    val idx = chain.indexOfFirst { it.id in scopeRootIds }
                    if (idx >= 0) chain.drop(idx) else listOf(item)
                } else {
                    chain
                }

            // Tag scope also applies to the ancestors: enforceScopeForItem only authorized the
            // target item, so without this an out-of-scope ancestor's title/type/role would be
            // handed to a tag-scoped caller. The target itself always survives — it passed the
            // same predicate at the entry-point check above.
            call.respond(HttpStatusCode.OK, visible.filterByTagScope(principal).map { it.toDto() })
        }

        // ─── GET /items/{id}/children ────────────────────────────────────────
        get("/items/{id}/children") {
            val rawId =
                call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing item id"))
                    return@get
                }
            val id =
                runCatching { UUID.fromString(rawId) }.getOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Invalid UUID: $rawId"))
                    return@get
                }

            val itemResult = workItemRepo.getById(id)
            if (itemResult is Result.Error) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Item $id not found"))
                return@get
            }

            if (!enforceScopeForItem(call, id, workItemRepo)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for item $id"))
                return@get
            }

            val pp = call.pageParamsOrRespond() ?: return@get
            val principalForChildren = call.attributes.getOrNull(ApiPrincipalKey)

            // tags_include has no SQL representation, so a tag-scoped caller reads a wider
            // candidate window (TAG_SCOPE_SCAN_LIMIT, same bound GET /items uses), filters it in
            // memory, and is paginated from the SURVIVING set — filter before paging, matching the
            // file KDoc above. countByFilters would count children the token cannot see, so it is
            // not used on this branch; totalItems is the filtered count instead. Callers without a
            // tag scope keep the original SQL LIMIT/OFFSET + countByFilters path unchanged.
            val tagScoped = principalForChildren.hasTagScope()
            val fetchLimit = if (tagScoped) TAG_SCOPE_SCAN_LIMIT else pp.pageSize
            val fetchOffset = if (tagScoped) 0 else pp.offset

            val childrenResult =
                workItemRepo.findByFilters(
                    parentId = id,
                    limit = fetchLimit,
                    offset = fetchOffset,
                )
            when (childrenResult) {
                is Result.Error -> {
                    logger.warn("GET /items/{}/children DB error: {}", id, childrenResult.error.message)
                    call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Database query failed"))
                }
                is Result.Success -> {
                    if (tagScoped) {
                        // Post-filter by tagsInclude when the principal's scope carries a tag
                        // allowlist. The entry-point check (enforceScopeForItem above) verified the
                        // parent item matches the tag constraint; children are not checked there.
                        // Filter first, paginate second, so totalItems/hasMore never count a child
                        // this token may not read.
                        call.respond(
                            HttpStatusCode.OK,
                            pageOfVisible(childrenResult.data.items.filterByTagScope(principalForChildren), pp),
                        )
                        return@get
                    }

                    val totalResult = workItemRepo.countByFilters(parentId = id)
                    val total = if (totalResult is Result.Success) totalResult.data.toLong() else null
                    val dtos = childrenResult.data.items.map { it.toDto() }
                    call.respond(HttpStatusCode.OK, buildPageDto(dtos, pp, total))
                }
            }
        }
    }
}

/**
 * Registers the read-only gate-status sub-resource for a single item, under `/api/v1`.
 *
 * - `GET /items/{id}/gate` — the item's title, current role, and canonical gate status for that
 *   role: field-for-field identical to `get_context` item mode's `gateStatus` /
 *   `guidanceKey` / `skillPointer`, computed via the SAME [ToolExecutionContext.resolveSchema] +
 *   [computePhaseNoteContext] path get_context uses — no gate logic is reimplemented here.
 *
 * Id handling mirrors `GET /items/{id}`: full UUID only (a hex prefix is rejected), malformed →
 * 400 `bad_request`, unknown → 404 `not_found`, out-of-scope → 403 `scope_forbidden`, checked in
 * that order. No dependency/blocker info and no dispatch field (out of scope for this route — see
 * task-scope). No ETag / `If-None-Match` handling: the gate depends on notes and config, neither
 * of which `item.modifiedAt` versions, so a `respondWithEtagCheck` here could serve a stale 304
 * after a note fill.
 */
fun Route.itemGateRoutes(
    repositoryProvider: RepositoryProvider,
    schemaService: NoteSchemaService,
) {
    val workItemRepo = repositoryProvider.workItemRepository()
    val noteRepo = repositoryProvider.noteRepository()

    // Same construction ItemWriteRoutes.kt's schemaResolutionContext uses: honors per-root config
    // layering via PerRootConfigService, so trait merging and per-root schema overrides behave
    // identically to the MCP get_context tool for the same item.
    val context =
        ToolExecutionContext(
            repositoryProvider,
            schemaService,
            perRootConfigService = PerRootConfigService(repositoryProvider.projectConfigRepository()),
        )

    requireCapability(ApiCapability.READ) {
        // ─── GET /items/{id}/gate ──────────────────────────────────────────────
        get("/items/{id}/gate") {
            val rawId =
                call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing item id"))
                    return@get
                }
            val id =
                runCatching { UUID.fromString(rawId) }.getOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Invalid UUID: $rawId"))
                    return@get
                }

            val itemResult = workItemRepo.getById(id)
            if (itemResult is Result.Error) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Item $id not found"))
                return@get
            }
            val item = (itemResult as Result.Success).data

            if (!enforceScopeForItem(call, id, workItemRepo)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for item $id"))
                return@get
            }

            val resolvedSchema = context.resolveSchema(item)

            val notesResult = noteRepo.findByItemId(item.id)
            val notes = if (notesResult is Result.Success) notesResult.data else emptyList()
            val notesByKey = notes.associateBy { it.key }

            val phaseContext = computePhaseNoteContext(item.role, resolvedSchema?.notes, notesByKey)
            val missing = phaseContext?.missingKeys ?: emptyList()
            val isTerminal = item.role == Role.TERMINAL

            call.respond(
                HttpStatusCode.OK,
                ItemGateDto(
                    itemId = item.id.toString(),
                    title = item.title,
                    role = item.role.toJsonString(),
                    gateStatus =
                        GateStatusDto(
                            canAdvance = !isTerminal && missing.isEmpty(),
                            phase = item.role.toJsonString(),
                            missing = missing,
                        ),
                    guidanceKey = phaseContext?.guidanceKey,
                    skillPointer = phaseContext?.skillPointer,
                ),
            )
        }
    }
}
