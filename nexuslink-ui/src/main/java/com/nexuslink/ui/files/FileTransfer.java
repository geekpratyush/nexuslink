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
}
