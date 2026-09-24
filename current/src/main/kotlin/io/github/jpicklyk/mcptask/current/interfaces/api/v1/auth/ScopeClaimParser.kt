package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import java.util.UUID

/**
 * Parses the `scope` / `to_scope` claim shared by the bearer-token secret file and JWKS JWTs
 * into an [ApiScope], failing closed on any malformed shape instead of silently widening access.
 *
 * The raw input is untyped ([Any]?) because both callers reduce to the same Map/List/scalar
 * shapes: the bearer YAML loader passes the raw, UNCAST `tokenMap["scope"]` value, and the JWKS
 * verifier passes the JSON object from `claims.getJSONObjectClaim("to_scope")`. Callers must not
 * pre-cast with `as? Map`: that turned a scalar `scope:` into null, i.e. unrestricted.
 *
 * Rules (frozen by the planning seat's diagnosis note, decisions D2-D5, D10):
 * - `null` (absent) scope means fully unrestricted: `ApiScope(rootIds = null, tagsInclude = emptySet())`.
 * - A non-null scope that is not a `Map` is malformed.
 * - Any key inside the scope map other than `root_ids` / `tags_include` is malformed — every
 *   scope key is a restriction (see [ApiPrincipal]), so silently ignoring an unrecognized key
 *   (e.g. a `root_id` typo) would silently widen access.
 * - `root_ids`: null/absent -> unrestricted (`null`). A `List<String>` where every element parses
 *   via [UUID.fromString] -> that set. Anything else (scalar, empty list, non-string element,
 *   null element, invalid UUID) is malformed. No coercion, no narrowing of a mixed list.
 * - `tags_include`: null/absent -> `emptySet()`. A `List<String>` where every element is
 *   non-blank -> that set (kept verbatim, no trim/case-fold). `[]` -> `emptySet()` (no tag
 *   constraint; this is the canonical, documented form). Anything else (scalar, non-string
 *   element, blank element) is malformed.
 *
 * @throws IllegalArgumentException naming the offending key path (`scope`, `scope.root_ids`, or
 *   `scope.tags_include`, or an unknown key name). The message never includes credential
 *   material (callers must not pass credential values into this parser).
 */
internal object ScopeClaimParser {
    private val ALLOWED_KEYS = setOf("root_ids", "tags_include")

    fun parse(raw: Any?): ApiScope {
        if (raw == null) {
            return ApiScope(rootIds = null, tagsInclude = emptySet())
        }

        val map =
            raw as? Map<*, *>
                ?: throw IllegalArgumentException(
                    "scope must be a mapping with optional 'root_ids'/'tags_include' keys, got: " +
                        raw::class.simpleName,
                )

        val unknownKeys = map.keys.mapNotNull { it as? String }.filter { it !in ALLOWED_KEYS }
        val nonStringKeys = map.keys.filter { it !is String }
        if (unknownKeys.isNotEmpty() || nonStringKeys.isNotEmpty()) {
            val bad = (unknownKeys + nonStringKeys.map { it.toString() }).joinToString(", ")
            throw IllegalArgumentException(
                "scope has unknown key(s): $bad. Only 'root_ids' and 'tags_include' are allowed.",
            )
        }

        val rootIds = parseRootIds(map["root_ids"])
        val tagsInclude = parseTagsInclude(map["tags_include"])

        return ApiScope(rootIds = rootIds, tagsInclude = tagsInclude)
    }

    private fun parseRootIds(raw: Any?): Set<UUID>? {
        if (raw == null) return null

        val list =
            raw as? List<*>
                ?: throw IllegalArgumentException(
                    "scope.root_ids must be a list of UUID strings (or omitted/null for unrestricted), got: " +
                        raw::class.simpleName,
                )

        if (list.isEmpty()) {
            throw IllegalArgumentException(
                "scope.root_ids must not be an empty list — omit the key (or set it to null) for unrestricted access.",
            )
        }

        return list
            .map { element ->
                val str =
                    element as? String
                        ?: throw IllegalArgumentException(
                            "scope.root_ids contains a non-string element: '$element'.",
                        )
                try {
                    UUID.fromString(str)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "scope.root_ids contains an invalid UUID '$str': ${e.message}",
                    )
                }
            }.toSet()
    }

    private fun parseTagsInclude(raw: Any?): Set<String> {
        if (raw == null) return emptySet()

        val list =
            raw as? List<*>
                ?: throw IllegalArgumentException(
                    "scope.tags_include must be a list of strings (or omitted/null), got: " +
                        raw::class.simpleName +
                        ". If this came from unquoted YAML (e.g. 'yes'/'on'/'no'), quote it.",
                )

        return list
            .map { element ->
                val str =
                    element as? String
                        ?: throw IllegalArgumentException(
                            "scope.tags_include contains a non-string element: '$element'. " +
                                "If this came from unquoted YAML (e.g. 'yes'/'on'/'no'), quote it.",
                        )
                if (str.isBlank()) {
                    throw IllegalArgumentException("scope.tags_include contains a blank tag.")
                }
                str
            }.toSet()
    }
}
