package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.SeatDefinition
import io.github.jpicklyk.mcptask.current.domain.model.SeatDispatchOverride
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlSchemaParser
import io.github.jpicklyk.mcptask.current.test.TestConfigResolver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `task-scope` / `test-plan` notes on item `21c446c6` (Q9, scenarios S1-S13
 * plus the planned probes). Real [ConfigDocument]s, no mocks: an [EffectiveConfigResolver] over a server
 * [ConfigLayer] and a hand-written per-root source.
 *
 * Oracles (never read from an implementation): plan `v4-phase2-core` section 3.5 (one schema precedence rule: per-root
 * type, server type, per-root `default`, server `default`, else null; tags never consulted), section 2 and U9 (trait
 * merge: item traits first, then the schema's `default_traits`, deduplicated keeping the first position; `required`
 * OR'd), and task-scope AC1-AC6 / D1-D13. Every scenario is EXISTING-SURFACE: no scenario calls `TraitResolver`
 * directly.
 */
class PrecedenceTableTest {
    private class FakePerRoot(
        private val layers: Map<UUID, ConfigLayer?>
    ) : PerRootConfigSource {
        override suspend fun layer(rootId: UUID): ConfigLayer? = layers[rootId]
    }

    private fun perRootLayer(doc: ConfigDocument): ConfigLayer = ConfigLayer(doc, PR_FP, ConfigSource.PER_ROOT)

    private fun note(
        key: String,
        required: Boolean = false,
        role: Role = Role.QUEUE,
        description: String = "",
        guidance: String? = null,
        skill: String? = null,
        maxLength: Int? = null
    ) = NoteSchemaEntry(
        key = key,
        role = role,
        required = required,
        description = description,
        guidance = guidance,
        skill = skill,
        maxLength = maxLength
    )

    private fun schema(
        type: String,
        notes: List<NoteSchemaEntry> = emptyList(),
        defaultTraits: List<String> = emptyList(),
        seats: List<SeatDefinition> = emptyList()
    ) = WorkItemSchema(type = type, notes = notes, defaultTraits = defaultTraits, seats = seats)

    private fun item(
        type: String?,
        rootId: UUID? = null,
        traits: List<String> = emptyList(),
        tags: String? = null
    ): WorkItem =
        WorkItem(
            title = "Q9 item",
            type = type,
            rootId = rootId,
            tags = tags,
            depth = 0,
            properties = if (traits.isEmpty()) null else """{"traits":[${traits.joinToString(",") { "\"$it\"" }}]}"""
        )

    private fun serverResolver(
        doc: ConfigDocument,
        rootId: UUID? = null,
        perRootDoc: ConfigDocument? = null
    ): EffectiveConfigResolver {
        val source = if (rootId != null && perRootDoc != null) FakePerRoot(mapOf(rootId to perRootLayer(perRootDoc))) else null
        return TestConfigResolver.resolver(doc, source, SV_FP)
    }

    private fun originSchema(
        origin: String,
        type: String
    ) = schema(type, listOf(note("origin-$origin")))

    // ------------------------------------------------------------------
    // S1 - the 16-row precedence table
    // ------------------------------------------------------------------

    private data class Row(
        val prType: Boolean,
        val prDefault: Boolean,
        val svType: Boolean,
        val svDefault: Boolean,
        val expectedOrigin: String?
    )

    // Hand-written from the rule: per-root[T], server[T], per-root[default], server[default], else null.
    private val rows =
        listOf(
            Row(false, false, false, false, null),
            Row(false, false, false, true, "sv-default"),
            Row(false, false, true, false, "sv-T"),
            Row(false, false, true, true, "sv-T"),
            Row(false, true, false, false, "pr-default"),
            Row(false, true, false, true, "pr-default"),
            Row(false, true, true, false, "sv-T"),
            Row(false, true, true, true, "sv-T"),
            Row(true, false, false, false, "pr-T"),
            Row(true, false, false, true, "pr-T"),
            Row(true, false, true, false, "pr-T"),
            Row(true, false, true, true, "pr-T"),
            Row(true, true, false, false, "pr-T"),
            Row(true, true, false, true, "pr-T"),
            Row(true, true, true, false, "pr-T"),
            Row(true, true, true, true, "pr-T")
        )

    private fun rowDocs(row: Row): Pair<ConfigDocument, ConfigDocument> {
        val pr = mutableMapOf<String, WorkItemSchema>()
        val sv = mutableMapOf<String, WorkItemSchema>()
        if (row.prType) pr["T"] = originSchema("pr-T", "T")
        if (row.prDefault) pr["default"] = originSchema("pr-default", "default")
        if (row.svType) sv["T"] = originSchema("sv-T", "T")
        if (row.svDefault) sv["default"] = originSchema("sv-default", "default")
        return ConfigDocument(workItemSchemas = pr, traits = emptyMap()) to ConfigDocument(workItemSchemas = sv, traits = emptyMap())
    }

    @Test
    fun `S1 all 16 rows - per-root type then server type then per-root default then server default then null`(): Unit =
        runBlocking {
            assertEquals(16, rows.size, "sanity: the table covers every combination")
            assertEquals(
                16,
                rows.map { listOf(it.prType, it.prDefault, it.svType, it.svDefault) }.toSet().size,
                "sanity: rows are distinct"
            )
            for (row in rows) {
                val rootId = UUID.randomUUID()
                val (prDoc, svDoc) = rowDocs(row)
                val resolver = serverResolver(svDoc, rootId, prDoc)
                val label = "row $row"

                val match = resolver.resolveSchemaWithSource(item("T", rootId))
                val viaType = resolver.resolveTypeSchema("T", rootId)
                val resolved = resolver.resolveSchema(item("T", rootId))

                if (row.expectedOrigin == null) {
                    assertNull(match, label)
                    assertNull(viaType, label)
                    assertNull(resolved, label)
                } else {
                    assertNotNull(match, label)
                    assertEquals(listOf("origin-${row.expectedOrigin}"), match.schema.notes.map { it.key }, label)
                    val expectedSource = if (row.expectedOrigin.startsWith("pr-")) ConfigSource.PER_ROOT else ConfigSource.GLOBAL
                    val expectedFp = if (row.expectedOrigin.startsWith("pr-")) PR_FP else SV_FP
                    assertEquals(expectedSource, match.source, label)
                    assertEquals(expectedFp, match.fingerprint, label)

                    assertNotNull(viaType, label)
                    assertEquals(listOf("origin-${row.expectedOrigin}"), viaType.schema.notes.map { it.key }, "resolveTypeSchema $label")
                    assertEquals(expectedSource, viaType.source, "resolveTypeSchema $label")

                    assertNotNull(resolved, label)
                    assertEquals(listOf("origin-${row.expectedOrigin}"), resolved.notes.map { it.key }, "resolveSchema $label")
                }
            }
        }

    // ------------------------------------------------------------------
    // S2 - AR-39: a server exact type beats a per-root default
    // ------------------------------------------------------------------

    @Test
    fun `S2 per-root has only default and server has the type - server type wins`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val prDoc = ConfigDocument(mapOf("default" to originSchema("pr-default", "default")), emptyMap())
            val svDoc =
                ConfigDocument(
                    mapOf("bug" to originSchema("sv-bug", "bug"), "default" to originSchema("sv-default", "default")),
                    emptyMap()
                )
            val resolver = serverResolver(svDoc, rootId, prDoc)

            val match = resolver.resolveSchemaWithSource(item("bug", rootId))

            assertNotNull(match)
            assertEquals(listOf("origin-sv-bug"), match.schema.notes.map { it.key })
            assertEquals(ConfigSource.GLOBAL, match.source)
        }

    // ------------------------------------------------------------------
    // S3 - tags are never consulted
    // ------------------------------------------------------------------

    private fun tagDocs(
        prDefault: Boolean,
        svDefault: Boolean
    ): Pair<ConfigDocument, ConfigDocument> {
        val pr = mutableMapOf("k" to originSchema("pr-k", "k"))
        val sv = mutableMapOf("k" to originSchema("sv-k", "k"))
        if (prDefault) pr["default"] = originSchema("pr-default", "default")
        if (svDefault) sv["default"] = originSchema("sv-default", "default")
        return ConfigDocument(pr, emptyMap()) to ConfigDocument(sv, emptyMap())
    }

    @Test
    fun `S3 null type with a tag naming a schema in both layers resolves through the default chain only`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()

            val (prBoth, svBoth) = tagDocs(prDefault = true, svDefault = true)
            val both = serverResolver(svBoth, rootId, prBoth).resolveSchemaWithSource(item(null, rootId, tags = "k"))
            assertNotNull(both)
            assertEquals(listOf("origin-pr-default"), both.schema.notes.map { it.key })
            assertEquals(ConfigSource.PER_ROOT, both.source)

            val (prNone, svOnly) = tagDocs(prDefault = false, svDefault = true)
            val serverDefault = serverResolver(svOnly, rootId, prNone).resolveSchemaWithSource(item(null, rootId, tags = "k"))
            assertNotNull(serverDefault)
            assertEquals(listOf("origin-sv-default"), serverDefault.schema.notes.map { it.key })
            assertEquals(ConfigSource.GLOBAL, serverDefault.source)

            val (prNoDefault, svNoDefault) = tagDocs(prDefault = false, svDefault = false)
            val resolver = serverResolver(svNoDefault, rootId, prNoDefault)
            assertNull(resolver.resolveSchemaWithSource(item(null, rootId, tags = "k")), "no default anywhere: null, not the tag's schema")
            assertNull(resolver.resolveSchema(item(null, rootId, tags = "k")))
        }

    @Test
    fun `S3 a typed item with an unknown type and a tag naming a schema falls to the default chain`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val (prDoc, svDoc) = tagDocs(prDefault = false, svDefault = true)
            val resolver = serverResolver(svDoc, rootId, prDoc)

            val match = resolver.resolveSchemaWithSource(item("no-such-type", rootId, tags = "k"))

            assertNotNull(match)
            assertEquals(listOf("origin-sv-default"), match.schema.notes.map { it.key })
        }

    // ------------------------------------------------------------------
    // S4 - schema_resolution parses with a removal warning and has no effect
    // ------------------------------------------------------------------

    private fun parsePerRoot(yaml: String): ConfigDocument {
        @Suppress("UNCHECKED_CAST")
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any>>(yaml) ?: emptyMap()
        return YamlSchemaParser.parseRoot(root, warnOnMissingSchemas = false)
    }

    @Test
    fun `S4 a per-root document with schema_resolution warns about the removed key and a server-only type resolves to the server`(): Unit =
        runBlocking {
            for (value in listOf("isolated", "legacy", "layered")) {
                val yaml =
                    """
                    schema_resolution: $value
                    work_item_schemas:
                      default:
                        notes:
                          - key: origin-pr-default
                            role: queue
                    """.trimIndent()
                val prDoc = parsePerRoot(yaml)
                val named = prDoc.warnings.filter { it.contains("schema_resolution") }
                assertEquals(1, named.size, "value '$value': exactly one warning names the key, got ${prDoc.warnings}")
                assertTrue(named.single().contains("removed"), "value '$value': warning says the key was removed: ${named.single()}")

                val rootId = UUID.randomUUID()
                val svDoc = ConfigDocument(mapOf("srv-only" to originSchema("sv-srv-only", "srv-only")), emptyMap())
                val resolver = serverResolver(svDoc, rootId, prDoc)

                val match = resolver.resolveSchemaWithSource(item("srv-only", rootId))
                assertNotNull(match, "value '$value'")
                assertEquals(
                    listOf("origin-sv-srv-only"),
                    match.schema.notes.map { it.key },
                    "value '$value': the removed mode must not change precedence"
                )
                assertEquals(ConfigSource.GLOBAL, match.source, "value '$value'")
            }
        }

    // ------------------------------------------------------------------
    // S5 - item traits before default traits; base notes first
    // ------------------------------------------------------------------

    @Test
    fun `S5 item trait and default trait both declare a note - the item trait's note wins and follows base notes`(): Unit =
        runBlocking {
            // Names chosen so alphabetical order would favour the default trait.
            val svDoc =
                ConfigDocument(
                    workItemSchemas =
                        mapOf(
                            "T" to schema("T", listOf(note("base-note", description = "base")), defaultTraits = listOf("trait-a-default"))
                        ),
                    traits =
                        linkedMapOf(
                            "trait-z-item" to
                                listOf(
                                    note(
                                        "k",
                                        role = Role.QUEUE,
                                        description = "from-item",
                                        guidance = "g-item",
                                        skill = "skill-item",
                                        maxLength = 100
                                    )
                                ),
                            "trait-a-default" to
                                listOf(
                                    note(
                                        "k",
                                        role = Role.WORK,
                                        description = "from-default",
                                        guidance = "g-default",
                                        skill = "skill-default",
                                        maxLength = 200
                                    ),
                                    note("extra-default")
                                )
                        )
                )
            val resolver = serverResolver(svDoc)

            val resolved = resolver.resolveSchema(item("T", traits = listOf("trait-z-item")))

            assertNotNull(resolved)
            assertEquals(listOf("base-note", "k", "extra-default"), resolved.notes.map { it.key })
            val k = resolved.notes.single { it.key == "k" }
            assertEquals("from-item", k.description)
            assertEquals(Role.QUEUE, k.role)
            assertEquals("g-item", k.guidance)
            assertEquals("skill-item", k.skill)
            assertEquals(100, k.maxLength)
        }

    // ------------------------------------------------------------------
    // S6 - required is OR'd across every declaration
    // ------------------------------------------------------------------

    private fun requiredFixture(): Pair<EffectiveConfigResolver, WorkItem> {
        val svDoc =
            ConfigDocument(
                workItemSchemas =
                    mapOf(
                        "T" to
                            schema(
                                "T",
                                listOf(
                                    note("k-base-false", required = false, description = "base-desc"),
                                    note("k-base-true", required = true, description = "base-true-desc")
                                ),
                                defaultTraits = listOf("trait-default")
                            )
                    ),
                traits =
                    linkedMapOf(
                        "trait-item" to
                            listOf(
                                note("k-base-false", required = true, description = "trait-desc"),
                                note("k-item-false-default-true", required = false, description = "item-desc"),
                                note("k-none", required = false, description = "none-item")
                            ),
                        "trait-default" to
                            listOf(
                                note("k-base-true", required = false, description = "default-desc"),
                                note("k-item-false-default-true", required = true, description = "default-true-desc"),
                                note("k-none", required = false, description = "none-default")
                            )
                    )
            )
        return serverResolver(svDoc) to item("T", traits = listOf("trait-item"))
    }

    @Test
    fun `S6 base false and trait true is required and the base content is kept`(): Unit =
        runBlocking {
            val (resolver, workItem) = requiredFixture()
            val k = resolver.resolveSchema(workItem)!!.notes.single { n -> n.key == "k-base-false" }
            assertTrue(k.required)
            assertEquals("base-desc", k.description)
        }

    @Test
    fun `S6 base true and trait false stays required`(): Unit =
        runBlocking {
            val (resolver, workItem) = requiredFixture()
            val k = resolver.resolveSchema(workItem)!!.notes.single { n -> n.key == "k-base-true" }
            assertTrue(k.required)
            assertEquals("base-true-desc", k.description)
        }

    @Test
    fun `S6 item trait false and default trait true is required and the item trait content is kept`(): Unit =
        runBlocking {
            val (resolver, workItem) = requiredFixture()
            val k = resolver.resolveSchema(workItem)!!.notes.single { n -> n.key == "k-item-false-default-true" }
            assertTrue(k.required)
            assertEquals("item-desc", k.description)
        }

    @Test
    fun `S6 required is false when no declaration marks it required`(): Unit =
        runBlocking {
            val (resolver, workItem) = requiredFixture()
            val k = resolver.resolveSchema(workItem)!!.notes.single { n -> n.key == "k-none" }
            assertEquals(false, k.required)
            assertEquals("none-item", k.description)
        }

    // ------------------------------------------------------------------
    // S7 - resources: union of keys, EXCLUSIVE beats ADVISORY, ttl first-seen
    // ------------------------------------------------------------------

    private fun resourceDoc(
        itemReq: ResourceRequirement,
        defaultReq: ResourceRequirement
    ) = ConfigDocument(
        workItemSchemas = mapOf("T" to schema("T", defaultTraits = listOf("trait-default"))),
        traits = emptyMap(),
        traitResources =
            linkedMapOf(
                "trait-item" to listOf(itemReq),
                "trait-default" to listOf(defaultReq)
            )
    )

    @Test
    fun `S7 item ADVISORY and default EXCLUSIVE resolves to EXCLUSIVE with the item trait's ttl`(): Unit =
        runBlocking {
            val doc =
                resourceDoc(ResourceRequirement("r", ResourceMode.ADVISORY, 100), ResourceRequirement("r", ResourceMode.EXCLUSIVE, 900))
            val result = serverResolver(doc).resolveResourceRequirements(item("T", traits = listOf("trait-item")))

            assertEquals(listOf(ResourceRequirement("r", ResourceMode.EXCLUSIVE, 100)), result)
        }

    @Test
    fun `S7 item EXCLUSIVE and default ADVISORY stays EXCLUSIVE with the item trait's ttl`(): Unit =
        runBlocking {
            val doc =
                resourceDoc(ResourceRequirement("r", ResourceMode.EXCLUSIVE, 100), ResourceRequirement("r", ResourceMode.ADVISORY, 900))
            val result = serverResolver(doc).resolveResourceRequirements(item("T", traits = listOf("trait-item")))

            assertEquals(listOf(ResourceRequirement("r", ResourceMode.EXCLUSIVE, 100)), result)
        }

    @Test
    fun `S7 resources from different traits are a union of keys`(): Unit =
        runBlocking {
            val doc =
                resourceDoc(
                    ResourceRequirement("r-item", ResourceMode.EXCLUSIVE, 10),
                    ResourceRequirement("r-default", ResourceMode.ADVISORY, 20)
                )
            val result = serverResolver(doc).resolveResourceRequirements(item("T", traits = listOf("trait-item")))

            assertEquals(setOf("r-item", "r-default"), result.map { it.key }.toSet())
            assertEquals(2, result.size)
            assertEquals(ResourceMode.EXCLUSIVE, result.single { it.key == "r-item" }.mode)
            assertEquals(ResourceMode.ADVISORY, result.single { it.key == "r-default" }.mode)
        }

    @Test
    fun `S7 for-type merge over default traits upgrades ADVISORY to EXCLUSIVE in either order with the first-seen ttl`(): Unit =
        runBlocking {
            val doc =
                ConfigDocument(
                    workItemSchemas = emptyMap(),
                    traits = emptyMap(),
                    traitResources =
                        linkedMapOf(
                            "d1-advisory" to listOf(ResourceRequirement("r", ResourceMode.ADVISORY, 111)),
                            "d2-exclusive" to listOf(ResourceRequirement("r", ResourceMode.EXCLUSIVE, 222))
                        )
                )
            val resolver = serverResolver(doc)

            assertEquals(
                listOf(ResourceRequirement("r", ResourceMode.EXCLUSIVE, 111)),
                resolver.resolveResourceRequirementsForType(listOf("d1-advisory", "d2-exclusive"), null)
            )
            assertEquals(
                listOf(ResourceRequirement("r", ResourceMode.EXCLUSIVE, 222)),
                resolver.resolveResourceRequirementsForType(listOf("d2-exclusive", "d1-advisory"), null)
            )
        }

    // ------------------------------------------------------------------
    // S8 - a per-root trait replaces the whole server entry
    // ------------------------------------------------------------------

    private val seatT = SeatDefinition("t-seat", Role.WORK)

    private fun serverTraitDoc() =
        ConfigDocument(
            workItemSchemas = mapOf("T" to schema("T", listOf(note("base-note")), defaultTraits = listOf("t"))),
            traits = mapOf("t" to listOf(note("n-server"))),
            traitResources = mapOf("t" to listOf(ResourceRequirement("rs", ResourceMode.EXCLUSIVE, 60))),
            traitDispatch = mapOf("t" to mapOf(Role.WORK to DispatchProfile(agent = "server-agent"))),
            traitSeats = mapOf("t" to listOf(seatT)),
            traitDispatchBySeat = mapOf("t" to mapOf(Role.WORK to mapOf("t-seat" to SeatDispatchOverride(agent = "seat-agent"))))
        )

    @Test
    fun `S8 control - with no per-root override the server trait contributes every dimension`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val resolver = serverResolver(serverTraitDoc(), rootId, ConfigDocument(emptyMap(), emptyMap()))
            val workItem = item("T", rootId)

            val resolved = resolver.resolveSchema(workItem)!!
            assertEquals(listOf("base-note", "n-server"), resolved.notes.map { n -> n.key })
            assertEquals(listOf("t-seat"), resolved.seats.map { s -> s.name })
            assertEquals(listOf("rs"), resolver.resolveResourceRequirements(workItem).map { r -> r.key })
            assertEquals("server-agent", resolver.resolveDispatchProfile(workItem, Role.WORK, resolved)?.agent)
            assertEquals("seat-agent", resolver.resolveDispatchBySeat(workItem, resolved)[Role.WORK]?.get("t-seat")?.agent)
        }

    @Test
    fun `S8 per-root trait with notes only takes the notes and leaves every other dimension empty`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val prDoc = ConfigDocument(emptyMap(), traits = mapOf("t" to listOf(note("n-root"))))
            val resolver = serverResolver(serverTraitDoc(), rootId, prDoc)
            val workItem = item("T", rootId)

            val resolved = resolver.resolveSchema(workItem)!!
            assertEquals(listOf("base-note", "n-root"), resolved.notes.map { n -> n.key })
            assertTrue(resolved.seats.none { s -> s.name == "t-seat" }, "seats: ${resolved.seats}")
            assertEquals(emptyList(), resolver.resolveResourceRequirements(workItem))
            assertNull(resolver.resolveDispatchProfile(workItem, Role.WORK, resolved))
            assertEquals(emptyMap(), resolver.resolveDispatchProfiles(workItem, resolved))
            assertEquals(emptyMap(), resolver.resolveDispatchBySeat(workItem, resolved))

            // The for-type variants obey the same whole-entry rule.
            assertEquals(emptyList(), resolver.resolveResourceRequirementsForType(listOf("t"), rootId))
            assertEquals(emptyMap(), resolver.resolveDispatchProfilesForType(listOf("t"), rootId))
            assertEquals(emptyMap(), resolver.resolveDispatchBySeatForType(listOf("t"), listOf(seatT), rootId))
        }

    @Test
    fun `S8 per-root trait declaring only resources takes those resources and none of the server's other dimensions`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val prDoc =
                ConfigDocument(
                    emptyMap(),
                    traits = mapOf("t" to emptyList()),
                    traitResources = mapOf("t" to listOf(ResourceRequirement("rp", ResourceMode.ADVISORY, 5)))
                )
            val resolver = serverResolver(serverTraitDoc(), rootId, prDoc)
            val workItem = item("T", rootId)

            val resolved = resolver.resolveSchema(workItem)!!
            assertEquals(listOf(ResourceRequirement("rp", ResourceMode.ADVISORY, 5)), resolver.resolveResourceRequirements(workItem))
            assertEquals(listOf("base-note"), resolved.notes.map { n -> n.key })
            assertNull(resolver.resolveDispatchProfile(workItem, Role.WORK, resolved))
        }

    @Test
    fun `S8 probe - a per-root trait with empty notes still shadows the server trait`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val prDoc = ConfigDocument(emptyMap(), traits = mapOf("t" to emptyList()))
            val resolver = serverResolver(serverTraitDoc(), rootId, prDoc)

            val resolved = resolver.resolveSchema(item("T", rootId))!!

            assertEquals(listOf("base-note"), resolved.notes.map { n -> n.key })
        }

    // ------------------------------------------------------------------
    // S9 - resource registry is server-only
    // ------------------------------------------------------------------

    @Test
    fun `S9 resource registry returns the server entries only`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val prDoc =
                ConfigDocument(
                    emptyMap(),
                    emptyMap(),
                    resourceRegistry =
                        mapOf(
                            "x" to ResourceDefinition("x", "per-root x"),
                            "z" to ResourceDefinition("z", "per-root z")
                        )
                )
            val svDoc =
                ConfigDocument(
                    emptyMap(),
                    emptyMap(),
                    resourceRegistry =
                        mapOf(
                            "y" to ResourceDefinition("y", "server y"),
                            "z" to ResourceDefinition("z", "server z")
                        )
                )
            val resolver = serverResolver(svDoc, rootId, prDoc)

            val registry = resolver.resolveResourceRegistry(rootId)

            assertEquals(setOf("y", "z"), registry.keys)
            assertEquals("server z", registry.getValue("z").description)
            assertEquals("server y", registry.getValue("y").description)
            assertEquals(setOf("y", "z"), resolver.resolveResourceRegistry(null).keys)
        }

    // ------------------------------------------------------------------
    // S10 / S11 - item traits beat default traits for dispatch and seats; base seats beat both
    // ------------------------------------------------------------------

    private val itemProfile = DispatchProfile(agent = "item-agent", model = "item-model", effort = "low")
    private val defaultProfile = DispatchProfile(agent = "default-agent", model = "default-model", effort = "high")

    private fun dispatchSeatDoc() =
        ConfigDocument(
            workItemSchemas =
                mapOf(
                    "T" to schema("T", defaultTraits = listOf("trait-default"), seats = listOf(SeatDefinition("s", Role.WORK)))
                ),
            traits = mapOf("trait-item" to emptyList(), "trait-default" to emptyList()),
            traitDispatch =
                linkedMapOf(
                    "trait-item" to mapOf(Role.WORK to itemProfile),
                    "trait-default" to mapOf(Role.WORK to defaultProfile)
                ),
            traitDispatchBySeat =
                linkedMapOf(
                    "trait-item" to mapOf(Role.WORK to mapOf("s" to SeatDispatchOverride(agent = "item-seat-agent"))),
                    "trait-default" to mapOf(Role.WORK to mapOf("s" to SeatDispatchOverride(agent = "default-seat-agent")))
                )
        )

    @Test
    fun `S10 item trait dispatch beats the default trait's for the role`(): Unit =
        runBlocking {
            val resolver = serverResolver(dispatchSeatDoc())
            val workItem = item("T", traits = listOf("trait-item"))
            val resolved = resolver.resolveSchema(workItem)

            assertEquals(itemProfile, resolver.resolveDispatchProfile(workItem, Role.WORK, resolved))
            assertEquals(itemProfile, resolver.resolveDispatchProfiles(workItem, resolved)[Role.WORK])
        }

    @Test
    fun `S10 item trait seat dispatch beats the default trait's for the same seat`(): Unit =
        runBlocking {
            val resolver = serverResolver(dispatchSeatDoc())
            val workItem = item("T", traits = listOf("trait-item"))
            val resolved = resolver.resolveSchema(workItem)

            assertEquals("item-seat-agent", resolver.resolveDispatchBySeat(workItem, resolved)[Role.WORK]?.get("s")?.agent)
        }

    @Test
    fun `S10 for-type dispatch with two traits declaring the same role takes the first trait's profile`(): Unit =
        runBlocking {
            val resolver = serverResolver(dispatchSeatDoc())

            assertEquals(itemProfile, resolver.resolveDispatchProfilesForType(listOf("trait-item", "trait-default"), null)[Role.WORK])
            assertEquals(defaultProfile, resolver.resolveDispatchProfilesForType(listOf("trait-default", "trait-item"), null)[Role.WORK])
            assertEquals(
                "item-seat-agent",
                resolver
                    .resolveDispatchBySeatForType(
                        listOf("trait-item", "trait-default"),
                        listOf(SeatDefinition("s", Role.WORK)),
                        null
                    )[Role.WORK]
                    ?.get("s")
                    ?.agent
            )
        }

    private fun seatDoc(baseSeats: List<SeatDefinition>) =
        ConfigDocument(
            workItemSchemas = mapOf("T" to schema("T", defaultTraits = listOf("trait-default"), seats = baseSeats)),
            traits = mapOf("trait-item" to emptyList(), "trait-default" to emptyList()),
            traitSeats =
                linkedMapOf(
                    "trait-item" to listOf(SeatDefinition("s", Role.WORK)),
                    "trait-default" to listOf(SeatDefinition("s", Role.REVIEW))
                )
        )

    @Test
    fun `S11 item trait seat beats the default trait seat of the same name`(): Unit =
        runBlocking {
            val resolved = serverResolver(seatDoc(emptyList())).resolveSchema(item("T", traits = listOf("trait-item")))!!

            val s = resolved.seats.filter { it.name == "s" }
            assertEquals(1, s.size, "seats: ${resolved.seats}")
            assertEquals(Role.WORK, s.single().phase)
        }

    @Test
    fun `S11 a base seat beats both trait seats of the same name`(): Unit =
        runBlocking {
            val resolved =
                serverResolver(seatDoc(listOf(SeatDefinition("s", Role.QUEUE)))).resolveSchema(item("T", traits = listOf("trait-item")))!!

            val s = resolved.seats.filter { it.name == "s" }
            assertEquals(1, s.size, "seats: ${resolved.seats}")
            assertEquals(Role.QUEUE, s.single().phase)
        }

    // ------------------------------------------------------------------
    // S12 - schema-free item: no trait notes, but trait resources and dispatch still apply
    // ------------------------------------------------------------------

    @Test
    fun `S12 an item with no schema resolves null but still gets its trait's resources and dispatch`(): Unit =
        runBlocking {
            val svDoc =
                ConfigDocument(
                    workItemSchemas = emptyMap(),
                    traits = mapOf("t" to listOf(note("n-t"))),
                    traitResources = mapOf("t" to listOf(ResourceRequirement("r", ResourceMode.EXCLUSIVE, 30))),
                    traitDispatch = mapOf("t" to mapOf(Role.WORK to DispatchProfile(agent = "t-agent")))
                )
            val resolver = serverResolver(svDoc)
            val workItem = item("no-schema-type", traits = listOf("t"))

            assertNull(resolver.resolveSchema(workItem))
            assertEquals(listOf(ResourceRequirement("r", ResourceMode.EXCLUSIVE, 30)), resolver.resolveResourceRequirements(workItem))
            assertEquals("t-agent", resolver.resolveDispatchProfile(workItem, Role.WORK, null)?.agent)
        }

    // ------------------------------------------------------------------
    // S13 - availableTypes
    // ------------------------------------------------------------------

    @Test
    fun `S13 availableTypes is the sorted distinct union of per-root and server schema keys`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val prDoc =
                ConfigDocument(
                    linkedMapOf(
                        "zeta" to schema("zeta"),
                        "bravo" to schema("bravo"),
                        "shared" to schema("shared")
                    ),
                    emptyMap()
                )
            val svDoc = ConfigDocument(linkedMapOf("shared" to schema("shared"), "alpha" to schema("alpha")), emptyMap())
            val resolver = serverResolver(svDoc, rootId, prDoc)

            assertEquals(listOf("alpha", "bravo", "shared", "zeta"), resolver.availableTypes(rootId))
            assertEquals(listOf("alpha", "shared"), resolver.availableTypes(null))
            assertEquals(
                listOf("alpha", "shared"),
                resolver.availableTypes(UUID.randomUUID()),
                "a root with no per-root row sees the server keys only"
            )
        }

    // ------------------------------------------------------------------
    // Probes
    // ------------------------------------------------------------------

    @Test
    fun `probe a trait named in both the item list and default_traits is merged once at the item position`(): Unit =
        runBlocking {
            val svDoc =
                ConfigDocument(
                    workItemSchemas = mapOf("T" to schema("T", listOf(note("base-note")), defaultTraits = listOf("tx", "ty"))),
                    traits = linkedMapOf("tx" to listOf(note("nx")), "ty" to listOf(note("ny")))
                )

            val resolved = serverResolver(svDoc).resolveSchema(item("T", traits = listOf("ty")))!!

            assertEquals(listOf("base-note", "ny", "nx"), resolved.notes.map { it.key })
        }

    @Test
    fun `probe a trait unknown to both layers is skipped without error`(): Unit =
        runBlocking {
            val svDoc =
                ConfigDocument(mapOf("T" to schema("T", listOf(note("base-note")), defaultTraits = listOf("ghost-default"))), emptyMap())

            val resolved = serverResolver(svDoc).resolveSchema(item("T", traits = listOf("ghost-item")))!!

            assertEquals(listOf("base-note"), resolved.notes.map { it.key })
        }

    @Test
    fun `probe type lookup is case sensitive - Bug does not match bug and falls to the default chain`(): Unit =
        runBlocking {
            val svDoc =
                ConfigDocument(
                    mapOf("bug" to originSchema("sv-bug", "bug"), "default" to originSchema("sv-default", "default")),
                    emptyMap()
                )

            val match = serverResolver(svDoc).resolveSchemaWithSource(item("Bug"))

            assertNotNull(match)
            assertEquals(listOf("origin-sv-default"), match.schema.notes.map { it.key })
        }

    @Test
    fun `probe no per-root row and an empty per-root document resolve identically to the server`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val svDoc = serverTraitDoc()
            val noRow = TestConfigResolver.resolver(svDoc, FakePerRoot(mapOf(rootId to null)), SV_FP)
            val emptyDoc = serverResolver(svDoc, rootId, ConfigDocument(emptyMap(), emptyMap()))
            val workItem = item("T", rootId)

            val a = noRow.resolveSchema(workItem)
            val b = emptyDoc.resolveSchema(workItem)

            assertEquals(listOf("base-note", "n-server"), a!!.notes.map { n -> n.key })
            assertEquals(a, b)
            assertEquals(noRow.resolveResourceRequirements(workItem), emptyDoc.resolveResourceRequirements(workItem))
        }

    @Test
    fun `probe an item with no root id ignores per-root config`(): Unit =
        runBlocking {
            val otherRoot = UUID.randomUUID()
            val prDoc = ConfigDocument(mapOf("T" to originSchema("pr-T", "T")), emptyMap())
            val svDoc = ConfigDocument(mapOf("T" to originSchema("sv-T", "T")), emptyMap())
            val resolver = TestConfigResolver.resolver(svDoc, FakePerRoot(mapOf(otherRoot to perRootLayer(prDoc))), SV_FP)

            val match = resolver.resolveSchemaWithSource(item("T", rootId = null))

            assertNotNull(match)
            assertEquals(listOf("origin-sv-T"), match.schema.notes.map { it.key })
        }

    @Test
    fun `probe resolving twice gives the same answer`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val (prDoc, svDoc) = rowDocs(Row(false, true, true, true, "sv-T"))
            val resolver = serverResolver(svDoc, rootId, prDoc)
            val workItem = item("T", rootId, traits = listOf("ghost"))

            assertEquals(resolver.resolveSchemaWithSource(workItem), resolver.resolveSchemaWithSource(workItem))
            assertEquals(resolver.resolveSchema(workItem), resolver.resolveSchema(workItem))
        }

    private companion object {
        const val PR_FP = "pr-fp"
        const val SV_FP = "sv-fp"
    }
}
