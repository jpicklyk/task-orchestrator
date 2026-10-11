@file:OptIn(StoreWrite::class)

package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.config.CanonicalConfigJson
import io.github.jpicklyk.mcptask.current.application.config.ConfigDiff
import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.application.config.ConfigValidationResult
import io.github.jpicklyk.mcptask.current.application.config.ConfigValidator
import io.github.jpicklyk.mcptask.current.application.config.ConfigViolation
import io.github.jpicklyk.mcptask.current.application.config.ParseMode
import io.github.jpicklyk.mcptask.current.application.config.ServerConfigSource
import io.github.jpicklyk.mcptask.current.application.port.ConfigAppend
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.StoreWrite
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.support.LegacyFaults
import io.github.jpicklyk.mcptask.current.application.support.UnitResult
import io.github.jpicklyk.mcptask.current.application.support.legacyReadFault
import io.github.jpicklyk.mcptask.current.application.support.legacyReadOrNull
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.application.support.runCatchingNonCancellation
import io.github.jpicklyk.mcptask.current.application.support.writeUnit
import io.github.jpicklyk.mcptask.current.application.telemetry.currentCallTelemetry
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.model.ConfigAppendOutcome
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.ConfigVersion
import io.github.jpicklyk.mcptask.current.domain.model.ConfigVersionSource
import io.github.jpicklyk.mcptask.current.domain.model.FingerprintRelation
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Transport-agnostic service over the versioned per-root config store: the validate-then-append push pipeline,
 * reads of the head or any version, history, rollback, reset and the `config-canonicalize` data step body.
 *
 * Both the MCP `manage_project_config` tool and the REST config routes drive this one class, so both surfaces
 * converge on identical stored state for the same payload.
 *
 * Push pipeline, in order: size cap -> root exists -> root is depth-0 -> [SafeConstructor] strict parse ->
 * embedded-rootId guard (skipped when `force: true`) -> [ConfigValidator] (any error rejects, `force` does not
 * bypass it) -> ONE write unit that appends the next version. The pipeline stops at the first failing step; nothing is
 * written on failure. [validate] runs the same steps without the write, the compare-and-set and the superseded guard.
 *
 * Only a push is an admission point. Reading a stored version, rolling back to one and canonicalizing the rows the V25
 * migration copied all parse in [ParseMode.MIGRATION], which never rejects.
 */
