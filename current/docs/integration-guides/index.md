# Integration Guides

MCP Task Orchestrator can be integrated at different levels of sophistication. These guides are progressive — each tier builds on the previous. Start where you are, level up when you need more.

## Guide Overview

| Tier | Guide | Audience | What You Get |
|------|-------|----------|--------------|
| 1 | [Bare MCP](bare-mcp.md) | Any MCP client user | 13 tools, persistent SQLite, role-based workflow |
| 2 | [CLAUDE.md-Driven](claude-md-driven.md) | Claude Code users | Consistent agent behavior via project instructions |
| 3 | [Note Schemas](note-schemas.md) | Schema configurers | Phase gate enforcement, required documentation |
| 4 | [Plugin: Skills and Hooks](plugin-skills-hooks.md) | Claude Code + plugin | Automated workflows, plan-mode pipeline, subagent protocols |
| 5 | [Output Styles](output-styles.md) | Power users | Full orchestrator mode with delegation |
| 6 | [Self-Improving Workflow](self-improving-workflow.md) | Self-optimizers | Feedback loop, observation logging, auto-memory correction |

## Which Guide Is Right for Me?

- "I use a non-Claude-Code MCP client" → Tier 1
- "I use Claude Code but don't want a plugin" → Tiers 1-2
- "I want to enforce documentation quality at each phase" → Add Tier 3 to any tier
- "I want automated skill workflows and plan-mode integration" → Tier 4
- "I want Claude to orchestrate and delegate, not implement directly" → Tier 5
- "I want the system to improve itself over time" → Tier 6

## Layering

Each tier adds on top of the previous. Note schemas (Tier 3) can be added independently at any tier — they require only a `.taskorchestrator/config.yaml` file and no plugin or CLAUDE.md changes. The self-improving workflow (Tier 6) is also relatively standalone — it can be adapted with or without the full output style.
