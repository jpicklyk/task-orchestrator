# planning-sweeps

A plan that names what it changes also owes a search for everything else that mentions it. The
sweeps below are done while planning, so the omissions are routed before the implementer starts and
not found by the reviewer after.

1. **Scope.** Search the paths the dispatch names as its search scope (the run profile's
   `searchScope`, shown as a SEARCH SCOPE line in a seat prompt). Without one, search the whole
   repository. Never narrow the scope to the files the plan already lists.
2. **Existing-test sweep.** When the item adds to, drops from or reorders a fixed list (hook groups,
   a registry, the keys of a schema, a table of ids), search the test tree for
   that list's file name and for assertions that pin its size, length or order. Each hit becomes a
   declared edit to an existing test: `file:line`, the pin as it is, the pin as it becomes. Every
   hit file gets one disposition, either an edit (naming the line) or no edit (naming why). A
   search with no hits is still reported, as 0.
3. **Docs sweep.** Whenever the item changes or adds a script, a hook, a tool or a command, search its file
   name and the name users know it by, across the search scope. Every file that mentions it gets
   one disposition: edited here, owned by a sibling item (its short id), left alone (naming why),
   or unowned. Report an unowned file so the orchestrator can route it before review.
4. **Key-noun sweep after a blocking finding.** A fix cycle that follows a finding starts with a
   search of the feature's owned files and of the repository for the key nouns of every claim the
   finding rules wrong. Each sibling occurrence is listed as `file:line` with an owner: fixed in
   this item, a named sibling's amendment, or a new follow-up item when the site lies outside the feature.
5. **Report the sweeps, do not narrate them.** The record is the dispositions: one line per hit
   file, never a paragraph describing the search.

## claude:

- The planner's return envelope records each search in its `sweep` list: kind `tests` or `docs`,
  the `hits` and `disposed` counts (`disposed` reaches `hits`), and `unowned` for the docs sweep.
  The list is empty only for an item that changes no fixed list and no script.
