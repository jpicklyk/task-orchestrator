// code-mode-helpers.mjs - small pure helpers for Code Mode REPL scripts.
//
// Purpose: a script, not the model, turns a Task Orchestrator failure or a guard refusal into a
// branchable value, builds fully qualified tool names, and formats a token file line without
// echoing the token.
//
// SELF-CONTAINMENT RULE: a recipe inlines these into a script prelude by source text, so every
// export is an `export function name(...) {...}` declaration; there is no import statement, no
// module-scope const, regex, helper or side effect (nested helpers and regex literals inside a
// function body are fine); no instanceof (a vm or script realm has its own Error and Object),
// duck-type instead; only standard globals (JSON, String, Number, RegExp, Object, Array).

export function toolKey(tool, server = 'mcp-task-orchestrator') {
  if (typeof tool !== 'string' || !/^[a-z][a-z0-9_]*$/.test(tool)) return null;
  if (typeof server !== 'string' || !/^[A-Za-z0-9_.-]+$/.test(server)) return null;
  if (server.indexOf('__') !== -1) return null;
  return 'mcp__' + server + '__' + tool;
}

export function parseErrorTail(err) {
  try {
    const candidates = [];
    if (typeof err === 'string') {
      candidates.push(err);
    } else if (err !== null && typeof err === 'object') {
      const m = err.message;
      if (typeof m === 'string') candidates.push(m);
      const e = err.error;
      if (typeof e === 'string') candidates.push(e);
    }
    const isPlain = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
    for (const text of candidates) {
      const tail = text.slice(text.lastIndexOf('\n') + 1);
      let parsed;
      try {
        parsed = JSON.parse(tail);
      } catch (_) {
        continue;
      }
      if (isPlain(parsed) && parsed.ok === false && isPlain(parsed.error)) return parsed;
    }
    return null;
  } catch (_) {
    return null;
  }
}

export function refusal(value) {
  try {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) return null;
    const P = 'Permission denied for';
    const hit = (s) => typeof s === 'string' && s.indexOf(P) !== -1;
    const build = (text, tool) => {
      const from = text.slice(text.indexOf(P));
      const nl = from.indexOf('\n');
      const line = (nl === -1 ? from : from.slice(0, nl)).trim();
      return { refused: true, tool, reason: line.slice(0, 300) };
    };
    const parseTool = (text) => {
      const m = /Permission denied for ([^\s:]+)/.exec(text);
      return m ? m[1] : null;
    };
    const message = value.message;
    const error = value.error;
    // a. Code Mode McpToolError
    if (typeof value.toolName === 'string') {
      if (hit(message)) return build(message, value.toolName);
      if (hit(error)) return build(error, value.toolName);
      return null;
    }
    // b. thrown Error whose message starts with the marker
    if (typeof message === 'string' && message.indexOf(P) === 0) {
      return build(message, parseTool(message));
    }
    // c. plain resolved object with a string error
    if (hit(error)) return build(error, parseTool(error));
    // d. $ result with a non-zero exit and the marker in stderr
    const names = ['exitCode', 'exit_code', 'code', 'status'];
    let exit;
    for (const n of names) {
      const v = value[n];
      if (typeof v === 'number' && !Number.isNaN(v)) {
        exit = v;
        break;
      }
    }
    if (exit !== undefined && exit !== 0 && hit(value.stderr)) {
      return build(value.stderr, parseTool(value.stderr));
    }
    return null;
  } catch (_) {
    return null;
  }
}

export function tokenLine(token) {
  if (typeof token !== 'string') throw new TypeError('token must be a string');
  const t = token.trim();
  if (t === '') throw new TypeError('token must not be empty');
  if (t.indexOf('\r') !== -1 || t.indexOf('\n') !== -1) {
    throw new TypeError('token must be a single line');
  }
  return t + '\n';
}
