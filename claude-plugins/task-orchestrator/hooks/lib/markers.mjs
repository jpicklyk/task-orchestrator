// Temp-dir marker files shared by the hooks. Pure at import; every function is fail-open.
import { readFileSync, writeFileSync, mkdirSync, renameSync, rmSync, openSync, closeSync } from 'fs';
import { join, dirname } from 'path';
import os from 'os';
import { randomBytes } from 'crypto';

export function markerRoot() {
  return join(os.tmpdir(), 'task-orchestrator');
}

export function sanitizeKey(v) {
  return String(v).replace(/[^a-zA-Z0-9-]/g, '_');
}

/** Per-session marker: <root>/<session>/<name>[-<agent>].json */
export function sessionMarkerPath(sessionId, name, agentId) {
  const file = sanitizeKey(name) + (agentId ? '-' + sanitizeKey(agentId) : '') + '.json';
  return join(markerRoot(), sanitizeKey(sessionId || 'unknown'), file);
}

/** Cross-session marker: <root>/<name><ext> */
export function sharedMarkerPath(name, ext = '.json') {
  return join(markerRoot(), sanitizeKey(name) + ext);
}

export function readMarker(path, fallback = {}) {
  try {
    const parsed = JSON.parse(readFileSync(path, 'utf-8'));
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return fallback;
    return parsed;
  } catch {
    return fallback;
  }
}

/** Atomic write (temp file + rename). False on any error, including a Windows rename EPERM. */
export function writeMarker(path, obj) {
  const tmp = `${path}.${process.pid}.${randomBytes(4).toString('hex')}.tmp`;
  try {
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(tmp, JSON.stringify(obj));
    renameSync(tmp, path);
    return true;
  } catch {
    try {
      rmSync(tmp, { force: true });
    } catch {
      // ignore
    }
    return false;
  }
}

/** Exclusive-create an empty marker. False when it already exists or on any error. */
export function createMarkerOnce(path) {
  try {
    mkdirSync(dirname(path), { recursive: true });
    closeSync(openSync(path, 'wx'));
    return true;
  } catch {
    return false;
  }
}
