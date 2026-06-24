# Environment Variables

> **Status:** on the roadmap (Phase 1.4).

The planned environment system will let you parameterise connections and requests:

- `${VAR}` substitution across URLs, headers, bodies, and connection fields.
- Per-environment variable sets (e.g. **dev / staging / prod**) you switch between.
- Values sourced from system environment, a `.env` file, and per-profile variables.
- Secret values masked in the UI and logs.

For now, use **Saved Connections** (the left panel) to keep per-environment endpoints, and the **credential vault** for secrets.
