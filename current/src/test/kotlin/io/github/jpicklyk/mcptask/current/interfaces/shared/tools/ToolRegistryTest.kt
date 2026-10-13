package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiCapability
import io.github.jpicklyk.mcptask.current.test.tools.FixtureTools
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 713c5c61) for [ToolRegistry], scenarios S3 and S4 of the frozen test-plan
 * (NEW-SURFACE).
 *
 * Oracles: task-scope Contract for `ToolRegistry` (`init` throws `IllegalArgumentException` naming tool/op/field for a duplicate
 * tool name, a duplicate select/op name within a tool, a tool name outside `^[a-z][a-z0-9_]{0,63}$`, an op name outside
 * `^[a-z][a-z_]{0,63}$`, a request class using a reserved field name (`actor`, `describe`, `select` for select tools; `actor`,
 * `op`, `idempotencyKey` for op elements), a polymorphic or sealed member anywhere, and the same field name in two selects/ops of
 * one tool with a different generated schema) and the declared reserved-name sets. Name boundaries are the regex quantifiers
 * (1 + 63 = 64 characters accepted, 65 rejected).
 *
 * Every rejection test is paired with an accepting control built with the same helper, so a rejection cannot come from the
 * helper itself.
 */
class ToolRegistryTest {
    @Serializable data class Fine(
        @Doc("Row limit.") val limit: Int = 1
    )

    @Serializable data class OtherFine(
        @Doc("Other limit.") val other: Int = 1
    )

    @Serializable data class BadActor(
        val actor: String = ""
    )

    @Serializable data class BadDescribe(
        val describe: Boolean = false
    )

    @Serializable data class BadSelect(
        val select: String = ""
    )

    @Serializable data class BadOp(
        val op: String = ""
    )

    @Serializable data class BadKey(
        val idempotencyKey: String = ""
    )

    @Serializable
    sealed interface Shape {
        @Serializable data class Circle(
            val r: Int
        ) : Shape
    }

    @Serializable data class WithSealed(
        val shape: Shape
    )

    @Serializable data class WithSealedList(
        val shapes: List<Shape>
    )

    @Serializable data class WithSealedDeep(
        val inner: WithSealed
    )

    @Serializable data class TitleString(
        @Doc("Title.") val title: String
    )

    @Serializable data class TitleInt(
        @Doc("Title.") val title: Int
    )

    @Serializable data class TitleStringOptional(
        @Doc("Title.") val title: String = "x"
    )

    @Serializable data class TitleOtherDoc(
        @Doc("Another description.") val title: String
    )

    private fun <E : Any> op(
        name: String,
        request: KSerializer<E>,
        profile: ToolProfile = ToolProfile.KNOWLEDGE
    ): OpSpec<E, String> =
        OpSpec(name = name, doc = "An op.", profile = profile, capability = ApiCapability.WRITE_ITEMS, request = request, execute = {
            _,
            _,
            _
            ->
            Outcome.Ok("x")
        })

    private fun opsTool(
        name: String,
        vararg ops: OpSpec<*, String>
    ): OpsToolSpec<String> = OpsToolSpec(name, "An ops tool.", ops.toList(), FixtureTools.StringCodec) { buildJsonObject { } }

    private fun <R : Any> select(
        name: String,
        request: KSerializer<R>,
        profile: ToolProfile = ToolProfile.KNOWLEDGE
    ): SelectSpec<R, String> =
        SelectSpec.Single(name = name, doc = "A select.", profile = profile, request = request, handler = {
            _,
            _,
            _
            ->
            Outcome.Ok("x")
        }, data = {
            JsonPrimitive(it)
        })

    private fun selectTool(
        name: String,
        vararg selects: SelectSpec<*, *>
    ): SelectToolSpec = SelectToolSpec(name, "A select tool.", selects.toList())

    private fun rejected(build: () -> Unit): String = assertFailsWith<IllegalArgumentException> { build() }.message.orEmpty()

    private val fine = serializer<Fine>()

    // ------------------------------------------------------------------ accessors and controls

    @Test
    fun `S3 control - a well formed registry builds and exposes tools, get and visible`() {
        val fx = FixtureTools()
        val registry = fx.registry

        assertEquals(listOf("fx_read", "fx_peek", "fx_write", "fx_admin"), registry.tools.map { it.name }, "registration order is kept")
        assertSame(fx.readTool.name, registry["fx_read"]!!.name)
        assertNull(registry["nope"])
        assertNull(registry["FX_READ"], "lookup is exact, case sensitive")
        assertEquals(listOf("fx_read", "fx_write"), registry.visible(ToolProfile.KNOWLEDGE).map { it.name })
        assertEquals(listOf("fx_read", "fx_peek", "fx_write", "fx_admin"), registry.visible(ToolProfile.ORCHESTRATION).map { it.name })
    }

