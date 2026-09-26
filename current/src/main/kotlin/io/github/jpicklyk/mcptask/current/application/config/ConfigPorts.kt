package io.github.jpicklyk.mcptask.current.application.config

import java.util.UUID

/**
 * The single, process-wide global config layer. Implementations parse the global
 * `.taskorchestrator/config.yaml` (or an equivalent process-scoped floor config) exactly ONCE at
 * startup and cache the result for the life of the process — restart to reload. Returns `null`
 * when no global config file is present (schema-free mode), never on a parse error: a malformed
 * file fails closed at construction time (see `GlobalConfigFile`), not by returning `null` here.
 */
fun interface GlobalConfigSource {
    fun layer(): ConfigLayer?
}

/**
 * A per-root config layer, keyed by a project root's WorkItem UUID. `null` = absence: no config
 * row exists for [rootId], or the stored document failed to parse — both fall through to the
 * global layer. A transient read failure with no last-known-good entry to serve throws
 * `PerRootConfigUnavailableException` (contract unchanged from `PerRootConfigService`) rather than
 * returning `null` — callers must not confuse "no per-root config" with "per-root config
 * temporarily unreadable".
 */
interface PerRootConfigSource {
    suspend fun layer(rootId: UUID): ConfigLayer?
}

/**
 * Shared YAML -> [ConfigDocument] parse, used by every per-root config reader (`PerRootConfigService`,
 * `ProjectConfigPushService`) so there is exactly one SafeConstructor-parsing implementation for
 * attacker-reachable (pushed) config YAML, instead of one per caller.
 */
interface ConfigDocumentParser {
    /** Outcome of a single [parse] call. */
    sealed interface Outcome {
        /**
         * [document] is the parsed document (an empty/comment-only document parses to
         * [ConfigDocument.EMPTY], never a null document — see the type's non-nullability).
         * [rawRoot] is the SafeConstructor-parsed root map before [ConfigDocument] extraction, or
         * `null` for an empty/blank document; callers that need the raw map (e.g. an
         * embedded-rootId guard) reuse it instead of re-parsing.
         */
        data class Parsed(
            val document: ConfigDocument,
            val rawRoot: Map<String, Any>?
        ) : Outcome

        /** [detail] is the parse failure message (YAML syntax error, non-mapping root, ...). */
        data class Failed(
            val detail: String
        ) : Outcome
    }

    /**
     * Parses [yaml] into an [Outcome]. [warnOnMissingSchemas] controls whether a document with
     * neither `work_item_schemas:` nor `note_schemas:` records a "no schemas loaded" warning — see
     * `YamlSchemaParser.parseRoot` for why the global loader wants this warning and per-root
     * documents do not.
     */
    fun parse(
        yaml: String,
        warnOnMissingSchemas: Boolean,
    ): Outcome
}
