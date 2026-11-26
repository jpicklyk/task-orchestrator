# System context

The context map of Task Orchestrator: the zones and actors of [target-architecture.md](target-architecture.md) as nodes, and the edges between them labelled with what crosses and what kind of edge it is, drawn once as it is today and once as the target. It is for contributors and reviewers who need to see at a glance which way a dependency runs and which edges no contract declares. The "today" diagram describes origin/main `ce75ca2a8` on 2026-11-26; the "target" diagram is not built. Both diagrams use the same node names, so only edges and labels move. Edge ids such as S05 are seams in [seams.md](seams.md).

## Edge legend

| Kind | Drawn as | Meaning |
|---|---|---|
| **served fact** | solid, label starts `served:` | The server serves a fact and the reader acts on it without deriving it again |
| **declared list** | solid, label starts `declared:` | A project or operator declares config through a published seam; the reader takes it as written |
| **override** | solid, label starts `override:` | A shipped default reaches the platform through the same seam a project uses to replace it |
| **conformist** | dotted | One side adopts the other's model with no contract declaring it: reading another zone's names, prose or layout. Every dotted edge is a leak |

Edges with no kind prefix only deliver (tool calls, context injection): they carry content and decide nothing.

## Today

```mermaid
flowchart TB
  Host[Host: Claude Code]
  UserZ[User stance: style, memory]
  Instr[Instruction files]
  Server[Server: state, gates, contract]
  SrvCfg[Server config: process types]
  Mech[Plugin mechanics: hooks, mechanics skills, agents, workflows, scripts]
  Proto[Protocol rules]
  Defaults[Shipped defaults: engineering skills, conduct rules, tables, dispatch policy]
  ProjCfg[Project config: schemas, traits, seats]
  ProjRules[Project rules]
  RunProf[Run profile]
  ProjSkills[Project skills and toolchain]
  Orch[Orchestrator]
  Seats[Seat agents]
  Dir[Director]
  SrvCfg -->|declared: boot import| Server
  ProjCfg -->|declared: per-root push, orchestrator declared as a seat| Server
  Mech -->|tool calls| Server
  Orch -->|tool calls with the run token| Server
  Seats -->|tool calls with a seat token| Server
  Dir -->|decision ops, authority tested by profile| Server
  ProjCfg -->|override: seat rules and skills replace defaults| Defaults
  Mech -->|context and guards| Host
  UserZ -->|always on| Host
  Instr -->|always on| Host
  Defaults -.->|defaults by a role derived from name and phase| Mech
  Proto -.->|chosen by the derived role| Mech
  RunProf -.->|read by the planner, contract only in code| Mech
  Server -.->|run token presented as a seat in refusals| Orch
  Mech -.->|reads dogfood note keys, seat names, recomputes note owners| ProjCfg
  Mech -.->|implements live-checks and red-proof policy| ProjRules
  Mech -.->|approval sets the pipeline in motion| ProjCfg
  Mech -.->|orchestrator conduct as core-hook prose| Orch
  Mech -.->|dispatch: protocol by derived role, transitions unlisted| Seats
  Defaults -.->|note shapes inside rules| ProjCfg
  ProjRules -.->|parity test: bundled text authored here| Defaults
  ProjSkills -.->|orchestrator rules bound in prose| ProjRules
  RunProf -.->|review.step names a prose heading| ProjSkills
  Instr -.->|design records and git workflow| ProjRules
  Mech -.->|links to repo-only files| Instr
```

## Target

```mermaid
flowchart TB
  Host[Host: Claude Code]
  UserZ[User stance: style, memory]
  Instr[Instruction files]
  Server[Server: state, gates, contract]
  SrvCfg[Server config: process types]
  Mech[Plugin mechanics: hooks, mechanics skills, agents, workflows, scripts]
  Proto[Protocol rules]
  Defaults[Shipped defaults: engineering skills, conduct rules, tables, dispatch policy]
  ProjCfg[Project config: schemas, traits, seats]
  ProjRules[Project rules]
  RunProf[Run profile]
  ProjSkills[Project skills and toolchain]
  Orch[Orchestrator]
  Seats[Seat agents]
  Dir[Director]
  SrvCfg -->|declared: boot import of process types and purpose-marked mechanism traits| Server
  Defaults -->|override: templates and mechanism traits, through the seams a project uses| SrvCfg
  ProjCfg -->|declared: per-root push of purpose, writes, orchestrator binding, approval routing| Server
  Server -->|served: seat facts, note owners, purposes, orchestrator binding| Mech
  Mech -->|tool calls| Server
  Orch -->|tool calls with the run token: orchestrator notes, any trigger on a member| Server
  Seats -->|tool calls with a seat token| Server
  Dir -->|decisions_*: authority from class and the decide capability| Server
  Defaults -->|override: defaults keyed by seat name, replaced by declared lists| Mech
  Proto -->|served: chosen from enters and writes| Mech
  ProjRules -->|declared: on seat lists and the orchestrator binding| ProjCfg
  Defaults -->|override: bundled rule sync, sole source| Server
  RunProf -->|declared: documented fields verify, review.skill, postRun rule and skill| Mech
  Mech -->|declared: hands off to the bound skill| ProjSkills
  Mech -->|resolved RULES and SKILLS line of the orchestrator binding| Orch
  Mech -->|dispatch: protocol from enters and writes, effective transitions| Seats
  Mech -->|context, resolved binding lines, guards| Host
  UserZ -->|override: stance and presentation| Host
  Instr -->|repo facts and pointers to records| Host
  classDef moved stroke:#e8590c,stroke-width:3px
  class SrvCfg,ProjCfg,Defaults,ProjRules,RunProf,Instr,Mech,Orch,Seats,Dir moved
```

## What moves

- **The plugin stops reading the project.** Every dotted edge from plugin mechanics becomes a served fact or a declared list: note jobs come from note `purpose` (S05), note owners from the served owner (S64), seat capability from `writes` (S09), stage order from `after` (S08), post-run procedures from `postRun[].skill` (S30), a rule's hold list from `ruleOutputs.hold` (S41), what plan approval sets in motion from approval routing (S22).
- **The orchestrator is never a seat.** Today the project declares a seat named `orchestrator` to make orchestrator notes owned, and the server lists the run token among the seats in a refusal. In the target, `seat: orchestrator` on a note entry is a reserved owner valid in every schema (S14), the run token is described only as a token (S39, S65), and the orchestrator's conduct comes from the orchestrator binding (S13), delivered as one resolved line (S45) instead of core-hook prose ([0016](decisions/0016-seat-capability-and-orchestrator-notes.md), [0017](decisions/0017-orchestrator-binding.md)).
- **Seats get facts, not a guessed role.** The protocol rule is chosen from served `enters` and `writes`, defaults are keyed by seat name, and the dispatch lists the seat's effective `transitions` (S24, S44; [seat-model.md](seat-model.md)).
- **Decision authority leaves the profile.** The director's decision ops move from the director profile, where authority is tested by profile, to `decisions_*` on every profile that needs them, with authority from the decision class and the credential ([0003](decisions/0003-role-neutral-decisions-surface.md)).
- **The server config gains the mechanism traits**, and the bundled rules become the only source of their own text: dogfood copies go ([config-layering.md](config-layering.md)).
- **The instruction file keeps facts and pointers.** Design records and process move to decision records, project rules and the skills that own them ([0018](decisions/0018-content-placement.md)).
- **The user's stance overrides presentation** through the output style instead of the core hook carrying a presentation of its own (S46, S48).
