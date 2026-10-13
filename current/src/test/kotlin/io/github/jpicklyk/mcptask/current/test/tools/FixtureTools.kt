package io.github.jpicklyk.mcptask.current.test.tools

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.api.ListPage
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCodec
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorFixtures
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.knowledge
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException

/**
 * Test-only 4.0 tool specs (task-scope D1: the production registry is empty, so the guards and the dispatcher are exercised
 * through fixtures). Four tools:
 *  - `fx_read`  select tool: `get` and `list` (knowledge), `audit` (orchestration).
 *  - `fx_peek`  select tool with only an orchestration select (hidden in the knowledge profile).
 *  - `fx_write` ops tool: `create` and `rename` (knowledge), `purge` (orchestration, destructive).
 *  - `fx_admin` ops tool with only an orchestration op (hidden in the knowledge profile).
 *
 * Magic ids and titles steer the handlers: see [GET_MISSING], [GET_SECRET], [GET_BUSY], [GET_CANCEL], [FAIL_TITLE],
 * [SECRET_TITLE]. A field repeated across selects or ops carries the same description text everywhere (a differing
 * description would be a differing generated schema).
 */
class FixtureTools {
    /** Every [CallContext] a handler or op saw, in call order. */
    val seen = CopyOnWriteArrayList<CallContext>()

    companion object {
        const val SECRET = "SECRET-9c2"
        const val GET_OK = "aaaaaaaa"
        const val GET_MISSING = "bbbbbbbb"
        const val GET_SECRET = "cccccccc"
        const val GET_BUSY = "dddddddd"
        const val GET_CANCEL = "eeeeeeee"

        const val FAIL_TITLE = "fail-me"
        const val SECRET_TITLE = "secret-throw"

        val StringCodec: IdempotencyCodec<String> =
            object : IdempotencyCodec<String> {
                override fun encode(value: String): JsonElement = JsonPrimitive(value)

                override fun decode(json: JsonElement): String = json.jsonPrimitive.content
            }
    }

    @Serializable
    data class GetReq(
        @Doc("Item id.") val id: IdRef,
        @Doc("Depth to include.") val depth: Int = 1
    )

    @Serializable
    data class ListReq(
        @Doc("Maximum rows.") val limit: Int = 10,
        @Doc("Tag filter.") val tag: String? = null,
        @Doc("Depth to include.") val depth: Int = 1
    )

    @Serializable
    data class AuditReq(
        @Doc("Audit scope.") val scope: String,
        @Doc("Tag filter.") val tag: String? = null,
        @Doc("Depth to include.") val depth: Int = 1
    )

    @Serializable
    data class PeekReq(
        @Doc("What to peek at.") val what: String
    )

    @Serializable
    data class CreateReq(
        @Doc("Title text.") val title: String,
        @Doc("Tags to attach.") val tags: List<String> = emptyList(),
        @Doc("Priority number.") val priority: Int = 0
    )

    @Serializable
    data class RenameReq(
        @Doc("Item id.") val id: IdRef,
        @Doc("Title text.") val title: String? = null
    )

    @Serializable
    data class PurgeReq(
        @Doc("Item id.") val id: IdRef,
        @Doc("Skip safety checks.") val force: Boolean = false
    )

    @Serializable
    data class ResetReq(
        @Doc("Reason for the reset.") val reason: String
    )

    val getSelect =
        SelectSpec.Single<GetReq, String>(
            name = "get",
            doc = "Read one thing by id.",
            profiles = knowledge(),
            request = serializer<GetReq>(),
            errors = setOf(ErrorCode.NOT_FOUND),
            handler = { _, ctx, req ->
                seen += ctx
                when (req.id.raw) {
                    GET_MISSING -> Outcome.Err(ErrorFixtures.error(ErrorCode.NOT_FOUND))
                    GET_SECRET -> throw RuntimeException(SECRET)
                    GET_BUSY -> throw SQLiteException("database is locked", SQLiteErrorCode.SQLITE_BUSY)
                    GET_CANCEL -> throw CancellationException("cancelled from inside")
                    else -> Outcome.Ok("got:${req.id.raw}:${req.depth}")
                }
            },
            data = { JsonPrimitive(it) }
        )

