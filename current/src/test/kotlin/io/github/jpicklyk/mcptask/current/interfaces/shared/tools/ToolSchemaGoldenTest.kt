package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.test.tools.FixtureTools
import io.github.jpicklyk.mcptask.current.test.tools.ToolGoldens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 713c5c61) for the per-tool schema goldens, scenario S16 of the frozen test-plan
 * (NEW-SURFACE), over the [ToolGoldens] helpers and fixture registries in temp directories, plus the production directory
 * against `buildToolSpecs()`.
 *
 * Oracles: task-scope D10 (one pretty JSON file per (tool, visible profile) named `<tool>.<profile>.json`, content = the
 * `tools/list` entry the adapter registers, LF line endings, stale files deleted by the generator) and plan section 2 (goldens
 * per tool; checked two ways: every registry rendering has a matching file, and every file has a registry rendering). The drift
 * comparison below is this test's own function; it is exercised on fixture directories that are deliberately made to drift, so
 * each failure mode has a fixture that would make it fail.
 */
class ToolSchemaGoldenTest {
    @TempDir
    lateinit var dir: Path

    private val fx = FixtureTools()

    /** Messages for every difference between what [registry] renders and what [directory] holds, in both directions. */
    private fun drift(
        registry: ToolRegistry,
        directory: Path
    ): List<String> {
        val expected = ToolGoldens.expected(registry)
        val actual = ToolGoldens.read(directory)
        val out = mutableListOf<String>()
        for ((name, content) in expected) {
            val onDisk = actual[name]
            if (onDisk == null) {
                out += "missing: $name"
            } else if (onDisk != content) {
                out += "changed: $name"
            }
        }
        for (name in actual.keys - expected.keys) out += "stale: $name"
        return out
    }

    private val expectedNames =
        setOf(
            "fx_read.knowledge.json",
            "fx_read.orchestration.json",
            "fx_peek.orchestration.json",
            "fx_write.knowledge.json",
            "fx_write.orchestration.json",
            "fx_admin.orchestration.json"
        )

    // ------------------------------------------------------------------ naming and content

    @Test
    fun `S16 one file per tool and visible profile, named tool dot profile dot json`() {
        assertEquals(expectedNames, ToolGoldens.expected(fx.registry).keys)
        assertEquals("fx_read.knowledge.json", ToolGoldens.fileName("fx_read", ToolProfile.KNOWLEDGE))
        assertEquals("fx_read.orchestration.json", ToolGoldens.fileName("fx_read", ToolProfile.ORCHESTRATION))
    }

    @Test
    fun `S16 a golden is LF-only pretty JSON ending in one newline`() {
        for ((name, content) in ToolGoldens.expected(fx.registry)) {
            assertFalse('\r' in content, "$name must be LF only")
            assertTrue(content.endsWith("}\n"), "$name must end with a closing brace and a single newline")
            assertFalse(content.endsWith("\n\n"), name)
            assertTrue(content.lines().size > 5, "$name must be pretty printed, not one line")
        }
    }

    @Test
    fun `S16 each golden is the tool entry for its profile`() {
        for ((name, content) in ToolGoldens.expected(fx.registry)) {
            val tool = Json.parseToJsonElement(content).jsonObject
            val toolName = name.substringBefore('.')
            val profile = ToolProfile.entries.single { name == ToolGoldens.fileName(toolName, it) }
            val spec = fx.registry[toolName]!!
            assertEquals(toolName, tool.getValue("name").jsonPrimitive.content, name)
            assertEquals(ToolSchemaRenderer.description(spec), tool.getValue("description").jsonPrimitive.content, name)
            assertEquals(
                ToolSchemaRenderer.inputSchema(spec, profile)!!.getValue("properties"),
                tool.getValue("inputSchema").jsonObject.getValue("properties"),
                "$name: properties"
            )
        }
    }

    @Test
    fun `S16 the knowledge golden of a tool differs from its orchestration golden and omits hidden operations`() {
        val files = ToolGoldens.expected(fx.registry)
        val knowledge = files.getValue("fx_write.knowledge.json")
        val orchestration = files.getValue("fx_write.orchestration.json")
        assertTrue(knowledge != orchestration)
        assertFalse("purge" in knowledge)
        assertTrue("purge" in orchestration, "control: the orchestration file lists the destructive op")
        assertTrue(
            Json
                .parseToJsonElement(
                    orchestration
                ).jsonObject
                .getValue("annotations")
                .jsonObject
                .getValue("destructiveHint")
                .jsonPrimitive.content
                .toBoolean()
        )
        assertFalse(
            Json
                .parseToJsonElement(
                    knowledge
                ).jsonObject
                .getValue("annotations")
                .jsonObject
                .getValue("destructiveHint")
                .jsonPrimitive.content
                .toBoolean()
        )
    }

    // ------------------------------------------------------------------ generator

    @Test
    fun `S16 write creates exactly the expected files and drift is then empty`() {
        val result = ToolGoldens.write(fx.registry, dir)
        assertEquals(expectedNames, result.written.toSet())
        assertEquals(emptyList(), result.deleted)
        assertEquals(expectedNames, ToolGoldens.read(dir).keys)
        assertEquals(emptyList(), drift(fx.registry, dir))
    }