class ConfigService(
    private val repositoryProvider: RepositoryProvider,
    private val parser: ConfigDocumentParser,
    /** The transaction boundary: each write ([push], [rollback], [reset]) is ONE write unit. */
    private val unitOfWork: UnitOfWork,
    /** The admission check run by [push] and [validate]. */
    private val validator: ConfigValidator = ConfigValidator(),
    /**
     * The DB-backed server layer a per-root document is validated against (read once per [push] / [validate] call).
     * Null = the validator's own constructor layer.
     */
    private val serverConfig: ServerConfigSource? = null,
) {
    private val logger = LoggerFactory.getLogger(ConfigService::class.java)

    /**
     * Validates and appends [configYaml] as the next version of [rootItemId]'s config. See [ConfigPushResult].
     *
     * With [force] false, a document that embeds a top-level `project.rootId` differing from [rootItemId] is
     * rejected as [ConfigPushResult.RootIdMismatch], and content whose fingerprint is known-old (SUPERSEDED) is
     * rejected as [ConfigPushResult.Superseded]. [force] bypasses those two guards but never the compare-and-set
     * ([expectedVersion] / the legacy [expectedFingerprint]), which both run in the same unit as the write. An
     * identical re-push appends nothing and reports `appended = false`.
     */
    suspend fun push(
        rootItemId: UUID,
        configYaml: String,
        force: Boolean = false,
        expectedVersion: Int? = null,
        expectedFingerprint: String? = null,
    ): ConfigPushResult {
        val sizeBytes = configYaml.toByteArray(Charsets.UTF_8).size
        if (sizeBytes > MAX_CONFIG_YAML_BYTES) {
            return ConfigPushResult.TooLarge(sizeBytes, MAX_CONFIG_YAML_BYTES)
        }

        val item =
            legacyReadFault({ return ConfigPushResult.RepositoryError(it) }) {
                repositoryProvider.workItemRepository().getById(rootItemId)
            } ?: return ConfigPushResult.NotFound(rootItemId)

        if (item.depth != 0) {
            return ConfigPushResult.NotDepthZero(rootItemId, item.depth)
        }

        val typeWarning =
            if (item.type != "project") {
                "Root item type is '${item.type ?: "null"}', not 'project' — config pushed anyway " +
                    "(a naming convention, not an enforced constraint)"
            } else {
                null
            }

        val parsed =
            when (val outcome = parseStrict(configYaml)) {
                is ConfigDocumentParser.Outcome.Failed -> return ConfigPushResult.ParseError(outcome.detail, outcome.violations)
                is ConfigDocumentParser.Outcome.Parsed -> outcome
            }
        val parsedRoot = parsed.rawRoot

        if (!force && parsedRoot != null) {
            val embeddedRootId = extractEmbeddedRootId(parsedRoot)
            if (embeddedRootId != null && embeddedRootId != rootItemId) {
                return ConfigPushResult.RootIdMismatch(rootItemId, embeddedRootId)
            }
        }

        val validation = validateRoot(parsed)
        if (!validation.isValid) {
            return ConfigPushResult.Invalid(validation.errors, validation.warnings)
        }
        val schemaWarnings = validation.warnings.map { it.reason }

        val store = repositoryProvider.configStore()
        val principalId = currentEventActor()?.id
        val reqId = currentCallTelemetry()?.reqId
        val append =
            ConfigAppend(
                yaml = configYaml,
                fingerprint = store.computeFingerprint(configYaml),
                canonicalJson = CanonicalConfigJson.encode(parsedRoot),
                modelVersion = CanonicalConfigJson.MODEL_VERSION,
                source = ConfigVersionSource.PUSH,
                principalId = principalId,
                reqId = reqId,
                warnings = schemaWarnings,
            )

        return unitOfWork.writeUnit(
            "ConfigService.push",
            onFault = { ConfigPushResult.RepositoryError(it) }
        ) {
            val outcome =
                store.append(
                    scope = ConfigScope.ROOT,
                    rootId = rootItemId,
                    append = append,
                    // Neither compare-and-set is skipped by force: force bypasses the rootId-mismatch and
                    // fast-forward guards only, not an explicit precondition the caller supplied.
                    expectedVersion = expectedVersion,
                    expectedFingerprint = expectedFingerprint,
                    rejectSuperseded = !force,
                    now = now,
                )
            if (outcome is ConfigAppendOutcome.Applied && outcome.appended) {
                events.record(createdEvent(outcome.version))
            }
            UnitResult.Commit(
                when (outcome) {
                    is ConfigAppendOutcome.Applied ->
                        ConfigPushResult.Success(
                            rootItemId = rootItemId,
                            fingerprint = outcome.version.fingerprint,
                            updatedAt = outcome.version.createdAt,
                            version = outcome.version.version,
                            appended = outcome.appended,
                            warning = typeWarning,
                            schemaWarnings = schemaWarnings,
                        )
                    is ConfigAppendOutcome.Superseded -> ConfigPushResult.Superseded(rootItemId, outcome.currentCreatedAt)
                    is ConfigAppendOutcome.PreconditionFailed ->
                        ConfigPushResult.PreconditionFailed(rootItemId, outcome.currentVersion, outcome.currentFingerprint)
                }
            )
        }
    }

    /**
     * Dry run of [push] for [rootItemId]: the same size cap, root checks, strict parse, embedded-rootId guard and
     * [ConfigValidator] pass, but nothing is written and neither the compare-and-set nor the superseded guard runs.
     * When the document has no errors the result also carries the [ConfigDiff] against the root's head version (no head,
     * or a reset head, counts as an empty document, so everything is added).
     */
    suspend fun validate(
        rootItemId: UUID,
        configYaml: String,
    ): ConfigValidateResult {
        val sizeBytes = configYaml.toByteArray(Charsets.UTF_8).size
        if (sizeBytes > MAX_CONFIG_YAML_BYTES) {
            return ConfigValidateResult.TooLarge(sizeBytes, MAX_CONFIG_YAML_BYTES)
        }

        val item =
            legacyReadFault({ return ConfigValidateResult.RepositoryError(it) }) {
                repositoryProvider.workItemRepository().getById(rootItemId)
            } ?: return ConfigValidateResult.NotFound(rootItemId)
        if (item.depth != 0) {
            return ConfigValidateResult.NotDepthZero(rootItemId, item.depth)
        }

        val parsed =
            when (val outcome = parseStrict(configYaml)) {
                is ConfigDocumentParser.Outcome.Failed -> return ConfigValidateResult.ParseError(outcome.detail, outcome.violations)
                is ConfigDocumentParser.Outcome.Parsed -> outcome
            }
        val parsedRoot = parsed.rawRoot
        if (parsedRoot != null) {
            val embeddedRootId = extractEmbeddedRootId(parsedRoot)
            if (embeddedRootId != null && embeddedRootId != rootItemId) {
                return ConfigValidateResult.RootIdMismatch(rootItemId, embeddedRootId)
            }
        }

        val validation = validateRoot(parsed)
        if (!validation.isValid) {
            return ConfigValidateResult.Checked(validation.errors, validation.warnings, diff = null)
        }

        val headRoot =
            legacyReadFault({ return ConfigValidateResult.RepositoryError(it) }) {
                get(rootItemId)?.let { headRoot(it) }
            }
        return ConfigValidateResult.Checked(
            errors = emptyList(),
            warnings = validation.warnings,
            diff = ConfigDiff.between(headRoot, parsedRoot),
        )
    }

    /** Validates a per-root document against the DB server layer when one is wired, else the validator's own. */
    private suspend fun validateRoot(parsed: ConfigDocumentParser.Outcome.Parsed): ConfigValidationResult =
        if (serverConfig != null) {
            validator.validate(parsed, ConfigScope.ROOT, serverConfig.layer())
        } else {
            validator.validate(parsed, ConfigScope.ROOT)
        }

    /** The parsed root map of a stored [version]: its canonical JSON, else a migration parse of its YAML. */
    private fun headRoot(version: ConfigVersion): Map<String, Any?>? {
        version.canonicalJson?.let { json -> CanonicalConfigJson.decode(json)?.let { return it } }
        val outcome = parser.parse(version.yaml, warnOnMissingSchemas = false, mode = ParseMode.MIGRATION)
        return (outcome as? ConfigDocumentParser.Outcome.Parsed)?.rawRoot
    }

    /**
     * Reads a stored config version of [rootItemId]. With [version] null this is the head, or null when there is no
     * head or the head is a reset (a reset reads as absence). With [version] given it is that exact version, even
     * a reset one.
     */
    suspend fun get(
        rootItemId: UUID,
        version: Int? = null,
    ): ConfigVersion? {
        val store = repositoryProvider.configStore()
        if (version != null) return store.versionAt(ConfigScope.ROOT, rootItemId, version)
        val head = store.head(ConfigScope.ROOT, rootItemId) ?: return null
        if (head.source == ConfigVersionSource.RESET) return null
        return store.version(head.versionId)
    }

    /** Versions of [rootItemId]'s config, newest first; [before] is an exclusive upper bound on the version. */
    suspend fun history(
        rootItemId: UUID,
        limit: Int = DEFAULT_HISTORY_LIMIT,
        before: Int? = null,
    ): List<ConfigVersion> = repositoryProvider.configStore().history(ConfigScope.ROOT, rootItemId, limit, before)

    /** The head version number of [rootItemId]'s config, or null when it has none. */
    suspend fun headVersion(rootItemId: UUID): Int? = repositoryProvider.configStore().head(ConfigScope.ROOT, rootItemId)?.version

    /**
     * Appends a copy of version [toVersion] as the new head with source `rollback`. A target equal to the head
     * writes nothing and reports `appended = false`. A target whose `canonical_json` is still NULL is canonicalized
     * first, in the same unit.
     */
    suspend fun rollback(
        rootItemId: UUID,
        toVersion: Int,
        expectedVersion: Int? = null,
        expectedFingerprint: String? = null,
    ): ConfigRollbackResult {
        val store = repositoryProvider.configStore()
        val principalId = currentEventActor()?.id
        val reqId = currentCallTelemetry()?.reqId
        return unitOfWork.writeUnit(
            "ConfigService.rollback",
            onFault = { ConfigRollbackResult.RepositoryError(it) }
        ) {
            val head = store.head(ConfigScope.ROOT, rootItemId)
            val target = store.versionAt(ConfigScope.ROOT, rootItemId, toVersion)
            if (head == null || target == null) {
                return@writeUnit UnitResult.Commit(ConfigRollbackResult.NotFound(rootItemId, toVersion))
            }
            val canonical =
                target.canonicalJson ?: run {
                    // Rollback restores a version that was already admitted, so it is never re-validated.
                    val parsed = parser.parse(target.yaml, warnOnMissingSchemas = false, mode = ParseMode.MIGRATION)
                    if (parsed !is ConfigDocumentParser.Outcome.Parsed) {
                        return@writeUnit UnitResult.Rollback(
                            ConfigRollbackResult.RepositoryError(
                                DomainError(ErrorCode.INTERNAL, "Config version $toVersion of root $rootItemId cannot be canonicalized")
                            )
                        )
                    }
                    val json = CanonicalConfigJson.encode(parsed.rawRoot)
                    store.fillCanonical(target.id, json, CanonicalConfigJson.MODEL_VERSION)
                    json
                }
            val outcome =
                store.append(
                    scope = ConfigScope.ROOT,
                    rootId = rootItemId,
                    append =
                        ConfigAppend(
                            yaml = target.yaml,
                            fingerprint = target.fingerprint,
                            canonicalJson = canonical,
                            modelVersion = target.modelVersion ?: CanonicalConfigJson.MODEL_VERSION,
                            source = ConfigVersionSource.ROLLBACK,
                            principalId = principalId,
                            reqId = reqId,
                            warnings = target.warnings,
                        ),
                    expectedVersion = expectedVersion,
                    expectedFingerprint = expectedFingerprint,
                    rejectSuperseded = false,
                    now = now,
                )
            UnitResult.Commit(
                when (outcome) {
                    is ConfigAppendOutcome.Applied -> {
                        if (outcome.appended) events.record(createdEvent(outcome.version))
                        ConfigRollbackResult.Success(outcome.version, outcome.appended)
                    }
                    is ConfigAppendOutcome.PreconditionFailed ->
                        ConfigRollbackResult.PreconditionFailed(rootItemId, outcome.currentVersion, outcome.currentFingerprint)
                    is ConfigAppendOutcome.Superseded ->
                        ConfigRollbackResult.RepositoryError(
                            DomainError(ErrorCode.INTERNAL, "Rollback append was unexpectedly rejected as superseded")
                        )
                }
            )
        }
    }

    /**
     * Resets [rootItemId]'s config: appends an empty RESET version (history keeps every prior version). Returns
     * false, writing nothing, when there is no head or the head is already a reset.
     */
    suspend fun reset(rootItemId: UUID): Outcome<Boolean> {
        val store = repositoryProvider.configStore()
        val principalId = currentEventActor()?.id
        val reqId = currentCallTelemetry()?.reqId
        return unitOfWork.write("ConfigService.reset") {
            val head = store.head(ConfigScope.ROOT, rootItemId)
            if (head == null || head.source == ConfigVersionSource.RESET) {
                return@write Outcome.Ok(false)
            }
            val outcome =
                store.append(
                    scope = ConfigScope.ROOT,
                    rootId = rootItemId,
                    append =
                        ConfigAppend(
                            yaml = "",
                            fingerprint = store.computeFingerprint(""),
                            canonicalJson = CanonicalConfigJson.encode(null),
                            modelVersion = CanonicalConfigJson.MODEL_VERSION,
                            source = ConfigVersionSource.RESET,
                            principalId = principalId,
                            reqId = reqId,
                            warnings = emptyList(),
                        ),
                    now = now,
                )
            if (outcome is ConfigAppendOutcome.Applied && outcome.appended) {
                events.record(createdEvent(outcome.version))
            }
            Outcome.Ok(outcome is ConfigAppendOutcome.Applied && outcome.appended)
        }
    }

    /**
     * Classifies [fingerprint] against [rootItemId]'s stored state and returns the lowercase [FingerprintRelation]
     * name (`"current"` / `"superseded"` / `"unknown"`), or null on a repository error — an unclassifiable
     * fingerprint is treated as if the caller had not supplied one.
     */
    suspend fun classifyRelation(
        rootItemId: UUID,
        fingerprint: String,
    ): String? =
        legacyReadOrNull {
            repositoryProvider
                .configStore()
                .classifyFingerprint(rootItemId, fingerprint)
                .name
                .lowercase()
        }

    /**
     * Body of the `config-canonicalize` data step: fills `canonical_json` / `model_version` of every version still
     * missing them (rows copied by the V25 migration). A row whose YAML fails to parse is left NULL and logged;
     * that root's head then loads fail-closed. Returns the number of rows filled. Runs inside the step's write unit.
     */
    suspend fun canonicalizeMissing(): Int {
        val store = repositoryProvider.configStore()
        var filled = 0
        while (true) {
            val batch = store.versionsMissingCanonical()
            if (batch.isEmpty()) break
            var filledInBatch = 0
            for (row in batch) {
                // Rows the V25 migration copied from 3.x carry whatever the 3.x parser tolerated (plugin sections, soft
                // skips), so they parse in migration mode: nothing here is an admission point.
                when (val outcome = parser.parse(row.yaml, warnOnMissingSchemas = false, mode = ParseMode.MIGRATION)) {
                    is ConfigDocumentParser.Outcome.Parsed ->
                        if (store.fillCanonical(row.id, CanonicalConfigJson.encode(outcome.rawRoot), CanonicalConfigJson.MODEL_VERSION)) {
                            filledInBatch++
                        }
                    is ConfigDocumentParser.Outcome.Failed ->
                        logger.warn(
                            "config-canonicalize: root {} version {} does not parse and is left without canonical JSON: {}",
                            row.rootId,
                            row.version,
                            outcome.detail,
                        )
                }
            }
            filled += filledInBatch
            if (filledInBatch == 0) break
        }
        return filled
    }

    /**
     * Boot import of the mounted global config file (the body of the `server-config-import` data step). Runs INSIDE
     * the ambient write unit, like [canonicalizeMissing]; nothing is written unless the document is accepted.
     *
     * Pipeline: size cap -> strict parse (an empty or comment-only document is [ConfigImportResult.Skipped]) ->
     * target (`project.rootId` absent = [ConfigScope.SERVER]; a valid UUID of a depth-0 item = [ConfigScope.ROOT]; a
     * malformed one is [ConfigImportResult.Invalid]; an unknown or non-root one is [ConfigImportResult.RootNotFound]) ->
     * the top-level `actor_authentication` entry is removed from the raw root (it stays file-sourced and is never
     * validated or stored in canonical form) -> [ConfigValidator] -> the U20 allowance: errors at exactly
     * `/schema_resolution`, `/orchestration`, `/retrospective` or `/actor_attribution` become stored warnings (a push
     * of the same text is still rejected) -> append with source `file-import`. The stored `yaml` is [yaml] verbatim
     * and the fingerprint is the config fingerprint of that text. Identical content appends nothing.
     */
    suspend fun importDocument(yaml: String): ConfigImportResult {
        val sizeBytes = yaml.toByteArray(Charsets.UTF_8).size
        if (sizeBytes > MAX_CONFIG_YAML_BYTES) return ConfigImportResult.TooLarge(sizeBytes, MAX_CONFIG_YAML_BYTES)

        val parsed =
            when (val outcome = parseStrict(yaml)) {
                is ConfigDocumentParser.Outcome.Failed -> return ConfigImportResult.ParseError(outcome.detail, outcome.violations)
                is ConfigDocumentParser.Outcome.Parsed -> outcome
            }
        val rawRoot = parsed.rawRoot ?: return ConfigImportResult.Skipped

        val rawRootId = (rawRoot["project"] as? Map<*, *>)?.get("rootId")
        val rootId: UUID? =
            if (rawRootId == null) {
                null
            } else {
                val id =
                    (rawRootId as? String)?.let { runCatchingNonCancellation { UUID.fromString(it) }.getOrNull() }
                        ?: return ConfigImportResult.Invalid(
                            errors = listOf(ConfigViolation("/project/rootId", "must be a UUID")),
                            warnings = emptyList(),
                        )
                val item =
                    legacyReadFault({ throw IllegalStateException("config import could not read root $id: ${LegacyFaults.message(it)}") }) {
                        repositoryProvider.workItemRepository().getById(id)
                    }
                if (item == null || item.depth != 0) return ConfigImportResult.RootNotFound(id)
                id
            }
        val scope = if (rootId == null) ConfigScope.SERVER else ConfigScope.ROOT

        val strippedRoot = rawRoot - "actor_authentication"
        val stripped = ConfigDocumentParser.Outcome.Parsed(parsed.document, strippedRoot)
        val validation =
            if (scope == ConfigScope.SERVER) {
                validator.validate(stripped, ConfigScope.SERVER, null)
            } else {
                validator.validate(stripped, ConfigScope.ROOT, serverConfig?.layer())
            }
        val (allowed, errors) = validation.errors.partition { it.path in MIGRATION_ONLY_KEY_POINTERS }
        if (errors.isNotEmpty()) return ConfigImportResult.Invalid(errors, validation.warnings)
        val warnings = allowed.map { it.reason } + validation.warnings.map { it.reason }

        val store = repositoryProvider.configStore()
        val append =
            ConfigAppend(
                yaml = yaml,
                fingerprint = store.computeFingerprint(yaml),
                canonicalJson = CanonicalConfigJson.encode(strippedRoot),
                modelVersion = CanonicalConfigJson.MODEL_VERSION,
                source = ConfigVersionSource.FILE_IMPORT,
                principalId = null,
                reqId = null,
                warnings = warnings,
            )
        return unitOfWork.writeUnit(
            "ConfigService.importDocument",
            onFault = { throw IllegalStateException("config import failed: ${LegacyFaults.message(it)}") }
        ) {
            val outcome =
                store.append(
                    scope = scope,
                    rootId = rootId,
                    append = append,
                    expectedVersion = null,
                    expectedFingerprint = null,
                    rejectSuperseded = false,
                    now = now,
                )
            val applied =
                outcome as? ConfigAppendOutcome.Applied
                    ?: throw IllegalStateException("config import append was unexpectedly rejected: $outcome")
            // DomainEvent.rootId is non-null, so a server-scope import records no event: the version row and the step
            // log are its record.
            if (applied.appended && scope == ConfigScope.ROOT) events.record(createdEvent(applied.version))
            UnitResult.Commit<ConfigImportResult>(ConfigImportResult.Imported(applied.version, applied.appended, warnings))
        }
    }

    /** The server-scope head version, or null when there is none or its head is a reset. */
    suspend fun serverHead(): ConfigVersion? =
        try {
            val store = repositoryProvider.configStore()
            val head = store.head(ConfigScope.SERVER, null)
            if (head == null || head.source == ConfigVersionSource.RESET) null else store.version(head.versionId)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            // Same split as the per-root path: a store read fault is transient config_unavailable, never untyped.
            throw PerRootConfigUnavailableException(null, "Server config is temporarily unavailable (read failed)", e)
        }

    private fun createdEvent(version: ConfigVersion): DomainEvent.ConfigVersionCreated =
        DomainEvent.ConfigVersionCreated(
            rootId = checkNotNull(version.rootId) { "A root-scope config version has a rootId" },
            versionId = version.id,
            scope = version.scope,
            version = version.version,
            fingerprint = version.fingerprint,
            source = version.source,
            warningsCount = version.warnings.size,
        )

    /**
     * Parses [configYaml] with the shared [parser] in [ParseMode.STRICT] BEFORE storing: an unparseable stored config
     * would otherwise fail every future read. Parses ONCE; the raw root is reused by the embedded-rootId guard, the
     * validator and the canonical JSON. Uses a SafeConstructor (see [ConfigDocumentParser]): [configYaml] is
     * attacker-reachable input.
     */
    private fun parseStrict(configYaml: String): ConfigDocumentParser.Outcome =
        parser.parse(configYaml, warnOnMissingSchemas = false, mode = ParseMode.STRICT)

    /** Reads a top-level `project.rootId` string from the parsed root as a UUID, or null when absent or malformed. */
    private fun extractEmbeddedRootId(root: Map<String, Any>): UUID? {
        val project = root["project"] as? Map<*, *> ?: return null
        val rawRootId = project["rootId"] as? String ?: return null
        return runCatchingNonCancellation { UUID.fromString(rawRootId) }.getOrNull()
    }

    companion object {
        /** `configYaml` size limit for `push`, in KiB — see [MAX_CONFIG_YAML_BYTES]. */
        private const val MAX_CONFIG_YAML_KIB = 128

        /** Hard cap on a pushed `configYaml` document's UTF-8 byte size (CWE-770), enforced before any parse. */
        const val MAX_CONFIG_YAML_BYTES = MAX_CONFIG_YAML_KIB * 1024

        /** The U20 allowance: top-level keys 3.x configs carried, as JSON Pointers (`ConfigKeyCatalog.TOP_LEVEL_MIGRATION_ONLY`). */
        private val MIGRATION_ONLY_KEY_POINTERS: Set<String> =
            setOf("/schema_resolution", "/orchestration", "/retrospective", "/actor_attribution")

        /** Default page size of [history]. */
        const val DEFAULT_HISTORY_LIMIT = 50
    }
}

