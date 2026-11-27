package io.github.jpicklyk.mcptask.current.run.internal.wire

/** The doc prefix of a deprecated `runs_write` decision op: names the `decisions_write` op that replaces it (ADR 0005). */
internal fun writeAliasDoc(
    op: String,
    doc: String
): String = "Deprecated (removed in 5.0): use decisions_write $op. $doc"

/** The doc prefix of a deprecated `runs_read` decision select: names `decisions_read list`, the one read that replaces it. */
internal fun readAliasDoc(doc: String): String = "Deprecated (removed in 5.0): use decisions_read list. $doc"
