package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.CallPrincipal
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.api.SeatContext
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
 * request-body actor can never be human (V2); since R22 (decision D3) the coordinator is the caller whose call resolved the
 * coordinator seat token of THAT run (`ctx.seat` is the run-scoped `coordinator` pseudo-seat of the run), never the opener by
 * identity, and under any seat token the caller is neither a human nor the director (a seat is always an agent); `update` and
 * `assign` need the coordinator token, `end_seat` a member seat token; close is coordinator, director or human; pause and resume are director or human only (the opener may not pause);
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

    // ------------------------------------------------------------------ isCoordinator (D3: the coordinator TOKEN, R22)

    /** The seat context a coordinator token resolves to for [run] (the run-scoped pseudo-seat has no item). */
    private fun coordinatorSeat(run: Run) =
        SeatContext(runId = run.id, assignmentId = UUID.randomUUID(), itemId = null, seat = "coordinator")

    /** The seat context of a member seat token for [run]. */
    private fun memberSeat(
        run: Run,
        seat: String = "implementer"
    ) = SeatContext(runId = run.id, assignmentId = UUID.randomUUID(), itemId = UUID.randomUUID(), seat = seat)

    private fun CallContext.under(seat: SeatContext?): CallContext = copy(seat = seat)

    @Test
    fun `the coordinator is the caller presenting the coordinator seat of that run, whatever its identity`() {
        val run = runOpenedBy("api:rw-coord")
        assertTrue(RunPermissions.isCoordinator(rest("rw-coord").under(coordinatorSeat(run)), run), "the coordinator token of this run")
        // the dispatcher bound the token to its principal before the context was built, so identity plays no part once a token resolved
        assertTrue(RunPermissions.isCoordinator(rest("rw-other").under(coordinatorSeat(run)), run))
        assertTrue(RunPermissions.isCoordinator(lifted("orchestrator-1").under(coordinatorSeat(run)), run))
        assertTrue(RunPermissions.isCoordinator(local().under(coordinatorSeat(run)), run))
    }

    @Test
    fun `the opener without a token is not the coordinator - for every way the opener can be identified`() {
        val byRest = runOpenedBy("api:rw-coord", "service")
        assertFalse(RunPermissions.isCoordinator(rest("rw-coord", PrincipalKind.SERVICE), byRest), "same id, same kind, no token")
        val byLifted = runOpenedBy("orchestrator-1", "agent")
        assertFalse(RunPermissions.isCoordinator(lifted("orchestrator-1"), byLifted), "a lifted actor that opened the run")
        val byLocal = runOpenedBy("local", "agent")
        assertFalse(RunPermissions.isCoordinator(local(), byLocal), "the local principal that opened the run")
        // control: the same three callers presenting the coordinator token of their run are the coordinator
        assertTrue(RunPermissions.isCoordinator(rest("rw-coord", PrincipalKind.SERVICE).under(coordinatorSeat(byRest)), byRest))
        assertTrue(RunPermissions.isCoordinator(lifted("orchestrator-1").under(coordinatorSeat(byLifted)), byLifted))
        assertTrue(RunPermissions.isCoordinator(local().under(coordinatorSeat(byLocal)), byLocal))
    }

    @Test
    fun `a coordinator token of another run does not make the caller the coordinator of this one`() {
        val run = runOpenedBy("api:rw-coord")
        val other = runOpenedBy("api:rw-coord")
        assertFalse(RunPermissions.isCoordinator(rest("rw-coord").under(coordinatorSeat(other)), run), "the seat names another run")
        assertTrue(
            RunPermissions.isCoordinator(rest("rw-coord").under(coordinatorSeat(other)), other),
            "control: it is the coordinator of its own"
        )
    }

    @Test
    fun `a member seat token is not the coordinator, whatever the seat is called`() {
        val run = runOpenedBy("api:rw-coord")
        for (seat in listOf(
            "implementer",
            "planner",
            "Coordinator",
            "COORDINATOR",
            "coordinator ",
            " coordinator",
            "coordinators",
            "coord"
        )) {
            assertFalse(RunPermissions.isCoordinator(rest("rw-coord").under(memberSeat(run, seat)), run), "member seat [$seat]")
        }
        // control: the exact pseudo-seat name on a run-scoped context is the coordinator
        assertTrue(RunPermissions.isCoordinator(rest("rw-coord").under(coordinatorSeat(run)), run))
    }

    @Test
    fun `the coordinator does not depend on the served profile or the credential kind`() {
        val run = runOpenedBy("api:rw-coord")
        for (kind in PrincipalKind.entries) {
            for (profile in listOf("rest", "director", "orchestration", null)) {
                assertTrue(
                    RunPermissions.isCoordinator(rest("rw-coord", kind, profile).under(coordinatorSeat(run)), run),
                    "kind $kind profile $profile"
                )
                assertFalse(RunPermissions.isCoordinator(rest("rw-coord", kind, profile), run), "no token: kind $kind profile $profile")
            }
        }
    }

    @Test
    fun `under any seat token the caller is neither a human nor the director`() {
        val run = runOpenedBy("api:rw-coord")
        for (seat in listOf(coordinatorSeat(run), memberSeat(run))) {
            assertFalse(RunPermissions.isHuman(rest("h", PrincipalKind.HUMAN).under(seat)), "a human credential under ${seat.seat}")
            assertFalse(RunPermissions.isDirector(rest("a", profile = "director").under(seat)), "the director profile under ${seat.seat}")
            assertFalse(RunPermissions.isDirector(lifted("a", profile = "director").under(seat)), "a lifted director under ${seat.seat}")
        }
        // controls: the same contexts without a token are human and director
        assertTrue(RunPermissions.isHuman(rest("h", PrincipalKind.HUMAN)))
        assertTrue(RunPermissions.isDirector(rest("a", profile = "director")))
    }

    // ------------------------------------------------------------------ the rows, as a truth table over (token, director, human)

    /** A context that is (or is not) a human credential and on the director profile, optionally under [seat]. */
    private fun ctx(
        director: Boolean,
        human: Boolean,
        seat: SeatContext? = null
    ) = rest(
        id = "rw-caller",
        kind = if (human) PrincipalKind.HUMAN else PrincipalKind.SERVICE,
        profile = if (director) "director" else "rest"
    ).under(seat)

    private val combinations: List<Pair<Boolean, Boolean>> = listOf(false, true).flatMap { d -> listOf(false, true).map { h -> d to h } }

    @Test
    fun `mayClose without a token is director or human, and the opener gets nothing from having opened`() {
        val run = runOpenedBy("api:rw-caller", "service")
        for ((d, h) in combinations) {
            assertEquals(d || h, RunPermissions.mayClose(ctx(d, h), run), "no token: director=$d human=$h")
        }
        assertFalse(RunPermissions.mayClose(ctx(director = false, human = false), run), "the opener without a token may not close")
    }

    @Test
    fun `mayClose with the coordinator token is true for every credential, and with a member token is false for every credential`() {
        val run = runOpenedBy("api:rw-caller", "service")
        for ((d, h) in combinations) {
            assertTrue(RunPermissions.mayClose(ctx(d, h, coordinatorSeat(run)), run), "coordinator token: director=$d human=$h")
            assertFalse(RunPermissions.mayClose(ctx(d, h, memberSeat(run)), run), "member token: director=$d human=$h")
        }
    }

    @Test
    fun `mayPauseOrResume is director or human without a token and never under a token, for all four combinations`() {
        val run = runOpenedBy("api:rw-caller", "service")
        for ((d, h) in combinations) {
            assertEquals(d || h, RunPermissions.mayPauseOrResume(ctx(d, h)), "no token: director=$d human=$h")
            assertFalse(RunPermissions.mayPauseOrResume(ctx(d, h, coordinatorSeat(run))), "coordinator token: director=$d human=$h")
            assertFalse(RunPermissions.mayPauseOrResume(ctx(d, h, memberSeat(run))), "member token: director=$d human=$h")
        }
    }

    @Test
    fun `mayUpdate and mayAssign are the coordinator token only - neither the director, a human nor the opener may`() {
        val run = runOpenedBy("api:rw-caller", "service")
        for ((d, h) in combinations) {
            assertFalse(RunPermissions.mayUpdate(ctx(d, h), run), "no token: director=$d human=$h")
            assertFalse(RunPermissions.mayAssign(ctx(d, h), run), "no token: director=$d human=$h")
            assertFalse(RunPermissions.mayUpdate(ctx(d, h, memberSeat(run)), run), "member token: director=$d human=$h")
            assertFalse(RunPermissions.mayAssign(ctx(d, h, memberSeat(run)), run), "member token: director=$d human=$h")
            assertTrue(RunPermissions.mayUpdate(ctx(d, h, coordinatorSeat(run)), run), "coordinator token: director=$d human=$h")
            assertTrue(RunPermissions.mayAssign(ctx(d, h, coordinatorSeat(run)), run), "coordinator token: director=$d human=$h")
        }
        // a coordinator token of another run authorizes nothing here
        assertFalse(RunPermissions.mayUpdate(ctx(false, false, coordinatorSeat(runOpenedBy("api:rw-caller"))), run))
        assertFalse(RunPermissions.mayAssign(ctx(false, false, coordinatorSeat(runOpenedBy("api:rw-caller"))), run))
    }

    @Test
    fun `isMemberSeat is true for a member seat token only - not for no token and not for the coordinator token`() {
        val run = runOpenedBy("api:rw-coord")
        assertTrue(RunPermissions.isMemberSeat(rest("a").under(memberSeat(run))))
        assertTrue(RunPermissions.isMemberSeat(rest("a").under(memberSeat(run, "planner"))))
        assertFalse(RunPermissions.isMemberSeat(rest("a")), "no token")
        assertFalse(RunPermissions.isMemberSeat(rest("a").under(coordinatorSeat(run))), "the coordinator token is refused by end_seat")
    }

    @Test
    fun `the predicates for a lifted body actor and for the local principal follow the token, never the id the actor claims`() {
        val opener = runOpenedBy("orchestrator-1", "agent")
        assertFalse(RunPermissions.mayClose(lifted("orchestrator-1", ActorKind.SUBAGENT), opener), "the opener without a token")
        assertFalse(RunPermissions.mayClose(lifted("orchestrator-2", ActorKind.USER), opener), "declaring user does not make a human")
        assertFalse(RunPermissions.mayPauseOrResume(lifted("orchestrator-1", ActorKind.USER)), "declaring user does not make a human")
        assertTrue(RunPermissions.mayPauseOrResume(lifted("orchestrator-2", profile = "director")), "the director profile may")
        assertFalse(RunPermissions.mayUpdate(lifted("orchestrator-1"), opener), "the opener without a token may not update")
        assertTrue(
            RunPermissions.mayUpdate(lifted("orchestrator-1").under(coordinatorSeat(opener)), opener),
            "control: with the token it may"
        )
        assertTrue(
            RunPermissions.mayClose(lifted("orchestrator-1").under(coordinatorSeat(opener)), opener),
            "control: with the token it may"
        )

        val own = runOpenedBy("local", "agent")
        assertFalse(RunPermissions.mayClose(local(), own), "the local principal that opened the run, without a token")
        assertFalse(RunPermissions.mayUpdate(local(), own), "the local principal that opened the run, without a token")
        assertTrue(RunPermissions.mayClose(local().under(coordinatorSeat(own)), own), "control: with the token")
        assertTrue(RunPermissions.mayUpdate(local().under(coordinatorSeat(own)), own), "control: with the token")
        assertFalse(RunPermissions.mayPauseOrResume(local().under(coordinatorSeat(own))), "a token never pauses")
        assertTrue(RunPermissions.mayPauseOrResume(local(profile = "director")), "control: the director profile may pause")
    }
    // ------------------------------------------------------------------ refuse

    @Test
    fun `the required strings of the three rows are the listed kinds`() {
        assertEquals("coordinator,director,human", RunPermissions.CLOSE_REQUIRED)
        assertEquals("director,human", RunPermissions.PAUSE_REQUIRED)
        assertEquals("coordinator", RunPermissions.UPDATE_REQUIRED)
        assertEquals("coordinator", RunPermissions.ASSIGN_REQUIRED)
        assertEquals("seat", RunPermissions.END_SEAT_REQUIRED)
    }

    @Test
    fun `refuse is forbidden with the principal_kind scope and the given required string`() {
        for (required in listOf(
            RunPermissions.CLOSE_REQUIRED,
            RunPermissions.PAUSE_REQUIRED,
            RunPermissions.UPDATE_REQUIRED,
            RunPermissions.ASSIGN_REQUIRED,
            RunPermissions.END_SEAT_REQUIRED
        )) {
            val error = RunPermissions.refuse(required)
            assertEquals(ErrorCode.FORBIDDEN, error.code, required)
            val detail = assertIs<ErrorDetail.Forbidden>(error.detail, required)
            assertEquals(ForbiddenScope.PRINCIPAL_KIND, detail.scope, required)
            assertEquals(required, detail.required, required)
        }
    }
}
