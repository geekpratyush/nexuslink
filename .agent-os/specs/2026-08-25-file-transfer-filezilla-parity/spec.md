# Spec — File transfer: FileZilla / WinSCP parity and beyond

**Date:** 2026-08-25 · **Status:** audit + backlog · **Tracks:** `TASKS.md` §10.3 · **Prefix:** `FX-`

Audited against the source on 2026-08-25 (`com.nexuslink.ui.files`, `SftpService`, `FtpService`,
`S3FileSystem`/`AzureBlobFileSystem`/`GcsFileSystem`).

## Already built — and already past FileZilla in places

| Capability | Where |
|------------|-------|
| Two-pane commander, local ↔ remote, tabs per protocol | `DualPaneBrowser`, `FileBrowserPane` |
| SFTP (+SCP mode), FTP/FTPS, **S3, Azure Blob, GCS** in the same commander | `*FileSystem`, `ObjectPath` |
| Transfer queue: concurrency, retries with backoff, pause/resume, per-item progress | `TransferQueue`, `RetryPolicy`, `TransferGovernor` |
| **Offset-based resume** (SFTP append / FTP `APPE`+`REST`), progress rebased so % never jumps back | `ResumePlan`, `FileTransfer` |
| **SHA-256 integrity verification** after transfer, with a safe fallback when a side can't hash | `Checksum`, `TransferIntegrity` |
| Overwrite resolver: overwrite / keep-both / **if-newer** / skip, with apply-to-all | `OverwriteResolver` |
| Directory compare incl. **content (digest) comparison**; sync browsing; sync planner | `DirectoryDiff`, `SyncBrowsing`, `SyncPlanner` |
| Drag & drop with modifier-move; same-side move via rename; duplicate; batch rename; bookmarks | `SameSideMove`, `BulkRename`, `PathBookmarks` |
| Saved sessions / quick-connect / last-directory memory (**passwords never persisted**) | `SavedSessions`, `SessionMenu` |
| Free-space indicator, quick view, file filters, nav history, breadcrumbs | `DiskSpace`, `QuickView`, `FileFilter` |

## Gaps — P1

- [ ] **FX-1 Remote file editing.** Open a remote file in an editor, and on save upload it back
      automatically (with a conflict check on mtime). WinSCP's single most-used feature; we have quick
      *view* only.
- [ ] **FX-2 Site manager with folders.** `SavedSessions` is a flat list per protocol. Needs a tree with
      folders, per-site colour/notes, and cross-protocol grouping — plus import from FileZilla's
      `sitemanager.xml` and WinSCP `.ini`, which is what makes switching painless.
- [ ] **FX-3 Directory synchronisation UI.** `SyncPlanner` exists; there is no dialog to choose direction
      (mirror / update / bidirectional), preview the plan, and run it through the transfer queue.
- [ ] **FX-4 Recursive search across the remote tree** — name glob + size/date filters + optional content
      grep for text files, with results as a virtual folder you can act on.
- [ ] **FX-5 Speed limiting and a global transfer-rate graph.** `TransferGovernor` handles concurrency but
      not bandwidth; a shared/office link needs a throttle.
- [ ] **FX-6 Transfer log + failed-queue export.** Persist the queue across restarts and let a failed batch
      be exported/re-imported, so an overnight transfer that died is resumable tomorrow.

## Gaps — P2

- [ ] **FX-7 SSH key management in-app** — generate, load from agent (`ssh-agent`/Pageant), passphrase
      caching per session, and a known-hosts viewer with a clear "host key changed" flow.
- [ ] **FX-8 FTP raw-command console** and a protocol log pane (FileZilla's message log) for diagnosing
      passive-mode/TLS failures. `SftpView` should show the SSH banner and negotiated ciphers.
- [ ] **FX-9 Permissions and ownership editor** — recursive chmod with a numeric/checkbox grid, chown/chgrp
      where the server allows it. `chmod` exists on the service; there is no UI.
- [ ] **FX-10 Queue priorities and reordering**, plus "transfer this now, ahead of the queue".
- [ ] **FX-11 Compare two remote sides** (remote↔remote), not just local↔remote, by staging through a temp
      area or by digest where both sides support it.
- [ ] **FX-12 Archive support** — browse a remote `.zip`/`.tar.gz` as a folder, extract server-side over SSH
      where available, compress-then-transfer for many small files.
- [ ] **FX-13 Cloud-native surface in the commander**: S3 storage class + versions, presigned-link copy,
      Azure SAS/tiering, GCS signed URLs. (Listed as `[-]` polish in §7.3; belongs here.)

## Gaps — P3

- [ ] **FX-14 Scheduled / watched-folder transfers** (watch a local dir, upload on change). Careful: the
      mission's non-goal is *hosted* scheduling — a foreground watcher inside the app is fine.
- [ ] **FX-15 WebDAV and SMB** as additional `FileSystem` implementations.
- [ ] **FX-16 Terminal pane docked beside the browser** (the SSH terminal exists as its own tab already).

## Beyond FileZilla / WinSCP

1. **Integrity as standard** (SHA-256 on every transfer) — FileZilla has none, WinSCP only on demand.
2. **Object storage in the same commander** as SFTP/FTP, with one mental model (`ObjectPath`).
3. **Content-aware directory compare** — digests, not timestamps, so cross-protocol stamp formats stop
   producing false "different" verdicts.
4. **One credential vault and one `${VAR}` environment set** shared with every other protocol tab.
5. **Cross-protocol pipelines** (future): download from SFTP → put to S3 → notify a Kafka topic, as one
   queued job. No file client can do this because none of them speak the other protocols.

## Build order

`FX-1` → `FX-3` → `FX-2` → `FX-4` → `FX-6` → `FX-5` → then P2.
