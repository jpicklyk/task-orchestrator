# Orchestration QA Skill - Usage Examples

This document provides practical examples of using the orchestration-qa skill in different scenarios.

## Example 1: Interactive Configuration (FIRST TIME)

### Scenario
First time using orchestration-qa in a session - choose which analyses to enable

### Usage
```javascript
orchestration-qa(phase="configure")
```

### Interactive Prompts
Agent presents multiselect options:

```markdown
## Quality Analysis Configuration

**Which quality analysis categories would you like to enable for this session?**
(Select all that apply)

☐ Information Density
  Analyze task content quality, detect wasteful patterns, measure information-to-token ratio (Specialists only)

☐ Execution Graphs
  Validate dependency graphs and parallel execution opportunities (Planning Specialist only)

☐ Tag Coverage
  Check tag consistency and agent-mapping coverage (Planning Specialist & Feature Architect)

☐ Token Optimization
  Identify token waste patterns (verbose output, unnecessary loading, redundant operations)

☐ Tool Selection
  Verify optimal tool usage (overview vs get, search vs filtered query, bulk operations)

☑ Routing Validation
  Detect Skills bypass violations (CRITICAL - status changes, feature creation, task execution)

☐ Parallel Detection
  Find missed parallelization opportunities (independent tasks, batch operations)

---

**How detailed should QA reports be?**

○ Brief - Only show critical issues (ALERT level) - minimal token usage
● Standard - Show ALERT and WARN level issues with brief explanations
○ Detailed - Show all issues (ALERT/WARN/INFO) with full analysis and recommendations
```

### User Selections
```javascript
// User selects:
{
  categories: ["Information Density", "Routing Validation"],
  reportStyle: "Standard"
}
```

### Output
```markdown
✅ **QA Configuration Saved**

**Enabled Categories (2):**
- ✅ Information Density - Track task content quality and wasteful patterns
- ✅ Routing Validation - Detect critical Skills bypass violations

**Disabled Categories (5):**
- ⊘ Execution Graphs
- ⊘ Tag Coverage
- ⊘ Token Optimization
- ⊘ Tool Selection
- ⊘ Parallel Detection

**Report Style:** Standard (ALERT + WARN)

**Token Efficiency:**
- Per analysis: ~1,200 tokens (vs ~3,500 comprehensive)
- Savings: 67% reduction from full analysis
- Trade-off: Focus on content quality + critical routing only

Configuration will be used for all QA reviews in this session.
You can reconfigure anytime with `orchestration-qa(phase="configure")`
```

## Example 2: Session Initialization (With Configuration)

### Scenario
After configuration, initialize the session with knowledge bases

### Prerequisites
Configuration must be set first (via `phase="configure"`)

### Usage
```javascript
orchestration-qa(phase="init")
```

### Output
```markdown
✅ **Orchestration QA Initialized**

**Knowledge Base Loaded:**
- Skills: 5 (feature-orchestration, task-orchestration, status-progression, dependency-analysis, dependency-orchestration)
- Subagents: 8 (feature-architect, planning-specialist, backend-engineer, frontend-developer, database-engineer, test-engineer, technical-writer, bug-triage-specialist)
- Routing: agent-mapping.yaml loaded (12 tag mappings)

**Active Configuration:**
- ✅ Information Density (track task content quality)
- ✅ Routing Validation (critical bypass detection)
- ⊘ Execution Graphs (disabled)
- ⊘ Tag Coverage (disabled)
- ⊘ Token Optimization (disabled)
- ⊘ Tool Selection (disabled)
- ⊘ Parallel Detection (disabled)
- Report Style: Standard (ALERT + WARN)

**Quality Assurance Active:**
- ✅ Pre-execution validation
- ✅ Post-execution review
- ✅ Configuration-driven analysis (2 categories enabled)
- ✅ Pattern tracking (continuous improvement)

**Session Tracking:**
- Deviations: 0 ALERT, 0 WARN, 0 INFO
- Patterns: 0 recurring issues
- Improvements: 0 suggestions

Ready to monitor orchestration quality.
**Token Efficiency:** ~1,200 tokens per analysis (67% savings from comprehensive)
```

## Example 3: Feature Architect Validation (PRD Mode)

### Scenario
User provides formal PRD document for feature creation

### User Request
```markdown
Create a user authentication feature:

# User Authentication

## Business Context
We need secure user authentication for our platform.

## User Stories
- As a user, I want to sign up with email/password
- As a user, I want to log in securely
- As a user, I want to reset my password

## Technical Requirements
- OAuth2 support
- JWT tokens
- Role-based access control
```

