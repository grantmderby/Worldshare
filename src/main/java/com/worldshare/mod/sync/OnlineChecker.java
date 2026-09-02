package com.worldshare.mod.sync;

import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.cloud.RemoteFileSet;
import com.worldshare.mod.cloud.CloudModule;
import com.worldshare.mod.cloud.DriveClient;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Quick Drive reachability check. Used before sync operations to give the
 * user a clear "you're offline" message rather than letting the sync hang
 * for a minute on a connection timeout.
 */
public final class OnlineChecker {

    /** Maximum time we'll wait for the check before declaring offline. */
    public static final long TIMEOUT_SECONDS = 10L;

    private OnlineChecker() {
        // utility
    }

    /**
     * Test whether Drive is reachable for sync operations.
     *
     * <p>Performs a single lightweight call (read the control file's metadata)
     * with a short timeout. Returns one of three results:
     * <ul>
     *   <li>ONLINE — Drive is reachable and we can read the folder</li>
     *   <li>OFFLINE — Network or Drive is unreachable</li>
     *   <li>NOT_AUTHENTICATED — Drive is reachable but we don't have credentials yet</li>
     * </ul>
     *
     * @param remote the world's Drive file set; its control file is the probe
     *               target, since under the {@code drive.file} scope that is
     *               something we are guaranteed to have access to, whereas the
     *               containing folder usually isn't
     * @return the result, never null
     */
    public static Result check(final RemoteFileSet remote) {
        if (remote == null || remote.controlFileId == null || remote.controlFileId.isBlank()) {
            return Result.OFFLINE;  // can't even attempt without a target
        }

        final CompletableFuture<Result> future = new CompletableFuture<>();
        CloudModule.executor().submit(() -> {
            try {
                // Must not prompt. This is a probe - it runs from the save-and-quit
                // screen among other places - and the prompting overload opens the
                // system browser when there is no stored credential. A question of
                // the form "can we reach Drive?" answering itself by hijacking the
                // player's browser is not an answer.
                //
                // NOT_AUTHENTICATED is exactly the right reply here, and both
                // callers already handle it; it was simply unreachable for the most
                // ordinary reason of all, having never signed in.
                final DriveClient client = CloudModule.driveClientIfSignedIn();
                if (client == null) {
                    future.complete(Result.NOT_AUTHENTICATED);
                    return;
                }
                final var meta = client.getFileMeta(remote.controlFileId);
                if (meta == null) {
                    future.complete(Result.OFFLINE);
                } else {
                    future.complete(Result.ONLINE);
                }
            } catch (final IOException e) {
                WorldShareMod.LOGGER.debug("OnlineChecker: I/O failure", e);
                future.complete(Result.OFFLINE);
            } catch (final Throwable t) {
                // Auth errors usually surface as RuntimeException wrapping a Google error.
                WorldShareMod.LOGGER.debug("OnlineChecker: auth/other failure", t);
                future.complete(Result.NOT_AUTHENTICATED);
            }
        });

        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final TimeoutException e) {
            return Result.OFFLINE;
        } catch (final InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            return Result.OFFLINE;
        }
    }

    public enum Result {
        ONLINE,
        OFFLINE,
        NOT_AUTHENTICATED
    }
}
