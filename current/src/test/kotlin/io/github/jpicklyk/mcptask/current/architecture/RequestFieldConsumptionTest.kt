package io.github.jpicklyk.mcptask.current.architecture

import io.github.jpicklyk.mcptask.current.architecture.requestfixtures.GhostSerializer
import io.github.jpicklyk.mcptask.current.architecture.requestfixtures.MissingClassSerializer
import io.github.jpicklyk.mcptask.current.architecture.requestfixtures.S11Node
import io.github.jpicklyk.mcptask.current.architecture.requestfixtures.S14Empty
import io.github.jpicklyk.mcptask.current.architecture.requestfixtures.S14Obj
import io.github.jpicklyk.mcptask.current.architecture.requestfixtures.S15AllReadReq
import io.github.jpicklyk.mcptask.current.architecture.requestfixtures.S15Req
import io.github.jpicklyk.mcptask.current.architecture.requestfixtures.S1Req
import io.github.jpicklyk.mcptask.current.architecture.requestfixtures.S2PosReq
import io.github.jpicklyk.mcptask.current.architecture.requestfixtures.S2Req
import io.github.jpicklyk.mcptask.current.architecture.requestfixtures.S3Req
import io.github.jpicklyk.mcptask.current.architecture.requestfixtures.S4Req
import io.github.jpicklyk.mcptask.current.architecture.requestfixtures.S5Req
import io.github.jpicklyk.mcptask.current.architecture.requestfixtures.S6Req
import io.github.jpicklyk.mcptask.current.architecture.requestfixtures.S7Req
import io.github.jpicklyk.mcptask.current.architecture.requestfixtures.S8Outer
import io.github.jpicklyk.mcptask.current.architecture.requestfixtures.S9RootA
import io.github.jpicklyk.mcptask.current.architecture.requestfixtures.S9RootB
import io.github.jpicklyk.mcptask.current.architecture.requestfixtures.S9Shared
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import kotlinx.serialization.KSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Self-tests of [RequestFieldConsumption] on fixture request classes (test-plan scenarios S1-S14, plus S15-S18 derived from
 * task-scope AC1-AC3). Oracles are the task-scope acceptance criteria: AC1 (what an entry names), AC2 (the read rule R),
 * AC3 (reachability, cycle safety, fail-closed), AC4 (two-way ratchet), AC5 (zero scanned classes throws).
 *
 * Every fixture field that must come out "read" is read by a never-called reader function in the fixtures package; a field
 * that must come out "unread" has no read of the kinds R admits.
 */
class RequestFieldConsumptionTest {
    private val fixturePackage = "io.github.jpicklyk.mcptask.current.architecture.requestfixtures."
    private val classRoot: Path =
        Path.of(
            S1Req::class.java.protectionDomain.codeSource.location
                .toURI()
        )

    private fun root(
        label: String,
        serializer: KSerializer<*>
    ) = RequestRoot(label, serializer)

    private fun scan(
        vararg roots: RequestRoot,
        include: (String) -> Boolean = { it.startsWith(fixturePackage) }
    ): List<UnreadField> = RequestFieldConsumption.unreadFields(roots.toList(), classRoot, include)

    private fun nameOf(type: Class<*>) = type.name

    // ------------------------------------------------------------------ S1-S4 happy

    @Test
    fun `S1 fields read through their getters are not reported`() {
        assertEquals(emptyList(), scan(root("t/x", S1Req.serializer())))
    }

    @Test
    fun `S2 a field read only by destructuring is not reported`() {
        assertEquals(emptyList(), scan(root("t/x", S2Req.serializer())))
    }

    @Test
    fun `S2b destructuring only the first component reads the first field and leaves the second unread`() {
        assertEquals(
            listOf(UnreadField(nameOf(S2PosReq::class.java), "b", listOf("t/x"))),
            scan(root("t/x", S2PosReq.serializer()))
        )
    }

    @Test
    fun `S3 is-prefixed Boolean, IdRef, nullable IdRef, JsonObject, enum and List fields read are not reported and do not throw`() {
        assertEquals(emptyList(), scan(root("t/x", S3Req.serializer())))
    }

