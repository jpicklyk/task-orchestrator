package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.FingerprintRelation
import io.github.jpicklyk.mcptask.current.domain.model.ProjectConfig
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlSchemaParser
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.time.Instant
import java.util.UUID

/**
 * Transport-agnostic validation + persist pipeline for per-root config YAML pushes.
 *
 * Extracted from [io.github.jpicklyk.mcptask.current.application.tools.config.ManageProjectConfigTool]
 * (the MCP `manage_project_config` `push` operation) so the exact SAME pipeline can be driven by
 * both the MCP tool and the REST `PUT /api/v1/roots/{rootId}/config` route
 * ([io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.projectConfigRoutes]) — both callers
 * must converge on identical DB state for the same payload.
 *
 * Pipeline, in order: size cap -> root exists -> root is depth-0 -> [SafeConstructor]
 * parse-validate -> embedded-rootId guard (skipped when `force: true`) ->
 * [io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository.upsert].
 * The pipeline stops at the first failing step; nothing is written on failure.
 */
class ProjectConfigPushService(
    private val repositoryProvider: RepositoryProvider,
) {
    /**
     * Validates and persists [configYaml] for [rootItemId]. See [ProjectConfigPushResult] for the
     * possible outcomes.
     *
     * When [force] is `false` (the default), a rootId guard runs after parse-validation: if the
     * document embeds its own root id at top-level `project.rootId` and it parses as a UUID that
     * differs from [rootItemId], the push is rejected as [ProjectConfigPushResult.RootIdMismatch]
     * instead of being upserted — this catches a config document copy-pasted or synced against the
     * wrong project root before it silently overwrites the target root's gates. An absent or
     * non-UUID embedded `project.rootId` is not an error; the push proceeds unchanged.
     *
     * A second guard runs right after: the fast-forward (known-old) guard. The incoming
     * `configYaml`'s fingerprint is classified against the root's stored fingerprint history (see
     * [io.github.jpicklyk.mcptask.current.domain.model.FingerprintRelation]) — a fingerprint that is
     * [FingerprintRelation.SUPERSEDED] (known-old: present in history but not current) is rejected as
     * [ProjectConfigPushResult.Superseded], since writing it would silently revert a later push made
     * from elsewhere. [FingerprintRelation.CURRENT] (idempotent re-push) and
     * [FingerprintRelation.UNKNOWN] (divergent edit, or no row/history yet) both proceed normally.
     *
     * Passing `force: true` skips BOTH guards (bypasses push guards generally — this single flag is
     * shared across guards).
     */
    suspend fun push(
        rootItemId: UUID,
        configYaml: String,
        force: Boolean = false,
    ): ProjectConfigPushResult {
        val sizeBytes = configYaml.toByteArray(Charsets.UTF_8).size
        if (sizeBytes > MAX_CONFIG_YAML_BYTES) {
            return ProjectConfigPushResult.TooLarge(sizeBytes, MAX_CONFIG_YAML_BYTES)
        }

        val item =
            when (val itemResult = repositoryProvider.workItemRepository().getById(rootItemId)) {
                is Result.Success -> itemResult.data
                is Result.Error -> return ProjectConfigPushResult.NotFound(rootItemId)
            }

        if (item.depth != 0) {
            return ProjectConfigPushResult.NotDepthZero(rootItemId, item.depth)
        }

        val typeWarning =
            if (item.type != "project") {
                "Root item type is '${item.type ?: "null"}', not 'project' — config pushed anyway " +
                    "(a naming convention, not an enforced constraint)"
            } else {
                null
            }

        val parsedRoot =
            when (val outcome = parseAndValidateYaml(configYaml)) {
                is YamlParseOutcome.Failure -> return ProjectConfigPushResult.ParseError(outcome.detail)
                is YamlParseOutcome.Success -> outcome.root
            }

        if (!force && parsedRoot != null) {
            val embeddedRootId = extractEmbeddedRootId(parsedRoot)
            if (embeddedRootId != null && embeddedRootId != rootItemId) {
                return ProjectConfigPushResult.RootIdMismatch(rootItemId, embeddedRootId)
            }
        }

        val projectConfigRepository = repositoryProvider.projectConfigRepository()

        if (!force) {
            val incomingFingerprint = projectConfigRepository.computeFingerprint(configYaml)
            val relation =
                when (val relationResult = projectConfigRepository.classifyFingerprint(rootItemId, incomingFingerprint)) {
                    is Result.Success -> relationResult.data
                    is Result.Error -> return ProjectConfigPushResult.RepositoryError(relationResult.error.message)
                }
            if (relation == FingerprintRelation.SUPERSEDED) {
                val currentUpdatedAt =
                    when (val currentResult = projectConfigRepository.get(rootItemId)) {
                        is Result.Success -> currentResult.data?.updatedAt
                        is Result.Error -> null
                    } ?: Instant.now()
                return ProjectConfigPushResult.Superseded(rootItemId, currentUpdatedAt)
            }
        }

        return when (val result = projectConfigRepository.upsert(rootItemId, configYaml)) {
            is Result.Success ->
                ProjectConfigPushResult.Success(
                    rootItemId = result.data.rootItemId,
                    fingerprint = result.data.fingerprint,
                    updatedAt = result.data.updatedAt,
                    warning = typeWarning,
                    ignoredSections = computeIgnoredSections(parsedRoot),
                )
            is Result.Error -> ProjectConfigPushResult.RepositoryError(result.error.message)
        }
    }

    /**
     * Returns the top-level keys of [parsedRoot] that are NOT honored by any per-root resolution
     * layer ([PerRootConfigService][io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService]
     * and [io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext]'s layered
     * resolvers) — e.g. `actor_authentication`, which stays global-only (see `config-format.md`).
     * A pushed document that only contains keys from [HONORED_TOP_LEVEL_SECTIONS] returns an empty
     * list, which callers omit from their response entirely rather than surfacing an empty array.
     */
    private fun computeIgnoredSections(parsedRoot: Map<String, Any>?): List<String> =
        parsedRoot?.keys?.filterNot { it in HONORED_TOP_LEVEL_SECTIONS } ?: emptyList()

    /** Reads back the stored config for [rootItemId], or a null payload when no row exists. */
    suspend fun get(rootItemId: UUID): Result<ProjectConfig?> = repositoryProvider.projectConfigRepository().get(rootItemId)

    /** Deletes the stored config row for [rootItemId]. Returns true when a row was deleted. */
    suspend fun delete(rootItemId: UUID): Result<Boolean> = repositoryProvider.projectConfigRepository().delete(rootItemId)

    /**
     * Parses [configYaml] the same way [io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService]
     * will on every subsequent read (via the shared [YamlSchemaParser]), but BEFORE storing — a
     * stored-but-unparseable config would otherwise silently fall through to the global schema on
     * every future read, a confusing failure mode discovered only much later. Parses ONCE: the
     * returned [YamlParseOutcome.Success.root] is reused by [push]'s embedded-rootId guard so the
     * document is never parsed twice for one push.
     *
     * Uses [SafeConstructor] rather than SnakeYAML's default `Constructor` — `configYaml` is
     * attacker-reachable input (pushed over MCP or REST, not read from a trusted local file), and
     * the default `Constructor` will happily instantiate an arbitrary Java type named by a
     * `!!`-tag (the SnakeYAML deserialization-RCE gadget class, CWE-502). `SafeConstructor` only
     * ever builds plain maps/lists/scalars, which is all a config document legitimately needs; any
     * `!!`-tagged custom type throws [org.yaml.snakeyaml.constructor.ConstructorException] before
     * anything is instantiated, and that exception is caught below like any other parse failure.
     *
     * Soft validation warnings collected by [YamlSchemaParser.parseRoot] (e.g. a note entry
     * missing `key`, an invalid `lifecycle` value) are NOT treated as rejection here — only a hard
     * YAML syntax/shape failure (invalid syntax, or a non-map document) is. Those soft warnings
     * mirror the global config loader's existing behavior: skip the offending entry, keep going.
     */
    private fun parseAndValidateYaml(configYaml: String): YamlParseOutcome =
        try {
            @Suppress("UNCHECKED_CAST")
            val root = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any>>(configYaml)
            if (root != null) {
                YamlSchemaParser.parseRoot(root, warnOnMissingSchemas = false)
            }
            YamlParseOutcome.Success(root)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            YamlParseOutcome.Failure(e.message ?: e.javaClass.simpleName)
        }

    /**
     * Reads a top-level `project.rootId` string from an already-[SafeConstructor]-parsed document
     * root and parses it as a UUID. Returns null when the `project` map, the `rootId` key, or a
     * valid UUID shape is absent — all three are treated as "no embedded rootId" by [push], not as
     * errors.
     */
    private fun extractEmbeddedRootId(root: Map<String, Any>): UUID? {
        val project = root["project"] as? Map<*, *> ?: return null
        val rawRootId = project["rootId"] as? String ?: return null
        return runCatching { UUID.fromString(rawRootId) }.getOrNull()
    }

    /** Outcome of a single [Yaml.load] + [YamlSchemaParser.parseRoot] pass over `configYaml`. */
    private sealed class YamlParseOutcome {
        /** [root] is the SafeConstructor-parsed document root, or null for an empty/blank document. */
        data class Success(
            val root: Map<String, Any>?
        ) : YamlParseOutcome()

        /** [detail] is the parse failure message. */
        data class Failure(
            val detail: String
        ) : YamlParseOutcome()
    }

    companion object {
        /** `configYaml` size limit for `push`, in KiB — see [MAX_CONFIG_YAML_BYTES]. */
        private const val MAX_CONFIG_YAML_KIB = 128

        /**
         * Hard cap on a pushed `configYaml` document's UTF-8 byte size (CWE-770: uncontrolled
         * resource consumption). Enforced before any parse attempt, well above any legitimate
         * config document, and small enough to bound parse cost against a hostile payload.
         */
        const val MAX_CONFIG_YAML_BYTES = MAX_CONFIG_YAML_KIB * 1024

        /**
         * Top-level `configYaml` keys honored by the per-root resolution layer (schema/trait
         * lookup via [io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService],
         * note-limits/status-label layering via
         * [io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext]). Any other
         * top-level key in a pushed document (e.g. `actor_authentication`, which is intentionally
         * global-only — see `config-format.md`) is reported via [ProjectConfigPushResult.Success.ignoredSections]
         * so a push is never silently partial.
         */
        private val HONORED_TOP_LEVEL_SECTIONS =
            setOf("work_item_schemas", "note_schemas", "traits", "project", "note_limits", "status_labels")
    }
}

