package com.worldshare.mod.sync;

import java.io.IOException;

/**
 * A bucket archive on Drive disagrees with the manifest that describes it.
 *
 * <p>Almost always an interrupted push: archives are uploaded first and the
 * manifest describing them is committed last, so a push that dies in between
 * leaves the two halves out of step.
 *
 * <p>A distinct type because callers must treat it differently from an ordinary
 * transfer failure, in two ways. Retrying cannot help - the disagreement is on
 * Drive and will still be there next time - and it is the one failure a player
 * can fix from their own copy, so it is what offers the repair path.
 *
 * <p>This used to be recognised by matching a substring of the message. That broke
 * the moment the message was reworded for players, which is exactly the failure
 * mode a type prevents.
 */
public class ManifestMismatchException extends IOException {

    private static final long serialVersionUID = 1L;

    public ManifestMismatchException(final String message) {
        super(message);
    }
}