### Pre-Execution
```javascript
orchestration-qa(
  phase="pre",
  entityType="feature-architect",
  userInput="[full PRD above]"
)
```

### Pre-Execution Output
```javascript
{
  ready: true,
  contextCaptured: true,
  entityType: "feature-architect",
  category: "SUBAGENT",
  checkpoints: 8,
  expected: {
    mode: "PRD",
    prdSections: ["Business Context", "User Stories", "Technical Requirements"],
    descriptionLength: [500, 1000],
    sectionsExpected: 3,
    handoffTokens: [50, 100]
  }
}
```

### Post-Execution
```javascript
orchestration-qa(
  phase="post",
  entityType="feature-architect",
  entityOutput="[Feature Architect's response]",
  entityId="feature-uuid-123"
)
```

### Post-Execution Output (Success)
```markdown
## ✅ QA Review: Feature Architect (PRD Mode)

**Workflow Adherence:** 8/8 steps (100%)
**Expected Outputs:** 7/7 present

### Validation Results
- ✅ PRD mode detected correctly
- ✅ All 3 PRD sections extracted to feature sections
- ✅ Description forward-looking (623 chars)
- ✅ Templates applied (Technical Approach, Requirements)
- ✅ Tags follow project conventions (reused "authentication", "security")
- ✅ agent-mapping.yaml checked (tags map to specialists)
- ✅ Handoff minimal (87 tokens)
- ✅ Core concepts preserved from PRD

**Quality Metrics:**
- PRD extraction: 100% (3/3 sections)
- Token efficiency: 95% (handoff 87 tokens, expected < 100)
- Tag conventions: 100% (reused existing tags)

**Result:** Excellent execution - all patterns followed correctly!
```

### Post-Execution Output (Issues Detected)
```markdown
## 🚨 QA Review: Feature Architect (PRD Mode) - ISSUES DETECTED

**Workflow Adherence:** 7/8 steps (87%)

### Critical Issues (1)

**❌ ALERT: PRD Sections Incomplete**

**What Happened:**
Feature has 2 sections, but PRD contained 3 sections.

**Expected Behavior:**
In PRD mode, Feature Architect must extract ALL sections from user's document.

**Impact:**
"Technical Requirements" section from PRD was not transferred to feature.
Requirements may be lost or incomplete.

**Evidence:**
- PRD sections: ["Business Context", "User Stories", "Technical Requirements"]
- Feature sections: ["Business Context", "User Stories"]
- Missing: "Technical Requirements"

**Recommendation:**
Add missing "Technical Requirements" section to feature.

**Definition Update Needed:**
Update feature-architect.md Step 7 to include validation:
- [ ] Verify all PRD sections extracted
- [ ] Compare PRD section count vs feature section count
- [ ] If mismatch, add missing sections before returning

---

### ✅ Successes (6)
- PRD mode detected correctly
- Description forward-looking
- Templates applied
- Tags follow conventions
- agent-mapping.yaml checked
- Handoff minimal (92 tokens)

### 📋 Added to TodoWrite
- [ ] Add "Technical Requirements" section to Feature [ID]
- [ ] Update feature-architect.md Step 7 validation checklist

### 💭 Decision Required

**Question:** Should we add the missing "Technical Requirements" section now?

**Options:**
1. **Add section now** ✅ Recommended
   - Pros: Ensures all PRD content captured
   - Cons: Requires one additional tool call

2. **Accept as-is**
   - Pros: Faster (no additional work)
   - Cons: Requirements may be incomplete

**Recommendation:** Add section now to ensure complete PRD capture.

**Your choice?**
```

## Example 4: Planning Specialist Review (Graph Analysis)

### Scenario
Planning Specialist breaks down feature into tasks

### Post-Execution
```javascript
orchestration-qa(
  phase="post",
  entityType="planning-specialist",
  entityOutput="[Planning Specialist's response]",
  entityId="feature-uuid-123"
)
```

