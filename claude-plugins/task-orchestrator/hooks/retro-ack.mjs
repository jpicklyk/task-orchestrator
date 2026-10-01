#!/usr/bin/env node
// CLI — manually acknowledges the retrospective marker(s), stamping handledAt = now so
// retro-trigger.mjs (and the Stop backstop) treat this run as already handled and stay silent
// through the cooldown window — this EXTENDS suppression, it does not bypass it. Useful after a
// retrospective was run out-of-band (e.g. invoked manually) and the marker should reflect that.
//
// Usage: node retro-ack.mjs
//
// Resolves .taskorchestrator/config.yaml with the shared locator (config-locator.mjs), the same
// way retro-trigger.mjs does:
//   - project scope with a rootId: only that project's marker is acked.
//   - user scope: markers are keyed by session id and this CLI has no session id, so every marker
//     in the shared marker directory whose recorded `scope` is 'user' is acked; project-keyed
//     markers are left untouched.
//   - no config, or a project config without a rootId: every marker file is acked.

import { readdirSync } from 'fs';
import { join } from 'path';
import os from 'os';
import { locateConfig } from './config-locator.mjs';
import {
  markerPath,
  readMarker,
  writeMarker,
} from './retro-lib.mjs';

function ackMarker(path) {
  const marker = readMarker(path);
  writeMarker(path, {
    ...marker,
    handledAt: Date.now(),
    sawTerminal: false,
    pendingRoots: [],
    terminalCount: 0,
  });
}

function listMarkerFiles(dir) {
  try {
    return readdirSync(dir).filter(f => f.startsWith('retro-') && f.endsWith('.json'));
  } catch {
    return [];
  }
}

try {
  const located = locateConfig();
  const dir = join(os.tmpdir(), 'task-orchestrator');

  if (located.scope === 'user') {
    let count = 0;
    for (const f of listMarkerFiles(dir)) {
      const path = join(dir, f);
      if (readMarker(path).scope === 'user') {
        ackMarker(path);
        count++;
      }
    }
    process.stdout.write(`Acked ${count} user-scope retrospective marker(s) in ${dir}.\n`);
  } else if (located.scope === 'project' && located.rootId) {
    const path = markerPath(located.rootId);
    ackMarker(path);
    process.stdout.write(`Acked retrospective marker for root ${located.rootId} (${path}).\n`);
  } else {
    const files = listMarkerFiles(dir);
    for (const f of files) {
      ackMarker(join(dir, f));
    }
    process.stdout.write(`Acked ${files.length} retrospective marker(s) in ${dir} (no project rootId configured).\n`);
  }
} catch {
  process.stdout.write('Retrospective marker ack failed silently — no markers were acknowledged.\n');
}