    @Test
    fun `S3 an empty registry is valid and shows nothing`() {
        val registry = ToolRegistry(emptyList())
        assertEquals(emptyList(), registry.tools)
        assertEquals(emptyList(), registry.visible(ToolProfile.ORCHESTRATION))
    }

    // ------------------------------------------------------------------ S3 reserved field names

    @Test
    fun `S3 the declared reserved-name sets are exactly the frozen ones`() {
        assertEquals(setOf("actor", "describe", "select"), SelectSpec.SELECT_RESERVED)
        assertEquals(setOf("actor", "describe", "select"), ToolRegistry.SELECT_FORBIDDEN_FIELDS)
        assertEquals(setOf("actor", "op", "idempotencyKey"), OpSpec.OP_FORBIDDEN_FIELDS)
        assertEquals(setOf("op", "idempotencyKey"), OpSpec.OP_RESERVED)
    }

    @Test
    fun `S3 control - a select and an op with ordinary fields are accepted`() {
        ToolRegistry(listOf(selectTool("sel_tool", select("pick", fine)), opsTool("ops_tool", op("do_it", fine))))
    }

    @Test
    fun `S3 a select request with actor, describe or select is rejected naming tool, select and field`() {
        val cases =
            mapOf(
                "actor" to serializer<BadActor>(),
                "describe" to serializer<BadDescribe>(),
                "select" to serializer<BadSelect>()
            )
        for ((field, ser) in cases) {
            val message = rejected { ToolRegistry(listOf(selectTool("sel_tool", select("pick", ser)))) }
            assertTrue("sel_tool" in message, "$field: message must name the tool: $message")
            assertTrue("pick" in message, "$field: message must name the select: $message")
            assertTrue(field in message, "$field: message must name the field: $message")
        }
    }

    @Test
    fun `S3 an op request with actor, op or idempotencyKey is rejected naming tool, op and field`() {
        val cases =
            mapOf(
                "actor" to serializer<BadActor>(),
                "op" to serializer<BadOp>(),
                "idempotencyKey" to serializer<BadKey>()
            )
        for ((field, ser) in cases) {
            val message = rejected { ToolRegistry(listOf(opsTool("ops_tool", op("do_it", ser)))) }
            assertTrue("ops_tool" in message, "$field: message must name the tool: $message")
            assertTrue("do_it" in message, "$field: message must name the op: $message")
            assertTrue(field in message, "$field: message must name the field: $message")
        }
    }

    @Test
    fun `S3 each reserved set binds only its own kind of tool`() {
        // describe and select are fine as op element fields; op and idempotencyKey are fine as select fields.
        ToolRegistry(listOf(opsTool("ops_tool", op("do_a", serializer<BadDescribe>()), op("do_b", serializer<BadSelect>()))))
        ToolRegistry(listOf(selectTool("sel_tool", select("pick_a", serializer<BadOp>()), select("pick_b", serializer<BadKey>()))))
    }

    @Test
    fun `S3 a sealed member is rejected directly, in a list and nested`() {
        for (ser in listOf(serializer<WithSealed>(), serializer<WithSealedList>(), serializer<WithSealedDeep>())) {
            val opMessage = rejected { ToolRegistry(listOf(opsTool("ops_tool", op("do_it", ser)))) }
            assertTrue("ops_tool" in opMessage && "do_it" in opMessage, "op: message must name tool and op: $opMessage")
            val selectMessage = rejected { ToolRegistry(listOf(selectTool("sel_tool", select("pick", ser)))) }
            assertTrue("sel_tool" in selectMessage && "pick" in selectMessage, "select: message must name tool and select: $selectMessage")
        }
    }

    @Test
    fun `S3 a sealed member message names the offending field`() {
        val message = rejected { ToolRegistry(listOf(opsTool("ops_tool", op("do_it", serializer<WithSealed>())))) }
        assertTrue("shape" in message, "the polymorphic property is called shape: $message")
    }

    // ------------------------------------------------------------------ S4 duplicates

    @Test
    fun `S4 a duplicate tool name is rejected, also across a select tool and an ops tool`() {
        val first = opsTool("dup_tool", op("do_it", fine))
        val message = rejected { ToolRegistry(listOf(first, opsTool("dup_tool", op("do_it", fine)))) }
        assertTrue("dup_tool" in message, message)
        val crossKind = rejected { ToolRegistry(listOf(first, selectTool("dup_tool", select("pick", fine)))) }
        assertTrue("dup_tool" in crossKind, crossKind)
        ToolRegistry(listOf(first, opsTool("other_tool", op("do_it", fine))))
    }