### Output (High Quality)
```markdown
## ✅ QA Review: Planning Specialist

**Workflow Adherence:** 8/8 steps (100%)
**Expected Outputs:** 7/7 present

### Specialized Analysis

**📊 Execution Graph Quality: 98%**
- Dependency Accuracy: 100% (all dependencies correct)
- Parallel Completeness: 100% (all opportunities identified)
- Format Clarity: 95% (clear batch numbers, explicit dependencies)

**🏷️ Tag Quality: 100%**
- Tag Coverage: 100% (all tasks have tags)
- Agent Mapping Coverage: 100% (all tags map to specialists)
- Convention Adherence: 100% (reused existing tags)

### Quality Metrics
- Domain isolation: ✅ (one task = one specialist)
- Dependencies mapped: ✅ (Database → Backend → Frontend pattern)
- Documentation task: ✅ (user-facing feature)
- Testing task: ✅ (created)
- No circular dependencies: ✅
- Templates applied: ✅

**Result:** Excellent execution - target quality (95%+) achieved!
```

### Output (Issues Detected)
```markdown
## ⚠️ QA Review: Planning Specialist - Issues Found

**Workflow Adherence:** 8/8 steps (100%)

### Specialized Analysis

**📊 Execution Graph Quality: 73%**
- Dependency Accuracy: 67% (2/3 dependencies incorrect)
- Parallel Completeness: 67% (1 opportunity missed)
- Format Clarity: 85% (some ambiguous notation)

**🏷️ Tag Quality: 92%**
- Tag Coverage: 100% (all tasks have tags)
- Agent Mapping Coverage: 100% (all tags map to specialists)
- Convention Adherence: 75% (1 new tag without agent-mapping check)

### Issues Detected (4)

**🚨 ALERT: Incorrect Dependency**
- Task: "Implement backend API"
- Expected blocked by: ["Create database schema", "Design API endpoints"]
- Found in graph: ["Design API endpoints"]
- **Missing:** "Create database schema"
- **Impact:** Task might start before database ready

**🚨 ALERT: Cross-Domain Task**
- Task: "Build authentication UI"
- Domains detected: frontend + backend
- Evidence: Description mentions "UI components AND API integration"
- **Fix:** Split into "Build authentication UI" (frontend) + "Integrate auth API" (backend)

**⚠️ WARN: Missed Parallel Opportunity**
- Task: "Create database schema"
- Status: Independent (no blockers)
- **Issue:** Not in Batch 1, placed in Batch 2
- **Fix:** Move to Batch 1 (can start immediately)

**⚠️ WARN: Tag Convention Violation**
- Task: "Build authentication UI"
- Tag: "auth-ui" (new tag)
- **Issue:** Tag not in project conventions, not checked in agent-mapping.yaml
- **Suggestion:** Use existing tag "frontend" or add "auth-ui" to agent-mapping.yaml

### ✅ Successes
- 8/8 workflow steps completed
- Documentation task included
- Testing task included
- Task descriptions populated (200-600 chars)

### 📋 Added to TodoWrite
- [ ] Fix dependency: "Implement backend API" should depend on "Create database schema"
- [ ] Split cross-domain task: "Build authentication UI"
- [ ] Move "Create database schema" to Batch 1
- [ ] Review tag "auth-ui" - add to agent-mapping.yaml or use "frontend"

### 🎯 Recommendations
1. Update dependencies (CRITICAL - affects execution order)
2. Split cross-domain task (CRITICAL - routing will fail)
3. Optimize parallel execution (move task to Batch 1)
4. Update planning-specialist.md Step 3 to enforce domain isolation check
```

## Example 5: Status Progression Skill Validation

### Scenario
Orchestrator attempts to mark task complete

### Pre-Execution (Routing Violation Detected)
```javascript
orchestration-qa(
  phase="pre",
  entityType="manage_container",  // Direct tool call detected
  userInput="Mark task T1 complete"
)
```

### Output (Immediate Alert)
```markdown
## 🚨 CRITICAL ROUTING VIOLATION

**Severity:** CRITICAL

**Workflow Type:** status-change

**Violation:** Status change bypassed mandatory Status Progression Skill

**Expected Approach:** Use Status Progression Skill

**Actual Approach:** Direct manage_container(operation="setStatus") call

**Impact:** Prerequisite validation may be skipped:
- Summary length validation (300-500 chars required)
- Dependency completion check
- Files Changed section verification (for code tasks)
- Test validation

**Recommendation:** Use Status Progression Skill for ALL status changes

---

**Action:** STOP - Do not proceed with direct status change

**Correct Approach:**
```javascript
Use status-progression skill with task ID
```

**Why This Matters:**
Status Progression Skill validates prerequisites before allowing status change.
Bypassing this validation can result in incomplete tasks being marked complete.
```

## Example 6: Implementation Specialist Review

