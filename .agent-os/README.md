# Agent OS for NexusLink

A small, file-based operating layer for AI-assisted work on this repository, following the
[Agent OS](https://buildermethods.com/agent-os) idea: give an agent the *product context*, the
*house standards*, and a *written spec per feature*, so each session starts from the same shared
understanding rather than re-deriving it from the code.

## Why this exists here

`TASKS.md` is a 2,000-line running ledger. It is excellent as a record of what has been built — and
poor as an instruction to an agent about to build the next thing, because everything in it is
weighted equally and nothing states *how* work should be done. This directory separates the three
concerns:

| Question | Answered by | Changes |
|----------|-------------|---------|
| What are we building and why? | `product/` | Rarely |
| How do we build it here? | `standards/` | Rarely |
| What is this specific piece of work? | `specs/` | Per feature |
| What is done? | `../TASKS.md` | Continuously |

`TASKS.md` stays the single source of truth for status. Nothing here duplicates it; specs link to
the section of it they belong to.

## Layout

```
.agent-os/
├── product/
│   ├── mission.md      what NexusLink is, who it's for, what "done" means
│   ├── roadmap.md      phase status, pointing at TASKS.md for detail
│   └── decisions.md    architectural decisions, with the reasoning
├── standards/
│   ├── tech-stack.md   the stack and the rules about adding to it
│   ├── code-style.md   how code in this repo is expected to read
│   └── testing.md      what gets a test, and what a good one looks like
└── specs/
    └── YYYY-MM-DD-<slug>/
        ├── spec.md     the problem, the approach, and what's out of scope
        └── tasks.md    the ordered work, checked off as it lands
```

## Working agreement

1. **Read `product/` and `standards/` first** in a new session. They are short by design.
2. **A non-trivial feature gets a spec** in `specs/` before code — problem first, then approach,
   then an explicit out-of-scope list. The out-of-scope list is the part that saves time.
3. **Verify status against the code, never against notes.** `TASKS.md` and any spec can drift; a
   `grep` cannot. Claims about what exists are checked before they are written down.
4. **Update `TASKS.md` when work lands**, in the same commit as the code.
