package io.github.jpicklyk.mcptask.current.parity

/**
 * The per-tool UNAUTHENTICATED rows of [ProductionParity] (item 8ae4c8e7, R14; scenario S11 of the frozen test-plan): for every
 * registered tool, one call sent over REST with no Authorization header and over MCP with an access that carries no principal.
 * Both legs must answer 401 `unauthenticated` and leave the database unchanged ([ParityHarness.run] asserts that and pairs it with
 * a control call that carries the scenario's token).
 *
 * The rows are derived, not hand-written, so a new tool cannot be added without its row: each tool's first HAPPY scenario (a
 * well-formed call that would be served, and for a write would change the database, which is what makes the "wrote nothing"
 * assertion able to fail) is re-labelled with the literal oracle of this item: status 401, code `unauthenticated`, database
 * unchanged. The oracle is the item's plan (`ApiBearerAuth` refuses a missing credential; the dispatcher refuses an access with
 * no principal before decode), not anything a run printed. The member stays the HAPPY scenario's own, so the existing per-member
 * catalogs and the registered-member staleness check are unaffected.
 */
internal object UnauthenticatedParityScenarios {
    /** The catalog status the envelope spec assigns to `unauthenticated`. */
    const val STATUS = 401

    const val CODE = "unauthenticated"

    /** One UNAUTHENTICATED scenario per tool that [base] has a successful HAPPY scenario for, in first-appearance order. */
    fun derive(base: List<ParityScenario>): List<ParityScenario> =
        base
            .filter { it.kind == ParityKind.HAPPY && it.expectStatus == 200 }
            .distinctBy { it.tool }
            .map { happy ->
                happy.copy(
                    name = "${happy.tool} unauthenticated",
                    kind = ParityKind.UNAUTHENTICATED,
                    expectStatus = STATUS,
                    expectCode = CODE,
                    expectContains = emptyList(),
                    expectAbsent = emptyList(),
                    expectDbChanged = false
                )
            }
}
