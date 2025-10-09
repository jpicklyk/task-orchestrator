# Minimal Memory Configuration

## Use Case

**When to Use**:
- Just getting started with Task Orchestrator
- Want the simplest possible setup
- Don't need custom branch naming or workflows
- Team is small and doesn't have established conventions

**Best For**:
- Individual developers
- Small teams (2-3 people)
- Prototyping or exploratory projects
- Learning the system

---

## Configuration

```markdown
# Task Orchestrator - Implementation Workflow Configuration

## Pull Request Preference
use_pull_requests: "ask"
```

---

## What It Does

**Behavior**:
- Uses default branch naming for all work types
- Asks about pull requests each time (doesn't save preference)
- Applies standard template validation
- No custom workflow steps

**Default Branch Names**:
- Bugs: `bugfix/{task-id-short}-{description}`
- Features: `feature/{task-id-short}-{description}`
- Hotfixes: `hotfix/{task-id-short}-{description}`
- Enhancements: `enhancement/{task-id-short}-{description}`

**Example Branch**: `feature/70490b4d-implement-oauth-authentication`

---

## Customization Tips

**Next Steps**:

1. **Save PR Preference**: Change `"ask"` to `"always"` or `"never"` to skip repeated questions

2. **Add Branch Naming**: If you want custom patterns, add:
   ```markdown
   ## Branch Naming Conventions
   branch_naming_feature: "feat/{description}"
   ```

3. **Upgrade to Team Config**: When your team grows, consider one of the other example configurations

---

## Where to Store

**Global (Personal)**:
- Store in your AI's global memory
- Applies to all projects

**Project-Specific**:
- Add to project's `CLAUDE.md` or `.cursorrules`
- Applies only to this project
- Version-controlled with your code
