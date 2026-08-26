# Getting Started with NexusLink

NexusLink is your universal connectivity workbench — one tool for every protocol, zero context switching.

## Your First Connection

### Step 1: Create a Connection Profile

Click the **+** button in the Connections panel (left sidebar) or press **Ctrl+N**.

Choose your protocol from the dropdown:
- REST API — for HTTP endpoints
- Kafka — for event streaming
- SFTP — for file transfer
- And 20+ more protocols

### Step 2: Configure the Connection

Fill in the host, port, and authentication details. Every field that looks like `${VAR}` can be backed by an environment variable — great for switching between dev and prod without editing profiles.

Click **Test Connection** to verify each step (DNS → TCP → TLS → Auth) before saving.

### Step 3: Start Working

Once connected, the workspace opens in a new tab. Each protocol has its own panel tailored to what you need:

- REST: URL bar, headers, body, auth — just like Postman, but more powerful
- Kafka: producer, consumer, topic browser, schema registry, consumer lag
- SFTP: dual-pane file browser with drag-and-drop transfer

## Keyboard Shortcuts to Know

| Action | Shortcut |
|--------|----------|
| Open Help | F1 |
| Search saved connections | Ctrl+K |
| New REST tab | Ctrl+T |
| Send Request | Ctrl+Enter |
| Toggle the activity log | Ctrl+` |
| Switch light / dark theme | Ctrl+Shift+T |

## Finding Things in the Sidebar

The left sidebar has two search boxes, and both filter as you type:

- **Search connections…** narrows your Saved connections and the public Samples together. It matches
  a connection's name, protocol, host and user, so `kafka`, `prod kafka`, `5432` and initials like
  `asb` all work. The best match is selected as you type — press **Enter** to open it, **↓** to step
  into the list, **Esc** to clear. **Ctrl+K** puts the caret straight in the box.
- **Filter connection types…** narrows the list of protocol buttons below it. It knows the everyday
  word for each thing, not just the button label: `postgres` finds the SQL client, `queue` finds
  every broker, `bucket` finds S3 / GCS / Azure Blob. **Enter** opens the top match.

## Environment Variables

Use `${VARIABLE_NAME}` anywhere in your configuration. NexusLink resolves from:
1. Profile-level variables (dev/staging/prod sets)
2. `.env` file in your workspace
3. System environment variables

## Credential Vault

Passwords and tokens are never stored in plain text. NexusLink uses AES-256-GCM encryption with a master password. The vault auto-locks after your configured timeout.

## Getting Help

- Press **F1** on any field for context-sensitive help
- Search in this dialog — results appear instantly as you type
- Errors include a **"What does this mean?"** link to the relevant help section
