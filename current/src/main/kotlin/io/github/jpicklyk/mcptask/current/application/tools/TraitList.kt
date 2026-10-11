package io.github.jpicklyk.mcptask.current.application.tools

/**
 * The 3.x `traits` string parameter ("a, b,c") as a typed list: split on `,`, each element trimmed, empty elements
 * dropped, duplicates dropped keeping the first occurrence. Shared by the MCP adapters that accept the string form.
 */
internal object TraitList {
    fun parse(raw: String?): List<String> =
        raw
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()
}
