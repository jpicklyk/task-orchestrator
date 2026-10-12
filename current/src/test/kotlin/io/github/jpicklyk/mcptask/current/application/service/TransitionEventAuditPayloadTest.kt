package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.event.TransitionOrigin
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.ProofClaims
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventBus
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventType
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11Driver.Companion.transitioned
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.P11_LEASE_KEY
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 02f1be4c "Q28a: widen the item.transitioned payload") over the production
 * composition: an applied transition's event row carries the full audit (the former role_transitions row, retired in
 * Q28b, is no longer a second source).
 *
 * Oracles: plan section 3.4 (the six audit fields; post-Q28a history is complete) and the item's frozen task-scope
 * D1-D3 / AC1-AC5. Every expected value below is an input the test chose.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class TransitionEventAuditPayloadTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val auditKeys =
        listOf("summary", "consumedCredentials", "actorProofSha256", "actorProofClaims", "verifier", "verificationReason")

    private fun driver(dir: Path) = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))

    private val subagent = ActorClaim(id = "agent-q28a", kind = ActorKind.SUBAGENT, parent = "parent-q28a")

    private val fullClaims =
        ProofClaims(
            iss = "https://issuer.q28a.example",
            sub = "agent-q28a",
            aud = listOf("aud-one", "aud-two"),
            jti = "jti-q28a",
            iat = 1_700_000_000L,
            exp = 1_700_000_300L,
            kid = "kid-q28a",
            alg = "RS256"
        )

    private val verified =
        VerificationResult(
            status = VerificationStatus.VERIFIED,
            verifier = "jwks",
            reason = "reason-q28a",
            proofSha256 = "sha-q28a",
            proofClaims = fullClaims
        )

    /** One applied advance through the production AdvanceService, with the exact inputs the test chooses. */
    private suspend fun P11Driver.applied(
        item: WorkItem,
        trigger: String,
        summary: String? = null,
        actor: ActorClaim? = null,
        verification: VerificationResult? = null,
        refs: List<String> = emptyList(),
    ) {
        val current = reload(item)
        val outcome =
            rig.ctx.advanceServiceFactory().forItem(current).advance(
                current,
                trigger,
                summary,
                actor,
                verification,
                DegradedModePolicy.ACCEPT_CACHED,
                enforceOwnership = false,
                credentialRefs = refs
            )
        val success = assertIs<AdvanceOutcome.Success>(outcome, "the advance must apply: $outcome")
        assertTrue(success.result.applied, "the transition must be applied: $outcome")
    }

    private fun JsonObject.orNull(key: String): JsonElement? = this[key]?.takeIf { it !is JsonNull }

    private fun EventRecord.credentials(): List<String> = payload()["consumedCredentials"]!!.jsonArray.map { it.jsonPrimitive.content }

    /** The claims object the audit must carry for [claims]: independently built from the ProofClaims fields. */
    private fun expectedClaims(claims: ProofClaims?): JsonObject? {
        if (claims == null) return null
        val built =
            buildJsonObject {
                claims.iss?.let { put("iss", it) }
                claims.sub?.let { put("sub", it) }
                claims.aud?.let { aud -> putJsonArray("aud") { aud.forEach { add(JsonPrimitive(it)) } } }
                claims.jti?.let { put("jti", it) }
                claims.iat?.let { put("iat", it) }
                claims.exp?.let { put("exp", it) }
                claims.kid?.let { put("kid", it) }
                claims.alg?.let { put("alg", it) }
            }
        return built.takeIf { it.isNotEmpty() }
    }

    /** All column values of one events row as strings, read through raw JDBC. */
    private fun eventColumns(seq: Long): Map<String, String?> =
        DriverManager.getConnection(db.db.jdbcUrl).use { c ->
            c.prepareStatement("SELECT * FROM events WHERE seq = ?").use { st ->
                st.setLong(1, seq)
                st.executeQuery().use { rs ->
                    check(rs.next()) { "no events row with seq $seq" }
                    val meta = rs.metaData
                    (1..meta.columnCount).associate { meta.getColumnName(it) to rs.getString(it) }
                }
            }
        }

    // ---------------------------------------------------------------------------------------------
    // S1 -- an applied user transition carries the six audit fields equal to its inputs (AC1)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S1 an applied user transition carries summary credentials proof hash claims verifier and reason equal to its inputs`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val item = d.item("s1", Role.QUEUE)
            val mark = d.rig.maxSeq()

            d.applied(item, "start", summary = "s1", actor = subagent, verification = verified, refs = listOf("c1", "c2"))

            val event =
                d.rig
                    .rowsAfter(mark)
                    .transitioned()
                    .single()
            val data = event.payload()
            assertEquals(item.id, event.entityId)
            assertEquals("s1", event.str("summary"))
            assertEquals(listOf("c1", "c2"), event.credentials())
            assertEquals("sha-q28a", event.str("actorProofSha256"))
            assertEquals("jwks", event.str("verifier"))
            assertEquals("reason-q28a", event.str("verificationReason"))
            val claims = data["actorProofClaims"]!!.jsonObject
            assertEquals("https://issuer.q28a.example", claims["iss"]!!.jsonPrimitive.content)
            assertEquals("agent-q28a", claims["sub"]!!.jsonPrimitive.content)
            assertEquals(listOf("aud-one", "aud-two"), claims["aud"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("jti-q28a", claims["jti"]!!.jsonPrimitive.content)
            assertFalse(claims["iat"]!!.jsonPrimitive.isString, "iat is a JSON number")
            assertFalse(claims["exp"]!!.jsonPrimitive.isString, "exp is a JSON number")
            assertEquals(1_700_000_000L, claims["iat"]!!.jsonPrimitive.long)
            assertEquals(1_700_000_300L, claims["exp"]!!.jsonPrimitive.long)
            assertEquals("kid-q28a", claims["kid"]!!.jsonPrimitive.content)
            assertEquals("RS256", claims["alg"]!!.jsonPrimitive.content)
            assertEquals(8, claims.size, "exactly the eight claims, nothing else")
            assertEquals("verified", event.proofStatus)
            assertEquals("agent-q28a", event.principalId)
            assertEquals("subagent", eventColumns(event.seq)["principal_kind"])
            assertEquals("parent-q28a", event.str("actorParent"))
        }

    // ---------------------------------------------------------------------------------------------
    // S2 -- the primary and the cascade each record one event with the chosen inputs (AC2)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S2 the primary and the start cascade each record one item transitioned event carrying the chosen inputs`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val parent = d.item("s2 parent", Role.QUEUE)
            val child = d.item("s2 child", Role.QUEUE, parent = parent)
            val mark = d.rig.maxSeq()

            d.applied(child, "start", summary = "s2", actor = subagent, verification = verified, refs = listOf("c1", "c2"))

            val events = d.rig.rowsAfter(mark).transitioned()
            assertEquals(
                2,
                events.size,
                "one user event for the child and one cascade event for the parent: ${events.map { it.str("origin") }}"
            )
            assertEquals(setOf("user", "cascade"), events.map { it.str("origin") }.toSet())
            val primary = events.single { it.entityId == child.id }
            assertEquals("s2", primary.str("summary"))
            assertEquals(listOf("c1", "c2"), primary.credentials())
            assertEquals("agent-q28a", primary.principalId)
            assertEquals("parent-q28a", primary.str("actorParent"))
            assertEquals("verified", primary.proofStatus)
            assertEquals("sha-q28a", primary.str("actorProofSha256"))
            assertEquals(expectedClaims(fullClaims), primary.payload().orNull("actorProofClaims"))
        }

    // ---------------------------------------------------------------------------------------------
    // S3 -- the cascade event carries the cascade reason as its summary, no verification, no credentials (AC2, D2)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S3 the cascade event carries the cascade summary equal to its row and no verification fields`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val parent = d.item("s3 parent", Role.QUEUE)
            val child = d.item("s3 child", Role.QUEUE, parent = parent)
            val mark = d.rig.maxSeq()

            d.applied(child, "start", summary = "s3", actor = subagent, verification = verified, refs = listOf("c1"))

            val cascade =
                d.rig
                    .rowsAfter(mark)
                    .transitioned()
                    .single { it.entityId == parent.id }
            assertEquals("cascade", cascade.str("origin"))
            assertNotNull(cascade.str("summary"), "the cascade event stores its reason as the summary")
            assertEquals(emptyList(), cascade.credentials())
            for (key in listOf("actorProofSha256", "actorProofClaims", "verifier", "verificationReason")) {
                assertTrue(cascade.payload().containsKey(key), "$key is present on a cascade event")
                assertEquals(JsonNull, cascade.payload()[key], "$key is null on a cascade event")
            }
        }

    // ---------------------------------------------------------------------------------------------
    // S4 -- absent inputs: the keys are still present, null and [] (AC3, D2)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S4 a transition with no summary actor verification or credentials still carries all six keys as null and empty`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val item = d.item("s4", Role.QUEUE)
            val mark = d.rig.maxSeq()

            d.applied(item, "start")

            val event =
                d.rig
                    .rowsAfter(mark)
                    .transitioned()
                    .single()
            val data = event.payload()
            for (key in auditKeys) assertTrue(data.containsKey(key), "$key must be present even when empty: $data")
            assertEquals(JsonNull, data["summary"])
            assertEquals(JsonArray(emptyList()), data["consumedCredentials"])
            assertEquals(JsonNull, data["actorProofSha256"])
            assertEquals(JsonNull, data["actorProofClaims"])
            assertEquals(JsonNull, data["verifier"])
            assertEquals(JsonNull, data["verificationReason"])
        }

    // ---------------------------------------------------------------------------------------------
    // S5 -- the raw proof never reaches any events column (AC4, D3)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S5 the raw actor proof text appears in no column of the event row`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val item = d.item("s5", Role.QUEUE)
            val mark = d.rig.maxSeq()
            val secret = "PROOF-SECRET-q28a"
            val withProof = ActorClaim(id = "agent-q28a", kind = ActorKind.SUBAGENT, parent = "parent-q28a", proof = secret)

            d.applied(item, "start", actor = withProof, verification = verified)

            val rows = d.rig.rowsAfter(mark)
            val event = rows.transitioned().single()
            assertEquals("sha-q28a", event.str("actorProofSha256"), "control: the hash really is stored in place of the proof")
            for (row in rows) {
                val columns = eventColumns(row.seq)
                for ((name, value) in columns) {
                    assertFalse(value?.contains(secret) == true, "the raw proof leaked into events.$name: $value")
                }
            }
        }

    // ---------------------------------------------------------------------------------------------
    // S6 -- rejected verification and empty claims (task-scope table)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S6 a rejected verification with a hash but no claims carries the hash verifier and reason and null claims`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val rejected =
                VerificationResult(
                    status = VerificationStatus.REJECTED,
                    verifier = "jwks",
                    reason = "bad-signature-q28a",
                    proofSha256 = "sha-rejected-q28a",
                    proofClaims = null
                )
            val noClaims = d.item("s6 no claims", Role.QUEUE)
            val emptyClaims = d.item("s6 empty claims", Role.QUEUE)
            val mark = d.rig.maxSeq()

            d.applied(noClaims, "start", actor = subagent, verification = rejected)
            d.applied(emptyClaims, "start", actor = subagent, verification = rejected.copy(proofClaims = ProofClaims()))

            val events = d.rig.rowsAfter(mark).transitioned()
            assertEquals(2, events.size)
            for (event in events) {
                assertEquals("sha-rejected-q28a", event.str("actorProofSha256"))
                assertEquals("jwks", event.str("verifier"))
                assertEquals("bad-signature-q28a", event.str("verificationReason"))
                assertTrue(event.payload().containsKey("actorProofClaims"))
                assertEquals(JsonNull, event.payload()["actorProofClaims"], "no claims -> null, not an empty object")
                assertEquals("rejected", event.proofStatus)
            }
        }

    // ---------------------------------------------------------------------------------------------
    // S7 -- lease keys merged into the consumed credentials reach the event (AC2)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S7 the lease key merged into consumedCredentials at work entry appears in the event`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val item = d.item("s7", Role.QUEUE, type = "p11-leased")
            val mark = d.rig.maxSeq()

            d.applied(item, "start", actor = subagent, verification = verified, refs = emptyList())

            val event =
                d.rig
                    .rowsAfter(mark)
                    .transitioned()
                    .single()
            assertTrue(P11_LEASE_KEY in event.credentials(), "the event records the lease key: ${event.credentials()}")
        }

    // ---------------------------------------------------------------------------------------------
    // S8 -- the SSE projection and the wire are unchanged (AC5)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S8 the item advanced projection of a widened row shows the new role and none of the audit values`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val item = d.item("s8", Role.QUEUE)
            val mark = d.rig.maxSeq()

            d.applied(
                item,
                "start",
                summary = "summary-s8-unique",
                actor = subagent,
                verification = verified,
                refs = listOf("cred-s8-unique")
            )

            val record =
                d.rig
                    .rowsAfter(mark)
                    .transitioned()
                    .single()
            val projected = assertNotNull(ApiEventBus.project(record))
            assertEquals(ApiEventType.ITEM_ADVANCED, projected.event)
            assertEquals("work", projected.newRole)
            assertEquals(item.id.toString(), projected.itemId)
            val wire = projected.toString()
            for (leaked in listOf("summary-s8-unique", "cred-s8-unique", "sha-q28a", "reason-q28a", "kid-q28a", "jti-q28a")) {
                assertFalse(wire.contains(leaked), "the SSE projection must not carry the audit value $leaked: $wire")
            }
        }

    @Test
    fun `S8 a widened and a legacy event for the same transition project to the same wire fields`(): Unit =
        runBlocking {
            val store = db.db.repositoryProvider().eventStore()
            val recorder = EventRecorder(store)
            val itemId = UUID.randomUUID()
            val rootId = UUID.randomUUID()

            val legacy =
                recorder.record(
                    listOf(DomainEvent.ItemTransitioned(itemId, rootId, "start", "queue", "work", null, null, TransitionOrigin.USER))
                )
            val widened =
                recorder.record(
                    listOf(
                        DomainEvent.ItemTransitioned(
                            itemId,
                            rootId,
                            "start",
                            "queue",
                            "work",
                            null,
                            null,
                            TransitionOrigin.USER,
                            actor = subagent,
                            verification = verified,
                            summary = "wide",
                            consumedCredentials = listOf("c")
                        )
                    )
                )

            val a = assertNotNull(ApiEventBus.project(legacy.single()))
            val b = assertNotNull(ApiEventBus.project(widened.single()))
            assertEquals(a.event, b.event)
            assertEquals(a.itemId, b.itemId)
            assertEquals(a.rootId, b.rootId)
            assertEquals(a.newRole, b.newRole)
        }

    // ---------------------------------------------------------------------------------------------
    // Recorder: Map values become JSON objects, and the widened event reaches the stored row
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a nested map payload value is serialised as a JSON object with its own nulls lists and numbers`() {
        val json =
            EventRecorder.toJson(
                mapOf(
                    "text" to "t",
                    "n" to 7L,
                    "list" to listOf("x", "y"),
                    "missing" to null,
                    "inner" to mapOf("k" to 1, "flag" to true, "none" to null)
                )
            )

        val expected =
            buildJsonObject {
                put("text", "t")
                put("n", 7L)
                putJsonArray("list") {
                    add(JsonPrimitive("x"))
                    add(JsonPrimitive("y"))
                }
                put("missing", JsonNull)
                put(
                    "inner",
                    buildJsonObject {
                        put("k", 1)
                        put("flag", true)
                        put("none", JsonNull)
                    }
                )
            }
        assertEquals(expected, json)
    }

    @Test
    fun `an empty map is serialised as an empty JSON object not as its string form`() {
        assertEquals(JsonObject(emptyMap()), EventRecorder.toJson(emptyMap<String, Any?>()))
    }

    @Test
    fun `recording a widened transition stores every audit field in the data column and the principal in its columns`(): Unit =
        runBlocking {
            val store = db.db.repositoryProvider().eventStore()
            val recorder = EventRecorder(store)
            val itemId = UUID.randomUUID()
            val rootId = UUID.randomUUID()

            val record =
                recorder
                    .record(
                        listOf(
                            DomainEvent.ItemTransitioned(
                                itemId,
                                rootId,
                                "complete",
                                "work",
                                "terminal",
                                "doing",
                                "done",
                                TransitionOrigin.USER,
                                actor = subagent,
                                verification = verified,
                                summary = "R\u00e9sum\u00e9 \u2014 \u65e5\u672c\u8a9e",
                                consumedCredentials = listOf("b", "a", "a")
                            )
                        )
                    ).single()

            assertEquals("R\u00e9sum\u00e9 \u2014 \u65e5\u672c\u8a9e", record.str("summary"))
            assertEquals(listOf("b", "a", "a"), record.credentials())
            assertEquals("sha-q28a", record.str("actorProofSha256"))
            assertEquals(expectedClaims(fullClaims), record.payload()["actorProofClaims"])
            assertEquals("jwks", record.str("verifier"))
            assertEquals("reason-q28a", record.str("verificationReason"))
            assertEquals("agent-q28a", record.principalId)
            assertEquals("verified", record.proofStatus)
        }

    // ---------------------------------------------------------------------------------------------
    // Probes: duplicate credentials, replay, non-ASCII summary through the whole path
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `probe duplicate credential labels and a non-ASCII summary reach the event as given`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val item = d.item("probe dup", Role.QUEUE)
            val mark = d.rig.maxSeq()
            val summary = "R\u00e9sum\u00e9 \u2014 \u65e5\u672c\u8a9e \u2713"

            d.applied(item, "start", summary = summary, refs = listOf("a", "a", "b"))

            val event =
                d.rig
                    .rowsAfter(mark)
                    .transitioned()
                    .single()
            assertEquals(summary, event.str("summary"))
            assertEquals(listOf("a", "a", "b"), event.credentials())
        }

    @Test
    fun `probe an empty summary is carried as an empty string and not as null`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val item = d.item("probe empty", Role.QUEUE)
            val mark = d.rig.maxSeq()

            d.applied(item, "start", summary = "")

            val event =
                d.rig
                    .rowsAfter(mark)
                    .transitioned()
                    .single()
            assertEquals("", event.str("summary"))
        }

    @Test
    fun `probe a replayed keyed advance records one event`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val item = d.item("probe replay", Role.QUEUE)
            val key = UUID.randomUUID().toString()
            val params = d.advanceParams(item, "start", actor = "agent-q28a", requestId = key)
            val mark = d.rig.maxSeq()

            d.rig.callOk(AdvanceItemTool(), *params)
            d.rig.callOk(AdvanceItemTool(), *params)

            val events = d.rig.rowsAfter(mark).transitioned()
            assertEquals(1, events.size, "the replay must not record a second event")
            val data = events.single().payload()
            for (key2 in auditKeys) assertTrue(data.containsKey(key2), "$key2 present on the tool path")
            assertEquals(Role.WORK, d.reload(item).role)
        }
}
