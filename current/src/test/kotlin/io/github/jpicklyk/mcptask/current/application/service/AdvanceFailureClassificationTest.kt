package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.testClaimService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Independently authored for item `0c07190d` (error catalog adoption), scenario S7 of the frozen `test-plan`:
 * every [AdvanceFailure] answers `error` as a catalog [io.github.jpicklyk.mcptask.current.domain.error.DomainError]
 * with the code the task-scope assigns to its cause. The type of `AdvanceFailure.error` is non-null by declaration,
 * so the value of the code is what these tests pin; each case is a distinct failure cause.
 *
 * EXISTING-SURFACE in intent (the service, its triggers and its failure causes predate the item; only the `error`
 * contract on the sealed type changes) but the assertions read `AdvanceFailure.error`, a declaration the item changes
 * from nullable to non-null, so a plain revert does not compile. Substitute verification (named here, per the rule's
 * section 2): run these tests in a scratch copy with the item's service edits reverted but `AdvanceFailure.error`
 * kept as `DomainError?` and every `error` initialiser returning null - the code assertions then fail for the causes
 * the task-scope lists as previously null (unknown trigger, policy rejection, not-applicable transition).
 *
 * Oracles: [T] task-scope Build step 1 (unknown trigger -> INVALID_REQUEST, PolicyRejected -> UNAUTHENTICATED,
 * NotApplicable -> INVALID_TRANSITION) and the legacy wire-code to catalog assignments (`gate_blocked` ->
 * GATE_BLOCKED, `dependency_blocked` -> DEPENDENCY_UNMET, `not_claim_holder` -> NOT_CLAIM_HOLDER); [M] the
 * `advance_item` error-code list in api-reference at the base commit, which fixes which cause carries which wire code.
 */
class AdvanceFailureClassificationTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val provider get() = db.repositoryProvider()
    private val items get() = provider.workItemRepository()

    private fun service(schema: WorkItemSchema? = null): AdvanceService =
        AdvanceService(
            workItemRepository = provider.workItemRepository(),
            roleTransitionRepository = provider.roleTransitionRepository(),
            dependencyRepository = provider.dependencyRepository(),
            noteRepository = provider.noteRepository(),
            schemaResolver = { schema },
            unitOfWork = db.unitOfWork(),
            claimService = testClaimService(provider.workItemRepository(), null, db.unitOfWork())
        )

    private suspend fun item(
        title: String,
        role: Role = Role.QUEUE
    ): WorkItem = items.create(WorkItem(title = title, role = role))

    private suspend fun AdvanceService.failureOf(
        subject: WorkItem,
        trigger: String,
        actor: ActorClaim? = null,
        verification: VerificationResult? = null,
        policy: DegradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
        enforceOwnership: Boolean = false
    ): AdvanceFailure {
        val outcome = advance(subject, trigger, null, actor, verification, policy, enforceOwnership)
        return assertIs<AdvanceOutcome.Failure>(outcome, "expected a failure for '$trigger' on ${subject.role}, got $outcome").failure
    }

    @Test
    fun `S7 an unknown trigger fails with INVALID_REQUEST`(): Unit =
        runBlocking {
            val failure = service().failureOf(item("bogus trigger"), "bogus")

            assertEquals(ErrorCode.INVALID_REQUEST, failure.error.code)
        }

    @Test
    fun `S7 the cascade trigger is not a user trigger and fails with INVALID_REQUEST`(): Unit =
        runBlocking {
            val failure = service().failureOf(item("cascade attempt"), "cascade")

            assertEquals(ErrorCode.INVALID_REQUEST, failure.error.code)
        }

    @Test
    fun `S7 a degraded-policy rejection fails with UNAUTHENTICATED`(): Unit =
        runBlocking {
            val actor = ActorClaim(id = "unverifiable", kind = ActorKind.SUBAGENT)
            val unavailable = VerificationResult(status = VerificationStatus.UNAVAILABLE, verifier = "jwks", reason = "JWKS endpoint down")

            val failure =
                service().failureOf(
                    item("policy rejected"),
                    "start",
                    actor = actor,
                    verification = unavailable,
                    policy = DegradedModePolicy.REJECT,
                    enforceOwnership = true
                )

            assertEquals(ErrorCode.UNAUTHENTICATED, failure.error.code)
        }

    @Test
    fun `S7 transitions that do not apply to the current role fail with INVALID_TRANSITION`(): Unit =
        runBlocking {
            val cases =
                listOf(
                    Role.TERMINAL to "start",
                    Role.TERMINAL to "complete",
                    Role.TERMINAL to "block",
                    Role.TERMINAL to "cancel",
                    Role.QUEUE to "resume",
                    Role.QUEUE to "reopen"
                )
            for ((role, trigger) in cases) {
                val failure = service().failureOf(item("$trigger from $role", role), trigger)

                assertEquals(ErrorCode.INVALID_TRANSITION, failure.error.code, "$trigger from $role")
            }
        }

    @Test
    fun `S7 a missing required note fails with GATE_BLOCKED`(): Unit =
        runBlocking {
            val gated =
                WorkItemSchema(type = "gated", notes = listOf(NoteSchemaEntry("spec", Role.QUEUE, required = true, description = "spec")))

            val failure = service(gated).failureOf(item("gated"), "start")

            assertEquals(ErrorCode.GATE_BLOCKED, failure.error.code)
        }

    @Test
    fun `S7 an unmet blocking dependency fails with DEPENDENCY_UNMET`(): Unit =
        runBlocking {
            val blocker = item("blocker")
            val blocked = item("blocked")
            provider.dependencyRepository().create(Dependency(fromItemId = blocker.id, toItemId = blocked.id, type = DependencyType.BLOCKS))

            val failure = service().failureOf(blocked, "start")

            assertEquals(ErrorCode.DEPENDENCY_UNMET, failure.error.code)
        }

    @Test
    fun `S7 advancing an item claimed by another agent with ownership enforced fails with NOT_CLAIM_HOLDER`(): Unit =
        runBlocking {
            val held = item("held")
            items.claim(held.id, "other-agent", 900)
            val reread = items.getById(held.id)!!
            val me = ActorClaim(id = "me", kind = ActorKind.SUBAGENT)
            val unchecked = VerificationResult(status = VerificationStatus.UNCHECKED, verifier = "noop")

            val failure = service().failureOf(reread, "start", actor = me, verification = unchecked, enforceOwnership = true)

            assertEquals(ErrorCode.NOT_CLAIM_HOLDER, failure.error.code)
        }

    @Test
    fun `S7 probe the same ownership conflict with ownership not enforced is not a failure`(): Unit =
        runBlocking {
            val held = item("held but not enforced")
            items.claim(held.id, "other-agent", 900)
            val reread = items.getById(held.id)!!
            val rest = ActorClaim(id = "api:token", kind = ActorKind.EXTERNAL)
            val unchecked = VerificationResult(status = VerificationStatus.UNCHECKED, verifier = "noop")

            val outcome = service().advance(reread, "start", null, rest, unchecked, DegradedModePolicy.ACCEPT_CACHED, false)

            assertIs<AdvanceOutcome.Success>(outcome)
        }

    @Test
    fun `S7 probe every failure cause above is classified by a distinct non-null error with a non-blank message`(): Unit =
        runBlocking {
            val failures =
                listOf(
                    service().failureOf(item("a"), "bogus"),
                    service().failureOf(item("b", Role.TERMINAL), "start"),
                    service().failureOf(
                        item("c"),
                        "start",
                        actor = ActorClaim(id = "x", kind = ActorKind.SUBAGENT),
                        verification = VerificationResult(status = VerificationStatus.ABSENT, verifier = "jwks"),
                        policy = DegradedModePolicy.REJECT,
                        enforceOwnership = true
                    )
                )

            assertEquals(
                listOf(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_TRANSITION, ErrorCode.UNAUTHENTICATED),
                failures.map { it.error.code }
            )
            failures.forEach { assertEquals(false, it.error.message.isBlank(), "message must not be blank: ${it.error}") }
        }
}