### Scenario
Backend Engineer completes task

### Post-Execution
```javascript
orchestration-qa(
  phase="post",
  entityType="backend-engineer",
  entityOutput="[Backend Engineer's response]",
  entityId="task-uuid-456"
)
```

### Output (Success)
```markdown
## ✅ QA Review: Backend Engineer

**Workflow Adherence:** 9/9 steps (100%)

### Lifecycle Validation
- ✅ Read task with sections
- ✅ Read dependencies
- ✅ Completed implementation work
- ✅ Updated task sections with results
- ✅ Tests run and passing
- ✅ Summary populated (387 chars)
- ✅ Files Changed section created (ordinal 999)
- ✅ Used Status Progression Skill to mark complete
- ✅ Output minimal (73 tokens)

### Quality Checks
- Summary length: 387 chars (expected 300-500) ✅
- Files Changed: Present ✅
- Tests mentioned: Yes ("All 12 tests passing") ✅
- Status change method: Status Progression Skill ✅
- Output brevity: 73 tokens (expected 50-100) ✅

**Result:** Perfect lifecycle execution!
```

### Output (Issues)
```markdown
## 🚨 QA Review: Backend Engineer - CRITICAL ISSUE

**Workflow Adherence:** 8/9 steps (89%)

### Critical Issues (1)

**❌ ALERT: Marked Complete Without Status Progression Skill**

**What Happened:**
Backend Engineer called manage_container(operation="setStatus") directly.

**Expected Behavior:**
Step 8 of specialist lifecycle requires using Status Progression Skill.

**Impact:**
- Summary validation may have been skipped (no length check)
- Files Changed section may not have been verified
- Test validation may have been incomplete

**Evidence:**
- Task status changed to "completed"
- No mention of "Status Progression" in output
- Direct tool call detected

**Recommendation:**
All implementation specialists MUST use Status Progression Skill in Step 8.

**Definition Update Needed:**
Update backend-engineer.md to emphasize CRITICAL pattern:
```diff
### Step 8: Use Status Progression Skill to Mark Complete

+ **CRITICAL:** NEVER call manage_container directly for status changes
+ **ALWAYS:** Use Status Progression Skill for prerequisite validation
```

---

### ⚠️ Issues (1)

**⚠️ WARN: Files Changed Section Missing**
- Expected: Section with ordinal 999, title "Files Changed"
- Found: No Files Changed section
- **Impact:** Difficult to track what files were modified

### 📋 Added to TodoWrite
- [ ] ALERT: Backend Engineer bypassed Status Progression Skill
- [ ] Add Files Changed section to task
- [ ] Update backend-engineer.md Step 8 critical pattern

### 💭 Decision Required

**Issue:** Critical workflow pattern violated (Status Progression bypass)

**Options:**
1. **Validate task manually**
   - Check summary length (300-500 chars)
   - Verify Files Changed section exists or create it
   - Confirm tests passing

2. **Revert and retry with Status Progression Skill**
   - Revert task to "in-progress"
   - Use Status Progression Skill for completion
   - Ensures all prerequisites validated

**Recommendation:** Option 1 for this instance, but update backend-engineer.md
to prevent recurrence.
```

## Example 7: Session Summary with Patterns

### Scenario
End of session after multiple workflows

### Usage
```javascript
orchestration-qa(phase="summary", sessionId="session-123")
```

### Output
```markdown
## 📊 Session QA Summary

**Workflows Analyzed:** 6
- Skills: 2 (Feature Orchestration, Status Progression)
- Subagents: 4 (Feature Architect, Planning Specialist, 2x Backend Engineer)

**Quality Overview:**
- ✅ Successful: 4 (no issues)
- ⚠️ Issues: 1 (Planning Specialist - graph quality 73%)
- 🚨 Critical: 1 (Backend Engineer - status bypass)

### Deviation Breakdown
- Routing violations: 1 (status change bypass)
- Workflow deviations: 0
- Output quality: 0
- Dependency errors: 2 (in Planning Specialist)
- Tag issues: 1 (convention violation)
- Token waste: 0

### Recurring Patterns (1)

**🔁 Pattern: Status Change Bypasses**
- Occurrences: 2 (Backend Engineer x2)
- Root cause: Step 8 critical pattern not emphasized enough
- Impact: Prerequisites validation skipped
- **Suggestion**: Update backend-engineer.md Step 8 with CRITICAL emphasis

### Improvement Recommendations (2)

**Priority 1: Backend Engineer Definition Update**
- File: backend-engineer.md
- Section: Step 8
- Type: Critical Pattern Emphasis
- Change: Add CRITICAL warning against direct status changes
- Impact: Prevents status bypass in future executions
- Effort: Low (text addition)

**Priority 2: Planning Specialist Validation Checklist**
- File: planning-specialist.md
- Section: Step 5 (Map Dependencies)
- Type: Validation Checklist
- Change: Add graph quality validation before returning
- Impact: Ensures execution graph accuracy >= 95%
- Effort: Medium (add quality gate step)

### Quality Trends
- Graph quality: 87% average (baseline 70%, target 95%+)
- Tag coverage: 98% average (baseline 90%, target 100%)
- Token efficiency: 91% average
- Workflow adherence: 94% average

### Next Steps
1. Update backend-engineer.md Step 8 (CRITICAL pattern emphasis)
2. Update planning-specialist.md Step 5 (graph validation checklist)
3. Monitor for recurrence in next session
```

