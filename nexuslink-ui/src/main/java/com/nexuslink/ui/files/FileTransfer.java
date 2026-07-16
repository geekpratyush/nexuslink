package com.nexuslink.ui.files;

import java.nio.file.Path;
import java.util.function.LongConsumer;

/**
 * Moves bytes between the local disk and a remote service (SFTP/FTP) for the two-pane browser.
 * Implemented by each protocol view over its own client (e.g. SFTP via {@code SftpService}).
 */
public interface FileTransfer {

    /** Uploads {@code localFile} into remote directory {@code remoteDir}, reporting bytes sent. */
    void upload(Path localFile, String remoteDir, LongConsumer progress) throws Exception;

    /** Downloads {@code remoteFile} into local directory {@code localDir}, reporting bytes read. */
    void download(FileItem remoteFile, Path localDir, LongConsumer progress) throws Exception;

    /**
     * Uploads {@code localFile} into {@code remoteDir} but landing under {@code destName} rather than
     * the source's own file name — used by the "rename on conflict" resolution to keep both files.
     * The default ignores {@code destName} (natural name); real file systems override to honour it.
     */
    default void upload(Path localFile, String remoteDir, String destName, LongConsumer progress) throws Exception {
        upload(localFile, remoteDir, progress);
    }

    /**
     * Downloads {@code remoteFile} into {@code localDir} but landing under {@code destName} rather than
     * the remote file's own name. The default ignores {@code destName}; real file systems override it.
     */
    default void download(FileItem remoteFile, Path localDir, String destName, LongConsumer progress) throws Exception {
        download(remoteFile, localDir, progress);
    }
}
