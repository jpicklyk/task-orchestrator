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
 * The `items_read` scenarios [ProductionParity] registers (split out unchanged at the Q11/Q12 merge).
 * `items_read` (item 450aa813) contributes
 * one HAPPY and one SCOPE_FORBIDDEN scenario per select plus strictness / capability scenarios; the oracles are literals from
 * the task-scope (D2: a root-scoped TARGET id outside the scope is `forbidden{scope: root, required: <listed roots>}` before
 * anything else; list-like selects drop out-of-scope rows instead of failing) and the envelope statuses.
 */
object ItemsReadParityScenarios {
    private val r1: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val c1: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val r2: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private val missing: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")

    private val reader = ParityToken("ir-reader", setOf(ApiCapability.READ))
    private val rootScoped = ParityToken("ir-root-scoped", setOf(ApiCapability.READ), rootIds = setOf(r1))
    private val noRead = ParityToken("ir-no-read", setOf(ApiCapability.WRITE_ITEMS))

    /** R1 "Alpha root zephyrquux" > C1 "Alpha child"; R2 "Beta root zephyrquux". */
    private val seedTree: suspend ParitySeeder.() -> Unit = {
        item(r1, "Alpha root zephyrquux", tags = "alpha")
        item(c1, "Alpha child", parent = r1, tags = "alpha")
        item(r2, "Beta root zephyrquux", tags = "beta")
    }