/** Outcome of [ConfigService.importDocument]. */
sealed class ConfigImportResult {
    /** The document was accepted; [appended] is false when it was identical to the head (nothing written). */
    data class Imported(
        val version: ConfigVersion,
        val appended: Boolean,
        val warnings: List<String>,
    ) : ConfigImportResult()

    /** An empty or comment-only document: nothing is imported and any stored head stands. */
    object Skipped : ConfigImportResult()

    data class Invalid(
        val errors: List<ConfigViolation>,
        val warnings: List<ConfigViolation>,
    ) : ConfigImportResult()

    data class ParseError(
        val detail: String,
        val violations: List<ConfigViolation> = emptyList(),
    ) : ConfigImportResult()

    /** `project.rootId` is a valid UUID but names no depth-0 item in this database. */
    data class RootNotFound(
        val rootId: UUID,
    ) : ConfigImportResult()

    data class TooLarge(
        val sizeBytes: Int,
        val maxBytes: Int,
    ) : ConfigImportResult()
}

/** Outcome of [ConfigService.push]. */
sealed class ConfigPushResult {
    /**
     * [version] is the head version after the push; [appended] is false for an identical re-push (nothing written).
     * [updatedAt] is the head version's creation time. [warning] is non-null when the root's `type` is not
     * `"project"` (non-fatal). [schemaWarnings] carries the advisory findings (resource fan-out, a required note no seat
     * owns); a push is never rejected for these.
     */
    data class Success(
        val rootItemId: UUID,
        val fingerprint: String,
        val updatedAt: Instant,
        val version: Int,
        val appended: Boolean,
        val warning: String? = null,
        val schemaWarnings: List<String> = emptyList(),
    ) : ConfigPushResult()

