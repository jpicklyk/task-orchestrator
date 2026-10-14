package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.CallPrincipal
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.infrastructure.identity.LOCAL_PRINCIPAL
import io.github.jpicklyk.mcptask.current.interfaces.shared.auth.toCallPrincipal
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 8df44a22, R20) for [RunPermissions]: one predicate per row of the plan 3.2 run
 * permission table, at the unit seam (no database, no dispatcher).
 *
 * Oracles (never read from a run): task-scope planner additions, "Permissions": the caller id is `ctx.principal?.claim?.id` (a REST
 * bearer principal is audited as `api:<id>`, an MCP call carries the lifted actor id, a call with no actor has none); the director
 * is a PROFILE (`ctx.profile == "director"`), not a principal attribute; a human is a credential kind (`PrincipalKind.HUMAN`) and a
 * request-body actor can never be human (V2); the coordinator before R22 is the caller whose id equals `run.openedBy`, null matching
 * null (D-coord); close is coordinator, director or human; pause and resume are director or human only (the opener may not pause);
 * update is the coordinator only; a refusal is `forbidden` with scope `principal_kind` and the listed `required` string; `openedByKind`
 * is the credential's `kind.wire`, `"agent"` for a lifted body actor whatever its [ActorKind] (D-kind), null with no principal.
 * Expected values are literals or the truth table of those clauses, never what the code returns.
 *
 * Negative-assertion conditions: every `false` is paired, in the same test, with the same context shape that differs only in the
 * attribute under test and returns `true`, so a predicate that is constant fails one of the pair.
 *
 * Domain invariants satisfied by construction: a [Run] needs only its root, open instant and stall window (the rest default), which
 * are supplied; a [Principal] has no validation beyond its types; the call principal of a REST context is produced by the production
 * `toCallPrincipal()` from the same [Principal], so claim, verification and principal agree.
 *
 * Merge ruling (4), orchestrator-merge-rulings note: since R14 a principal-less CallContext no longer exists (the dispatcher refuses it), so
 * the null-principal scenarios (no callerId, null openerKind, null matching null, anonymous coordinating) are removed by design and
 * re-expressed with the local principal (id `local`, kind agent) or credential principals; the F1 lifted-actor spoof is replaced by its
 * credential form (the same id with a different credential kind is not the coordinator).
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
class RunPermissionsTest {
    private fun principal(
        id: String = "svc",
        kind: PrincipalKind = PrincipalKind.SERVICE
    ) = Principal(
        id = id,
        kind = kind,
        proofStatus = ProofStatus.ISSUED,
        scope = AccessScope.unrestricted(),
        capabilities = setOf(ApiCapability.RUNS)
    )

    /** A REST-style context: the caller id is `api:<id>` and the principal carries the credential kind. */
    private fun rest(
        id: String = "svc",
        kind: PrincipalKind = PrincipalKind.SERVICE,
        profile: String? = "rest"
    ) = CallContext(reqId = "perm-t", surface = "rest", principal = principal(id, kind).toCallPrincipal(), profile = profile)

    /** An MCP-style context: a lifted body actor claim with no credential principal. */
    private fun lifted(
        id: String,
        kind: ActorKind = ActorKind.SUBAGENT,
        profile: String? = "orchestration"
    ) = CallContext(
        reqId = "perm-t",
        surface = "mcp",
        principal =
            CallPrincipal(
                claim = ActorClaim(id = id, kind = kind),
                verification = VerificationResult(status = VerificationStatus.UNCHECKED, verifier = "noop")
            ),
        profile = profile
    )

    /** The local principal's context (the MCP default since R14): claim id `local`, credential kind agent. */
    private fun local(profile: String? = "orchestration") =
        CallContext(reqId = "perm-t", surface = "mcp", principal = LOCAL_PRINCIPAL.toCallPrincipal(), profile = profile)

    /** A run as the opener would have recorded it: an opener is always recorded with its kind, none with neither (D-kind). */
    private fun runOpenedBy(
        openedBy: String?,
        kind: String? = if (openedBy == null) null else "service"
    ) = Run(
        rootId = UUID.fromString("11111111-1111-4111-8111-111111111111"),
        openedBy = openedBy,
        openedByKind = kind,
        openedAt = Instant.parse("2026-10-10T11:00:00Z"),
        stallAfterSeconds = Run.DEFAULT_STALL_AFTER_SECONDS
    )

    // ------------------------------------------------------------------ callerId

    @Test
    fun `callerId is api-prefixed for a REST principal, the lifted id for an MCP actor and local for the local principal`() {
        assertEquals("api:rw-coord", RunPermissions.callerId(rest("rw-coord")))
        assertEquals("orchestrator-1", RunPermissions.callerId(lifted("orchestrator-1")))
        assertEquals("local", RunPermissions.callerId(local()))
    }

