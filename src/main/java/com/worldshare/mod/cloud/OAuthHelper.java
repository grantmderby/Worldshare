package com.worldshare.mod.cloud;

import com.google.api.client.auth.oauth2.AuthorizationCodeRequestUrl;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.DriveScopes;
import com.worldshare.mod.WorldShareMod;
import com.worldshare.mod.util.BrowserOpener;
import com.worldshare.mod.util.WorldSharePaths;

import java.util.function.Consumer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;

/**
 * Owns the Google OAuth 2.0 flow: loading client secrets, triggering the browser
 * consent screen, and persisting refresh tokens for silent reuse.
 *
 * <p>There are two entry points, and the difference between them matters:
 * <ul>
 *   <li>{@link #authorize(Consumer)} - get a usable token. Returns immediately
 *       from the token store when possible. Grants no new file access.</li>
 *   <li>{@link #authorizeWithPicker} - consent <em>plus</em> a file-selection
 *       step, always a full browser round trip. This is the only way to widen
 *       what the mod can see under {@code drive.file}.</li>
 * </ul>
 *
 * <p><b>Threading:</b> both can block for minutes. Neither may be called on the
 * Minecraft main thread - dispatch via {@link CloudModule#executor()}.
 */
public final class OAuthHelper {

    /**
     * Classpath location of the bundled {@code client_secret.json}. Populated
     * by dropping the file from Google Cloud Console into:
     * {@code src/main/resources/worldshare/oauth/client_secret.json}.
     */
    private static final String CLIENT_SECRET_CLASSPATH = "/worldshare/oauth/client_secret.json";

    /**
     * Single logical user. We don't support multi-account within one
     * installation - each installer authenticates as themselves.
     */
    private static final String USER_ID = "default-user";

    /**
     * Per-file Drive access, and nothing more.
     *
     * <p>This is deliberately NOT {@code DriveScopes.DRIVE}. The broad scope is
     * classified <em>restricted</em> by Google, which caps an unverified app at
     * 100 test users with 7-day token expiry and requires a recurring paid CASA
     * security audit to escape that cap - a non-starter for this project. See
     * {@code docs/CLOUD_BACKEND_DECISION.md} for the full reasoning and the
     * testing behind it.
     *
     * <p>The tradeoff this buys us: the mod can only ever touch files the user
     * personally selected through the Picker. It cannot browse the user's Drive,
     * and it cannot see files created later inside a shared folder. That
     * constraint is what drives the fixed-bucket remote layout in
     * {@link com.worldshare.mod.sync.BucketLayout}.
     */
    private static final List<String> SCOPES = Collections.singletonList(DriveScopes.DRIVE_FILE);

    /**
     * Authorization-URL parameter that makes Google show its file Picker as part
     * of the consent screen, returning the selection on the same redirect as the
     * auth code. Documented for desktop/mobile apps at
     * https://developers.google.com/workspace/drive/picker/guides/desktop-mobile-picker
     *
     * <p>The web-app integration pattern (a {@code PickerBuilder} widget fed an
     * existing access token) does <em>not</em> work here - it was tried, and it
     * silently granted nothing. Don't "simplify" this back into two steps.
     */
    private static final String PARAM_TRIGGER_PICKER = "trigger_onepick";

    /** Authorization-URL parameter allowing multi-select in the Picker step. */
    private static final String PARAM_ALLOW_MULTIPLE = "allow_multiple";

    /**
     * Authorization-URL parameter letting the Picker select folders.
     *
     * <p>Used by the world <em>creator</em>, who needs a folder to create the fixed
     * file set inside. Note what this does and doesn't buy: a folder grant lets the
     * app create files in that folder, and files an app creates are always
     * reachable by that app for that user - but it does not extend to files another
     * account adds later. That limitation is exactly why the file set is fixed and
     * created up front.
     */
    private static final String PARAM_ALLOW_FOLDER_SELECTION = "allow_folder_selection";

