package io.github.jpicklyk.mcptask.current.parity

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
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
        expectDbChanged: Boolean? = null
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
        seedTree
    )

    private val rootForbidden = listOf("\"code\":\"forbidden\"", "\"scope\":\"root\"", "\"required\":\"$r1\"")

    val all: List<ParityScenario> =
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
            )
        )
}
