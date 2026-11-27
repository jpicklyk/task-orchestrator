package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolDescriber
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.platform.tool.OpsToolSpec
import io.github.jpicklyk.mcptask.current.platform.tool.ToolProfile
import io.github.jpicklyk.mcptask.current.platform.tool.ToolSchemaRenderer
import io.github.jpicklyk.mcptask.current.platform.tool.ToolSpec
import io.github.jpicklyk.mcptask.current.platform.tool.ToolView
import io.modelcontextprotocol.kotlin.sdk.types.Tool
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
 * Independent tests (test-author seat, item 47c83a63) for the top-level `seatToken` declaration on tool input schemas,
 * scenarios S1 to S5 of the frozen test-plan (all EXISTING-SURFACE).
 *
 * Oracles: O1 the carry-in-r22-research ruling (the orchestration profile declares `seatToken` as an optional top-level string
 * on every tool, so a Code Mode client that validates arguments against the declared schema accepts a token-bearing call);
 * O2 task-scope D1 of 95a22e6e (seatToken is top-level and reserved: it is never an element field); O3 RunPermissions.kt:42
 * (the director is a call with no seat); O4 api-reference.md:19 (describe serves the same schema data); literal expected values.
 *
 * S6 is existing coverage (SeatTokenLiftTest D1, ToolSurfaceGuardsTest S15, ToolTextForDeferredLoadingTest S10): no test here.
 */
class SeatTokenSchemaTest {
    private val registry = ToolRegistry(buildToolSpecs())

    private fun rendered(profile: ToolProfile): List<Pair<ToolSpec, Tool>> =
        registry.tools.mapNotNull { spec -> McpOperationAdapter.toolFor(spec, profile)?.let { spec to it } }

    private fun props(tool: Tool): JsonObject = assertNotNull(tool.inputSchema.properties, "${tool.name} has input properties")

    @Test
    fun `S1 every orchestration tool declares seatToken as a described top-level string`() {
        val tools = rendered(ToolProfile.ORCHESTRATION)
        assertEquals(15, tools.size, "control: the orchestration profile renders 15 tools")
        for ((_, tool) in tools) {
            val seat = props(tool)["seatToken"]?.jsonObject
            assertNotNull(seat, "${tool.name} declares seatToken")
            assertEquals("string", seat.getValue("type").jsonPrimitive.content, "${tool.name} seatToken type")
            assertTrue(
                seat
                    .getValue("description")
                    .jsonPrimitive.content
                    .isNotBlank(),
                "${tool.name} seatToken description"
            )
        }
    }

    @Test
    fun `S1 probe seatToken is declared exactly once and only in its exact spelling`() {
        for ((_, tool) in rendered(ToolProfile.ORCHESTRATION)) {
            val keys = props(tool).keys
            assertEquals(1, keys.count { it == "seatToken" }, "${tool.name} exact key count")
            assertEquals(1, keys.count { it.equals("seattoken", ignoreCase = true) }, "${tool.name} no case variant")
        }
    }

    @Test
    fun `S2 knowledge and director renderings and the rest view declare no seatToken`() {
        for (profile in listOf(ToolProfile.KNOWLEDGE, ToolProfile.DIRECTOR)) {
            val tools = rendered(profile)
            assertTrue(tools.isNotEmpty(), "control: $profile renders at least one tool")
            for ((_, tool) in tools) {
                assertFalse("seatToken" in props(tool).keys, "$profile ${tool.name} must not declare seatToken")
            }
        }
        var restRendered = 0
        for (spec in registry.tools) {
            val schema = ToolSchemaRenderer.inputSchema(spec, ToolView.Rest) ?: continue
            restRendered++
            assertFalse("seatToken" in schema.getValue("properties").jsonObject.keys, "rest ${spec.name} must not declare seatToken")
        }
        assertTrue(restRendered > 0, "control: the rest view renders at least one tool")
    }

    @Test
    fun `S3 seatToken is never an element field and no orchestration schema requires anything at the top level`() {
        var opsTools = 0
        for ((spec, tool) in rendered(ToolProfile.ORCHESTRATION)) {
            assertTrue(tool.inputSchema.required.isNullOrEmpty(), "${tool.name} has no top-level required")
            if (spec !is OpsToolSpec<*>) continue
            opsTools++
            val items =
                props(tool)
                    .getValue("ops")
                    .jsonObject
                    .getValue("items")
                    .jsonObject
            val itemKeys = items.getValue("properties").jsonObject.keys
            assertFalse("seatToken" in itemKeys, "${tool.name} ops.items must not declare seatToken")
            assertEquals(listOf("op"), items.getValue("required").jsonArray.map { it.jsonPrimitive.content }, "${tool.name} items.required")
        }
        assertTrue(opsTools > 0, "control: at least one ops tool is rendered")
    }

    @Test
    fun `S4 a token-bearing call uses only declared top-level properties on every orchestration tool`() {
        for ((spec, tool) in rendered(ToolProfile.ORCHESTRATION)) {
            val callKeys = if (spec is OpsToolSpec<*>) setOf("seatToken", "ops", "atomic") else setOf("seatToken", "select")
            val undeclared = callKeys - props(tool).keys
            assertTrue(undeclared.isEmpty(), "${tool.name}: a Code Mode client would refuse undeclared $undeclared")
        }
    }

    @Test
    fun `S5 describe declares seatToken for orchestration and not for a director tool`() {
        val orchestration = registry.tools.first { ToolSchemaRenderer.inputSchema(it, ToolProfile.ORCHESTRATION) != null }
        val orchestrationProps =
            ToolDescriber
                .describe(orchestration, ToolProfile.ORCHESTRATION, null)
                .getValue("inputSchema")
                .jsonObject
                .getValue("properties")
                .jsonObject
        assertTrue("seatToken" in orchestrationProps.keys, "describe in orchestration declares seatToken")

        val runsWrite = assertNotNull(registry["runs_write"], "runs_write is registered")
        val directorSchema = ToolDescriber.describe(runsWrite, ToolProfile.DIRECTOR, null).getValue("inputSchema").jsonObject
        assertNull(directorSchema.getValue("properties").jsonObject["seatToken"], "describe in director declares no seatToken")
    }
}
