import './hermetic-claude-config.mjs';
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, existsSync, realpathSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join, isAbsolute, dirname } from 'node:path';
import { directorMarkerPath } from '../execution-mode.mjs';
import { RETIRED_3X_TOOLS } from '../../scripts/lib/contract-checks.mjs';

const HOOK = fileURLToPath(new URL('../session-start.mjs', import.meta.url));

function writeConfig(dir, content) {
  const cfgDir = join(dir, '.taskorchestrator');
  mkdirSync(cfgDir, { recursive: true });
  writeFileSync(join(cfgDir, 'config.yaml'), content, 'utf-8');
}

const trackedDirs = [];
function trackedTmp(prefix) {
  const d = mkdtempSync(join(tmpdir(), prefix));
  trackedDirs.push(d);
  return d;
}
after(() => {
  for (const d of trackedDirs) rmSync(d, { recursive: true, force: true });
});

function runHook(agentConfigDir, extraEnv = {}) {
  // Isolate HOME/USERPROFILE (os.homedir() honors both) so the registration self-check reads a
  // controlled ~/.claude.json instead of the developer's real one — otherwise results become
  // machine-dependent. Tests that want a specific ~/.claude.json pass homeDir via extraEnv.
  const homeDir = extraEnv.homeDir || agentConfigDir;
  // Hermeticity (Ruling 2): TASK_ORCHESTRATOR_HOME is pinned to its OWN fresh empty dir (never the
  // fixture / AGENT_CONFIG_DIR / HOME dir) so the real home never yields a user-scope hit; TEMP/TMP
  // are a fresh empty dir so the setup-hint marker is isolated. Callers may supply their own.
  const toHome = extraEnv.toHome || trackedTmp('to-session-start-tohome-');
  const tmpDir = extraEnv.tmpDir || trackedTmp('to-session-start-tmp-');
  return spawnSync(process.execPath, [HOOK], {
    env: {
      ...process.env,
      AGENT_CONFIG_DIR: agentConfigDir,
      HOME: homeDir,
      USERPROFILE: homeDir,
      CLAUDE_CONFIG_DIR: '',
      TASK_ORCHESTRATOR_HOME: toHome,
      TEMP: tmpDir,
      TMP: tmpDir,
      TMPDIR: tmpDir,
      TASK_ORCHESTRATOR_API_URL: '',
      TASK_ORCHESTRATOR_SETUP_HINT: '',
      ...extraEnv.env,
    },
    encoding: 'utf-8',
    input: extraEnv.input,
    cwd: agentConfigDir, // avoid the cwd-walk fallback finding this repo's real config.yaml
  });
}

function tmpConfigDir() {
  return mkdtempSync(join(tmpdir(), 'to-session-start-'));
}

