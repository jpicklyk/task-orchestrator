package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.config.CanonicalConfigJson
import io.github.jpicklyk.mcptask.current.application.config.ConfigValidator
import io.github.jpicklyk.mcptask.current.application.port.ConfigAppend
import io.github.jpicklyk.mcptask.current.application.port.ConfigFileSource
import io.github.jpicklyk.mcptask.current.application.port.ConfigStore
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepException
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepKind
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepPhase
import io.github.jpicklyk.mcptask.current.application.upgrade.steps.ServerConfigImportStep
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.ConfigAppendOutcome
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.ConfigVersionSource
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.NetworkntConfigStructuralValidator
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.infrastructure.security.configFingerprint
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.test.StrictConfigCases
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item ad684e46 / Q17) for [ConfigService.importDocument], [ConfigService.serverHead] and
 * the [ServerConfigImportStep] that drives them, over a real SQLite database and the production validator wiring.
 *
 * Scenarios: S1 and S7 (first import, identical re-import, changed re-import, empty and comment-only files), S2 (project.rootId
 * targets), S3 (the U20 allowance), S4 (rejections: ParseError, Invalid, RootNotFound, TooLarge), the ROOT-scope server-only rules (D8), the stored advisory warnings, and the step's own contract
 * (metadata, return values, exception texts).
 *
 * Oracles: task-scope section 1.1 (the step and its exception texts), section 1.2 (the six pipeline steps and the result
 * types, decisions D1 to D5 and D8) and AC1 to AC4. Canonical JSON is written by hand from the codec rule (sorted keys) as
 * ConfigServiceVersioningTest pins it. Fingerprints come from `configFingerprint` and the store's pure `computeFingerprint`.
 * Every rejection scenario asserts that nothing was stored and has a same-fixture control that is admitted.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ConfigServiceImportTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val provider get() = sqlite.repositoryProvider()
    private val store: ConfigStore get() = provider.configStore()

    private val service: ConfigService by lazy {
        ConfigService(
            provider,
            YamlConfigDocumentParser,
            sqlite.unitOfWork(),
            ConfigValidator(NetworkntConfigStructuralValidator()),
            serverConfig = PerRootConfigService(store)
        )
    }

    private val base =
        "work_item_schemas:\n  feature-task:\n    notes:\n      - key: spec\n        role: queue\n        required: true\n"

    private val baseCanonical =
        "{\"work_item_schemas\":{\"feature-task\":{\"notes\":[{\"key\":\"spec\",\"required\":true,\"role\":\"queue\"}]}}}"

    private val migrationOnlyKeys = listOf("orchestration", "retrospective", "actor_attribution", "schema_resolution")

    private val migrationOnlyYaml =
        mapOf(
            "orchestration" to "orchestration:\n  mode: workflow\n",
            "retrospective" to "retrospective:\n  mode: dispatch\n",
            "actor_attribution" to "actor_attribution:\n  required: true\n",
            "schema_resolution" to "schema_resolution: legacy\n"
        )

    private suspend fun import(
        text: String,
        using: ConfigService = service
    ): ConfigImportResult =
        when (val outcome = sqlite.unitOfWork().write("ConfigServiceImportTest.import") { Outcome.Ok(using.importDocument(text)) }) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("the write unit failed: ${outcome.error.message}")
        }

    private suspend fun newRoot(
        id: UUID = UUID.randomUUID(),
        title: String = "Root"
    ): WorkItem = provider.workItemRepository().create(WorkItem(id = id, title = title, type = "project", depth = 0))

    private suspend fun configEvents(): List<EventRecord> =
        provider.eventStore().readAfter(0L, null, 100_000).filter { it.type == "config.version_created" }

    private suspend fun serverHistorySize() = store.history(ConfigScope.SERVER, null, 100).size

    private suspend fun assertNothingStored() {
        assertNull(store.head(ConfigScope.SERVER, null), "no server head")
        assertEquals(0, serverHistorySize(), "no server version")
    }

    private fun projectFile(
        rootId: String,
        rest: String = base
    ) = "project:\n  rootId: \"$rootId\"\n$rest"

    // ------------------------------------------------------------------------------------------------ S1: first import

    @Test
    fun `S1 a strict-valid document with no project rootId becomes server version 1, verbatim`(): Unit =
        runBlocking {
            val result = import(base)

            val imported = assertIs<ConfigImportResult.Imported>(result)
            assertTrue(imported.appended)
            val v = imported.version
            assertEquals(base, v.yaml)
            assertEquals(configFingerprint(base), v.fingerprint)
            assertEquals(store.computeFingerprint(base), v.fingerprint)
            assertEquals(baseCanonical, v.canonicalJson)
            assertEquals(1, v.modelVersion)
            assertEquals(ConfigVersionSource.FILE_IMPORT, v.source)
            assertNull(v.principalId)
            assertNull(v.reqId)
            assertEquals(emptyList(), v.warnings)
            assertEquals(emptyList(), imported.warnings)
            val head = assertNotNull(store.head(ConfigScope.SERVER, null))
            assertEquals(1, head.version)
            assertEquals(v.id, head.versionId)
            assertEquals(1, serverHistorySize())
        }

    @Test
    fun `S1 a server import records no event`(): Unit =
        runBlocking {
            assertIs<ConfigImportResult.Imported>(import(base))

            assertEquals(emptyList(), configEvents())
        }

    @Test
    fun `S1 serverHead is the stored server version, and null before any import`(): Unit =
        runBlocking {
            assertNull(service.serverHead(), "no head yet")

            val imported = assertIs<ConfigImportResult.Imported>(import(base))

            val head = assertNotNull(service.serverHead())
            assertEquals(imported.version.id, head.id)
            assertEquals(base, head.yaml)
            assertEquals(configFingerprint(base), head.fingerprint)
        }

    // ------------------------------------------------------------------------------------------------ S7: idempotence

    @Test
    fun `S7 importing identical content again appends nothing`(): Unit =
        runBlocking {
            val first = assertIs<ConfigImportResult.Imported>(import(base))

            val second = assertIs<ConfigImportResult.Imported>(import(base))

            assertFalse(second.appended)
            assertEquals(first.version.id, second.version.id)
            assertEquals(1, serverHistorySize())
            assertEquals(1, assertNotNull(store.head(ConfigScope.SERVER, null)).version)
        }

    @Test
    fun `S7 changed content appends version 2 and the head moves`(): Unit =
        runBlocking {
            assertIs<ConfigImportResult.Imported>(import(base))
            val changed = base + "# second revision\n"

            val second = assertIs<ConfigImportResult.Imported>(import(changed))

            assertTrue(second.appended)
            assertEquals(2, serverHistorySize())
            val head = assertNotNull(store.head(ConfigScope.SERVER, null))
            assertEquals(2, head.version)
            assertEquals(second.version.id, head.versionId)
            assertEquals(changed, assertNotNull(service.serverHead()).yaml)
        }

    @Test
    fun `S7 an older document imported after a newer one is appended again, not rejected as superseded`(): Unit =
        runBlocking {
            val other = "work_item_schemas:\n  other-task:\n    notes: []\n"
            assertIs<ConfigImportResult.Imported>(import(base))
            assertIs<ConfigImportResult.Imported>(import(other))

            val back = assertIs<ConfigImportResult.Imported>(import(base))

            assertTrue(back.appended, "a file-import version is never rejected as superseded")
            assertEquals(3, assertNotNull(store.head(ConfigScope.SERVER, null)).version)
            assertEquals(3, serverHistorySize())
        }

    @Test
    fun `S7 an empty, comment-only or blank document is Skipped and stores nothing`(): Unit =
        runBlocking {
            for (text in listOf("", "# only a comment\n", "\n  \n")) {
                assertEquals(ConfigImportResult.Skipped, import(text), "text: '$text'")
            }
            assertNothingStored()
        }

    @Test
    fun `S7 Skipped leaves an existing head alone`(): Unit =
        runBlocking {
            val first = assertIs<ConfigImportResult.Imported>(import(base))

            assertEquals(ConfigImportResult.Skipped, import(""))

            assertEquals(first.version.id, assertNotNull(store.head(ConfigScope.SERVER, null)).versionId)
            assertEquals(1, serverHistorySize())
        }

    @Test
    fun `S7 a RESET server head is absence for serverHead and the layer, and a later import appends after it`(): Unit =
        runBlocking {
            assertIs<ConfigImportResult.Imported>(import(base))
            val reset =
                store.append(
                    scope = ConfigScope.SERVER,
                    rootId = null,
                    append =
                        ConfigAppend(
                            yaml = "",
                            fingerprint = store.computeFingerprint(""),
                            canonicalJson = CanonicalConfigJson.encode(null),
                            modelVersion = CanonicalConfigJson.MODEL_VERSION,
                            source = ConfigVersionSource.RESET,
                            principalId = null,
                            reqId = null,
                            warnings = emptyList()
                        ),
                    now = Instant.now()
                )
            assertIs<ConfigAppendOutcome.Applied>(reset)

            assertNull(service.serverHead(), "a reset head is no head")
            assertNull(PerRootConfigService(store).layer(), "and no server layer")

            val again = assertIs<ConfigImportResult.Imported>(import(base))
            assertTrue(again.appended, "importing after a reset appends")
            assertEquals(3, assertNotNull(store.head(ConfigScope.SERVER, null)).version)
        }

    // ------------------------------------------------------------------------------------------------ S4: rejections

    @Test
    fun `S4 unparseable yaml is a ParseError with a detail, and nothing is stored`(): Unit =
        runBlocking {
            val result = import("work_item_schemas: [\ninvalid yaml: :\n  - broken")

            val parseError = assertIs<ConfigImportResult.ParseError>(result)
            assertTrue(parseError.detail.isNotBlank())
            assertNothingStored()
        }

    @Test
    fun `S4 an unknown top-level key is Invalid at its path, and nothing is stored`(): Unit =
        runBlocking {
            val result = import("bogus: 1\n" + base)

            val invalid = assertIs<ConfigImportResult.Invalid>(result)
            assertTrue("/bogus" in invalid.errors.map { it.path }, "errors: ${invalid.errors}")
            assertTrue(invalid.errors.all { it.reason.isNotBlank() })
            assertNothingStored()
            assertIs<ConfigImportResult.Imported>(import(base), "control: the same document without the key is admitted")
        }

    @Test
    fun `S4 every error is reported, not only the first`(): Unit =
        runBlocking {
            val invalid = assertIs<ConfigImportResult.Invalid>(import("bogus: 1\nbogus-two: 2\n" + base))

            val paths = invalid.errors.map { it.path }
            assertTrue("/bogus" in paths && "/bogus-two" in paths, "errors: ${invalid.errors}")
        }

    @Test
    fun `S4 the size cap - exactly the maximum is admitted, one byte more is TooLarge`(): Unit =
        runBlocking {
            val max = ConfigService.MAX_CONFIG_YAML_BYTES
            val head = base + "#"
            val atCap = head + "x".repeat(max - head.length)
            assertEquals(max, atCap.toByteArray(Charsets.UTF_8).size, "fixture: exactly at the cap")

            assertIs<ConfigImportResult.Imported>(import(atCap))

            val tooLarge = assertIs<ConfigImportResult.TooLarge>(import(atCap + "x"))
            assertEquals(max + 1, tooLarge.sizeBytes)
            assertEquals(max, tooLarge.maxBytes)
            assertEquals(1, serverHistorySize(), "only the document at the cap was stored")
        }

    @Test
    fun `S4 the cap counts UTF-8 bytes, not characters`(): Unit =
        runBlocking {
            val max = ConfigService.MAX_CONFIG_YAML_BYTES
            val head = base + "#"
            val text = head + "\u00e9".repeat((max - head.length) / 2 + 1)
            assertTrue(text.length <= max, "fixture: within the cap by character count")
            assertTrue(text.toByteArray(Charsets.UTF_8).size > max, "fixture: over the cap by byte count")

            val tooLarge = assertIs<ConfigImportResult.TooLarge>(import(text))

            assertEquals(text.toByteArray(Charsets.UTF_8).size, tooLarge.sizeBytes)
            assertEquals(max, tooLarge.maxBytes)
            assertNothingStored()
        }

    // ------------------------------------------------------------------------------------------------ S3: the U20 allowance

    @Test
    fun `S3 each migration-only key is admitted with exactly one stored warning naming it`(): Unit =
        runBlocking {
            for (key in migrationOnlyKeys) {
                val text = migrationOnlyYaml.getValue(key) + base + "# $key\n"

                val imported = assertIs<ConfigImportResult.Imported>(import(text), "key $key")

                assertTrue(imported.appended)
                assertEquals(1, imported.warnings.size, "exactly one warning for $key: ${imported.warnings}")
                assertTrue(key in imported.warnings.single(), "the warning names '$key': ${imported.warnings}")
                assertEquals(imported.warnings, imported.version.warnings, "the stored warnings are the reported warnings")
            }
        }

    @Test
    fun `S3 all four keys together give four warnings`(): Unit =
        runBlocking {
            val text = migrationOnlyKeys.joinToString("") { migrationOnlyYaml.getValue(it) } + base

            val imported = assertIs<ConfigImportResult.Imported>(import(text))

            assertEquals(4, imported.warnings.size, "warnings: ${imported.warnings}")
            for (key in migrationOnlyKeys) assertTrue(imported.warnings.any { key in it }, "no warning names '$key': ${imported.warnings}")
            assertEquals(text, imported.version.yaml, "the legacy keys stay in the verbatim text")
        }

    @Test
    fun `S3 the allowance is exactly the four root pointers - a cased or nested spelling is still an error`(): Unit =
        runBlocking {
            val cased = assertIs<ConfigImportResult.Invalid>(import("Orchestration:\n  mode: workflow\n" + base))
            assertTrue("/Orchestration" in cased.errors.map { it.path }, "errors: ${cased.errors}")

            val nested = assertIs<ConfigImportResult.Invalid>(import("project:\n  orchestration:\n    mode: workflow\n" + base))
            assertTrue(nested.errors.any { "orchestration" in it.path }, "errors: ${nested.errors}")

            assertNothingStored()
        }

    @Test
    fun `S3 an allowed key does not excuse another error, and its pointer is not reported as an error`(): Unit =
        runBlocking {
            val invalid = assertIs<ConfigImportResult.Invalid>(import("schema_resolution: legacy\nbogus: 1\n" + base))

            val paths = invalid.errors.map { it.path }
            assertTrue("/bogus" in paths, "errors: ${invalid.errors}")
            assertFalse("/schema_resolution" in paths, "the allowance removed it from the errors: ${invalid.errors}")
            assertNothingStored()
        }

    @Test
    fun `S3 a plugin key under extensions is admitted without a warning`(): Unit =
        runBlocking {
            val imported =
                assertIs<ConfigImportResult.Imported>(
                    import(
                        "extensions:\n  claude-code:\n    orchestration:\n      mode: workflow\n" + base
                    )
                )

            assertEquals(emptyList(), imported.warnings)
        }

    @Test
    fun `S3 the stored warnings put the legacy-key reasons before the advisory findings`(): Unit =
        runBlocking {
            val advisory =
                "work_item_schemas:\n  feature-task:\n    seats:\n      - { name: impl, phase: work, enters: true }\n" +
                    "    notes:\n      - key: spec\n        role: work\n        required: true\n"

            val imported = assertIs<ConfigImportResult.Imported>(import("orchestration:\n  mode: workflow\n" + advisory))

            val warnings = imported.warnings
            assertTrue(warnings.size >= 2, "one legacy-key warning and at least one advisory finding: $warnings")
            assertTrue("orchestration" in warnings.first(), "the legacy-key reason comes first: $warnings")
            assertTrue(warnings.any { "feature-task" in it && "spec" in it }, "the advisory finding is stored too: $warnings")
            assertEquals(warnings, imported.version.warnings)
        }

    @Test
    fun `S3 an advisory finding alone is stored as a warning and does not reject`(): Unit =
        runBlocking {
            val advisory =
                "work_item_schemas:\n  feature-task:\n    seats:\n      - { name: impl, phase: work, enters: true }\n" +
                    "    notes:\n      - key: spec\n        role: work\n        required: true\n"

            val imported = assertIs<ConfigImportResult.Imported>(import(advisory))

            assertTrue(imported.warnings.any { "feature-task" in it && "spec" in it }, "warnings: ${imported.warnings}")
            assertEquals(advisory, imported.version.yaml)
        }

    // ------------------------------------------------------------------------------------------------ S2: project.rootId

    @Test
    fun `S2 project rootId of a depth-0 item targets that root, records one event and leaves the server scope alone`(): Unit =
        runBlocking {
            val root = newRoot()
            val text = projectFile(root.id.toString())

            val imported = assertIs<ConfigImportResult.Imported>(import(text))

            assertTrue(imported.appended)
            assertEquals(text, imported.version.yaml)
            assertEquals(ConfigVersionSource.FILE_IMPORT, imported.version.source)
            val head = assertNotNull(store.head(ConfigScope.ROOT, root.id))
            assertEquals(1, head.version)
            assertEquals(imported.version.id, head.versionId)
            assertNull(store.head(ConfigScope.SERVER, null), "the server scope is untouched")
            assertNull(service.serverHead())

            val events = configEvents()
            assertEquals(1, events.size, "events: $events")
            assertEquals(root.id, events.single().rootId)
            assertEquals("root", events.single().str("scope"))
            assertEquals("file-import", events.single().str("source"))
        }

    @Test
    fun `S2 an identical root re-import appends nothing and records no second event`(): Unit =
        runBlocking {
            val root = newRoot()
            val text = projectFile(root.id.toString())
            assertIs<ConfigImportResult.Imported>(import(text))

            val again = assertIs<ConfigImportResult.Imported>(import(text))

            assertFalse(again.appended)
            assertEquals(1, store.history(ConfigScope.ROOT, root.id, 100).size)
            assertEquals(1, configEvents().size)
        }

    @Test
    fun `S2 the rootId is accepted in upper case and still targets the root`(): Unit =
        runBlocking {
            val root = newRoot()

            val imported = assertIs<ConfigImportResult.Imported>(import(projectFile(root.id.toString().uppercase())))

            assertTrue(imported.appended)
            assertEquals(imported.version.id, assertNotNull(store.head(ConfigScope.ROOT, root.id)).versionId)
        }

    @Test
    fun `S4 a rootId that is not a UUID is Invalid at the rootId pointer with exactly one error`(): Unit =
        runBlocking {
            for (bad in listOf("not-a-uuid", "")) {
                val invalid = assertIs<ConfigImportResult.Invalid>(import(projectFile(bad)), "rootId '$bad'")

                assertEquals(listOf("/project/rootId" to "must be a UUID"), invalid.errors.map { it.path to it.reason }, "rootId '$bad'")
                assertEquals(emptyList(), invalid.warnings.map { it.path to it.reason })
            }
            assertNothingStored()
        }

    @Test
    fun `S4 a well-formed rootId with no such item is RootNotFound`(): Unit =
        runBlocking {
            val unknown = UUID.fromString("11111111-2222-4333-8444-555555555555")

            val result = import(projectFile(unknown.toString()))

            assertEquals(ConfigImportResult.RootNotFound(unknown), result)
            assertNothingStored()
        }

    @Test
    fun `S4 a rootId naming a depth-1 item is RootNotFound`(): Unit =
        runBlocking {
            val root = newRoot()
            val child =
                provider.workItemRepository().create(
                    WorkItem(title = "child", type = "feature-task", parentId = root.id, rootId = root.id, depth = 1, role = Role.QUEUE)
                )

            val result = import(projectFile(child.id.toString()))

            assertEquals(ConfigImportResult.RootNotFound(child.id), result)
            assertNull(store.head(ConfigScope.ROOT, child.id))
            assertNull(store.head(ConfigScope.ROOT, root.id))
        }

    // ------------------------------------------------------------------------------------------------ D8: ROOT-scope rules

    @Test
    fun `D8 a root-targeted document with a resources registry is Invalid at the resources pointer`(): Unit =
        runBlocking {
            val root = newRoot()

            val invalid =
                assertIs<ConfigImportResult.Invalid>(
                    import(projectFile(root.id.toString(), "resources:\n  db-cred:\n    description: \"x\"\n" + base))
                )

            assertTrue(invalid.errors.any { it.path == "/resources" || it.path.startsWith("/resources/") }, "errors: ${invalid.errors}")
            assertNull(store.head(ConfigScope.ROOT, root.id))
            assertIs<ConfigImportResult.Imported>(import(projectFile(root.id.toString())), "control: without the registry it is admitted")
        }

    @Test
    fun `D8 the same registry is admitted when the file has no project rootId`(): Unit =
        runBlocking {
            val imported = assertIs<ConfigImportResult.Imported>(import(StrictConfigCases.SERVER_MAX_HOLDERS_FIXED_YAML + base))

            assertTrue(imported.appended)
            assertNotNull(store.head(ConfigScope.SERVER, null))
        }

    @Test
    fun `D8 a root-targeted document combining note_schemas with work_item_schemas is Invalid`(): Unit =
        runBlocking {
            val root = newRoot()

            val invalid =
                assertIs<ConfigImportResult.Invalid>(import(projectFile(root.id.toString(), "work_item_schemas: {}\nnote_schemas: {}\n")))

            assertTrue(invalid.errors.any { it.path == "/note_schemas" || it.path == "/work_item_schemas" }, "errors: ${invalid.errors}")
            assertNull(store.head(ConfigScope.ROOT, root.id))
        }

    @Test
    fun `D8 a root-targeted document is validated against the stored server layer`(): Unit =
        runBlocking {
            val root = newRoot()
            val usesGhost = projectFile(root.id.toString(), "work_item_schemas:\n  x:\n    default_traits: [ghost]\n    notes: []\n")

            val before = assertIs<ConfigImportResult.Invalid>(import(usesGhost))
            assertTrue("/work_item_schemas/x/default_traits/0" in before.errors.map { it.path }, "errors: ${before.errors}")
            assertNull(store.head(ConfigScope.ROOT, root.id))

            assertIs<ConfigImportResult.Imported>(import("traits:\n  ghost:\n    notes: []\n"))
            val after = assertIs<ConfigImportResult.Imported>(import(usesGhost))
            assertTrue(after.appended, "the trait defined by the server head resolves")
            assertNotNull(store.head(ConfigScope.ROOT, root.id))
        }

    @Test
    fun `S3 the allowance also applies to a root-targeted document`(): Unit =
        runBlocking {
            val root = newRoot()

            val imported =
                assertIs<ConfigImportResult.Imported>(
                    import(
                        projectFile(
                            root.id.toString(),
                            "orchestration:\n  mode: workflow\n" + base
                        )
                    )
                )

            assertEquals(1, imported.warnings.size, "warnings: ${imported.warnings}")
            assertTrue("orchestration" in imported.warnings.single())
        }

    // ------------------------------------------------------------------------------------------------ the data step

    private class FakeFile(
        override val description: String,
        private val content: String?
    ) : ConfigFileSource {
        override fun text(): String? = content
    }

    private data class StepRun(
        val rows: Int?,
        val failure: Throwable?
    )

    private suspend fun runStep(file: ConfigFileSource): StepRun {
        val step = ServerConfigImportStep(service, file)
        var rows: Int? = null
        var failure: Throwable? = null
        val outcome =
            sqlite.unitOfWork().write("ConfigServiceImportTest.step") {
                try {
                    rows = step.run(this)
                } catch (e: DataStepException) {
                    failure = e
                }
                Outcome.Ok(Unit)
            }
        assertIs<Outcome.Ok<Unit>>(outcome)
        return StepRun(rows, failure)
    }

    @Test
    fun `step metadata - an EVERY_BOOT step in the CONFIG_IMPORT phase with no ordering constraint`() {
        val step = ServerConfigImportStep(service, FakeFile("fake", null))

        assertEquals("server-config-import", step.name)
        assertEquals(DataStepPhase.CONFIG_IMPORT, step.phase)
        assertEquals(DataStepKind.EVERY_BOOT, step.kind)
        assertEquals(emptySet(), step.after)
    }

    @Test
    fun `step - an absent file returns 0 and stores nothing`(): Unit =
        runBlocking {
            val run = runStep(FakeFile("fake:///absent", null))

            assertNull(run.failure)
            assertEquals(0, run.rows)
            assertNothingStored()
        }

    @Test
    fun `step - an appended import returns 1 and an identical one returns 0`(): Unit =
        runBlocking {
            val file = FakeFile("fake:///config.yaml", base)

            val first = runStep(file)
            val second = runStep(file)

            assertNull(first.failure)
            assertEquals(1, first.rows)
            assertEquals(0, second.rows, "an identical re-import appends nothing")
            assertEquals(1, serverHistorySize())
        }

    @Test
    fun `step - an empty file returns 0 and stores nothing`(): Unit =
        runBlocking {
            val run = runStep(FakeFile("fake:///empty.yaml", ""))

            assertNull(run.failure)
            assertEquals(0, run.rows)
            assertNothingStored()
        }

    @Test
    fun `step - an Invalid file throws DataStepException with the description and every path and reason`(): Unit =
        runBlocking {
            val text = "bogus: 1\nbogus-two: 2\n" + base
            val expectedErrors = assertIs<ConfigImportResult.Invalid>(import(text)).errors
            val description = "fake:///mounted/config.yaml"

            val run = runStep(FakeFile(description, text))

            val failure = assertNotNull(run.failure, "the step must throw")
            assertEquals(
                "server-config-import: $description rejected: " + expectedErrors.joinToString("; ") { "${it.path}: ${it.reason}" },
                failure.message
            )
            assertNothingStored()
        }

    @Test
    fun `step - a ParseError file throws with the parse detail`(): Unit =
        runBlocking {
            val text = "work_item_schemas: [\ninvalid yaml: :\n  - broken"
            val detail = assertIs<ConfigImportResult.ParseError>(import(text)).detail

            val run = runStep(FakeFile("fake:///broken.yaml", text))

            assertEquals("server-config-import: fake:///broken.yaml rejected: $detail", assertNotNull(run.failure).message)
        }

    @Test
    fun `step - an unknown project rootId throws the documented root message`(): Unit =
        runBlocking {
            val unknown = UUID.fromString("11111111-2222-4333-8444-555555555555")

            val run = runStep(FakeFile("fake:///root.yaml", projectFile(unknown.toString())))

            assertEquals(
                "server-config-import: fake:///root.yaml rejected: " +
                    "project.rootId $unknown is not a project root (depth 0) in this database",
                assertNotNull(run.failure).message
            )
        }

    @Test
    fun `step - an oversized file throws naming the size and the maximum`(): Unit =
        runBlocking {
            val max = ConfigService.MAX_CONFIG_YAML_BYTES
            val text = base + "#" + "x".repeat(max)

            val run = runStep(FakeFile("fake:///big.yaml", text))

            val message = assertNotNull(run.failure).message.orEmpty()
            assertTrue(message.startsWith("server-config-import: fake:///big.yaml rejected: "), message)
            assertTrue(max.toString() in message, "names the maximum: $message")
            assertTrue(text.toByteArray(Charsets.UTF_8).size.toString() in message, "names the size: $message")
            assertNothingStored()
        }
}
