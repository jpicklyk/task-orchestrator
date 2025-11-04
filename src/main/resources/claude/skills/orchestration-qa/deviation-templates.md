# Deviation Report Templates

**Purpose**: Format QA findings for user presentation based on severity.

**When**: After deviations detected in post-execution review

**Token Cost**: ~200-400 tokens

## Severity Levels

### 🚨 ALERT (Critical)
**Impact**: Affects functionality, correctness, or mandatory patterns
**Action**: Report immediately, add to TodoWrite, request user decision
**Examples**:
- Status change bypassed Status Progression Skill
- Cross-domain task detected (violates domain isolation)
- PRD sections not extracted (requirements lost)
- Incorrect dependencies in execution graph
- Task has no specialist mapping (routing will fail)

### ⚠️ WARN (Process Issue)
**Impact**: Process not followed optimally, should be addressed
**Action**: Include in post-execution report, add to TodoWrite
**Examples**:
- Workflow step skipped (non-critical)
- Output too verbose (token waste)
- Templates not applied when available
- Missed parallel opportunities
- Tags don't follow project conventions

### ℹ️ INFO (Observation)
**Impact**: Optimization opportunity or quality pattern
**Action**: Log for pattern tracking, mention if noteworthy
**Examples**:
- Token usage outside expected range (but reasonable)
- Could use more efficient tool (overview vs get)
- Format improvement suggestions
- Efficiency opportunities identified

## Report Templates

### ALERT Template (Critical Violation)

```markdown
## 🚨 QA Review: [Entity Name] - CRITICAL ISSUES DETECTED

**Workflow Adherence:** [X]/[Y] steps ([Z]%)

### Critical Issues ([count])

**❌ ALERT: [Issue Title]**

**What Happened:**
[Clear description of what was observed]

**Expected Behavior:**
[What should have happened according to documentation]

**Impact:**
[What this affects - functionality, correctness, workflow]

**Evidence:**
- [Specific evidence from output/database]
- [Tool calls made or not made]
- [Data discrepancies]

**Recommendation:**
[Specific action to fix the issue]

**Definition Update Needed:**
[If this is a pattern, what definition needs updating]

---

### ✅ Successes ([count])
- [What went well]
- [Patterns followed correctly]

### 📋 Added to TodoWrite
- [ ] Review [Entity]: [Issue description]
- [ ] Fix [specific issue]
- [ ] Update [definition file] with [improvement]

### 💭 Decision Required

**Question:** [What user needs to decide]

**Options:**
1. [Option A with pros/cons]
2. [Option B with pros/cons]
3. [Option C with pros/cons]

**Recommendation:** [Your suggestion with reasoning]
```

### WARN Template (Process Issue)

```markdown
## ⚠️ QA Review: [Entity Name] - Issues Found

**Workflow Adherence:** [X]/[Y] steps ([Z]%)

### Issues Detected ([count])

**⚠️ WARN: [Issue Title]**
- **Found:** [What was observed]
- **Expected:** [What should have happened]
- **Impact:** [How this affects quality/efficiency]
- **Fix:** [How to correct]

**⚠️ WARN: [Issue Title 2]**
- **Found:** [What was observed]
- **Expected:** [What should have happened]

### ✅ Successes
- [Workflow adherence: X/Y steps]
- [Quality metrics: X% graph quality, Y% tag coverage]

### 📋 Added to TodoWrite
- [ ] [Issue 1 to address]
- [ ] [Issue 2 to address]

### 🎯 Recommendations
1. [Most important fix]
2. [Process improvement]
3. [Optional optimization]
```

### INFO Template (Observations)

```markdown
## ℹ️ QA Review: [Entity Name] - Observations

**Workflow Adherence:** [X]/[Y] steps ([Z]%)

### Quality Metrics
- Dependency Accuracy: [X]%
- Parallel Completeness: [Y]%
- Tag Coverage: [Z]%
- Token Efficiency: [W]%

### Observations ([count])

**ℹ️ Efficiency Opportunity: [Title]**
- Current approach: [What was done]
- Optimal approach: [Better way]
- Potential savings: [Benefit]

**ℹ️ Format Suggestion: [Title]**
- Current: [What was done]
- Suggested: [Improvement]

### ✅ Overall Assessment
Workflow completed successfully with minor optimization opportunities.

[Optional: Include observations in session summary]
```

### Success Template (No Issues)

