package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.application.config.ConfigValidator
import io.github.jpicklyk.mcptask.current.application.config.GlobalConfigSource
import io.github.jpicklyk.mcptask.current.application.config.ParseMode
import io.github.jpicklyk.mcptask.current.application.service.ConfigPushResult
import io.github.jpicklyk.mcptask.current.application.service.ConfigService
import io.github.jpicklyk.mcptask.current.application.service.buildFullSchemaEntriesJson
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independently authored tests (test-author seat, item 190f1bdd) for the note-entry `shared` flag at the config seams:
 * the lenient parse (`YamlSchemaParser.parseRoot`), the STRICT / MIGRATION classification of a non-boolean value
 * (`YamlConfigDocumentParser`), admission through `ConfigService.push` (parser plus the JSON Schema `noteEntry`), and the
 * full served entry shape (`buildFullSchemaEntriesJson`).
 *
 * Oracle (frozen task-scope "What to build" 1 and AC1): `shared` is a known entry key on schema-level and trait-level
 * entries, defaults to false, a non-boolean is a soft warning at `<entry>/shared` and false (lenient) or an error (strict
 * mode), and it is served as `shared: true` only, absent when false. Paths follow the RFC 6901 shape of the existing
 * strict-mode cases (`/work_item_schemas/<type>/notes/<i>/<member>`, `/traits/<trait>/notes/<i>/<member>`).
 */
class YamlSchemaParserTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private fun parseRoot(yaml: String): YamlSchemaParser.ParsedConfig {
        @Suppress("UNCHECKED_CAST")
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any>>(yaml) ?: emptyMap()
        return YamlSchemaParser.parseRoot(root, warnOnMissingSchemas = false)
    }

    private fun document(
        yaml: String,
        mode: ParseMode
    ): ConfigDocument =
        assertIs<ConfigDocumentParser.Outcome.Parsed>(
            YamlConfigDocumentParser.parse(yaml, warnOnMissingSchemas = false, mode = mode),
            "expected Parsed for $mode"
        ).document

    private fun schemaYaml(sharedValue: String?) =
        "work_item_schemas:\n  bug-fix:\n    notes:\n      - { key: diagnosis, role: queue" +
            (sharedValue?.let { ", shared: $it" } ?: "") + " }\n"

    private fun traitYaml(sharedValue: String?) =
        "traits:\n  t1:\n    notes:\n      - { key: board, role: work" + (sharedValue?.let { ", shared: $it" } ?: "") + " }\n"

    // ------------------------------------------------------------------ S1 lenient parse

    @Test
    fun `S1 shared true parses on a schema-level and a trait-level entry, absent and false are false`() {
        val parsed =
            parseRoot(
                """
                work_item_schemas:
                  bug-fix:
                    notes:
                      - { key: a, role: work, shared: true }
                      - { key: b, role: work }
                      - { key: c, role: work, shared: false }
                traits:
                  t1:
                    notes:
                      - { key: t-shared, role: work, shared: true }
                      - { key: t-plain, role: work }
                """.trimIndent()
            )

        val notes =
            parsed.workItemSchemas
                .getValue("bug-fix")
                .notes
                .associateBy { it.key }
        assertTrue(notes.getValue("a").shared)
        assertFalse(notes.getValue("b").shared, "absent defaults to false")
        assertFalse(notes.getValue("c").shared)
        val traitNotes = parsed.traits.getValue("t1").associateBy { it.key }
        assertTrue(traitNotes.getValue("t-shared").shared)
        assertFalse(traitNotes.getValue("t-plain").shared)
    }

    @Test
    fun `S1 shared is a known entry key - no unknown-key warning - while a misspelling still warns`() {
        val known = parseRoot(schemaYaml("true") + traitYaml("true"))
        assertTrue(known.warnings.isEmpty(), "shared is documented: ${known.warnings}")

        val misspelled = parseRoot("work_item_schemas:\n  bug-fix:\n    notes:\n      - { key: diagnosis, role: queue, sharedd: true }\n")
        assertTrue(
            misspelled.warnings.any { it.contains("diagnosis") && it.contains("sharedd") },
            "control: an unknown key next to it is still reported: ${misspelled.warnings}"
        )
        assertFalse(
            misspelled.workItemSchemas
                .getValue("bug-fix")
                .notes
                .single()
                .shared
        )
    }

    @Test
    fun `S1 a non-boolean shared is a soft warning naming the entry and the field, and the flag is false`() {
        for (bad in listOf("\"yes\"", "\"true\"", "1", "[true]")) {
            val parsed = parseRoot(schemaYaml(bad))

            assertFalse(
                parsed.workItemSchemas
                    .getValue("bug-fix")
                    .notes
                    .single()
                    .shared,
                "shared: $bad"
            )
            assertTrue(
                parsed.warnings.any { it.contains("diagnosis") && it.contains("shared") },
                "shared: $bad should warn naming the entry and the field: ${parsed.warnings}"
            )
        }
        // control: a real boolean true produces no warning and a true flag
        val ok = parseRoot(schemaYaml("true"))
        assertTrue(ok.warnings.isEmpty(), "${ok.warnings}")
        assertTrue(
            ok.workItemSchemas
                .getValue("bug-fix")
                .notes
                .single()
                .shared
        )
    }

    @Test
    fun `S1 a non-boolean shared on a trait entry is false with a warning too`() {
        val parsed = parseRoot(traitYaml("\"yes\""))

        assertFalse(
            parsed.traits
                .getValue("t1")
                .single()
                .shared
        )
        assertTrue(parsed.warnings.any { it.contains("board") && it.contains("shared") }, "${parsed.warnings}")
    }

    // ------------------------------------------------------------------ S1 strict / migration classification

    @Test
    fun `S1 STRICT turns a non-boolean shared into an error at the entry's shared pointer, MIGRATION keeps a warning at the same path`() {
        val cases =
            mapOf(
                schemaYaml("\"yes\"") to "/work_item_schemas/bug-fix/notes/0/shared",
                traitYaml("\"yes\"") to "/traits/t1/notes/0/shared"
            )
        for ((yaml, path) in cases) {
            val strict = document(yaml, ParseMode.STRICT)
            val migration = document(yaml, ParseMode.MIGRATION)

            assertTrue(path in strict.errors.map { it.path }, "STRICT errors: ${strict.errors}")
            assertEquals(emptyList(), migration.errors, "MIGRATION records no error")
            assertTrue(path in migration.warningViolations.map { it.path }, "MIGRATION warnings: ${migration.warningViolations}")
        }
    }

    @Test
    fun `S1 a boolean shared has no STRICT errors on either level`() {
        for (yaml in listOf(schemaYaml("true"), schemaYaml("false"), traitYaml("true"))) {
            assertEquals(emptyList(), document(yaml, ParseMode.STRICT).errors, yaml)
        }
    }

    @Test
    fun `S1 the shared flag survives the document parse in both modes`() {
        for (mode in ParseMode.values()) {
            val schemas = document(schemaYaml("true"), mode).workItemSchemas
            assertTrue(
                schemas
                    .getValue("bug-fix")
                    .notes
                    .single()
                    .shared,
                "mode $mode"
            )
        }
    }

    // ------------------------------------------------------------------ S1 admission through push (parser + JSON Schema)

    private fun service() =
        ConfigService(
            sqlite.repositoryProvider(),
            YamlConfigDocumentParser,
            sqlite.unitOfWork(),
            ConfigValidator(NetworkntConfigStructuralValidator(), GlobalConfigSource { null })
        )

    private suspend fun newRoot() =
        sqlite
            .repositoryProvider()
            .workItemRepository()
            .create(WorkItem(title = "Root", type = "project", depth = 0))
            .id

    @Test
    fun `S1 push admits shared true and false and rejects a non-boolean at the shared pointer, storing nothing`(): Unit =
        runBlocking {
            val service = service()
            val root = newRoot()

            assertIs<ConfigPushResult.Success>(service.push(root, schemaYaml("true")), "shared: true is admitted")
            assertIs<ConfigPushResult.Success>(service.push(root, schemaYaml("false") + "# v2\n"), "shared: false is admitted")
            val headBefore = assertNotNull(sqlite.repositoryProvider().configStore().head(ConfigScope.ROOT, root))

            val rejected = service.push(root, schemaYaml("\"yes\""))

            val invalid = assertIs<ConfigPushResult.Invalid>(rejected, "a non-boolean shared must be rejected: $rejected")
            assertTrue(
                invalid.errors.any { it.path == "/work_item_schemas/bug-fix/notes/0/shared" },
                "errors: ${invalid.errors}"
            )
            assertEquals(
                headBefore,
                sqlite.repositoryProvider().configStore().head(ConfigScope.ROOT, root),
                "a rejected push leaves the head unchanged"
            )
        }

    @Test
    fun `S1 push still rejects a misspelled entry key, so shared is accepted by name and not by loosening the entry`(): Unit =
        runBlocking {
            val service = service()
            val root = newRoot()

            val rejected =
                service.push(
                    root,
                    "work_item_schemas:\n  bug-fix:\n    notes:\n      - { key: diagnosis, role: queue, sharedd: true }\n"
                )

            assertIs<ConfigPushResult.Invalid>(rejected, "control: an unknown entry key is an error: $rejected")
        }

    // ------------------------------------------------------------------ S1 served shape

    @Test
    fun `S1 the full served entry carries shared true only - absent when false`() {
        val entries =
            listOf(
                NoteSchemaEntry(key = "board", role = Role.WORK, description = "Board", shared = true),
                NoteSchemaEntry(key = "private", role = Role.WORK, description = "Private", shared = false),
                NoteSchemaEntry(key = "plain", role = Role.QUEUE, description = "Plain")
            )

        val served = buildFullSchemaEntriesJson(entries).map { it.jsonObject }

        assertTrue(served[0].getValue("shared").jsonPrimitive.boolean, "shared: true is served: ${served[0]}")
        assertFalse("shared" in served[1], "shared: false is not served: ${served[1]}")
        assertFalse("shared" in served[2], "the default is not served: ${served[2]}")
        assertNull(served[2]["shared"])
        // the unchanged members stay unchanged next to it
        assertEquals("board", served[0].getValue("key").jsonPrimitive.content)
        assertEquals("work", served[0].getValue("role").jsonPrimitive.content)
        assertEquals(served[2].keys, served[1].keys, "a non-shared entry is shaped exactly like an entry that never had the flag")
    }
}