    @Test
    fun `S16 two regenerations are byte identical`() {
        ToolGoldens.write(fx.registry, dir)
        val first = ToolGoldens.read(dir)
        val bytesFirst = first.mapValues { Files.readAllBytes(dir.resolve(it.key)).toList() }
        ToolGoldens.write(fx.registry, dir)
        val second = ToolGoldens.read(dir)
        assertEquals(first, second)
        assertEquals(bytesFirst, second.mapValues { Files.readAllBytes(dir.resolve(it.key)).toList() })
        assertEquals(first, ToolGoldens.expected(fx.registry), "what is on disk is exactly what the registry renders")
    }

    @Test
    fun `S16 regeneration deletes a stale file and reports it`() {
        ToolGoldens.write(fx.registry, dir)
        Files.writeString(dir.resolve("old_tool.orchestration.json"), "{}\n")
        assertEquals(listOf("stale: old_tool.orchestration.json"), drift(fx.registry, dir))
        val result = ToolGoldens.write(fx.registry, dir)
        assertEquals(listOf("old_tool.orchestration.json"), result.deleted)
        assertFalse(Files.exists(dir.resolve("old_tool.orchestration.json")))
        assertEquals(emptyList(), drift(fx.registry, dir))
    }

    // ------------------------------------------------------------------ drift detection, both directions

    @Test
    fun `S16 a missing golden is detected`() {
        ToolGoldens.write(fx.registry, dir)
        Files.delete(dir.resolve("fx_read.knowledge.json"))
        assertEquals(listOf("missing: fx_read.knowledge.json"), drift(fx.registry, dir))
    }

    @Test
    fun `S16 a changed golden is detected`() {
        ToolGoldens.write(fx.registry, dir)
        val file = dir.resolve("fx_write.orchestration.json")
        Files.writeString(file, Files.readString(file).replace("Fixture write tool.", "Fixture write tool, edited."))
        assertEquals(listOf("changed: fx_write.orchestration.json"), drift(fx.registry, dir))
    }

    @Test
    fun `S16 a tool removed from the registry leaves stale goldens`() {
        ToolGoldens.write(fx.registry, dir)
        val smaller = ToolRegistry(listOf(fx.readTool, fx.peekTool, fx.adminTool))
        assertEquals(
            setOf("stale: fx_write.knowledge.json", "stale: fx_write.orchestration.json"),
            drift(smaller, dir).toSet()
        )
    }

    @Test
    fun `S16 a tool that becomes hidden in a profile leaves a stale golden for that profile`() {
        ToolGoldens.write(fx.registry, dir)
        // fx_read without its knowledge selects is orchestration-only: its knowledge golden must go.
        val hiddenInKnowledge = fx.readTool
        val onlyAudit = SelectToolSpec("fx_read", hiddenInKnowledge.description, listOf(fx.auditSelect))
        val changed = ToolRegistry(listOf(onlyAudit, fx.peekTool, fx.writeTool, fx.adminTool))
        val messages = drift(changed, dir)
        assertTrue("stale: fx_read.knowledge.json" in messages, messages.toString())
        assertTrue("changed: fx_read.orchestration.json" in messages, messages.toString())
    }

    @Test
    fun `S16 a tool hidden in knowledge has no knowledge golden`() {
        val files = ToolGoldens.expected(fx.registry)
        assertNull(files["fx_admin.knowledge.json"])
        assertNull(files["fx_peek.knowledge.json"])
    }

    @Test
    fun `S16 an empty registry has no goldens and an absent directory is empty`() {
        assertEquals(emptyMap(), ToolGoldens.expected(ToolRegistry(emptyList())))
        assertEquals(emptyMap(), ToolGoldens.read(dir.resolve("does-not-exist")))
        assertEquals(emptyList(), drift(ToolRegistry(emptyList()), dir.resolve("does-not-exist")))
    }

    // ------------------------------------------------------------------ production directory

    @Test
    fun `S16 the checked-in goldens match the production registry in both directions`() {
        val messages = drift(ToolRegistry(buildToolSpecs()), ToolGoldens.DIR)
        assertEquals(
            emptyList(),
            messages,
            "run ${ToolGoldens.REGENERATE_TASK} to rewrite the goldens, then review the diff; expected files: ${ToolGoldens.expected(
                ToolRegistry(buildToolSpecs())
            ).keys}"
        )
    }

    @Test
    fun `S16 golden JSON arrays keep a stable order across regenerations`() {
        val a = ToolGoldens.expected(fx.registry)
        val b = ToolGoldens.expected(FixtureTools().registry)
        assertEquals(a, b)
        val select =
            Json
                .parseToJsonElement(a.getValue("fx_read.orchestration.json"))
                .jsonObject
                .getValue("inputSchema")
                .jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("select")
                .jsonObject
                .getValue("enum")
                .jsonArray
        assertEquals(listOf("get", "list", "audit"), select.map { it.jsonPrimitive.content })
    }
}
