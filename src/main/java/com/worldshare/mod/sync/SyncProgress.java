package com.worldshare.mod.sync;

/**
 * Callback for receiving progress updates during a {@link SyncEngine} push or pull.
 *
 * <p>Implementations are called from the {@link com.worldshare.mod.cloud.CloudModule}
 * executor thread — NOT the Minecraft main thread. Implementations that update UI
 * must marshal back via {@code Minecraft.getInstance().execute(...)}.
 *
 * <p>Implementations should also rate-limit themselves if they post to chat — being
 * called once per file on a 200-file world would spam chat. The recommended pattern
 * is to track the last update time and skip if &lt;2 seconds elapsed and progress
 * hasn't moved a meaningful amount.
 *
 * <p>Note: callbacks are best-effort. The engine doesn't promise every method is
 * called for every push, so implementations must tolerate (e.g.) {@code onComplete}
 * arriving without prior {@code onFileComplete} calls (e.g. nothing to upload).
 */
public interface SyncProgress {

    /** Called once at the start of a push/pull, after the local scan completes. */
    void onStart(int totalFiles, long totalBytes);

    /** Called after each file finishes uploading or downloading (success or fail). */
    void onFileProgress(int filesDone,
                        int totalFiles,
                        long bytesDone,
                        long totalBytes,
                        String currentFile);

    /** Called once at the end of a successful run. */
    void onComplete();

    /** Called once if the run fails with an unrecoverable error. */
    void onError(Throwable error);

    /** No-op progress reporter. Useful when the caller doesn't care about progress. */
    SyncProgress NOOP = new SyncProgress() {
        @Override public void onStart(int t, long b) {}
        @Override public void onFileProgress(int fd, int tf, long bd, long tb, String f) {}
        @Override public void onComplete() {}
        @Override public void onError(Throwable e) {}
    };
}
