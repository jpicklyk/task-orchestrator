package io.github.jpicklyk.mcptask.current.parity

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import java.util.UUID

/**
 * The parity catalog of `deps_read` (list, graph) and `deps_write` (create, delete, linear, fan_out, fan_in), item 54f3a1ce
 * (Q12). Scenario ids S1..S22 refer to the frozen test-plan; the ones without an S-id are probes.
 *
 * Seeded tree (no event rows): R1 "Alpha root" (tag alpha) with children a, b, c, d (tag alpha); R2 "Beta root" (tag beta) with
 * child x (tag beta). Root-scoped tokens are limited to R1, tag-scoped tokens to `alpha`, so x is the out-of-scope counterparty.
 *
 * Oracles (named per scenario in the plan): the response-envelope spec for statuses and shapes (200 ok, 207 partial_failure,
 * 400 invalid_request/unknown_parameter/invalid_cursor, 403 forbidden, 404 not_found, 409 duplicate/cycle_detected/ambiguous_id),
 * the DependencyCommandService policy (duplicates within the request, duplicates against stored rows, then cycles; one rejected
 * edge rejects the whole request and stores nothing; RELATES_TO never cycles), the Dependency invariants, and the task-scope
 * rules for scope (reads: subject refused, counterparty dropped; writes: every named end admitted or the element is forbidden).
 */
internal object DepsParityScenarios {
    val r1: UUID = UUID.fromString("11000000-0000-4000-8000-000000000001")
    val a: UUID = UUID.fromString("a1000000-0000-4000-8000-00000000000a")
    val b: UUID = UUID.fromString("b1000000-0000-4000-8000-00000000000b")
    val c: UUID = UUID.fromString("c1000000-0000-4000-8000-00000000000c")
    val d: UUID = UUID.fromString("d1000000-0000-4000-8000-00000000000d")
    val r2: UUID = UUID.fromString("22000000-0000-4000-8000-000000000002")
    val x: UUID = UUID.fromString("e1000000-0000-4000-8000-0000000000e1")

    /** Two items sharing the 8-hex prefix `ab000000`, for the ambiguous_id probe. */
    val p1: UUID = UUID.fromString("ab000000-0000-4000-8000-0000000000a1")
    val p2: UUID = UUID.fromString("ab000000-0000-4000-8000-0000000000a2")

    /** A well-formed item id no item carries. */
    val ghost: UUID = UUID.fromString("99000000-0000-4000-8000-0000000000ff")

    val d1: UUID = UUID.fromString("de000001-0000-4000-8000-000000000001")
    val d2: UUID = UUID.fromString("de000002-0000-4000-8000-000000000002")
    val d3: UUID = UUID.fromString("de000003-0000-4000-8000-000000000003")
    val d4: UUID = UUID.fromString("de000004-0000-4000-8000-000000000004")
    val d5: UUID = UUID.fromString("de000005-0000-4000-8000-000000000005")

    /** A well-formed dependency id no edge carries. */
    val ghostDep: UUID = UUID.fromString("de0000ff-0000-4000-8000-0000000000ff")

    val admin = ParityToken("deps-admin", setOf(ApiCapability.ADMIN))
    val reader = ParityToken("deps-reader", setOf(ApiCapability.READ))
    val rootScoped = ParityToken("deps-root-scoped", setOf(ApiCapability.READ, ApiCapability.MANAGE_DEPENDENCIES), rootIds = setOf(r1))
    val tagScoped =
        ParityToken("deps-tag-scoped", setOf(ApiCapability.READ, ApiCapability.MANAGE_DEPENDENCIES), tagsInclude = setOf("alpha"))

    /** One seeded edge. */
    class Edge(
        val id: UUID,
        val from: UUID,
        val to: UUID,
        val type: DependencyType = DependencyType.BLOCKS,
        val unblockAt: String? = null
    )

    /** The item tree, then the given edges (through the store, no events). */
    fun tree(vararg edges: Edge): suspend ParitySeeder.() -> Unit =
        {
            item(r1, "Alpha root", tags = "alpha")
            for ((id, title) in listOf(a to "Item a", b to "Item b", c to "Item c", d to "Item d")) {
                item(id, title, parent = r1, tags = "alpha")
            }
            item(r2, "Beta root", tags = "beta")
            item(x, "Item x", parent = r2, tags = "beta")
            for (e in edges) dependency(e.id, e.from, e.to, e.type, e.unblockAt)
        }

    /** The tree plus the two prefix-sharing items. */
    fun treeWithPrefixPair(): suspend ParitySeeder.() -> Unit =
        {
            tree().invoke(this)
            item(p1, "Prefix one", parent = r1, tags = "alpha")
            item(p2, "Prefix two", parent = r1, tags = "alpha")
        }

    // ------------------------------------------------------------------ argument builders