    @Test
    fun `callerId does not depend on the credential kind or the served profile`() {
        for (kind in PrincipalKind.entries) {
            for (profile in listOf("rest", "director", "orchestration", null)) {
                assertEquals("api:same", RunPermissions.callerId(rest("same", kind, profile)), "kind $kind profile $profile")
            }
        }
    }

    // ------------------------------------------------------------------ openerKind (D-kind)

    @Test
    fun `openerKind is the credential kind wire for a REST principal, whatever the kind`() {
        for (kind in PrincipalKind.entries) {
            assertEquals(kind.wire, RunPermissions.openerKind(rest("k-${kind.wire}", kind)), "kind $kind")
        }
        assertEquals("human", RunPermissions.openerKind(rest("h", PrincipalKind.HUMAN)))
        assertEquals("service", RunPermissions.openerKind(rest("s", PrincipalKind.SERVICE)))
        assertEquals("agent", RunPermissions.openerKind(rest("a", PrincipalKind.AGENT)))
    }

    @Test
    fun `openerKind is agent for a lifted body actor whatever kind it declares, so human is never self-declared`() {
        for (kind in ActorKind.entries) {
            assertEquals("agent", RunPermissions.openerKind(lifted("actor-${kind.name}", kind)), "declared kind $kind")
        }
        // control: the same id as a credential principal of kind HUMAN is the one way to be recorded human
        assertEquals("human", RunPermissions.openerKind(rest("actor-USER", PrincipalKind.HUMAN)))
    }

    @Test
    fun `openerKind is agent for the local principal, the kind of its credential`() {
        assertEquals("agent", RunPermissions.openerKind(local()))
        assertEquals("service", RunPermissions.openerKind(rest("x", PrincipalKind.SERVICE)), "control: another credential kind differs")
    }

    // ------------------------------------------------------------------ isDirector (V3: a profile)

    @Test
    fun `isDirector is true for the director profile only`() {
        assertTrue(RunPermissions.isDirector(rest("a", profile = "director")))
        for (profile in listOf("rest", "orchestration", "knowledge", null, "Director", "director ", "")) {
            assertFalse(RunPermissions.isDirector(rest("a", profile = profile)), "profile '$profile'")
        }
        assertTrue(RunPermissions.isDirector(lifted("a", profile = "director")), "an MCP call on the director profile is the director")
        assertFalse(RunPermissions.isDirector(local(profile = "orchestration")))
        assertTrue(RunPermissions.isDirector(local(profile = "director")), "the director is a profile, not a principal attribute")
    }

    @Test
    fun `isDirector does not follow the credential kind`() {
        for (kind in PrincipalKind.entries) {
            assertFalse(RunPermissions.isDirector(rest("a", kind, "rest")), "kind $kind on the plain route")
        }
    }

    // ------------------------------------------------------------------ isHuman (credential only)

    @Test
    fun `isHuman is true only for a credential principal of kind HUMAN`() {
        assertTrue(RunPermissions.isHuman(rest("h", PrincipalKind.HUMAN)))
        assertFalse(RunPermissions.isHuman(rest("s", PrincipalKind.SERVICE)))
        assertFalse(RunPermissions.isHuman(rest("a", PrincipalKind.AGENT)))
        assertFalse(RunPermissions.isHuman(local()), "the local principal is an agent, never human")
    }

    @Test
    fun `a lifted body actor is never human, whatever kind it declares`() {
        for (kind in ActorKind.entries) {
            assertFalse(RunPermissions.isHuman(lifted("h", kind)), "declared kind $kind")
        }
        assertTrue(RunPermissions.isHuman(rest("h", PrincipalKind.HUMAN)), "control: the same id as a HUMAN credential is human")
    }

    @Test
    fun `isHuman does not follow the served profile`() {
        for (profile in listOf("rest", "director", "orchestration", null)) {
            assertTrue(RunPermissions.isHuman(rest("h", PrincipalKind.HUMAN, profile)), "profile $profile")
            assertFalse(RunPermissions.isHuman(rest("s", PrincipalKind.SERVICE, profile)), "profile $profile")
        }
    }

    // ------------------------------------------------------------------ isCoordinator (D-coord)

    @Test
    fun `the coordinator is the caller whose id equals the opener`() {
        val run = runOpenedBy("api:rw-coord")
        assertTrue(RunPermissions.isCoordinator(rest("rw-coord"), run))
        assertFalse(RunPermissions.isCoordinator(rest("rw-other"), run))
        assertTrue(RunPermissions.isCoordinator(lifted("orchestrator-1"), runOpenedBy("orchestrator-1", "agent")))
        assertFalse(RunPermissions.isCoordinator(lifted("orchestrator-2"), runOpenedBy("orchestrator-1", "agent")))
    }

