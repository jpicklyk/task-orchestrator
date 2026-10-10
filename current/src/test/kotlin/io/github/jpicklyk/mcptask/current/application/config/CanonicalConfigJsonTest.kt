package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f75eb22e / Q8, scenario S13) for [CanonicalConfigJson] and the
 * [ConfigDocumentParser.fromRoot] entry point.
 *
 * Oracle: task-scope section 1.4 (the codec rules written out there: keys sorted by String.compareTo at every depth, no
 * insignificant whitespace, non-string keys stringified, Int/Long/Double/Boolean/null as JSON, any other scalar as its
 * toString(), a null or empty root as "{}", decode yielding LinkedHashMap/ArrayList with Int when it fits else Long and
 * Double for fractions) and decision D1. Every expected string below is written out by hand from those rules; none was
 * produced by running the codec.
 */
class CanonicalConfigJsonTest {
    private fun parseRoot(yaml: String): Map<String, Any>? =
        assertIs<ConfigDocumentParser.Outcome.Parsed>(YamlConfigDocumentParser.parse(yaml, warnOnMissingSchemas = false)).rawRoot

    @Suppress("UNCHECKED_CAST")
    private fun anyKeyed(vararg pairs: Pair<Any?, Any?>): Map<String, Any?> = linkedMapOf(*pairs) as Map<String, Any?>

