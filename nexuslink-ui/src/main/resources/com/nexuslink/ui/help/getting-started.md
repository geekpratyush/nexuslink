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
| Command Palette | Ctrl+K |
| Global Search | Ctrl+Shift+F |
| New Tab | Ctrl+T |
| Send Request | Ctrl+Enter |
| Save Profile | Ctrl+S |

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