    /**
     * `configYaml` parsed but failed validation: [errors] (never empty) each name a JSON Pointer path and a reason;
     * [warnings] are the advisory findings. Nothing was stored.
     */
    data class Invalid(
        val errors: List<ConfigViolation>,
        val warnings: List<ConfigViolation> = emptyList(),
    ) : ConfigPushResult()

    /** No WorkItem exists for [rootItemId]. */
    data class NotFound(
        val rootItemId: UUID
    ) : ConfigPushResult()

    /** [rootItemId] resolved to a WorkItem, but it is not depth-0 (configs anchor to project roots only). */
    data class NotDepthZero(
        val rootItemId: UUID,
        val depth: Int
    ) : ConfigPushResult()

    /** `configYaml` exceeded [ConfigService.MAX_CONFIG_YAML_BYTES]; rejected before any parse attempt. */
    data class TooLarge(
        val sizeBytes: Int,
        val maxBytes: Int
    ) : ConfigPushResult()

    /**
     * `configYaml` failed SafeConstructor parse-validation; [detail] is the parse failure message. [violations] carries
     * the same failure with a JSON Pointer path when it has one (a structural seat error), else it is empty.
     */
    data class ParseError(
        val detail: String,
        val violations: List<ConfigViolation> = emptyList(),
    ) : ConfigPushResult()