    @Test
    fun `S3 non-recursion holds when the include filter also admits IdRef package`() {
        // IdRef is a custom-serializer value class in the main source set. If the engine recursed into it, its class file would
        // be absent from the fixture classRoot and the scan would throw (AC3 fail-closed) instead of returning cleanly.
        assertEquals(
            emptyList(),
            scan(root("t/x", S3Req.serializer()), include = { it.startsWith("io.github.jpicklyk.mcptask.") })
        )
    }

    @Test
    fun `S4 a field read only inside an owner member function is not reported`() {
        assertEquals(emptyList(), scan(root("t/x", S4Req.serializer())))
    }

    // ------------------------------------------------------------------ S5-S8 failure

    @Test
    fun `S5 an unread field is reported with owner, field and label, and another class same-named getter does not count`() {
        assertEquals(
            listOf(UnreadField(nameOf(S5Req::class.java), "c", listOf("t/x"))),
            scan(root("t/x", S5Req.serializer()))
        )
    }

    @Test
    fun `S6 toString, equals, hashCode, copy and JSON encoding do not count as reads`() {
        assertEquals(
            listOf(UnreadField(nameOf(S6Req::class.java), "c", listOf("t/x"))),
            scan(root("t/x", S6Req.serializer()))
        )
    }

    @Test
    fun `S7 a field read only in the owner init block is unread`() {
        assertEquals(
            listOf(UnreadField(nameOf(S7Req::class.java), "c", listOf("t/x"))),
            scan(root("t/x", S7Req.serializer()))
        )
    }

    @Test
    fun `S8 unread fields of a nested object, a list element and a map value are all reported, sorted by key`() {
        val actual = scan(root("t/x", S8Outer.serializer()))
        assertEquals(
            listOf(
                UnreadField(nameOf(S8Outer.Inner::class.java), "x", listOf("t/x")),
                UnreadField(nameOf(S8Outer.Item::class.java), "y", listOf("t/x")),
                UnreadField(nameOf(S8Outer.Val::class.java), "z", listOf("t/x"))
            ),
            actual
        )
        assertEquals(actual.map { it.key }.sorted(), actual.map { it.key })
    }

    // ------------------------------------------------------------------ S9-S14 edge

    @Test
    fun `S9 a nested class reached from two roots is one entry carrying both labels`() {
        val actual = scan(root("t/a", S9RootA.serializer()), root("t/b", S9RootB.serializer()))
        assertEquals(1, actual.size)
        assertEquals(nameOf(S9Shared::class.java), actual.single().owner)
        assertEquals("x", actual.single().field)
        assertEquals(listOf("t/a", "t/b"), actual.single().labels)
    }

    @Test
    fun `S9 reversing the order of the roots still yields one entry with both labels and no duplicates`() {
        val actual = scan(root("t/b", S9RootB.serializer()), root("t/a", S9RootA.serializer()))
        assertEquals(1, actual.size)
        assertEquals(listOf("t/a", "t/b"), actual.single().labels.sorted())
    }

    @Test
    fun `S10 an include filter that matches no class throws`() {
        assertFailsWith<IllegalStateException> { scan(root("t/x", S1Req.serializer()), include = { false }) }
        assertFailsWith<IllegalStateException> {
            scan(root("t/x", S1Req.serializer()), include = { it.startsWith("no.such.package.") })
        }
    }

    @Test
    fun `S11 a self-referential request terminates and reports its unread field once`() {
        val actual =
            assertTimeoutPreemptively(Duration.ofSeconds(30)) {
                scan(root("t/x", S11Node.serializer()))
            }
        assertEquals(listOf(UnreadField(nameOf(S11Node::class.java), "v", listOf("t/x"))), actual)
    }

    @Test
    fun `S12 ratchet is clean when actual equals the baseline`() {
        val k1 = UnreadField("p.Owner", "k1", listOf("t/x"))
        assertNull(RequestFieldConsumption.ratchet(listOf(k1), setOf(k1.key)))
        assertNull(RequestFieldConsumption.ratchet(emptyList(), emptySet()))
    }

