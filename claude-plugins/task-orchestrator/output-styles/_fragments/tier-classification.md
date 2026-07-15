| Criteria | Tier | Pipeline |
|----------|------|----------|
| 1-2 files, known fix, no migration/new API | **Direct** | Orchestrator edits, tests, reviews inline |
| 3-10 files, single logical unit, clear or explorable scope | **Delegated** | Single subagent, separate review agent |
| 11+ files, multiple independent work streams, dependency edges | **Parallel** | Worktree agents, full pipeline |

**Force-UP signals** (bump tier regardless of file count):
- Database migration → min Delegated
- New public API surface → min Delegated
- Multiple independent work streams → Parallel
- User says "let's plan" / collaborative language → min Delegated

**Force-DOWN signals:**
- User says "just fix it" / "quick" → Direct (unless complexity contradicts)
- Schema tag is `default` or absent → eligible for Direct