```markdown
## ✅ QA Review: [Entity Name]

**Workflow Adherence:** 100% ([Y]/[Y] steps completed)

**Quality Metrics:**
- All checkpoints passed ✅
- All expected outputs present ✅
- Token usage within range ✅
- Workflow patterns followed ✅

[If efficiency analysis enabled:]
**Efficiency:**
- Token efficiency: [X]%
- Optimal tool selection ✅
- Parallel opportunities identified ✅

**Result:** No issues detected - excellent execution!
```

## TodoWrite Integration

### ALERT Issues

```javascript
TodoWrite([
  {
    content: `ALERT: [Entity] - [Critical issue summary]`,
    activeForm: `Reviewing [Entity] critical issue`,
    status: "pending"
  },
  {
    content: `Fix: [Specific corrective action]`,
    activeForm: `Fixing [issue]`,
    status: "pending"
  },
  {
    content: `Update [definition]: [Improvement needed]`,
    activeForm: `Updating definition`,
    status: "pending"
  }
])
```

### WARN Issues

```javascript
TodoWrite([
  {
    content: `Review [Entity]: [Issue summary] ([count] issues)`,
    activeForm: `Reviewing [Entity] quality issues`,
    status: "pending"
  }
])
```

### INFO Observations

```javascript
// Generally don't add INFO to TodoWrite unless noteworthy
// Track for pattern analysis instead
```

## Multi-Issue Aggregation

When multiple issues of same type detected:

```markdown
### Cross-Domain Tasks Detected ([count])

**Pattern:** Tasks mixing specialist domains

**Violations:**
1. **[Task A]:** Combines [domain1] + [domain2]
   - Evidence: [description mentions both]
   - Fix: Split into 2 tasks

2. **[Task B]:** Combines [domain2] + [domain3]
   - Evidence: [tags include both]
   - Fix: Split into 2 tasks

**Root Cause:** [Why this happened - e.g., feature requirements not decomposed properly]

**Systemic Fix:** Update [planning-specialist.md] to enforce domain isolation check before task creation

**Added to TodoWrite:**
- [ ] Split Task A into domain-isolated tasks
- [ ] Split Task B into domain-isolated tasks
- [ ] Update planning-specialist.md validation checklist
```

## User Decision Prompts

### Template 1: Retry with Correct Approach

```markdown
### 💭 Decision Required

**Issue:** [Entity] bypassed mandatory [Skill Name] Skill

**Impact:** [What validation was skipped]

**Options:**

1. **Retry with [Skill Name] Skill** ✅ Recommended
   - Pros: Ensures validation runs, follows documented workflow
   - Cons: Requires re-execution

2. **Accept as-is and manually verify**
   - Pros: Faster (no re-execution)
   - Cons: May miss validation issues, sets bad precedent

3. **Update [Entity] to bypass Skill** ⚠️ Not Recommended
   - Pros: Allows direct approach
   - Cons: Removes safety checks, violates workflow

**Recommendation:** Retry with [Skill Name] Skill to ensure [prerequisites] are validated.

**Your choice?**
```

### Template 2: Definition Update

```markdown
### 💭 Decision Required

**Pattern Detected:** [Issue] occurred [N] times in session

**Systemic Issue:** [Root cause analysis]

**Proposed Definition Update:**

```diff
// File: [definition-file.md]

+ Add validation checklist:
+ - [ ] Verify all independent tasks in Batch 1
+ - [ ] Check for cross-domain tasks before creation
+ - [ ] Validate tag → specialist mapping coverage
```

**Options:**

1. **Update definition now** ✅ Recommended
   - Prevents recurrence
   - Improves workflow quality

2. **Track for later review**
   - Allows more data collection
   - May recur in meantime

**Your preference?**
```

## Formatting Guidelines

### Clarity
- Start with severity emoji (🚨/⚠️/ℹ️)
- Use clear section headers
- Separate concerns (issues, successes, recommendations)

### Actionability
- Specific evidence, not vague observations
- Clear "Expected" vs "Found" comparisons
- Concrete recommendations with steps

### Brevity
- ALERT: Full details (this is critical)
- WARN: Moderate details (important but not urgent)
- INFO: Brief summary (observations only)

### Consistency
- Always include workflow adherence percentage
- Always show count of issues by severity
- Always provide TodoWrite summary
- Always offer recommendations

## Output Size Targets

- **ALERT report**: 300-600 tokens (comprehensive)
- **WARN report**: 200-400 tokens (focused)
- **INFO report**: 100-200 tokens (brief)
- **Success report**: 50-100 tokens (minimal)

**Total QA report** (including analysis): 800-2000 tokens depending on issues found
