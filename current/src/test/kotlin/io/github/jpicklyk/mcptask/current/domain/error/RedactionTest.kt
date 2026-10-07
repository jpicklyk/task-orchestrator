package io.github.jpicklyk.mcptask.current.domain.error

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structural guard: no detail of any code can carry another principal's identity
 * (envelope section 2 detail rule, section 4 claim_held row, section 6 RedactionTest; task-scope D10).
 */
class RedactionTest {
    // Allowlisted field names across every detail class and its nested value types (envelope section 4 detail schemas).
    private val allowedNames =
        setOf(
            "fields",
            "field",
            "reason",
            "received",
            "parameters",
            "kind",
            "id",
            "prefix",
            "candidates",
            "expected",
            "actual",
            "existingId",
            "idempotencyKey",
            "itemId",
            "role",
            "seat",
            "fromRole",
            "trigger",
            "allowed",
            "missing",
            "key",
            "blockers",
            "path",
            "expiresAt",
            "retryAfterMs",
            "action",
            "allowedSeats",
            "ownerSeat",
            "max",
            "resources",
            "name",
            "mode",
            "type",
            "availableTypes",
            "errors",
            "pinnedVersion",
            "currentVersion",
            "scope",
            "required",
        )

    // Names that denote a principal identity. Exact (case-insensitive) matches.
    private val deniedNames =
        setOf(
            "claimedby",
            "claimant",
            "holder",
            "claimholder",
            "owner",
            "ownerid",
            "actor",
            "actorid",
            "principal",
            "principalid",
            "user",
            "userid",
            "username",
            "agent",
            "agentid",
            "createdby",
            "modifiedby",
            "identity",
            "subject",
            "email",
            "host",
            "session",
            "sessionid",
        )

    private val deniedFragments = listOf("claimedby", "principal", "actorid", "userid", "agentid", "holder", "identity")

    private val permittedLeaves: Set<Class<*>> =
        setOf(
            String::class.java,
            Int::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            Int::class.javaObjectType,
            Long::class.javaObjectType,
            Boolean::class.javaObjectType,
            UUID::class.java,
            Instant::class.java,
        )

    private fun isDenied(name: String): Boolean {
        val n = name.lowercase()
        return n in deniedNames || deniedFragments.any { n.contains(it) }
    }

    private fun isDomainErrorType(c: Class<*>): Boolean = c.name.startsWith("io.github.jpicklyk.mcptask.current.domain.error.")

    private fun dataFields(c: Class<*>): List<Field> = c.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }

    private fun walk(
        c: Class<*>,
        seen: MutableSet<Class<*>>,
        visit: (Class<*>, Field) -> Unit,
    ) {
        if (!seen.add(c)) return
        for (f in dataFields(c)) {
            visit(c, f)
            for (t in leafTypes(f.genericType)) {
                if (isDomainErrorType(t) && !t.isEnum) walk(t, seen, visit)
            }
        }
    }

    private fun leafTypes(t: Type): List<Class<*>> =
        when (t) {
            is Class<*> -> listOf(t)
            is ParameterizedType -> listOf(t.rawType as Class<*>) + t.actualTypeArguments.flatMap { leafTypes(it) }
            else -> listOf(Any::class.java)
        }

    private fun allDetailClasses(): List<Class<*>> = ErrorCode.entries.mapNotNull { it.detailClass?.java }

    private fun allFieldsWalked(): List<Pair<Class<*>, Field>> {
        val out = mutableListOf<Pair<Class<*>, Field>>()
        val seen = mutableSetOf<Class<*>>()
        for (c in allDetailClasses()) walk(c, seen) { owner, f -> out += owner to f }
        return out
    }

    @Test
    fun `S15 walk covers all 23 detail classes and their nested value types`() {
        val owners = allFieldsWalked().map { it.first.simpleName }.toSet()
        assertEquals(23, allDetailClasses().size)
        for (nested in listOf("FieldViolation", "MissingNote", "Blocker", "ResourceRef", "ConfigViolation")) {
            assertTrue(nested in owners, "nested value type $nested was not walked")
        }
        assertTrue(allFieldsWalked().size >= 40, "walk looks too small: ${allFieldsWalked().size}")
    }

    @Test
    fun `S15 every detail field name is in the allowlist`() {
        val offenders = allFieldsWalked().filter { it.second.name !in allowedNames }.map { "${it.first.simpleName}.${it.second.name}" }
        assertTrue(offenders.isEmpty(), "fields outside the allowlist: $offenders")
    }

    @Test
    fun `S15 no detail field name matches the principal identity denylist`() {
        val offenders = allFieldsWalked().filter { isDenied(it.second.name) }.map { "${it.first.simpleName}.${it.second.name}" }
        assertTrue(offenders.isEmpty(), "identity-like fields: $offenders")
    }

    @Test
    fun `S15 allowlist and denylist are disjoint`() {
        val clash = allowedNames.filter { isDenied(it) }
        assertTrue(clash.isEmpty(), "allowlisted names that are denied: $clash")
    }

    @Test
    fun `S15 every detail field has a permitted leaf type`() {
        val offenders = mutableListOf<String>()
        for ((owner, f) in allFieldsWalked()) {
            for (leaf in leafTypes(f.genericType)) {
                val ok =
                    leaf in permittedLeaves ||
                        List::class.java.isAssignableFrom(leaf) ||
                        (isDomainErrorType(leaf))
                if (!ok) offenders += "${owner.simpleName}.${f.name}: ${leaf.name}"
            }
        }
        assertTrue(offenders.isEmpty(), "disallowed field types (free-form maps and objects can smuggle identities): $offenders")
    }

    @Test
    fun `S15 no detail field is a map`() {
        val offenders = allFieldsWalked().filter { (_, f) -> leafTypes(f.genericType).any { Map::class.java.isAssignableFrom(it) } }
        assertTrue(offenders.isEmpty(), "map-typed fields: ${offenders.map { it.second.name }}")
    }

    @Test
    fun `S15 fix template slot names are allowlisted and not identity-like`() {
        for (code in ErrorCode.entries) {
            for (slot in ErrorFixTemplates.slots(code)) {
                assertTrue(slot in allowedNames, "slot $slot of $code is not allowlisted")
                assertTrue(!isDenied(slot), "slot $slot of $code is identity-like")
            }
        }
    }

    @Test
    fun `S15 claim_held detail carries exactly itemId expiresAt and retryAfterMs`() {
        val names = dataFields(ErrorDetail.ClaimHeld::class.java).map { it.name }.toSet()
        assertEquals(setOf("itemId", "expiresAt", "retryAfterMs"), names)
    }
}
