// Decides whether this session is a knowledge session: one whose Task Orchestrator connection serves
// the knowledge tool profile, or whose root config declares only lifecycle-free types. Hooks that
// carry build-process text (phases, gates, dispatch, plan mode, bundled build rules) stay silent or
// switch text in such a session; callers ask one boolean.
//
// Pure module: no I/O at import time, and no function here throws. `isKnowledgeSession` reads the
// registrations from disk (through mcp-registrations.mjs) only when the caller passes none. Any
// failure answers false, which is the hooks' behaviour before this module existed.

import { isOrchestratorServerKey } from '../registration.mjs';
import { readSection, childBlock, scalar } from '../yaml-lite.mjs';
import { collectOrchestratorRegistrations } from './mcp-registrations.mjs';

/**
 * The tool profile a set of orchestrator registrations serves: `orchestration` when any row under an
 * orchestrator key has no auxiliary profile (its tools are then served, so it wins in a mixed
 * registration), else `knowledge`, else `director`; null when no row qualifies. Never throws.
 */
export function profileOfRegistrations(rows) {
  try {
    if (!Array.isArray(rows)) return null;
    const own = rows.filter((r) => r && typeof r === 'object' && isOrchestratorServerKey(r.key));
    if (own.some((r) => r.profile == null)) return 'orchestration';
    if (own.some((r) => r.profile === 'knowledge')) return 'knowledge';
    if (own.some((r) => r.profile === 'director')) return 'director';
    return null;
  } catch {
    return null;
  }
}

const TYPE_HEADER_RE = /^["']?([^"'#:\s][^"'#:]*?)["']?\s*:\s*(.*)$/;

function indentOf(line) {
  return line.length - line.trimStart().length;
}

function isContent(line) {
  const t = line.trim();
  return t !== '' && !t.startsWith('#');
}

/**
 * True when the top-level `work_item_schemas:` block declares at least one type and every type's
 * first-level `lifecycle` is exactly `none` (lowercase, as the server accepts it; quotes and a
 * trailing comment allowed). An absent block, no types, an inline-form type, a type without
 * `lifecycle` or any other value answers false. Never throws.
 */
export function isLifecycleFreeConfig(configText) {
  try {
    if (typeof configText !== 'string' || configText === '') return false;
    const section = readSection(configText, 'work_item_schemas', { blockOnly: true });
    if (!section || !Array.isArray(section.lines)) return false;
    const lines = section.lines.map((l) => l.replace(/\r$/, ''));
    let firstLevel = Infinity;
    for (const line of lines) if (isContent(line)) firstLevel = Math.min(firstLevel, indentOf(line));
    if (firstLevel === Infinity) return false;

    let types = 0;
    for (const line of lines) {
      if (!isContent(line) || indentOf(line) !== firstLevel) continue;
      const m = line.trim().match(TYPE_HEADER_RE);
      if (!m) return false;
      const rest = m[2].trim();
      if (rest !== '' && !rest.startsWith('#')) return false; // inline or scalar form: unreadable here
      const block = childBlock(lines, line.trim().slice(0, line.trim().indexOf(':')));
      if (!block || block.inline !== null) return false;
      if (scalar(block.lines, 'lifecycle') !== 'none') return false;
      types++;
    }
    return types > 0;
  } catch {
    return false;
  }
}

/**
 * True when the session is a knowledge session: the orchestrator registrations serve the knowledge
 * profile, or the located config is lifecycle-free. `registrations` defaults to the discoverable
 * registrations for `cwd`/`env`. Any throw answers false.
 */
export function isKnowledgeSession({ configText, registrations, cwd = process.cwd(), env = process.env } = {}) {
  try {
    if (isLifecycleFreeConfig(configText)) return true;
    const rows = registrations ?? collectOrchestratorRegistrations({ cwd, env });
    return profileOfRegistrations(rows) === 'knowledge';
  } catch {
    return false;
  }
}
