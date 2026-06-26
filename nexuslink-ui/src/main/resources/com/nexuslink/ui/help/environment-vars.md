# Environment Variables

Parameterise your connections and requests with `${VAR}` placeholders, then switch between
**dev / staging / prod** without editing a single request. Open **Tools ▸ Environments…**.

## Environments

An *environment* is a named set of variables. Create one with **New Environment…**, give it a name
(`dev`, `staging`, `prod`, …), then add variables in the table on the right. Click **Set Active** to
make an environment the one the rest of the app resolves against — the active environment is marked
with a ● in the list and shown in the status line.

## Using `${VAR}`

Reference any variable as `${NAME}` in URLs, headers, bodies, and connection fields:

```
https://${BASE_URL}/v1/users
Authorization: Bearer ${API_TOKEN}
```

- `${VAR:-fallback}` — uses `fallback` when `VAR` is unset or empty.
- `$${VAR}` — a literal `${VAR}` (the `$$` escapes the substitution).
- Values may reference other variables (e.g. `BASE_URL=${HOST}:${PORT}`); cycles are detected and
  stopped rather than looping.

## Where values come from

Resolution order, most specific first:

1. the **active environment's** variables,
2. a **`.env` file** at `~/.nexuslink/.env` (`KEY=VALUE` per line, `#` comments, optional quotes),
3. the **system environment** (`$HOME`, `$PATH`, …).

So an environment can override `${BASE_URL}` while `${HOME}` still falls through to the OS. Unknown
names are left as a literal `${NAME}` so you can see exactly what didn't resolve.

## Secrets

Tick **Secret** on a variable (passwords, tokens, and keys are auto-flagged by name) to mask its
value in the table — toggle **Reveal secrets** to show it while editing. Secret values are also
scrubbed from rendered requests and log output so a copied URL or logged body never leaks them.