    /** [seedTree] plus R2 BLOCKS C1: the child is dependency-blocked by an item outside a root-R1 scope. */
    private val seedBlocked: suspend ParitySeeder.() -> Unit = {
        seedTree()
        dependency(UUID.fromString("55555555-5555-4555-8555-555555555555"), from = r2, to = c1)
    }

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
        seed: suspend ParitySeeder.() -> Unit = seedTree
    ) = ParityScenario(
        name = "items_read $name",
        tool = "items_read",
        member = member,
        kind = kind,
        token = token,
        arguments = arguments,
        expectStatus = expectStatus,
        expectCode = expectCode,
        expectContains = expectContains,
        expectAbsent = expectAbsent,
        expectDbChanged = false,
        seed = seed
    )

    private val rootForbidden = listOf("\"scope\":\"root\"", "\"required\":\"$r1\"")

    val scenarios: List<ParityScenario> =
        listOf(
            scenario(
                "get happy",
                "get",
                ParityKind.HAPPY,
                reader,
                """{"select":"get","itemId":"$c1"}""",
                200,
                expectContains = listOf("\"title\":\"Alpha child\"")
            ),
            scenario(
                "get scope root",
                "get",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                """{"select":"get","itemId":"$r2"}""",
                403,
                expectCode = "forbidden",
                expectContains = rootForbidden,
                expectAbsent = listOf("Beta root")
            ),
            scenario(
                "list happy",
                "list",
                ParityKind.HAPPY,
                reader,
                """{"select":"list","parentId":"$r1"}""",
                200,
                expectContains = listOf("\"title\":\"Alpha child\"")
            ),
            scenario(
                "list filtered by root scope",
                "list",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                """{"select":"list"}""",
                200,
                expectContains = listOf("Alpha root zephyrquux", "Alpha child"),
                expectAbsent = listOf("Beta root")
            ),
            scenario(
                "schema happy",
                "schema",
                ParityKind.HAPPY,
                reader,
                """{"select":"schema","type":"no-such-type"}""",
                200,
                expectContains = listOf("\"schemaFree\":true")
            ),
            scenario(
                "schema scope root",
                "schema",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                """{"select":"schema","itemId":"$r2"}""",
                403,
                expectCode = "forbidden",
                expectContains = rootForbidden,
                expectAbsent = listOf("Beta root")
            ),
            scenario(
                "search happy",
                "search",
                ParityKind.HAPPY,
                reader,
                """{"select":"search","query":"zephyrquux"}""",
                200,
                expectContains = listOf("Alpha root zephyrquux", "Beta root zephyrquux")
            ),
            scenario(
                "search filtered by root scope",
                "search",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                """{"select":"search","query":"zephyrquux"}""",
                200,
                expectContains = listOf("Alpha root zephyrquux"),
                expectAbsent = listOf("Beta root")
            ),
            scenario(
                "overview happy",
                "overview",
                ParityKind.HAPPY,
                reader,
                """{"select":"overview"}""",
                200,
                expectContains = listOf("Alpha root zephyrquux", "Beta root zephyrquux")
            ),
            scenario(
                "overview scope root",
                "overview",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                """{"select":"overview","itemId":"$r2"}""",
                403,
                expectCode = "forbidden",
                expectContains = rootForbidden,
                expectAbsent = listOf("Beta root")
            ),
            // related (Q30 630a826a): C1 shares the `alpha` tag with R1, so R1 is a relative with a shared-tag reason.
            scenario(
                "related happy",
                "related",
                ParityKind.HAPPY,
                reader,
                """{"select":"related","itemId":"$c1"}""",
                200,
                expectContains = listOf("Alpha root zephyrquux", "shared_tags:1")
            ),
            scenario(
                "related scope root",
                "related",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                """{"select":"related","itemId":"$r2"}""",
                403,
                expectCode = "forbidden",
                expectContains = rootForbidden,
                expectAbsent = listOf("Beta root")
            ),
            scenario(
                "context happy",
                "context",
                ParityKind.HAPPY,
                reader,
                """{"select":"context","itemId":"$c1"}""",
                200,
                expectContains = listOf("\"canAdvance\":true", "\"title\":\"Alpha child\"", "\"transitions\"")
            ),
            scenario(
                "context scope root",
                "context",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                """{"select":"context","itemId":"$r2"}""",
                403,
                expectCode = "forbidden",
                expectContains = rootForbidden,
                expectAbsent = listOf("Beta root")
            ),
            scenario(
                "context health filtered by root scope",
                "context",
                ParityKind.OTHER,
                rootScoped,
                """{"select":"context"}""",
                200,
                expectContains = listOf("\"claimSummary\"", "\"active\":{\"items\":[]"),
                expectAbsent = listOf("Beta root")
            ),
            scenario(
                "next happy",
                "next",
                ParityKind.HAPPY,
                reader,
                """{"select":"next","limit":5}""",
                200,
                expectContains = listOf("Alpha root zephyrquux", "Beta root zephyrquux")
            ),
            scenario(
                "next filtered by root scope",
                "next",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                """{"select":"next","limit":5}""",
                200,
                expectContains = listOf("Alpha root zephyrquux"),
                expectAbsent = listOf("Beta root")
            ),
            scenario(
                "blocked happy",
                "blocked",
                ParityKind.HAPPY,
                reader,
                """{"select":"blocked"}""",
                200,
                expectContains = listOf("\"title\":\"Alpha child\"", "\"blockType\":\"dependency\"", "\"satisfied\":false"),
                seed = seedBlocked
            ),
            scenario(
                "blocked filtered by root scope",
                "blocked",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                """{"select":"blocked"}""",
                200,
                expectContains = listOf("\"title\":\"Alpha child\"", "\"blockType\":\"dependency\""),
                expectAbsent = listOf("Beta root", "33333333"),
                seed = seedBlocked
            ),
            scenario(
                "get not found",
                "get",
                ParityKind.OTHER,
                reader,
                """{"select":"get","itemId":"$missing"}""",
                404,
                expectCode = "not_found"
            ),
            scenario(
                "list limit above the maximum",
                "list",
                ParityKind.OTHER,
                reader,
                """{"select":"list","limit":201}""",
                400,
                expectCode = "invalid_request",
                expectContains = listOf("/limit")
            ),
            scenario(
                "get unknown member",
                "get",
                ParityKind.OTHER,
                reader,
                """{"select":"get","itemId":"$c1","bogus":1}""",
                400,
                expectCode = "unknown_parameter",
                expectContains = listOf("/bogus")
            ),
            scenario(
                "search without the read capability",
                "search",
                ParityKind.OTHER,
                noRead,
                """{"select":"search","query":"zephyrquux"}""",
                403,
                expectCode = "forbidden",
                expectContains = listOf("\"scope\":\"capability\"", "\"required\":\"read\""),
                expectAbsent = listOf("Alpha")
            )
        )

    val exemptions: List<ScopeExempt> = emptyList()
}

/**
 * The production parity catalog. Every item that registers a select or op MUST append its HAPPY and SCOPE_FORBIDDEN
 * scenarios (or a reasoned [ScopeExempt]) here, or `ParityCoverageGuardTest` fails. `notes_read` / `notes_write` (Q11) are the
 * first production tools; their scenarios live in `NotesParityCatalog` (they need notes seeded beforehand) and run in
 * `NotesParityTest`. `deps_read` / `deps_write` (Q12) follow in `DepsParityScenarios`, run in `DepsParityTest`; `items_read`
 * (Q19) in [ItemsReadParityScenarios], run in `ItemsReadParityTest`; `docs_read` / `docs_write` (Q25) are in
 * `DocsParityScenarios`, run in `DocsParityTest`.
 */
object ProductionParity {
    val scenarios: List<ParityScenario> =
        ItemsWriteParity.scenarios + NotesParityCatalog.scenarios + DepsParityScenarios.scenarios + AdvanceParityScenarios.all +
            ItemsReadParityScenarios.scenarios + DocsParityScenarios.scenarios + ConfigParityScenarios.scenarios
    val exemptions: List<ScopeExempt> =
        NotesParityCatalog.exemptions + DepsParityScenarios.exemptions + ItemsReadParityScenarios.exemptions +
            DocsParityScenarios.exemptions + ConfigParityScenarios.exemptions
}
