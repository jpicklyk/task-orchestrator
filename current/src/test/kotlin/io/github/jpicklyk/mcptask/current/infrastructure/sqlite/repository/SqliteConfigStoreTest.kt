package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.ConfigAppend
import io.github.jpicklyk.mcptask.current.application.port.ConfigStore
import io.github.jpicklyk.mcptask.current.domain.model.ConfigAppendOutcome
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.ConfigVersion
import io.github.jpicklyk.mcptask.current.domain.model.ConfigVersionSource
import io.github.jpicklyk.mcptask.current.domain.model.FingerprintRelation
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.BaselineDataset
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f75eb22e / Q8) for the versioned config store [ConfigStore] over the real
 * SQLite implementation: version numbering, the head pointer, immutability, identical-content no-op, both
 * compare-and-set guards, the fast-forward classification, history paging and the canonical-JSON fill.
 *
 * Oracles: task-scope section 1.3 (port contract and kdoc), 1.11 AC1-AC5, AC7-AC8 (fast-forward epoch rule written out in
 * AC5), the frozen DDL in migration-assessment section 1. Every expected value is derived from those clauses: version
 * numbers by counting appends, fingerprints through the store's own pure `computeFingerprint`, never from stored output.
 */
class SqliteConfigStoreTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val provider get() = sqlite.repositoryProvider()
    private val store: ConfigStore get() = provider.configStore()

    private val t0: Instant = Instant.parse("2026-03-01T10:00:00.123Z")

    private fun at(seconds: Long): Instant = t0.plusSeconds(seconds)

    private suspend fun newRoot(title: String = "Root"): UUID =
        provider.workItemRepository().create(WorkItem(title = title, type = "project", depth = 0)).id

    private fun payload(
        yaml: String,
        canonical: String? = "{}",
        source: ConfigVersionSource = ConfigVersionSource.PUSH,
        principal: String? = null,
        req: String? = null,
        warnings: List<String> = emptyList()
    ) = ConfigAppend(
        yaml = yaml,
        fingerprint = store.computeFingerprint(yaml),
        canonicalJson = canonical,
        modelVersion = if (canonical == null) null else 1,
        source = source,
        principalId = principal,
        reqId = req,
        warnings = warnings
    )

    private suspend fun append(
        root: UUID,
        payload: ConfigAppend,
        now: Instant = t0,
        expectedVersion: Int? = null,
        expectedFingerprint: String? = null,
        rejectSuperseded: Boolean = false
    ): ConfigAppendOutcome =
        store.append(
            scope = ConfigScope.ROOT,
            rootId = root,
            append = payload,
            expectedVersion = expectedVersion,
            expectedFingerprint = expectedFingerprint,
            rejectSuperseded = rejectSuperseded,
            now = now
        )

    private suspend fun appendApplied(
        root: UUID,
        yaml: String,
        now: Instant = t0
    ): ConfigVersion {
        val applied = assertIs<ConfigAppendOutcome.Applied>(append(root, payload(yaml), now))
        assertTrue(applied.appended, "fixture append of '$yaml' must write a row")
        return applied.version
    }

    private suspend fun reset(
        root: UUID,
        now: Instant = t0
    ): ConfigVersion =
        assertIs<ConfigAppendOutcome.Applied>(
            append(root, payload("", canonical = "{}", source = ConfigVersionSource.RESET), now)
        ).version

    private fun addKnownFingerprint(
        root: UUID,
        fingerprint: String
    ) {
        DriverManager.getConnection(sqlite.jdbcUrl).use { c ->
            c.prepareStatement("INSERT INTO config_known_fingerprints (root_id, fingerprint) VALUES (?, ?)").use { ps ->
                ps.setBytes(1, BaselineDataset.bytes(root))
                ps.setString(2, fingerprint)
                ps.executeUpdate()
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // S2 - first append
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S2 the first append is version 1, stores every field, and moves the head`(): Unit =
        runBlocking {
            val root = newRoot()
            assertNull(store.head(ConfigScope.ROOT, root), "control: no head before the first append")

            val outcome =
                append(
                    root,
                    payload(
                        yaml = "a: 1\n",
                        canonical = "{\"a\":1}",
                        source = ConfigVersionSource.PUSH,
                        principal = "agent-1",
                        req = "k7f3q9ab",
                        warnings = listOf("w-one", "w-two")
                    ),
                    now = at(5)
                )

            val applied = assertIs<ConfigAppendOutcome.Applied>(outcome)
            assertTrue(applied.appended)
            val v = applied.version
            assertEquals(1, v.version)
            assertEquals(ConfigScope.ROOT, v.scope)
            assertEquals(root, v.rootId)
            assertEquals("a: 1\n", v.yaml)
            assertEquals(store.computeFingerprint("a: 1\n"), v.fingerprint)
            assertEquals("{\"a\":1}", v.canonicalJson)
            assertEquals(1, v.modelVersion)
            assertEquals(ConfigVersionSource.PUSH, v.source)
            assertEquals("agent-1", v.principalId)
            assertEquals("k7f3q9ab", v.reqId)
            assertEquals(at(5), v.createdAt)
            assertEquals(listOf("w-one", "w-two"), v.warnings)

            val head = assertNotNull(store.head(ConfigScope.ROOT, root))
            assertEquals(v.id, head.versionId)
            assertEquals(1, head.version)
            assertEquals(v.fingerprint, head.fingerprint)
            assertEquals(ConfigVersionSource.PUSH, head.source)
            assertEquals(ConfigScope.ROOT, head.scope)
            assertEquals(root, head.rootId)

            assertEquals(v, store.version(v.id), "version(id) returns the stored row field for field")
            assertEquals(v, store.versionAt(ConfigScope.ROOT, root, 1), "versionAt returns the same row")
        }

    @Test
    fun `S2 a nullable-metadata append stores nulls and an empty warnings list`(): Unit =
        runBlocking {
            val root = newRoot()

            val v = appendApplied(root, "b: 2\n")

            assertNull(v.principalId)
            assertNull(v.reqId)
            assertEquals(emptyList(), v.warnings)
            assertEquals(v, store.versionAt(ConfigScope.ROOT, root, 1))
        }

    @Test
    fun `S3 a second distinct append is version 2, the head moves, and version 1 is left untouched`(): Unit =
        runBlocking {
            val root = newRoot()
            val v1 = appendApplied(root, "a: 1\n", at(1))
            val v2 = appendApplied(root, "a: 2\n", at(2))

            assertEquals(2, v2.version)
            assertNotEquals(v1.id, v2.id)
            val head = assertNotNull(store.head(ConfigScope.ROOT, root))
            assertEquals(v2.id, head.versionId)
            assertEquals(2, head.version)
            assertEquals(v1, store.versionAt(ConfigScope.ROOT, root, 1), "an append never rewrites an earlier version")
            assertEquals(v2, store.versionAt(ConfigScope.ROOT, root, 2))
        }

    @Test
    fun `S3 version numbers and heads are per root`(): Unit =
        runBlocking {
            val a = newRoot("A")
            val b = newRoot("B")
            appendApplied(a, "a: 1\n")
            appendApplied(a, "a: 2\n")
            appendApplied(a, "a: 3\n")

            val bFirst = appendApplied(b, "b: 1\n")

            assertEquals(1, bFirst.version, "root B starts at version 1 although root A has three")
            assertEquals(3, store.head(ConfigScope.ROOT, a)?.version)
            assertEquals(1, store.head(ConfigScope.ROOT, b)?.version)
            assertNull(store.versionAt(ConfigScope.ROOT, b, 2))
        }

    @Test
    fun `lookups for absent data return null`(): Unit =
        runBlocking {
            val root = newRoot()
            assertNull(store.head(ConfigScope.ROOT, root))
            assertNull(store.version(UUID.randomUUID()))
            assertNull(store.versionAt(ConfigScope.ROOT, root, 1))
            appendApplied(root, "a: 1\n")
            assertNull(store.versionAt(ConfigScope.ROOT, root, 2))
            assertNull(store.versionAt(ConfigScope.ROOT, root, 0))
            assertNull(store.head(ConfigScope.ROOT, UUID.randomUUID()), "unknown root has no head")
        }

    // ---------------------------------------------------------------------------------------------
    // S4 - identical content is a no-op
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S4 appending content identical to the head writes nothing and reports the head`(): Unit =
        runBlocking {
            val root = newRoot()
            val v1 = appendApplied(root, "a: 1\n", at(1))

            val again = append(root, payload("a: 1\n", principal = "someone-else", req = "other-req"), at(9))

            val applied = assertIs<ConfigAppendOutcome.Applied>(again)
            assertFalse(applied.appended, "identical re-append must report appended=false")
            assertEquals(v1, applied.version, "the head row is returned unchanged (metadata of the first write kept)")
            assertEquals(1, store.history(ConfigScope.ROOT, root, 50).size, "no second row exists")
            assertEquals(1, store.head(ConfigScope.ROOT, root)?.version)
        }

    @Test
    fun `S4 content equal to an OLDER version but not to the head is appended normally`(): Unit =
        runBlocking {
            val root = newRoot()
            appendApplied(root, "a: 1\n", at(1))
            appendApplied(root, "a: 2\n", at(2))

            val back = appendApplied(root, "a: 1\n", at(3))

            assertEquals(3, back.version, "only head-identical content is a no-op")
            assertEquals(3, store.history(ConfigScope.ROOT, root, 50).size)
        }

    // ---------------------------------------------------------------------------------------------
    // S5 - compare-and-set guards
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S5 expectedVersion different from the head version is PreconditionFailed and appends nothing`(): Unit =
        runBlocking {
            val root = newRoot()
            appendApplied(root, "a: 1\n", at(1))
            val v2 = appendApplied(root, "a: 2\n", at(2))

            val outcome = append(root, payload("a: 3\n"), at(3), expectedVersion = 1)

            val failed = assertIs<ConfigAppendOutcome.PreconditionFailed>(outcome)
            assertEquals(2, failed.currentVersion)
            assertEquals(v2.fingerprint, failed.currentFingerprint)
            assertEquals(2, store.head(ConfigScope.ROOT, root)?.version, "head did not move")
            assertEquals(2, store.history(ConfigScope.ROOT, root, 50).size, "no row was written")
        }

    @Test
    fun `S5 expectedVersion equal to the head version applies`(): Unit =
        runBlocking {
            val root = newRoot()
            appendApplied(root, "a: 1\n", at(1))
            appendApplied(root, "a: 2\n", at(2))

            val outcome = append(root, payload("a: 3\n"), at(3), expectedVersion = 2)

            val applied = assertIs<ConfigAppendOutcome.Applied>(outcome)
            assertEquals(3, applied.version.version)
        }

    @Test
    fun `S5 an expectedVersion one above the head is also a mismatch`(): Unit =
        runBlocking {
            val root = newRoot()
            appendApplied(root, "a: 1\n", at(1))

            val outcome = append(root, payload("a: 2\n"), at(2), expectedVersion = 2)

            assertIs<ConfigAppendOutcome.PreconditionFailed>(outcome)
            assertEquals(1, store.history(ConfigScope.ROOT, root, 50).size)
        }

    @Test
    fun `S5 expectedVersion and expectedFingerprint are ignored when the root has no head`(): Unit =
        runBlocking {
            val root = newRoot()

            val withVersion = append(root, payload("a: 1\n"), at(1), expectedVersion = 7)
            assertEquals(1, assertIs<ConfigAppendOutcome.Applied>(withVersion).version.version)

            val other = newRoot("Other")
            val withFingerprint = append(other, payload("a: 1\n"), at(1), expectedFingerprint = "f".repeat(64))
            assertEquals(1, assertIs<ConfigAppendOutcome.Applied>(withFingerprint).version.version)
        }

    @Test
    fun `S5 the legacy expectedFingerprint compares against the head fingerprint`(): Unit =
        runBlocking {
            val root = newRoot()
            val v1 = appendApplied(root, "a: 1\n", at(1))
            val v2 = appendApplied(root, "a: 2\n", at(2))

            val stale = append(root, payload("a: 3\n"), at(3), expectedFingerprint = v1.fingerprint)
            val failed = assertIs<ConfigAppendOutcome.PreconditionFailed>(stale)
            assertEquals(2, failed.currentVersion)
            assertEquals(v2.fingerprint, failed.currentFingerprint)
            assertEquals(2, store.history(ConfigScope.ROOT, root, 50).size)

            val fresh = append(root, payload("a: 3\n"), at(4), expectedFingerprint = v2.fingerprint)
            assertEquals(3, assertIs<ConfigAppendOutcome.Applied>(fresh).version.version)
        }

    // ---------------------------------------------------------------------------------------------
    // S6 / S7 - fast-forward guard and fingerprint classification
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S6 rejectSuperseded refuses content that an earlier version already held`(): Unit =
        runBlocking {
            val root = newRoot()
            appendApplied(root, "a: 1\n", at(1))
            val v2 = appendApplied(root, "a: 2\n", at(2))

            val outcome = append(root, payload("a: 1\n"), at(3), rejectSuperseded = true)

            val superseded = assertIs<ConfigAppendOutcome.Superseded>(outcome)
            assertEquals(2, superseded.currentVersion)
            assertEquals(v2.createdAt, superseded.currentCreatedAt)
            assertEquals(2, store.history(ConfigScope.ROOT, root, 50).size, "rejected append writes nothing")
            assertEquals(v2.id, store.head(ConfigScope.ROOT, root)?.versionId)
        }

    @Test
    fun `S6 without rejectSuperseded the same content is appended as a new version`(): Unit =
        runBlocking {
            val root = newRoot()
            appendApplied(root, "a: 1\n", at(1))
            appendApplied(root, "a: 2\n", at(2))

            val applied = assertIs<ConfigAppendOutcome.Applied>(append(root, payload("a: 1\n"), at(3)))

            assertEquals(3, applied.version.version)
            assertTrue(applied.appended)
        }

    @Test
    fun `S6 rejectSuperseded still accepts unknown content and head-identical content`(): Unit =
        runBlocking {
            val root = newRoot()
            appendApplied(root, "a: 1\n", at(1))
            appendApplied(root, "a: 2\n", at(2))

            val unknown = assertIs<ConfigAppendOutcome.Applied>(append(root, payload("a: 9\n"), at(3), rejectSuperseded = true))
            assertEquals(3, unknown.version.version, "unknown content is accepted")

            val current = assertIs<ConfigAppendOutcome.Applied>(append(root, payload("a: 9\n"), at(4), rejectSuperseded = true))
            assertFalse(current.appended, "head-identical content is the idempotent no-op, not a rejection")
            assertEquals(3, current.version.version)
        }

    @Test
    fun `S7 a fingerprint only present in config_known_fingerprints classifies as superseded and is refused`(): Unit =
        runBlocking {
            val root = newRoot()
            appendApplied(root, "a: 1\n", at(1))
            val old = store.computeFingerprint("legacy: true\n")
            addKnownFingerprint(root, old)

            assertEquals(FingerprintRelation.SUPERSEDED, store.classifyFingerprint(root, old))

            val outcome = append(root, payload("legacy: true\n"), at(2), rejectSuperseded = true)
            assertIs<ConfigAppendOutcome.Superseded>(outcome)
            assertEquals(1, store.history(ConfigScope.ROOT, root, 50).size)
        }

    @Test
    fun `S7 classification covers current, superseded and unknown`(): Unit =
        runBlocking {
            val root = newRoot()
            val v1 = appendApplied(root, "a: 1\n", at(1))
            val v2 = appendApplied(root, "a: 2\n", at(2))

            assertEquals(FingerprintRelation.CURRENT, store.classifyFingerprint(root, v2.fingerprint))
            assertEquals(FingerprintRelation.SUPERSEDED, store.classifyFingerprint(root, v1.fingerprint))
            assertEquals(FingerprintRelation.UNKNOWN, store.classifyFingerprint(root, store.computeFingerprint("never: seen\n")))
        }

    @Test
    fun `S7 a root with no head classifies every fingerprint as unknown and another roots history does not leak`(): Unit =
        runBlocking {
            val withHistory = newRoot("H")
            val empty = newRoot("E")
            val v1 = appendApplied(withHistory, "a: 1\n", at(1))
            appendApplied(withHistory, "a: 2\n", at(2))

            assertEquals(FingerprintRelation.UNKNOWN, store.classifyFingerprint(empty, v1.fingerprint))
            assertEquals(FingerprintRelation.UNKNOWN, store.classifyFingerprint(empty, store.computeFingerprint("a: 2\n")))
        }

    // ---------------------------------------------------------------------------------------------
    // S9 - reset epoch rule
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S9 after a reset version pre-reset content is unknown and may be appended with rejectSuperseded`(): Unit =
        runBlocking {
            val root = newRoot()
            val a = appendApplied(root, "a: 1\n", at(1))
            val b = appendApplied(root, "a: 2\n", at(2))
            val resetVersion = reset(root, at(3))
            assertEquals(3, resetVersion.version)
            assertEquals(ConfigVersionSource.RESET, resetVersion.source)
            assertEquals(ConfigVersionSource.RESET, store.head(ConfigScope.ROOT, root)?.source)

            assertEquals(FingerprintRelation.UNKNOWN, store.classifyFingerprint(root, a.fingerprint), "v1 predates the reset")
            assertEquals(FingerprintRelation.UNKNOWN, store.classifyFingerprint(root, b.fingerprint), "v2 predates the reset")
            assertEquals(
                FingerprintRelation.CURRENT,
                store.classifyFingerprint(root, resetVersion.fingerprint),
                "the head is current even when it is a reset"
            )

            val pushed = assertIs<ConfigAppendOutcome.Applied>(append(root, payload("a: 1\n"), at(4), rejectSuperseded = true))
            assertEquals(4, pushed.version.version)
            assertTrue(pushed.appended)
        }

    @Test
    fun `S9 versions after the reset count again, versions before it still do not`(): Unit =
        runBlocking {
            val root = newRoot()
            val a = appendApplied(root, "a: 1\n", at(1))
            val b = appendApplied(root, "a: 2\n", at(2))
            reset(root, at(3))
            appendApplied(root, "a: 1\n", at(4))
            assertEquals(FingerprintRelation.CURRENT, store.classifyFingerprint(root, a.fingerprint), "re-pushed content is the head")

            val c = appendApplied(root, "a: 3\n", at(5))

            assertEquals(FingerprintRelation.SUPERSEDED, store.classifyFingerprint(root, a.fingerprint), "v4 is after the reset (v3)")
            assertEquals(FingerprintRelation.UNKNOWN, store.classifyFingerprint(root, b.fingerprint), "v2 is before the reset (v3)")
            assertEquals(FingerprintRelation.CURRENT, store.classifyFingerprint(root, c.fingerprint))
        }

    @Test
    fun `S9 a known legacy fingerprint stops counting once the root has been reset`(): Unit =
        runBlocking {
            val root = newRoot()
            appendApplied(root, "a: 1\n", at(1))
            val legacy = store.computeFingerprint("legacy: true\n")
            addKnownFingerprint(root, legacy)
            assertEquals(FingerprintRelation.SUPERSEDED, store.classifyFingerprint(root, legacy), "control: counts before any reset")

            reset(root, at(2))

            assertEquals(FingerprintRelation.UNKNOWN, store.classifyFingerprint(root, legacy), "a reset starts a new epoch")
        }

    @Test
    fun `S9 a second reset moves the epoch boundary forward`(): Unit =
        runBlocking {
            val root = newRoot()
            appendApplied(root, "a: 1\n", at(1))
            reset(root, at(2))
            val x = appendApplied(root, "a: 5\n", at(3))
            appendApplied(root, "a: 6\n", at(4))
            assertEquals(
                FingerprintRelation.SUPERSEDED,
                store.classifyFingerprint(root, x.fingerprint),
                "control: x is after the first reset"
            )

            reset(root, at(5))

            assertEquals(
                FingerprintRelation.UNKNOWN,
                store.classifyFingerprint(root, x.fingerprint),
                "x now predates the latest reset (v5)"
            )
        }

    // ---------------------------------------------------------------------------------------------
    // S8 - history paging
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S8 history is newest first, honours limit, and before is an exclusive upper bound`(): Unit =
        runBlocking {
            val root = newRoot()
            for (n in 1..5) appendApplied(root, "n: $n\n", at(n.toLong()))

            fun versions(list: List<ConfigVersion>) = list.map { it.version }

            assertEquals(listOf(5, 4, 3, 2, 1), versions(store.history(ConfigScope.ROOT, root, 10)))
            assertEquals(listOf(5, 4), versions(store.history(ConfigScope.ROOT, root, 2)))
            assertEquals(listOf(5), versions(store.history(ConfigScope.ROOT, root, 1)))
            assertEquals(listOf(2, 1), versions(store.history(ConfigScope.ROOT, root, 10, before = 3)))
            assertEquals(listOf(2), versions(store.history(ConfigScope.ROOT, root, 1, before = 3)))
            assertEquals(listOf(3, 2), versions(store.history(ConfigScope.ROOT, root, 2, before = 4)))
            assertEquals(emptyList(), versions(store.history(ConfigScope.ROOT, root, 10, before = 1)))
            assertEquals(listOf(5, 4, 3, 2, 1), versions(store.history(ConfigScope.ROOT, root, 10, before = 6)))
            assertEquals(listOf(5, 4, 3, 2, 1), versions(store.history(ConfigScope.ROOT, root, 200)))
        }

    @Test
    fun `S8 history of a root without versions is empty and another roots versions are not included`(): Unit =
        runBlocking {
            val a = newRoot("A")
            val b = newRoot("B")
            appendApplied(a, "a: 1\n")

            assertEquals(emptyList(), store.history(ConfigScope.ROOT, b, 50))
            assertEquals(emptyList(), store.history(ConfigScope.ROOT, UUID.randomUUID(), 50))
            assertEquals(1, store.history(ConfigScope.ROOT, a, 50).size)
        }

    @Test
    fun `S8 history includes reset and rollback rows with their sources`(): Unit =
        runBlocking {
            val root = newRoot()
            appendApplied(root, "a: 1\n", at(1))
            appendApplied(root, "a: 2\n", at(2))
            val rb =
                assertIs<ConfigAppendOutcome.Applied>(
                    append(root, payload("a: 1\n", source = ConfigVersionSource.ROLLBACK), at(3))
                ).version
            val rs = reset(root, at(4))

            val history = store.history(ConfigScope.ROOT, root, 50)

            assertEquals(listOf(4, 3, 2, 1), history.map { it.version })
            assertEquals(
                listOf(ConfigVersionSource.RESET, ConfigVersionSource.ROLLBACK, ConfigVersionSource.PUSH, ConfigVersionSource.PUSH),
                history.map { it.source }
            )
            assertEquals(rs, history[0])
            assertEquals(rb, history[1])
            assertEquals("", history[0].yaml, "a reset version carries empty yaml")
        }

    // ---------------------------------------------------------------------------------------------
    // Canonical fill
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `fillCanonical fills a NULL canonical row once and only changes the two canonical columns`(): Unit =
        runBlocking {
            val root = newRoot()
            val v1 = assertIs<ConfigAppendOutcome.Applied>(append(root, payload("a: 1\n", canonical = null), at(1))).version
            assertNull(v1.canonicalJson)
            assertNull(v1.modelVersion)

            assertTrue(store.fillCanonical(v1.id, "{\"a\":1}", 1))

            val filled = assertNotNull(store.version(v1.id))
            assertEquals(v1.copy(canonicalJson = "{\"a\":1}", modelVersion = 1), filled)
        }

    @Test
    fun `fillCanonical refuses an already filled row, an unknown id, and never overwrites`(): Unit =
        runBlocking {
            val root = newRoot()
            val v1 = assertIs<ConfigAppendOutcome.Applied>(append(root, payload("a: 1\n", canonical = null), at(1))).version
            assertTrue(store.fillCanonical(v1.id, "{\"a\":1}", 1))

            assertFalse(store.fillCanonical(v1.id, "{\"a\":999}", 7), "second fill returns false")
            assertEquals("{\"a\":1}", store.version(v1.id)?.canonicalJson, "first value stays")
            assertEquals(1, store.version(v1.id)?.modelVersion)

            assertFalse(store.fillCanonical(UUID.randomUUID(), "{}", 1), "unknown id returns false")

            val alreadyCanonical = appendApplied(root, "a: 2\n", at(2))
            assertEquals("{}", alreadyCanonical.canonicalJson)
            assertFalse(store.fillCanonical(alreadyCanonical.id, "{\"x\":1}", 1), "a row appended with canonical JSON is not refilled")
            assertEquals("{}", store.version(alreadyCanonical.id)?.canonicalJson)
        }

    @Test
    fun `versionsMissingCanonical lists exactly the NULL rows and honours its limit`(): Unit =
        runBlocking {
            val root = newRoot()
            val missing1 = assertIs<ConfigAppendOutcome.Applied>(append(root, payload("a: 1\n", canonical = null), at(1))).version
            val filled = appendApplied(root, "a: 2\n", at(2))
            val missing2 = assertIs<ConfigAppendOutcome.Applied>(append(root, payload("a: 3\n", canonical = null), at(3))).version
            val missing3 = assertIs<ConfigAppendOutcome.Applied>(append(root, payload("a: 4\n", canonical = null), at(4))).version

            val all = store.versionsMissingCanonical()
            assertEquals(setOf(missing1.id, missing2.id, missing3.id), all.map { it.id }.toSet())
            assertTrue(all.none { it.id == filled.id })
            assertTrue(all.all { it.canonicalJson == null })

            assertEquals(2, store.versionsMissingCanonical(limit = 2).size)

            store.fillCanonical(missing1.id, "{}", 1)
            store.fillCanonical(missing2.id, "{}", 1)
            store.fillCanonical(missing3.id, "{}", 1)
            assertEquals(emptyList(), store.versionsMissingCanonical())
        }

    // ---------------------------------------------------------------------------------------------
    // Scope and fingerprint helpers
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `append with SERVER scope throws IllegalArgumentException in Q8`(): Unit =
        runBlocking {
            assertFailsWith<IllegalArgumentException> {
                store.append(
                    scope = ConfigScope.SERVER,
                    rootId = null,
                    append = payload("a: 1\n"),
                    now = t0
                )
            }
        }

    @Test
    fun `computeFingerprint is deterministic, content sensitive and 64 lowercase hex characters`() {
        val fp = store.computeFingerprint("a: 1\n")
        assertEquals(fp, store.computeFingerprint("a: 1\n"))
        assertNotEquals(fp, store.computeFingerprint("a: 1\n\n"), "a trailing newline changes the fingerprint")
        assertNotEquals(fp, store.computeFingerprint("a: 2\n"))
        assertTrue(Regex("[0-9a-f]{64}").matches(fp), "fingerprint is a sha-256 hex digest: $fp")
        assertTrue(Regex("[0-9a-f]{64}").matches(store.computeFingerprint("")))
    }

    @Test
    fun `computeFingerprint strips one leading BOM and normalizes CRLF to LF and nothing else`() {
        val lf = "a: 1\nb: 2\n"
        assertEquals(store.computeFingerprint(lf), store.computeFingerprint(lf.replace("\n", "\r\n")), "CRLF fingerprints like LF")
        assertEquals(store.computeFingerprint(lf), store.computeFingerprint("\uFEFF" + lf), "one leading BOM is ignored")
        assertNotEquals(store.computeFingerprint(lf), store.computeFingerprint("\uFEFF\uFEFF" + lf), "only ONE leading BOM is stripped")
        assertNotEquals(store.computeFingerprint(lf), store.computeFingerprint(lf.trimEnd()), "trailing whitespace is significant")
        assertNotEquals(store.computeFingerprint(lf), store.computeFingerprint(" $lf"), "leading whitespace is significant")
    }
}
