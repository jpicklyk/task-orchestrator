package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.ADMIN_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.configureTestApp
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.itemRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.makeTestAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Shared harness for the `items_read` tests (test-author seat, item 450aa813). Every call goes over HTTP into the real
 * production topology: `POST /api/v1/ops/items_read` for selects and `GET /api/v1/items/...` for the resource GETs, with a
 * real bearer principal so the scope under test is the scope a production token carries.
 *
 * Tokens: [ADMIN_TOKEN] is unrestricted; `TEST_TOKEN` carries the root / tag scope handed to [irApp] (via
 * `makeTestAuthConfig`). Both hold the READ capability.
 */
internal const val IR = "items_read"

/** The 8-hex short id a compact view uses (task-scope item 2: "id and parentId as 8-hex short ids"). */
internal fun short(id: UUID): String = id.toString().substring(0, 8)

/** One HTTP answer. [body] is parsed on demand so a 304 (no body) can be inspected without a parse failure. */
internal class Reply(
    val status: Int,
    val raw: String,
    val etag: String?
) {
    val body: JsonObject by lazy { Json.parseToJsonElement(raw).jsonObject }
    val ok: Boolean get() =
        body
            .getValue("ok")
            .jsonPrimitive.content
            .toBoolean()
    val data: JsonObject get() = body.getValue("data").jsonObject
    val error: JsonObject get() = body.getValue("error").jsonObject
    val code: String get() = error.getValue("code").jsonPrimitive.content
    val detail: JsonObject get() = error.getValue("detail").jsonObject

    /** `data.items` of a paged select. */
    val items: List<JsonObject> get() = data.getValue("items").jsonArray.map { it.jsonObject }
    val page: JsonObject get() = data.getValue("page").jsonObject
    val total: Int get() =
        page
            .getValue("total")
            .jsonPrimitive.content
            .toInt()
    val next: String? get() = page["next"]?.jsonPrimitive?.content

    /** The `field` pointers of an `invalid_request` detail. */
    fun fields(): List<String> =
        detail.getValue("fields").jsonArray.map {
            it.jsonObject
                .getValue("field")
                .jsonPrimitive.content
        }

    /** The reason texts of an `invalid_request` detail. */
    fun reasons(): List<String> =
        detail.getValue("fields").jsonArray.map {
            it.jsonObject
                .getValue("reason")
                .jsonPrimitive.content
        }

    /** The pointers of an `unknown_parameter` detail. */
    fun parameters(): List<String> = detail.getValue("parameters").jsonArray.map { it.jsonPrimitive.content }
}

internal fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content

internal fun JsonObject.arr(name: String): JsonArray = getValue(name).jsonArray

internal fun JsonObject.strings(name: String): List<String> = arr(name).map { it.jsonPrimitive.content }

/** The titles of the items in order. */
internal fun List<JsonObject>.titles(): List<String> = map { it.str("title") }

/** Thin HTTP client over the mounted app. */
internal class IrClient(
    private val http: HttpClient
) {
    /** `POST /api/v1/ops/items_read` with [args] (a JSON object text) under [token]. */
    suspend fun select(
        args: String,
        token: String = ADMIN_TOKEN
    ): Reply {
        val response =
            http.post("/api/v1/ops/$IR") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(args)
            }
        return Reply(response.status.value, response.bodyAsText(), response.headers[HttpHeaders.ETag])
    }

    /** `GET [path]` (under `/api/v1`) under [token], with optional extra [headers]. */
    suspend fun get(
        path: String,
        token: String = ADMIN_TOKEN,
        headers: List<Pair<String, String>> = emptyList()
    ): Reply {
        val response =
            http.get("/api/v1$path") {
                header(HttpHeaders.Authorization, "Bearer $token")
                headers.forEach { (k, v) -> header(k, v) }
            }
        return Reply(response.status.value, response.bodyAsText(), response.headers[HttpHeaders.ETag])
    }
}

/**
 * Runs [block] against the full production REST topology (real composition, `installRestApiRoutes`, the production tool
 * registry behind `/ops`). [scopeRootIds] / [tagsInclude] scope `TEST_TOKEN`; `ADMIN_TOKEN` stays unrestricted.
 */
internal fun irApp(
    db: SqliteTestDatabase,
    dir: Path,
    scopeRootIds: Set<UUID>? = null,
    tagsInclude: Set<String> = emptySet(),
    yaml: String = "work_item_schemas: {}\n",
    block: suspend IrClient.(EventLogRig) -> Unit
) = testApplication {
    val rig = EventLogRig.build(db, dir, yaml)
    val auth: ApiAuthConfig.Bearer = makeTestAuthConfig(scopeRootIds, tagsInclude)
    application { installParityRestApp(rig, auth, ToolRegistry(buildToolSpecs())) }
    IrClient(client).block(rig)
}

/**
 * Runs [block] against ONLY the item resource GETs mounted exactly as the route-test harness mounts them
 * (`configureTestApp` + `itemRoutes(registry, dispatcher)`), over the real dispatcher of the rig.
 */
internal fun irRestApp(
    db: SqliteTestDatabase,
    dir: Path,
    scopeRootIds: Set<UUID>? = null,
    tagsInclude: Set<String> = emptySet(),
    block: suspend IrClient.(EventLogRig) -> Unit
) = testApplication {
    val rig = EventLogRig.build(db, dir)
    val auth: ApiAuthConfig.Bearer = makeTestAuthConfig(scopeRootIds, tagsInclude)
    application {
        configureTestApp(auth) { itemRoutes(ToolRegistry(buildToolSpecs()), buildToolDispatcher(rig.ctx)) }
    }
    IrClient(client).block(rig)
}

/**
 * Seeds an item through the undecorated repository. Depth and root are derived from [parent] so the domain invariants hold
 * by construction.
 */
internal suspend fun EventLogRig.make(
    title: String,
    parent: WorkItem? = null,
    role: Role = Role.QUEUE,
    tags: String? = null,
    id: UUID = UUID.randomUUID(),
    priority: Priority = Priority.MEDIUM,
    type: String? = "test-item",
    summary: String = "",
    traits: List<String> = emptyList(),
    claimedBy: String? = null
): WorkItem {
    val claimExpiresAt = if (claimedBy != null) Instant.now().plusSeconds(3600) else null
    val claimedAt = claimExpiresAt?.minusSeconds(60)
    return raw.workItemRepository().create(
        WorkItem(
            id = id,
            title = title,
            summary = summary,
            parentId = parent?.id,
            depth = (parent?.depth ?: -1) + 1,
            rootId = parent?.let { it.rootId ?: it.id },
            role = role,
            priority = priority,
            tags = tags,
            type = type,
            traits = traits,
            claimedBy = claimedBy,
            claimedAt = claimedAt,
            claimExpiresAt = claimExpiresAt,
            originalClaimedAt = claimedAt
        )
    )
}
