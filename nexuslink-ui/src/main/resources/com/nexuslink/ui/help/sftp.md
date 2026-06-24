# SFTP / File Transfer

Browse remote filesystems over **SFTP** (SSH) or **FTP / FTPS**.

## SFTP
- Enter **host**, **port** (22), **username**, and **password** (SSH private-key auth is also supported).
- **Connect** opens the remote root; the tree lazily expands folders → files.
- Each file shows size, last-modified, and Unix permissions in the details panel.

## FTP / FTPS
- Enter host/port (21), username/password (or anonymous).
- Tick **Passive** for passive mode, or **FTPS** for explicit TLS.

## Object storage
For cloud object stores, use the dedicated **S3 / Object Storage**, **Azure Blob**, or **Google Cloud Storage** tabs — they browse buckets/containers → objects with the same tree.

> Tip: the *Rebex* SFTP and FTP demo servers (`test.rebex.net`, user `demo`) under **Samples (public)** are read-only public test targets.
