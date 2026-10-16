// Contract test for hooks/lib/tool-names.mjs (fc131c71, scenario S20). Oracle: the frozen task-scope
// list and the basenames of current/src/test/resources/golden/tools/*.json (one file per
// <tool>.<profile>.json; the tool name is the part before the first dot).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { TOOLS } from '../lib/tool-names.mjs';

const GOLDEN_DIR = fileURLToPath(new URL('../../../../current/src/test/resources/golden/tools/', import.meta.url));

const FROZEN = [
  'advance', 'config', 'deps_read', 'deps_write', 'docs_read', 'docs_write', 'items_read', 'items_write',
  'notes_read', 'notes_write', 'report', 'runs_read', 'runs_write',
];

test('S20: TOOLS equals the sorted distinct tool names of the golden tool files', () => {
  const derived = [...new Set(readdirSync(GOLDEN_DIR).filter((f) => f.endsWith('.json')).map((f) => f.split('.')[0]))].sort();
  assert.equal(derived.length, 13, 'the golden directory defines 13 tools');
  assert.deepEqual([...TOOLS], derived);
});

test('S20: TOOLS equals the frozen task-scope list, in order', () => {
  assert.deepEqual([...TOOLS], FROZEN);
});

test('S20: TOOLS is a frozen array, sorted, without duplicates', () => {
  assert.ok(Array.isArray(TOOLS));
  assert.ok(Object.isFrozen(TOOLS));
  assert.deepEqual([...TOOLS], [...TOOLS].sort());
  assert.equal(new Set(TOOLS).size, TOOLS.length);
  assert.throws(() => { TOOLS.push('x'); }, TypeError);
  assert.equal(TOOLS.length, 13);
});

test('S20 probe: every entry is a valid callOp tool name ([a-z][a-z_]*)', () => {
  for (const t of TOOLS) assert.match(t, /^[a-z][a-z_]*$/);
});
