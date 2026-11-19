---
name: review-method
description: "Method for reviewing a code change: a spec axis (does it do the right thing), a standards axis (is it built well) and a defect search beyond the stated criteria. Every finding names its axis and carries location, expected and source, found, severity and confidence; the review closes with one verdict per axis. Use when reviewing a diff, branch or pull request someone else wrote, or when writing up review findings and a verdict for a change."
user-invocable: false
---

# Review Method

## Stance

The reviewer judges a change it did not write. An author reviewing its own work tends to explain
the work rather than test it; a reviewer who did not write it has no reason to.

A review reports and does not repair. The reviewer changes no code and no tests: it states what it
found, precisely enough that someone else can act on it.

## Evidence

- **Read the change itself, not its summary.** A description of the change says what the author
  meant to do; the diff and the files it touches say what was done.
- **Know the verification state of the exact revision under review, from evidence.** Test or
  build results count only when they name the revision they ran against. An author's claim that
  the tests pass is not evidence. Missing evidence, or evidence for an older revision than the one
  reviewed, is a finding in its own right.
- **Confirm before you report absence or breakage.** Before saying a file, function or behaviour is
  missing or broken, check it directly: open the exact expected path, search for the exact name, or
  reproduce the failure. Do not infer absence from one plausible location or from the pattern of an
  earlier bug.

## Order of work

1. [Spec axis](references/spec-axis.md): does the change do what was asked.
2. [Standards axis](references/standards-axis.md): is it built well.
3. [Defect search](references/defect-search.md): what breaks it that the stated criteria never
   mention.

Work the spec axis first: a change that does the wrong thing does not become right by being well
built, and knowing what it must do tells you where to look for defects.

## The two axes

- **Spec**: does the change do the right thing? That covers every stated criterion, and also the
  correct behaviour the criteria imply but do not spell out.
- **Standards**: is the change built well? Readable, consistent with its surroundings, no more
  complex than the problem, and safe to change later.

Every finding names its axis; [defect search](references/defect-search.md) says which axis a
defect takes.

## What a finding contains

- **Axis**: spec or standards.
- **Location**: `file:line`, or a symbol name when no single line holds it.
- **Expected, and its source**: what should be there, and where that expectation comes from (the
  criterion, the standard, or the correct behaviour the change must have).
- **Found**: what is actually there.
- **Severity**: blocking or observation, by the "Severity on this axis" rules of the finding's
  axis ([spec](references/spec-axis.md), [standards](references/standards-axis.md)).
- **Confidence**: how sure you are that this is a real problem and not a misreading, with what
  would settle it when you are not sure.

Bad: "Error handling could be better."

Specific: "spec, the header parser, at the line that splits each header line on its first colon:
expected a blank header line to give an empty result (criterion 3, 'blank input is accepted');
found it reads the second part of the split, which does not exist, and fails with an out-of-range
index error. Blocking. High confidence: reproduced with an input of a single newline."

## Report everything

Report every finding you observe, at every severity, low-confidence ones included with their
confidence stated. Apply no severity threshold and withhold nothing as too minor: a filter applied
while reviewing suppresses real findings.

## Verdict

Close the review with one verdict line per axis:

- `Spec: Pass | Pass with observations | Fail`
- `Standards: Pass | Pass with observations | Fail`

An axis is `Fail` when it has at least one blocking finding, `Pass with observations` when it has
only observations, and `Pass` when it has none. The overall verdict is the worst of the two axis
verdicts.
