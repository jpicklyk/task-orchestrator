# Token Optimization Analysis

**Purpose**: Identify token waste patterns and optimization opportunities.

**When**: Optional post-execution (controlled by enableEfficiencyAnalysis parameter)

**Applies To**: All Skills and Subagents

**Token Cost**: ~400-600 tokens

## Common Token Waste Patterns

### Pattern 1: Verbose Specialist Output

**Issue**: Specialist returns full code/documentation in response instead of brief summary

**Expected**: Specialists return 50-100 token summary, detailed work goes in sections/files

**Detection**:
```javascript
if (isImplementationSpecialist(entityType) && estimateTokens(output) > 200) {
  return {
    severity: "WARN",
    pattern: "Verbose specialist output",
    actual: estimateTokens(output),
    expected: "50-100 tokens",
    savings: estimateTokens(output) - 100,
    recommendation: "Return brief summary, put details in task sections"
  }
}
```

### Pattern 2: Reading with includeSections When Not Needed

**Issue**: Loading all sections when only metadata needed

**Expected**: Use scoped `overview` for hierarchical views without sections

**Detection**:
```javascript
if (mentions(output, "includeSections=true") && !needsSections(workflowType)) {
  return {
    severity: "INFO",
    pattern: "Unnecessary section loading",
    recommendation: "Use operation='overview' for metadata + task list",
    savings: "85-93% tokens (e.g., 18.5k → 1.2k for typical feature)"
  }
}
```

### Pattern 3: Multiple Get Operations Instead of Overview

**Issue**: Calling `query_container(operation="get")` multiple times instead of one overview

**Expected**: Single scoped overview provides hierarchical view efficiently

**Detection**:
```javascript
getCallCount = countToolCalls(output, "query_container", "get")
if (getCallCount > 1 && !usedOverview) {
  return {
    severity: "INFO",
    pattern: "Multiple get calls instead of overview",
    actual: `${getCallCount} get calls`,
    expected: "1 scoped overview call",
    savings: estimateSavings(getCallCount)
  }
}
```

### Pattern 4: Listing All Entities When Filtering Would Work

**Issue**: Querying all tasks then filtering in code

**Expected**: Use query parameters (status, tags, priority) for filtering

**Detection**:
```javascript
if (mentions(output, "filter") && !usedQueryFilters) {
  return {
    severity: "INFO",
    pattern: "Client-side filtering instead of query filters",
    recommendation: "Use status/tags/priority parameters in query_container",
    savings: "~50-70% tokens"
  }
}
```

### Pattern 5: PRD Content in Description Instead of Sections

**Issue**: Feature Architect puts all PRD content in description field

**Expected**: Description is forward-looking summary; PRD sections go in feature sections

**Detection**:
```javascript
if (entityType == "feature-architect" && feature.description.length > 800) {
  return {
    severity: "WARN",
    pattern: "PRD content in description field",
    actual: `${feature.description.length} chars`,
    expected: "200-500 chars description + sections for detailed content",
    recommendation: "Move detailed content to feature sections"
  }
}
```

### Pattern 6: Verbose Feature Architect Handoff

**Issue**: Feature Architect returns detailed feature explanation

**Expected**: Minimal handoff (50-100 tokens): "Feature created, ID: X, Y tasks ready"

**Detection**:
```javascript
if (entityType == "feature-architect" && estimateTokens(output) > 200) {
  return {
    severity: "WARN",
    pattern: "Verbose Feature Architect handoff",
    actual: estimateTokens(output),
    expected: "50-100 tokens",
    savings: estimateTokens(output) - 100,
    recommendation: "Brief handoff: Feature ID, next action. Details in feature sections."
  }
}
```

## Analysis Workflow

### Step 1: Estimate Token Usage

```javascript
tokenUsage = {
  input: estimateTokens(context.userInput.fullText),
  output: estimateTokens(entityOutput),
  total: estimateTokens(context.userInput.fullText) + estimateTokens(entityOutput)
}
```

### Step 2: Compare Against Expected Range

```javascript
expectedRange = definition.tokenRange  // e.g., [1800, 2200] for Feature Architect
deviation = tokenUsage.output - expectedRange[1]

if (deviation > expectedRange[1] * 0.5) {  // More than 50% over
  severity = "WARN"
} else {
  severity = "INFO"
}
```

### Step 3: Detect Waste Patterns

```javascript
wastePatterns = []

// Check each pattern
if (verboseSpecialistOutput()) wastePatterns.push(pattern1)
if (unnecessarySectionLoading()) wastePatterns.push(pattern2)
if (multipleGetsInsteadOfOverview()) wastePatterns.push(pattern3)
if (clientSideFiltering()) wastePatterns.push(pattern4)
if (prdContentInDescription()) wastePatterns.push(pattern5)
if (verboseHandoff()) wastePatterns.push(pattern6)
```

### Step 4: Calculate Potential Savings

```javascript
totalSavings = wastePatterns.reduce((sum, pattern) => sum + pattern.savings, 0)
optimizedTokens = tokenUsage.total - totalSavings
efficiencyGain = (totalSavings / tokenUsage.total) * 100
```

### Step 5: Generate Report

```markdown
## 💡 Token Optimization Opportunities

**Current Usage**: [X] tokens
**Potential Savings**: [Y] tokens ([Z]% reduction)
**Optimized Usage**: [X - Y] tokens

### Patterns Detected ([count])

**⚠️ WARN** ([count]): Significant waste
- Verbose specialist output: [X] tokens (expected 50-100)
- PRD content in description: [Y] chars (expected 200-500)

**ℹ️ INFO** ([count]): Optimization opportunities
- Use overview instead of get: [savings] tokens
- Use query filters: [savings] tokens

### Recommendations
1. [Most impactful optimization]
2. [Secondary optimization]
```

## Recommended Baselines

- **Skills**: 200-900 tokens (lightweight coordination)
- **Feature Architect**: 1800-2200 tokens (complexity assessment + creation)
- **Planning Specialist**: 1800-2200 tokens (analysis + task creation)
- **Implementation Specialists**: 1800-2200 tokens (work done, not described)
  - **Output**: 50-100 tokens (brief summary)
  - **Sections**: Detailed work (not counted against specialist)

## When to Report

- **Only if** enableEfficiencyAnalysis=true
- **WARN**: Include in post-execution report
- **INFO**: Log for pattern tracking only

## Integration Example

```javascript
if (params.enableEfficiencyAnalysis) {
  Read "token-optimization.md"
  opportunities = analyzeTokenOptimization(entityType, entityOutput, context)

  if (opportunities.length > 0) {
    report.efficiencyAnalysis = {
      currentUsage: tokenUsage.total,
      savings: totalSavings,
      gain: efficiencyGain,
      opportunities: opportunities
    }
  }
}
```
