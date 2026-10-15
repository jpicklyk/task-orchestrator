package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.telemetry.currentCallTelemetry
import io.github.jpicklyk.mcptask.current.domain.lifecycle.SeatCaller
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import java.util.UUID

/**
 * Who made a call: the actor [claim] and the [verification] result recorded for it, and, when a credential
 * established the caller, the credential [principal]. No trust policy is applied when it is built; handlers decide
 * what a given verification status allows.
 *
 * [principal] is the transport-established principal the claim was synthesized from (`api:<id>` for a credential,
 * `local` for the local principal; kind external); null only for a hand-built principal in tests.
 */
data class CallPrincipal(
    val claim: ActorClaim,
    val verification: VerificationResult,
    val principal: Principal? = null
)

/**
 * The seat a call is made under: the active assignment its valid `seatToken` names (R22). [runId] is the run,
 * [assignmentId] the assignment (the claim holder's second member), [itemId] the member item (null for the run-scoped
 * `coordinator` pseudo-seat) and [seat] the seat name.
 */
data class SeatContext(
    val runId: UUID,
    val assignmentId: UUID,
    val itemId: UUID?,
    val seat: String
)

/**
 * Per-call context handed to every 4.0 tool handler next to its decoded request.
 *
 * Request classes never declare `actor`: the transport establishes [principal] in one place, and a call reaches a
 * handler only with one (the dispatcher refuses a call without a principal as `unauthenticated`). [reqId] is the call's
 * correlation id, [surface] the transport (`mcp` or `rest`) and [scope] what the caller may see: handlers check
 * every item they touch with [ScopeGuard]. [attributionVisible] says whether the caller may see note attribution (a
 * note view's `actor` and `verification`); the transport decides it, so application code never inspects capabilities.
 * It defaults to false (hidden). Proof evidence is never emitted, whatever its value. [principalsVisible] says whether the
 * caller may see other principals' ids in an aggregate (the `report` `principal` grouping); the transport decides it too
 * (ADMIN only), and it defaults to false. [rootWritesAllowed] says whether the caller may create a depth-0 root or move an
 * item to the top level; the transport decides it (an unrestricted scope or ADMIN), and it defaults to whether [scope] is
 * unrestricted. [takeoverAllowed] says whether the caller may take over a note owned by another principal (`notes_write`
 * `takeover`); the transport decides it (ADMIN only) and it defaults to false. [profile] is the wire name of
 * the tool view that served the call (set by the dispatcher); [sessionId] is the caller's MCP session (null on REST and stdio).
 * [seat] is the seat of the call's valid seat token (null without one); under a token the dispatcher has already narrowed
 * the access (no ADMIN, so no principal visibility and no takeover).
 */
data class CallContext(
    val reqId: String,
    val surface: String,
    val principal: CallPrincipal,
    val scope: AccessScope = AccessScope.unrestricted(),
    val attributionVisible: Boolean = false,
    val principalsVisible: Boolean = false,
    val profile: String? = null,
    val sessionId: String? = null,
    val rootWritesAllowed: Boolean = scope.isUnrestricted,
    val takeoverAllowed: Boolean = false,
    val seat: SeatContext? = null
) {
    /**
     * The provenance tuple of this call, or null when its [CallPrincipal] carries no transport [Principal] (only a
     * hand-built test principal). The principal id is the audited actor id ([CallPrincipal.claim]); run and seat come from
     * the call's telemetry when one is installed.
     */
    suspend fun provenance(): AnswerProvenance? {
        val callPrincipal = principal
        val credential = callPrincipal.principal ?: return null
        val telemetry = currentCallTelemetry()
        return AnswerProvenance(
            principalId = callPrincipal.claim.id,
            kind = credential.kind,
            proofStatus = credential.proofStatus,
            host = credential.host,
            sessionId = sessionId,
            runId = telemetry?.runId,
            seat = telemetry?.seat,
            profile = profile
        )
    }
}

/**
 * Whether [ctx] is a human caller: a [PrincipalKind.HUMAN] credential carrying no seat token (a seat is always an
 * agent). The single human predicate: run permissions and seat enforcement (R23) both read it. A profile (the director
 * view) is not a kind, so it never makes a caller human.
 */
fun isHumanCaller(ctx: CallContext): Boolean = ctx.seat == null && ctx.principal.principal?.kind == PrincipalKind.HUMAN

/** The seat-enforcement caller of [ctx]: its seat token's seat, run and member item, and whether it is human. */
fun SeatCaller.Companion.of(ctx: CallContext): SeatCaller {
    val seat = ctx.seat
    return SeatCaller(seat = seat?.seat, runId = seat?.runId, itemId = seat?.itemId, human = isHumanCaller(ctx))
}
