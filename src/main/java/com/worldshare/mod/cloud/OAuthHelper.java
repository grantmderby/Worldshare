package com.worldshare.mod.cloud;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.java6.auth.oauth2.VerificationCodeReceiver;
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
 * consent screen on first run, and persisting refresh tokens for silent reuse.
 *
 * <p>Call {@link #authorize()} to obtain a {@link Credential}. On the very first
 * call, this pops the system browser and blocks until the user approves. On every
 * subsequent call it uses the stored refresh token and returns immediately.
 *
 * <p><b>Threading:</b> {@link #authorize()} can block for minutes on the first
 * invocation. It must not be called on the Minecraft main thread. Callers should
 * dispatch via {@link CloudModule#executor()}.
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
     * installation — each installer authenticates as themselves.
     */
    private static final String USER_ID = "default-user";

    /**
     * Full Drive access. See docs/GOOGLE_CLOUD_SETUP.md for the "why" on this
     * scope choice - narrower scopes like {@code drive.file} don't work across
     * shared folders created by another user.
     */
    private static final List<String> SCOPES = Collections.singletonList(DriveScopes.DRIVE);

    private OAuthHelper() {
        // utility class
    }

    /**
     * Obtain a Credential, triggering the browser OAuth flow if necessary.
     *
     * <p>This overload uses {@link BrowserOpener} to try launching the system
     * browser with a log-warn fallback. Good for headless / CLI contexts.
     * For Minecraft chat integration, use {@link #authorize(Consumer)}.
     *
     * @return a Credential whose access token will be automatically refreshed
     *         by the Google client library.
     * @throws IOException              if the token store or network fails, or
     *                                  if the user denies / times out the flow
     * @throws GeneralSecurityException if the Google trusted transport can't be built
     */
    public static Credential authorize() throws IOException, GeneralSecurityException {
        return authorize(url -> BrowserOpener.open(url));
    }

    /**
     * Obtain a Credential, triggering the OAuth flow if necessary, using a
     * caller-supplied function to present the authorization URL to the user.
     *
     * <p>The {@code urlPresenter} is invoked exactly once, only when the stored
     * credential is missing or expired. It receives the full
     * {@code https://accounts.google.com/o/oauth2/auth?...} URL. The typical
     * implementations are:
     * <ul>
     *   <li>Desktop: open the URL in the system browser</li>
     *   <li>Minecraft: post the URL as a clickable chat link</li>
     * </ul>
     *
     * <p>The presenter does NOT wait for the user to complete the flow -
     * that's handled internally by the redirect receiver. Presenter
     * implementations should return promptly.
     *
     * @param urlPresenter callback receiving the authorization URL
     */
    public static Credential authorize(final Consumer<String> urlPresenter)
            throws IOException, GeneralSecurityException {
        final NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        final GsonFactory jsonFactory = GsonFactory.getDefaultInstance();

        final GoogleClientSecrets secrets = loadClientSecrets(jsonFactory);

        // The FileDataStore persists the refresh token between JVM restarts.
        // Path: <gamedir>/config/worldshare/tokens/StoredCredential
        final Path tokensDir = WorldSharePaths.tokensDir();
        Files.createDirectories(tokensDir);
        final FileDataStoreFactory dataStore = new FileDataStoreFactory(tokensDir.toFile());

        final GoogleAuthorizationCodeFlow flow =
                new GoogleAuthorizationCodeFlow.Builder(transport, jsonFactory, secrets, SCOPES)
                        .setDataStoreFactory(dataStore)
                        // "offline" access so we receive a refresh token, not just an access token.
                        .setAccessType("offline")
                        // Force the consent prompt on the very first authorization so the refresh
                        // token is always issued. If we've already authorized once, the flow
                        // skips the browser entirely and uses the stored token.
                        .setApprovalPrompt("force")
                        .build();

        final VerificationCodeReceiver receiver = new LocalRedirectReceiver();
        // AuthorizationCodeInstalledApp's third param is a Browser functional interface
        // whose single method takes a URL and returns void. We delegate to our
        // Consumer<String>, which the caller provides.
        final AuthorizationCodeInstalledApp app = new AuthorizationCodeInstalledApp(
                flow,
                receiver,
                url -> urlPresenter.accept(url));

        WorldShareMod.LOGGER.info("Starting OAuth authorization for user '{}'", USER_ID);
        final Credential credential = app.authorize(USER_ID);
        WorldShareMod.LOGGER.info("OAuth authorization complete");
        return credential;
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
     */
    public static void forgetStoredCredential() throws IOException {
        final Path stored = WorldSharePaths.tokensDir().resolve("StoredCredential");
        Files.deleteIfExists(stored);
        WorldShareMod.LOGGER.info("Forgot stored OAuth credential");
    }

    // -----------------------------------------------------------------

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