    val listSelect =
        SelectSpec.Paged<ListReq, String>(
            name = "list",
            doc = "List things.",
            profiles = knowledge(),
            request = serializer<ListReq>(),
            handler = { _, ctx, req ->
                seen += ctx
                if (req.tag == "all") {
                    Outcome.Ok(ListPage(listOf("a", "b"), total = 2, truncated = false))
                } else {
                    Outcome.Ok(ListPage(listOf("a", "b"), total = 5, truncated = true, next = "n1"))
                }
            },
            item = { JsonPrimitive(it) }
        )

    val auditSelect =
        SelectSpec.Single<AuditReq, String>(
            name = "audit",
            doc = "Audit things.",
            profiles = orchestration(),
            request = serializer<AuditReq>(),
            handler = { _, ctx, req ->
                seen += ctx
                Outcome.Ok("audit:${req.scope}")
            },
            data = { JsonPrimitive(it) }
        )

    val peekSelect =
        SelectSpec.Single<PeekReq, String>(
            name = "peek",
            doc = "Peek.",
            profiles = orchestration(),
            request = serializer<PeekReq>(),
            handler = { _, _, req -> Outcome.Ok("peek:${req.what}") },
            data = { JsonPrimitive(it) }
        )

    val createOp =
        OpSpec<CreateReq, String>(
            name = "create",
            doc = "Create a thing.",
            capability = ApiCapability.WRITE_ITEMS,
            profiles = knowledge(),
            request = serializer<CreateReq>(),
            errors = setOf(ErrorCode.NOT_FOUND),
            execute = { _, ctx, req ->
                seen += ctx
                stores.workItemRepository().create(WorkItem(title = req.title))
                when (req.title) {
                    FAIL_TITLE -> Outcome.Err(ErrorFixtures.error(ErrorCode.NOT_FOUND))
                    SECRET_TITLE -> throw RuntimeException(SECRET)
                    else -> Outcome.Ok("created:${req.title}")
                }
            }
        )

    val renameOp =
        OpSpec<RenameReq, String>(
            name = "rename",
            doc = "Rename a thing.",
            capability = ApiCapability.WRITE_ITEMS,
            profiles = knowledge(),
            request = serializer<RenameReq>(),
            execute = { _, ctx, req ->
                seen += ctx
                Outcome.Ok("renamed:${req.id.raw}:${req.title.orEmpty()}")
            }
        )

    val purgeOp =
        OpSpec<PurgeReq, String>(
            name = "purge",
            doc = "Purge a thing.",
            profiles = orchestration(),
            destructive = true,
            capability = ApiCapability.WRITE_ITEMS,
            request = serializer<PurgeReq>(),
            execute = { _, ctx, req ->
                seen += ctx
                Outcome.Ok("purged:${req.id.raw}")
            }
        )

    val resetOp =
        OpSpec<ResetReq, String>(
            name = "reset",
            doc = "Reset.",
            profiles = orchestration(),
            capability = ApiCapability.WRITE_ITEMS,
            request = serializer<ResetReq>(),
            execute = { _, _, req -> Outcome.Ok("reset:${req.reason}") }
        )

    val readTool = SelectToolSpec("fx_read", "Fixture read tool.", listOf(getSelect, listSelect, auditSelect))

    val peekTool = SelectToolSpec("fx_peek", "Fixture orchestration-only read tool.", listOf(peekSelect))

    val writeTool =
        OpsToolSpec<String>(
            name = "fx_write",
            description = "Fixture write tool.",
            ops = listOf(createOp, renameOp, purgeOp),
            codec = StringCodec,
            element = { buildJsonObject { put("id", it) } }
        )

    val adminTool =
        OpsToolSpec<String>(
            name = "fx_admin",
            description = "Fixture orchestration-only write tool.",
            ops = listOf(resetOp),
            codec = StringCodec,
            element = { buildJsonObject { put("id", it) } }
        )

    val registry: ToolRegistry get() = ToolRegistry(listOf(readTool, peekTool, writeTool, adminTool))
}
