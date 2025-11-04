# Task Content Quality Analysis

**Purpose**: Analyze information added to tasks by specialists to detect wasteful content, measure information density, and suggest improvements.

**When**: After Implementation Specialists complete tasks (Backend, Frontend, Database, Test, Technical Writer)

**Applies To**: Implementation Specialist Subagents only

**Token Cost**: ~500-700 tokens

## Overview

Implementation specialists add content to tasks through:
1. **Summary field** (300-500 chars) - What was accomplished
2. **Task sections** - Detailed results, approach, decisions
3. **Files Changed section** (ordinal 999) - List of modified files

This analysis ensures specialists add **high-density, non-redundant information** while avoiding token waste.

## Quality Metrics

### 1. Information Density
**Definition**: Ratio of useful information to total tokens added

**Formula**: `density = (unique_concepts + actionable_details) / total_tokens`

**Target**: ≥ 70% (7 concepts per 10 tokens)

**Good Example** (High Density):
```
Summary (87 tokens):
"Implemented OAuth2 authentication with JWT tokens. Added UserService with
login/logout endpoints. All 12 tests passing. Files: AuthController.kt,
UserService.kt, SecurityConfig.kt, AuthControllerTest.kt"

Density: 85% (7 concepts: OAuth2, JWT, UserService, login, logout, tests passing, files)
```

**Bad Example** (Low Density):
```
Summary (143 tokens):
"I have successfully completed the implementation of the authentication feature
as requested. The work involved creating the necessary components and ensuring
everything works correctly. Testing was performed and all tests are now passing
successfully."

Density: 35% (3 concepts: authentication, components created, tests passing)
Waste: 60 tokens of filler words
```

### 2. Redundancy Score
**Definition**: Percentage of information duplicated across summary + sections

**Formula**: `redundancy = duplicate_tokens / (summary_tokens + section_tokens)`

**Target**: ≤ 20% (minimal overlap between summary and sections)

**Detection**:
```javascript
// Extract key phrases from summary
summaryPhrases = extractPhrases(task.summary)
// e.g., ["OAuth2 authentication", "JWT tokens", "UserService", "12 tests passing"]

// Check sections for duplicate phrases
sectionContent = task.sections.map(s => s.content).join(" ")
duplicates = summaryPhrases.filter(phrase => sectionContent.includes(phrase))

redundancy = (duplicates.length / summaryPhrases.length) * 100
```

**High Redundancy Example** (Bad):
```
Summary:
"Implemented OAuth2 authentication with JWT tokens. Added UserService."

Technical Approach Section:
"For this task, I implemented OAuth2 authentication using JWT tokens.
I created a UserService to handle authentication logic..."

Redundancy: 70% (both mention OAuth2, JWT, UserService)
```

**Low Redundancy Example** (Good):
```
Summary:
"Implemented OAuth2 authentication. 12 tests passing."

Technical Approach Section:
"Used Spring Security OAuth2 library. Token validation in JwtFilter.
Refresh token rotation every 24h. Rate limiting: 5 attempts/min."

Redundancy: 15% (summary is high-level, section adds technical details)
```

### 3. Code Snippet Ratio
**Definition**: Percentage of section content that is code vs explanation

**Formula**: `code_ratio = code_block_tokens / section_tokens`

**Target**: ≤ 30% (sections explain, files contain code)

**Detection**:
```javascript
// Count tokens in code blocks
codeBlocks = extractCodeBlocks(section.content)  // ```language ... ```
codeTokens = sum(codeBlocks.map(b => estimateTokens(b)))

// Total section tokens
sectionTokens = estimateTokens(section.content)

ratio = (codeTokens / sectionTokens) * 100
```

**Bad Example** (High Code Ratio):
```markdown
## Implementation Details

Here's the UserService implementation:

```kotlin
@Service
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder
) {
    fun login(email: String, password: String): User? {
        val user = userRepository.findByEmail(email)
        return if (user != null && passwordEncoder.matches(password, user.password)) {
            user
        } else null
    }
    // ... 50 more lines
}
```

And here's the test:

```kotlin
@Test
fun `login with valid credentials returns user`() {
    // ... 30 lines of test code
}
```

Code Ratio: 85% (300 code tokens / 350 total tokens)
Issue: Full code belongs in files, not task sections
```

**Good Example** (Low Code Ratio):
```markdown
## Implementation Details

Created UserService with login/logout methods. Key decisions:
- Password hashing: BCrypt (cost factor 12)
- Session management: JWT with 1h expiration
- Rate limiting: 5 failed attempts → 15min lockout

Example usage:
```kotlin
userService.login(email, password) // Returns User or null
```

Code Ratio: 12% (20 code tokens / 165 total tokens)
Quality: Explains approach, minimal code snippet for clarity
```

