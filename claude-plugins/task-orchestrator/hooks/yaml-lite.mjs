// Shared minimal-YAML section reader used by every plugin hook that reads
// .taskorchestrator/config.yaml. Pure module — no I/O, no dependencies.
//
// This is NOT a general YAML parser. It only understands the narrow subset the plugin's
// config.yaml uses: a handful of top-level `key:` blocks, each either a nested block of
// `subkey: value` lines, or (for some keys) an inline `{ subkey: value, ... }` form.
//
// The bug this module fixes (six copies of it, one per hook): the idiom
// `if (inSection && /^\S/.test(line)) inSection = false;` treats a column-0 `#` comment as
// end-of-section, silently truncating block parsing when a comment happens to sit unindented
// above (or inside) the value line it documents. Correct section exit is a line that is
// column-0, non-blank, AND not a comment — blank lines and column-0 comments stay inside.

/**
 * Locate a top-level `name:` block or inline `name: { ... }` form.
 *
 * @param {string} content Full config.yaml text.
 * @param {string} name Top-level key to find, e.g. "retrospective".
 * @param {{ blockOnly?: boolean }} [opts]
 *   blockOnly: when true, only the block form (`name:` alone on its line) is recognized — the
 *   inline `{...}` form is ignored even if present. Mirrors `project:`'s original block-only
 *   matching (session-start.mjs, config-sync.mjs, plan-capture.mjs never supported an inline
 *   `project: { ... }` form, so this preserves that).
 * @returns {{ inline: string|null, lines: string[] } | null}
 *   `inline` holds the raw interior text of a matched `{ ... }` form (null for block form).
 *   `lines` holds the raw (untrimmed) lines belonging to a matched block form (empty array for
 *   inline form). Returns null when the section header isn't found at all.
 */
export function readSection(content, name, opts = {}) {
  if (!content) return null;
  const blockOnly = !!opts.blockOnly;

  const blockHeaderRe = new RegExp(`^${name}\\s*:\\s*$`);
  // Deliberately unanchored at the end (no trailing `\s*$`) — the six original per-hook
  // parsers this module replaced were unanchored too, so an inline `{ ... }` form followed by
  // a trailing `# comment` (or any other trailing content) still matches; only the leading
  // `{...}` span is captured and anything after the closing brace is ignored.
  const inlineHeaderRe = new RegExp(`^${name}\\s*:\\s*\\{(.*)\\}`);
  const anyHeaderRe = new RegExp(`^${name}\\s*:`);

  const lines = content.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const trimmed = lines[i].trim();
    if (!anyHeaderRe.test(trimmed)) continue;

    if (!blockOnly) {
      const inlineMatch = trimmed.match(inlineHeaderRe);
      if (inlineMatch) {
        return { inline: inlineMatch[1], lines: [] };
      }
    }
    if (blockHeaderRe.test(trimmed)) {
      return { inline: null, lines: collectBlockLines(lines, i + 1) };
    }
    // Header matched `name:` but is neither block-only form nor (when allowed) inline-brace
    // form — not a section we can read. Keep scanning in case the key appears again later.
  }

  return null;
}

// Collects lines belonging to a block starting right after the header line, stopping at the
// first column-0, non-blank, non-comment line (or end of input).
function collectBlockLines(lines, startIdx) {
  const out = [];
  for (let i = startIdx; i < lines.length; i++) {
    const line = lines[i];
    const trimmed = line.trim();
    if (trimmed === '') {
      out.push(line);
      continue;
    }
    const isColumnZero = /^\S/.test(line);
    if (isColumnZero && !trimmed.startsWith('#')) break;
    out.push(line);
  }
  return out;
}

// Extracts an unquoted, comment-stripped scalar value for `key: value` from a set of block
// lines (as returned by readSection). Comment-only lines are skipped entirely — never treated
// as a candidate match. Returns null if the key isn't present in the block.
export function scalar(lines, key) {
  const re = new RegExp(`^${key}\\s*:\\s*["']?([^"'#]+?)["']?\\s*(#.*)?$`);
  for (const line of lines) {
    const trimmed = line.trim();
    if (trimmed.startsWith('#')) continue;
    const m = trimmed.match(re);
    if (m) return m[1].trim();
  }
  return null;
}

// Same extraction as `scalar`, but for the interior text of an inline `{ key: value, ... }`
// form. The value is bounded by the next comma or closing brace (or end of string).
export function inlineScalar(inline, key) {
  if (inline == null) return null;
  const re = new RegExp(`${key}\\s*:\\s*["']?([^"',}#]+?)["']?\\s*(#[^,}]*)?\\s*(?=[,}]|$)`);
  const m = inline.match(re);
  return m ? m[1].trim() : null;
}