/** Outcome of [ProjectConfigPushService.push]. */
sealed class ProjectConfigPushResult {
    /**
     * [warning] is non-null when the root's `type` is not `"project"` (non-fatal, push still
     * succeeds). [ignoredSections] lists top-level `configYaml` keys not honored by any per-root
     * resolution layer (see [ProjectConfigPushService.HONORED_TOP_LEVEL_SECTIONS]); empty when the
     * document only used honored keys.
     */
    data class Success(
        val rootItemId: UUID,
        val fingerprint: String,
        val updatedAt: Instant,
        val warning: String? = null,
        val ignoredSections: List<String> = emptyList(),
    ) : ProjectConfigPushResult()

    /** No WorkItem exists for [rootItemId]. */
    data class NotFound(
        val rootItemId: UUID
    ) : ProjectConfigPushResult()

    /** [rootItemId] resolved to a WorkItem, but it is not depth-0 (configs anchor to project roots only). */
    data class NotDepthZero(
        val rootItemId: UUID,
        val depth: Int
    ) : ProjectConfigPushResult()

    /** `configYaml` exceeded [ProjectConfigPushService.MAX_CONFIG_YAML_BYTES]; rejected before any parse attempt. */
    data class TooLarge(
        val sizeBytes: Int,
        val maxBytes: Int
    ) : ProjectConfigPushResult()