    @Test
    fun `S12 ratchet fails and names a new unread field that is not in the baseline`() {
        val k1 = UnreadField("p.Owner", "k1", listOf("t/x"))
        val k2 = UnreadField("p.Owner", "k2", listOf("t/y"))
        val message = RequestFieldConsumption.ratchet(listOf(k1, k2), setOf(k1.key))
        assertNotNull(message)
        assertTrue(k2.key in message, "message must name the new unread key ${k2.key}: $message")
    }

    @Test
    fun `S12 ratchet fails and names a stale baseline line whose field is no longer unread`() {
        val k1 = UnreadField("p.Owner", "k1", listOf("t/x"))
        val stale = UnreadField("p.Owner", "k3", listOf("t/z"))
        val message = RequestFieldConsumption.ratchet(listOf(k1), setOf(k1.key, stale.key))
        assertNotNull(message)
        assertTrue(stale.key in message, "message must name the stale baseline key ${stale.key}: $message")
    }

    @Test
    fun `S12 ratchet reports a new field and a stale line together when both exist`() {
        val newer = UnreadField("p.Owner", "k2", listOf("t/y"))
        val stale = UnreadField("p.Owner", "k3", listOf("t/z"))
        val message = RequestFieldConsumption.ratchet(listOf(newer), setOf(stale.key))
        assertNotNull(message)
        assertTrue(newer.key in message, "message must name ${newer.key}: $message")
        assertTrue(stale.key in message, "message must name ${stale.key}: $message")
    }

    @Test
    fun `S13 roots over the production registry are one distinctly labelled root per select and op`() {
        val specs = buildToolSpecs()
        val expected = mutableListOf<Pair<String, String>>()
        for (spec in specs) {
            when (spec) {
                is SelectToolSpec -> spec.selects.forEach { expected += "${spec.name}/${it.name}" to it.request.descriptor.serialName }
                is OpsToolSpec<*> -> spec.ops.forEach { expected += "${spec.name}/${it.name}" to it.request.descriptor.serialName }
            }
        }
        val roots = RequestFieldConsumption.roots(specs)

        assertTrue(expected.isNotEmpty(), "the production registry must declare at least one select or op")
        assertEquals(expected.size, roots.size)
        assertEquals(roots.size, roots.map { it.label }.distinct().size)
        assertEquals(expected.map { it.first }.sorted(), roots.map { it.label }.sorted())
        assertEquals(
            expected.sortedBy { it.first },
            roots.map { it.label to it.serializer.descriptor.serialName }.sortedBy { it.first }
        )
    }

    @Test
    fun `S14 a zero-field object request and a zero-field class request report nothing and do not throw`() {
        assertEquals(emptyList(), scan(root("t/obj", S14Obj.serializer())))
        assertEquals(emptyList(), scan(root("t/empty", S14Empty.serializer())))
    }

    // ------------------------------------------------------------------ S15-S18 derived from AC1-AC3

    @Test
    fun `S15 a public JvmField read counts as a read and an unread JvmField is reported`() {
        assertEquals(emptyList(), scan(root("t/x", S15AllReadReq.serializer())))
        assertEquals(
            listOf(UnreadField(nameOf(S15Req::class.java), "c", listOf("t/x"))),
            scan(root("t/x", S15Req.serializer()))
        )
    }

    @Test
    fun `S16 a descriptor element with no matching property throws naming the owner and the field`() {
        val ex = assertFailsWith<IllegalStateException> { scan(root("t/g", GhostSerializer)) }
        val message = ex.message.orEmpty()
        assertTrue("GhostHolder" in message, "message must name the owner: $message")
        assertTrue("ghost" in message, "message must name the field: $message")
    }

    @Test
    fun `S17 a descriptor naming a class that cannot be resolved throws naming the owner`() {
        val ex = assertFailsWith<IllegalStateException> { scan(root("t/m", MissingClassSerializer)) }
        assertTrue("DoesNotExistAnywhere" in ex.message.orEmpty(), "message must name the owner: ${ex.message}")
    }

    @Test
    fun `S18 scanning twice returns equal results`() {
        val first = scan(root("t/a", S9RootA.serializer()), root("t/b", S9RootB.serializer()), root("t/o", S8Outer.serializer()))
        val second = scan(root("t/a", S9RootA.serializer()), root("t/b", S9RootB.serializer()), root("t/o", S8Outer.serializer()))
        assertEquals(first, second)
    }
}
