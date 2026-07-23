#!/usr/bin/env node
// CLI — manually acknowledges the retrospective marker(s), stamping handledAt = now so
// retro-trigger.mjs (and the Stop backstop) treat this run as already handled and stay silent
// through the cooldown window — this EXTENDS suppression, it does not bypass it. Useful after a
// retrospective was run out-of-band (e.g. invoked manually) and the marker should reflect that.
//
// Usage: node retro-ack.mjs
//
// Resolves .taskorchestrator/config.yaml the same way retro-trigger.mjs does. When a project
// rootId is configured, only that project's marker is acked. Without a rootId — this CLI has no
// session_id to key off of — every marker file in the shared marker directory is acked instead.

import { readdirSync } from 'fs';
import { join } from 'path';
import os from 'os';
import {
  findConfigContent,
  parseProjectRootId,
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

try {
  const configContent = findConfigContent();
  const rootId = parseProjectRootId(configContent);

  if (rootId) {
    const path = markerPath(rootId);
    ackMarker(path);
    process.stdout.write(`Acked retrospective marker for root ${rootId} (${path}).\n`);
  } else {
    const dir = join(os.tmpdir(), 'task-orchestrator');
    let files = [];
    try {
      files = readdirSync(dir).filter(f => f.startsWith('retro-') && f.endsWith('.json'));
    } catch {
      files = [];
    }

    for (const f of files) {
      ackMarker(join(dir, f));
    }

    process.stdout.write(`Acked ${files.length} retrospective marker(s) in ${dir} (no project rootId configured).\n`);
  }
} catch {
  process.stdout.write('Retrospective marker ack failed silently — no markers were acknowledged.\n');
}
