// Server version / feature compatibility check. Pure at import; never throws.
import { wellKnown } from './api-client.mjs';
import { createMarkerOnce, sessionMarkerPath } from './markers.mjs';

const VERSION_RE = /^(\d+)(?:[.+-]|$)/;

/** null when compatible (major 4 and the `seats` feature) or the doc is not an object; else a one-line warning. */
export function assessServer(doc) {
  try {
    if (doc === null || typeof doc !== 'object' || Array.isArray(doc)) return null;
    const version = typeof doc.version === 'string' ? doc.version : null;
    const m = version ? VERSION_RE.exec(version) : null;
    const majorOk = !!m && Number(m[1]) === 4;
    const featuresOk = Array.isArray(doc.features) && doc.features.includes('seats');
    if (majorOk && featuresOk) return null;
    const shown = (version || 'unknown').replace(/[\r\n]+/g, ' ');
    const why = featuresOk ? '' : ' without the seats feature';
    return 'Task Orchestrator server reports version ' + shown + why + '; this plugin expects a 4.x server with seats. Upgrade the server or the plugin.';
  } catch {
    return null;
  }
}

export async function checkServer(opts) {
  try {
    return assessServer(await wellKnown(opts));
  } catch {
    return null;
  }
}

/** One-line warning the first time a 3.x-shaped result is seen in a session, then null. */
export function legacyShapeWarningOnce(sessionId) {
  try {
    if (!createMarkerOnce(sessionMarkerPath(sessionId, 'legacy-shape-warned'))) return null;
    return 'Task Orchestrator returned a 3.x-shaped result; this plugin expects the 4.0 envelope. Upgrade the server or the plugin.';
  } catch {
    return null;
  }
}
