package io.github.jpicklyk.mcptask.current.infrastructure.identity

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import java.util.UUID

/**
 * Parses the `scope` / `to_scope` claim shared by the bearer-token secret file and JWKS JWTs
 * into an [AccessScope], failing closed on any malformed shape instead of silently widening access.
 *
 * The input is the CONTAINER that holds the claim plus the claim's key, not the claim's value: a
 * value alone cannot tell an absent key from a present-but-null one, and only an absent key may mean
 * unrestricted. The bearer YAML loader passes the token entry map with `"scope"`, the JWKS verifier
 * passes `claims.claims` with `"to_scope"`.
 *
 * Rules:
 * - The key is absent: [AccessScope.unrestricted]. This is the only way to be unrestricted.
 * - The key is present with a null value, a non-`Map` value or an empty map: malformed. A present
 *   scope is a restriction, so an empty one is an operator mistake, never a widening.
 * - Any key inside the scope map other than `root_ids` / `tags_include` is malformed: every scope
 *   key is a restriction (see [AccessScope]), so silently ignoring an unrecognized key (e.g. a
 *   `root_id` typo) would silently widen access.
 * - `root_ids`: absent -> unrestricted (`null`). A `List<String>` where every element parses via
 *   [UUID.fromString] -> that set. Anything else (null, scalar, empty list, non-string element,
 *   null element, invalid UUID) is malformed. No coercion, no narrowing of a mixed list.
 * - `tags_include`: absent -> `emptySet()` (no tag constraint). A `List<String>` where every
 *   element is non-blank -> that set (kept verbatim, no trim/case-fold). Anything else (null,
 *   scalar, empty list, non-string element, blank element) is malformed.
 *
 * @throws IllegalArgumentException starting with the offending key path (`scope`, `scope.root_ids`,
 *   `scope.tags_include`) and ending with the remedy (`delete the key`) where one applies. The
 *   message never includes credential material.
 */
internal object ScopeClaimParser {
    private val ALLOWED_KEYS = setOf("root_ids", "tags_include")

    fun parse(
        container: Map<*, *>,
        key: String,
    ): AccessScope {
        if (!container.containsKey(key)) {
            return AccessScope.unrestricted()
        }

        val raw =
            container[key]
                ?: throw IllegalArgumentException(
                    "$key must not be null: a present scope is a restriction. To grant unrestricted access, delete the key.",
                )
        val map =
            raw as? Map<*, *>
                ?: throw IllegalArgumentException(
                    "$key must be a mapping with optional 'root_ids'/'tags_include' keys, got: " +
                        raw::class.simpleName +
                        ". To grant unrestricted access, delete the key.",
                )
        if (map.isEmpty()) {
            throw IllegalArgumentException(
                "$key must not be empty: a present scope is a restriction. To grant unrestricted access, delete the key.",
            )
        }

        val unknownKeys = map.keys.mapNotNull { it as? String }.filter { it !in ALLOWED_KEYS }
        val nonStringKeys = map.keys.filter { it !is String }
        if (unknownKeys.isNotEmpty() || nonStringKeys.isNotEmpty()) {
            val bad = (unknownKeys + nonStringKeys.map { it.toString() }).joinToString(", ")
            throw IllegalArgumentException(
                "$key has unknown key(s): $bad. Only 'root_ids' and 'tags_include' are allowed.",
            )
        }

        val rootIds = parseRootIds(map, key)
        val tagsInclude = parseTagsInclude(map, key)

        return AccessScope.principal(rootIds = rootIds, tagsInclude = tagsInclude)
    }

    private fun parseRootIds(
        scope: Map<*, *>,
        scopeKey: String,
    ): Set<UUID>? {
        val path = "$scopeKey.root_ids"
        if (!scope.containsKey("root_ids")) return null

        val list =
            scope["root_ids"] as? List<*>
                ?: throw IllegalArgumentException(
                    "$path must be a list of UUID strings, got: " +
                        (scope["root_ids"]?.let { it::class.simpleName } ?: "null") +
                        ". To leave root ids unrestricted, delete the key.",
                )

        if (list.isEmpty()) {
            throw IllegalArgumentException(
                "$path must not be an empty list. To leave root ids unrestricted, delete the key.",
            )
        }

        return list
            .map { element ->
                val str =
                    element as? String
                        ?: throw IllegalArgumentException(
                            "$path contains a non-string element: '$element'.",
                        )
                try {
                    UUID.fromString(str)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "$path contains an invalid UUID '$str': ${e.message}",
                    )
                }
            }.toSet()
    }

    private fun parseTagsInclude(
        scope: Map<*, *>,
        scopeKey: String,
    ): Set<String> {
        val path = "$scopeKey.tags_include"
        if (!scope.containsKey("tags_include")) return emptySet()

        val list =
            scope["tags_include"] as? List<*>
                ?: throw IllegalArgumentException(
                    "$path must be a list of strings, got: " +
                        (scope["tags_include"]?.let { it::class.simpleName } ?: "null") +
                        ". To leave tags unrestricted, delete the key. " +
                        "If this came from unquoted YAML (e.g. 'yes'/'on'/'no'), quote it.",
                )

        if (list.isEmpty()) {
            throw IllegalArgumentException(
                "$path must not be an empty list. To leave tags unrestricted, delete the key.",
            )
        }

        return list
            .map { element ->
                val str =
                    element as? String
                        ?: throw IllegalArgumentException(
                            "$path contains a non-string element: '$element'. " +
                                "If this came from unquoted YAML (e.g. 'yes'/'on'/'no'), quote it.",
                        )
                if (str.isBlank()) {
                    throw IllegalArgumentException("$path contains a blank tag.")
                }
                str
            }.toSet()
    }
}
