# Pattern Tracking & Continuous Improvement

**Purpose**: Track recurring issues and suggest systemic improvements to definitions.

**When**: After deviations detected, end of session

**Token Cost**: ~300-500 tokens

## Pattern Detection

### Recurrence Threshold

**Definition**: Issue is "recurring" if it happens 2+ times in session

**Why**: One-off issues may be anomalies; recurring issues indicate systemic problems

### Pattern Categories

1. **Routing Violations** - Skills bypassed
2. **Workflow Deviations** - Steps skipped
3. **Output Quality** - Verbose output, missing sections
4. **Dependency Errors** - Incorrect graph, circular dependencies
5. **Tag Issues** - Missing mappings, convention violations
6. **Token Waste** - Repeated inefficiency patterns

## Tracking Workflow

### Step 1: Detect Recurrence

```javascript
// Track issues across session
session.deviations = {
  routing: [],
  workflow: [],
  output: [],
  dependency: [],
  tag: [],
  token: []
}

// After each workflow, categorize deviations
for deviation in currentDeviations:
  category = categorize(deviation)
  session.deviations[category].push({
    timestamp: now(),
    entity: entityType,
    issue: deviation.issue,
    severity: deviation.severity
  })
}

// Detect patterns
patterns = []
for category, issues in session.deviations:
  grouped = groupByIssue(issues)
  for issueType, occurrences in grouped:
    if (occurrences.length >= 2) {
      patterns.push({
        category: category,
        issue: issueType,
        count: occurrences.length,
        entities: occurrences.map(o => o.entity),
        severity: determineSeverity(occurrences)
      })
    }
  }
}
```

### Step 2: Analyze Root Cause

```javascript
for pattern in patterns:
  rootCause = analyzeRootCause(pattern)
  // Returns: "Definition unclear", "Validation missing", "Template incomplete", etc.

  pattern.rootCause = rootCause
  pattern.systemic = isSystemic(rootCause)  // vs one-off orchestrator error
}
```

**Root Cause Types**:

- **Definition Unclear**: Instructions ambiguous or missing
- **Validation Missing**: No checkpoint to catch issue
- **Template Incomplete**: Template doesn't guide properly
- **Knowledge Gap**: Orchestrator unaware of pattern
- **Tool Limitation**: Current tools can't prevent issue

### Step 3: Generate Improvement Suggestions

```javascript
improvements = []

for pattern in patterns where pattern.systemic:
  suggestion = generateImprovement(pattern)
  improvements.push(suggestion)
}
```

**Improvement Types**:

#### Type 1: Definition Update

```javascript
{
  type: "Definition Update",
  file: "planning-specialist.md",
  section: "Step 5: Map Dependencies",
  issue: "Cross-domain tasks created (3 occurrences)",
  rootCause: "No validation step for domain isolation",
  suggestion: {
    add: `
### Validation Checkpoint: Domain Isolation

Before creating tasks, verify:
- [ ] Each task maps to ONE specialist domain
- [ ] No task mixes backend + frontend
- [ ] No task mixes database + API logic

If domain mixing detected, split into separate tasks.
    `,
    location: "After Step 3, before Step 4"
  },
  impact: "Prevents cross-domain tasks in future Planning Specialist executions"
}
```

#### Type 2: Validation Checklist

```javascript
{
  type: "Validation Checklist",
  file: "feature-architect.md",
  section: "Step 8: Return Handoff",
  issue: "Verbose handoff (2 occurrences, avg 400 tokens)",
  rootCause: "No token limit specified in definition",
  suggestion: {
    add: `
### Handoff Validation Checklist

Before returning:
- [ ] Token count < 100 (brief summary only)
- [ ] No code/detailed content in response
- [ ] Feature ID mentioned
- [ ] Next action clear

If output > 100 tokens, move details to feature sections.
    `,
    location: "End of Step 8"
  },
  impact: "Reduces Feature Architect output from 400 → 80 tokens (80% reduction)"
}
```

#### Type 3: Quality Gate

```javascript
{
  type: "Quality Gate",
  file: "planning-specialist.md",
  section: "Step 6: Create Tasks",
  issue: "Execution graph accuracy < 95% (2 occurrences)",
  rootCause: "No validation before returning graph",
  suggestion: {
    add: `
### Quality Gate: Graph Validation

Before returning execution graph:
1. Query actual dependencies via query_dependencies
2. Compare graph claims vs database reality
3. Verify accuracy >= 95%
4. If < 95%, correct graph before returning

This ensures graph quality baseline is met.
    `,
    location: "After Step 6, before Step 7"
  },
  impact: "Ensures execution graph accuracy >= 95% in all cases"
}
```

#### Type 4: Orchestrator Guidance

```javascript
{
  type: "Orchestrator Guidance",
  file: "CLAUDE.md",
  section: "Decision Gates",
  issue: "Status changes bypassed Status Progression Skill (2 occurrences)",
  rootCause: "Orchestrator unaware of mandatory pattern",
  suggestion: {
    add: `
