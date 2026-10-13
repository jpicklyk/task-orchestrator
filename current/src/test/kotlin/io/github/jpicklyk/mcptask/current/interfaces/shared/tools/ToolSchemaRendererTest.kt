package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.test.tools.FixtureTools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 713c5c61) for [ToolSchemaRenderer], scenarios S5 and S6 of the frozen test-plan
 * (NEW-SURFACE).
 *
 * Oracles: task-scope Contract for `ToolSchemaRenderer` and decisions D3/D12 (select tool properties: `select` enum of the
 * visible selects in registration order, the union of visible selects' fields, `describe`; ops tool: `ops` array whose items hold
 * `op` enum of visible ops, the union of visible ops' fields and `idempotencyKey`, items `required: ["op"]`, plus `atomic` and
 * `describe`; no top-level `required`; a union field not declared by every visible select/op gets the suffix
 * " [select: a, b]" / " [ops: a, b]" on its description; null when nothing is visible; `description(spec)` is the spec
 * description + " " + DESCRIBE_HINT; annotations as listed) and the declared fixed-text constants. Hidden selects/ops and their
 * exclusive fields must not appear in the knowledge profile.
 *
 * Fixture field map (see FixtureTools): get{id, depth}, list{limit, tag, depth}, audit[orch]{scope, tag, depth};
 * create{title, tags, priority}, rename{id, title}, purge[orch]{id, force}.
 */
class ToolSchemaRendererTest {
    private val fx = FixtureTools()

    private fun schema(
        spec: ToolSpec,
        profile: ToolProfile
    ): JsonObject = ToolSchemaRenderer.inputSchema(spec, profile) ?: error("expected ${spec.name} to be visible in $profile")

    private fun JsonObject.props(): JsonObject = getValue("properties").jsonObject

    private fun JsonObject.desc(name: String): String =
        props()
            .getValue(name)
            .jsonObject
            .getValue("description")
            .jsonPrimitive.content

    private fun JsonObject.itemProps(): JsonObject =
        props()
            .getValue("ops")
            .jsonObject
            .getValue("items")
            .jsonObject
            .getValue("properties")
            .jsonObject

    private fun JsonObject.itemDesc(name: String): String =
        itemProps()
            .getValue(name)
            .jsonObject
            .getValue("description")
            .jsonPrimitive.content

    // ------------------------------------------------------------------ S5 select tool

