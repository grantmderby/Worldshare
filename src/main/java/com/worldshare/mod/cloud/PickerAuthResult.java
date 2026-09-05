package com.worldshare.mod.cloud;

import com.google.api.client.auth.oauth2.Credential;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Outcome of a combined consent + Picker authorization round trip.
 *
 * <p>Under the {@code drive.file} scope, an access token by itself grants
 * nothing — the app can only see files the user explicitly handed it. Google's
 * desktop Picker flow issues both halves in a single redirect: the
 * authorization code (which becomes {@link #credential}) and the list of Drive
 * file IDs the user selected (which becomes {@link #pickedFileIds}). Pairing
 * them in one object keeps callers from accidentally using a fresh credential
 * while forgetting to record what it can actually reach.
 *
 * @see OAuthHelper#authorizeWithPicker
 */
public final class PickerAuthResult {

    private final Credential credential;
    private final List<String> pickedFileIds;

    PickerAuthResult(final Credential credential, final List<String> pickedFileIds) {
        this.credential = Objects.requireNonNull(credential, "credential");
        this.pickedFileIds = Collections.unmodifiableList(
                Objects.requireNonNull(pickedFileIds, "pickedFileIds"));
    }

    /** The authorized credential. Its refresh token is already persisted. */
    public Credential credential() {
        return credential;
    }

    /**
     * Drive file IDs the user selected, in the order Google returned them.
     * Empty if the user dismissed the Picker without choosing anything —
     * callers must treat that as "setup incomplete", not as an error.
     */
    public List<String> pickedFileIds() {
        return pickedFileIds;
    }

    /** @return true if the user actually selected at least one file. */
    public boolean hasPicks() {
        return !pickedFileIds.isEmpty();
    }

    @Override
    public String toString() {
        return "PickerAuthResult{picked=" + pickedFileIds.size() + " file(s)}";
    }
}
