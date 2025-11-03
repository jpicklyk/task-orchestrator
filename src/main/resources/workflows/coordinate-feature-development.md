---
name: coordinate-feature-development
description: Coordinate feature development through four phases using Skills for detailed guidance
version: "2.0.1"
---

# Coordinate Feature Development

**Purpose:** Coordinate end-to-end feature development through four phases, delegating to Skills for detailed guidance.

**When to Use:** User provides feature request (with or without PRD file) for end-to-end orchestration.

---

## Overview

This workflow coordinates four phases of feature development:

```
Phase 1: Feature Creation → Feature Orchestration Skill
Phase 2: Task Breakdown → Feature Orchestration Skill
Phase 3: Task Execution → Task Orchestration Skill
Phase 4: Feature Completion → Feature Orchestration Skill
```

**Skills provide:** Detailed templates, examples, error handling, decision logic
**Workflow provides:** Phase coordination and handoff points

---

## Pre-Flight Checklist

Before starting:
- [ ] Check for file path in user message (pass to subagents, don't read)
- [ ] Assess complexity: Simple (< 200 chars, < 3 tasks) or Complex (≥ 200 chars, ≥ 5 tasks)

---

## Phase 1: Feature Creation

**Objective:** Create feature with templates and sections, progress to planning status

**Action:**
```
Skill(command="feature-orchestration")

"Create feature: [user request]
File reference: [file path if provided, else 'None']
Project: [project UUID if known]

Guide me through feature creation."
```

**Skill Decides:**
- Simple feature → Create directly with templates
- Complex feature → Route to Feature Architect (Opus)

**Skill Provides:**
- Feature Architect prompt template (if needed)
- Template discovery guidance
- Section creation patterns
- Status progression (draft → planning)

**Output from Skill:**
```json
{
  "featureId": "uuid",
  "featureName": "Feature Name",
  "status": "planning",
  "readyForBreakdown": true
}
```

**Next:** Proceed to Phase 2 with featureId

---

## Phase 2: Task Breakdown

**Objective:** Break feature into domain-isolated tasks with dependencies

**Action:**
```
Skill(command="feature-orchestration")

"Break down feature [featureId] into tasks.
Feature name: [name]

Guide me through task breakdown."
```

**Skill Decides:**
- Simple breakdown (< 5 tasks) → Create tasks directly
- Complex breakdown (≥ 5 tasks) → Route to Planning Specialist (Sonnet)

**Skill Provides:**
- Planning Specialist prompt template (if needed)
- Task template discovery
- Domain isolation guidance
- Dependency creation patterns
- Execution graph generation

**Output from Skill:**
```json
{
  "featureId": "uuid",
  "tasksCreated": 8,
  "dependenciesCreated": 6,
  "executionGraph": {
    "batch1": {"parallel": true, "tasks": [...]},
    "batch2": {"parallel": false, "tasks": [...]}
  },
  "readyForExecution": true
}
```

**Next:** Proceed to Phase 3 with executionGraph

---

## Phase 3: Task Execution

**Objective:** Execute tasks in parallel batches, cascade through dependencies

**Pre-Execution Step (CRITICAL):**
```
Skill(command="feature-orchestration")

"Feature [featureName] (ID: [featureId]) has tasks ready to execute.

Cascade Event: tasks_ready_to_execute
Check if feature status needs to progress before launching specialists.

Use Status Progression Skill to determine and apply the appropriate next status based on workflow config."
```

**Why:** Feature status must reflect current phase. Uses cascade event system to determine next status (config-driven, not hardcoded).

**Action:**
```
Skill(command="task-orchestration")

"Execute tasks for feature [featureName].
Feature ID: [featureId]

Execution graph from Phase 2:
[paste executionGraph JSON]

Guide me through parallel execution."
```

**Skill Provides:**
- Trust execution graph pattern (OPTIMIZATION #6)
- Specialist routing (recommend_agent)
- Implementation Specialist prompt template
- Parallel launch instructions
- Progress monitoring
- Batch cascade logic

**Skill Monitors:**
- Batch 1: Launch specialists in parallel
- Wait for batch completion
- Cascade to Batch 2 when dependencies resolved
- Continue until all batches complete

**Output from Skill:**
```
Batch 1 Progress: 2/2 in-progress
✅ Batch 1 Complete: 2/2 completed
Cascade: Batch 2 unblocked
Launching Batch 2...
[...]
✅ All tasks complete: 8/8
```

**Next:** Proceed to Phase 4 when all tasks complete

---

## Phase 4: Feature Completion

**Objective:** Progress feature through testing and quality gates to completion

**Action:**
```
Skill(command="feature-orchestration")

"Complete feature [featureName].
All tasks finished: [taskCount] completed.

Guide me through completion."
```

**Skill Provides:**
- Cascade event detection (all_tasks_complete)
- Status Progression Skill delegation
- Quality gate execution
- Testing validation (if configured)

**Skill Progresses:**
1. Check workflow state: query_workflow_state
2. Detect cascade: all_tasks_complete
3. Delegate: Status Progression Skill
4. Progress: in-development → testing → completed
5. Quality gates: Run tests if configured

**Output from Skill:**
```json
{
  "featureId": "uuid",
  "status": "completed",
  "tasksCompleted": 8,
  "outcome": "success"
}
```

**Next:** Feature development complete

---

## Error Handling

**If any phase reports blocker:**

**Skill provides resolution guidance:**
- Feature Architect blocked → Direct fix or Senior Engineer
- Circular dependencies → Dependency Orchestration Skill
- Specialist blocked → Quick fix or escalation
- Status validation failed → Prerequisite guidance
- Invalid output format → Parse or re-prompt

**Your action:**
1. Review skill's guidance
2. Ask user if needed (use AskUserQuestion):
   ```
   AskUserQuestion(
     questions: [{
       question: "How would you like to resolve this blocker?",
       header: "Resolution",
       multiSelect: false,
       options: [
         {
           label: "Direct Fix",
           description: "I'll resolve the issue directly and continue"
         },
         {
           label: "Escalate",
           description: "Launch Senior Engineer to investigate and resolve"
         }
       ]
     }]
   )
   ```
3. Apply fix or launch Senior Engineer based on user choice
4. Retry phase

---

## Success Criteria

**Phase 1:** ✅ Feature created, status=planning
**Phase 2:** ✅ Tasks created, execution graph generated
**Phase 3:** ✅ All tasks completed, summaries populated (300-500 chars)
**Phase 4:** ✅ Feature completed, quality gates passed

---

## Quick Reference

**Invoke workflow:**
```
/coordinate_feature_development
```

**Skill invocations:**
- Phase 1 & 2: `Skill(command="feature-orchestration")`
- Phase 3: `Skill(command="task-orchestration")`
- Phase 4: `Skill(command="feature-orchestration")`

**All detailed guidance in Skills:**
- Feature Orchestration Skill: Phases 1, 2, 4
- Task Orchestration Skill: Phase 3
- Status Progression Skill: Status changes

---

## Usage Pattern

**Typical conversation:**
```
User: /coordinate_feature_development
AI: [Loads workflow] "Ready to coordinate feature development. What feature?"

User: "Create authentication feature from D:\requirements.md"
AI: [Follows Phase 1] "Loading Feature Orchestration Skill..."
    [Detects file path, launches Feature Architect]
    [Feature Architect completes]
    "✅ Phase 1 Complete: Feature created (ID: abc-123)"

    [Follows Phase 2] "Loading Feature Orchestration Skill for breakdown..."
    [Planning Specialist creates tasks + dependencies]
    "✅ Phase 2 Complete: 8 tasks created with execution graph"

    [Follows Phase 3] "Loading Task Orchestration Skill..."
    [Launches Implementation Specialists in parallel batches]
    "✅ Phase 3 Complete: All tasks completed"

    [Follows Phase 4] "Loading Feature Orchestration Skill for completion..."
    [Feature progresses through quality gates]
    "✅ Phase 4 Complete: Feature authentication completed"
```


