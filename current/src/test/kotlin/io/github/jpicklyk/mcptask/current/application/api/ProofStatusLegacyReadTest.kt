package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.ProofVocabulary
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.proofWire
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.envelopeOk
import io.github.jpicklyk.mcptask.current.test.getNoteVia
import io.github.jpicklyk.mcptask.current.test.listNotesVia
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item a0a47509, R18) for the proof vocabulary and the one read mapping from the stored
 * columns onto it: scenarios S7, S8 and S9 of the frozen test-plan. S7 and S9 are NEW-SURFACE (`ProofStatus.fromStored`, the
 * `proofStatus` extension and the `verification.proofStatus` wire key are introduced by this item: red-proof is the narrowest
 * revert, keep the function and revert one mapping arm); S8 binds only pre-existing declarations (the note repository and the
 * stored columns) and reaches red by a plain revert.
 *
 * Oracles (written from the task-scope, section D, never from a run): inputs are trimmed and case-insensitive; `verified`,
 * `verified_stale`, `issued`, `none` and `rejected` map to themselves; `unchecked` with verifier `api-bearer` is ISSUED and with
 * any other verifier (or none) is NONE; `absent`, `unverified` and `unavailable` are NONE; `failed` is REJECTED; anything else is
 * null (the row's verification is skipped). The wire is `verification: {proofStatus, verifier?, reason?}` with no `status` key and
 * no proof evidence, and no wire value is ever `unchecked`, `absent` or `unavailable`. Expected values are literals.
 *
 * Negative-assertion conditions: "no `status` key" would fail on the pre-change view, which emitted `verification.status`;
 * "never unchecked/absent/unavailable" would fail on the same view for the three stored values that used to be emitted verbatim.
 * Every skipped (null) result is paired with a recognised row read the same way.
 *
 * Domain invariants satisfied by construction: notes are created through the production repository with an actor claim and a
 * verification, the stored columns are then overwritten with the legacy spelling (the raw-row shape of an old database); work
 * items are real rows, so the notes' foreign key holds.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ProofStatusLegacyReadTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private lateinit var rig: EventLogRig

    @BeforeEach
    fun setUp(
        @TempDir dir: Path,
    ) {
        rig = EventLogRig.build(db.db, dir)
    }

    // ------------------------------------------------------------------ S7 the mapping

    @Test
    fun `S7 the five proof words map to themselves`() {
        assertEquals(ProofStatus.VERIFIED, ProofStatus.fromStored("verified", null))
        assertEquals(ProofStatus.VERIFIED_STALE, ProofStatus.fromStored("verified_stale", null))
        assertEquals(ProofStatus.ISSUED, ProofStatus.fromStored("issued", null))
        assertEquals(ProofStatus.NONE, ProofStatus.fromStored("none", null))
        assertEquals(ProofStatus.REJECTED, ProofStatus.fromStored("rejected", null))
    }

    @Test
    fun `S7 the wire words of the vocabulary are verified, verified_stale, issued, none and rejected`() {
        assertEquals(
            setOf("verified", "verified_stale", "issued", "none", "rejected"),
            ProofStatus.entries.map { it.wire }.toSet(),
        )
        assertEquals(5, ProofStatus.entries.size, "no sixth word")
    }

    @Test
    fun `S7 unchecked is ISSUED for the api-bearer verifier and NONE for any other or no verifier`() {
        assertEquals(ProofStatus.ISSUED, ProofStatus.fromStored("unchecked", "api-bearer"))
        assertEquals(ProofStatus.NONE, ProofStatus.fromStored("unchecked", "local"))
        assertEquals(ProofStatus.NONE, ProofStatus.fromStored("unchecked", "noop"))
        assertEquals(ProofStatus.NONE, ProofStatus.fromStored("unchecked", null))
        assertEquals(ProofStatus.NONE, ProofStatus.fromStored("unchecked", ""))
        assertEquals(ProofStatus.NONE, ProofStatus.fromStored("unchecked", "jwks"))
    }

    @Test
    fun `S7 absent, unverified and unavailable are NONE whatever the verifier`() {
        for (verifier in listOf(null, "api-bearer", "jwks", "local")) {
            assertEquals(ProofStatus.NONE, ProofStatus.fromStored("absent", verifier), "absent / $verifier")
            assertEquals(ProofStatus.NONE, ProofStatus.fromStored("unverified", verifier), "unverified / $verifier")
            assertEquals(ProofStatus.NONE, ProofStatus.fromStored("unavailable", verifier), "unavailable / $verifier")
        }
    }

    @Test
    fun `S7 failed is REJECTED and a recorded verified stays VERIFIED regardless of the verifier`() {
        assertEquals(ProofStatus.REJECTED, ProofStatus.fromStored("failed", null))
        assertEquals(ProofStatus.REJECTED, ProofStatus.fromStored("failed", "jwks"))
        assertEquals(ProofStatus.VERIFIED, ProofStatus.fromStored("verified", "jwks"))
        assertEquals(ProofStatus.VERIFIED, ProofStatus.fromStored("verified", "api-bearer"))
    }

    @Test
    fun `S7 inputs are trimmed and case-insensitive`() {
        assertEquals(ProofStatus.VERIFIED, ProofStatus.fromStored("VERIFIED", null))
        assertEquals(ProofStatus.VERIFIED, ProofStatus.fromStored("  verified  ", null))
        assertEquals(ProofStatus.VERIFIED_STALE, ProofStatus.fromStored("Verified_Stale", null))
        assertEquals(ProofStatus.REJECTED, ProofStatus.fromStored("FAILED", null))
        assertEquals(ProofStatus.ISSUED, ProofStatus.fromStored("Unchecked", "API-Bearer"))
        assertEquals(ProofStatus.ISSUED, ProofStatus.fromStored(" unchecked ", " api-bearer "))
        assertEquals(ProofStatus.NONE, ProofStatus.fromStored("ABSENT", null))
    }

    @Test
    fun `S7 an unknown or empty status is null`() {
        assertNull(ProofStatus.fromStored("bogus", null))
        assertNull(ProofStatus.fromStored("", null))
        assertNull(ProofStatus.fromStored("   ", "api-bearer"))
        assertNull(ProofStatus.fromStored("verifiedd", null))
        assertNull(ProofStatus.fromStored("verified stale", null), "a space is not an underscore")
        assertNull(ProofStatus.fromStored("verified-stale", null), "a hyphen is not an underscore")
        assertEquals(ProofStatus.VERIFIED, ProofStatus.fromStored("verified", null), "control: the recognised word resolves")
    }

    @Test
    fun `S7 the VerificationResult extension applies the same mapping to the domain enum`() {
        assertEquals(ProofStatus.VERIFIED, VerificationResult(VerificationStatus.VERIFIED, verifier = "jwks").proofStatus)
        assertEquals(ProofStatus.ISSUED, VerificationResult(VerificationStatus.UNCHECKED, verifier = "api-bearer").proofStatus)
        assertEquals(ProofStatus.NONE, VerificationResult(VerificationStatus.UNCHECKED, verifier = "local").proofStatus)
        assertEquals(ProofStatus.NONE, VerificationResult(VerificationStatus.UNCHECKED).proofStatus)
        assertEquals(ProofStatus.NONE, VerificationResult(VerificationStatus.ABSENT).proofStatus)
        assertEquals(ProofStatus.NONE, VerificationResult(VerificationStatus.UNAVAILABLE, verifier = "jwks").proofStatus)
        assertEquals(ProofStatus.REJECTED, VerificationResult(VerificationStatus.REJECTED, verifier = "jwks").proofStatus)
    }

    @Test
    fun `S7 the string vocabulary in the domain layer agrees with the enum for every input`() {
        val statuses =
            listOf(
                "verified",
                "verified_stale",
                "issued",
                "none",
                "rejected",
                "unchecked",
                "absent",
                "unverified",
                "unavailable",
                "failed",
                "bogus",
                ""
            )
        for (verifier in listOf(null, "api-bearer", "local")) {
            for (status in statuses) {
                assertEquals(
                    ProofStatus.fromStored(status, verifier)?.wire,
                    ProofVocabulary.fromStored(status, verifier),
                    "$status / $verifier",
                )
            }
        }
        assertEquals("verified", ProofVocabulary.VERIFIED)
        assertEquals("verified_stale", ProofVocabulary.VERIFIED_STALE)
        assertEquals("issued", ProofVocabulary.ISSUED)
        assertEquals("none", ProofVocabulary.NONE)
        assertEquals("rejected", ProofVocabulary.REJECTED)
        assertEquals("issued", VerificationResult(VerificationStatus.UNCHECKED, verifier = "api-bearer").proofWire())
        assertEquals("rejected", VerificationResult(VerificationStatus.REJECTED).proofWire())
    }

    // ------------------------------------------------------------------ S8 raw rows read through the repository

    private fun newItem(): UUID =
        runBlocking {
            val item = WorkItem(title = "legacy proof rows")
            db.repositoryProvider().workItemRepository().create(item)
            item.id
        }

    private fun overwriteStoredVerification(
        status: String,
        verifier: String?,
    ) {
        DriverManager.getConnection(db.jdbcUrl).use { connection ->
            connection.prepareStatement("UPDATE notes SET verification_status = ?, verification_verifier = ?").use { statement ->
                statement.setString(1, status)
                statement.setString(2, verifier)
                statement.executeUpdate()
            }
        }
    }

    /** Seeds one fresh note with a recognised verification, then rewrites the stored columns to the legacy spelling and reads it back. */
    private fun readBackWithStored(
        itemId: UUID,
        key: String,
        status: String,
        verifier: String?,
    ): Note =
        runBlocking {
            val repository = db.repositoryProvider().noteRepository()
            val note =
                Note(
                    itemId = itemId,
                    key = key,
                    role = "work",
                    body = "legacy row",
                    actorClaim = ActorClaim(id = "legacy-actor", kind = ActorKind.SUBAGENT),
                    verification = VerificationResult(VerificationStatus.VERIFIED, verifier = "jwks"),
                )
            assertNotNull(repository.upsert(note), "fixture note must be stored")
            overwriteStoredVerification(status, verifier)
            assertNotNull(repository.getById(note.id), "fixture note must read back")
        }

    @Test
    fun `S8 a stored failed row reads as REJECTED`() {
        val itemId = newItem()
        val note = readBackWithStored(itemId, "k-failed", "failed", "jwks")
        assertEquals(ProofStatus.REJECTED, assertNotNull(note.verification, "$note").proofStatus)
    }

    @Test
    fun `S8 a stored unverified row reads as NONE`() {
        val itemId = newItem()
        val note = readBackWithStored(itemId, "k-unverified", "unverified", "jwks")
        assertEquals(ProofStatus.NONE, assertNotNull(note.verification, "$note").proofStatus)
    }

    @Test
    fun `S8 a stored unchecked row reads as ISSUED for api-bearer and NONE for another verifier`() {
        val itemId = newItem()
        val issued = readBackWithStored(itemId, "k-issued", "unchecked", "api-bearer")
        assertEquals(ProofStatus.ISSUED, assertNotNull(issued.verification, "$issued").proofStatus)
        val none = readBackWithStored(itemId, "k-none", "unchecked", "local")
        assertEquals(ProofStatus.NONE, assertNotNull(none.verification, "$none").proofStatus)
    }

    @Test
    fun `S8 stored absent and unavailable rows read as NONE and the uppercase legacy spellings are recognised`() {
        val itemId = newItem()
        assertEquals(
            ProofStatus.NONE,
            assertNotNull(readBackWithStored(itemId, "k-absent", "absent", null).verification).proofStatus,
        )
        assertEquals(
            ProofStatus.NONE,
            assertNotNull(readBackWithStored(itemId, "k-unavail", "unavailable", "jwks").verification).proofStatus,
        )
        assertEquals(
            ProofStatus.VERIFIED,
            assertNotNull(readBackWithStored(itemId, "k-upper-v", "VERIFIED", "jwks").verification).proofStatus,
        )
        assertEquals(
            ProofStatus.REJECTED,
            assertNotNull(readBackWithStored(itemId, "k-upper-r", "REJECTED", "jwks").verification).proofStatus,
        )
    }

    @Test
    fun `S8 a stored status that is not in the vocabulary is skipped, and the recognised row beside it is kept`() {
        val itemId = newItem()
        val skipped = readBackWithStored(itemId, "k-bogus", "bogus-status", "jwks")
        assertNull(skipped.verification, "an unknown stored status drops the row's verification: $skipped")
        val kept = readBackWithStored(itemId, "k-control", "verified", "jwks")
        assertEquals(ProofStatus.VERIFIED, assertNotNull(kept.verification, "control: $kept").proofStatus)
    }

    // ------------------------------------------------------------------ S9 notes_read wire

    private fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject

    private fun JsonObject.data(): JsonObject = obj("data")

    private fun JsonObject.items(): List<JsonObject> = data().getValue("items").jsonArray.map { it.jsonObject }

    private suspend fun seedNote(
        itemId: UUID,
        key: String,
        verification: VerificationResult,
    ) {
        rig.raw.noteRepository().upsert(
            Note(
                itemId = itemId,
                key = key,
                role = "work",
                body = "wire body",
                actorClaim = ActorClaim(id = "wire-actor", kind = ActorKind.SUBAGENT),
                verification = verification,
            ),
        )
    }

    private val allowedVerificationKeys = setOf("proofStatus", "verifier", "reason")

    private fun assertWireVerification(
        answer: JsonObject,
        verification: JsonObject,
        expectedProof: String,
    ) {
        assertEquals(expectedProof, verification.getValue("proofStatus").jsonPrimitive.content, "$answer")
        assertFalse("status" in verification, "the verification object has no status key: $verification")
        assertTrue(verification.keys.all { it in allowedVerificationKeys }, "only proofStatus, verifier, reason: $verification")
        val text = answer.toString()
        for (evidence in listOf("sha256", "proofClaims", "claims", "\"proof\"")) {
            assertFalse(evidence in text, "no proof evidence ($evidence) on the wire: $text")
        }
        for (retired in listOf("\"unchecked\"", "\"absent\"", "\"unavailable\"")) {
            assertFalse(retired in text, "a retired status word ($retired) is never emitted: $text")
        }
    }

    @Test
    fun `S9 notes_read get carries verification proofStatus and no status key for every stored status`() =
        runBlocking {
            val item: WorkItem = rig.seed("owner")
            val cases =
                listOf(
                    Triple("k-verified", VerificationResult(VerificationStatus.VERIFIED, verifier = "jwks"), "verified"),
                    Triple("k-issued", VerificationResult(VerificationStatus.UNCHECKED, verifier = "api-bearer"), "issued"),
                    Triple("k-local", VerificationResult(VerificationStatus.UNCHECKED, verifier = "local"), "none"),
                    Triple("k-unavailable", VerificationResult(VerificationStatus.UNAVAILABLE, verifier = "jwks"), "none"),
                    Triple(
                        "k-rejected",
                        VerificationResult(VerificationStatus.REJECTED, verifier = "jwks", reason = "bad signature"),
                        "rejected"
                    ),
                )
            for ((key, verification, _) in cases) seedNote(item.id, key, verification)

            for ((key, _, expected) in cases) {
                val answer = rig.ctx.getNoteVia(item.id, key)
                assertTrue(envelopeOk(answer), "$answer")
                assertWireVerification(answer, answer.data().obj("verification"), expected)
            }
        }

    @Test
    fun `S9 the verifier and the reason ride on the verification object beside proofStatus`() =
        runBlocking {
            val item: WorkItem = rig.seed("owner")
            seedNote(item.id, "k-reason", VerificationResult(VerificationStatus.REJECTED, verifier = "jwks", reason = "bad signature"))
            val verification =
                rig.ctx
                    .getNoteVia(item.id, "k-reason")
                    .data()
                    .obj("verification")
            assertEquals("jwks", verification.getValue("verifier").jsonPrimitive.content)
            assertEquals("bad signature", verification.getValue("reason").jsonPrimitive.content)
            assertEquals("rejected", verification.getValue("proofStatus").jsonPrimitive.content)
        }

    @Test
    fun `S9 notes_read list carries the same verification shape on every summary`() =
        runBlocking {
            val item: WorkItem = rig.seed("owner")
            seedNote(item.id, "k-a-verified", VerificationResult(VerificationStatus.VERIFIED, verifier = "jwks"))
            seedNote(item.id, "k-b-issued", VerificationResult(VerificationStatus.UNCHECKED, verifier = "api-bearer"))
            val answer = rig.ctx.listNotesVia(item.id)
            assertTrue(envelopeOk(answer), "$answer")
            val byKey = answer.items().associateBy { it.getValue("key").jsonPrimitive.content }
            assertEquals(setOf("k-a-verified", "k-b-issued"), byKey.keys)
            assertWireVerification(answer, byKey.getValue("k-a-verified").obj("verification"), "verified")
            assertWireVerification(answer, byKey.getValue("k-b-issued").obj("verification"), "issued")
        }

    @Test
    fun `S9 a note with no verification emits no verification object at all`() =
        runBlocking {
            val item: WorkItem = rig.seed("owner")
            rig.raw.noteRepository().upsert(Note(itemId = item.id, key = "k-bare", role = "work", body = "no attribution"))
            val answer = rig.ctx.getNoteVia(item.id, "k-bare")
            assertTrue(envelopeOk(answer), "$answer")
            assertFalse("verification" in answer.data(), "no verification member: ${answer.data()}")
            assertEquals(
                "k-bare",
                answer
                    .data()
                    .getValue("key")
                    .jsonPrimitive.content,
                "control: the note itself is returned"
            )
        }
}
