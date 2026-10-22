package io.github.jpicklyk.mcptask.current.parity

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.domain.model.Role
import java.util.UUID

/**
 * The `advance` rows of [ProductionParity]: one HAPPY and one SCOPE_FORBIDDEN scenario for each of its four ops (transition,
 * claim, release, heartbeat), plus the OTHER scenarios that pin capability, atomic and holder behaviour on both surfaces.
 *
 * Oracles: envelope spec section 2 (a batch is 200 when every element is ok and 207 `partial_failure` otherwise; `atomic: true`
 * makes the first failing element the whole-call error with `error.index`) and section 3 (403 forbidden, 403 not_claim_holder),
 * task-scope TS1 (element shapes, `claimed`, `released`, `refreshed`), D9 (the claim scope scenario runs in id mode) and Q6 D4
 * (a scope scenario runs under a token whose scope restricts something). The seeded tree comes from [ParitySeeder.item], which
 * can only create queue items; release and heartbeat therefore get their holder state from a claim earlier in the SAME call.
 */
internal object AdvanceParityScenarios {
    val r1: UUID = UUID.fromString("a0000001-0000-4000-8000-000000000001")
    val c1: UUID = UUID.fromString("a0000002-0000-4000-8000-000000000002")
    val r2: UUID = UUID.fromString("a0000003-0000-4000-8000-000000000003")

    val agent = ParityToken("adv-agent", setOf(ApiCapability.READ, ApiCapability.ADVANCE))
    val reader = ParityToken("adv-reader", setOf(ApiCapability.READ))
    val rootScoped = ParityToken("adv-root-scoped", setOf(ApiCapability.READ, ApiCapability.ADVANCE), rootIds = setOf(r1))

    /** R1 "Advance root" > C1 "Advance child"; R2 "Other root" (outside the root-scoped token's scope). */
    val seedTree: suspend ParitySeeder.() -> Unit = {
        item(r1, "Advance root", tags = "alpha")
        item(c1, "Advance child", parent = r1, tags = "alpha")
        item(r2, "Other root", tags = "beta")
    }

    private fun transition(id: UUID) = """{"op":"transition","itemId":"$id","trigger":"start"}"""

    private fun claim(id: UUID) = """{"op":"claim","itemId":"$id"}"""

    private fun release(id: UUID) = """{"op":"release","itemId":"$id"}"""

    private fun heartbeat(
        id: UUID,
        ttl: Int? = null
    ) = """{"op":"heartbeat","itemId":"$id"${if (ttl != null) ""","ttlSeconds":$ttl""" else ""}}"""

    private fun heartbeatWithUsage(id: UUID) = """{"op":"heartbeat","itemId":"$id","usage":{"tokens":10,"costUsd":0.5}}"""

    /** A run of R1 over C1 whose opener is the `adv-agent` principal, plus an `implementer` seat on C1 held by that principal. */
    private val seatRun: UUID = UUID.fromString("a0000004-0000-4000-8000-000000000004")

    /** The known token of that seat (43 characters of the base64url alphabet). */
    private val seatToken = "h".repeat(43)

    private val seedSeat: suspend ParitySeeder.() -> Unit = {
        seedTree()
        run(seatRun, r1, listOf(c1), openedBy = "api:adv-agent", openedByKind = "agent")
        seat(seatRun, c1, "implementer", principalId = "api:adv-agent", token = seatToken)
    }

    private fun ops(
        vararg elements: String,
        top: String = ""
    ) = """{"ops":[${elements.joinToString(",")}]$top}"""

    private fun scenario(
        name: String,
        member: String,
        kind: ParityKind,
        token: ParityToken,
        arguments: String,
        expectStatus: Int,
        expectCode: String? = null,
        expectContains: List<String> = emptyList(),
        expectAbsent: List<String> = emptyList(),
        expectDbChanged: Boolean? = null,
        seed: suspend ParitySeeder.() -> Unit = seedTree
    ) = ParityScenario(
        name,
        "advance",
        member,
        kind,
        token,
        arguments,
        expectStatus,
        expectCode,
        expectContains,
        expectAbsent,
        expectDbChanged,
        seed
    )

