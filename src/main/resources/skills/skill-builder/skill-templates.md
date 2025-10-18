# Skill Templates - Common Patterns

This document provides reusable templates for creating different types of Skills.

---

## Template 1: Coordination Skill

**Use when:** Coordinating multiple MCP tool calls for a specific workflow

```markdown
---
name: [Coordination Skill Name]
description: [Coordinate X workflow]. Use when [managing/coordinating/checking] [specific aspect], [action 1], or [action 2].
allowed-tools: [list relevant MCP tools]
---

# [Coordination Skill Name]

You are a [role] specialist focused on [coordination purpose].

## What You Do

Coordinate [workflow description] by making the right sequence of MCP tool calls.

## Workflow

1. **Step 1: Gather context**
   - Tool: [tool_name]
   - Purpose: [why this step]

2. **Step 2: Perform action**
   - Tool: [tool_name]
   - Purpose: [why this step]

3. **Step 3: Update state**
   - Tool: [tool_name]
   - Purpose: [why this step]

4. **Return to user**: [what information to provide]

## Key Guidelines

- [Important rule 1]
- [Important rule 2]
- [Error handling approach]

## Common Patterns

### Pattern 1: [Common Scenario]
[How to handle this scenario]

### Pattern 2: [Another Scenario]
[How to handle this scenario]
```

**Example:** Feature Management Skill, Task Management Skill

---

## Template 2: File Generation Skill

**Use when:** Generating files from templates or data

```markdown
---
name: [Generator Skill Name]
description: Generate [file type] from [source]. Use when creating [files], [generating documentation], or [building configuration].
allowed-tools: Read, Write, [optional: Grep, Bash, mcp tools]
---

# [Generator Skill Name]

You are a [file type] generation specialist.

## What You Do

Generate [file type] files by reading [source data], applying [template/logic], and writing output.

## Workflow

1. **Read source data**: [where data comes from]
   - Tool: Read or [MCP tool]
   - Parse: [what to extract]

2. **Apply template/logic**: [transformation rules]
   - Structure: [file format]
   - Validation: [checks to perform]

3. **Write output**: [where to save]
   - Tool: Write
   - Location: [path pattern]

4. **Return to user**: [file location and summary]

## Template Structure

[Show the structure/format of generated files]

```
[Example template]
```

## Customization

Users can customize:
- [Aspect 1]: [how to modify]
- [Aspect 2]: [how to modify]
```

**Example:** API Documentation Generator, Config File Creator

---

## Template 3: Analysis Skill

**Use when:** Analyzing data and providing insights

```markdown
---
name: [Analyzer Skill Name]
description: Analyze [data type] and provide [insights]. Use when analyzing [aspect], checking [metrics], or investigating [issues].
allowed-tools: Read, Grep, [MCP query tools]
---

# [Analyzer Skill Name]

You are a [analysis type] specialist focused on [analysis purpose].

## What You Do

Analyze [data sources], identify [patterns/issues], and provide actionable recommendations.

## Workflow

1. **Collect data**: [what to gather]
   - Tools: [data collection tools]
   - Sources: [where data lives]

2. **Analyze patterns**: [analysis logic]
   - Look for: [what to identify]
   - Calculate: [metrics to compute]

3. **Generate insights**: [reporting format]
   - Summary: [high-level findings]
   - Details: [specific issues]
   - Recommendations: [action items]

4. **Return to user**: [analysis results]

## Analysis Criteria

- [Criterion 1]: [threshold/rule]
- [Criterion 2]: [threshold/rule]
- [Criterion 3]: [threshold/rule]

## Common Issues

- **Issue**: [Problem description]
  - **Detection**: [How to identify]
  - **Recommendation**: [How to fix]
```

**Example:** Dependency Analysis Skill, Test Coverage Analyzer

---

## Template 4: Integration Skill

**Use when:** Bridging Task Orchestrator with external systems

