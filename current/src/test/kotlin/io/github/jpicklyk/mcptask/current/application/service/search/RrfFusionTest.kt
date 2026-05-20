package io.github.jpicklyk.mcptask.current.application.service.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RrfFusionTest {
    private val tolerance = 1e-9

    // ──────────────────────────────────────────────────────────────────────────
    // score() — unit formula verification
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `score at rank 1 with k=60 equals 1 divided by 61`(): Unit {
        assertEquals(1.0 / 61.0, RrfFusion.score(1), tolerance)
    }

    @Test
    fun `score at rank 2 with k=60 equals 1 divided by 62`(): Unit {
        assertEquals(1.0 / 62.0, RrfFusion.score(2), tolerance)
    }

    @Test
    fun `score uses provided k value when overridden`(): Unit {
        // k=0 → 1/(0+1) = 1.0 for rank 1
        assertEquals(1.0, RrfFusion.score(1, k = 0.0), tolerance)
    }

    @Test
    fun `score decreases as rank increases`(): Unit {
        val rank1 = RrfFusion.score(1)
        val rank2 = RrfFusion.score(2)
        val rank10 = RrfFusion.score(10)
        assertTrue(rank1 > rank2)
        assertTrue(rank2 > rank10)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // fuse — single source preserves rank order
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `single source: higher rank yields higher score`(): Unit {
        val source: Map<Long, Int> = mapOf(10L to 1, 20L to 2, 30L to 3)
        val result = RrfFusion.fuse(source)
        assertEquals(3, result.size)
        // rank 1 > rank 2 > rank 3  →  fused scores must be in descending order
        assertTrue(result[10L]!! > result[20L]!!)
        assertTrue(result[20L]!! > result[30L]!!)
    }

    @Test
    fun `single source: score values equal the plain RRF formula`(): Unit {
        val source: Map<Long, Int> = mapOf(5L to 1, 7L to 3)
        val result = RrfFusion.fuse(source)
        assertEquals(1.0 / 61.0, result[5L]!!, tolerance)
        assertEquals(1.0 / 63.0, result[7L]!!, tolerance)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // fuse — two sources: deduplication and score addition
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `document in both sources has higher score than document in one source only`(): Unit {
        // docA appears in both (rank 1 each); docB only in source1.
        val source1: Map<Long, Int> = mapOf(100L to 1, 200L to 2)
        val source2: Map<Long, Int> = mapOf(100L to 1)
        val result = RrfFusion.fuse(source1, source2)
        assertTrue(result[100L]!! > result[200L]!!)
    }

    @Test
    fun `two sources produce correct additive fused score for shared document`(): Unit {
        // docId=1 is rank 1 in both sources.
        val source1: Map<String, Int> = mapOf("a" to 1, "b" to 2)
        val source2: Map<String, Int> = mapOf("a" to 1, "c" to 2)
        val result = RrfFusion.fuse(source1, source2)

        // "a": 1/(60+1) + 1/(60+1) = 2/61
        assertEquals(2.0 / 61.0, result["a"]!!, tolerance)

        // "b": 1/(60+2) + 0 = 1/62
        assertEquals(1.0 / 62.0, result["b"]!!, tolerance)

        // "c": 0 + 1/(60+2) = 1/62
        assertEquals(1.0 / 62.0, result["c"]!!, tolerance)
    }

    @Test
    fun `result contains union of all document IDs across sources`(): Unit {
        val source1: Map<Int, Int> = mapOf(1 to 1, 2 to 2)
        val source2: Map<Int, Int> = mapOf(3 to 1, 4 to 2)
        val result = RrfFusion.fuse(source1, source2)
        assertEquals(setOf(1, 2, 3, 4), result.keys)
    }

    @Test
    fun `documents unique to each source do not appear in the other source score`(): Unit {
        val source1: Map<Long, Int> = mapOf(10L to 1)
        val source2: Map<Long, Int> = mapOf(20L to 1)
        val result = RrfFusion.fuse(source1, source2)
        // Both are rank 1 in their respective source, so scores are equal.
        assertEquals(1.0 / 61.0, result[10L]!!, tolerance)
        assertEquals(1.0 / 61.0, result[20L]!!, tolerance)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // fuse — edge cases
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `empty sources produce empty result`(): Unit {
        val result = RrfFusion.fuse(emptyMap<Long, Int>(), emptyMap())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single source with empty map and one populated produces correct result`(): Unit {
        val result = RrfFusion.fuse(emptyMap<String, Int>(), mapOf("x" to 2))
        assertEquals(1, result.size)
        assertEquals(1.0 / 62.0, result["x"]!!, tolerance)
    }

    @Test
    fun `vararg overload accepts three sources`(): Unit {
        val s1: Map<String, Int> = mapOf("a" to 1)
        val s2: Map<String, Int> = mapOf("a" to 1)
        val s3: Map<String, Int> = mapOf("a" to 1)
        val result = RrfFusion.fuse(s1, s2, s3)
        // 3 × 1/(60+1) = 3/61
        assertEquals(3.0 / 61.0, result["a"]!!, tolerance)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // k=60 constant verification
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `K constant is 60`(): Unit {
        assertEquals(60.0, RrfFusion.K, tolerance)
    }
}
