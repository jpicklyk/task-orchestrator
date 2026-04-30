---
name: perf-review
description: Performance impact assessment for items with the needs-perf-review trait. Evaluates hot paths, query patterns, and measurement plans. Invoked via skillPointer when filling performance-baseline notes.
user-invocable: false
---

# Performance Review Framework

Evaluate performance impact of changes. This project is a Kotlin MCP server with SQLite via Exposed ORM, handling tool calls synchronously per request.

## Step 1: Hot Path Analysis

Identify which hot paths the change touches:
- [ ] **Per-request paths** — MCP tool execution (every tool call hits this). New work here adds latency to every request.
- [ ] **Per-item loops** — operations that iterate over items (search, overview, stalled-item detection). N+1 patterns here scale poorly.
- [ ] **Startup path** — server initialization, database schema creation, config loading. Affects container startup time.
- [ ] **Background operations** — cascade detection, dependency resolution. Runs inline, not async.

## Step 2: Database Query Patterns

- [ ] **N+1 queries** — does the change add a query inside a loop? (e.g., `countChildrenByRole` per child in overview). Count total queries for a typical operation.
- [ ] **Full table scans** — any `selectAll()` without filters on large tables?
- [ ] **Missing indexes** — new filter conditions that would benefit from an index?
- [ ] **Transaction scope** — are transactions held open longer than necessary?
- [ ] **Aggregate vs fetch-all** — using `SELECT COUNT(*)` with `GROUP BY` vs fetching all rows and counting in memory?

## Step 3: JSON/Serialization Cost

- [ ] **Large response payloads** — does the change add fields that significantly increase response size? (e.g., adding `childCounts` to every child in overview)
- [ ] **Repeated serialization** — same object serialized multiple times in one request?
- [ ] **String parsing** — `PropertiesHelper.extractTraits()` parses JSON on every call. Acceptable for small objects, flag if called in tight loops.

## Step 4: Complexity Analysis

- [ ] **What is N?** — identify the scaling variable (number of items, children, notes, dependencies)
- [ ] **Current complexity** — O(1), O(N), O(N*M)? Where does the change sit?
- [ ] **Realistic scale** — what's the expected N in practice? (Most projects: <100 items, <30 children per root)
- [ ] **Worst case** — what happens at 1000+ items? Does it degrade gracefully or hit a wall?

## Step 5: Measurement Plan

- [ ] **How to verify** — what should be measured before/after? (query count, response time, payload size)
- [ ] **Baseline** — document current performance for the affected operation
- [ ] **Acceptance threshold** — what's the maximum acceptable degradation?

## Output

Compose the `performance-baseline` note with findings from each step. This note is optional (`required: false`) — use it when the change touches known hot paths or adds significant new work.
