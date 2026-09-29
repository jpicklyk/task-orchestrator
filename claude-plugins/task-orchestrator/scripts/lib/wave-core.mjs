// wave-core.mjs — loads B1's implement-wave.js core region so Method B (run-exec-lib.mjs)
// calls the SAME seatPrompt/scanDeclarations/mapStageResult/etc bytes as Method A (the
// Workflow script). This is the harness technique from scripts/tests/workflow-harness.mjs
// (loadCore), duplicated here (not imported — the harness lives under scripts/tests/ and
// this is production code) so wave-core.mjs and the harness stay independently red-proofable.
//
// Node 22, ESM, stdlib-only. This module is the only file-reading dependency in the
// run-exec-lib/provenance-lib pair — both of those stay pure.

import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const CORE_BEGIN = '// @core-begin';
const CORE_END = '// @core-end';

/**
 * The exact 18 names the @core-begin/@core-end region of workflows/implement-wave.js
 * exports, in B1's own order (scripts/tests/workflow-harness.mjs CORE_EXPORT_NAMES).
 * Kept as a literal copy (not imported) — the harness lives under scripts/tests/, out of
 * production code's reach.
 */
export const CORE_NAMES = [
  'ENVELOPE_VERSION', 'OUTPUT_SCHEMAS', 'normalizeArgs', 'normalizePath', 'preflight',
  'makeMilestones', 'makeLocks', 'lockKeysFor', 'overlapDeferral', 'envelopeSchema',
  'mapEntry', 'mapStageResult', 'seatActor', 'seatPrompt', 'handoff', 'runItem', 'runPlan',
  'scanDeclarations',
]

/**
 * loadWaveCore({pluginRoot} = {}) -> core object with exactly the CORE_NAMES keys.
 * pluginRoot defaults to the task-orchestrator plugin root (two levels up from this file,
 * scripts/lib/ -> scripts/ -> plugin root). Missing script -> 'implement-wave.js not found
 * at <path>'; missing @core-begin/@core-end markers -> 'markers not found in <path>'; a core
 * missing any declared export -> 'core lacks <name>'.
 */
export function loadWaveCore({ pluginRoot } = {}) {
  const root = pluginRoot || resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')
  const path = join(root, 'workflows', 'implement-wave.js')

  let text
  try {
    text = readFileSync(path, 'utf8')
  } catch {
    throw new Error(`implement-wave.js not found at ${path}`)
  }

  const start = text.indexOf(CORE_BEGIN)
  const end = text.indexOf(CORE_END)
  if (start === -1 || end === -1 || end < start) {
    throw new Error(`markers not found in ${path}`)
  }

  const slice = text.slice(start + CORE_BEGIN.length, end)
  const builder = new Function(`${slice}\nreturn {${CORE_NAMES.join(',')}};`)
  const core = builder()

  for (const name of CORE_NAMES) {
    if (core[name] === undefined) throw new Error(`core lacks ${name}`)
  }

  return core
}