    /** `configYaml` embeds a `project.rootId` that differs from [targetRootId]; rejected unless `force`. */
    data class RootIdMismatch(
        val targetRootId: UUID,
        val embeddedRootId: UUID
    ) : ConfigPushResult()

    /** The content's fingerprint is known-old (SUPERSEDED); rejected unless `force`. [currentUpdatedAt] = head's creation time. */
    data class Superseded(
        val rootItemId: UUID,
        val currentUpdatedAt: Instant
    ) : ConfigPushResult()

    /** A supplied expected version / fingerprint did not match the head at the point of the write. Not skipped by `force`. */
    data class PreconditionFailed(
        val rootItemId: UUID,
        val currentVersion: Int,
        val currentFingerprint: String
    ) : ConfigPushResult()

    /** The append itself failed at the repository layer. */
    data class RepositoryError(
        val error: DomainError
    ) : ConfigPushResult() {
        /** The 3.x store message (the innermost SQL text), unchanged on the wire. */
        val message: String get() = LegacyFaults.message(error)
    }
}

/** Outcome of [ConfigService.validate]. */
sealed class ConfigValidateResult {
    /**
     * The document was checked. [errors] empty means it would be accepted by a push; [diff] is then the change against
     * the root's head version (null when there are errors). [warnings] are the advisory findings.
     */
    data class Checked(
        val errors: List<ConfigViolation>,
        val warnings: List<ConfigViolation>,
        val diff: ConfigDiff?,
    ) : ConfigValidateResult() {
        val isValid: Boolean get() = errors.isEmpty()
    }