    /**
     * Authorization-URL parameter restricting what the Picker offers, as a
     * comma-separated list of Drive IDs.
     *
     * <p>This is what turns joining a world from "find these eighteen files
     * somewhere in your Drive" into a single screen showing exactly the right
     * ones. Passing the world's <em>folder</em> ID is the useful case: the Picker
     * shows that one folder, the user opens it, and its contents are selectable
     * inside. Verified directly against the API.
     *
     * <p>Pair it with {@code allow_folder_selection} left off. The folder then
     * stays navigable but cannot itself be selected - which matters, because
     * selecting the folder returns a grant that reaches none of its contents and
     * looks like success to the user.
     */
    private static final String PARAM_FILE_IDS = "file_ids";

    private OAuthHelper() {
        // utility class
    }

    /**
     * Obtain a Credential, triggering the browser OAuth flow if necessary.
     *
     * <p>This overload uses {@link BrowserOpener} to try launching the system
     * browser with a log-warn fallback. Good for headless / CLI contexts.
     * For Minecraft chat integration, use {@link #authorize(Consumer)}.
     */
    public static Credential authorize() throws IOException, GeneralSecurityException {
        return authorize(url -> BrowserOpener.open(url));
    }

    /**
     * Obtain a Credential, triggering the consent flow only if the stored token
     * is missing or unusable.
     *
     * <p>The {@code urlPresenter} is invoked at most once, and only when a
     * browser round trip is actually needed. It receives the full authorization
     * URL. Typical implementations open the system browser, or post the URL as
     * a clickable Minecraft chat link. It should return promptly - waiting for
     * the user is handled internally by the redirect receiver.
     *
     * <p>Note that on a fresh install this grants a token with <em>no</em> file
     * access at all under {@code drive.file}. Use {@link #authorizeWithPicker}
     * for world setup.
     *
     * @param urlPresenter callback receiving the authorization URL
     */
    public static Credential authorize(final Consumer<String> urlPresenter)
            throws IOException, GeneralSecurityException {
        final GoogleAuthorizationCodeFlow flow = buildFlow();

        final Credential stored = loadUsableCredential(flow);
        if (stored != null) {
            WorldShareMod.LOGGER.debug("Reusing stored OAuth credential for '{}'", USER_ID);
            return stored;
        }

        WorldShareMod.LOGGER.info("Starting OAuth authorization for user '{}'", USER_ID);
        return runBrowserFlow(flow, urlPresenter, false, false, false, null).credential();
    }

    /**
     * Run a full consent + Picker round trip, returning both the credential and
     * the Drive file IDs the user selected.
     *
     * <p>Unlike {@link #authorize(Consumer)}, this <b>always</b> opens the
     * browser, even when a valid token is already stored. That's the point: the
     * token isn't what we're after, the file grants are, and Google only issues
     * those through a fresh trip past the Picker.
     *
     * <p>Previously-granted files are not revoked by re-running this. Grants
     * accumulate per (user, OAuth client), so a player can be walked through
     * setup again later to add newly-created buckets without losing access to
     * the ones they already picked.
     *
     * @param urlPresenter  callback receiving the authorization URL
     * @param allowMultiple whether the Picker permits selecting several files at
     *                      once. World setup wants {@code true}; re-picking a
     *                      single replaced file wants {@code false}.
     * @return the credential paired with whatever the user picked (possibly nothing)
     */
    public static PickerAuthResult authorizeWithPicker(final Consumer<String> urlPresenter,
                                                       final boolean allowMultiple)
            throws IOException, GeneralSecurityException {
        return authorizeWithPicker(urlPresenter, allowMultiple, false);
    }

    /**
     * As {@link #authorizeWithPicker(Consumer, boolean)}, but able to offer folders
     * in the Picker.
     *
     * @param allowFolderSelection whether folders are selectable. World creation
     *                             wants {@code true} (it needs somewhere to create
     *                             the fixed file set); joining an existing world
     *                             depends on whether a folder grant reaches files
     *                             already inside it.
     */
    public static PickerAuthResult authorizeWithPicker(final Consumer<String> urlPresenter,
                                                       final boolean allowMultiple,
                                                       final boolean allowFolderSelection)
            throws IOException, GeneralSecurityException {
        return authorizeWithPicker(urlPresenter, allowMultiple, allowFolderSelection, null);
    }

