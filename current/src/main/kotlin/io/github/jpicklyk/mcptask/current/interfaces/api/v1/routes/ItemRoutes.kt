package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.DependenciesDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ItemDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.etag.respondWithEtagCheck
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.buildDependenciesDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.toDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination.buildPageDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination.pageParams
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
 * All endpoints require [ApiCapability.READ]. Scope filtering is applied at the SQL level
 * via [findInScope] / [countInScope] when the principal has a non-null `rootIds` set.
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
            val pp = call.pageParams()
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
                        limit = pp.pageSize,
                        offset = pp.offset,
                        type = type,
                        claimStatus = claimStatus,
                    )
                } else {
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
                        limit = pp.pageSize,
                        offset = pp.offset,
                        type = type,
                        claimStatus = claimStatus,
                    )
                }

            when (items) {
                is Result.Error -> {
                    logger.warn("GET /items DB error: {}", items.error.message)
                    call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Database query failed"))
                }
                is Result.Success -> {
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
                    call.respond(HttpStatusCode.OK, buildPageDto(dtos, pp, total))
                }
            }
        }

        // ─── GET /items/roots ────────────────────────────────────────────────
        get("/items/roots") {
            val principal = call.attributes.getOrNull(ApiPrincipalKey)
            val pp = call.pageParams()
            val scopeRootIds = principal?.scope?.rootIds

            if (scopeRootIds != null) {
                // Scoped token: fetch only the specific root items the principal can see.
                // This avoids the 200-cap correctness issue for tokens with many roots.
                val roots =
                    scopeRootIds
                        .mapNotNull { rid ->
                            val r = workItemRepo.getById(rid)
                            if (r is Result.Success && r.data.parentId == null) r.data else null
                        }
                val page = roots.drop(pp.offset).take(pp.pageSize)
                val dtos = page.map { it.toDto() }
                call.respond(HttpStatusCode.OK, buildPageDto(dtos, pp, roots.size.toLong()))
            } else {
                // Unscoped/admin: global scan (cap documented — full count may exceed 200).
                val result = workItemRepo.findRootItems(limit = 200)
                when (result) {
                    is Result.Error -> {
                        logger.warn("GET /items/roots DB error: {}", result.error.message)
                        call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Database query failed"))
                    }
                    is Result.Success -> {
                        val allRoots = result.data
                        val page = allRoots.drop(pp.offset).take(pp.pageSize)
                        val dtos = page.map { it.toDto() }
                        call.respond(HttpStatusCode.OK, buildPageDto(dtos, pp, allRoots.size.toLong()))
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

            val childrenDtos: List<ItemDto>? =
                if ("children" in includes) {
                    workItemRepo.findChildren(id).let { r ->
                        if (r is Result.Success) r.data.map { it.toDto() } else null
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

            val pp = call.pageParams()
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
            val filtered =
                if (relativeMaxDepth != null) {
                    descendants.filter { it.depth <= relativeMaxDepth }
                } else {
                    descendants
                }

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
            call.respond(HttpStatusCode.OK, visible.map { it.toDto() })
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

            val pp = call.pageParams()
            val childrenResult =
                workItemRepo.findByFilters(
                    parentId = id,
                    limit = pp.pageSize,
                    offset = pp.offset,
                )
            when (childrenResult) {
                is Result.Error -> {
                    logger.warn("GET /items/{}/children DB error: {}", id, childrenResult.error.message)
                    call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Database query failed"))
                }
                is Result.Success -> {
                    val totalResult = workItemRepo.countByFilters(parentId = id)
                    val total = if (totalResult is Result.Success) totalResult.data.toLong() else null
                    val dtos = childrenResult.data.map { it.toDto() }
                    call.respond(HttpStatusCode.OK, buildPageDto(dtos, pp, total))
                }
            }
        }
    }
}
