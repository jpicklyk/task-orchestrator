package io.github.jpicklyk.mcptask.current.application.knowledge.link

import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind

/** One `[[...]]` reference found in a body. */
sealed interface LinkTarget {
    /** The text stored in `links.to_ref`: the target as written, without its anchor. */
    val toRef: String

    /** The fragment after `#`, or null. */
    val anchor: String?
}

/**
 * A reference to a document by [slug], optionally restricted to one [kind] (`[[plan:x]]`); a null [kind] means "the
 * bare slug", which resolves by precedence PAGE > PLAN > RULE.
 */
data class DocumentTarget(
    val kind: DocumentKind?,
    val slug: String,
    override val anchor: String?
) : LinkTarget {
    override val toRef: String get() = if (kind == null) slug else "${kind.toDbValue()}:$slug"
}

/** A reference to a work item (`[[item:<id>]]`): [id] is a canonical UUID or 8..32 hex digits, as written. */
data class ItemTarget(
    val id: String
) : LinkTarget {
    override val anchor: String? get() = null
    override val toRef: String get() = "item:$id"
}

/**
 * Extracts `[[...]]` references from a markdown body. Pure and stateless.
 *
 * Grammar (task-scope D1-D3): a target is the text between `[[` and the next `]]` on one line, trimmed; an empty
 * target, or one containing `[` or `]`, is no link. A prefix `plan:`, `rule:`, `page:` or `item:` (case-insensitive)
 * selects the kind; any other `x:` prefix is no link. For documents the target is split once at `#` into slug and
 * anchor (an empty anchor is null). An `item:` target must be a canonical UUID or 8..32 hex digits. Slugs are
 * case-sensitive. Text inside inline code spans and fenced blocks (``` or ~~~) is ignored. One target is kept per
 * `(kind, slug)` or item id (the first occurrence, with its anchor), and at most [MAX_LINK_TARGETS] targets are
 * returned, in document order.
 */
object LinkParser {
    /** The most distinct targets one source can carry. */
    const val MAX_LINK_TARGETS = 256

    private const val OPEN = "[["
    private const val CLOSE = "]]"

    /** The distinct targets of [body], in document order. */
    fun parse(body: String): List<LinkTarget> {
        if (!body.contains(OPEN)) return emptyList()
        val found = LinkedHashMap<String, LinkTarget>()
        var fenceChar = ' '
        var fenceLength = 0
        for (rawLine in body.lineSequence()) {
            val trimmed = rawLine.trimStart()
            if (fenceLength > 0) {
                if (closesFence(trimmed, fenceChar, fenceLength)) fenceLength = 0
                continue
            }
            val opening = fenceRun(trimmed)
            if (opening != null) {
                fenceChar = opening.first
                fenceLength = opening.second
                continue
            }
            if (!rawLine.contains(OPEN)) continue
            scanLine(maskCodeSpans(rawLine), found)
            if (found.size >= MAX_LINK_TARGETS) break
        }
        return found.values.toList()
    }

    private fun scanLine(
        line: String,
        found: MutableMap<String, LinkTarget>
    ) {
        var from = 0
        while (found.size < MAX_LINK_TARGETS) {
            val open = line.indexOf(OPEN, from)
            if (open < 0) return
            val close = line.indexOf(CLOSE, open + OPEN.length)
            if (close < 0) return
            val inner = line.substring(open + OPEN.length, close)
            val target = if (inner.contains('[') || inner.contains(']')) null else parseTarget(inner.trim())
            if (target == null) {
                from = open + 1
                continue
            }
            found.putIfAbsent(identityOf(target), target)
            from = close + CLOSE.length
        }
    }

    private fun identityOf(target: LinkTarget): String =
        when (target) {
            is DocumentTarget -> "d:${target.kind?.toDbValue().orEmpty()}:${target.slug}"
            is ItemTarget -> "i:${target.id.replace("-", "").lowercase()}"
        }

    private fun parseTarget(text: String): LinkTarget? {
        if (text.isEmpty()) return null
        val hash = text.indexOf('#')
        val head = if (hash < 0) text else text.substring(0, hash)
        val colon = head.indexOf(':')
        var kind: DocumentKind? = null
        var isItem = false
        var rest = text
        if (colon >= 0) {
            when (head.substring(0, colon).lowercase()) {
                "plan" -> kind = DocumentKind.PLAN
                "rule" -> kind = DocumentKind.RULE
                "page" -> kind = DocumentKind.PAGE
                "item" -> isItem = true
                else -> return null
            }
            rest = text.substring(colon + 1)
        }
        if (isItem) {
            val id = rest.trim()
            return if (IdRef.parse(id) != null) ItemTarget(id) else null
        }
        val rawHash = rest.indexOf('#')
        val slug = (if (rawHash < 0) rest else rest.substring(0, rawHash)).trim()
        if (slug.isEmpty()) return null
        val anchor = if (rawHash < 0) null else rest.substring(rawHash + 1).trim().ifEmpty { null }
        return DocumentTarget(kind, slug, anchor)
    }

    /** The fence character and length when [trimmed] opens a fenced block, else null. */
    private fun fenceRun(trimmed: String): Pair<Char, Int>? {
        val c = trimmed.firstOrNull() ?: return null
        if (c != '`' && c != '~') return null
        val n = trimmed.takeWhile { it == c }.length
        if (n < 3) return null
        // A backtick fence's info string may not contain a backtick (otherwise it is an inline span).
        if (c == '`' && trimmed.substring(n).contains('`')) return null
        return c to n
    }

    private fun closesFence(
        trimmed: String,
        fenceChar: Char,
        fenceLength: Int
    ): Boolean {
        val n = trimmed.takeWhile { it == fenceChar }.length
        return n >= fenceLength && trimmed.substring(n).isBlank()
    }

    /** [line] with every inline code span (backtick run to the next run of equal length) replaced by spaces. */
    private fun maskCodeSpans(line: String): String {
        if (!line.contains('`')) return line
        val out = StringBuilder(line)
        var i = 0
        while (i < line.length) {
            if (line[i] != '`') {
                i++
                continue
            }
            var runEnd = i
            while (runEnd < line.length && line[runEnd] == '`') runEnd++
            val runLength = runEnd - i
            var j = runEnd
            var closeStart = -1
            while (j < line.length) {
                if (line[j] != '`') {
                    j++
                    continue
                }
                var k = j
                while (k < line.length && line[k] == '`') k++
                if (k - j == runLength) {
                    closeStart = j
                    break
                }
                j = k
            }
            if (closeStart < 0) {
                i = runEnd
                continue
            }
            for (p in i until closeStart + runLength) out.setCharAt(p, ' ')
            i = closeStart + runLength
        }
        return out.toString()
    }
}
