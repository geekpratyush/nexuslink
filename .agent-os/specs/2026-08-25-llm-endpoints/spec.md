# Spec — Configurable LLM endpoints

**Date:** 2026-08-25 · **Status:** shipped · **Tracks:** `TASKS.md` §9.5

## Problem

The AI tester could only call `api.anthropic.com` through the vendor SDK, keyed on
`ANTHROPIC_API_KEY` from the environment, at fixed parameters (max tokens hard-coded, adaptive
thinking always on, nothing else adjustable). Inside an organisation the model is typically reached
through an internal gateway on a private hostname, authenticated with an OIDC token from the company
IdP, often behind extra routing headers — none of which the SDK path can express.

## Approach

`LlmEndpointConfig` describes an endpoint completely, and `HttpLlmClient` calls it over plain HTTP.

- **Wire format**, not vendor: Anthropic Messages (`/v1/messages`) or OpenAI-compatible chat
  completions (`/v1/chat/completions`). Internal gateways commonly expose the latter in front of
  other models, so the two are decoupled.
- **Auth:** none / API key in a named header / bearer / **OIDC client-credentials**, the last
  exchanged by `OidcTokenSource` and cached until shortly before expiry.
- **Parameters:** max tokens, temperature, top_p, top_k, stop sequences, thinking budget,
  `anthropic-version`, connect/read timeouts. A parameter the user did not set is **omitted from the
  body**, because strict gateways reject unknown or null fields (`decisions.md` #17).
- **Headers:** arbitrary extra headers, overriding defaults, for tenancy and routing.
- **Secrets:** persisted only as `${ENV_VAR}` references; a literal typed value lives for the
  session and the dialog says so (`decisions.md` #15).
- **Test connection** in the dialog sends a one-word prompt so misconfiguration surfaces during
  configuration rather than mid-task.

The SDK path remains the default for users who just have an API key.

## Out of scope

- **Streaming responses.** The panel is a single-turn tester; streaming is a separate piece of work
  that touches the response pane's rendering model.
- **Multi-turn conversation state.** One system prompt and one user message by design.
- **Tool use / function calling** from this panel — the MCP agent panel covers that.
- **Vendor SDKs beyond Anthropic.** The HTTP path covers any endpoint that speaks either format;
  adding another SDK dependency would fail the bar in `standards/tech-stack.md`.

## Verification

`LlmEndpointConfigTest`, `LlmRequestBuilderTest`, `LlmResponseParserTest`, `LlmEndpointStoreTest` —
34 tests, offline, asserting the exact request body per format and that literal secrets never reach
disk. Live calls against a real gateway are not covered by the suite.