```markdown
---
name: [Integration Skill Name]
description: Integrate with [external system]. Use when [syncing/exporting/importing] [data type] with [system name].
allowed-tools: Bash, Read, Write, [relevant MCP tools]
---

# [Integration Skill Name]

You are an integration specialist focused on [system] connectivity.

## What You Do

Bridge Task Orchestrator with [external system] by [action description].

## Workflow

1. **Read internal data**: Get data from Task Orchestrator
   - Tool: [MCP tool]
   - Data: [what to retrieve]

2. **Transform data**: Convert to [external format]
   - Mapping: [field mappings]
   - Validation: [rules to check]

3. **Call external API**: Send data to [system]
   - Tool: Bash (curl/API client)
   - Endpoint: [API endpoint]
   - Authentication: [auth method]

4. **Update internal state**: Record integration success
   - Tool: [MCP tool]
   - Update: [what to record]

5. **Return to user**: [confirmation and external identifiers]

## Configuration Required

Users must provide:
- `[VAR_NAME]`: [Description and how to obtain]
- `[VAR_NAME]`: [Description and how to obtain]

## API Reference

### [External API Call]
```bash
curl -X [METHOD] "$[URL_VAR]/[endpoint]" \
  -H "Authorization: Bearer $[TOKEN_VAR]" \
  -H "Content-Type: application/json" \
  -d '[JSON payload]'
```

## Error Handling

- **Authentication failure**: [how to handle]
- **API rate limit**: [how to handle]
- **Network error**: [how to handle]
```

**Example:** Jira Task Exporter, GitHub Issue Sync

---

## Template 5: Validation/Quality Gate Skill

**Use when:** Checking quality criteria before proceeding

```markdown
---
name: [Validator Skill Name]
description: Validate [aspect] meets [criteria]. Use when checking [quality aspect], verifying [requirements], or enforcing [standards].
allowed-tools: Bash, Read, Grep, [relevant MCP tools]
---

# [Validator Skill Name]

You are a quality assurance specialist focused on [validation purpose].

## What You Do

Validate that [aspect] meets [quality criteria] and report violations.

## Workflow

1. **Collect validation data**: [what to check]
   - Tools: [data collection]
   - Scope: [what to validate]

2. **Apply validation rules**: [criteria]
   - Rule 1: [description and threshold]
   - Rule 2: [description and threshold]
   - Rule 3: [description and threshold]

3. **Report violations**: [reporting format]
   - Pass: [what to report on success]
   - Fail: [what to report on failure]

4. **Return to user**: [validation results with details]

## Validation Rules

### Rule 1: [Rule Name]
- **Check**: [What to validate]
- **Pass criteria**: [When it passes]
- **Fail action**: [What happens on failure]

### Rule 2: [Rule Name]
- **Check**: [What to validate]
- **Pass criteria**: [When it passes]
- **Fail action**: [What happens on failure]

## Common Failures

- **Failure**: [Problem]
  - **Cause**: [Why it happens]
  - **Fix**: [How to resolve]
```

**Example:** Code Quality Checker, Commit Message Validator

---

## Template 6: Reporting Skill

**Use when:** Generating reports from project data

```markdown
---
name: [Reporter Skill Name]
description: Generate [report type] from [data source]. Use when creating [reports], [summarizing progress], or [tracking metrics].
allowed-tools: [MCP query tools], Read, Write
---

# [Reporter Skill Name]

You are a reporting specialist focused on [reporting purpose].

## What You Do

Generate [report type] by querying [data sources], aggregating [metrics], and formatting results.

## Workflow

1. **Query data**: Gather information for report
   - Tools: [MCP query tools]
   - Scope: [time range, filters, etc.]

2. **Aggregate metrics**: Calculate statistics
   - Metric 1: [calculation]
   - Metric 2: [calculation]
   - Metric 3: [calculation]

3. **Format report**: Create readable output
   - Format: [markdown/HTML/JSON]
   - Sections: [report structure]

4. **Save/display**: Output report
   - Tool: Write (if saving to file)
   - Return: [summary to user]

## Report Structure

```markdown
# [Report Title]