    /**
     * As {@link #authorizeWithPicker(Consumer, boolean, boolean)}, but restricting
     * what the Picker shows.
     *
     * @param scopeToIds Drive IDs the Picker should be limited to, or null for the
     *                   user's whole Drive. Passing a world's folder ID here is
     *                   what lets a joining player see just that world's files
     *                   instead of hunting for them - see {@link #PARAM_FILE_IDS}.
     */
    public static PickerAuthResult authorizeWithPicker(final Consumer<String> urlPresenter,
                                                       final boolean allowMultiple,
                                                       final boolean allowFolderSelection,
                                                       final List<String> scopeToIds)
            throws IOException, GeneralSecurityException {
        final GoogleAuthorizationCodeFlow flow = buildFlow();
        WorldShareMod.LOGGER.info(
                "Starting OAuth authorization WITH Picker (allowMultiple={}, allowFolders={}, scoped={})",
                allowMultiple, allowFolderSelection,
                scopeToIds == null ? "no" : scopeToIds.size() + " id(s)");
        final PickerAuthResult result = runBrowserFlow(
                flow, urlPresenter, true, allowMultiple, allowFolderSelection, scopeToIds);
        WorldShareMod.LOGGER.info("Picker flow complete: {} file(s) granted",
                result.pickedFileIds().size());
        return result;
    }

    /**
     * Returns true if we have a stored credential that doesn't require a browser
     * round-trip. This is used by the UI to show "Sign in" vs "Connected" states
     * without actually triggering the flow.
     */
    public static boolean hasStoredCredential() {
        final Path stored = WorldSharePaths.tokensDir().resolve("StoredCredential");
        return Files.isRegularFile(stored);
    }

    /**
     * Erase the stored credential. The next call to {@link #authorize()} will
     * trigger a full browser flow. Used by a "Sign out" menu action.
     *
     * <p>This does not cost the user their file picks. Grants live server-side
     * against the (Google account, OAuth client) pair, not inside the token, so
     * signing back in with the same account restores access to everything they
     * previously selected without another trip through the Picker. Verified
     * directly: token deleted, plain consent re-run with no Picker step, and a
     * previously-picked object was still reachable. Revoking access for real is
     * done from the user's Google account permissions page, not here.
     */
    public static void forgetStoredCredential() throws IOException {
        final Path stored = WorldSharePaths.tokensDir().resolve("StoredCredential");
        Files.deleteIfExists(stored);
        WorldShareMod.LOGGER.info("Forgot stored OAuth credential");
    }

    // -----------------------------------------------------------------

    /**
     * Drive the browser half of the OAuth dance by hand.
     *
     * <p>This deliberately doesn't use {@code AuthorizationCodeInstalledApp},
     * which the mod used before. That helper hides the redirect entirely and
     * hands back only a Credential - but under the Picker flow the redirect also
     * carries {@code picked_file_ids}, which is the whole reason we're here.
     * Doing the four steps ourselves is the only way to read both halves of it.
     */
    private static PickerAuthResult runBrowserFlow(final GoogleAuthorizationCodeFlow flow,
                                                   final Consumer<String> urlPresenter,
                                                   final boolean triggerPicker,
                                                   final boolean allowMultiple,
                                                   final boolean allowFolderSelection,
                                                   final List<String> scopeToIds)
            throws IOException {
        final LocalRedirectReceiver receiver = new LocalRedirectReceiver();
        try {
            final String redirectUri = receiver.getRedirectUri();

            final AuthorizationCodeRequestUrl authUrl = flow.newAuthorizationUrl()
                    .setRedirectUri(redirectUri);
            if (triggerPicker) {
                authUrl.set(PARAM_TRIGGER_PICKER, "true");
                if (allowMultiple) {
                    authUrl.set(PARAM_ALLOW_MULTIPLE, "true");
                }
                if (allowFolderSelection) {
                    authUrl.set(PARAM_ALLOW_FOLDER_SELECTION, "true");
                }
                if (scopeToIds != null && !scopeToIds.isEmpty()) {
                    authUrl.set(PARAM_FILE_IDS, String.join(",", scopeToIds));
                }
            }

            urlPresenter.accept(authUrl.build());

            // Blocks until the browser hits our loopback listener (or times out).
            final String code = receiver.waitForCode();
            final List<String> picked = receiver.pickedFileIds();

            final TokenResponse token = flow.newTokenRequest(code)
                    .setRedirectUri(redirectUri)
                    .execute();
            final Credential credential = flow.createAndStoreCredential(token, USER_ID);

            WorldShareMod.LOGGER.info("OAuth authorization complete");
            return new PickerAuthResult(credential, picked);
        } finally {
            receiver.stop();
        }
    }

