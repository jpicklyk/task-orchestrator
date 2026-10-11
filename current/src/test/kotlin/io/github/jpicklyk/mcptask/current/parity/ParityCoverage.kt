package io.github.jpicklyk.mcptask.current.parity

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.toAccessScope
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import java.util.UUID

/**
 * What a parity scenario is for (task-scope D4). Only [HAPPY] and [SCOPE_FORBIDDEN] count toward coverage; [OTHER] scenarios
 * (not-found, capability-forbidden, atomic, mixed batches) run through the same harness but satisfy neither requirement.
 */
enum class ParityKind { HAPPY, SCOPE_FORBIDDEN, OTHER }

/**
 * A bearer token identity for a scenario. The REST leg authenticates with [raw]; the MCP leg is handed the same principal,
 * scope and capabilities through the adapter's access seam, so both legs run under one identity (D3).
 */
data class ParityToken(
    val id: String,
    val capabilities: Set<ApiCapability>,
    val rootIds: Set<UUID>? = null,
    val tagsInclude: Set<String> = emptySet()
) {
    val raw: String get() = "raw-parity-token-$id"

    fun principal(): ApiPrincipal =
        ApiPrincipal(
            tokenId = id,
            scope = ApiScope(rootIds = rootIds, tagsInclude = tagsInclude),
            capabilities = capabilities,
            authMode = ApiAuthMode.BEARER
        )

    /** True when the token's scope restricts anything (a non-null root set, even an empty one, or any tag). */
    val isScopeRestricted: Boolean get() = !principal().toAccessScope().isUnrestricted
}

/**
 * One parity scenario: the SAME tool arguments sent over MCP and over `POST /api/v1/ops/{tool}` against two identically seeded
 * databases. The `expect*` fields are literal oracles taken from the envelope spec (status per error code, forbidden detail
 * shape), never from a run.
 *
 * @property member the select name or op name this scenario exercises (what coverage counts against)
 * @property expectStatus the REST status the envelope spec assigns to the expected outcome (200 for ok)
 * @property expectCode the wire error code of the whole-call body, or null for an ok body
 * @property expectContains substrings of the compact normalized body that must be present
 * @property expectAbsent substrings of the compact normalized body that must be absent
 * @property expectDbChanged whether the work_items/notes/dependencies/events snapshot must differ from the seeded one, or null
 *   to not assert
 */
data class ParityScenario(
    val name: String,
    val tool: String,
    val member: String,
    val kind: ParityKind,
    val token: ParityToken,
    val arguments: String,
    val expectStatus: Int,
    val expectCode: String? = null,
    val expectContains: List<String> = emptyList(),
    val expectAbsent: List<String> = emptyList(),
    val expectDbChanged: Boolean? = null,
    val seed: suspend ParitySeeder.() -> Unit = {}
)

/**
 * An explicit, reasoned exemption from the SCOPE_FORBIDDEN requirement for a select/op that touches no item (D4). A blank
 * [reason] is rejected by [ParityCoverage.missing].
 */
data class ScopeExempt(
    val tool: String,
    val member: String,
    val reason: String
)

data class MissingParity(
    val tool: String,
    val member: String,
    val kind: ParityKind
) {
    override fun toString(): String = "$tool.$member:$kind"
}

/**
 * The coverage rule (task-scope D4), pure over a registry and a scenario catalog: every registered `tool x select/op` needs one
 * HAPPY scenario and one SCOPE_FORBIDDEN scenario that runs under a non-unrestricted scope, unless it carries a valid
 * [ScopeExempt].
 */
object ParityCoverage {
    /** Every (tool name, select-or-op name) pair of [registry], in registration order. */
    fun members(registry: ToolRegistry): List<Pair<String, String>> =
        registry.tools.flatMap { spec ->
            if (spec is SelectToolSpec) {
                spec.selects.map { spec.name to it.name }
            } else if (spec is OpsToolSpec<*>) {
                spec.ops.map { spec.name to it.name }
            } else {
                emptyList()
            }
        }

    /**
     * The required scenarios [scenarios] do not provide.
     *
     * @throws IllegalArgumentException for an exemption with a blank reason, or one naming a tool/member that is not registered
     */
    fun missing(
        registry: ToolRegistry,
        scenarios: List<ParityScenario>,
        exemptions: List<ScopeExempt> = emptyList()
    ): List<MissingParity> {
        val members = members(registry)
        val known = members.toSet()
        for (e in exemptions) {
            require(e.reason.isNotBlank()) { "scope exemption for ${e.tool}.${e.member} needs a non-blank reason" }
            require((e.tool to e.member) in known) { "scope exemption names unregistered ${e.tool}.${e.member}" }
        }
        return buildList {
            for ((tool, member) in members) {
                val mine = scenarios.filter { it.tool == tool && it.member == member }
                if (mine.none { it.kind == ParityKind.HAPPY }) add(MissingParity(tool, member, ParityKind.HAPPY))
                val exempt = exemptions.any { it.tool == tool && it.member == member }
                val scoped = mine.any { it.kind == ParityKind.SCOPE_FORBIDDEN && it.token.isScopeRestricted }
                if (!exempt && !scoped) add(MissingParity(tool, member, ParityKind.SCOPE_FORBIDDEN))
            }
        }
    }

    /** Scenarios naming a tool/member that [registry] does not have (a stale catalog entry). */
    fun stale(
        registry: ToolRegistry,
        scenarios: List<ParityScenario>
    ): List<ParityScenario> {
        val known = members(registry).toSet()
        return scenarios.filter { (it.tool to it.member) !in known }
    }
}

/**
 * The production parity catalog. Every item that registers a select or op MUST append its HAPPY and SCOPE_FORBIDDEN
 * scenarios (or a reasoned [ScopeExempt]) here, or `ParityCoverageGuardTest` fails. `notes_read` / `notes_write` (Q11) are the
 * first production tools; their scenarios live in `NotesParityCatalog` (they need notes seeded beforehand) and run in
 * `NotesParityTest`. `deps_read` / `deps_write` (Q12) follow in `DepsParityScenarios`, run in `DepsParityTest`; `docs_read` / `docs_write` (Q25) are in `DocsParityScenarios`, run in `DocsParityTest`.
 */
object ProductionParity {
    val scenarios: List<ParityScenario> =
        NotesParityCatalog.scenarios + DepsParityScenarios.scenarios + AdvanceParityScenarios.all + DocsParityScenarios.scenarios
    val exemptions: List<ScopeExempt> = NotesParityCatalog.exemptions + DepsParityScenarios.exemptions + DocsParityScenarios.exemptions
}
