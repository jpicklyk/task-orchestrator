# declarations-extractor

A trait-gated item's test author is blind to the implementation by design (rule `test-author`,
section 4). That boundary only holds if someone supplies the author's inputs mechanically instead
of asking the author to look them up -- this rule defines the seat that supplies them: a
read-only, non-author extractor, dispatched after the item's implementer returns, that writes one
declarations block per item to a file before the author is dispatched.

## 1. The seat

- **Read-only, never the item's author.** The extractor is a separate dispatch (or a separate,
  declared pass within the orchestrator) from both the implementer and the test author. It never
  writes production code, test code, or any note the author later reads as guidance.
- **Dispatched after the implementer returns**, so it extracts from the real, now-existing
  surface rather than a predicted one.
- **One block per item, written to a file**, not pasted inline into a chat turn -- the file is
  what the orchestrator scans (section 3) before the author ever sees it.

## 2. Block shape

Every block carries three parts, in this order:

```
DECLARATIONS for <item> -- verbatim and complete
<every public declaration the tests touch: constructors with full parameter lists and defaults,
function/method signatures, constants, named values, and any pre-existing doc comment carrying an
oracle or stating an invariant -- including whatever validation rule fixtures must satisfy>
harness: <fully-qualified test-support helper that builds the system under test, with its
file:line -- or NONE plus the exact plugin/module list production installs>
runtime call order: <the pinned, frozen call-order statement for this surface -- see the addendum
for this repo's exact text>
```

No Kotlin, Java, or any other language-specific type name belongs in this generic shape --
concrete names live only where a specific repo's addendum states them.

**Accuracy contract** -- six rules, all mandatory:

1. Copy signatures, constructors with their defaults, named constants, and oracle-bearing doc
   comments **verbatim**, and only doc comments that pre-date this item. A doc comment the
   implementation commit itself added is implementation prose, not a pre-existing declaration --
   leave it out.
2. No prose describing implementation behavior: never paraphrase what the code does, and never
   paste a function body or a call site.
3. For every scenario input the frozen plan names (a parameter, an environment variable, a
   config key, a field name), give the exact name or write `NOT DECLARED: <what>`.
4. A claim that a file or symbol does not exist names the check that produced it.
5. `harness:` names the actual helper that assembles the system under test, fully qualified with
   its file:line -- or `NONE` plus the exact list of what production wires, so the gap is visible
   rather than improvised by the author.
6. `runtime call order:` is copied verbatim from a pinned, frozen source (a fixed sentence
   describing the framework's dispatch order, or a frozen error/validation-order table) -- never
   composed fresh by the extractor.

## 3. Orchestrator scan, before handoff

Before the declarations file reaches the test author, the orchestrator (or whoever dispatched the
extractor) greps it for behavior words -- forms like "returns", "throws", "falls back", "catches",
"calls", "if", "when", "otherwise", "instead" -- and strips every hit that describes behavior
rather than declaring a signature or a pre-existing doc comment. The `runtime call order:` line is
exempt from this strip, since it is fixed contract text supplied by the addendum, not extractor
prose. After stripping, delete any unredacted copy so only the scanned file ever reaches the
author.

The scan is not optional and not a formality: on one measured run the extractor leaked
implementation prose into the declarations file in 2 of 2 attempts despite an explicit prohibition
against it, and the scan caught both leaks before the author saw the file. Skipping the scan
because "the extractor was told not to" reproduces that failure.

## claude:

- Doc-comment wording in this repo is KDoc; "pre-existing" means it predates the item's
  implementation commit. `src/main` is the implementation tree the extractor reads from and the
  author is barred from.
- `harness:` examples for this repo: `io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.configureTestApp`
  (`ApiTestHelper.kt:153`) and `configureWriteTestApp` (`WriteRoutesTest.kt:76`).
- `runtime call order:` for an MCP tool is this pinned sentence, verbatim: "`McpToolAdapter`
  preprocesses params, calls `validateParams`, then `execute` inside `withConfigSession`;
  `execute()` alone never validates" (`interfaces/mcp/McpToolAdapter.kt:101-111`). For a REST
  route it is the route's check order, copied from the frozen error table.
- Orchestration tool names: `Grep` for the scan step; `SendMessage` to report a missing or
  unresolvable declaration back to the orchestrator rather than guessing one.
