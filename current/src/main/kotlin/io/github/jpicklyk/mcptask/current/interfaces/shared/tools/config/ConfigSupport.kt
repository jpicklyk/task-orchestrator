package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.config.ConfigDelta
import io.github.jpicklyk.mcptask.current.application.config.ConfigDiff
import io.github.jpicklyk.mcptask.current.application.service.ConfigService
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.application.support.toDomainError
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.ConfigVersion
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigInvalidException
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidField
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.checkItemScope
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.resolveItemId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import io.github.jpicklyk.mcptask.current.application.config.ConfigViolation as AppViolation
import io.github.jpicklyk.mcptask.current.domain.error.ConfigViolation as ErrorViolation

/** The one documentation source of each field two or more `config` ops share (a shared name has one schema). */
internal object ConfigDocs {
    const val ROOT_ID = "Project root item (depth 0): a UUID or a hex prefix of 8 to 32 characters."
    const val VERSION = "Config version number, 1 or greater."
    const val EXPECTED_VERSION =
        "Compare-and-set: the head version this write expects, 1 or greater. A different head is version_conflict; force does not skip it."
    const val CONFIG_YAML = "The config document, YAML text of at most 131072 bytes."
}

/** Helpers shared by the `config` ops: root resolution, error building and JSON shaping. */
internal object ConfigSupport {
    /** Retry hint of an `unavailable` raised by an unreadable config head. */
    private const val CONFIG_RETRY_MS = 1000L

    /** The service wired at composition, or one built over the context's own stores (tests). */
    fun service(env: ToolExecutionContext): ConfigService =
        env.configService ?: ConfigService(env.repositoryProvider, YamlConfigDocumentParser, env.unitOfWork)

    /**
     * Resolves [ref] to a depth-0 root the call's scope admits. Order: scope (an absent or ambiguous id under a
     * restricted scope is `forbidden`, never an existence oracle), then existence (`not_found` kind root), then depth
     * (`invalid_request` at `/rootId`).
     */
    suspend fun resolveRoot(
        env: ToolExecutionContext,
        ctx: CallContext,
        ref: IdRef,
        field: String = "/rootId"
    ): Outcome<UUID> {
        val id =
            when (val r = resolveItemId(env, ctx.scope, ref, field)) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> return if (r.error.code == ErrorCode.NOT_FOUND) Outcome.Err(notFound(EntityKind.ROOT, ref.raw)) else r
            }
        (checkItemScope(env, ctx, id) as? Outcome.Err)?.let { return it }
        val item = env.workItemRepository().getById(id) ?: return Outcome.Err(notFound(EntityKind.ROOT, id.toString()))
        if (item.depth != 0) {
            return Outcome.Err(invalidField(field, "must reference a depth-0 (root) item; it has depth ${item.depth}", id.toString()))
        }
        return Outcome.Ok(id)
    }

    /**
     * Runs [block], mapping an unloadable config head to `config_invalid` and an unreadable one to `unavailable`
     * for THIS element (a thrown store fault is translated by the unit boundary as usual).
     */
    suspend fun <T> guarded(block: suspend () -> Outcome<T>): Outcome<T> =
        try {
            block()
        } catch (e: PerRootConfigInvalidException) {
            Outcome.Err(e.toDomainError())
        } catch (e: PerRootConfigUnavailableException) {
            e.rethrowIfCancellation()
            Outcome.Err(
                DomainError(
                    code = ErrorCode.UNAVAILABLE,
                    message = "The config could not be read; retry shortly.",
                    detail = ErrorDetail.Unavailable(CONFIG_RETRY_MS)
                )
            )
        }

    fun notFound(
        kind: EntityKind,
        id: String
    ): DomainError =
        DomainError(
            code = ErrorCode.NOT_FOUND,
            message = "No ${kind.name.lowercase()} matches $id.",
            detail = ErrorDetail.NotFound(kind, id),
            fixArgs = mapOf("kind" to kind.name.lowercase(), "id" to id)
        )

    /** `not_found` of a root's config (no config at all, or an absent version). */
    fun configNotFound(rootId: UUID): DomainError = notFound(EntityKind.CONFIG, rootId.toString())

    fun versionConflict(
        rootId: UUID,
        expected: Int,
        actual: Int
    ): DomainError =
        DomainError(
            code = ErrorCode.VERSION_CONFLICT,
            message = "The config of root $rootId is at version $actual, not $expected.",
            detail = ErrorDetail.VersionConflict(EntityKind.CONFIG, rootId.toString(), expected.toLong(), actual.toLong()),
            fixArgs = mapOf("kind" to "config", "id" to rootId.toString(), "actual" to actual.toString())
        )

    fun payloadTooLarge(
        max: Int,
        actual: Int
    ): DomainError =
        DomainError(
            code = ErrorCode.PAYLOAD_TOO_LARGE,
            message = "configYaml is $actual bytes, exceeds the $max byte limit.",
            detail = ErrorDetail.PayloadTooLarge(max.toLong(), actual.toLong()),
            fixArgs = mapOf("max" to max.toString())
        )

    /** `config_invalid` over [errors]; a failure without a pointer is one root-pointer entry. */
    fun configInvalid(errors: List<AppViolation>): DomainError {
        val list = errors.map { ErrorViolation(it.path, it.reason) }
        return DomainError(
            code = ErrorCode.CONFIG_INVALID,
            message = "The config failed validation with ${list.size} error(s); nothing was stored.",
            detail = ErrorDetail.ConfigInvalid(list)
        )
    }

    /** The violations of a parse failure: its own pointers, else one root-pointer entry carrying [detail]. */
    fun parseViolations(
        detail: String,
        violations: List<AppViolation>
    ): List<AppViolation> = violations.ifEmpty { listOf(AppViolation("", detail)) }

    /** The violation of a `project.rootId` that names another root. */
    fun rootIdMismatch(
        target: UUID,
        embedded: UUID
    ): AppViolation =
        AppViolation(
            "/project/rootId",
            "embeds root $embedded, which differs from the target root $target; fix project.rootId or pass force: true"
        )

    fun violationJson(v: AppViolation): JsonObject =
        buildJsonObject {
            put("path", v.path)
            put("reason", v.reason)
        }

    fun violationsJson(list: List<AppViolation>): JsonArray = JsonArray(list.map(::violationJson))

    fun diffJson(diff: ConfigDiff): JsonObject =
        buildJsonObject {
            put("types", deltaJson(diff.types))
            put("traits", deltaJson(diff.traits))
            put("settings", deltaJson(diff.settings))
        }

    private fun deltaJson(delta: ConfigDelta): JsonObject =
        buildJsonObject {
            put("added", JsonArray(delta.added.map(::JsonPrimitive)))
            put("removed", JsonArray(delta.removed.map(::JsonPrimitive)))
            put("changed", JsonArray(delta.changed.map(::JsonPrimitive)))
        }

    /** The element result of a write that produced or selected [version]. */
    fun versionJson(
        rootId: UUID,
        version: ConfigVersion,
        appended: Boolean
    ): JsonObject =
        buildJsonObject {
            put("rootId", rootId.toString())
            put("version", version.version)
            put("fingerprint", version.fingerprint)
            put("source", version.source.wire)
            put("appended", appended)
            put("createdAt", version.createdAt.toString())
        }
}