    @Test
    fun `the local principal coordinates only runs opened by local`() {
        assertTrue(RunPermissions.isCoordinator(local(), runOpenedBy("local", "agent")), "opened by local as an agent")
        assertFalse(RunPermissions.isCoordinator(local(), runOpenedBy("api:rw-coord")), "another opener's run")
        assertFalse(RunPermissions.isCoordinator(local(), runOpenedBy("local", "service")), "the id local with another recorded kind")
        assertFalse(RunPermissions.isCoordinator(rest("rw-coord"), runOpenedBy("local", "agent")), "another caller is not local")
    }

    @Test
    fun `the coordinator comparison is exact - case, prefix and whitespace differences are different callers`() {
        val run = runOpenedBy("api:rw-coord")
        assertTrue(RunPermissions.isCoordinator(rest("rw-coord"), run), "control: the exact id matches")
        assertFalse(RunPermissions.isCoordinator(rest("RW-COORD"), run), "case differs")
        assertFalse(RunPermissions.isCoordinator(rest("rw-coord "), run), "trailing space differs")
        assertFalse(RunPermissions.isCoordinator(rest("rw-coor"), run), "a prefix of the id is not the id")
        assertFalse(RunPermissions.isCoordinator(rest("rw-coord2"), run), "an extension of the id is not the id")
        assertFalse(RunPermissions.isCoordinator(lifted("rw-coord"), run), "a lifted id without the api: prefix is not the REST caller")
    }

    @Test
    fun `a REST token whose id already starts with api is not the coordinator of a run opened by the token it imitates`() {
        val run = runOpenedBy("api:rw-coord")
        // the token id is literally "api:rw-coord", so its audited identity is "api:api:rw-coord"
        assertEquals("api:api:rw-coord", RunPermissions.callerId(rest("api:rw-coord")))
        assertFalse(RunPermissions.isCoordinator(rest("api:rw-coord"), run))
        assertTrue(RunPermissions.isCoordinator(rest("rw-coord"), run), "control: the real token is")
    }

    @Test
    fun `the coordinator does not depend on the served profile, for each credential kind the opener recorded`() {
        for (kind in PrincipalKind.entries) {
            val run = runOpenedBy("api:rw-coord", kind.wire)
            for (profile in listOf("rest", "director", "orchestration", null)) {
                assertTrue(RunPermissions.isCoordinator(rest("rw-coord", kind, profile), run), "kind $kind profile $profile")
            }
        }
    }

    @Test
    fun `the coordinator is the caller whose id AND kind equal the recorded opener, so the same id with another recorded kind is not`() {
        // Orchestrator ruling (decisions note, item 3) amending TS-P: id equality plus the caller kind equal to openedByKind.
        val recordedService = runOpenedBy("api:rw-coord", "service")
        assertTrue(RunPermissions.isCoordinator(rest("rw-coord", PrincipalKind.SERVICE), recordedService), "same id, same kind")
        val recordedHuman = runOpenedBy("api:rw-coord", "human")
        assertFalse(RunPermissions.isCoordinator(rest("rw-coord", PrincipalKind.SERVICE), recordedHuman), "same id, different kind")
        assertTrue(RunPermissions.isCoordinator(rest("rw-coord", PrincipalKind.HUMAN), recordedHuman), "control: same id, matching kind")
        assertFalse(RunPermissions.isCoordinator(rest("rw-other", PrincipalKind.SERVICE), recordedService), "control: another id is not")
    }

    @Test
    fun `a credential with the same id but a different kind is not the coordinator and cannot update or close the run`() {
        // The run was opened by REST principal api:rw-coord whose credential kind is service.
        val run = runOpenedBy("api:rw-coord", "service")
        val spoof = rest("rw-coord", PrincipalKind.AGENT)
        assertEquals("api:rw-coord", RunPermissions.callerId(spoof), "the spoof carries the identical caller id")
        assertFalse(RunPermissions.isCoordinator(spoof, run))
        assertFalse(RunPermissions.mayUpdate(spoof, run), "update requires the coordinator, so the spoof is refused")
        assertFalse(RunPermissions.mayClose(spoof, run), "close needs coordinator, director or human; the spoof is none")
        val refusal = assertIs<ErrorDetail.Forbidden>(RunPermissions.refuse(RunPermissions.UPDATE_REQUIRED).detail)
        assertEquals(ForbiddenScope.PRINCIPAL_KIND, refusal.scope)
        assertEquals("coordinator", refusal.required)
        // controls: the genuine opener credential is the coordinator, and the local principal is for its own runs
        assertTrue(RunPermissions.isCoordinator(rest("rw-coord", PrincipalKind.SERVICE), run))
        assertTrue(RunPermissions.mayUpdate(rest("rw-coord", PrincipalKind.SERVICE), run))
        assertTrue(RunPermissions.isCoordinator(local(), runOpenedBy("local", "agent")))
    }