    // ---------------------------------------------------------------------------------------------
    // encode
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a null or empty root encodes as an empty object`() {
        assertEquals("{}", CanonicalConfigJson.encode(null))
        assertEquals("{}", CanonicalConfigJson.encode(emptyMap()))
        assertEquals("{}", CanonicalConfigJson.encode(linkedMapOf<String, Any?>()))
    }

    @Test
    fun `object keys are sorted at every depth including objects inside lists`() {
        val root =
            linkedMapOf<String, Any?>(
                "b" to 1,
                "a" to linkedMapOf<String, Any?>("z" to 1, "y" to listOf(linkedMapOf<String, Any?>("q" to 1, "p" to 2)))
            )

        assertEquals("{\"a\":{\"y\":[{\"p\":2,\"q\":1}],\"z\":1},\"b\":1}", CanonicalConfigJson.encode(root))
    }

    @Test
    fun `keys are ordered by String compareTo, so uppercase precedes lowercase and digits compare as text`() {
        val root = linkedMapOf<String, Any?>("a" to 2, "B" to 3, "9" to 5, "10" to 4, "" to 6)

        assertEquals("{\"\":6,\"10\":4,\"9\":5,\"B\":3,\"a\":2}", CanonicalConfigJson.encode(root))
    }

    @Test
    fun `the output has no whitespace outside string values`() {
        val encoded =
            CanonicalConfigJson.encode(
                linkedMapOf<String, Any?>(
                    "k" to listOf(1, 2),
                    "m" to linkedMapOf<String, Any?>("x" to "a b")
                )
            )

        assertEquals("{\"k\":[1,2],\"m\":{\"x\":\"a b\"}}", encoded)
        assertTrue(!encoded.contains('\n') && !encoded.contains("  ") && !encoded.contains(": ") && !encoded.contains(", "), encoded)
    }

    @Test
    fun `scalars Int Long Double Boolean and null are written as JSON`() {
        val root =
            linkedMapOf<String, Any?>(
                "int" to 42,
                "neg" to -7,
                "long" to 10_000_000_000L,
                "dbl" to 1.5,
                "yes" to true,
                "no" to false,
                "nil" to null,
                "str" to "text"
            )

        assertEquals(
            "{\"dbl\":1.5,\"int\":42,\"long\":10000000000,\"neg\":-7,\"nil\":null,\"no\":false,\"str\":\"text\",\"yes\":true}",
            CanonicalConfigJson.encode(root)
        )
    }

    @Test
    fun `strings are escaped as JSON`() {
        val encoded = CanonicalConfigJson.encode(linkedMapOf<String, Any?>("k" to "a\"b\\c\nd\te"))

        assertEquals("{\"k\":\"a\\\"b\\\\c\\nd\\te\"}", encoded)
    }

    @Test
    fun `a list keeps its element order and null elements`() {
        assertEquals(
            "{\"l\":[\"z\",\"a\",null,3]}",
            CanonicalConfigJson.encode(linkedMapOf<String, Any?>("l" to listOf("z", "a", null, 3)))
        )
    }

    @Test
    fun `empty nested containers are preserved`() {
        val root = linkedMapOf<String, Any?>("e" to emptyMap<String, Any?>(), "l" to emptyList<Any?>())

        assertEquals("{\"e\":{},\"l\":[]}", CanonicalConfigJson.encode(root))
    }

    @Test
    fun `non-string keys are stringified before sorting`() {
        assertEquals("{\"1\":\"a\",\"b\":2,\"true\":3}", CanonicalConfigJson.encode(anyKeyed(1 to "a", "b" to 2, true to 3)))
    }

    @Test
    fun `any other scalar is written as the string form of the value`() {
        class Odd {
            override fun toString(): String = "odd-value"
        }
        val date = java.util.Date(0L)

        val encoded = CanonicalConfigJson.encode(linkedMapOf<String, Any?>("d" to date, "o" to Odd()))

        assertEquals("{\"d\":\"$date\",\"o\":\"odd-value\"}", encoded)
    }

    @Test
    fun `the same logical map encodes identically whatever its insertion order`() {
        val one = linkedMapOf<String, Any?>("a" to 1, "b" to linkedMapOf<String, Any?>("x" to 1, "y" to 2), "c" to listOf(1, 2))
        val two = linkedMapOf<String, Any?>("c" to listOf(1, 2), "b" to linkedMapOf<String, Any?>("y" to 2, "x" to 1), "a" to 1)

        assertEquals(CanonicalConfigJson.encode(one), CanonicalConfigJson.encode(two))
    }

    // ---------------------------------------------------------------------------------------------
    // decode
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `decode yields LinkedHashMap and ArrayList with Int when the number fits, Long otherwise, Double for fractions`() {
        val decoded =
            CanonicalConfigJson.decode(
                "{\"a\":1,\"big\":3000000000,\"d\":{\"inner\":[1,2.5,\"x\",null,true,10000000000]},\"frac\":1.25,\"max\":2147483647,\"min\":-2147483648,\"over\":2147483648,\"under\":-2147483649}"
            )

        val map = assertIs<LinkedHashMap<String, Any?>>(decoded)
        assertEquals(1, map["a"])
        assertIs<Int>(map["a"])
        assertEquals(3_000_000_000L, map["big"])
        assertIs<Long>(map["big"])
        assertEquals(1.25, map["frac"])
        assertIs<Double>(map["frac"])
        assertIs<Int>(map["max"])
        assertEquals(Int.MAX_VALUE, map["max"])
        assertIs<Int>(map["min"])
        assertEquals(Int.MIN_VALUE, map["min"])
        assertIs<Long>(map["over"])
        assertEquals(2_147_483_648L, map["over"])
        assertIs<Long>(map["under"])
        assertEquals(-2_147_483_649L, map["under"])

        val inner = assertIs<LinkedHashMap<String, Any?>>(map["d"])["inner"]
        val list = assertIs<ArrayList<Any?>>(inner)
        assertEquals(listOf<Any?>(1, 2.5, "x", null, true, 10_000_000_000L), list)
        assertIs<Int>(list[0])
        assertIs<Double>(list[1])
        assertIs<Long>(list[5])
    }

    @Test
    fun `decode preserves document key order and decodes an empty object`() {
        val decoded = assertIs<LinkedHashMap<String, Any?>>(CanonicalConfigJson.decode("{\"b\":1,\"a\":2}"))
        assertEquals(listOf("b", "a"), decoded.keys.toList())

        assertEquals(emptyMap(), CanonicalConfigJson.decode("{}"))
    }

    @Test
    fun `decode of an encoded map restores it and a second encode is byte-identical`() {
        val original =
            linkedMapOf<String, Any?>(
                "name" to "caf\u00e9 \u2603 \"quoted\" back\\slash\nnewline\ttab \u0001",
                "n" to 7,
                "list" to listOf("a", 1, 1.5, true, null, linkedMapOf<String, Any?>("k" to "v")),
                "nested" to linkedMapOf<String, Any?>("deeper" to linkedMapOf<String, Any?>("deepest" to emptyList<Any?>()))
            )

        val encoded = CanonicalConfigJson.encode(original)
        val decoded = CanonicalConfigJson.decode(encoded)

        assertEquals(original, decoded)
        assertEquals(encoded, CanonicalConfigJson.encode(decoded), "encode(decode(encode(x))) == encode(x)")
    }

    @Test
    fun `the model version constant is 1`() {
        assertEquals(1, CanonicalConfigJson.MODEL_VERSION)
    }

    // ---------------------------------------------------------------------------------------------
    // S13 - the codec over parsed YAML, and fromRoot
    // ---------------------------------------------------------------------------------------------

    private val layoutA =
        """
        work_item_schemas:
          feature-task:
            notes:
              - key: spec
                role: queue
                required: true
        note_limits:
          mode: reject
        """.trimIndent()

    // The same data as layoutA: key order swapped, flow style for the map entries, comments and different indentation.
    private val layoutB =
        """
        # a comment that is not data
        note_limits: {mode: reject}
        work_item_schemas:
            feature-task:
                notes:
                    - {required: true, role: queue, key: spec}
        """.trimIndent()

    @Test
    fun `S13 two YAML layouts of the same data produce the same canonical JSON, written out by hand`() {
        val expected =
            "{\"note_limits\":{\"mode\":\"reject\"},\"work_item_schemas\":{\"feature-task\":{\"notes\":[{\"key\":\"spec\",\"required\":true,\"role\":\"queue\"}]}}}"

        assertEquals(expected, CanonicalConfigJson.encode(parseRoot(layoutA)))
        assertEquals(expected, CanonicalConfigJson.encode(parseRoot(layoutB)))
        assertNotEquals(layoutA, layoutB, "control: the two texts differ, so their fingerprints would differ")
    }

    @Test
    fun `S13 a YAML date scalar is written as the string form of the parsed value`() {
        val root = parseRoot("d: 2026-03-01\nn: 5\n")

        val parsedDate = root?.get("d")
        assertEquals("{\"d\":\"$parsedDate\",\"n\":5}", CanonicalConfigJson.encode(root))
    }

    @Test
    fun `S13 an empty or comment-only YAML document encodes as an empty object`() {
        assertEquals("{}", CanonicalConfigJson.encode(parseRoot("")))
        assertEquals("{}", CanonicalConfigJson.encode(parseRoot("# nothing here\n")))
    }

    private val richYaml =
        """
        work_item_schemas:
          bug-fix:
            lifecycle: auto
            notes:
              - key: repro-steps
                role: queue
                required: true
                description: "Repro steps"
                skill: "repro-review"
          default:
            notes:
              - key: implementation-notes
                role: work
                required: false
        traits:
          needs-migration-review:
            notes:
              - key: migration-assessment
                role: queue
                required: true
                description: "Migration assessment"
                skill: "migration-review"
        note_limits:
          mode: reject
        status_labels:
          start: "root-started"
          complete: null
        """.trimIndent()

    @Test
    fun `S13 fromRoot over the decoded canonical JSON yields the same document as parsing the YAML`() {
        val parsed = assertIs<ConfigDocumentParser.Outcome.Parsed>(YamlConfigDocumentParser.parse(richYaml, warnOnMissingSchemas = false))
        val canonical = CanonicalConfigJson.encode(parsed.rawRoot)

        @Suppress("UNCHECKED_CAST")
        val rebuilt =
            YamlConfigDocumentParser.fromRoot(
                CanonicalConfigJson.decode(canonical) as Map<String, Any>?,
                warnOnMissingSchemas = false
            )

        assertEquals(parsed.document.workItemSchemas, rebuilt.workItemSchemas)
        assertEquals(parsed.document.traits, rebuilt.traits)
        assertEquals(parsed.document.noteLimitsMode, rebuilt.noteLimitsMode)
        assertEquals(parsed.document.statusLabels, rebuilt.statusLabels)
        assertEquals(setOf("bug-fix", "default"), rebuilt.workItemSchemas.keys, "control: the document is not trivially empty")
        assertEquals("reject", rebuilt.noteLimitsMode)
        val labels = assertNotNull(rebuilt.statusLabels)
        assertTrue(labels.containsKey("complete"), "an explicit null label survives the canonical round trip")
        assertNull(labels["complete"])
    }

    @Test
    fun `S13 fromRoot of a null root is the empty document`() {
        assertEquals(ConfigDocument.EMPTY, YamlConfigDocumentParser.fromRoot(null, warnOnMissingSchemas = false))
        assertEquals(ConfigDocument.EMPTY, YamlConfigDocumentParser.fromRoot(null, warnOnMissingSchemas = true))
    }

    @Test
    fun `S13 fromRoot of an empty canonical object has no schemas`() {
        @Suppress("UNCHECKED_CAST")
        val rebuilt = YamlConfigDocumentParser.fromRoot(CanonicalConfigJson.decode("{}") as Map<String, Any>?, warnOnMissingSchemas = false)

        assertTrue(rebuilt.workItemSchemas.isEmpty())
        assertTrue(rebuilt.traits.isEmpty())
    }
}