## Summary
[High-level overview]

## Metrics
- **Metric 1**: [value] ([interpretation])
- **Metric 2**: [value] ([interpretation])

## Details
[Detailed breakdown]

## Recommendations
[Action items based on data]
```

## Customization

Users can customize:
- Time range: [how to adjust]
- Filters: [what can be filtered]
- Output format: [format options]
```

**Example:** Sprint Progress Report, Test Results Summary

---

## Template 7: Migration/Data Transformation Skill

**Use when:** Moving or transforming data between structures

```markdown
---
name: [Migration Skill Name]
description: Migrate [data] from [source] to [target]. Use when moving [data type], restructuring [hierarchy], or transforming [format].
allowed-tools: [relevant MCP tools for read/write operations]
---

# [Migration Skill Name]

You are a data migration specialist focused on [migration purpose].

## What You Do

Safely move [data type] from [source] to [target] while preserving [important aspects].

## Workflow

1. **Validate source**: Check source data integrity
   - Tool: [query tool]
   - Checks: [validation rules]

2. **Check for conflicts**: Identify blocking issues
   - Conflicts: [what to check for]
   - Resolution: [how to handle conflicts]

3. **Transform data**: Convert format if needed
   - Mapping: [source → target field mapping]
   - Defaults: [default values for new fields]

4. **Perform migration**: Execute the move
   - Tool: [update tool]
   - Backup: [whether to backup first]

5. **Verify**: Confirm migration success
   - Check: [validation after migration]
   - Rollback: [if verification fails]

6. **Return to user**: [migration summary]

## Safety Checks

- **Pre-migration**: [checks before executing]
- **During migration**: [validations while running]
- **Post-migration**: [verifications after completion]

## Rollback Procedure

If migration fails:
1. [Rollback step 1]
2. [Rollback step 2]
3. [How to recover]
```

**Example:** Task Migration Assistant, Feature Restructure Tool

---

## Choosing the Right Template

**Decision Guide:**

1. **Is it primarily coordination?** → Use Coordination Skill Template
2. **Does it generate files?** → Use File Generation Skill Template
3. **Does it analyze data?** → Use Analysis Skill Template
4. **Does it integrate with external systems?** → Use Integration Skill Template
5. **Does it validate/enforce rules?** → Use Validation Skill Template
6. **Does it create reports?** → Use Reporting Skill Template
7. **Does it move/transform data?** → Use Migration Skill Template

---

## Combining Templates

Skills can combine multiple patterns:

**Example: "Test Runner and Reporter" Skill**
- Combines: Validation (run tests) + Reporting (format results)
- Pattern: Execute → Analyze → Report

**Example: "Auto-Committer" Skill**
- Combines: Coordination (check task status) + Integration (git operations)
- Pattern: Query → Transform → Execute

---

## Customizing Templates

### Common Customizations

1. **Add defensive checks**:
   - Validate inputs before proceeding
   - Check for missing data
   - Handle error conditions gracefully

2. **Enhance error messages**:
   - Provide actionable error messages
   - Suggest fixes for common problems
   - Include debugging information

3. **Add logging/metrics**:
   - Track usage patterns
   - Record performance metrics
   - Enable debugging when issues occur

4. **Support configuration**:
   - Read config from .env files
   - Allow per-user customization
   - Support project-specific settings

---

## Next Steps

1. **Choose a template** that matches your Skill's purpose
2. **Copy the template** and fill in the placeholders
3. **Add examples** showing realistic usage scenarios
4. **Test activation** to ensure proper triggering
5. **Iterate** based on user feedback

For help creating a Skill, say "Help me create a Skill" and the Skill Builder will guide you through the process.
