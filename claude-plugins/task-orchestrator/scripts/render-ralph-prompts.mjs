#!/usr/bin/env node
// Renders the one ralph outcome table (OUTCOME_TABLE in scripts/ralph-lib.mjs) into the three
// ralph prompt sources, between the generated markers, so they can never contradict each other:
//
//   skills/ralph/SKILL.md, skills/ralph/iteration-prompt.md, skills/ralph/iteration-system-prompt.md
//
//   node claude-plugins/task-orchestrator/scripts/render-ralph-prompts.mjs           # rewrite in place
//   node claude-plugins/task-orchestrator/scripts/render-ralph-prompts.mjs --check   # verify only; exit 1 on drift
//
// Modelled on _fragments/generate.mjs: only the marked region changes and the file's own line-ending
// style is preserved. A missing file or missing/malformed markers is an error (exit 1).
// Stdlib-only. Node 18+.

import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { renderOutcomeTable } from "./ralph-lib.mjs";

export const MARKER = "ralph-outcomes";
export const BEGIN = `<!-- BEGIN GENERATED:${MARKER} -->`;
export const END = `<!-- END GENERATED:${MARKER} -->`;

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const TARGETS = Object.freeze([
    path.resolve(HERE, "../skills/ralph/SKILL.md"),
    path.resolve(HERE, "../skills/ralph/iteration-prompt.md"),
    path.resolve(HERE, "../skills/ralph/iteration-system-prompt.md"),
]);

const norm = (s) => s.replace(/\r\n/g, "\n");

/**
 * Rebuild `text` with `table` between the markers. Returns `{text}` (LF line endings) or
 * `{error}` when the markers are missing or END does not follow BEGIN on a later line.
 */
export function rebuild(text, table = renderOutcomeTable()) {
    const t = norm(text);
    const bi = t.indexOf(BEGIN);
    const ei = t.indexOf(END);
    if (bi === -1 || ei === -1) return { error: "markers not found" };
    const beginLineEnd = t.indexOf("\n", bi);
    if (beginLineEnd === -1 || ei <= beginLineEnd) return { error: "malformed markers (END must follow BEGIN on a later line)" };
    return { text: t.slice(0, beginLineEnd + 1) + table + t.slice(ei) };
}

/** Sync (or, with check, verify) every target. Returns the process exit code. */
export function main({ check = false, targets = TARGETS, log = console.log, err = console.error } = {}) {
    const table = renderOutcomeTable();
    let failures = 0;
    let changed = 0;
    for (const file of targets) {
        if (!existsSync(file)) {
            err(`ERROR: target missing: ${file}`);
            failures++;
            continue;
        }
        const raw = readFileSync(file, "utf8");
        const r = rebuild(raw, table);
        if (r.error) {
            err(`ERROR: ${r.error} in ${file}`);
            failures++;
            continue;
        }
        if (r.text === norm(raw)) continue;
        if (check) {
            err(`OUT OF SYNC: ${file} - run: node claude-plugins/task-orchestrator/scripts/render-ralph-prompts.mjs`);
            failures++;
            continue;
        }
        const eol = raw.includes("\r\n") ? "\r\n" : "\n";
        writeFileSync(file, eol === "\n" ? r.text : r.text.replace(/\n/g, eol));
        log(`updated: ${file}`);
        changed++;
    }
    if (failures > 0) return 1;
    if (!check) log(changed === 0 ? "all ralph prompt sources already in sync" : `${changed} file(s) updated`);
    return 0;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    process.exit(main({ check: process.argv.includes("--check") }));
}