    /**
     * Load the stored credential, but only hand it back if it's actually good for
     * something - i.e. it can refresh itself, or its current access token still
     * has meaningful life left. Mirrors the check
     * {@code AuthorizationCodeInstalledApp} used to do for us.
     *
     * @return a usable Credential, or null if the caller must re-consent
     */
    private static Credential loadUsableCredential(final GoogleAuthorizationCodeFlow flow)
            throws IOException {
        final Credential credential = flow.loadCredential(USER_ID);
        if (credential == null) {
            return null;
        }
        final boolean canRefresh = credential.getRefreshToken() != null;
        final Long expiresIn = credential.getExpiresInSeconds();
        final boolean stillFresh = expiresIn == null || expiresIn > 60L;
        return (canRefresh || stillFresh) ? credential : null;
    }

    private static GoogleAuthorizationCodeFlow buildFlow()
            throws IOException, GeneralSecurityException {
        final NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        final GsonFactory jsonFactory = GsonFactory.getDefaultInstance();
        final GoogleClientSecrets secrets = loadClientSecrets(jsonFactory);

        // The FileDataStore persists the refresh token between JVM restarts.
        // Path: <gamedir>/config/worldshare/tokens/StoredCredential
        final Path tokensDir = WorldSharePaths.tokensDir();
        Files.createDirectories(tokensDir);
        final FileDataStoreFactory dataStore = new FileDataStoreFactory(tokensDir.toFile());

        return new GoogleAuthorizationCodeFlow.Builder(transport, jsonFactory, secrets, SCOPES)
                .setDataStoreFactory(dataStore)
                // "offline" access so we receive a refresh token, not just an access token.
                .setAccessType("offline")
                // Force the consent prompt so a refresh token is always issued, and so the
                // Picker step reliably appears rather than being skipped for a returning user.
                .setApprovalPrompt("force")
                .build();
    }

    private static GoogleClientSecrets loadClientSecrets(final GsonFactory jsonFactory)
            throws IOException {
        // Precedence 1: override file at <gamedir>/config/worldshare/client_secret.json.
        // Useful for swapping credentials in development without rebuilding the jar.
        final Path override = WorldSharePaths.clientSecretOverride();
        if (Files.isRegularFile(override)) {
            WorldShareMod.LOGGER.info("Loading OAuth client_secret from override: {}", override);
            try (Reader reader = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                return GoogleClientSecrets.load(jsonFactory, reader);
            }
        }

        // Precedence 2: bundled resource in the mod jar.
        try (InputStream in = OAuthHelper.class.getResourceAsStream(CLIENT_SECRET_CLASSPATH)) {
            if (in == null) {
                throw new IOException(
                        "OAuth client_secret.json not found. Either:\n" +
                        "  - Place it at src/main/resources" + CLIENT_SECRET_CLASSPATH +
                        " (bundled in jar at build time)\n" +
                        "  - Or at " + WorldSharePaths.clientSecretOverride() +
                        " (loaded at runtime, overrides bundled)\n" +
                        "See docs/GOOGLE_CLOUD_SETUP.md for how to get this file.");
            }
            return GoogleClientSecrets.load(
                    jsonFactory,
                    new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }
}