    fun list(
        item: Any,
        extra: String = ""
    ) = """{"select":"list","item":"$item"$extra}"""

    fun graph(
        item: Any,
        extra: String = ""
    ) = """{"select":"graph","item":"$item"$extra}"""

    fun create(
        from: Any,
        to: Any,
        extra: String = ""
    ) = """{"op":"create","from":"$from","to":"$to"$extra}"""

    fun delete(fields: String) = """{"op":"delete"$fields}"""

    fun linear(
        vararg ids: Any,
        extra: String = ""
    ) = """{"op":"linear","ids":[${ids.joinToString(",") { "\"$it\"" }}]$extra}"""

    fun fanOut(
        from: Any,
        vararg to: Any,
        extra: String = ""
    ) = """{"op":"fan_out","from":"$from","toIds":[${to.joinToString(",") { "\"$it\"" }}]$extra}"""

    fun fanIn(
        vararg from: Any,
        to: Any,
        extra: String = ""
    ) = """{"op":"fan_in","fromIds":[${from.joinToString(",") { "\"$it\"" }}],"to":"$to"$extra}"""

    fun ops(
        vararg elements: String,
        top: String = ""
    ) = """{"ops":[${elements.joinToString(",")}]$top}"""

    private fun scenario(
        name: String,
        tool: String,
        member: String,
        kind: ParityKind,
        token: ParityToken,
        arguments: String,
        status: Int,
        code: String? = null,
        contains: List<String> = emptyList(),
        absent: List<String> = emptyList(),
        dbChanged: Boolean? = null,
        seed: suspend ParitySeeder.() -> Unit = tree()
    ) = ParityScenario(name, tool, member, kind, token, arguments, status, code, contains, absent, dbChanged, seed)

    const val READ_TOOL = "deps_read"
    const val WRITE_TOOL = "deps_write"

    private val relates = DependencyType.RELATES_TO

    private val happyKind = ParityKind.HAPPY
    private val scopeKind = ParityKind.SCOPE_FORBIDDEN
    private val otherKind = ParityKind.OTHER

    private fun fromTo(
        from: UUID,
        to: UUID
    ) = listOf("\"from\":\"$from\"", "\"to\":\"$to\"")

    private fun scopeRoot() = listOf("\"scope\":\"root\"", "\"required\":\"$r1\"")

    private fun scopeTag() = listOf("\"scope\":\"tag\"", "\"required\":\"alpha\"")

    // ------------------------------------------------------------------ HAPPY (S1-S7)