test('no config discoverable -> base guidance only, no Project Scope section', () => {
  const dir = tmpConfigDir();
  try {
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('Task Orchestrator — Session Context'));
    assert.ok(!out.hookSpecificOutput.additionalContext.includes('## Project Scope'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('config present but no project: block -> not-project-scoped guidance', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'extensions:\n  claude-code:\n    retrospective:\n      mode: nudge\n');
    const res = runHook(dir);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('not project-scoped'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('project block with a column-0 comment above rootId still resolves (parser bug fix)', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, [
      'project:',
      '# a stray column-0 comment sitting right above rootId',
      '  rootId: "root-comment-fix-check"',
      '  name: "Comment Fix Check"',
      '',
    ].join('\n'));
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('root-comment-fix-check'));
    assert.ok(out.hookSpecificOutput.additionalContext.includes('Comment Fix Check'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('project block with only rootId (no name) still resolves', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: bare-root-id\n');
    const res = runHook(dir);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('bare-root-id'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('config found -> watchPaths present, absolute, and points at the found config.yaml', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: bare-root-id\n');
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    const expectedPath = join(dir, '.taskorchestrator', 'config.yaml');
    assert.deepEqual(out.hookSpecificOutput.watchPaths, [expectedPath]);
    assert.ok(isAbsolute(out.hookSpecificOutput.watchPaths[0]));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('no config discoverable -> no watchPaths key at all', () => {
  const dir = tmpConfigDir();
  try {
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(!('watchPaths' in out.hookSpecificOutput));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// ─────────────────────────────────────────────────────────────────────────
// Registration self-check
// ─────────────────────────────────────────────────────────────────────────

function writeUserClaudeJson(homeDir, content) {
  writeFileSync(join(homeDir, '.claude.json'), JSON.stringify(content), 'utf-8');
}

function writeProjectMcpJson(dir, content) {
  writeFileSync(join(dir, '.mcp.json'), JSON.stringify(content), 'utf-8');
}

test('offending key in ~/.claude.json mcpServers -> Hook Registration Check warns', () => {
  const dir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, {
      mcpServers: {
        tasks: { command: 'docker', args: ['run', 'ghcr.io/jpicklyk/task-orchestrator:latest'] },
      },
    });
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('Hook Registration Check'));
    assert.ok(out.hookSpecificOutput.additionalContext.includes('tasks'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('compliant key mcp-task-orchestrator -> no Hook Registration Check section', () => {
  const dir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, {
      mcpServers: {
        'mcp-task-orchestrator': { command: 'docker', args: ['run', 'ghcr.io/jpicklyk/task-orchestrator:latest'] },
      },
    });
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(!out.hookSpecificOutput.additionalContext.includes('Hook Registration Check'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('offending HTTP entry in project .mcp.json -> warned', () => {
  const dir = tmpConfigDir();
  try {
    writeProjectMcpJson(dir, {
      mcpServers: {
        tasks: { type: 'http', url: 'http://host/task-orchestrator/mcp' },
      },
    });
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('Hook Registration Check'));
    assert.ok(out.hookSpecificOutput.additionalContext.includes('tasks'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('offending entry under ~/.claude.json projects[cwd].mcpServers -> warned', () => {
  const dir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, {
      projects: {
        [dir]: {
          mcpServers: {
            tasks: { command: 'docker', args: ['run', 'ghcr.io/jpicklyk/task-orchestrator:latest'] },
          },
        },
      },
    });
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('Hook Registration Check'));
    assert.ok(out.hookSpecificOutput.additionalContext.includes('tasks'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('malformed ~/.claude.json -> exit 0, base guidance, no Hook Registration Check', () => {
  const dir = tmpConfigDir();
  try {
    writeFileSync(join(dir, '.claude.json'), '{ not valid json', 'utf-8');
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('Task Orchestrator — Session Context'));
    assert.ok(!out.hookSpecificOutput.additionalContext.includes('Hook Registration Check'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('unrelated server entry -> not flagged', () => {
  const dir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, {
      mcpServers: {
        memory: { command: 'npx', args: ['@foo/memory'] },
      },
    });
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(!out.hookSpecificOutput.additionalContext.includes('Hook Registration Check'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// O1 regression: a filesystem MCP server whose path arg happens to mention the project folder
// name must NOT be mistaken for an orchestrator registration. Reproduces the reviewer's case.
test('O1: filesystem server arg pointing at a task-orchestrator checkout -> not flagged', () => {
  const dir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, {
      mcpServers: {
        filesystem: {
          command: 'npx',
          args: ['-y', '@modelcontextprotocol/server-filesystem', 'D:/Projects/task-orchestrator'],
        },
      },
    });
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(!out.hookSpecificOutput.additionalContext.includes('Hook Registration Check'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// O1 positive: a docker-args registration whose image ref mentions the token, under a
// non-matching server name, must still warn (path-arg exclusion must not swallow real hits).
test('O1: docker image-ref arg under a non-matching server name still warns', () => {
  const dir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, {
      mcpServers: {
        'docker-mcp': { command: 'docker', args: ['run', 'jpicklyk/task-orchestrator'] },
      },
    });
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('Hook Registration Check'));
    assert.ok(out.hookSpecificOutput.additionalContext.includes('docker-mcp'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// O2 regression: ~/.claude.json `projects` keys have been observed in both the native
// (backslash, on Windows) and forward-slash forms. Build the forward-slash key explicitly so the
// test is meaningful on Windows and harmless (a no-op transform) on POSIX.
test('O2: projects[cwd] lookup matches the forward-slash key form of cwd', () => {
  const dir = tmpConfigDir();
  try {
    const forwardSlashKey = dir.replace(/\\/g, '/');
    writeUserClaudeJson(dir, {
      projects: {
        [forwardSlashKey]: {
          mcpServers: {
            tasks: { command: 'docker', args: ['run', 'ghcr.io/jpicklyk/task-orchestrator:latest'] },
          },
        },
      },
    });
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('Hook Registration Check'));
    assert.ok(out.hookSpecificOutput.additionalContext.includes('tasks'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// O3: exercise the CLAUDE_CONFIG_DIR override branch — config is read from that directory
// instead of homedir when set.
test('O3: CLAUDE_CONFIG_DIR override reads .claude.json from that directory, not homedir', () => {
  const dir = tmpConfigDir();
  const configDir = tmpConfigDir();
  try {
    // homedir (dir) has no .claude.json at all; the offending registration lives only under
    // the CLAUDE_CONFIG_DIR override, so a warning proves the override branch actually ran.
    writeUserClaudeJson(configDir, {
      mcpServers: {
        tasks: { command: 'docker', args: ['run', 'ghcr.io/jpicklyk/task-orchestrator:latest'] },
      },
    });
    const res = runHook(dir, { env: { CLAUDE_CONFIG_DIR: configDir } });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('Hook Registration Check'));
    assert.ok(out.hookSpecificOutput.additionalContext.includes('tasks'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(configDir, { recursive: true, force: true });
  }
});

// ─────────────────────────────────────────────────────────────────────────
// Plugin version freshness check
// ─────────────────────────────────────────────────────────────────────────

function writeDevCheckoutPluginJson(dir, version) {
  const pluginDir = join(dir, 'claude-plugins', 'task-orchestrator', '.claude-plugin');
  mkdirSync(pluginDir, { recursive: true });
  writeFileSync(join(pluginDir, 'plugin.json'), JSON.stringify({ version }), 'utf-8');
}

function writeRunningPluginJson(rootDir, version) {
  const pluginDir = join(rootDir, '.claude-plugin');
  mkdirSync(pluginDir, { recursive: true });
  writeFileSync(join(pluginDir, 'plugin.json'), JSON.stringify({ version }), 'utf-8');
  return rootDir;
}

test('not a dev checkout (no claude-plugins/task-orchestrator/.claude-plugin/plugin.json) -> no Plugin Version Drift section', () => {
  const dir = tmpConfigDir();
  try {
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(!out.hookSpecificOutput.additionalContext.includes('Plugin Version Drift'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('dev checkout with matching versions -> no Plugin Version Drift section', () => {
  const dir = tmpConfigDir();
  const runningRoot = tmpConfigDir();
  try {
    writeDevCheckoutPluginJson(dir, '3.7.0');
    writeRunningPluginJson(runningRoot, '3.7.0');
    const res = runHook(dir, { env: { CLAUDE_PLUGIN_ROOT: runningRoot } });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(!out.hookSpecificOutput.additionalContext.includes('Plugin Version Drift'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(runningRoot, { recursive: true, force: true });
  }
});

test('dev checkout with differing versions -> Plugin Version Drift warning fires, names both versions', () => {
  const dir = tmpConfigDir();
  const runningRoot = tmpConfigDir();
  try {
    writeDevCheckoutPluginJson(dir, '3.8.0');
    writeRunningPluginJson(runningRoot, '3.7.0');
    const res = runHook(dir, { env: { CLAUDE_PLUGIN_ROOT: runningRoot } });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('Plugin Version Drift'));
    assert.ok(out.hookSpecificOutput.additionalContext.includes('3.8.0'));
    assert.ok(out.hookSpecificOutput.additionalContext.includes('3.7.0'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(runningRoot, { recursive: true, force: true });
  }
});

test('dev checkout present but running plugin.json unreadable (CLAUDE_PLUGIN_ROOT points nowhere) -> fail-open, silent', () => {
  const dir = tmpConfigDir();
  try {
    writeDevCheckoutPluginJson(dir, '3.8.0');
    const res = runHook(dir, { env: { CLAUDE_PLUGIN_ROOT: join(dir, 'no-such-dir') } });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(!out.hookSpecificOutput.additionalContext.includes('Plugin Version Drift'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('dev checkout plugin.json malformed JSON -> fail-open, silent', () => {
  const dir = tmpConfigDir();
  const runningRoot = tmpConfigDir();
  try {
    const pluginDir = join(dir, 'claude-plugins', 'task-orchestrator', '.claude-plugin');
    mkdirSync(pluginDir, { recursive: true });
    writeFileSync(join(pluginDir, 'plugin.json'), '{ not valid json', 'utf-8');
    writeRunningPluginJson(runningRoot, '3.7.0');
    const res = runHook(dir, { env: { CLAUDE_PLUGIN_ROOT: runningRoot } });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(!out.hookSpecificOutput.additionalContext.includes('Plugin Version Drift'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(runningRoot, { recursive: true, force: true });
  }
});

// ─────────────────────────────────────────────────────────────────────────
// Setup status states, init hint, config-sync notice, watchPaths (plan section C)
// Note: findProjectMcpConfig walks above the tmp fixture to real ancestors; a stray .mcp.json with
// a TO entry in a tmp ancestor could make the no-registration negative cases machine-dependent
// (pre-existing; not fixed here).
// ─────────────────────────────────────────────────────────────────────────

const COMPLIANT_STDIO = {
  mcpServers: {
    'mcp-task-orchestrator': { command: 'docker', args: ['run', 'ghcr.io/jpicklyk/task-orchestrator:latest'] },
  },
};
// An http registration that yields NO REST base: the URL carries credentials, which apiBaseUrl rejects
// (bc3e3c99). A plain http registration now resolves, which is the point of that change and is covered below.
const HTTP_REG = {
  mcpServers: {
    'mcp-task-orchestrator': { type: 'http', url: 'http://user:pw@host/task-orchestrator/mcp' },
  },
};
const parse = (res) => {
  assert.equal(res.status, 0);
  return JSON.parse(res.stdout);
};

test('none state -> Setup Status naming /task-orchestrator:init, no Project Scope, no watchPaths', () => {
  const dir = tmpConfigDir();
  const out = parse(runHook(dir));
  const ctx = out.hookSpecificOutput.additionalContext;
  assert.ok(ctx.includes('## Setup Status'));
  assert.ok(ctx.includes('/task-orchestrator:init'));
  assert.ok(!ctx.includes('## Project Scope'));
  assert.ok(!('watchPaths' in out.hookSpecificOutput));
  rmSync(dir, { recursive: true, force: true });
});

test('no-root state (project file) -> not project-scoped, names the config path', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'extensions:\n  claude-code:\n    retrospective:\n      mode: nudge\n');
    const ctx = parse(runHook(dir)).hookSpecificOutput.additionalContext;
    assert.ok(ctx.includes('## Setup Status'));
    assert.ok(ctx.includes('not project-scoped'));
    assert.ok(ctx.includes(join(dir, '.taskorchestrator', 'config.yaml')));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('project state -> Project Scope with rootId, name, ancestorId and Config path; watchPaths = [path]', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: proj-root-1\n  name: "Proj One"\n');
    const out = parse(runHook(dir));
    const ctx = out.hookSpecificOutput.additionalContext;
    const p = join(dir, '.taskorchestrator', 'config.yaml');
    assert.ok(ctx.includes('## Project Scope'));
    assert.ok(ctx.includes('proj-root-1') && ctx.includes('Proj One'));
    assert.ok(ctx.includes('ancestorId'));
    assert.ok(ctx.includes(`Config: ${p}`));
    assert.deepEqual(out.hookSpecificOutput.watchPaths, [p]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('user state -> Personal Scope, Personal root, no ancestorId scoping instruction; watchPaths = [user path]', () => {
  const dir = tmpConfigDir();
  const toHome = tmpConfigDir();
  try {
    writeConfig(toHome, 'project:\n  rootId: personal-root-1\n  name: "Me"\n');
    const out = parse(runHook(dir, { toHome }));
    const ctx = out.hookSpecificOutput.additionalContext;
    const p = join(toHome, '.taskorchestrator', 'config.yaml');
    assert.ok(ctx.includes('## Personal Scope'));
    assert.ok(ctx.includes('Personal root'));
    assert.ok(ctx.includes('personal-root-1'));
    assert.ok(ctx.includes(p));
    assert.ok(ctx.includes('Do NOT pass `ancestorId`'));
    assert.ok(!ctx.includes('ancestorId: "personal-root-1"'));
    assert.ok(!ctx.includes('## Project Scope'));
    assert.deepEqual(out.hookSpecificOutput.watchPaths, [p]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(toHome, { recursive: true, force: true });
  }
});

test('project hit plus existing user config -> watchPaths = [project, user]', () => {
  const dir = tmpConfigDir();
  const toHome = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: proj-root-2\n');
    writeConfig(toHome, 'project:\n  rootId: personal-root-2\n');
    const out = parse(runHook(dir, { toHome }));
    assert.deepEqual(out.hookSpecificOutput.watchPaths, [
      join(dir, '.taskorchestrator', 'config.yaml'),
      join(toHome, '.taskorchestrator', 'config.yaml'),
    ]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(toHome, { recursive: true, force: true });
  }
});

test('hint: none + registration -> single-line systemMessage once per cwd per day', () => {
  const dir = tmpConfigDir();
  const dir2 = tmpConfigDir();
  const tmpDirShared = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, COMPLIANT_STDIO);
    writeUserClaudeJson(dir2, COMPLIANT_STDIO);
    const first = parse(runHook(dir, { tmpDir: tmpDirShared }));
    assert.equal(typeof first.systemMessage, 'string');
    assert.ok(first.systemMessage.includes('/task-orchestrator:init'));
    assert.ok(!first.systemMessage.includes('\n'));
    const second = parse(runHook(dir, { tmpDir: tmpDirShared }));
    assert.ok(!('systemMessage' in second));
    const other = parse(runHook(dir2, { tmpDir: tmpDirShared }));
    assert.equal(typeof other.systemMessage, 'string');
  } finally {
    for (const d of [dir, dir2, tmpDirShared]) rmSync(d, { recursive: true, force: true });
  }
});

test('hint: no-root + registration -> systemMessage names the config path', () => {
  const dir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, COMPLIANT_STDIO);
    writeConfig(dir, 'extensions:\n  claude-code:\n    retrospective:\n      mode: nudge\n');
    const out = parse(runHook(dir));
    assert.ok(out.systemMessage.includes('/task-orchestrator:init'));
    assert.ok(out.systemMessage.includes(join(dir, '.taskorchestrator', 'config.yaml')));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('hint suppressed: no registration, SETUP_HINT=off/OFF, project and user states', () => {
  const dir = tmpConfigDir();
  const toHome = tmpConfigDir();
  try {
    assert.ok(!('systemMessage' in parse(runHook(dir))));

    writeUserClaudeJson(dir, COMPLIANT_STDIO);
    for (const v of ['off', 'OFF']) {
      const tmpDir = tmpConfigDir();
      try {
        const out = parse(runHook(dir, { tmpDir, env: { TASK_ORCHESTRATOR_SETUP_HINT: v } }));
        assert.ok(!('systemMessage' in out));
        assert.ok(!existsSync(join(tmpDir, 'task-orchestrator')));
      } finally {
        rmSync(tmpDir, { recursive: true, force: true });
      }
    }

    writeConfig(dir, 'project:\n  rootId: proj-root-3\n');
    assert.ok(!('systemMessage' in parse(runHook(dir))));
    rmSync(join(dir, '.taskorchestrator'), { recursive: true, force: true });

    writeConfig(toHome, 'project:\n  rootId: personal-root-3\n');
    assert.ok(!('systemMessage' in parse(runHook(dir, { toHome }))));
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(toHome, { recursive: true, force: true });
  }
});

test('hint: stale marker from another date does not suppress today', () => {
  const dir = tmpConfigDir();
  const tmpDir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, COMPLIANT_STDIO);
    let cwd = realpathSync(dir);
    if (process.platform === 'win32') cwd = cwd.toLowerCase();
    const hash = createHash('sha256').update(cwd).digest('hex').slice(0, 16);
    mkdirSync(join(tmpDir, 'task-orchestrator'), { recursive: true });
    writeFileSync(join(tmpDir, 'task-orchestrator', `setup-hint-${hash}-2000-01-01`), '');
    const out = parse(runHook(dir, { tmpDir }));
    assert.equal(typeof out.systemMessage, 'string');
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('config-sync notice: project state + http registration, no API URL -> present', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: proj-root-4\n');
    writeUserClaudeJson(dir, HTTP_REG);
    const ctx = parse(runHook(dir)).hookSpecificOutput.additionalContext;
    assert.ok(ctx.includes('## Config Sync'));
    assert.ok(ctx.includes('TASK_ORCHESTRATOR_API_URL'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('config-sync notice: absent for stdio registration, API URL set, or none state', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: proj-root-5\n');
    writeUserClaudeJson(dir, COMPLIANT_STDIO);
    assert.ok(!parse(runHook(dir)).hookSpecificOutput.additionalContext.includes('## Config Sync'));

    writeUserClaudeJson(dir, HTTP_REG);
    const withUrl = parse(runHook(dir, { env: { TASK_ORCHESTRATOR_API_URL: 'http://host:3001' } }));
    assert.ok(!withUrl.hookSpecificOutput.additionalContext.includes('## Config Sync'));

    rmSync(join(dir, '.taskorchestrator'), { recursive: true, force: true });
    assert.ok(!parse(runHook(dir)).hookSpecificOutput.additionalContext.includes('## Config Sync'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// ─────────────────────────────────────────────────────────────────────────
// d90dccd3: exact-text assertions, per-day marker, suppressed runs, notice scoping.
// Oracle: frozen-texts blocks BASE, T1..T8 (planner, frozen before implementation).
// ─────────────────────────────────────────────────────────────────────────

// The guidance block is rewritten for 4.0, so it is checked by property (task-scope AC4, AC8, AC9), not by
// an exact string: an exact string would just be a copy of whatever the hook prints.
const STALE = [/depth 0/i, /Process-global/i, /actor.attribution/i, /OUTSIDE the (project|personal) root/i];
const RETIRED_NAMES = RETIRED_3X_TOOLS.filter((n) => n !== 'complete_tree');

function assertNoStaleText(text, label) {
  for (const re of STALE) assert.ok(!re.test(text), label + ': stale text ' + re + ' in: ' + text);
  for (const tool of RETIRED_NAMES) {
    assert.ok(!new RegExp('\\b' + tool + '\\b').test(text), label + ': bare retired tool name ' + tool + ' in: ' + text);
  }
}

function assertGuidance(block) {
  assert.ok(block.startsWith('## Task Orchestrator'), 'guidance block must lead: ' + block.slice(0, 60));
  assert.match(block, /Session Context/);
  assertNoStaleText(block, 'guidance');
  assert.match(block, /`advance`/);
  assert.match(block, /`transition`/);
  assert.match(block, /`items_read`/);
  assert.match(block, /`context`/);
  assert.match(block, /`next`/);
  assert.match(block, /UUID/);
  assert.match(block, /\b8\b/);
  assert.match(block, /hex/i);
  assert.ok(
    block.split('\n').some((l) => /items_write/.test(l) && /create/.test(l) && /\btype\b/.test(l)),
    'a guidance line must say items_write create requires type',
  );
  assert.match(block, /profile/i);
}

const label = (r, n) => (n ? n + ' (`' + r + '`)' : '`' + r + '`');

// Scope sections: structure and the pieces AC4 pins (selects named for ancestorId, the parentId anchoring
// line, the personal-root no-ancestorId rule).
const personalScope = (r, n, p) => (block) => {
  assert.ok(block.startsWith('## Personal Scope'), block);
  assert.ok(block.includes('Personal root: ' + label(r, n)), block);
  assert.ok(block.includes('Config: ' + p), block);
  assert.ok(
    block.split('\n').includes('- Anchor new root-level items under the personal root by setting `parentId: "' + r + '"`.'),
    'parentId anchoring line missing: ' + block,
  );
  assert.ok(block.split('\n').some((l) => /Do NOT pass `ancestorId`/.test(l) && /items_read/.test(l)), 'no-ancestorId rule must name items_read: ' + block);
  assertNoStaleText(block, 'personal scope');
};

const projectScope = (r, n, p) => (block) => {
  assert.ok(block.startsWith('## Project Scope'), block);
  assert.ok(block.includes('Active project: ' + label(r, n)), block);
  assert.ok(block.includes('Config: ' + p), block);
  assert.ok(
    block.split('\n').some((l) => /items_read/.test(l) && l.includes('ancestorId: "' + r + '"')),
    'a line must pass ancestorId on items_read selects: ' + block,
  );
  assert.ok(
    block.split('\n').includes('- Anchor new root-level items under this project by setting `parentId: "' + r + '"`.'),
    'parentId anchoring line missing: ' + block,
  );
  assertNoStaleText(block, 'project scope');
};

// additionalContext = guidance block, then one block per '## ' section. expected[i] is the exact text of
// section i (string) or a checker (function).
const blocksOf = (ctx) => ctx.split(/\n\n(?=## )/);
function assertCtx(ctx, expected, msg = '') {
  const blocks = blocksOf(ctx);
  assertGuidance(blocks[0]);
  assert.equal(blocks.length, expected.length + 1, msg + ' section count; got headings: ' + blocks.map((b) => b.split('\n')[0]).join(' | '));
  expected.forEach((e, i) => {
    if (typeof e === 'function') e(blocks[i + 1]);
    else assert.equal(blocks[i + 1], e, msg);
  });
}

const T1 = [
  '## Setup Status',
  '',
  'No Task Orchestrator config was found for this directory. Run `/task-orchestrator:init` to set up this project, or `/task-orchestrator:init --user` for a personal root that serves every unconfigured directory.',
].join('\n');

const t2a = (p) => [
  '## Setup Status',
  '',
  'This workspace is not project-scoped — no `project.rootId` in `' + p + '`.',
  'Run `/task-orchestrator:init` to set one up.',
].join('\n');

const t2b = (p) => [
  '## Setup Status',
  '',
  'This workspace is not project-scoped — no `project.rootId` in `' + p + '`.',
  'Run `/task-orchestrator:init --user` to create a personal root.',
].join('\n');

const T5 = 'Task Orchestrator is not set up for this directory — run /task-orchestrator:init to configure it.';
const t6Project = (p) => 'Task Orchestrator config at ' + p + ' has no project.rootId — run /task-orchestrator:init to finish setup.';
const t6User = (p) => 'Task Orchestrator config at ' + p + ' has no project.rootId — run /task-orchestrator:init --user to finish setup.';

const T7_PROJECT = [
  '## Config Sync',
  '',
  'Config-sync cannot push this config: the MCP server is registered over http(s) but no REST API URL resolves from it (the registration url carries credentials, is a non-loopback `.mcp.json` url, or uses an unresolved `${VAR}`). Register the MCP server over HTTP at a usable URL, set `TASK_ORCHESTRATOR_API_URL`, or run `/task-orchestrator:init` (writes `client.json`).',
].join('\n');
const T7_USER = [
  '## Config Sync',
  '',
  'Config-sync cannot push this config: the MCP server is registered over http(s) but no REST API URL resolves from it (the registration url carries credentials, is a non-loopback `.mcp.json` url, or uses an unresolved `${VAR}`). Register the MCP server over HTTP at a usable URL, set `TASK_ORCHESTRATOR_API_URL`, or run `/task-orchestrator:init --user` (writes `client.json`).',
].join('\n');

const cfgPath = (base) => join(base, '.taskorchestrator', 'config.yaml');
const ctxOf = (res) => parse(res).hookSpecificOutput.additionalContext;

function writeClientJson(base, content) {
  const d = join(base, '.taskorchestrator');
  mkdirSync(d, { recursive: true });
  writeFileSync(join(d, 'client.json'), typeof content === 'string' ? content : JSON.stringify(content), 'utf-8');
}

function localDate(d) {
  const p = (n) => String(n).padStart(2, '0');
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
}

function markerHash(dir) {
  let cwd = realpathSync(dir);
  if (process.platform === 'win32') cwd = cwd.toLowerCase();
  return createHash('sha256').update(cwd).digest('hex').slice(0, 16);
}

function markerDir(tmpDir) {
  return join(tmpDir, 'task-orchestrator');
}

// ── Four state sections, exact text ──────────────────────────────────────

test('S1 none state -> additionalContext is guidance block + Setup Status (T1), no systemMessage', () => {
  const dir = tmpConfigDir();
  try {
    const out = parse(runHook(dir));
    assertCtx(out.hookSpecificOutput.additionalContext, [T1]);
    assert.ok(!('systemMessage' in out));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('S2 no-root state, project file -> guidance block + T2a', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'extensions:\n  claude-code:\n    retrospective:\n      mode: nudge\n');
    assertCtx(ctxOf(runHook(dir)), [t2a(cfgPath(dir))]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('S3 no-root state, user file -> guidance block + T2b; watchPaths = [user path]', () => {
  const dir = tmpConfigDir();
  const toHome = tmpConfigDir();
  try {
    writeConfig(toHome, 'extensions:\n  claude-code:\n    retrospective:\n      mode: nudge\n');
    const out = parse(runHook(dir, { toHome }));
    assertCtx(out.hookSpecificOutput.additionalContext, [t2b(cfgPath(toHome))]);
    assert.deepEqual(out.hookSpecificOutput.watchPaths, [cfgPath(toHome)]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(toHome, { recursive: true, force: true });
  }
});

test('S4 project state with name -> guidance block + T4', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: proj-exact-1\n  name: "Exact One"\n');
    assertCtx(ctxOf(runHook(dir)), [projectScope('proj-exact-1', 'Exact One', cfgPath(dir))]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('S4b project state without name -> label is just the backticked rootId', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: proj-exact-2\n');
    assertCtx(ctxOf(runHook(dir)), [projectScope('proj-exact-2', null, cfgPath(dir))]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('S5 user state -> guidance block + T3', () => {
  const dir = tmpConfigDir();
  const toHome = tmpConfigDir();
  try {
    writeConfig(toHome, 'project:\n  rootId: personal-exact-1\n  name: "Me"\n');
    assertCtx(ctxOf(runHook(dir, { toHome })), [personalScope('personal-exact-1', 'Me', cfgPath(toHome))]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(toHome, { recursive: true, force: true });
  }
});

// ── systemMessage hint, exact text ───────────────────────────────────────

test('S6 hint, none state + registration -> systemMessage is exactly T5', () => {
  const dir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, COMPLIANT_STDIO);
    assert.equal(parse(runHook(dir)).systemMessage, T5);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('S7 hint, no-root project file -> systemMessage is exactly T6 (project wording)', () => {
  const dir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, COMPLIANT_STDIO);
    writeConfig(dir, 'extensions:\n  claude-code:\n    retrospective:\n      mode: nudge\n');
    assert.equal(parse(runHook(dir)).systemMessage, t6Project(cfgPath(dir)));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('S8 hint, no-root USER file -> systemMessage names init --user, agreeing with the context section', () => {
  const dir = tmpConfigDir();
  const toHome = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, COMPLIANT_STDIO);
    writeConfig(toHome, 'extensions:\n  claude-code:\n    retrospective:\n      mode: nudge\n');
    const out = parse(runHook(dir, { toHome }));
    assert.equal(out.systemMessage, t6User(cfgPath(toHome)));
    assertCtx(out.hookSpecificOutput.additionalContext, [t2b(cfgPath(toHome))]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(toHome, { recursive: true, force: true });
  }
});

// ── Config Sync notice, exact text and gating ────────────────────────────

test('S9 project state + http registration, no API URL -> guidance + T4 + T7 (project command)', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: proj-sync-1\n  name: "Sync One"\n');
    writeUserClaudeJson(dir, HTTP_REG);
    assertCtx(
      ctxOf(runHook(dir)),
      [projectScope('proj-sync-1', 'Sync One', cfgPath(dir)), T7_PROJECT],
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('S10 user state + http registration, no API URL -> guidance + T3 + T7 naming init --user', () => {
  const dir = tmpConfigDir();
  const toHome = tmpConfigDir();
  try {
    writeConfig(toHome, 'project:\n  rootId: personal-sync-1\n  name: "Me"\n');
    writeUserClaudeJson(dir, HTTP_REG);
    assertCtx(
      ctxOf(runHook(dir, { toHome })),
      [personalScope('personal-sync-1', 'Me', cfgPath(toHome)), T7_USER],
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(toHome, { recursive: true, force: true });
  }
});

test('S11a notice absent when a loopback project client.json resolves the URL', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: proj-sync-2\n  name: "Sync Two"\n');
    writeUserClaudeJson(dir, HTTP_REG);
    writeClientJson(dir, { apiUrl: 'http://127.0.0.1:3001' });
    assertCtx(ctxOf(runHook(dir)), [projectScope('proj-sync-2', 'Sync Two', cfgPath(dir))]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('S11b notice absent in project state when a user client.json resolves the URL', () => {
  const dir = tmpConfigDir();
  const toHome = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: proj-sync-3\n  name: "Sync Three"\n');
    writeUserClaudeJson(dir, HTTP_REG);
    writeClientJson(toHome, { apiUrl: 'http://127.0.0.1:3001' });
    assertCtx(ctxOf(runHook(dir, { toHome })), [projectScope('proj-sync-3', 'Sync Three', cfgPath(dir))]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(toHome, { recursive: true, force: true });
  }
});

test('S11c notice absent in user state when a user client.json resolves the URL', () => {
  const dir = tmpConfigDir();
  const toHome = tmpConfigDir();
  try {
    writeConfig(toHome, 'project:\n  rootId: personal-sync-2\n  name: "Me"\n');
    writeUserClaudeJson(dir, HTTP_REG);
    writeClientJson(toHome, { apiUrl: 'http://127.0.0.1:3001' });
    assertCtx(ctxOf(runHook(dir, { toHome })), [personalScope('personal-sync-2', 'Me', cfgPath(toHome))]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(toHome, { recursive: true, force: true });
  }
});

test('S12 non-loopback or malformed project client.json does not resolve -> notice (project text) present', () => {
  for (const content of [JSON.stringify({ apiUrl: 'http://example.com:3001' }), '{ not valid json']) {
    const dir = tmpConfigDir();
    try {
      writeConfig(dir, 'project:\n  rootId: proj-sync-4\n  name: "Sync Four"\n');
      writeUserClaudeJson(dir, HTTP_REG);
      writeClientJson(dir, content);
      assertCtx(
        ctxOf(runHook(dir)),
        [projectScope('proj-sync-4', 'Sync Four', cfgPath(dir)), T7_PROJECT],
        'client.json content: ' + content,
      );
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  }
});

test('S13 none and no-root states + http registration -> no Config Sync section', () => {
  const dir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, HTTP_REG);
    assertCtx(ctxOf(runHook(dir)), [T1]);
    writeConfig(dir, 'extensions:\n  claude-code:\n    retrospective:\n      mode: nudge\n');
    assertCtx(ctxOf(runHook(dir)), [t2a(cfgPath(dir))]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// ── Once per directory per DAY marker ────────────────────────────────────

test("S14 marker from a previous day lets the hint through, creates today's marker, keeps the stale one", () => {
  const dir = tmpConfigDir();
  const tmpDir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, COMPLIANT_STDIO);
    const h = markerHash(dir);
    mkdirSync(markerDir(tmpDir), { recursive: true });
    const stale = join(markerDir(tmpDir), 'setup-hint-' + h + '-2000-01-01');
    writeFileSync(stale, '');
    const before = localDate(new Date());
    const out = parse(runHook(dir, { tmpDir }));
    const after = localDate(new Date());
    assert.equal(out.systemMessage, T5);
    assert.ok(existsSync(stale), 'stale marker must be left in place');
    assert.ok(
      existsSync(join(markerDir(tmpDir), 'setup-hint-' + h + '-' + before)) ||
        existsSync(join(markerDir(tmpDir), 'setup-hint-' + h + '-' + after)),
      "today's marker must be created",
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('S15 a marker from today suppresses the hint', () => {
  const dir = tmpConfigDir();
  const tmpDir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, COMPLIANT_STDIO);
    const h = markerHash(dir);
    mkdirSync(markerDir(tmpDir), { recursive: true });
    // Markers for the date before AND after the spawn so a midnight rollover cannot flip the outcome.
    const beforeDate = localDate(new Date());
    writeFileSync(join(markerDir(tmpDir), 'setup-hint-' + h + '-' + beforeDate), '');
    const afterDate = localDate(new Date(Date.now() + 5000));
    if (afterDate !== beforeDate) writeFileSync(join(markerDir(tmpDir), 'setup-hint-' + h + '-' + afterDate), '');
    const out = parse(runHook(dir, { tmpDir }));
    assert.ok(!('systemMessage' in out));
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('S16 a date-less marker setup-hint-<h> does not suppress (once per day, not once ever)', () => {
  const dir = tmpConfigDir();
  const tmpDir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, COMPLIANT_STDIO);
    const h = markerHash(dir);
    mkdirSync(markerDir(tmpDir), { recursive: true });
    const legacy = join(markerDir(tmpDir), 'setup-hint-' + h);
    writeFileSync(legacy, '');
    assert.equal(parse(runHook(dir, { tmpDir })).systemMessage, T5);
    assert.ok(existsSync(legacy), 'date-less marker must be left in place');
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('S17 a malformed-date marker setup-hint-<h>-garbage does not suppress', () => {
  const dir = tmpConfigDir();
  const tmpDir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, COMPLIANT_STDIO);
    const h = markerHash(dir);
    mkdirSync(markerDir(tmpDir), { recursive: true });
    const bad = join(markerDir(tmpDir), 'setup-hint-' + h + '-garbage');
    writeFileSync(bad, '');
    assert.equal(parse(runHook(dir, { tmpDir })).systemMessage, T5);
    assert.ok(existsSync(bad), 'malformed marker must be left in place');
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

// ── A suppressed run never consumes the marker ───────────────────────────

test('S18 SETUP_HINT=off: no marker created, hint still shows once the opt-out is lifted', () => {
  const dir = tmpConfigDir();
  const tmpDir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, COMPLIANT_STDIO);
    const first = parse(runHook(dir, { tmpDir, env: { TASK_ORCHESTRATOR_SETUP_HINT: 'off' } }));
    assert.ok(!('systemMessage' in first));
    assert.ok(!existsSync(markerDir(tmpDir)));
    assert.equal(parse(runHook(dir, { tmpDir })).systemMessage, T5);
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('S19 no registration: no marker created, hint shows once a registration exists', () => {
  const dir = tmpConfigDir();
  const tmpDir = tmpConfigDir();
  try {
    const first = parse(runHook(dir, { tmpDir }));
    assert.ok(!('systemMessage' in first));
    assert.ok(!existsSync(markerDir(tmpDir)));
    writeUserClaudeJson(dir, COMPLIANT_STDIO);
    assert.equal(parse(runHook(dir, { tmpDir })).systemMessage, T5);
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('S20 project state: no marker created, hint shows once the project config is gone', () => {
  const dir = tmpConfigDir();
  const tmpDir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, COMPLIANT_STDIO);
    writeConfig(dir, 'project:\n  rootId: proj-marker-1\n');
    const first = parse(runHook(dir, { tmpDir }));
    assert.ok(!('systemMessage' in first));
    assert.ok(!existsSync(markerDir(tmpDir)));
    rmSync(join(dir, '.taskorchestrator'), { recursive: true, force: true });
    assert.equal(parse(runHook(dir, { tmpDir })).systemMessage, T5);
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('S21 user state: no marker created, hint shows once the user config is gone', () => {
  const dir = tmpConfigDir();
  const toHome = tmpConfigDir();
  const tmpDir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, COMPLIANT_STDIO);
    writeConfig(toHome, 'project:\n  rootId: personal-marker-1\n');
    const first = parse(runHook(dir, { tmpDir, toHome }));
    assert.ok(!('systemMessage' in first));
    assert.ok(!existsSync(markerDir(tmpDir)));
    rmSync(join(toHome, '.taskorchestrator'), { recursive: true, force: true });
    assert.equal(parse(runHook(dir, { tmpDir, toHome })).systemMessage, T5);
  } finally {
    for (const d of [dir, toHome, tmpDir]) rmSync(d, { recursive: true, force: true });
  }
});

// ── Adversarial probes ───────────────────────────────────────────────────

test('probe: SETUP_HINT " Off " (padded, mixed case) suppresses the hint', () => {
  const dir = tmpConfigDir();
  const tmpDir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, COMPLIANT_STDIO);
    const out = parse(runHook(dir, { tmpDir, env: { TASK_ORCHESTRATOR_SETUP_HINT: ' Off ' } }));
    assert.ok(!('systemMessage' in out));
    assert.ok(!existsSync(markerDir(tmpDir)));
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('probe: empty or blank apiUrl in a project client.json does not resolve -> notice present', () => {
  for (const apiUrl of ['', '   ']) {
    const dir = tmpConfigDir();
    try {
      writeConfig(dir, 'project:\n  rootId: proj-probe-1\n  name: "Probe"\n');
      writeUserClaudeJson(dir, HTTP_REG);
      writeClientJson(dir, { apiUrl });
      assertCtx(
        ctxOf(runHook(dir)),
        [projectScope('proj-probe-1', 'Probe', cfgPath(dir)), T7_PROJECT],
        'apiUrl: ' + JSON.stringify(apiUrl),
      );
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  }
});

test('probe: BOM-prefixed loopback client.json still resolves -> notice absent', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: proj-probe-2\n  name: "Probe"\n');
    writeUserClaudeJson(dir, HTTP_REG);
    writeClientJson(dir, '﻿' + JSON.stringify({ apiUrl: 'http://127.0.0.1:3001' }));
    assertCtx(ctxOf(runHook(dir)), [projectScope('proj-probe-2', 'Probe', cfgPath(dir))]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('probe: loopback variants 127.0.0.2 and [::1] resolve; localhost.evil.com does not', () => {
  const cases = [
    ['http://127.0.0.2:3001', false],
    ['http://[::1]:3001', false],
    ['http://localhost.evil.com:3001', true],
  ];
  for (const [apiUrl, expectNotice] of cases) {
    const dir = tmpConfigDir();
    try {
      writeConfig(dir, 'project:\n  rootId: proj-probe-3\n  name: "Probe"\n');
      writeUserClaudeJson(dir, HTTP_REG);
      writeClientJson(dir, { apiUrl });
      assertCtx(ctxOf(runHook(dir)), [projectScope('proj-probe-3', 'Probe', cfgPath(dir)), ...(expectNotice ? [T7_PROJECT] : [])], 'apiUrl: ' + apiUrl);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  }
});

test('probe: replay with the same cwd and tmp -> second run is suppressed', () => {
  const dir = tmpConfigDir();
  const tmpDir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, COMPLIANT_STDIO);
    assert.equal(parse(runHook(dir, { tmpDir })).systemMessage, T5);
    assert.ok(!('systemMessage' in parse(runHook(dir, { tmpDir }))));
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

test("probe: today's marker path is a directory -> exit 0, no systemMessage", () => {
  const dir = tmpConfigDir();
  const tmpDir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, COMPLIANT_STDIO);
    const h = markerHash(dir);
    mkdirSync(markerDir(tmpDir), { recursive: true });
    const d1 = localDate(new Date());
    mkdirSync(join(markerDir(tmpDir), 'setup-hint-' + h + '-' + d1));
    const d2 = localDate(new Date(Date.now() + 5000));
    if (d2 !== d1) mkdirSync(join(markerDir(tmpDir), 'setup-hint-' + h + '-' + d2));
    const out = parse(runHook(dir, { tmpDir }));
    assert.ok(!('systemMessage' in out));
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

// ─────────────────────────────────────────────────────────────────────────
// Item 8cd79bc0: S6 mode gating (guidance vs diagnostics) and S7 Config Shape diagnostic.
// Oracle: task-scope AC3/AC4 - guidance (base guidance, Setup Status, Personal/Project Scope) is omitted
// when suppressionReason is non-null (headless iteration, subagent agent_id, director marker);
// diagnostics (setup hint, registration check, config-sync notice, plugin drift, watchPaths, Config
// Shape) are always computed; with nothing left additionalContext is omitted (no empty string); stdin is
// read through readHookInput so empty or malformed stdin is a main session.
// ─────────────────────────────────────────────────────────────────────────

const HEADLESS_ENV = { TASK_ORCHESTRATOR_MODE: 'headless-iteration' };
const ctxIn = (out) => (out.hookSpecificOutput ? out.hookSpecificOutput.additionalContext : undefined);

function plantDirectorIn(tmpDir, sessionId) {
  const saved = {};
  for (const k of ['TMPDIR', 'TEMP', 'TMP']) {
    saved[k] = process.env[k];
    process.env[k] = tmpDir;
  }
  try {
    const p = directorMarkerPath(sessionId);
    mkdirSync(dirname(p), { recursive: true });
    writeFileSync(p, '');
  } finally {
    for (const k of Object.keys(saved)) {
      if (saved[k] === undefined) delete process.env[k];
      else process.env[k] = saved[k];
    }
  }
}

// Each suppression yields extra runHook options. Every one gets its own tmpDir so a director marker is
// visible to the hook (which pins TEMP to it).
const SUPPRESSIONS = [
  ['headless iteration', (tmpDir) => ({ tmpDir, env: HEADLESS_ENV, input: JSON.stringify({ session_id: 'main-s' }) })],
  ['subagent (agent_id)', (tmpDir) => ({ tmpDir, input: JSON.stringify({ session_id: 'main-s', agent_id: 'agent-7' }) })],
  [
    'director marker',
    (tmpDir) => {
      plantDirectorIn(tmpDir, 'dir-s');
      return { tmpDir, input: JSON.stringify({ session_id: 'dir-s' }) };
    },
  ],
  [
    'director marker after /compact',
    (tmpDir) => {
      plantDirectorIn(tmpDir, 'dir-s');
      return { tmpDir, input: JSON.stringify({ session_id: 'dir-s', source: 'compact' }) };
    },
  ],
];

function eachSuppression(fn) {
  for (const [name, mk] of SUPPRESSIONS) {
    const dir = tmpConfigDir();
    const tmpDir = tmpConfigDir();
    try {
      fn(name, dir, mk(tmpDir));
    } finally {
      rmSync(dir, { recursive: true, force: true });
      rmSync(tmpDir, { recursive: true, force: true });
    }
  }
}

test('S6 suppressed, project state -> no guidance, no additionalContext key at all, watchPaths kept', () => {
  eachSuppression((name, dir, opts) => {
    writeConfig(dir, 'project:\n  rootId: proj-gate-1\n  name: "Gate One"\n');
    const out = parse(runHook(dir, opts));
    assert.equal(ctxIn(out), undefined, name);
    assert.ok(!out.hookSpecificOutput || !('additionalContext' in out.hookSpecificOutput), name + ': empty-string additionalContext is not allowed');
    assert.deepEqual(out.hookSpecificOutput && out.hookSpecificOutput.watchPaths, [cfgPath(dir)], name);
  });
});

test('S6 suppressed, none state + registration -> setup hint (systemMessage) still shown, Setup Status guidance omitted', () => {
  eachSuppression((name, dir, opts) => {
    writeUserClaudeJson(dir, COMPLIANT_STDIO);
    const out = parse(runHook(dir, opts));
    assert.equal(out.systemMessage, T5, name);
    assert.equal(ctxIn(out), undefined, name);
  });
});

test('S6 suppressed, no-root and user states -> Setup Status / Personal Scope guidance omitted', () => {
  eachSuppression((name, dir, opts) => {
    writeConfig(dir, 'extensions:\n  claude-code:\n    retrospective:\n      mode: nudge\n');
    assert.equal(ctxIn(parse(runHook(dir, opts))), undefined, name + ' no-root');
  });
  eachSuppression((name, dir, opts) => {
    const toHome = tmpConfigDir();
    try {
      writeConfig(toHome, 'project:\n  rootId: personal-gate-1\n  name: "Me"\n');
      const out = parse(runHook(dir, { ...opts, toHome }));
      assert.equal(ctxIn(out), undefined, name + ' user');
      assert.deepEqual(out.hookSpecificOutput.watchPaths, [cfgPath(toHome)], name + ' user');
    } finally {
      rmSync(toHome, { recursive: true, force: true });
    }
  });
});

test('S6 suppressed -> the registration check diagnostic is still produced, alone', () => {
  eachSuppression((name, dir, opts) => {
    writeConfig(dir, 'project:\n  rootId: proj-gate-2\n  name: "Gate Two"\n');
    writeUserClaudeJson(dir, { mcpServers: { tasks: { command: 'docker', args: ['run', 'ghcr.io/jpicklyk/task-orchestrator:latest'] } } });
    const ctx = ctxIn(parse(runHook(dir, opts)));
    assert.ok(ctx && ctx.includes('Hook Registration Check'), name);
    assert.ok(ctx.includes('tasks'), name);
    for (const guidance of ['Session Context', '## Project Scope', '## Setup Status', '## Personal Scope']) {
      assert.ok(!ctx.includes(guidance), name + ': guidance leaked: ' + guidance);
    }
    assert.match(ctx, /^## /, name);
  });
});

test('S6 suppressed -> the plugin version drift diagnostic is still produced, guidance omitted', () => {
  eachSuppression((name, dir, opts) => {
    const runningRoot = tmpConfigDir();
    try {
      writeDevCheckoutPluginJson(dir, '3.8.0');
      writeRunningPluginJson(runningRoot, '3.7.0');
      const ctx = ctxIn(parse(runHook(dir, { ...opts, env: { ...(opts.env || {}), CLAUDE_PLUGIN_ROOT: runningRoot } })));
      assert.ok(ctx && ctx.includes('Plugin Version Drift'), name);
      assert.ok(ctx.includes('3.8.0') && ctx.includes('3.7.0'), name);
      assert.ok(!ctx.includes('Session Context'), name);
    } finally {
      rmSync(runningRoot, { recursive: true, force: true });
    }
  });
});

test('S6 suppressed -> the config-sync notice diagnostic is still produced, guidance omitted', () => {
  eachSuppression((name, dir, opts) => {
    writeConfig(dir, 'project:\n  rootId: proj-gate-3\n  name: "Gate Three"\n');
    writeUserClaudeJson(dir, HTTP_REG);
    const ctx = ctxIn(parse(runHook(dir, opts)));
    assert.equal(ctx, T7_PROJECT, name);
  });
});

test('S6 unsuppressed variants still get the guidance: empty/non-string agent_id, no marker, wrong-case sentinel, bad stdin', () => {
  const variants = [
    ['agent_id empty', { input: JSON.stringify({ session_id: 'main-s', agent_id: '' }) }],
    ['agent_id number', { input: JSON.stringify({ session_id: 'main-s', agent_id: 5 }) }],
    ['agent_id null', { input: JSON.stringify({ session_id: 'main-s', agent_id: null }) }],
    ['session without marker', { input: JSON.stringify({ session_id: 'plain-s' }) }],
    ['empty session id', { input: JSON.stringify({ session_id: '' }) }],
    ['no session id', { input: JSON.stringify({ source: 'startup' }) }],
    ['wrong-case sentinel', { env: { TASK_ORCHESTRATOR_MODE: 'HEADLESS-ITERATION' }, input: JSON.stringify({ session_id: 'main-s' }) }],
    ['other mode value', { env: { TASK_ORCHESTRATOR_MODE: 'interactive' }, input: JSON.stringify({ session_id: 'main-s' }) }],
    ['empty stdin', { input: '' }],
    ['malformed stdin', { input: 'not json' }],
    ['non-object stdin', { input: '[1,2]' }],
    ['no stdin option', {}],
  ];
  for (const [name, opts] of variants) {
    const dir = tmpConfigDir();
    try {
      writeConfig(dir, 'project:\n  rootId: proj-gate-4\n  name: "Gate Four"\n');
      assertCtx(ctxOf(runHook(dir, opts)), [projectScope('proj-gate-4', 'Gate Four', cfgPath(dir))], name);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  }
});

test('S6 a director marker for another session does not suppress this one', () => {
  const dir = tmpConfigDir();
  const tmpDir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: proj-gate-5\n');
    plantDirectorIn(tmpDir, 'dir-s');
    assertCtx(ctxOf(runHook(dir, { tmpDir, input: JSON.stringify({ session_id: 'someone-else' }) })), [projectScope('proj-gate-5', null, cfgPath(dir))]);
    assert.equal(ctxIn(parse(runHook(dir, { tmpDir, input: JSON.stringify({ session_id: 'dir-s' }) }))), undefined);
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('S6 headless beats an otherwise unsuppressed session on every state, and the guidance returns once the env is lifted', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: proj-gate-6\n');
    assert.equal(ctxIn(parse(runHook(dir, { env: HEADLESS_ENV }))), undefined);
    assertCtx(ctxOf(runHook(dir, {})), [projectScope('proj-gate-6', null, cfgPath(dir))]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('S6 guidance and diagnostics: no state emits a stale depth-0 line, actor-attribution wording or a bare 3.x tool name', () => {
  const cases = [];
  {
    const dir = tmpConfigDir();
    writeUserClaudeJson(dir, { mcpServers: { tasks: { command: 'docker', args: ['run', 'ghcr.io/jpicklyk/task-orchestrator:latest'] } } });
    cases.push(['none + offending registration', dir, {}]);
  }
  {
    const dir = tmpConfigDir();
    writeConfig(dir, 'extensions:\n  claude-code:\n    retrospective:\n      mode: nudge\n');
    writeUserClaudeJson(dir, { mcpServers: { tasks: { command: 'docker', args: ['run', 'ghcr.io/jpicklyk/task-orchestrator:latest'] } } });
    cases.push(['no-root + offending registration', dir, {}]);
  }
  {
    const dir = tmpConfigDir();
    writeConfig(dir, 'project:\n  rootId: proj-gate-7\n  name: "P"\n');
    writeUserClaudeJson(dir, { mcpServers: { tasks: { type: 'http', url: 'http://host/task-orchestrator/mcp' } } });
    cases.push(['project + offending http registration', dir, {}]);
  }
  {
    const dir = tmpConfigDir();
    const toHome = tmpConfigDir();
    writeConfig(toHome, 'project:\n  rootId: personal-gate-2\n  name: "Me"\n');
    writeUserClaudeJson(dir, HTTP_REG);
    cases.push(['user + http registration', dir, { toHome }]);
  }
  try {
    for (const [name, dir, opts] of cases) {
      const out = parse(runHook(dir, opts));
      assertNoStaleText(ctxIn(out) || '', name);
      assertNoStaleText(out.systemMessage || '', name + ' systemMessage');
    }
  } finally {
    for (const [, dir, opts] of cases) {
      rmSync(dir, { recursive: true, force: true });
      if (opts.toHome) rmSync(opts.toHome, { recursive: true, force: true });
    }
  }
});

// ── S7: Config Shape diagnostic ──────────────────────────────────────────

const shapeBlocks = (ctx) => blocksOf(ctx || '').filter((b) => b.startsWith('## Config Shape'));
const shapeBody = (block) => block.split('\n').slice(1).filter((l) => l.trim() !== '');
const PROJ = 'project:\n  rootId: proj-shape-1\n  name: "Shape"\n';

function shapeFor(configText, opts = {}) {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, configText);
    return shapeBlocks(ctxIn(parse(runHook(dir, opts))));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test('S7 a top-level orchestration: section -> exactly one Config Shape line saying it is ignored and naming extensions.claude-code', () => {
  for (const text of [PROJ + 'orchestration:\n  mode: off\n', PROJ + 'orchestration: { mode: schema }\n']) {
    const blocks = shapeFor(text);
    assert.equal(blocks.length, 1, text);
    const body = shapeBody(blocks[0]);
    assert.equal(body.length, 1, 'exactly one line: ' + blocks[0]);
    assert.match(body[0], /orchestration/);
    assert.match(body[0], /ignored/i);
    assert.match(body[0], /extensions\.claude-code/);
  }
});

test('S7 a top-level retrospective: section -> exactly one Config Shape line naming it', () => {
  const blocks = shapeFor(PROJ + 'retrospective:\n  mode: nudge\n');
  assert.equal(blocks.length, 1);
  const body = shapeBody(blocks[0]);
  assert.equal(body.length, 1);
  assert.match(body[0], /retrospective/);
  assert.match(body[0], /ignored/i);
  assert.match(body[0], /extensions\.claude-code/);
});

test('S7 both top-level sections -> a single Config Shape heading that names both', () => {
  const blocks = shapeFor(PROJ + 'orchestration:\n  mode: off\nretrospective:\n  mode: nudge\n');
  assert.equal(blocks.length, 1);
  assert.match(blocks[0], /orchestration/);
  assert.match(blocks[0], /retrospective/);
  assert.match(blocks[0], /ignored/i);
});

test('S7 keys already under extensions.claude-code -> no Config Shape section', () => {
  const nested = PROJ + 'extensions:\n  claude-code:\n    orchestration:\n      mode: off\n    retrospective:\n      mode: nudge\n';
  assert.equal(shapeFor(nested).length, 0);
  assert.equal(shapeFor(PROJ).length, 0);
});

test('S7 probe: look-alikes are not top-level sections - comment, prefixed key, string value, indented key under another parent', () => {
  for (const text of [
    PROJ + '# orchestration:\n',
    PROJ + 'orchestration-notes: x\n',
    PROJ + 'retrospective_extra: x\n',
    PROJ + 'note: "orchestration:"\n',
    PROJ + 'other:\n  orchestration:\n    mode: off\n',
    PROJ + 'extensions:\n  other-tool:\n    retrospective:\n      mode: nudge\n',
  ]) {
    assert.equal(shapeFor(text).length, 0, text);
  }
});

test('S7 probe: CRLF config is recognised', () => {
  assert.equal(shapeFor('project:\r\n  rootId: proj-shape-2\r\norchestration:\r\n  mode: off\r\n').length, 1);
});

test('S7 the notice appears for a located user-scope config too, and not when no config is located', () => {
  const dir = tmpConfigDir();
  const toHome = tmpConfigDir();
  try {
    assert.equal(shapeBlocks(ctxIn(parse(runHook(dir)))).length, 0);
    writeConfig(toHome, 'project:\n  rootId: personal-shape\n  name: "Me"\norchestration:\n  mode: off\n');
    assert.equal(shapeBlocks(ctxIn(parse(runHook(dir, { toHome })))).length, 1);
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(toHome, { recursive: true, force: true });
  }
});

test('S7 the notice is a diagnostic: it is still produced when the guidance is suppressed, alone', () => {
  eachSuppression((name, dir, opts) => {
    writeConfig(dir, PROJ + 'orchestration:\n  mode: off\n');
    const ctx = ctxIn(parse(runHook(dir, opts)));
    const blocks = shapeBlocks(ctx);
    assert.equal(blocks.length, 1, name);
    assert.ok(!ctx.includes('Session Context') && !ctx.includes('## Project Scope'), name + ': guidance leaked');
  });
});

test('S7 the notice sits next to normal guidance in an unsuppressed session (guidance first, scope section present)', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, PROJ + 'orchestration:\n  mode: off\n');
    const blocks = blocksOf(ctxOf(runHook(dir)));
    assertGuidance(blocks[0]);
    assert.ok(blocks.some((b) => b.startsWith('## Project Scope')));
    assert.equal(blocks.filter((b) => b.startsWith('## Config Shape')).length, 1);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
// -------------------------------------------------------------------------
// bc3e3c99: hooks/MCP endpoint mismatch warning (AC7).
// -------------------------------------------------------------------------

const mismatchReg = (url) => ({ mcpServers: { 'mcp-task-orchestrator': { type: 'http', url } } });
const msgOf = (res) => parse(res).systemMessage ?? '';

test('AC7: env URL differs from the http registration -> one warning line naming both', () => {
  const dir = tmpConfigDir();
  try {
    writeProjectMcpJson(dir, mismatchReg('http://localhost:3003/mcp'));
    const msg = msgOf(runHook(dir, { env: { TASK_ORCHESTRATOR_API_URL: 'http://localhost:3001' } }));
    assert.ok(msg.includes('hooks call the REST API at http://localhost:3001 (TASK_ORCHESTRATOR_API_URL)'), msg);
    assert.ok(msg.includes('mcp-task-orchestrator is registered at http://localhost:3003 (.mcp.json)'), msg);
    assert.ok(msg.includes('talking to different servers'), msg);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('AC7: matching origins (case and default port normalised), no registration, or stdio -> no warning', () => {
  const dir = tmpConfigDir();
  try {
    writeProjectMcpJson(dir, mismatchReg('http://LOCALHOST:80/mcp'));
    assert.ok(!msgOf(runHook(dir, { env: { TASK_ORCHESTRATOR_API_URL: 'http://localhost' } })).includes('different servers'));
    writeProjectMcpJson(dir, mismatchReg('http://localhost:3003/mcp'));
    assert.ok(!msgOf(runHook(dir)).includes('different servers'), 'no env, the registration itself resolves');
    writeProjectMcpJson(dir, { mcpServers: { 'mcp-task-orchestrator': { command: 'docker', args: ['run', 'x'] } } });
    assert.ok(!msgOf(runHook(dir, { env: { TASK_ORCHESTRATOR_API_URL: 'http://localhost:3001' } })).includes('different servers'));
    rmSync(join(dir, '.mcp.json'), { force: true });
    assert.ok(!msgOf(runHook(dir, { env: { TASK_ORCHESTRATOR_API_URL: 'http://localhost:3001' } })).includes('different servers'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('AC7: a project client.json source differing from the registration -> warning names project client.json', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: 11111111-2222-3333-4444-555555555555\n');
    writeFileSync(join(dir, '.taskorchestrator', 'client.json'), JSON.stringify({ apiUrl: 'http://localhost:3001' }));
    writeProjectMcpJson(dir, mismatchReg('http://localhost:3003/mcp'));
    // The registration sits below the project client.json in precedence, so the client.json is the source.
    const msg = msgOf(runHook(dir));
    assert.ok(msg.includes('hooks call the REST API at http://localhost:3001 (project client.json)'), msg);
    assert.ok(msg.includes('registered at http://localhost:3003 (.mcp.json)'), msg);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('AC7: a user client.json source differing from an unusable (remote .mcp.json) registration -> warning names user client.json', () => {
  const dir = tmpConfigDir();
  const toHome = trackedTmp('to-session-start-userclient-');
  try {
    mkdirSync(join(toHome, '.taskorchestrator'), { recursive: true });
    writeFileSync(join(toHome, '.taskorchestrator', 'client.json'), JSON.stringify({ apiUrl: 'http://other.example:1' }));
    writeProjectMcpJson(dir, mismatchReg('https://remote.example/mcp'));
    const msg = msgOf(runHook(dir, { toHome }));
    assert.ok(msg.includes('hooks call the REST API at http://other.example:1 (user client.json)'), msg);
    assert.ok(msg.includes('registered at https://remote.example (.mcp.json)'), msg);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('AC7 fail-open: unparseable .mcp.json and ~/.claude.json -> no warning, exit 0, valid output', () => {
  const dir = tmpConfigDir();
  try {
    writeFileSync(join(dir, '.mcp.json'), '{ not json', 'utf-8');
    writeFileSync(join(dir, '.claude.json'), '[[[', 'utf-8');
    const res = runHook(dir, { env: { TASK_ORCHESTRATOR_API_URL: 'http://localhost:3001' } });
    assert.equal(res.status, 0, res.stderr);
    const out = parse(res);
    assert.ok(out.hookSpecificOutput);
    assert.ok(!(out.systemMessage ?? '').includes('different servers'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