    @Test
    fun `S5 knowledge select tool offers only the knowledge selects and their fields`() {
        val s = schema(fx.readTool, ToolProfile.KNOWLEDGE)
        assertEquals(setOf("select", "id", "depth", "limit", "tag", "describe"), s.props().keys)
        val select = s.props().getValue("select").jsonObject
        assertEquals("string", select.getValue("type").jsonPrimitive.content)
        assertEquals(listOf("get", "list"), select.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(ToolSchemaRenderer.SELECT_DOC, select.getValue("description").jsonPrimitive.content)
        assertFalse("scope" in s.props(), "the audit-only field must be hidden from knowledge")
    }

    @Test
    fun `S5 orchestration select tool offers every select in registration order and every field`() {
        val s = schema(fx.readTool, ToolProfile.ORCHESTRATION)
        assertEquals(setOf("select", "id", "depth", "limit", "tag", "scope", "describe"), s.props().keys)
        assertEquals(
            listOf("get", "list", "audit"),
            s
                .props()
                .getValue("select")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `S5 a field not declared by every visible select gets a suffix naming only the visible selects that declare it`() {
        val knowledge = schema(fx.readTool, ToolProfile.KNOWLEDGE)
        assertEquals("Item id. [select: get]", knowledge.desc("id"))
        assertEquals("Maximum rows. [select: list]", knowledge.desc("limit"))
        assertEquals("Tag filter. [select: list]", knowledge.desc("tag"), "audit also declares tag but is hidden, so it is not named")
        assertEquals("Depth to include.", knowledge.desc("depth"), "declared by every visible select: no suffix")

        val orchestration = schema(fx.readTool, ToolProfile.ORCHESTRATION)
        assertEquals("Item id. [select: get]", orchestration.desc("id"))
        assertEquals("Tag filter. [select: list, audit]", orchestration.desc("tag"), "names are in registration order")
        assertEquals("Audit scope. [select: audit]", orchestration.desc("scope"))
        assertEquals("Depth to include.", orchestration.desc("depth"))
    }

    @Test
    fun `S5 the fixed select and describe texts are used verbatim`() {
        val s = schema(fx.readTool, ToolProfile.KNOWLEDGE)
        assertEquals(ToolSchemaRenderer.DESCRIBE_DOC, s.desc("describe"))
        assertEquals(
            "boolean",
            s
                .props()
                .getValue("describe")
                .jsonObject
                .getValue("type")
                .jsonPrimitive.content
        )
        assertEquals(
            "Which read to run. A field marked [select: ...] applies only to the selects named.",
            ToolSchemaRenderer.SELECT_DOC
        )
    }

    @Test
    fun `S5 a field type and shape is carried from its request class`() {
        val s = schema(fx.readTool, ToolProfile.ORCHESTRATION)
        assertEquals(
            "integer",
            s
                .props()
                .getValue("depth")
                .jsonObject
                .getValue("type")
                .jsonPrimitive.content
        )
        assertEquals(
            "integer",
            s
                .props()
                .getValue("limit")
                .jsonObject
                .getValue("type")
                .jsonPrimitive.content
        )
        assertEquals(
            "string",
            s
                .props()
                .getValue("id")
                .jsonObject
                .getValue("type")
                .jsonPrimitive.content
        )
    }

    @Test
    fun `S5 no top-level required so a describe-only call is schema-valid`() {
        var rendered = 0
        for (spec in listOf(fx.readTool, fx.writeTool)) {
            for (profile in ToolProfile.entries) {
                // R5: the fixture members are knowledge/orchestration only, so the director profile renders no schema for them.
                val s = ToolSchemaRenderer.inputSchema(spec, profile) ?: continue
                rendered++
                assertFalse("required" in s, "${spec.name}/$profile must not declare a top-level required: ${s["required"]}")
            }
        }
        assertEquals(4, rendered, "control: both fixture tools render in knowledge and orchestration (and not in director)")
    }

    @Test
    fun `S5 fixture tools without a director member render no schema and no tool in director`() {
        for (spec in fx.registry.tools) {
            assertNull(ToolSchemaRenderer.inputSchema(spec, ToolProfile.DIRECTOR), "${spec.name} has no director member")
        }
    }

    @Test
    fun `S5 a select tool whose selects are all hidden renders no schema in knowledge and a schema in orchestration`() {
        assertNull(ToolSchemaRenderer.inputSchema(fx.peekTool, ToolProfile.KNOWLEDGE))
        assertNotNull(ToolSchemaRenderer.inputSchema(fx.peekTool, ToolProfile.ORCHESTRATION))
    }

    // ------------------------------------------------------------------ S5 ops tool

    @Test
    fun `S5 knowledge ops tool has ops atomic and describe and hides the orchestration op`() {
        val s = schema(fx.writeTool, ToolProfile.KNOWLEDGE)
        assertEquals(setOf("ops", "atomic", "describe"), s.props().keys)
        val ops = s.props().getValue("ops").jsonObject
        assertEquals("array", ops.getValue("type").jsonPrimitive.content)
        assertEquals(ToolSchemaRenderer.OPS_DOC, ops.getValue("description").jsonPrimitive.content)
        val items = ops.getValue("items").jsonObject
        assertEquals("object", items.getValue("type").jsonPrimitive.content)
        assertEquals(listOf("op"), items.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(setOf("op", "title", "tags", "priority", "id", "idempotencyKey"), items.getValue("properties").jsonObject.keys)
        assertFalse("force" in items.getValue("properties").jsonObject, "the purge-only field must be hidden from knowledge")
        assertEquals(
            listOf("create", "rename"),
            items
                .getValue("properties")
                .jsonObject
                .getValue("op")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `S5 orchestration ops tool offers all ops in order`() {
        val s = schema(fx.writeTool, ToolProfile.ORCHESTRATION)
        assertEquals(setOf("op", "title", "tags", "priority", "id", "force", "idempotencyKey"), s.itemProps().keys)
        assertEquals(
            listOf("create", "rename", "purge"),
            s
                .itemProps()
                .getValue("op")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `S5 ops tool fixed texts and union-field suffixes`() {
        val knowledge = schema(fx.writeTool, ToolProfile.KNOWLEDGE)
        assertEquals(ToolSchemaRenderer.OP_DOC, knowledge.itemDesc("op"))
        assertEquals(ToolSchemaRenderer.IDEMPOTENCY_KEY_DOC, knowledge.itemDesc("idempotencyKey"))
        assertEquals(ToolSchemaRenderer.ATOMIC_DOC, knowledge.desc("atomic"))
        assertEquals(
            "boolean",
            knowledge
                .props()
                .getValue("atomic")
                .jsonObject
                .getValue("type")
                .jsonPrimitive.content
        )
        assertEquals(ToolSchemaRenderer.DESCRIBE_DOC, knowledge.desc("describe"))
        assertEquals("Title text.", knowledge.itemDesc("title"), "declared by every visible op: no suffix")
        assertEquals("Tags to attach. [ops: create]", knowledge.itemDesc("tags"))
        assertEquals("Priority number. [ops: create]", knowledge.itemDesc("priority"))
        assertEquals("Item id. [ops: rename]", knowledge.itemDesc("id"), "purge also declares id but is hidden")

        val orchestration = schema(fx.writeTool, ToolProfile.ORCHESTRATION)
        assertEquals(
            "Title text. [ops: create, rename]",
            orchestration.itemDesc("title"),
            "now purge is visible and does not declare title"
        )
        assertEquals("Item id. [ops: rename, purge]", orchestration.itemDesc("id"))
        assertEquals("Skip safety checks. [ops: purge]", orchestration.itemDesc("force"))
    }

    @Test
    fun `S5 the fixed ops texts are the frozen sentences`() {
        assertEquals(
            "Write operations, one flat object per op, run in order. A field marked [ops: ...] applies only to the ops named.",
            ToolSchemaRenderer.OPS_DOC
        )
        assertEquals("The operation this element runs.", ToolSchemaRenderer.OP_DOC)
        assertEquals(
            "true: the first failing op rolls back the whole batch. Default false: each op commits on its own.",
            ToolSchemaRenderer.ATOMIC_DOC
        )
        assertEquals(
            "Optional client key (1-128 chars, needs actor). Resending the same op with the same key returns the stored result.",
            ToolSchemaRenderer.IDEMPOTENCY_KEY_DOC
        )
        assertEquals(
            "true: return the operations, their parameters and errors instead of running the tool.",
            ToolSchemaRenderer.DESCRIBE_DOC
        )
        assertEquals("Call with describe:true for each operation's parameters and errors.", ToolSchemaRenderer.DESCRIBE_HINT)
    }

    @Test
    fun `S5 an ops tool whose ops are all hidden renders no schema in knowledge`() {
        assertNull(ToolSchemaRenderer.inputSchema(fx.adminTool, ToolProfile.KNOWLEDGE))
        val orchestration = schema(fx.adminTool, ToolProfile.ORCHESTRATION)
        assertEquals(setOf("ops", "atomic", "describe"), orchestration.props().keys)
        assertEquals(setOf("op", "reason", "idempotencyKey"), orchestration.itemProps().keys)
    }

    @Test
    fun `S5 the rendered schema is valid JSON that round trips`() {
        for (spec in fx.registry.tools) {
            for (profile in ToolProfile.entries) {
                val s = ToolSchemaRenderer.inputSchema(spec, profile) ?: continue
                assertEquals(s, Json.parseToJsonElement(s.toString()).jsonObject)
            }
        }
    }

    // ------------------------------------------------------------------ S5 description

    @Test
    fun `S5 description is the spec description, one space, then the describe hint`() {
        assertEquals(
            "Fixture read tool. Call with describe:true for each operation's parameters and errors.",
            ToolSchemaRenderer.description(fx.readTool)
        )
        assertEquals("Fixture write tool. ${ToolSchemaRenderer.DESCRIBE_HINT}", ToolSchemaRenderer.description(fx.writeTool))
    }

    // ------------------------------------------------------------------ S6 annotations

    @Test
    fun `S6 a select tool is read only, idempotent, not destructive and closed world in every profile`() {
        for (profile in ToolProfile.entries) {
            val a = ToolSchemaRenderer.annotations(fx.readTool, profile)
            assertEquals(true, a.readOnlyHint, "$profile readOnly")
            assertEquals(false, a.destructiveHint, "$profile destructive")
            assertEquals(true, a.idempotentHint, "$profile idempotent")
            assertEquals(false, a.openWorldHint, "$profile openWorld")
        }
    }

    @Test
    fun `S6 an ops tool is destructive only when a visible op is destructive`() {
        val knowledge = ToolSchemaRenderer.annotations(fx.writeTool, ToolProfile.KNOWLEDGE)
        assertEquals(false, knowledge.readOnlyHint)
        assertEquals(false, knowledge.destructiveHint, "purge is hidden in knowledge, create and rename are not destructive")
        assertEquals(false, knowledge.idempotentHint)
        assertEquals(false, knowledge.openWorldHint)

        val orchestration = ToolSchemaRenderer.annotations(fx.writeTool, ToolProfile.ORCHESTRATION)
        assertEquals(false, orchestration.readOnlyHint)
        assertEquals(true, orchestration.destructiveHint, "purge is visible and destructive")
        assertEquals(false, orchestration.idempotentHint)
        assertEquals(false, orchestration.openWorldHint)
    }

    @Test
    fun `S6 an orchestration-only ops tool without a destructive op is not destructive`() {
        val a = ToolSchemaRenderer.annotations(fx.adminTool, ToolProfile.ORCHESTRATION)
        assertEquals(false, a.destructiveHint)
        assertEquals(false, a.readOnlyHint)
    }

    @Test
    fun `S6 the rendered schema text never mentions a hidden op or select name in the knowledge profile`() {
        val text = schema(fx.writeTool, ToolProfile.KNOWLEDGE).toString() + schema(fx.readTool, ToolProfile.KNOWLEDGE).toString()
        assertFalse("purge" in text, text)
        assertFalse("audit" in text, text)
        assertTrue("create" in text && "get" in text, "control: visible names are present")
    }
}