    @Test
    fun `S4 a duplicate op name inside one tool is rejected and the same op name in two tools is not`() {
        val message = rejected { ToolRegistry(listOf(opsTool("ops_tool", op("do_it", fine), op("do_it", serializer<OtherFine>())))) }
        assertTrue("ops_tool" in message && "do_it" in message, message)
        ToolRegistry(listOf(opsTool("tool_a", op("do_it", fine)), opsTool("tool_b", op("do_it", fine))))
    }

    @Test
    fun `S4 a duplicate select name inside one tool is rejected`() {
        val message =
            rejected { ToolRegistry(listOf(selectTool("sel_tool", select("pick", fine), select("pick", serializer<OtherFine>())))) }
        assertTrue("sel_tool" in message && "pick" in message, message)
    }

    // ------------------------------------------------------------------ S4 names

    @Test
    fun `S4 tool names follow the Messages API pattern`() {
        val max = "a" + "b".repeat(63)
        assertEquals(64, max.length)
        for (name in listOf("a", "items_read", "tool_2", "x9", max)) {
            ToolRegistry(listOf(opsTool(name, op("do_it", fine))))
        }
        for (name in listOf("Items_Read", "3x.y", "", "has-dash", "x y", "_lead", "9lives", max + "b", "tool.name", "toolN")) {
            val message = rejected { ToolRegistry(listOf(opsTool(name, op("do_it", fine)))) }
            if (name.isNotEmpty()) assertTrue(name in message, "message must name the tool '$name': $message")
        }
    }

    @Test
    fun `S4 op names follow the call_log pattern with no digits`() {
        val max = "a" + "b".repeat(63)
        for (name in listOf("a", "create_tree", "do_it", max)) {
            ToolRegistry(listOf(opsTool("ops_tool", op(name, fine))))
        }
        for (name in listOf("Op", "op2", "", "has-dash", "_lead", "2fast", max + "b", "do it")) {
            val message = rejected { ToolRegistry(listOf(opsTool("ops_tool", op(name, fine)))) }
            assertTrue("ops_tool" in message, "message must name the tool: $message")
            if (name.isNotEmpty()) assertTrue(name in message, "message must name the op '$name': $message")
        }
    }

    @Test
    fun `S4 the published name patterns are the frozen ones`() {
        assertEquals("^[a-z][a-z0-9_]{0,63}$", ToolRegistry.TOOL_NAME.pattern)
        assertEquals("^[a-z][a-z_]{0,63}$", ToolRegistry.OP_NAME.pattern)
    }

    // ------------------------------------------------------------------ S4 field conflicts

    @Test
    fun `S4 the same field with a different type in two ops of one tool is rejected naming the field`() {
        val message =
            rejected {
                ToolRegistry(
                    listOf(opsTool("ops_tool", op("make_a", serializer<TitleString>()), op("make_b", serializer<TitleInt>())))
                )
            }
        assertTrue("title" in message && "ops_tool" in message, message)
    }

    @Test
    fun `S4 the same field with a different type in two selects of one tool is rejected`() {
        val message =
            rejected {
                ToolRegistry(
                    listOf(selectTool("sel_tool", select("pick_a", serializer<TitleString>()), select("pick_b", serializer<TitleInt>())))
                )
            }
        assertTrue("title" in message && "sel_tool" in message, message)
    }

    @Test
    fun `S4 an identical field in two ops is accepted, also when only its optionality differs`() {
        ToolRegistry(listOf(opsTool("ops_tool", op("make_a", serializer<TitleString>()), op("make_b", serializer<TitleString>()))))
        ToolRegistry(listOf(opsTool("ops_tool", op("make_a", serializer<TitleString>()), op("make_b", serializer<TitleStringOptional>()))))
    }

    @Test
    fun `S4 the same field with a different generated description is a different schema and is rejected`() {
        rejected {
            ToolRegistry(
                listOf(opsTool("ops_tool", op("make_a", serializer<TitleString>()), op("make_b", serializer<TitleOtherDoc>())))
            )
        }
    }

    @Test
    fun `S4 the same field with a different type in two different tools is accepted`() {
        val registry =
            ToolRegistry(
                listOf(
                    opsTool("tool_a", op("make_a", serializer<TitleString>())),
                    opsTool("tool_b", op("make_b", serializer<TitleInt>()))
                )
            )
        assertNotNull(registry["tool_a"])
        assertNotNull(registry["tool_b"])
    }

    @Test
    fun `S4 a conflict is found even when the conflicting op is hidden from some profile`() {
        rejected {
            ToolRegistry(
                listOf(
                    opsTool(
                        "ops_tool",
                        op("make_a", serializer<TitleString>()),
                        op("make_b", serializer<TitleInt>(), ToolProfile.ORCHESTRATION)
                    )
                )
            )
        }
    }
}
