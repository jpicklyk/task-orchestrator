package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.config.CanonicalConfigJson
import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.application.port.ConfigAppend
import io.github.jpicklyk.mcptask.current.application.port.ConfigStore
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.ConfigAppendOutcome
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.ConfigVersion
import io.github.jpicklyk.mcptask.current.domain.model.ConfigVersionSource
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import java.time.Instant
import java.util.UUID

/**
 * Test helpers for seeding and reading the versioned config store.
 *
 * [pushConfig] appends one fully-canonicalized version inside a write unit (the production write path shape).
 * The `ConfigStore` extensions below keep the many fixtures that seeded config through the old one-row store
 * readable: they append / read the head the same way, outside a unit.
 */
suspend fun pushConfig(
    provider: RepositoryProvider,
    unitOfWork: UnitOfWork,
    rootId: UUID,
    yaml: String
): ConfigVersion {
    val store = provider.configStore()
    var pushed: ConfigVersion? = null
    val outcome =
        unitOfWork.write("ConfigTestSupport.pushConfig") {
            pushed = store.appendPush(rootId, yaml, now)
            Outcome.Ok(Unit)
        }
    check(outcome is Outcome.Ok) { "pushConfig failed: $outcome" }
    return checkNotNull(pushed)
}

/** Appends [yaml] as the next PUSH version of [rootItemId] (canonical JSON filled), returning the head version. */
suspend fun ConfigStore.upsert(
    rootItemId: UUID,
    configYaml: String
): ConfigVersion = appendPush(rootItemId, configYaml, Instant.now())

/** The head version of [rootItemId]'s config, or null (a reset head still counts as a head here). */
suspend fun ConfigStore.get(rootItemId: UUID): ConfigVersion? = head(ConfigScope.ROOT, rootItemId)?.let { version(it.versionId) }

/** The head fingerprint of [rootItemId]'s config, or null. */
suspend fun ConfigStore.getFingerprint(rootItemId: UUID): String? = head(ConfigScope.ROOT, rootItemId)?.fingerprint

/** Resets [rootItemId]'s config (appends a RESET version), returning it, or null when there is no head or it is already reset. */
suspend fun ConfigStore.delete(rootItemId: UUID): ConfigVersion? {
    val head = head(ConfigScope.ROOT, rootItemId) ?: return null
    if (head.source == ConfigVersionSource.RESET) return null
    val outcome =
        append(
            scope = ConfigScope.ROOT,
            rootId = rootItemId,
            append =
                ConfigAppend(
                    yaml = "",
                    fingerprint = computeFingerprint(""),
                    canonicalJson = CanonicalConfigJson.encode(null),
                    modelVersion = CanonicalConfigJson.MODEL_VERSION,
                    source = ConfigVersionSource.RESET,
                    principalId = null,
                    reqId = null,
                    warnings = emptyList()
                ),
            now = Instant.now()
        )
    return (outcome as ConfigAppendOutcome.Applied).version
}

/** Appends [configYaml] with the old guarded-upsert knobs; returns the raw [ConfigAppendOutcome]. */
suspend fun ConfigStore.upsertGuarded(
    rootItemId: UUID,
    configYaml: String,
    expectedVersion: Int? = null,
    rejectSuperseded: Boolean = false
): ConfigAppendOutcome {
    val parsed = YamlConfigDocumentParser.parse(configYaml, warnOnMissingSchemas = false)
    val canonical = (parsed as? ConfigDocumentParser.Outcome.Parsed)?.let { CanonicalConfigJson.encode(it.rawRoot) }
    return append(
        scope = ConfigScope.ROOT,
        rootId = rootItemId,
        append =
            ConfigAppend(
                yaml = configYaml,
                fingerprint = computeFingerprint(configYaml),
                canonicalJson = canonical,
                modelVersion = CanonicalConfigJson.MODEL_VERSION,
                source = ConfigVersionSource.PUSH,
                principalId = null,
                reqId = null,
                warnings = emptyList()
            ),
        expectedVersion = expectedVersion,
        rejectSuperseded = rejectSuperseded,
        now = Instant.now()
    )
}

val ConfigVersion.configYaml: String get() = yaml
val ConfigVersion.updatedAt: Instant get() = createdAt
val ConfigVersion.rootItemId: UUID get() = checkNotNull(rootId)

private suspend fun ConfigStore.appendPush(
    rootItemId: UUID,
    yaml: String,
    now: Instant
): ConfigVersion {
    val parsed = YamlConfigDocumentParser.parse(yaml, warnOnMissingSchemas = false)
    val canonical = (parsed as? ConfigDocumentParser.Outcome.Parsed)?.let { CanonicalConfigJson.encode(it.rawRoot) }
    val outcome =
        append(
            scope = ConfigScope.ROOT,
            rootId = rootItemId,
            append =
                ConfigAppend(
                    yaml = yaml,
                    fingerprint = computeFingerprint(yaml),
                    canonicalJson = canonical,
                    modelVersion = CanonicalConfigJson.MODEL_VERSION,
                    source = ConfigVersionSource.PUSH,
                    principalId = null,
                    reqId = null,
                    warnings = emptyList()
                ),
            now = now
        )
    return (outcome as ConfigAppendOutcome.Applied).version
}