    val happy: List<ParityScenario> =
        listOf(
            // S1 oracle: envelope + task-scope (type defaults to blocks, the result echoes full ids and the type)
            scenario(
                "S1 create happy",
                WRITE_TOOL,
                "create",
                happyKind,
                admin,
                ops(create(a, b)),
                200,
                contains = fromTo(a, b) + listOf("\"type\":\"blocks\"", "\"summary\":{\"ok\":1,\"failed\":0}"),
                dbChanged = true
            ),
            // S2 oracle: task-scope (linear [a,b,c] stores a->b and b->c), U18 (one element, one unit)
            scenario(
                "S2 linear happy",
                WRITE_TOOL,
                "linear",
                happyKind,
                admin,
                ops(linear(a, b, c)),
                200,
                contains = listOf("\"created\":[") + fromTo(a, b) + fromTo(b, c),
                dbChanged = true
            ),
            scenario(
                "S3 fan_out happy",
                WRITE_TOOL,
                "fan_out",
                happyKind,
                admin,
                ops(fanOut(a, b, c)),
                200,
                contains = listOf("\"created\":[") + fromTo(a, b) + fromTo(a, c),
                dbChanged = true
            ),
            scenario(
                "S4 fan_in happy",
                WRITE_TOOL,
                "fan_in",
                happyKind,
                admin,
                ops(fanIn(a, b, to = c)),
                200,
                contains = listOf("\"created\":[") + fromTo(a, c) + fromTo(b, c),
                dbChanged = true
            ),
            scenario(
                "S5 delete by id happy",
                WRITE_TOOL,
                "delete",
                happyKind,
                admin,
                ops(delete(""","id":"$d1"""")),
                200,
                contains = listOf("\"deleted\":1"),
                dbChanged = true,
                seed = tree(Edge(d1, a, b))
            ),
            // S6 oracle: task-scope (incoming = edges whose `to` is the item; exact total; the item's own edges only)
            scenario(
                "S6 list incoming happy",
                READ_TOOL,
                "list",
                happyKind,
                reader,
                list(b, ""","direction":"incoming""""),
                200,
                contains = listOf("\"id\":\"$d1\"", "\"id\":\"$d2\"", "\"total\":2"),
                absent = listOf("$d3"),
                dbChanged = false,
                seed = tree(Edge(d1, a, b), Edge(d2, c, b), Edge(d3, a, c))
            ),
            // S7 oracle: task-scope graph (level = longest blocker path, sources 0)
            scenario(
                "S7 graph happy",
                READ_TOOL,
                "graph",
                happyKind,
                reader,
                graph(a),
                200,
                contains =
                    listOf(
                        "\"id\":\"$a\"",
                        "\"id\":\"$b\"",
                        "\"id\":\"$c\"",
                        "\"level\":0",
                        "\"level\":1",
                        "\"level\":2",
                        "\"total\":3"
                    ),
                dbChanged = false,
                seed = tree(Edge(d1, a, b), Edge(d2, b, c))
            )
        )

    // ------------------------------------------------------------------ SCOPE_FORBIDDEN (S14-S19)

    val scopeForbidden: List<ParityScenario> =
        listOf(
            // S14: every named end must be admitted; each forbidden element stores nothing
            scenario(
                "S14 create scope root both ends",
                WRITE_TOOL,
                "create",
                scopeKind,
                rootScoped,
                ops(create(a, x), create(x, a)),
                207,
                code = "partial_failure",
                contains = scopeRoot() + listOf("\"summary\":{\"ok\":0,\"failed\":2}"),
                dbChanged = false
            ),
            scenario(
                "S19 create scope tag",
                WRITE_TOOL,
                "create",
                scopeKind,
                tagScoped,
                ops(create(a, x)),
                207,
                code = "partial_failure",
                contains = scopeTag(),
                dbChanged = false
            ),
            // S15: a pattern is one element; one non-admitted item refuses it and nothing is written
            scenario(
                "S15 fan_out scope root",
                WRITE_TOOL,
                "fan_out",
                scopeKind,
                rootScoped,
                ops(fanOut(a, b, x)),
                207,
                code = "partial_failure",
                contains = scopeRoot(),
                dbChanged = false
            ),
            scenario(
                "S15 fan_in scope root",
                WRITE_TOOL,
                "fan_in",
                scopeKind,
                rootScoped,
                ops(fanIn(b, x, to = a)),
                207,
                code = "partial_failure",
                contains = scopeRoot(),
                dbChanged = false
            ),
            scenario(
                "S15 linear scope root",
                WRITE_TOOL,
                "linear",
                scopeKind,
                rootScoped,
                ops(linear(a, x)),
                207,
                code = "partial_failure",
                contains = scopeRoot(),
                dbChanged = false
            ),
            // S15 variant: the non-admitted item is last, so a chain written edge by edge would keep a->b
            scenario(
                "S15 linear scope root, out-of-scope item last in the chain",
                WRITE_TOOL,
                "linear",
                scopeKind,
                rootScoped,
                ops(linear(a, b, x)),
                207,
                code = "partial_failure",
                contains = scopeRoot(),
                dbChanged = false
            ),
            // S16: delete by id needs BOTH stored ends admitted (3.x DELETE /dependencies/{id} checked both)
            scenario(
                "S16 delete by id scope root, from end in scope and to end out",
                WRITE_TOOL,
                "delete",
                scopeKind,
                rootScoped,
                ops(delete(""","id":"$d1"""")),
                207,
                code = "partial_failure",
                contains = scopeRoot(),
                dbChanged = false,
                seed = tree(Edge(d1, a, x))
            ),
            scenario(
                "S16 delete by id scope root, from end out of scope and to end in",
                WRITE_TOOL,
                "delete",
                scopeKind,
                rootScoped,
                ops(delete(""","id":"$d1"""")),
                207,
                code = "partial_failure",
                contains = scopeRoot(),
                dbChanged = false,
                seed = tree(Edge(d1, x, a))
            ),
            scenario(
                "S16 delete by from and to scope root",
                WRITE_TOOL,
                "delete",
                scopeKind,
                rootScoped,
                ops(delete(""","from":"$a","to":"$x"""")),
                207,
                code = "partial_failure",
                contains = scopeRoot(),
                dbChanged = false,
                seed = tree(Edge(d1, a, x))
            ),
            // S16: delete {item} needs the item and every counterparty of its edges admitted
            scenario(
                "S16 delete by item scope root, one counterparty out of scope",
                WRITE_TOOL,
                "delete",
                scopeKind,
                rootScoped,
                ops(delete(""","item":"$a"""")),
                207,
                code = "partial_failure",
                contains = scopeRoot(),
                dbChanged = false,
                seed = tree(Edge(d1, a, b), Edge(d2, a, x))
            ),
            // S17: reads refuse a non-admitted subject and drop a non-admitted counterparty (not counted in total)
            scenario(
                "S17 list scope root subject out of scope",
                READ_TOOL,
                "list",
                scopeKind,
                rootScoped,
                list(x),
                403,
                code = "forbidden",
                contains = scopeRoot(),
                absent = listOf("$d1"),
                dbChanged = false,
                seed = tree(Edge(d1, a, x))
            ),
            scenario(
                "S17 list scope root drops the out-of-scope counterparty",
                READ_TOOL,
                "list",
                scopeKind,
                rootScoped,
                list(a),
                200,
                contains = listOf("\"id\":\"$d1\"", "\"total\":1"),
                absent = listOf("$d2", "$x"),
                dbChanged = false,
                seed = tree(Edge(d1, a, b), Edge(d2, a, x))
            ),
            scenario(
                "S19 list scope tag subject out of scope",
                READ_TOOL,
                "list",
                scopeKind,
                tagScoped,
                list(x),
                403,
                code = "forbidden",
                contains = scopeTag(),
                dbChanged = false
            ),
            // S18: graph neither lists nor expands a non-admitted node (D9: no traversal through it)
            scenario(
                "S18 graph scope root does not expand a non-admitted node",
                READ_TOOL,
                "graph",
                scopeKind,
                rootScoped,
                graph(a),
                200,
                contains = listOf("\"id\":\"$a\"", "\"total\":1"),
                absent = listOf("$x", "$c"),
                dbChanged = false,
                seed = tree(Edge(d1, a, x), Edge(d2, x, c))
            ),
            scenario(
                "S18 graph scope root subject out of scope",
                READ_TOOL,
                "graph",
                scopeKind,
                rootScoped,
                graph(x),
                403,
                code = "forbidden",
                contains = scopeRoot(),
                dbChanged = false,
                seed = tree(Edge(d1, a, x))
            ),
            // scope is decided before existence: an unknown item under a restricted scope is forbidden, never not_found
            scenario(
                "create scope root, unknown to item is forbidden not not_found",
                WRITE_TOOL,
                "create",
                scopeKind,
                rootScoped,
                ops(create(a, ghost)),
                207,
                code = "partial_failure",
                contains = scopeRoot(),
                absent = listOf("not_found"),
                dbChanged = false
            ),
            scenario(
                "list scope root, unknown item is forbidden not not_found",
                READ_TOOL,
                "list",
                scopeKind,
                rootScoped,
                list(ghost),
                403,
                code = "forbidden",
                contains = scopeRoot(),
                absent = listOf("not_found")
            )
        )

    // ------------------------------------------------------------------ OTHER: failures, edge cases, probes

    val failures: List<ParityScenario> =
        listOf(
            // S8 oracle: CycleDetector KDoc path [b, a, b]; Svc: a cycle rejects the element and stores nothing
            scenario(
                "S8 create closing a cycle",
                WRITE_TOOL,
                "create",
                otherKind,
                admin,
                ops(create(b, a)),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"cycle_detected\"", "\"path\":[\"$b\",\"$a\",\"$b\"]"),
                dbChanged = false,
                seed = tree(Edge(d1, a, b))
            ),
            // S9 oracle: envelope atomic (whole call carries the element's code and error.index)
            scenario(
                "S9 create closing a cycle with atomic",
                WRITE_TOOL,
                "create",
                otherKind,
                admin,
                ops(create(b, a), top = ""","atomic":true"""),
                409,
                code = "cycle_detected",
                contains = listOf("\"index\":0"),
                dbChanged = false,
                seed = tree(Edge(d1, a, b))
            ),
            // S10 oracle: Svc (same normalized from/to/type is a duplicate whatever the unblockAt)
            scenario(
                "S10 create duplicating a stored edge",
                WRITE_TOOL,
                "create",
                otherKind,
                admin,
                ops(create(a, b, ""","unblockAt":"work"""")),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"duplicate\"", "\"kind\":\"dependency\"", "\"existingId\":\"$d1\""),
                dbChanged = false,
                seed = tree(Edge(d1, a, b))
            ),
            // S11 oracle: Svc policy (duplicates within the request first); U18 (nothing stored)
            scenario(
                "S11 linear repeating an edge within the request",
                WRITE_TOOL,
                "linear",
                otherKind,
                admin,
                ops(linear(a, b, a, b)),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"duplicate\""),
                dbChanged = false
            ),
            // S12 oracle: CD (RELATES_TO never forms a cycle), Dep (RELATES_TO carries no unblockAt)
            scenario(
                "S12 relates_to both ways is fine, relates_to with unblockAt is invalid_request",
                WRITE_TOOL,
                "create",
                otherKind,
                admin,
                ops(
                    create(a, b, ""","type":"relates_to""""),
                    create(b, a, ""","type":"relates_to""""),
                    create(a, c, ""","type":"relates_to","unblockAt":"work"""")
                ),
                207,
                code = "partial_failure",
                contains = listOf("\"summary\":{\"ok\":2,\"failed\":1}", "\"code\":\"invalid_request\"", "/unblockAt"),
                dbChanged = true
            ),
            // S13 oracle: U18 (a pattern is atomic: the cycle on the last edge removes a->b too)
            scenario(
                "S13 linear whose last edge closes a cycle stores nothing",
                WRITE_TOOL,
                "linear",
                otherKind,
                admin,
                ops(linear(a, b, c)),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"cycle_detected\""),
                dbChanged = false,
                seed = tree(Edge(d1, c, a))
            ),
            scenario(
                "fan_out with one stored duplicate stores none of its edges",
                WRITE_TOOL,
                "fan_out",
                otherKind,
                admin,
                ops(fanOut(a, b, c)),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"duplicate\"", "\"existingId\":\"$d1\""),
                dbChanged = false,
                seed = tree(Edge(d1, a, c))
            ),
            scenario(
                "fan_in closing a cycle",
                WRITE_TOOL,
                "fan_in",
                otherKind,
                admin,
                ops(fanIn(b, to = a)),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"cycle_detected\""),
                dbChanged = false,
                seed = tree(Edge(d1, a, b))
            ),
            scenario(
                "elements of one call run in order: the second sees the first and closes a cycle",
                WRITE_TOOL,
                "create",
                otherKind,
                admin,
                ops(create(a, b), create(b, a)),
                207,
                code = "partial_failure",
                contains = listOf("\"summary\":{\"ok\":1,\"failed\":1}", "\"code\":\"cycle_detected\"", "\"path\":[\"$b\",\"$a\",\"$b\"]"),
                dbChanged = true
            ),
            scenario(
                "atomic duplicate in the second element rolls the first back",
                WRITE_TOOL,
                "create",
                otherKind,
                admin,
                ops(create(a, b), create(a, b), top = ""","atomic":true"""),
                409,
                code = "duplicate",
                contains = listOf("\"index\":1"),
                dbChanged = false
            ),
            scenario(
                "relates_to alongside a blocks edge between the same items is not a duplicate",
                WRITE_TOOL,
                "create",
                otherKind,
                admin,
                ops(create(a, b, ""","type":"relates_to"""")),
                200,
                contains = fromTo(a, b) + listOf("\"type\":\"relates_to\""),
                dbChanged = true,
                seed = tree(Edge(d1, a, b))
            ),
            scenario(
                "relates_to in the reverse direction of a blocks edge never cycles",
                WRITE_TOOL,
                "create",
                otherKind,
                admin,
                ops(create(b, a, ""","type":"relates_to"""")),
                200,
                contains = fromTo(b, a) + listOf("\"type\":\"relates_to\""),
                dbChanged = true,
                seed = tree(Edge(d1, a, b))
            ),
            scenario(
                "create with an unblockAt threshold",
                WRITE_TOOL,
                "create",
                otherKind,
                admin,
                ops(create(a, b, ""","unblockAt":"work"""")),
                200,
                contains = fromTo(a, b) + listOf("\"unblockAt\":\"work\""),
                dbChanged = true
            ),
            // S20 oracle: envelope + task-scope (a named entity that does not exist is not_found)
            scenario(
                "S20 create to an unknown item",
                WRITE_TOOL,
                "create",
                otherKind,
                admin,
                ops(create(a, ghost)),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"not_found\"", "\"kind\":\"item\"", "\"id\":\"$ghost\""),
                dbChanged = false
            ),
            scenario(
                "S20 delete an unknown dependency id",
                WRITE_TOOL,
                "delete",
                otherKind,
                admin,
                ops(delete(""","id":"$ghostDep"""")),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"not_found\"", "\"kind\":\"dependency\"", "\"id\":\"$ghostDep\""),
                dbChanged = false
            ),
            scenario(
                "S20 list an unknown item",
                READ_TOOL,
                "list",
                otherKind,
                admin,
                list(ghost),
                404,
                code = "not_found",
                contains = listOf("\"kind\":\"item\"", "\"id\":\"$ghost\"")
            ),
            scenario(
                "graph an unknown item",
                READ_TOOL,
                "graph",
                otherKind,
                admin,
                graph(ghost),
                404,
                code = "not_found"
            ),
            // task-scope item 6: a relation filter that matches nothing is a count, not an error
            scenario(
                "delete by from and to matching nothing deletes zero",
                WRITE_TOOL,
                "delete",
                otherKind,
                admin,
                ops(delete(""","from":"$a","to":"$b"""")),
                200,
                contains = listOf("\"deleted\":0"),
                dbChanged = false
            ),
            scenario(
                "delete by item with no edges deletes zero",
                WRITE_TOOL,
                "delete",
                otherKind,
                admin,
                ops(delete(""","item":"$a"""")),
                200,
                contains = listOf("\"deleted\":0"),
                dbChanged = false
            ),
            // Svc deleteByRelationship KDoc: null type matches the stored rows of any type from->to
            scenario(
                "delete by from and to without type removes every type",
                WRITE_TOOL,
                "delete",
                otherKind,
                admin,
                ops(delete(""","from":"$a","to":"$b"""")),
                200,
                contains = listOf("\"deleted\":2"),
                dbChanged = true,
                seed = tree(Edge(d1, a, b), Edge(d2, a, b, relates))
            ),
            scenario(
                "delete by from and to with a type removes only that type",
                WRITE_TOOL,
                "delete",
                otherKind,
                admin,
                ops(delete(""","from":"$a","to":"$b","type":"relates_to"""")),
                200,
                contains = listOf("\"deleted\":1"),
                dbChanged = true,
                seed = tree(Edge(d1, a, b), Edge(d2, a, b, relates))
            ),
            scenario(
                "delete by from and to does not match the reverse direction",
                WRITE_TOOL,
                "delete",
                otherKind,
                admin,
                ops(delete(""","from":"$b","to":"$a"""")),
                200,
                contains = listOf("\"deleted\":0"),
                dbChanged = false,
                seed = tree(Edge(d1, a, b))
            ),
            scenario(
                "delete by item removes both directions and leaves unrelated edges",
                WRITE_TOOL,
                "delete",
                otherKind,
                admin,
                ops(delete(""","item":"$a"""")),
                200,
                contains = listOf("\"deleted\":2"),
                dbChanged = true,
                seed = tree(Edge(d1, a, b), Edge(d2, c, a), Edge(d3, b, d))
            ),
            scenario(
                "delete by item scope root with every counterparty in scope succeeds",
                WRITE_TOOL,
                "delete",
                otherKind,
                rootScoped,
                ops(delete(""","item":"$a"""")),
                200,
                contains = listOf("\"deleted\":2"),
                dbChanged = true,
                seed = tree(Edge(d1, a, b), Edge(d2, c, a))
            ),
            scenario(
                "create scope root, one element in scope and one out",
                WRITE_TOOL,
                "create",
                otherKind,
                rootScoped,
                ops(create(a, b), create(a, x)),
                207,
                code = "partial_failure",
                contains = scopeRoot() + listOf("\"summary\":{\"ok\":1,\"failed\":1}"),
                dbChanged = true
            ),
            // S21 oracle: Dep invariants + task-scope check order (b) -> invalid_request
            scenario(
                "S21 create a self edge",
                WRITE_TOOL,
                "create",
                otherKind,
                admin,
                ops(create(a, a)),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"invalid_request\"", "/to"),
                dbChanged = false
            ),
            scenario(
                "S21 delete with both id and item",
                WRITE_TOOL,
                "delete",
                otherKind,
                admin,
                ops(delete(""","id":"$d1","item":"$a"""")),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"invalid_request\""),
                dbChanged = false,
                seed = tree(Edge(d1, a, b))
            ),
            scenario(
                "delete with no selector",
                WRITE_TOOL,
                "delete",
                otherKind,
                admin,
                ops(delete("")),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"invalid_request\""),
                dbChanged = false,
                seed = tree(Edge(d1, a, b))
            ),
            scenario(
                "delete with from but no to",
                WRITE_TOOL,
                "delete",
                otherKind,
                admin,
                ops(delete(""","from":"$a"""")),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"invalid_request\""),
                dbChanged = false,
                seed = tree(Edge(d1, a, b))
            ),
            // task-scope D4: a dependency id is a full UUID only
            scenario(
                "delete with a dependency id prefix is invalid_request on /id",
                WRITE_TOOL,
                "delete",
                otherKind,
                admin,
                ops(delete(""","id":"de000001"""")),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"invalid_request\"", "/id"),
                dbChanged = false,
                seed = tree(Edge(d1, a, b))
            ),
            // S21 / D3: is_blocked_by is not on the 4.0 wire; the wire enums are lowercase
            scenario(
                "S21 create with type is_blocked_by",
                WRITE_TOOL,
                "create",
                otherKind,
                admin,
                ops(create(a, b, ""","type":"is_blocked_by"""")),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"invalid_request\"", "type"),
                dbChanged = false
            ),
            scenario(
                "create with type in upper case",
                WRITE_TOOL,
                "create",
                otherKind,
                admin,
                ops(create(a, b, ""","type":"BLOCKS"""")),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"invalid_request\"", "type"),
                dbChanged = false
            ),
            scenario(
                "create with an unblockAt outside the enum",
                WRITE_TOOL,
                "create",
                otherKind,
                admin,
                ops(create(a, b, ""","unblockAt":"never"""")),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"invalid_request\"", "unblockAt"),
                dbChanged = false
            ),
            scenario(
                "create with a malformed item reference",
                WRITE_TOOL,
                "create",
                otherKind,
                admin,
                ops(create("zz", b)),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"invalid_request\"", "/from"),
                dbChanged = false
            ),
            scenario(
                "create with an item prefix shared by two items is ambiguous_id",
                WRITE_TOOL,
                "create",
                otherKind,
                admin,
                ops(create("ab000000", b)),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"ambiguous_id\""),
                dbChanged = false,
                seed = treeWithPrefixPair()
            ),
            scenario(
                "create with an item prefix no item carries is not_found",
                WRITE_TOOL,
                "create",
                otherKind,
                admin,
                ops(create("fe000000", b)),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"not_found\""),
                dbChanged = false
            ),
            // probes: an 8-hex prefix and an upper-case UUID resolve to the same full ids
            scenario(
                "probe create with 8-hex item prefixes echoes the full ids",
                WRITE_TOOL,
                "create",
                otherKind,
                admin,
                ops(create("a1000000", "b1000000")),
                200,
                contains = fromTo(a, b),
                dbChanged = true
            ),
            scenario(
                "probe create with upper-case UUIDs echoes the lower-case full ids",
                WRITE_TOOL,
                "create",
                otherKind,
                admin,
                ops(create(a.toString().uppercase(), b.toString().uppercase())),
                200,
                contains = fromTo(a, b),
                dbChanged = true
            ),
            // probes: pattern shapes
            scenario(
                "probe fan_out with a repeated target",
                WRITE_TOOL,
                "fan_out",
                otherKind,
                admin,
                ops(fanOut(a, b, b)),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"duplicate\""),
                dbChanged = false
            ),
            scenario(
                "probe fan_out with an empty target list",
                WRITE_TOOL,
                "fan_out",
                otherKind,
                admin,
                ops(fanOut(a)),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"invalid_request\"", "toIds"),
                dbChanged = false
            ),
            scenario(
                "probe fan_in with an empty source list",
                WRITE_TOOL,
                "fan_in",
                otherKind,
                admin,
                ops(fanIn(to = a)),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"invalid_request\"", "fromIds"),
                dbChanged = false
            ),
            scenario(
                "probe linear with a single id",
                WRITE_TOOL,
                "linear",
                otherKind,
                admin,
                ops(linear(a)),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"invalid_request\"", "ids"),
                dbChanged = false
            ),
            scenario(
                "probe linear with a repeated neighbour is a self edge",
                WRITE_TOOL,
                "linear",
                otherKind,
                admin,
                ops(linear(a, a, b)),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"invalid_request\"", "/ids"),
                dbChanged = false
            ),
            scenario(
                "probe fan_out whose targets include its own source",
                WRITE_TOOL,
                "fan_out",
                otherKind,
                admin,
                ops(fanOut(a, a, b)),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"invalid_request\""),
                dbChanged = false
            ),
            scenario(
                "probe linear of relates_to with an unblockAt",
                WRITE_TOOL,
                "linear",
                otherKind,
                admin,
                ops(linear(a, b, extra = ""","type":"relates_to","unblockAt":"work"""")),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"invalid_request\"", "/unblockAt"),
                dbChanged = false
            ),
            scenario(
                "probe linear of relates_to never cycles",
                WRITE_TOOL,
                "linear",
                otherKind,
                admin,
                ops(linear(a, b, extra = ""","type":"relates_to"""")),
                200,
                contains = fromTo(a, b) + listOf("\"type\":\"relates_to\""),
                dbChanged = true,
                seed = tree(Edge(d1, b, a, relates))
            ),
            // D4 / dispatcher: a field of another op or select is unknown_parameter
            scenario(
                "probe create with an unknown field",
                WRITE_TOOL,
                "create",
                otherKind,
                admin,
                ops(create(a, b, ""","foo":1""")),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"unknown_parameter\""),
                dbChanged = false
            ),
            scenario(
                "probe create with a field of another op",
                WRITE_TOOL,
                "create",
                otherKind,
                admin,
                ops(create(a, b, ""","toIds":["$c"]""")),
                207,
                code = "partial_failure",
                contains = listOf("\"code\":\"unknown_parameter\""),
                dbChanged = false
            ),
            scenario(
                "probe list with a field of another select",
                READ_TOOL,
                "list",
                otherKind,
                admin,
                list(a, ""","toIds":["$b"]"""),
                400,
                code = "unknown_parameter"
            ),
            scenario(
                "probe graph with a direction",
                READ_TOOL,
                "graph",
                otherKind,
                admin,
                graph(a, ""","direction":"incoming""""),
                400,
                code = "unknown_parameter"
            ),
            // S22 oracle: envelope + Q6 (the capability is checked per op; a READ token may still read)
            scenario(
                "S22 reader cannot write",
                WRITE_TOOL,
                "create",
                otherKind,
                reader,
                ops(create(a, b)),
                207,
                code = "partial_failure",
                contains = listOf("\"scope\":\"capability\"", "\"required\":\"manage-dependencies\""),
                dbChanged = false
            ),
            scenario(
                "S22 reader can list",
                READ_TOOL,
                "list",
                otherKind,
                reader,
                list(a),
                200,
                contains = listOf("\"id\":\"$d1\""),
                dbChanged = false,
                seed = tree(Edge(d1, a, b))
            ),
            // list filters
            scenario(
                "list outgoing returns only edges leaving the item",
                READ_TOOL,
                "list",
                otherKind,
                admin,
                list(a, ""","direction":"outgoing""""),
                200,
                contains = listOf("\"id\":\"$d1\"", "\"id\":\"$d3\"", "\"total\":2"),
                absent = listOf("$d2"),
                seed = tree(Edge(d1, a, b), Edge(d2, c, a), Edge(d3, a, d))
            ),
            scenario(
                "list without direction returns both directions",
                READ_TOOL,
                "list",
                otherKind,
                admin,
                list(a),
                200,
                contains = listOf("\"id\":\"$d1\"", "\"id\":\"$d2\"", "\"total\":2"),
                absent = listOf("$d3"),
                seed = tree(Edge(d1, a, b), Edge(d2, c, a), Edge(d3, b, d))
            ),
            scenario(
                "list with a type filter returns only that type",
                READ_TOOL,
                "list",
                otherKind,
                admin,
                list(a, ""","type":"relates_to""""),
                200,
                contains = listOf("\"id\":\"$d2\"", "\"total\":1"),
                absent = listOf("$d1"),
                seed = tree(Edge(d1, a, b), Edge(d2, a, b, relates))
            ),
            scenario(
                "list of an item without edges is an empty page",
                READ_TOOL,
                "list",
                otherKind,
                admin,
                list(a),
                200,
                contains = listOf("\"items\":[]", "\"total\":0")
            ),
            scenario(
                "list with an unknown direction",
                READ_TOOL,
                "list",
                otherKind,
                admin,
                list(a, ""","direction":"sideways""""),
                400,
                code = "invalid_request",
                contains = listOf("direction")
            ),
            // S25 / ListPage: limit 1..200, opaque cursor
            scenario(
                "S25 list with limit 0",
                READ_TOOL,
                "list",
                otherKind,
                admin,
                list(a, ""","limit":0"""),
                400,
                code = "invalid_request",
                contains = listOf("limit")
            ),
            scenario(
                "S25 list with limit 201",
                READ_TOOL,
                "list",
                otherKind,
                admin,
                list(a, ""","limit":201"""),
                400,
                code = "invalid_request",
                contains = listOf("limit")
            ),
            scenario(
                "S25 list with limit 200",
                READ_TOOL,
                "list",
                otherKind,
                admin,
                list(a, ""","limit":200"""),
                200,
                contains = listOf("\"id\":\"$d1\"", "\"total\":1"),
                seed = tree(Edge(d1, a, b))
            ),
            scenario(
                "S25 list with an undecodable cursor",
                READ_TOOL,
                "list",
                otherKind,
                admin,
                list(a, ""","cursor":"not-a-cursor!""""),
                400,
                code = "invalid_cursor"
            ),
            scenario(
                "S25 list with an empty cursor",
                READ_TOOL,
                "list",
                otherKind,
                admin,
                list(a, ""","cursor":"""""),
                400,
                code = "invalid_cursor"
            ),
            // S7 / TS: RELATES_TO is ignored by the graph; a cycle marks its nodes cyclic and gives them no level
            scenario(
                "graph ignores relates_to edges",
                READ_TOOL,
                "graph",
                otherKind,
                admin,
                graph(a),
                200,
                contains = listOf("\"id\":\"$a\"", "\"level\":0", "\"total\":1"),
                absent = listOf("$b"),
                seed = tree(Edge(d1, a, b, relates))
            ),
            scenario(
                "graph reports a stored cycle as cyclic nodes without a level",
                READ_TOOL,
                "graph",
                otherKind,
                admin,
                graph(a),
                200,
                contains = listOf("\"id\":\"$a\"", "\"id\":\"$b\"", "\"cyclic\":true", "\"total\":2"),
                absent = listOf("\"level\""),
                seed = tree(Edge(d1, a, b), Edge(d2, b, a))
            ),
            // S7 variant: level is the LONGEST blocker path (d is reachable in one hop from a and in two via b and c)
            scenario(
                "graph level is the longest blocker path",
                READ_TOOL,
                "graph",
                otherKind,
                admin,
                graph(a),
                200,
                contains = listOf("\"total\":4", "\"level\":0", "\"level\":1", "\"level\":2"),
                absent = listOf("\"level\":3"),
                seed = tree(Edge(d1, a, b), Edge(d2, a, c), Edge(d3, b, d), Edge(d4, c, d), Edge(d5, a, d))
            )
        )

    val all: List<ParityScenario> get() = happy + scopeForbidden + failures

    /** The catalog `ProductionParity` concatenates. */
    val scenarios: List<ParityScenario> get() = all

    /** Every deps select and op touches an item, so none is exempt from the scope-forbidden requirement. */
    val exemptions: List<ScopeExempt> = emptyList()
}