    // ------------------------------------------------------------------ the three rows, as a truth table

    /** A context that is (or is not) the coordinator, the director and a human, independently. */
    private fun ctx(
        coordinator: Boolean,
        director: Boolean,
        human: Boolean
    ) = rest(
        id = if (coordinator) "rw-coord" else "rw-stranger",
        kind = if (human) PrincipalKind.HUMAN else PrincipalKind.SERVICE,
        profile = if (director) "director" else "rest"
    )

    /** The run the coordinator of a combination opened, recorded with the kind that combination's credential has. */
    private fun runFor(human: Boolean) = runOpenedBy("api:rw-coord", if (human) "human" else "service")

    private val combinations: List<Triple<Boolean, Boolean, Boolean>> =
        listOf(false, true).flatMap { c -> listOf(false, true).flatMap { d -> listOf(false, true).map { h -> Triple(c, d, h) } } }

    @Test
    fun `mayClose is coordinator or director or human, for all eight combinations`() {
        for ((c, d, h) in combinations) {
            assertEquals(c || d || h, RunPermissions.mayClose(ctx(c, d, h), runFor(h)), "coordinator=$c director=$d human=$h")
        }
    }

    @Test
    fun `mayPauseOrResume is director or human only - the coordinator alone may not, for all eight combinations`() {
        for ((c, d, h) in combinations) {
            assertEquals(d || h, RunPermissions.mayPauseOrResume(ctx(c, d, h)), "coordinator=$c director=$d human=$h")
        }
        assertFalse(RunPermissions.mayPauseOrResume(ctx(coordinator = true, director = false, human = false)), "the opener may not pause")
    }

    @Test
    fun `mayUpdate is the coordinator only - neither the director nor a human may, for all eight combinations`() {
        for ((c, d, h) in combinations) {
            assertEquals(c, RunPermissions.mayUpdate(ctx(c, d, h), runFor(h)), "coordinator=$c director=$d human=$h")
        }
        assertFalse(RunPermissions.mayUpdate(ctx(coordinator = false, director = true, human = true), runFor(true)))
    }

    @Test
    fun `the predicates for a lifted body actor follow the actor id and the profile, never a kind it declares`() {
        val opener = runOpenedBy("orchestrator-1", "agent")
        assertTrue(RunPermissions.mayClose(lifted("orchestrator-1", ActorKind.SUBAGENT), opener))
        assertFalse(RunPermissions.mayClose(lifted("orchestrator-2", ActorKind.USER), opener), "declaring user does not make a human")
        assertFalse(RunPermissions.mayPauseOrResume(lifted("orchestrator-1", ActorKind.USER)), "declaring user does not make a human")
        assertTrue(RunPermissions.mayPauseOrResume(lifted("orchestrator-2", profile = "director")), "the director profile may")
        assertTrue(RunPermissions.mayUpdate(lifted("orchestrator-1"), opener))
        assertFalse(RunPermissions.mayUpdate(lifted("orchestrator-2", profile = "director"), opener))
    }

    @Test
    fun `the local principal coordinates its own run, so it may close and update it but not pause it`() {
        val own = runOpenedBy("local", "agent")
        assertTrue(RunPermissions.mayClose(local(), own))
        assertTrue(RunPermissions.mayUpdate(local(), own))
        assertFalse(RunPermissions.mayPauseOrResume(local()))
        assertTrue(RunPermissions.mayPauseOrResume(local(profile = "director")), "control: the director profile may pause")
        assertFalse(RunPermissions.mayUpdate(local(), runOpenedBy("api:rw-coord")), "control: not a named opener's run")
    }

    // ------------------------------------------------------------------ refuse

    @Test
    fun `the required strings of the three rows are the listed kinds`() {
        assertEquals("coordinator,director,human", RunPermissions.CLOSE_REQUIRED)
        assertEquals("director,human", RunPermissions.PAUSE_REQUIRED)
        assertEquals("coordinator", RunPermissions.UPDATE_REQUIRED)
    }

    @Test
    fun `refuse is forbidden with the principal_kind scope and the given required string`() {
        for (required in listOf(RunPermissions.CLOSE_REQUIRED, RunPermissions.PAUSE_REQUIRED, RunPermissions.UPDATE_REQUIRED)) {
            val error = RunPermissions.refuse(required)
            assertEquals(ErrorCode.FORBIDDEN, error.code, required)
            val detail = assertIs<ErrorDetail.Forbidden>(error.detail, required)
            assertEquals(ForbiddenScope.PRINCIPAL_KIND, detail.scope, required)
            assertEquals(required, detail.required, required)
        }
    }
}