    data class NotFound(
        val rootItemId: UUID
    ) : ConfigValidateResult()

    data class NotDepthZero(
        val rootItemId: UUID,
        val depth: Int
    ) : ConfigValidateResult()

    data class TooLarge(
        val sizeBytes: Int,
        val maxBytes: Int
    ) : ConfigValidateResult()

    data class ParseError(
        val detail: String,
        val violations: List<ConfigViolation> = emptyList(),
    ) : ConfigValidateResult()

    data class RootIdMismatch(
        val targetRootId: UUID,
        val embeddedRootId: UUID
    ) : ConfigValidateResult()

    data class RepositoryError(
        val error: DomainError
    ) : ConfigValidateResult() {
        val message: String get() = LegacyFaults.message(error)
    }
}

/** Outcome of [ConfigService.rollback]. */
sealed class ConfigRollbackResult {
    /** [version] is the new head (the existing head when [appended] is false). */
    data class Success(
        val version: ConfigVersion,
        val appended: Boolean
    ) : ConfigRollbackResult()

    /** No such version, or no config at all for the root. */
    data class NotFound(
        val rootItemId: UUID,
        val toVersion: Int
    ) : ConfigRollbackResult()

    data class PreconditionFailed(
        val rootItemId: UUID,
        val currentVersion: Int,
        val currentFingerprint: String
    ) : ConfigRollbackResult()

    data class RepositoryError(
        val error: DomainError
    ) : ConfigRollbackResult() {
        val message: String get() = LegacyFaults.message(error)
    }
}
