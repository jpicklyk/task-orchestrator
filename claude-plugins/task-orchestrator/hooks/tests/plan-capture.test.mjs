// Direct unit coverage for plan-capture.mjs's parseRootId — previously only inferable through
// session-start.mjs's subprocess tests (the two parsers are textually identical). Importing the
// module must NOT trigger a synchronous stdin read as a side effect — plan-capture.mjs guards
// its `main()` invocation behind an entrypoint check (`process.argv[1] === this file`)
// specifically so importing `parseRootId` here stays side-effect free.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseRootId } from '../plan-capture.mjs';

test('parseRootId: resolves rootId from a project: block', () => {
  const content = 'project:\n  rootId: "abc-123"\n  name: "X"\n';
  assert.equal(parseRootId(content), 'abc-123');
});

test('parseRootId: tolerates a column-0 comment above rootId', () => {
  const content = [
    'project:',
    '# a stray column-0 comment sitting right above rootId',
    '  rootId: "root-comment-check"',
  ].join('\n');
  assert.equal(parseRootId(content), 'root-comment-check');
});

test('parseRootId: null when project: block is absent', () => {
  assert.equal(parseRootId('retrospective:\n  mode: nudge\n'), null);
  assert.equal(parseRootId(null), null);
});

test('parseRootId: block-only — ignores an inline project: { ... } form', () => {
  assert.equal(parseRootId('project: { rootId: abc }\n'), null);
});
