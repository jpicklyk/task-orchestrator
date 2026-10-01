import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, existsSync, realpathSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join, isAbsolute } from 'node:path';

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
    writeConfig(dir, 'retrospective:\n  mode: nudge\n');
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
const HTTP_REG = {
  mcpServers: {
    'mcp-task-orchestrator': { type: 'http', url: 'http://host/task-orchestrator/mcp' },
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
    writeConfig(dir, 'retrospective:\n  mode: nudge\n');
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
    writeConfig(dir, 'retrospective:\n  mode: nudge\n');
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

const BASE = [
  '## Task Orchestrator — Session Context',
  '',
  '- Use `advance_item` for role transitions — not raw status edits.',
  '- Hierarchy: items have parentId and depth; trees nest to any depth.',
  '- To resume: call `get_context()` with no args to see active and stalled items.',
].join('\n');

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

const label = (r, n) => (n ? n + ' (`' + r + '`)' : '`' + r + '`');

const t3 = (r, n, p) => [
  '## Personal Scope',
  '',
  'Personal root: ' + label(r, n),
  'Config: ' + p,
  '',
  '- Anchor new root-level items under the personal root by setting `parentId: "' + r + '"`.',
  "- Do NOT pass `ancestorId` on reads (`query_items`, `get_next_item`, `get_context`, `get_blocked_items`) — the personal root is a global store, so reads stay unscoped and may return other projects' items.",
  '- Process-global items stay OUTSIDE the personal root at depth 0: the Session Retrospectives and Improvement Proposals containers, and standalone agent-observation items.',
].join('\n');

const t4 = (r, n, p) => [
  '## Project Scope',
  '',
  'Active project: ' + label(r, n),
  'Config: ' + p,
  '',
  '- Pass `ancestorId: "' + r + '"` on `query_items` (list mode), `get_next_item`, `get_context`, and `get_blocked_items` to scope results to this project.',
  '- Anchor new root-level items under this project by setting `parentId: "' + r + '"`.',
  '- Process-global items stay OUTSIDE the project root at depth 0: the Session Retrospectives and Improvement Proposals containers, and standalone agent-observation items — do not anchor any of them under `' + r + '`.',
].join('\n');

const T5 = 'Task Orchestrator is not set up for this directory — run /task-orchestrator:init to configure it.';
const t6Project = (p) => 'Task Orchestrator config at ' + p + ' has no project.rootId — run /task-orchestrator:init to finish setup.';
const t6User = (p) => 'Task Orchestrator config at ' + p + ' has no project.rootId — run /task-orchestrator:init --user to finish setup.';

const T7_PROJECT = [
  '## Config Sync',
  '',
  'Config-sync cannot push this config because no REST API URL resolves. Set `TASK_ORCHESTRATOR_API_URL` or run `/task-orchestrator:init` (writes `client.json`).',
].join('\n');
const T7_USER = [
  '## Config Sync',
  '',
  'Config-sync cannot push this config because no REST API URL resolves. Set `TASK_ORCHESTRATOR_API_URL` or run `/task-orchestrator:init --user` (writes `client.json`).',
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

test('S1 none state -> additionalContext is exactly BASE + Setup Status (T1), no systemMessage', () => {
  const dir = tmpConfigDir();
  try {
    const out = parse(runHook(dir));
    assert.equal(out.hookSpecificOutput.additionalContext, BASE + '\n\n' + T1);
    assert.ok(!('systemMessage' in out));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('S2 no-root state, project file -> exactly BASE + T2a', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'retrospective:\n  mode: nudge\n');
    assert.equal(ctxOf(runHook(dir)), BASE + '\n\n' + t2a(cfgPath(dir)));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('S3 no-root state, user file -> exactly BASE + T2b; watchPaths = [user path]', () => {
  const dir = tmpConfigDir();
  const toHome = tmpConfigDir();
  try {
    writeConfig(toHome, 'retrospective:\n  mode: nudge\n');
    const out = parse(runHook(dir, { toHome }));
    assert.equal(out.hookSpecificOutput.additionalContext, BASE + '\n\n' + t2b(cfgPath(toHome)));
    assert.deepEqual(out.hookSpecificOutput.watchPaths, [cfgPath(toHome)]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(toHome, { recursive: true, force: true });
  }
});

test('S4 project state with name -> exactly BASE + T4', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: proj-exact-1\n  name: "Exact One"\n');
    assert.equal(ctxOf(runHook(dir)), BASE + '\n\n' + t4('proj-exact-1', 'Exact One', cfgPath(dir)));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('S4b project state without name -> label is just the backticked rootId', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: proj-exact-2\n');
    assert.equal(ctxOf(runHook(dir)), BASE + '\n\n' + t4('proj-exact-2', null, cfgPath(dir)));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('S5 user state -> exactly BASE + T3', () => {
  const dir = tmpConfigDir();
  const toHome = tmpConfigDir();
  try {
    writeConfig(toHome, 'project:\n  rootId: personal-exact-1\n  name: "Me"\n');
    assert.equal(ctxOf(runHook(dir, { toHome })), BASE + '\n\n' + t3('personal-exact-1', 'Me', cfgPath(toHome)));
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
    writeConfig(dir, 'retrospective:\n  mode: nudge\n');
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
    writeConfig(toHome, 'retrospective:\n  mode: nudge\n');
    const out = parse(runHook(dir, { toHome }));
    assert.equal(out.systemMessage, t6User(cfgPath(toHome)));
    assert.equal(out.hookSpecificOutput.additionalContext, BASE + '\n\n' + t2b(cfgPath(toHome)));
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(toHome, { recursive: true, force: true });
  }
});

// ── Config Sync notice, exact text and gating ────────────────────────────

test('S9 project state + http registration, no API URL -> BASE + T4 + T7 (project command)', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: proj-sync-1\n  name: "Sync One"\n');
    writeUserClaudeJson(dir, HTTP_REG);
    assert.equal(
      ctxOf(runHook(dir)),
      BASE + '\n\n' + t4('proj-sync-1', 'Sync One', cfgPath(dir)) + '\n\n' + T7_PROJECT,
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('S10 user state + http registration, no API URL -> BASE + T3 + T7 naming init --user', () => {
  const dir = tmpConfigDir();
  const toHome = tmpConfigDir();
  try {
    writeConfig(toHome, 'project:\n  rootId: personal-sync-1\n  name: "Me"\n');
    writeUserClaudeJson(dir, HTTP_REG);
    assert.equal(
      ctxOf(runHook(dir, { toHome })),
      BASE + '\n\n' + t3('personal-sync-1', 'Me', cfgPath(toHome)) + '\n\n' + T7_USER,
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
    assert.equal(ctxOf(runHook(dir)), BASE + '\n\n' + t4('proj-sync-2', 'Sync Two', cfgPath(dir)));
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
    assert.equal(ctxOf(runHook(dir, { toHome })), BASE + '\n\n' + t4('proj-sync-3', 'Sync Three', cfgPath(dir)));
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
    assert.equal(ctxOf(runHook(dir, { toHome })), BASE + '\n\n' + t3('personal-sync-2', 'Me', cfgPath(toHome)));
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
      assert.equal(
        ctxOf(runHook(dir)),
        BASE + '\n\n' + t4('proj-sync-4', 'Sync Four', cfgPath(dir)) + '\n\n' + T7_PROJECT,
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
    assert.equal(ctxOf(runHook(dir)), BASE + '\n\n' + T1);
    writeConfig(dir, 'retrospective:\n  mode: nudge\n');
    assert.equal(ctxOf(runHook(dir)), BASE + '\n\n' + t2a(cfgPath(dir)));
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
      assert.equal(
        ctxOf(runHook(dir)),
        BASE + '\n\n' + t4('proj-probe-1', 'Probe', cfgPath(dir)) + '\n\n' + T7_PROJECT,
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
    assert.equal(ctxOf(runHook(dir)), BASE + '\n\n' + t4('proj-probe-2', 'Probe', cfgPath(dir)));
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
      const expected = BASE + '\n\n' + t4('proj-probe-3', 'Probe', cfgPath(dir)) + (expectNotice ? '\n\n' + T7_PROJECT : '');
      assert.equal(ctxOf(runHook(dir)), expected, 'apiUrl: ' + apiUrl);
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