### CRITICAL: Status Changes

**ALWAYS use Status Progression Skill for status changes**

❌ NEVER: manage_container(operation="setStatus", ...)
✅ ALWAYS: Use status-progression skill

**Why Critical**: Prerequisite validation required (summary length, dependencies, task counts)

**Triggers**:
- "mark complete"
- "update status"
- "move to [status]"
- "change status"
    `,
    location: "Decision Gates section, top priority"
  },
  impact: "Prevents status bypasses in future sessions"
}
```

### Step 4: Prioritize Improvements

```javascript
prioritized = improvements.sort((a, b) => {
  // Priority order:
  // 1. CRITICAL patterns (routing violations)
  // 2. Frequent patterns (count >= 3)
  // 3. High-impact patterns (affects multiple workflows)
  // 4. Easy fixes (checklist additions)

  score = {
    critical: a.severity == "CRITICAL" ? 100 : 0,
    frequency: a.count * 10,
    impact: estimateImpact(a) * 5,
    ease: estimateEase(a) * 2
  }

  return scoreB - scoreA  // Descending
})
```

## Session Summary

### End-of-Session Report

```markdown
## 📊 Session QA Summary

**Workflows Analyzed:** [count]
- Skills: [count]
- Subagents: [count]

**Quality Overview:**
- ✅ Successful: [count] (no issues)
- ⚠️ Issues: [count] (addressed)
- 🚨 Critical: [count] (require attention)

### Deviation Breakdown
- Routing violations: [count]
- Workflow deviations: [count]
- Output quality: [count]
- Dependency errors: [count]
- Tag issues: [count]
- Token waste: [count]

### Recurring Patterns ([count])

**🔁 Pattern: Cross-domain tasks**
- Occurrences: [count] (Planning Specialist)
- Root cause: No domain isolation validation
- Impact: Tasks can't be routed to single specialist
- **Suggestion**: Update planning-specialist.md Step 3 with validation checklist

**🔁 Pattern: Status change bypasses**
- Occurrences: [count] (Orchestrator)
- Root cause: Decision gates not prominent enough
- Impact: Prerequisites not validated
- **Suggestion**: Update CLAUDE.md Decision Gates section

### Improvement Recommendations ([count])

**Priority 1: [Improvement Title]**
- File: [definition-file.md]
- Type: [Definition Update / Validation Checklist / Quality Gate]
- Impact: [What this prevents/improves]
- Effort: [Low / Medium / High]

**Priority 2: [Improvement Title]**
- File: [definition-file.md]
- Type: [...]
- Impact: [...]

### Quality Trends
- Graph quality: [X]% average (baseline 70%, target 95%+)
- Tag coverage: [Y]% average (baseline 90%, target 100%)
- Token efficiency: [Z]% average
- Workflow adherence: [W]% average

### Next Steps
1. [Most critical improvement]
2. [Secondary improvement]
3. [Optional enhancement]
```

## Continuous Improvement Cycle

### Cycle 1: Detection (This Session)
- Track deviations as they occur
- Detect recurring patterns (2+ occurrences)
- Analyze root causes

### Cycle 2: Analysis (End of Session)
- Generate improvement suggestions
- Prioritize by impact and ease
- Present to user with recommendations

### Cycle 3: Implementation (User Decision)
- User approves definition updates
- Apply changes to source files
- Document changes in version control

### Cycle 4: Validation (Next Session)
- Verify improvements are effective
- Track if recurring patterns reduced
- Measure quality metric improvements

## Metrics to Track

### Quality Metrics (Per Session)
- Workflow adherence: [X]%
- Graph quality: [X]%
- Tag coverage: [X]%
- Token efficiency: [X]%

### Pattern Metrics (Across Sessions)
- Recurring pattern count: [decreasing trend = good]
- Definition update count: [applied improvements]
- Quality improvement: [metrics increasing over time]

## Integration with QA Skill

```javascript
// At end of session
orchestration-qa(
  phase="summary",
  sessionId=currentSession
)

// Returns:
{
  workflowsAnalyzed: 8,
  deviationsSummary: { ALERT: 2, WARN: 5, INFO: 3 },
  recurringPatterns: 2,
  improvements: [
    { priority: 1, file: "planning-specialist.md", impact: "high" },
    { priority: 2, file: "CLAUDE.md", impact: "high" }
  ],
  qualityTrends: {
    graphQuality: "92%",
    tagCoverage: "98%",
    tokenEfficiency: "87%"
  }
}
```

## When to Report

- **After deviations**: Track pattern occurrence
- **End of session**: Generate summary if patterns detected
- **User request**: "Show QA summary", "Any improvements?"

## Output Size

- Pattern tracking: ~100-200 tokens per pattern
- Session summary: ~400-800 tokens total
- Improvement suggestions: ~200-400 tokens per suggestion