    /** `configYaml` failed SafeConstructor parse-validation; [detail] is the parse failure message. */
    data class ParseError(
        val detail: String
    ) : ProjectConfigPushResult()

    /**
     * `configYaml` embeds a top-level `project.rootId` that parses as a UUID but differs from
     * [targetRootId] — the push target the caller supplied. Rejected before any write, unless the
     * caller passed `force: true` to [ProjectConfigPushService.push].
     */
    data class RootIdMismatch(
        val targetRootId: UUID,
        val embeddedRootId: UUID
    ) : ProjectConfigPushResult()

    /**
     * The incoming `configYaml`'s fingerprint classified as
     * [io.github.jpicklyk.mcptask.current.domain.model.FingerprintRelation.SUPERSEDED] — known-old:
     * present in [rootItemId]'s fingerprint history but not its current fingerprint. Rejected before
     * any write, since persisting it would silently revert a later push made from elsewhere, unless
     * the caller passed `force: true` to [ProjectConfigPushService.push]. [currentUpdatedAt] is the
     * server's current row's `updatedAt`, for a caller-facing "local config is older than the
     * server's (updated ...)" message.
     */
    data class Superseded(
        val rootItemId: UUID,
        val currentUpdatedAt: Instant
    ) : ProjectConfigPushResult()

    /** The upsert itself failed at the repository layer. */
    data class RepositoryError(
        val message: String
    ) : ProjectConfigPushResult()
}
