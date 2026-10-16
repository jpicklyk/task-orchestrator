// Hook stdin reader. Pure at import; never throws.
import { readFileSync } from 'fs';

/** Parse hook input text to a plain object (one leading BOM stripped), or null when empty, invalid, or not a plain object. */
export function parseHookInput(text) {
  try {
    if (typeof text !== 'string') return null;
    let t = text;
    if (t.charCodeAt(0) === 0xfeff) t = t.slice(1);
    if (t.trim() === '') return null;
    const parsed = JSON.parse(t);
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
    return parsed;
  } catch {
    return null;
  }
}

/** Parsed hook input from fd 0, or null on a read error or unusable input. */
export function readHookInput() {
  let text;
  try {
    text = readFileSync(0, 'utf-8');
  } catch {
    return null;
  }
  return parseHookInput(text);
}
