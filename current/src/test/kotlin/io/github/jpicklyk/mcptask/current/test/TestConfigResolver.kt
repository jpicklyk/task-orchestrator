package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.config.LayerBackedGlobalLookup
import io.github.jpicklyk.mcptask.current.application.config.PerRootConfigSource
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.mockk.coEvery
import io.mockk.spyk

/**
 * Shared test fixture: an [EffectiveConfigResolver] over a real server layer
 * (`LayerBackedGlobalLookup(ConfigLayer(ConfigDocument(...)))`), optionally over a per-root source.
 * Replaces hand-rolled schema-service fakes and mocks: tests describe the server config as a
 * [ConfigDocument] and resolve through the production precedence rule and trait merge.
 */
object TestConfigResolver {
    /** A fingerprint a test may stamp on the server layer (the default is none, like a schema-free server). */
    const val FINGERPRINT = "test-global-fingerprint"

    /** A server [ConfigLayer] over [document]. */
    fun layer(
        document: ConfigDocument,
        fingerprint: String? = null,
    ): ConfigLayer = ConfigLayer(document, fingerprint, ConfigSource.GLOBAL)

    /** A resolver over [document] as the server config, and [perRoot] (none by default). */
    fun resolver(
        document: ConfigDocument = ConfigDocument.EMPTY,
        perRoot: PerRootConfigSource? = null,
        fingerprint: String? = null,
    ): EffectiveConfigResolver = EffectiveConfigResolver(LayerBackedGlobalLookup(layer(document, fingerprint)), perRoot)

    /** A resolver whose server config declares one schema per entry of [schemas] (type -> notes). */
    fun forSchemas(
        schemas: Map<String, List<NoteSchemaEntry>>,
        perRoot: PerRootConfigSource? = null,
    ): EffectiveConfigResolver = resolver(document(schemas), perRoot)

    /** A [ConfigDocument] declaring one [WorkItemSchema] per entry of [schemas] (type -> notes), nothing else. */
    fun document(schemas: Map<String, List<NoteSchemaEntry>>): ConfigDocument =
        documentOf(schemas.mapValues { (type, notes) -> WorkItemSchema(type = type, notes = notes) })

    /** A [ConfigDocument] declaring exactly [schemas], plus optional [traits] notes. */
    fun documentOf(
        schemas: Map<String, WorkItemSchema>,
        traits: Map<String, List<NoteSchemaEntry>> = emptyMap(),
    ): ConfigDocument = ConfigDocument(workItemSchemas = schemas, traits = traits)

    /**
     * [resolver] with type-key validation switched off (`availableTypes` is always empty, so creates and patches
     * accept any type), for tests that need a created item whose type has NO schema while the server declares
     * others. Resolution itself is unchanged.
     */
    fun withoutTypeValidation(resolver: EffectiveConfigResolver): EffectiveConfigResolver =
        spyk(resolver).also { coEvery { it.availableTypes(any()) } returns emptyList() }
}