## Example 8: Mid-Session Reconfiguration

### Scenario
User wants to enable additional analysis categories after session has started

### User Request
"I want to also track token optimization now to see if we're wasting tokens"

### Usage
```javascript
orchestration-qa(phase="configure")
```

### Interactive Prompts
Agent presents options with current configuration pre-selected:

```markdown
## Quality Analysis Configuration

**Current Configuration:**
- ✅ Information Density
- ✅ Routing Validation

**Which quality analysis categories would you like to enable?**
(Your current selections are pre-checked)

☑ Information Density ← Currently enabled
☐ Execution Graphs
☐ Tag Coverage
☑ Token Optimization ← User adds this
☐ Tool Selection
☑ Routing Validation ← Currently enabled
☐ Parallel Detection
```

### User Updates
```javascript
// User selects:
{
  categories: ["Information Density", "Routing Validation", "Token Optimization"],
  reportStyle: "Standard"  // Keep existing
}
```

### Output
```markdown
✅ **QA Configuration Updated**

**Changes:**
- ➕ Added: Token Optimization

**New Configuration (3 categories):**
- ✅ Information Density
- ✅ Routing Validation
- ✅ Token Optimization (NEW)

**Report Style:** Standard (unchanged)

**Token Impact:**
- Previous: ~1,200 tokens per analysis
- Updated: ~1,800 tokens per analysis (+600 tokens)
- Trade-off: Now detecting token waste patterns

All future QA reviews will include token optimization analysis.
```

### Next Post-Execution
```javascript
// After Backend Engineer completes task
orchestration-qa(
  phase="post",
  entityType="backend-engineer",
  entityOutput="...",
  entityId="task-uuid"
)

// Now loads: post-execution.md + routing-validation.md +
//            task-content-quality.md + token-optimization.md
// Analysis includes: content quality + routing + token waste detection
```

## Usage Patterns Summary

### Session Start (First Time)
1. `phase="configure"` - Interactive category selection (~200-300 tokens)
2. `phase="init"` - Load knowledge bases (~1000 tokens)

### Per Entity
- `phase="pre"` - Before launching any Skill or Subagent (~600 tokens)
- `phase="post"` - After any Skill or Subagent completes (varies by config)

### Optional
- `phase="configure"` - Reconfigure mid-session
- `phase="summary"` - End-of-session pattern tracking (~800 tokens)

### Configuration-Driven Token Costs

**Post-Execution Costs by Configuration:**

| Configuration | Token Cost | Use Case |
|--------------|------------|----------|
| **Minimal** (Routing only) | ~1,000 tokens | Critical bypass detection only |
| **Default** (Info Density + Routing) | ~1,200 tokens | Most users - content + critical checks |
| **Planning Focus** (Graphs + Tags + Routing) | ~2,000 tokens | Planning Specialist reviews |
| **Comprehensive** (All enabled) | ~3,500 tokens | Full quality analysis |

**Session Cost Examples:**

| Workflow | Config | Total Cost | vs Monolithic |
|----------|--------|------------|---------------|
| 1 Feature + 3 Tasks | Default | ~6k tokens | 70% savings |
| 1 Feature + 3 Tasks | Minimal | ~4.5k tokens | 78% savings |
| 1 Feature + 3 Tasks | Comprehensive | ~15k tokens | 25% savings |

**Monolithic Trainer**: 20k-30k tokens always loaded (no configuration)

**Smart Defaults**: Information Density + Routing Validation achieves 93% token reduction while catching critical issues
