# Contributing to NexusLink

Thanks for wanting to improve it. This page covers the whole loop: fork, branch, build, test,
pull request, review.

## What you need

- **JDK 21 or newer** — `java -version` should say 21+.
- **Maven 3.9+** — `mvn -v`.
- Nothing else. The default build needs no Docker, no database and no network beyond the
  dependencies Maven downloads once.

## Fork and clone

1. **Fork** the repository from its page (the *Fork* button, top right). You do not need write
   access to the original — the fork is your own copy to push to.
2. **Clone your fork** and register the original as `upstream`, so you can keep up with it:

   ```bash
   git clone <your-fork-url> nexuslink
   cd nexuslink
   git remote add upstream <original-repository-url>
   git remote -v          # origin = your fork, upstream = the original
   ```

## Branch

Always branch from an up-to-date `main` — never commit on `main` itself:

```bash
git fetch upstream
git checkout -b feature/short-description upstream/main
```

Name the branch for what it does: `feature/kafka-acl-editor`, `fix/sftp-resume-offset`,
`docs/mongo-auth-panel`.

## Build and test

```bash
mvn -q verify                              # everything, all 29 modules
mvn -q -pl nexuslink-protocol-kafka test   # just the module you touched
mvn -pl nexuslink-app javafx:run           # run the application
```

Live integration tests that need a real server are **gated behind properties** so the default
build stays green offline — for example `-DrunMongoIT=true`, or the `test-env/` Docker Compose
harness for the `*LiveIT` tests. Turn on the ones your change touches before you push.

Every new behaviour needs a test. Prefer a **pure, dependency-free unit test** in the protocol
module over a UI test: that is why parsing, planning and formatting live in
`nexuslink-protocol-*` and `nexuslink-core` rather than in the JavaFX views.

## Commit

- One concern per commit.
- The subject line says what changed and why, in plain words — no ticket-speak, no `feat:`
  prefixes, no trailing period.
- The body, when it is needed, explains the reasoning a reviewer cannot read off the diff.

## Open the pull request

```bash
git push -u origin feature/short-description
```

Then open a pull request against `main` of the original repository. In the description:

- **What** changed, in a sentence.
- **Why** — the problem it solves.
- **How you verified it** — the tests you ran, and against what (a live broker, the in-memory
  directory server, the Compose harness).
- Link the issue it closes (`Closes #123`).

Keep the PR focused: a reviewer should be able to hold all of it in their head. Unrelated
cleanups belong in their own PR.

## While it is in review

Rebase rather than merge, so the history stays linear:

```bash
git fetch upstream
git rebase upstream/main
git push --force-with-lease
```

Push follow-up commits for review feedback; squash them only when the reviewer asks.

## Where things go

| You are adding | It belongs in |
|---|---|
| A new protocol client | a new `nexuslink-protocol-<name>` module, plus a view in `nexuslink-ui` |
| Parsing, planning, formatting, crypto | the protocol module — pure, no JavaFX, unit-tested |
| Anything shared by more than one protocol | `nexuslink-core` (vault, environments, history) |
| Screens, dialogs, panels | `nexuslink-ui`, thin — it calls the service, it does not implement it |

[`ARCHITECTURE.md`](ARCHITECTURE.md) explains the layering and walks through adding a protocol
end to end.

## Reporting a bug

Include the version (`nexuslink --where` prints the jar that ran), the operating system, the JDK,
what you did, what you expected and what happened. If it is a protocol problem, the server product
and version matter more than anything else.

## Security

Do not open a public issue for a vulnerability. Report it privately to the maintainers, with
enough detail to reproduce it, and give them time to ship a fix before disclosing.
