# Testing

## The rule

**Everything that can be tested without infrastructure must be.** The whole suite is expected to
run offline, in seconds, with no Docker, no network, and no display. That is only possible because
parsers, builders, planners and signers are pure — so keeping them pure is a testing requirement,
not just a style preference.

## What a test is for

A test states the behaviour someone depends on. Prefer a few tests that would each catch a real
regression over many that restate the implementation.

Test names read as sentences describing the guarantee:

```java
void bindsBecomePositionalParametersAndNeverEnterTheSql()
void environmentReferencesArePersistedButLiteralSecretsAreNot()
void operatorsAreNotMistakenForSubstitutionVariables()
```

Not `testPrepare1`, `testPrepare2`.

## What must have a test

- Every pure class: its happy path, its edge cases, and its *documented* failure behaviour.
- Every parser: malformed input, since "never throws" is usually part of the contract.
- Every security-relevant property, asserted directly — that a secret is **not** written to disk,
  that user input reaches the database as a bound parameter rather than as SQL text.
- Known-answer vectors where a specification provides them (the RFC 7636, RFC 4231 and MS-NLMP
  vectors in the HTTP module are the precedent).

## What doesn't

- JavaFX rendering. Layout code is verified by launching the app.
- Live protocol behaviour, which belongs in a gated `*LiveIT` against the `test-env/` Docker stack.

## Before claiming something works

1. `mvn -o test` green across the reactor.
2. For UI changes, launch the app (`cd nexuslink-app && mvn javafx:run`) and confirm it starts
   clean. A compile is not a smoke test.
3. Report the actual numbers. If something is untested, say so.