### 4. Summary Quality
**Definition**: Summary is concise, informative, and follows best practices

**Checks**:
- ✅ Length: 300-500 characters (enforced by Status Progression Skill)
- ✅ Mentions what was done (not how or why - that's in sections)
- ✅ Includes test status
- ✅ Lists key files changed
- ✅ No filler words ("I have...", "successfully...", "as requested...")

**Scoring**:
```javascript
quality = {
  length: inRange(summary.length, 300, 500) ? 25 : 0,
  mentions_what: containsActionVerbs(summary) ? 25 : 0,  // "Implemented", "Added", "Fixed"
  test_status: mentionsTests(summary) ? 25 : 0,          // "12 tests passing"
  no_filler: !containsFiller(summary) ? 25 : 0           // No "successfully", "I have"
}

score = sum(quality.values)  // 0-100
```

**Example Scores**:

90/100 (Excellent):
```
"Implemented OAuth2 authentication with JWT tokens. Added UserService for
user management. All 12 tests passing. Files: AuthController.kt, UserService.kt,
SecurityConfig.kt"

✓ Length: 387 chars
✓ Mentions what: "Implemented", "Added"
✓ Test status: "12 tests passing"
✓ No filler: Clean, direct
```

50/100 (Poor):
```
"I have successfully completed the authentication feature as requested. The
implementation involved creating the necessary components and ensuring that
everything works correctly. All tests are passing."

✓ Length: 349 chars
✗ Mentions what: Vague "components"
✓ Test status: "tests are passing"
✗ No filler: "successfully", "as requested", "I have"
```

### 5. Section Usefulness
**Definition**: Sections add value beyond what's in summary and files

**Checks per section**:
- ✅ Explains decisions/trade-offs
- ✅ Documents non-obvious approach
- ✅ Provides context for future developers
- ✅ References files instead of duplicating code
- ✅ Concise (bullet points > paragraphs)

**Scoring**:
```javascript
usefulness = {
  explains_why: containsRationale(section) ? 20 : 0,     // "Chose X because..."
  approach: describesApproach(section) ? 20 : 0,         // "Used pattern Y"
  future_context: providesContext(section) ? 20 : 0,     // "Note: Z limitation"
  references_files: hasFileReferences(section) ? 20 : 0, // "See AuthController.kt:45"
  concise: isConcise(section) ? 20 : 0                   // Bullet points, not prose
}

score = sum(usefulness.values)  // 0-100
```

## Wasteful Patterns to Detect

### Pattern 1: Full Code in Sections

**Issue**: Code belongs in files, not task documentation

**Detection**:
```javascript
if (section.codeBlockCount > 2 || section.codeRatio > 30) {
  return {
    pattern: "Full code in sections",
    severity: "WARN",
    found: `${section.codeBlockCount} code blocks, ${section.codeRatio}% of content`,
    expected: "≤ 2 brief code snippets, ≤ 30% code ratio",
    recommendation: "Move code to files, reference with: 'See FileName.kt:lineNumber'",
    savings: estimateSavings(section)  // e.g., "~500 tokens"
  }
}
```

### Pattern 2: Full Test Output

**Issue**: Test results should be summarized, not pasted verbatim

**Detection**:
```javascript
if (section.title.includes("Test") && section.content.includes("PASSED") && section.content.length > 500) {
  return {
    pattern: "Full test output in section",
    severity: "WARN",
    found: `${section.content.length} chars of test output`,
    expected: "Test summary: X/Y passed, failure details if any",
    recommendation: "Summarize: '12/12 tests passing' or '11/12 passing (1 flaky test)'",
    savings: `~${section.content.length * 0.75} tokens`
  }
}
```

### Pattern 3: Summary Redundancy

**Issue**: Summary repeats information already in sections

**Detection**:
```javascript
overlap = calculateOverlap(task.summary, task.sections)

if (overlap > 40) {
  return {
    pattern: "High summary-section redundancy",
    severity: "INFO",
    found: `${overlap}% overlap between summary and sections`,
    expected: "≤ 20% overlap (summary = high-level, sections = details)",
    recommendation: "Make summary more concise, or add new details to sections",
    savings: `~${estimateRedundantTokens(task)} tokens`
  }
}
```

### Pattern 4: Filler Language

**Issue**: Verbose, unnecessary words that don't add information

**Detection**:
```javascript
fillerPhrases = [
  "I have successfully",
  "as requested",
  "in order to",
  "it should be noted that",
  "for the purpose of",
  "with regards to",
  "in conclusion"
]

found = fillerPhrases.filter(phrase => task.summary.includes(phrase))

if (found.length > 0) {
  return {
    pattern: "Filler language in summary",
    severity: "INFO",
    found: found.join(", "),
    expected: "Direct, concise language",
    recommendation: "Remove filler: 'Implemented X' not 'I have successfully implemented X as requested'",
    savings: `~${found.length * 3} tokens`
  }
}
```

### Pattern 5: Over-Explaining Obvious

**Issue**: Explaining what's clear from file/function names

**Detection**:
```javascript
if (section.title == "Implementation" && containsObvious(section.content)) {
  return {
    pattern: "Over-explaining obvious implementation",
    severity: "INFO",
    example: "Explaining 'UserService manages users' when class is named UserService",
    recommendation: "Focus on non-obvious: design decisions, trade-offs, gotchas",
    savings: "~100-200 tokens"
  }
}
```

### Pattern 6: Uncustomized Template Sections

**Issue**: Generic template sections with placeholder text that provide zero value

**Detection**:
```javascript
placeholderPatterns = [
  /\[Component\s*\d*\]/i,
  /\[Library\s*Name\]/i,
  /\[Phase\s*Name\]/i,
  /\[Library\]/i,
  /\[Version\]/i,
  /\[What it does\]/i,
  /\[Why chosen\]/i,
  /\[Goal\]:/i,
  /\[Deliverables\]:/i
]

for (section in task.sections) {
  // Check for placeholder patterns
  hasPlaceholder = placeholderPatterns.some(pattern => pattern.test(section.content))

  // Check for generic template titles with minimal content
  genericTitles = ["Architecture Overview", "Key Dependencies", "Implementation Strategy"]
  isGenericTitle = genericTitles.includes(section.title)
  hasMinimalCustomization = section.content.length < 300 || section.content.includes('[')

  if (hasPlaceholder || (isGenericTitle && hasMinimalCustomization)) {
    return {
      pattern: "Uncustomized template section",
      severity: "WARN",  // High priority - significant token waste
      found: `Section "${section.title}" contains placeholder text or generic template`,
      expected: "Task-specific content ≥200 chars, OR delete section entirely",
      recommendation: "DELETE section using manage_sections(operation='delete', id='${section.id}') - Templates provide sufficient structure",
      savings: `~${estimateTokens(section.content)} tokens`,
      sectionId: section.id,
      action: "DELETE"  // Explicit action to take
    }
  }
}
```

**Common Placeholder Patterns**:
- `[Component 1]`, `[Component 2]` - Generic component names
- `[Library Name]`, `[Version]` - Dependency table placeholders
- `[Phase Name]`, `[Goal]:`, `[Deliverables]:` - Implementation strategy placeholders
- `[What it does]`, `[Why chosen]` - Generic explanations

**Examples of Violations**:

**Bad Example 1 - Architecture Overview with placeholders**:
```markdown
Title: Architecture Overview
Content:
This task involves the following components:
- [Component 1]: [What it does]
- [Component 2]: [What it does]

Technical approach:
- [Library Name] for [functionality]
- [Library Name] for [functionality]

(72 tokens of waste - DELETE this section)
```

**Bad Example 2 - Key Dependencies with placeholders**:
```markdown
Title: Key Dependencies
Content:
| Library | Version | Purpose |
|---------|---------|---------|
| [Library Name] | [Version] | [What it does] |
| [Library Name] | [Version] | [What it does] |

Rationale:
- [Library]: [Why chosen]

(85 tokens of waste - DELETE this section)
```

**Bad Example 3 - Implementation Strategy with placeholders**:
```markdown
Title: Implementation Strategy
Content:
Phase 1: [Phase Name]
- Goal: [Goal]
- Deliverables: [Deliverables]

Phase 2: [Phase Name]
- Goal: [Goal]
- Deliverables: [Deliverables]

(98 tokens of waste - DELETE this section)
```

**Proper Response When Detected**:
```markdown
⚠️ WARN - Uncustomized Template Sections (Pattern 6)

**Found**: 3 task sections contain placeholder text, wasting ~255 tokens

**Violations**:
1. Task [ID] - Section "Architecture Overview" (72 tokens)
   - Placeholder patterns: `[Component 1]`, `[What it does]`
   - **Action**: DELETE section (ID: xxx)
   - **Reason**: Templates provide sufficient structure

2. Task [ID] - Section "Key Dependencies" (85 tokens)
   - Placeholder patterns: `[Library Name]`, `[Version]`, `[Why chosen]`
   - **Action**: DELETE section (ID: yyy)
   - **Reason**: Generic table with no actual dependencies

3. Task [ID] - Section "Implementation Strategy" (98 tokens)
   - Placeholder patterns: `[Phase Name]`, `[Goal]:`, `[Deliverables]:`
   - **Action**: DELETE section (ID: zzz)
   - **Reason**: Uncustomized phases with no specific strategy

**Expected**: Task-specific content ≥200 chars with NO placeholder text, OR delete section entirely

**Recommendation**:
- Planning Specialist must customize ALL sections before returning to orchestrator (Step 7.5 validation)
- Implementation Specialists must DELETE any placeholder sections during Step 4
- Templates provide sufficient structure for 95% of tasks (complexity ≤7)

**Root Cause**: Planning Specialist's bulkCreate operation included generic template sections without customization

**Prevention**:
1. Planning Specialist Step 7.5 (Validate Task Quality) must detect and delete placeholder sections
2. Implementation Specialists Step 4 must check for and delete placeholder sections
3. Orchestration QA Skill now detects this pattern automatically

**Token Savings**: ~255 tokens (current waste) → 0 tokens (after deletion)
```

## Analysis Workflow

### Step 1: Capture Baseline

**Before specialist executes**:
```javascript
baseline = {
  taskId: task.id,
  summaryLength: task.summary?.length || 0,
  sectionCount: task.sections.length,
  totalTokens: estimateTaskTokens(task)
}
```

### Step 2: Measure Addition

**After specialist completes**:
```javascript
delta = {
  summaryAdded: task.summary.length - baseline.summaryLength,
  sectionsAdded: task.sections.length - baseline.sectionCount,
  tokensAdded: estimateTaskTokens(task) - baseline.totalTokens
}
```

### Step 3: Analyze Quality

**Run quality checks**:
```javascript
analysis = {
  informationDensity: calculateDensity(task, delta),
  redundancyScore: calculateRedundancy(task),
  codeRatio: calculateCodeRatio(task),
  summaryQuality: scoreSummary(task.summary),
  sectionUsefulness: task.sections.map(s => scoreSection(s)),
  wastefulPatterns: detectWaste(task)
}
```

### Step 4: Generate Report

**Format findings**:
```javascript
report = {
  specialist: entityType,
  taskId: task.id,
  tokensAdded: delta.tokensAdded,
  quality: {
    informationDensity: `${analysis.informationDensity}%`,
    redundancy: `${analysis.redundancyScore}%`,
    codeRatio: `${analysis.codeRatio}%`,
    summaryScore: `${analysis.summaryQuality}/100`,
    avgSectionScore: average(analysis.sectionUsefulness)
  },
  wastefulPatterns: analysis.wastefulPatterns,
  potentialSavings: calculateSavings(analysis.wastefulPatterns)
}
```

### Step 5: Track Trends

**Aggregate across tasks**:
```javascript
session.contentQuality.push(report)

// After N tasks (e.g., 5), analyze trends
if (session.contentQuality.length >= 5) {
  trends = analyzeTrends(session.contentQuality)
  // e.g., "Backend Engineer consistently has high code ratio (avg 65%)"
}
```

## Report Template

```markdown
## 📊 Task Content Quality Analysis

**Specialist**: [Backend Engineer / Frontend Developer / etc.]
**Task**: [Task Title] ([ID])

### Tokens Added
- Summary: [X] chars ([Y] tokens)
- Sections: [N] sections added ([Z] tokens)
- **Total Added**: [Y+Z] tokens

### Quality Metrics
- **Information Density**: [X]% ([Target: ≥70%])
- **Redundancy Score**: [Y]% ([Target: ≤20%])
- **Code Ratio**: [Z]% ([Target: ≤30%])
- **Summary Quality**: [Score]/100

### ✅ Strengths
- [What was done well]
- [Good practice observed]

### ⚠️ Wasteful Patterns Detected ([count])

**Pattern 1: [Name]**
- Found: [What was observed]
- Expected: [Best practice]
- Recommendation: [How to improve]
- Potential Savings: ~[X] tokens

**Pattern 2: [Name]**
- Found: [What was observed]
- Expected: [Best practice]
- Recommendation: [How to improve]
- Potential Savings: ~[Y] tokens

### 💰 Total Potential Savings
- Current: [N] tokens added
- Optimized: [N-X-Y] tokens
- **Savings**: ~[X+Y] tokens ([Z]% reduction)

### 🎯 Specific Recommendations
1. [Most impactful improvement]
2. [Secondary improvement]
3. [Optional enhancement]
```

## Trend Analysis (After 5+ Tasks)

```markdown
## 📈 Content Quality Trends

**Session**: [N] tasks analyzed
**Specialists**: [List of specialists used]

### Average Metrics
- Information Density: [X]% (Target: ≥70%)
- Redundancy: [Y]% (Target: ≤20%)
- Code Ratio: [Z]% (Target: ≤30%)
- Summary Quality: [Score]/100

### Recurring Patterns
**Most Common Issue**: [Pattern name] ([N] occurrences)
- **Specialists Affected**: [Backend Engineer (3x), Frontend (2x)]
- **Total Waste**: ~[X] tokens across tasks
- **Recommendation**: Update [specialist].md to emphasize [practice]

**Second Most Common**: [Pattern name] ([M] occurrences)
- **Specialists Affected**: [...]
- **Recommendation**: [...]

### Specialist Performance

**Backend Engineer** ([N] tasks):
- Avg Density: [X]%
- Avg Redundancy: [Y]%
- Common Issue: High code ratio (avg [Z]%)
- **Recommendation**: Reference files instead of embedding code

**Frontend Developer** ([M] tasks):
- Avg Density: [X]%
- Avg Redundancy: [Y]%
- Strengths: Excellent summary quality (avg 85/100)

### System-Wide Opportunities
1. **Update Specialist Templates**
   - Add "Code in Files, Not Sections" guideline to all implementation specialists
   - Estimated Impact: [X]% token reduction

2. **Enhance Summary Guidelines**
   - Add anti-pattern examples (filler language)
   - Estimated Impact: [Y]% improvement in quality scores

3. **Section Template Improvements**
   - Provide better examples of useful vs wasteful sections
   - Estimated Impact: [Z]% reduction in redundancy
```

## Integration with Post-Execution Review

```javascript
// In post-execution.md, after Step 4 (Validate completion quality):

if (isImplementationSpecialist(entityType)) {
  // Read task-content-quality.md
  Read ".claude/skills/orchestration-qa/task-content-quality.md"

  // Run content quality analysis
  contentAnalysis = analyzeTaskContent(task, baseline)

  // Add to report
  report.contentQuality = contentAnalysis

  // Track for trends
  session.contentQuality.push(contentAnalysis)

  // If patterns found, add to deviations
  if (contentAnalysis.wastefulPatterns.length > 0) {
    deviations.push({
      severity: "INFO",  // Usually INFO, can be WARN if severe
      type: "Content Quality",
      patterns: contentAnalysis.wastefulPatterns,
      savings: contentAnalysis.potentialSavings
    })
  }
}
```

## When to Report

**Individual Task**:
- Report if wasteful patterns detected
- Report if quality scores below targets

**Session Trends**:
- After 5+ tasks analyzed
- When recurring patterns detected (same issue 2+ times)
- At session end (via `phase="summary"`)

## Add to TodoWrite (If Issues Found)

```javascript
if (contentAnalysis.potentialSavings > 100) {
  TodoWrite([{
    content: `Review ${specialist} content quality: ${contentAnalysis.potentialSavings} tokens wasted`,
    activeForm: `Reviewing ${specialist} content patterns`,
    status: "pending"
  }])
}

// If recurring pattern
if (trends.recurringPatterns.length > 0) {
  TodoWrite([{
    content: `Update ${specialist}.md: ${trends.recurringPatterns[0].name} pattern recurring`,
    activeForm: `Improving ${specialist} guidelines`,
    status: "pending"
  }])
}
```

## Target Benchmarks

**Excellent** (95%+ of metrics in target):
- Information Density: ≥ 80%
- Redundancy: ≤ 15%
- Code Ratio: ≤ 20%
- Summary Quality: ≥ 85/100
- No wasteful patterns

**Good** (80%+ of metrics in target):
- Information Density: 70-79%
- Redundancy: 16-20%
- Code Ratio: 21-30%
- Summary Quality: 70-84/100
- Minor wasteful patterns (< 100 tokens waste)

**Needs Improvement** (< 80% in target):
- Information Density: < 70%
- Redundancy: > 20%
- Code Ratio: > 30%
- Summary Quality: < 70/100
- Significant waste (> 100 tokens)

## Continuous Improvement

**Track over time**:
- Are quality scores improving?
- Are wasteful patterns decreasing?
- Which specialists need guideline updates?
- What best practices emerge from high-quality tasks?

**Update specialist definitions when**:
- Same pattern occurs 3+ times
- Potential savings > 500 tokens across multiple tasks
- Quality scores consistently below targets
