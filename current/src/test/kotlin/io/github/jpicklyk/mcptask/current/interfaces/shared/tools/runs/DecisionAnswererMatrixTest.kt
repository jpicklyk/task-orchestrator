package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.api.SeatContext
import io.github.jpicklyk.mcptask.current.application.decision.DecisionCaller
import io.github.jpicklyk.mcptask.current.application.decision.admits
import io.github.jpicklyk.mcptask.current.application.decision.isDirector
import io.github.jpicklyk.mcptask.current.application.decision.isHuman
import io.github.jpicklyk.mcptask.current.application.decision.required
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestAnswerers
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.sha256
import io.github.jpicklyk.mcptask.current.interfaces.shared.auth.toCallPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.PARITY_CLOCK
import io.github.jpicklyk.mcptask.current.parity.ParitySeeder
import io.github.jpicklyk.mcptask.current.parity.RunsParityScenarios
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item a53e6875, R24) for the answerer matrix, the consent boundary of a decision: who may
 * ack and answer a decision of each class. Two seams: the pure predicate over a [DecisionCaller] built from a [CallContext]
 * (class x kind x proof x profile x token, exhaustively) and the REST routes (`/ops/runs_write` served as `rest`,
 * `/ops/director/runs_write` served as `director`) with real bearer principals, where the refusal is the wire shape.
 *
 * Oracles (never read from a run): plan 3.3 and task-scope D6: `human-only` admits a caller whose credential kind is HUMAN, who
 * presents NO seat token, and whose proof status is VERIFIED, VERIFIED_STALE or ISSUED (never NONE or REJECTED); `director-or-human`
 * admits that human, or a caller served on the `director` profile who presents no seat token; `any-actor` admits everyone; a
 * refusal is `forbidden{scope: principal_kind, required}` with `required` "human" for human-only and "director,human" for
 * director-or-human; the director is a PROFILE (never a credential attribute) and a seat is always an agent, so under any seat token
 * the caller is neither a human nor the director. The expected admission is a three-line truth table written here from those
 * clauses, never read from the predicate.
 *
 * Negative-assertion conditions: every refusal is paired, in the same loop, with the same context differing only in the attribute
 * under test that is admitted; the loop also asserts that the truth table admits some and refuses some contexts for each class, so
 * a predicate that is constant fails. The route tests pair each refused principal with an admitted one on the same class and mount.
 *
 * Domain invariants satisfied by construction: a [Principal] has no validation beyond its types; the call principal is produced by
 * the production `toCallPrincipal()` from the same [Principal]; a [SeatContext] names a run and an assignment of its own.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class DecisionAnswererMatrixTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    // ------------------------------------------------------------------ the truth table (task-scope D6)

    private val humanProofs = setOf(ProofStatus.VERIFIED, ProofStatus.VERIFIED_STALE, ProofStatus.ISSUED)

    private fun humanExpected(
        kind: PrincipalKind?,
        proof: ProofStatus?,
        seat: String?
    ): Boolean = kind == PrincipalKind.HUMAN && seat == null && proof in humanProofs

    private fun directorExpected(
        profile: String?,
        seat: String?
    ): Boolean = profile == "director" && seat == null

    private fun admittedExpected(
        answerers: DecisionRequestAnswerers,
        kind: PrincipalKind?,
        proof: ProofStatus?,
        profile: String?,
        seat: String?
    ): Boolean =
        when (answerers) {
            DecisionRequestAnswerers.HUMAN_ONLY -> humanExpected(kind, proof, seat)
            DecisionRequestAnswerers.DIRECTOR_OR_HUMAN -> humanExpected(kind, proof, seat) || directorExpected(profile, seat)
            DecisionRequestAnswerers.ANY_ACTOR -> true
        }

    // ------------------------------------------------------------------ unit seam: DecisionCallers.of(ctx) then admits

    private fun principal(
        id: String,
        kind: PrincipalKind,
        proof: ProofStatus
    ) = Principal(
        id = id,
        kind = kind,
        proofStatus = proof,
        scope = AccessScope.unrestricted(),
        capabilities = setOf(ApiCapability.RUNS, ApiCapability.DECIDE)
    )

    private fun context(
        kind: PrincipalKind,
        proof: ProofStatus,
        profile: String?,
        seat: SeatContext?,
        id: String = "dm-${kind.wire}-${proof.wire}"
    ): CallContext =
        CallContext(reqId = "dm-matrix", surface = "rest", principal = principal(id, kind, proof).toCallPrincipal(), profile = profile)
            .copy(seat = seat)

    private val runId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val memberSeat = SeatContext(runId = runId, assignmentId = UUID.randomUUID(), itemId = UUID.randomUUID(), seat = "implementer")
    private val coordinatorSeat = SeatContext(runId = runId, assignmentId = UUID.randomUUID(), itemId = null, seat = "coordinator")

    @Test
    fun `the human proof set is exactly verified, verified_stale and issued`() {
        assertEquals(humanProofs, DecisionCaller.HUMAN_PROOFS)
        assertEquals(setOf(ProofStatus.NONE, ProofStatus.REJECTED), ProofStatus.entries.toSet() - DecisionCaller.HUMAN_PROOFS)
    }

    @Test
    fun `required names the human for human-only and the director and human for director-or-human`() {
        assertEquals("human", DecisionRequestAnswerers.HUMAN_ONLY.required())
        assertEquals("director,human", DecisionRequestAnswerers.DIRECTOR_OR_HUMAN.required())
    }

    @Test
    fun `the whole matrix class x kind x proof x profile x token admits exactly what the truth table says`() =
        runBlocking {
            val mismatches = mutableListOf<String>()
            val admittedPerClass = mutableMapOf<DecisionRequestAnswerers, Int>()
            val refusedPerClass = mutableMapOf<DecisionRequestAnswerers, Int>()
            for (kind in PrincipalKind.entries) {
                for (proof in ProofStatus.entries) {
                    for (profile in listOf("director", "orchestration", "rest", "knowledge", null)) {
                        for (seat in listOf<SeatContext?>(null, memberSeat, coordinatorSeat)) {
                            val caller = DecisionCallers.of(context(kind, proof, profile, seat))
                            for (answerers in DecisionRequestAnswerers.entries) {
                                val expected = admittedExpected(answerers, kind, proof, profile, seat?.seat)
                                if (expected) {
                                    admittedPerClass.merge(answerers, 1, Int::plus)
                                } else {
                                    refusedPerClass.merge(answerers, 1, Int::plus)
                                }
                                if (answerers.admits(caller) != expected) {
                                    mismatches +=
                                        "$answerers kind=$kind proof=$proof profile=$profile seat=${seat?.seat}: expected $expected"
                                }
                            }
                        }
                    }
                }
            }
            assertEquals(emptyList(), mismatches, "every context is decided by the truth table")
            // Non-vacuity: each restrictive class both admits and refuses somebody, and any-actor refuses nobody.
            assertTrue((admittedPerClass[DecisionRequestAnswerers.HUMAN_ONLY] ?: 0) > 0, "human-only admits a human")
            assertTrue((refusedPerClass[DecisionRequestAnswerers.HUMAN_ONLY] ?: 0) > 0, "human-only refuses a non-human")
            assertTrue((admittedPerClass[DecisionRequestAnswerers.DIRECTOR_OR_HUMAN] ?: 0) > 0, "director-or-human admits")
            assertTrue((refusedPerClass[DecisionRequestAnswerers.DIRECTOR_OR_HUMAN] ?: 0) > 0, "director-or-human refuses")
            assertEquals(null, refusedPerClass[DecisionRequestAnswerers.ANY_ACTOR], "any-actor refuses nobody")
        }

    @Test
    fun `a human credential with no proof or a rejected proof is not a human, whatever the profile`() =
        runBlocking {
            for (proof in listOf(ProofStatus.NONE, ProofStatus.REJECTED)) {
                for (profile in listOf("rest", "orchestration", null)) {
                    val caller = DecisionCallers.of(context(PrincipalKind.HUMAN, proof, profile, seat = null))
                    assertFalse(DecisionRequestAnswerers.HUMAN_ONLY.admits(caller), "human-only $proof $profile")
                    assertFalse(DecisionRequestAnswerers.DIRECTOR_OR_HUMAN.admits(caller), "director-or-human $proof $profile")
                }
            }
            // controls: the same credential with each acceptable proof is admitted on both restrictive classes
            for (proof in humanProofs) {
                val caller = DecisionCallers.of(context(PrincipalKind.HUMAN, proof, "rest", seat = null))
                assertTrue(DecisionRequestAnswerers.HUMAN_ONLY.admits(caller), "human-only $proof")
                assertTrue(DecisionRequestAnswerers.DIRECTOR_OR_HUMAN.admits(caller), "director-or-human $proof")
            }
        }

    @Test
    fun `the director profile alone does not make a human-only answerer, and a seat token strips the director`() =
        runBlocking {
            val director = DecisionCallers.of(context(PrincipalKind.SERVICE, ProofStatus.ISSUED, "director", seat = null))
            assertTrue(director.isDirector())
            assertFalse(director.isHuman())
            assertTrue(DecisionRequestAnswerers.DIRECTOR_OR_HUMAN.admits(director))
            assertFalse(DecisionRequestAnswerers.HUMAN_ONLY.admits(director))

            val seated = DecisionCallers.of(context(PrincipalKind.SERVICE, ProofStatus.ISSUED, "director", seat = memberSeat))
            assertFalse(seated.isDirector(), "a seat token on the director profile is not the director (a seat is an agent)")
            assertFalse(DecisionRequestAnswerers.DIRECTOR_OR_HUMAN.admits(seated))
            assertTrue(DecisionRequestAnswerers.ANY_ACTOR.admits(seated), "control: any-actor still admits a seat")

            val seatedHuman = DecisionCallers.of(context(PrincipalKind.HUMAN, ProofStatus.ISSUED, "director", seat = coordinatorSeat))
            assertFalse(seatedHuman.isHuman(), "a human credential under a seat token is not a human")
            assertFalse(DecisionRequestAnswerers.HUMAN_ONLY.admits(seatedHuman))
            assertFalse(DecisionRequestAnswerers.DIRECTOR_OR_HUMAN.admits(seatedHuman))
        }

    @Test
    fun `the caller carries the credential kind, proof, seat and run from the context`() =
        runBlocking {
            val plain = DecisionCallers.of(context(PrincipalKind.HUMAN, ProofStatus.VERIFIED, "director", seat = null, id = "person-1"))
            assertEquals("api:person-1", plain.principalId)
            assertEquals(PrincipalKind.HUMAN, plain.kind)
            assertEquals(ProofStatus.VERIFIED, plain.proofStatus)
            assertTrue(plain.director)
            assertNull(plain.seat)
            assertNull(plain.runId)
            assertNull(plain.assignmentId)
            assertNotNull(plain.provenance, "a REST call carries provenance")

            val seated = DecisionCallers.of(context(PrincipalKind.AGENT, ProofStatus.ISSUED, "rest", seat = memberSeat, id = "agent-1"))
            assertEquals(PrincipalKind.AGENT, seated.kind)
            assertFalse(seated.director)
            assertEquals("implementer", seated.seat)
            assertEquals(memberSeat.runId, seated.runId)
            assertEquals(memberSeat.assignmentId, seated.assignmentId)
        }

    // ------------------------------------------------------------------ route seam

    private val registry = ToolRegistry(buildToolSpecs())

    private val rootId = RunsParityScenarios.r1
    private val childId = RunsParityScenarios.c1
    private val runA = RunsParityScenarios.ra

    private val seatToken = "m".repeat(43)

    private class Bearer(
        val id: String,
        val kind: PrincipalKind,
        val proof: ProofStatus
    ) {
        val raw: String get() = "raw-dm-$id"
    }

    private val orchestrator = Bearer("dm-orch", PrincipalKind.SERVICE, ProofStatus.ISSUED)

    private val bearers: List<Bearer> =
        listOf(orchestrator) +
            PrincipalKind.entries.flatMap { kind ->
                ProofStatus.entries.map { proof -> Bearer("dm-${kind.wire}-${proof.wire}", kind, proof) }
            }

    private val auth =
        ApiAuthConfig.Bearer(
            tokens =
                bearers.associate { b ->
                    HashBytes(sha256(b.raw)) to
                        BearerTokenStore.TokenEntry(
                            Principal(
                                id = b.id,
                                scope = AccessScope.unrestricted(),
                                capabilities = setOf(ApiCapability.RUNS, ApiCapability.READ, ApiCapability.DECIDE),
                                kind = b.kind,
                                proofStatus = b.proof
                            ),
                            expiresAt = null
                        )
                }
        )

    private val config =
        "work_item_schemas:\n  test-item:\n    notes: []\n" +
            "decision_classes:\n  signoff:\n    answerers: human-only\n"

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            val rig = EventLogRig.build(db.db, dir, config, PARITY_CLOCK)
            val provider = db.db.repositoryProvider()
            val seeder = ParitySeeder(provider.workItemRepository(), provider.dependencyRepository(), provider, db.db.unitOfWork())
            runBlocking {
                RunsParityScenarios.tree.invoke(seeder)
                seeder.run(runA, rootId, listOf(childId), openedBy = "api:${orchestrator.id}", openedByKind = "service")
                // one member seat per credential, so any credential can present a seat token as the principal it was assigned to
                // (one seat name each: the active (run, item, seat) triple is unique)
                bearers.forEachIndexed { i, b ->
                    seeder.seat(runA, childId, "impl$i", principalId = "api:${b.id}", token = seatTokenOf(b))
                }
            }
            application { installParityRestApp(rig, auth, registry) }
            block()
        }

    private fun seatTokenOf(b: Bearer): String = seatToken.take(40) + "%03d".format(bearers.indexOf(b))

    private suspend fun HttpClient.send(
        path: String,
        body: String,
        token: Bearer
    ): HttpResponse =
        post(path) {
            header(HttpHeaders.Authorization, "Bearer ${token.raw}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private var counter = 0

    private fun raiseBody(cls: String): String {
        counter++
        return """{"ops":[{"op":"raise","class":"$cls","question":"Which one?",""" +
            """"options":[{"key":"a","label":"A"},{"key":"b","label":"B"}],""" +
            """"dedupeKey":"dm-$counter","rootId":"$rootId"}]}"""
    }

    private fun answerBody(
        decisionId: String,
        seatTokenValue: String? = null
    ): String =
        """{"ops":[{"op":"answer","decisionId":"$decisionId","option":"a"}]""" +
            (if (seatTokenValue != null) ""","seatToken":"$seatTokenValue"""" else "") + "}"

    private val uuidPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /** The decision id in a raise reply: the first `decisionId` (else `id`) string that is a UUID, searched through the whole body. */
    private fun decisionIdIn(body: String): String {
        fun find(
            element: JsonElement,
            key: String
        ): String? =
            when (element) {
                is JsonObject ->
                    element[key]?.let { (it as? JsonPrimitive)?.content }?.takeIf { uuidPattern.matches(it) }
                        ?: element.values.firstNotNullOfOrNull { find(it, key) }
                is JsonArray -> element.firstNotNullOfOrNull { find(it, key) }
                else -> null
            }
        val parsed = Json.parseToJsonElement(body)
        return checkNotNull(find(parsed, "decisionId") ?: find(parsed, "id")) { "no decision id in $body" }
    }

    private suspend fun HttpClient.raised(cls: String): String {
        val response = send("/api/v1/ops/runs_write", raiseBody(cls), orchestrator)
        val text = response.bodyAsText()
        assertEquals(200, response.status.value, "raise $cls: $text")
        return decisionIdIn(text)
    }

    private val answeredMarker = "\"status\":\"answered\""

    private fun requiredOf(answerers: DecisionRequestAnswerers) = answerers.required()

    private fun classOf(answerers: DecisionRequestAnswerers) =
        when (answerers) {
            DecisionRequestAnswerers.HUMAN_ONLY -> "signoff"
            DecisionRequestAnswerers.DIRECTOR_OR_HUMAN -> "deviation"
            DecisionRequestAnswerers.ANY_ACTOR -> "question"
        }

    @Test
    fun `route - every credential kind and proof is admitted exactly as the truth table says on both mounts for every class`() =
        withApp {
            val mismatches = mutableListOf<String>()
            var admitted = 0
            var refused = 0
            for (answerers in DecisionRequestAnswerers.entries) {
                for ((mount, profile) in listOf("/api/v1/ops/runs_write" to "rest", "/api/v1/ops/director/runs_write" to "director")) {
                    for (b in bearers.filter { it !== orchestrator }) {
                        val decisionId = client.raised(classOf(answerers))
                        val response = client.send(mount, answerBody(decisionId), b)
                        val text = response.bodyAsText()
                        val expected = admittedExpected(answerers, b.kind, b.proof, profile, seat = null)
                        val label = "$answerers ${b.kind} ${b.proof} on $mount"
                        if (expected) {
                            admitted++
                            if (response.status.value != 200 ||
                                answeredMarker !in text
                            ) {
                                mismatches += "$label: expected answered, got ${response.status.value} $text"
                            }
                        } else {
                            refused++
                            val shaped =
                                response.status.value == 207 &&
                                    "\"code\":\"forbidden\"" in text &&
                                    "\"scope\":\"principal_kind\"" in text &&
                                    "\"required\":\"${requiredOf(answerers)}\"" in text
                            if (!shaped) {
                                mismatches +=
                                    "$label: expected forbidden principal_kind ${requiredOf(answerers)}, got ${response.status.value} $text"
                            }
                        }
                    }
                }
            }
            assertEquals(emptyList(), mismatches)
            assertTrue(admitted > 0 && refused > 0, "the matrix both admits and refuses: admitted=$admitted refused=$refused")
        }

    @Test
    fun `route - a refused answer leaves the decision open and the same decision is then answered by an admitted principal`() =
        withApp {
            val agent = bearers.single { it.id == "dm-agent-issued" }
            val human = bearers.single { it.id == "dm-human-issued" }
            val decisionId = client.raised("signoff")

            val refused = client.send("/api/v1/ops/runs_write", answerBody(decisionId), agent)
            assertEquals(207, refused.status.value)
            val stillOpen =
                client.send(
                    "/api/v1/ops/director/runs_read",
                    """{"select":"decision","decisionId":"$decisionId"}""",
                    human
                )
            assertTrue("\"status\":\"open\"" in stillOpen.bodyAsText(), "the refused answer changed nothing: ${stillOpen.bodyAsText()}")

            val answered = client.send("/api/v1/ops/runs_write", answerBody(decisionId), human)
            assertEquals(200, answered.status.value, answered.bodyAsText())
            assertTrue(answeredMarker in answered.bodyAsText())
        }

    @Test
    fun `route - a seat token on the director mount is not the director and a seat token over a human credential is not a human`() =
        withApp {
            val service = bearers.single { it.id == "dm-service-issued" }
            val human = bearers.single { it.id == "dm-human-issued" }

            // director-or-human: the service credential on the director mount is the director WITHOUT a seat token (control) ...
            val control = client.raised("deviation")
            val admitted = client.send("/api/v1/ops/director/runs_write", answerBody(control), service)
            assertEquals(200, admitted.status.value, admitted.bodyAsText())

            // ... and refused with the director,human requirement once it presents its member seat token
            val seated = client.raised("deviation")
            val refused = client.send("/api/v1/ops/director/runs_write", answerBody(seated, seatTokenOf(service)), service)
            val text = refused.bodyAsText()
            assertEquals(207, refused.status.value, text)
            assertTrue(
                "\"code\":\"forbidden\"" in text && "\"scope\":\"principal_kind\"" in text && "\"required\":\"director,human\"" in text,
                text
            )

            // human-only: the human credential (control) is admitted, the same credential under a seat token is refused as not a human
            val humanControl = client.raised("signoff")
            assertEquals(200, client.send("/api/v1/ops/runs_write", answerBody(humanControl), human).status.value)
            val humanSeated = client.raised("signoff")
            val humanRefused = client.send("/api/v1/ops/runs_write", answerBody(humanSeated, seatTokenOf(human)), human)
            val humanText = humanRefused.bodyAsText()
            assertEquals(207, humanRefused.status.value, humanText)
            assertTrue("\"required\":\"human\"" in humanText && "\"scope\":\"principal_kind\"" in humanText, humanText)

            // any-actor: a seat token is admitted (control that the seat token itself is accepted by the dispatcher)
            val anyActor = client.raised("question")
            val anyText = client.send("/api/v1/ops/runs_write", answerBody(anyActor, seatTokenOf(service)), service)
            assertEquals(200, anyText.status.value, anyText.bodyAsText())
        }

    @Test
    fun `route - ack follows the same matrix as answer`() =
        withApp {
            val agent = bearers.single { it.id == "dm-agent-issued" }
            val human = bearers.single { it.id == "dm-human-issued" }
            val decisionId = client.raised("signoff")

            val refused = client.send("/api/v1/ops/runs_write", """{"ops":[{"op":"ack","decisionId":"$decisionId"}]}""", agent)
            val refusedText = refused.bodyAsText()
            assertEquals(207, refused.status.value, refusedText)
            assertTrue("\"scope\":\"principal_kind\"" in refusedText && "\"required\":\"human\"" in refusedText, refusedText)

            val acked = client.send("/api/v1/ops/runs_write", """{"ops":[{"op":"ack","decisionId":"$decisionId"}]}""", human)
            assertEquals(200, acked.status.value, acked.bodyAsText())
            assertTrue("\"status\":\"acknowledged\"" in acked.bodyAsText(), acked.bodyAsText())
        }
}
