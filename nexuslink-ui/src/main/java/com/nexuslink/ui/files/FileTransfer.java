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

    /**
     * Whether this transport can append to a partially transferred file, letting an interrupted
     * transfer pick up mid-file rather than starting over. Transports that say true must implement
     * both {@link #uploadFrom} and {@link #downloadFrom}. See {@link ResumePlan} for when it is safe.
     */
    default boolean supportsResume() { return false; }

    /**
     * Uploads {@code localFile} from byte {@code offset} onward, appending to the partial file already
     * at {@code remoteDir/destName}. An {@code offset} of 0 is an ordinary whole-file upload.
     *
     * <p>{@code progress} reports the file's <em>total</em> bytes present (i.e. {@code offset} plus what
     * this call has sent), not just this call's contribution, so percentages and throttling stay correct
     * across a resume. The default throws, so a transport must opt in via {@link #supportsResume}.
     */
    default void uploadFrom(Path localFile, String remoteDir, String destName, long offset, LongConsumer progress) throws Exception {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " cannot resume uploads");
    }

    /**
     * Downloads {@code remoteFile} from byte {@code offset} onward, appending to the partial file at
     * {@code localDir/destName}. Progress semantics match {@link #uploadFrom}.
     */
    default void downloadFrom(FileItem remoteFile, Path localDir, String destName, long offset, LongConsumer progress) throws Exception {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " cannot resume downloads");
    }
}
