---
name: schema-builder
description: Interactively build a YAML note schema for a new work item type and optionally create a companion guidance skill
---

# Schema Builder — Current (v3)

Interactively create a note schema for `.taskorchestrator/config.yaml`.

## Steps

1. Ask: "What type of work items will use this schema?" (e.g., feature, bug-fix, research)
2. Ask: "What information should agents capture at each phase?"
   - Queue phase: planning, requirements, acceptance criteria
   - Work phase: implementation notes, progress, blockers
   - Review phase: test coverage, verification results, sign-off

3. Generate the YAML schema:
```yaml
note_schemas:
  <schema-name>:
    - key: <note-key>
      role: queue  # or work, review
      required: true  # or false
      description: "What this note should contain"
      guidance: "<skill-name>"  # optional — reference a guidance skill
```

4. Show the generated YAML and ask if they want a companion guidance skill created.

5. If yes, generate a skill file at `claude-plugins/current/skills/<schema-name>/skill.md` with sections for each note key.

## Notes

- The schema name is matched against item tags. Items with tag `<schema-name>` will use this schema.
- `required: true` notes must be filled before `advance_item` gates allow progression.
- The `guidance` field can point to a skill name (e.g., `feature-planning`) or a URL.
