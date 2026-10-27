// Import for side effect from any test file that spawns a hook or script with the inherited environment.
// apiBaseUrl falls back to the orchestrator MCP registration in ~/.claude.json (CLAUDE_CONFIG_DIR), so a
// developer's real registration must never be visible to a spawned hook (it would resolve a REST base and
// make live calls). Pinning CLAUDE_CONFIG_DIR to an empty temp dir hides it; os.homedir() is only the
// fallback when CLAUDE_CONFIG_DIR is unset or empty, so HOME/USERPROFILE need no pinning here.
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const dir = mkdtempSync(join(tmpdir(), 'to-hermetic-claude-'));
process.env.CLAUDE_CONFIG_DIR = dir;
process.on('exit', () => {
  try {
    rmSync(dir, { recursive: true, force: true });
  } catch {
    // best effort
  }
});