    private val rootForbidden = listOf("\"code\":\"forbidden\"", "\"scope\":\"root\"", "\"required\":\"$r1\"")

    private val core: List<ParityScenario> =
        listOf(
            scenario(
                "advance transition start happy",
                "transition",
                ParityKind.HAPPY,
                agent,
                ops(transition(r1)),
                200,
                expectContains =
                    listOf(
                        "\"itemId\":\"$r1\"",
                        "\"previousRole\":\"queue\"",
                        "\"newRole\":\"work\"",
                        "\"trigger\":\"start\""
                    ),
                expectDbChanged = true
            ),
            scenario(
                "advance transition scope root",
                "transition",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                ops(transition(r2)),
                207,
                expectCode = "partial_failure",
                expectContains = rootForbidden,
                expectAbsent = listOf("\"newRole\""),
                expectDbChanged = false
            ),
            scenario(
                "advance claim by id happy",
                "claim",
                ParityKind.HAPPY,
                agent,
                ops(claim(r1)),
                200,
                expectContains = listOf("\"itemId\":\"$r1\"", "\"claimed\":true"),
                expectDbChanged = true
            ),
            scenario(
                "advance claim scope root",
                "claim",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                ops(claim(r2)),
                207,
                expectCode = "partial_failure",
                expectContains = rootForbidden,
                expectAbsent = listOf("\"claimed\":true"),
                expectDbChanged = false
            ),
            scenario(
                "advance release happy after a claim in the same call",
                "release",
                ParityKind.HAPPY,
                agent,
                ops(claim(r1), release(r1)),
                200,
                expectContains = listOf("\"claimed\":true", "\"released\":true")
            ),
            scenario(
                "advance release scope root",
                "release",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                ops(release(r2)),
                207,
                expectCode = "partial_failure",
                expectContains = rootForbidden,
                expectAbsent = listOf("\"released\""),
                expectDbChanged = false
            ),
            scenario(
                "advance heartbeat happy after a claim in the same call",
                "heartbeat",
                ParityKind.HAPPY,
                agent,
                ops(claim(r1), heartbeat(r1, ttl = 1800)),
                200,
                expectContains = listOf("\"refreshed\":true"),
                expectDbChanged = true
            ),
            scenario(
                "advance heartbeat scope root",
                "heartbeat",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                ops(heartbeat(r2)),
                207,
                expectCode = "partial_failure",
                expectContains = rootForbidden,
                expectAbsent = listOf("\"refreshed\""),
                expectDbChanged = false
            ),
            scenario(
                "advance every op is capability forbidden for a read-only token",
                "transition",
                ParityKind.OTHER,
                reader,
                ops(transition(r1), claim(r1), release(r1), heartbeat(r1)),
                207,
                expectCode = "partial_failure",
                expectContains = listOf("\"scope\":\"capability\"", "\"required\":\"advance\""),
                expectAbsent = listOf("\"claimed\":true", "\"newRole\""),
                expectDbChanged = false
            ),
            scenario(
                "advance atomic claim batch rolls the in-scope claim back when the next element is out of scope",
                "claim",
                ParityKind.OTHER,
                rootScoped,
                ops(claim(r1), claim(r2), top = ""","atomic":true"""),
                403,
                expectCode = "forbidden",
                expectContains = listOf("\"index\":1", "\"scope\":\"root\""),
                expectDbChanged = false
            ),
            scenario(
                "advance heartbeat on an unclaimed item is not_claim_holder and never takes it over",
                "heartbeat",
                ParityKind.OTHER,
                agent,
                ops(heartbeat(r1)),
                207,
                expectCode = "partial_failure",
                expectContains = listOf("\"code\":\"not_claim_holder\"", "\"itemId\":\"$r1\""),
                expectAbsent = listOf("\"refreshed\""),
                expectDbChanged = false
            ),
            // R22 (decision D9): `usage` is a seat's spend, so a call with no seat token cannot carry it. Atomic, so the failing
            // element is the whole-call error with its index and the claim before it is rolled back.
            scenario(
                "advance heartbeat usage without a seat token is invalid_request on the usage pointer",
                "heartbeat",
                ParityKind.OTHER,
                agent,
                ops(claim(r1), heartbeatWithUsage(r1), top = ""","atomic":true"""),
                400,
                expectCode = "invalid_request",
                expectContains = listOf("\"index\":1", "/usage", "requires a seat token"),
                expectAbsent = listOf("\"refreshed\""),
                expectDbChanged = false
            ),
            // R22 (decision D9), the HAPPY half the round 1 harness could not seed: under a live seat token the seat's spend is
            // accepted. The claim and the heartbeat are taken under the assignment (the holder is the pair, D10), so the heartbeat
            // refreshes the very claim the same call made; the seat row keeps the reported usage (compared across the legs through
            // the run_seats snapshot).
            scenario(
                "advance heartbeat usage under a seat token is accepted for the seat's own claim",
                "heartbeat",
                ParityKind.OTHER,
                agent,
                ops(claim(c1), heartbeatWithUsage(c1), top = ""","seatToken":"$seatToken""""),
                200,
                expectContains = listOf("\"claimed\":true", "\"refreshed\":true"),
                expectAbsent = listOf(seatToken, "requires a seat token"),
                expectDbChanged = true,
                seed = seedSeat
            ),
            // R22 (decision D1/D2): a seat token that no live assignment issued is the fixed 401 before any op is read.
            scenario(
                "advance with an unknown seat token is unauthenticated and claims nothing",
                "claim",
                ParityKind.OTHER,
                agent,
                ops(claim(r1), top = ""","seatToken":"${"u".repeat(43)}""""),
                401,
                expectCode = "unauthenticated",
                expectContains = listOf("The seat token is not accepted."),
                expectAbsent = listOf("\"claimed\":true", "u".repeat(43)),
                expectDbChanged = false
            )
        )

    // R23 (item 9536cf09): seat enforcement inside an open run, on both transports. The tree is R1 > C1 (builder seat) and C2
    // (checker seat), both members of an open run of R1 whose per-root config declares a seat-aware `default`
    // schema (the seeder stamps no item type, so every untyped item under R1 resolves it). Oracles: the plan 4.1 R23 table and the frozen task-scope (a seat token acts only on its own item; `start`
    // only into the phase the seat enters; other triggers only when declared, default block and hold; the coordinator token any
    // trigger the table allows; no token is `seat_forbidden{itemId, action, allowedSeats}` with `seat` omitted (Ruling 3); a human credential is
    // exempt; a refusal applies nothing). Both legs must answer the same bytes and leave the same database.
    private val enforceRun: UUID = UUID.fromString("a0000005-0000-4000-8000-000000000005")
    private val c2: UUID = UUID.fromString("a0000006-0000-4000-8000-000000000006")
    private val builderToken = "b".repeat(43)
    private val checkerToken = "k".repeat(43)

    private const val SEAT_ENFORCE_YAML = """
work_item_schemas:
  default:
    seats:
      - { name: builder, phase: work, enters: true }
      - { name: checker, phase: work, transitions: [complete] }
    notes:
      - key: build-notes
        role: work
        required: false
        seat: builder
"""

    private val seedEnforcement: suspend ParitySeeder.() -> Unit = {
        item(r1, "Advance root", tags = "alpha")
        item(c1, "Advance child", parent = r1, tags = "seat-task")
        item(c2, "Second child", parent = r1, tags = "seat-task")
        item(r2, "Other root", tags = "beta")
        config(r1, SEAT_ENFORCE_YAML)
        run(enforceRun, r1, listOf(c1, c2), openedBy = "api:adv-agent", openedByKind = "agent")
        seat(enforceRun, c1, "builder", principalId = "api:adv-agent", token = builderToken)
        seat(enforceRun, c2, "checker", principalId = "api:adv-agent", token = checkerToken)
    }

    private fun transitionOf(
        id: UUID,
        trigger: String
    ) = """{"op":"transition","itemId":"$id","trigger":"$trigger"}"""

    private fun withToken(token: String?) = if (token != null) ""","seatToken":"$token"""" else ""

    private val humanAgent = ParityToken("adv-human", setOf(ApiCapability.READ, ApiCapability.ADVANCE), kind = PrincipalKind.HUMAN)

    private fun enforcement(
        name: String,
        token: ParityToken,
        element: String,
        status: Int,
        seatToken: String? = null,
        code: String? = null,
        contains: List<String> = emptyList(),
        absent: List<String> = emptyList(),
        changed: Boolean,
        top: String = ""
    ) = scenario(
        name,
        "transition",
        ParityKind.OTHER,
        token,
        ops(element, top = withToken(seatToken) + top),
        status,
        expectCode = code,
        expectContains = contains,
        expectAbsent = absent,
        expectDbChanged = changed,
        seed = seedEnforcement
    )

    private val seatEnforcement: List<ParityScenario> =
        listOf(
            enforcement(
                "advance transition R23 the builder token starts its own item into work",
                agent,
                transitionOf(c1, "start"),
                200,
                seatToken = builderToken,
                contains = listOf("\"itemId\":\"$c1\"", "\"newRole\":\"work\""),
                absent = listOf(builderToken),
                changed = true
            ),
            enforcement(
                "advance transition R23 the builder token is refused on another member of the run",
                agent,
                transitionOf(c2, "start"),
                207,
                seatToken = builderToken,
                code = "partial_failure",
                contains =
                    listOf(
                        "\"code\":\"seat_forbidden\"",
                        "\"itemId\":\"$c2\"",
                        "\"seat\":\"builder\"",
                        "\"action\":\"start\"",
                        "\"allowedSeats\":[\"coordinator\",\"builder\"]"
                    ),
                absent = listOf("\"newRole\"", builderToken),
                changed = false
            ),
            enforcement(
                "advance transition R23 the builder token never completes",
                agent,
                transitionOf(c1, "complete"),
                207,
                seatToken = builderToken,
                code = "partial_failure",
                contains =
                    listOf(
                        "\"code\":\"seat_forbidden\"",
                        "\"action\":\"complete\"",
                        "\"allowedSeats\":[\"coordinator\",\"checker\"]"
                    ),
                absent = listOf("\"newRole\""),
                changed = false
            ),
            enforcement(
                "advance transition R23 the checker token completes through its declared transition",
                agent,
                transitionOf(c2, "complete"),
                200,
                seatToken = checkerToken,
                contains = listOf("\"itemId\":\"$c2\"", "\"newRole\":\"terminal\""),
                changed = true
            ),
            enforcement(
                "advance transition R23 the coordinator token starts a member",
                agent,
                transitionOf(c2, "start"),
                200,
                seatToken = parityCoordinatorToken(enforceRun),
                contains = listOf("\"itemId\":\"$c2\"", "\"newRole\":\"work\""),
                changed = true
            ),
            enforcement(
                "advance transition R23 a token-less agent is refused with the seat omitted",
                agent,
                transitionOf(c1, "start"),
                207,
                code = "partial_failure",
                contains =
                    listOf(
                        "\"code\":\"seat_forbidden\"",
                        "\"itemId\":\"$c1\"",
                        "\"action\":\"start\"",
                        "\"allowedSeats\":[\"coordinator\",\"builder\"]"
                    ),
                // Ruling 3: the seat member is OMITTED without a token, never JSON null (the builder-token refusal carries it)
                absent = listOf("\"newRole\"", "\"seat\":"),
                changed = false
            ),
            enforcement(
                "advance transition R23 a token-less agent in an atomic batch is the whole-call seat_forbidden",
                agent,
                transitionOf(c1, "start"),
                403,
                code = "seat_forbidden",
                contains = listOf("\"index\":0", "\"action\":\"start\""),
                absent = listOf("\"seat\":"),
                changed = false,
                top = ""","atomic":true"""
            ),
            enforcement(
                "advance transition R23 a human credential with no token is exempt",
                humanAgent,
                transitionOf(c1, "start"),
                200,
                contains = listOf("\"itemId\":\"$c1\"", "\"newRole\":\"work\""),
                changed = true
            )
        )

    // 7cb8a3df: a terminal cascade never moves a REVIEW parent. Oracles: proposal 4b2ac588 (a parent leaves REVIEW only by an
    // explicit advance), the frozen task-scope D1-D6 and the api-reference Cascades bullets (terminal, Not reported, Suppressed).
    // Each scenario runs over MCP and REST and must answer the same bytes and leave the same database. The tree has no schema
    // unless a config is seeded, and a schema-free item is AUTO. A suppressed cascade reads
    // `{itemId, previousRole: review, targetRole: terminal, applied: false, blocked: "gate", reason: "review-pending"}` with no
    // `missing`. Every scenario here is OTHER, so the per-tool UNAUTHENTICATED row stays derived from the HAPPY scenario above.
    private val rp: UUID = UUID.fromString("a0000010-0000-4000-8000-000000000010")
    private val rc1: UUID = UUID.fromString("a0000011-0000-4000-8000-000000000011")
    private val rc2: UUID = UUID.fromString("a0000012-0000-4000-8000-000000000012")
    private val rgp: UUID = UUID.fromString("a0000013-0000-4000-8000-000000000013")

    /** Every item under the root requires a review-phase note nothing fills; only a cancel (which skips notes) can finish a child. */
    private const val GATED_PARENT_YAML = """
work_item_schemas:
  default:
    notes:
      - key: review-notes
        role: review
        required: true
"""

    /** The parent's only note is optional, so no required note is outstanding at the cascade. */
    private const val NOTED_PARENT_YAML = """
work_item_schemas:
  default:
    notes:
      - key: review-notes
        role: review
        required: false
"""

    private const val MANUAL_PARENT_YAML = """
work_item_schemas:
  default:
    lifecycle: manual
    notes:
      - key: review-notes
        role: review
        required: false
"""

    private val seedReviewParent: suspend ParitySeeder.() -> Unit = {
        item(rp, "Review parent", role = Role.REVIEW)
        item(rc1, "Work child", parent = rp, role = Role.WORK)
    }

    private val seedReviewParentTwoChildren: suspend ParitySeeder.() -> Unit = {
        item(rp, "Review parent", role = Role.REVIEW)
        item(rc1, "Work child one", parent = rp, role = Role.WORK)
        item(rc2, "Work child two", parent = rp, role = Role.WORK)
    }

    private val seedReviewParentTerminalChild: suspend ParitySeeder.() -> Unit = {
        item(rp, "Review parent", role = Role.REVIEW)
        item(rc1, "Done child", parent = rp, role = Role.TERMINAL)
    }

    private val seedWorkParent: suspend ParitySeeder.() -> Unit = {
        item(rp, "Work parent", role = Role.WORK)
        item(rc1, "Work child", parent = rp, role = Role.WORK)
    }

    private val seedReviewParentUnderWorkGrandparent: suspend ParitySeeder.() -> Unit = {
        item(rgp, "Work grandparent", role = Role.WORK)
        item(rp, "Review parent", parent = rgp, role = Role.REVIEW)
        item(rc1, "Work child", parent = rp, role = Role.WORK)
    }

    /**
     * A parent in [parentRole] over one work child, with [yaml] pushed as the root's config. The seeder stamps no item type, so only
     * the `default` schema applies, to the parent and the child alike.
     */
    private fun seedConfiguredParent(
        yaml: String,
        parentRole: Role
    ): suspend ParitySeeder.() -> Unit =
        {
            item(rp, "Configured parent", role = parentRole)
            item(rc1, "Work child", parent = rp, role = Role.WORK)
            config(rp, yaml)
        }

    private fun completeOp(id: UUID) = transitionOf(id, "complete")

    /** The suppressed-cascade entry for the parent [rp]; each member is checked on its own so the member order is not pinned. */
    private val reviewPendingEntry =
        listOf(
            "\"itemId\":\"$rp\"",
            "\"previousRole\":\"review\"",
            "\"targetRole\":\"terminal\"",
            "\"applied\":false",
            "\"blocked\":\"gate\"",
            "\"reason\":\"review-pending\""
        )

    private fun reviewCascade(
        name: String,
        arguments: String,
        seed: suspend ParitySeeder.() -> Unit,
        contains: List<String>,
        absent: List<String>,
        changed: Boolean = true
    ) = scenario(
        name,
        "transition",
        ParityKind.OTHER,
        agent,
        arguments,
        200,
        expectContains = contains,
        expectAbsent = absent,
        expectDbChanged = changed,
        seed = seed
    )

    private val reviewCascades: List<ParityScenario> =
        listOf(
            // S1: the last child completes under a REVIEW parent; the parent stays in review and the child's result reports why.
            reviewCascade(
                "advance transition 7cb8a3df S1 the last child completing leaves a REVIEW parent in review with a review-pending entry",
                ops(completeOp(rc1)),
                seedReviewParent,
                contains = reviewPendingEntry + listOf("\"itemId\":\"$rc1\"", "\"newRole\":\"terminal\""),
                absent = listOf("\"applied\":true", "\"missing\"", "\"blockers\"", "\"error\"")
            ),
            // S2 (variant): no required note is outstanding on the parent (its only note is optional), so the note gate would pass;
            // the suppression is the same. ParitySeeder seeds no notes, so a filled required note cannot be fixtured here.
            reviewCascade(
                "advance transition 7cb8a3df S2 a REVIEW parent with no required note outstanding is suppressed the same way",
                ops(completeOp(rc1)),
                seedConfiguredParent(NOTED_PARENT_YAML, Role.REVIEW),
                contains = reviewPendingEntry,
                absent = listOf("\"applied\":true", "\"missing\"")
            ),
            // S3: a WORK parent still cascades to terminal and its entry carries neither blocked nor reason.
            reviewCascade(
                "advance transition 7cb8a3df S3 a WORK parent still cascades to terminal with no blocked or reason member",
                ops(completeOp(rc1)),
                seedWorkParent,
                contains =
                    listOf(
                        "\"itemId\":\"$rp\"",
                        "\"previousRole\":\"work\"",
                        "\"targetRole\":\"terminal\"",
                        "\"applied\":true"
                    ),
                absent = listOf("\"blocked\"", "\"reason\"", "review-pending", "\"missing\"")
            ),
            // S4: an explicit complete of the REVIEW parent (its only child is already terminal) moves it to terminal.
            reviewCascade(
                "advance transition 7cb8a3df S4 an explicit complete moves a REVIEW parent to terminal",
                ops(completeOp(rp)),
                seedReviewParentTerminalChild,
                contains = listOf("\"itemId\":\"$rp\"", "\"previousRole\":\"review\"", "\"newRole\":\"terminal\""),
                absent = listOf("review-pending", "\"cascades\"", "\"blocked\"")
            ),
            // S5 / D4: a cancel-origin chain is suppressed at a REVIEW parent too.
            reviewCascade(
                "advance transition 7cb8a3df S5 a child cancel under a REVIEW parent is suppressed the same way",
                ops(transitionOf(rc1, "cancel")),
                seedReviewParent,
                contains = reviewPendingEntry + listOf("\"trigger\":\"cancel\""),
                absent = listOf("\"applied\":true", "\"missing\"")
            ),
            // S10 + probe (completion order): with two work children both elements report ok with their own itemId and the review-pending entry is reported (neighbouring fields of an element
            // are not pinned).
            reviewCascade(
                "advance transition 7cb8a3df S10 the first of two child completions reports no cascade and the second reports review-pending",
                ops(completeOp(rc1), completeOp(rc2)),
                seedReviewParentTwoChildren,
                contains =
                    reviewPendingEntry +
                        listOf(
                            "\"ok\":true,\"itemId\":\"$rc1\"",
                            // the first element ends right before the second: a string-valued tail, never a cascades array
                            "\"},{\"ok\":true,\"itemId\":\"$rc2\""
                        ),
                absent = listOf("\"applied\":true", "\"missing\"", "]},{\"ok\":true,\"itemId\":\"$rc2\"")
            ),
            reviewCascade(
                "advance transition 7cb8a3df S10 probe the permuted completion order reports the same suppression on the other child",
                ops(completeOp(rc2), completeOp(rc1)),
                seedReviewParentTwoChildren,
                contains =
                    reviewPendingEntry +
                        listOf(
                            "\"ok\":true,\"itemId\":\"$rc2\"",
                            // the first element ends right before the second: a string-valued tail, never a cascades array
                            "\"},{\"ok\":true,\"itemId\":\"$rc1\""
                        ),
                absent = listOf("\"applied\":true", "\"missing\"", "]},{\"ok\":true,\"itemId\":\"$rc1\"")
            ),
            // S11: only the REVIEW parent is reported; the WORK grandparent above it is neither reported nor moved (an applied
            // cascade is what climbs).
            reviewCascade(
                "advance transition 7cb8a3df S11 the chain stops at the REVIEW parent and never reaches the grandparent",
                ops(completeOp(rc1)),
                seedReviewParentUnderWorkGrandparent,
                contains = reviewPendingEntry,
                absent = listOf("\"itemId\":\"$rgp\"", "\"applied\":true")
            ),
            // S12: a manual-lifecycle parent in review is left alone and nothing is reported.
            reviewCascade(
                "advance transition 7cb8a3df S12 a manual REVIEW parent produces no cascade entry",
                ops(completeOp(rc1)),
                seedConfiguredParent(MANUAL_PARENT_YAML, Role.REVIEW),
                contains = listOf("\"itemId\":\"$rc1\"", "\"newRole\":\"terminal\""),
                absent = listOf("\"cascades\"", "review-pending", "\"applied\"")
            ),
            // Probe (replay): complete, reopen, complete the same child in one call. Both completions are suppressed, the reopen
            // element ends right after its trigger (a REVIEW parent is not a terminal parent, so a reopen cascade reports nothing).
            reviewCascade(
                "advance transition 7cb8a3df probe replay complete then reopen then complete is suppressed again",
                ops(completeOp(rc1), transitionOf(rc1, "reopen"), completeOp(rc1)),
                seedReviewParent,
                contains =
                    reviewPendingEntry +
                        listOf(
                            // first completion suppressed, reopen reports nothing, second completion suppressed again (two entries in all)
                            "\"reason\":\"review-pending\"}]},{\"ok\":true,\"itemId\":\"$rc1\"",
                            "\"trigger\":\"reopen\"},{\"ok\":true,\"itemId\":\"$rc1\"",
                            "\"reason\":\"review-pending\"}]}]"
                        ),
                absent = listOf("\"applied\":true", "\"missing\"")
            ),
            // S15 (author addition, D4): the cancel-origin note-gate skip is unchanged for a parent that is not in review.
            reviewCascade(
                "advance transition 7cb8a3df S15 a child cancel under a WORK parent with unfilled notes still cascades",
                ops(transitionOf(rc1, "cancel")),
                seedConfiguredParent(GATED_PARENT_YAML, Role.WORK),
                contains =
                    listOf(
                        "\"itemId\":\"$rp\"",
                        "\"previousRole\":\"work\"",
                        "\"targetRole\":\"terminal\"",
                        "\"applied\":true"
                    ),
                absent = listOf("\"blocked\"", "\"reason\"", "\"missing\"", "review-pending")
            )
        )

    val all: List<ParityScenario> = core + seatEnforcement + reviewCascades
}
