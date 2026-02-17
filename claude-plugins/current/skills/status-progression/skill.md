---
name: status-progression
description: Navigate role transitions using advance_item. Shows current role, gate status, and correct trigger to use.
---

# Status Progression — Current (v3)

Guide role transitions for a WorkItem.

## Steps

1. Call `get_context(itemId=<id>)` to see:
   - Current role
   - Gate status (canAdvance, missing notes)
   - Expected notes for the next phase

2. If `gateStatus.canAdvance = false`:
   - Fill missing notes with `manage_notes(operation="upsert", notes=[{itemId, key, role, body}])`
   - Re-call `get_context(itemId=<id>)` to verify gate is now open

3. Advance: `advance_item(itemId=<id>, trigger="start")` — or `"complete"` to jump to terminal

## Trigger Reference

| Trigger | Effect |
|---------|--------|
| start | QUEUE→WORK, WORK→REVIEW (or TERMINAL if no review schema), REVIEW→TERMINAL |
| complete | Any non-terminal → TERMINAL (checks all-phase gates) |
| cancel | Any non-terminal → TERMINAL (statusLabel="cancelled") |
| block | Any → BLOCKED (saves previousRole) |
| resume | BLOCKED → previousRole |
