package com.worldshare.mod.cloud;

import com.google.api.client.extensions.java6.auth.oauth2.VerificationCodeReceiver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.worldshare.mod.WorldShareMod;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * A minimal {@link VerificationCodeReceiver} built on the JDK's bundled
 * {@link HttpServer}. Used by the OAuth flow to catch the authorization code that
 * Google sends back after the user consents.
 *
 * <p>Why we wrote our own instead of using {@code google-oauth-client-jetty}:
 * that artifact pulls in Jetty 9 (~2MB) as an embedded server purely for this
 * one use case. {@code com.sun.net.httpserver.HttpServer} is part of the JDK
 * and does exactly what we need in under 100 lines.
 *
 * <p>Lifecycle contract (defined by {@link VerificationCodeReceiver}):
 * <ol>
 *   <li>{@link #getRedirectUri()} is called once before the browser opens.
 *       We start an HTTP server on a free localhost port and return its URL.</li>
 *   <li>Google redirects the user's browser to {@code http://localhost:PORT/?code=...}
 *       (or {@code ?error=...}). Our handler captures those params.</li>
 *   <li>{@link #waitForCode()} is called from the flow thread to block until
 *       the redirect arrives (with a safety timeout).</li>
 *   <li>{@link #stop()} is called to shut down the HTTP server.</li>
 * </ol>
 *
 * <p>Thread safety: this class is used sequentially by the oauth flow on a
 * single thread. No external synchronization is required.
 */
public final class LocalRedirectReceiver implements VerificationCodeReceiver {

    /**
     * Maximum time we'll wait for the user to complete the OAuth flow before
     * giving up. Five minutes is plenty - if they don't act by then, they
     * probably walked away and we should release the port.
     */
    private static final long WAIT_TIMEOUT_MINUTES = 5L;

    /**
     * True while a browser round trip is outstanding.
     *
     * <p>Static because the thing that needs to know is a screen with no route
     * to the receiver, and there is only ever one of these in flight - the whole
     * flow runs on the single-threaded Drive executor.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean WAITING_FOR_BROWSER =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Whether WorldShare is currently waiting for the player to finish in their browser. */
    public static boolean isWaitingForBrowser() {
        return WAITING_FOR_BROWSER.get();
    }

    private HttpServer server;
    private int port;
    private String capturedCode;
    private String capturedError;
    /**
     * Comma-joined Drive file IDs the user selected in the Picker, when the
     * authorization URL carried {@code trigger_onepick=true}. Null on a plain
     * consent flow, or if the user dismissed the Picker without selecting.
     */
    private String capturedPickedFileIds;

    // Released once the redirect arrives (or the server is asked to stop).
    private final Semaphore gotRedirect = new Semaphore(0);

    @Override
    public String getRedirectUri() throws IOException {
        // Port 0 = let the OS pick a free one. We report the chosen port back
        // to Google via the redirect URI in the auth request, so it doesn't
        // need to be hardcoded or pre-registered for "Desktop" OAuth clients.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();

        server.createContext("/", new OAuthCallbackHandler());
        server.setExecutor(null); // use a default executor - single-threaded is fine
        server.start();

        final String uri = "http://127.0.0.1:" + port + "/";
        WorldShareMod.LOGGER.info("OAuth redirect receiver listening on {}", uri);
        return uri;
    }

    @Override
    public String waitForCode() throws IOException {
        final boolean arrived;
        // Flagged for the UI. This blocks on the single-threaded Drive executor,
        // so every screen behind it sits still until the browser comes back -
        // and a player who left the tab open sees a screen reading "Checking
        // Drive..." for up to five minutes, which looks like a hang rather than
        // like something waiting on them.
        WAITING_FOR_BROWSER.set(true);
        try {
            arrived = gotRedirect.tryAcquire(WAIT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for OAuth redirect", e);
        } finally {
            WAITING_FOR_BROWSER.set(false);
        }

        if (!arrived) {
            throw new IOException("Timed out waiting for OAuth redirect after "
                    + WAIT_TIMEOUT_MINUTES + " minutes. Did you complete the consent flow?");
        }

        if (capturedError != null) {
            throw new IOException("OAuth redirect returned error: " + capturedError);
        }

        if (capturedCode == null || capturedCode.isEmpty()) {
            throw new IOException("OAuth redirect arrived but contained no 'code' parameter");
        }

        return capturedCode;
    }

    @Override
    public void stop() {
        if (server != null) {
            // Grace period of 0 seconds - this is a one-shot server, nothing left to drain.
            server.stop(0);
            server = null;
        }
        // Release the semaphore if nothing else has, so any thread stuck in waitForCode
        // can unblock and see the stop. (Defense in depth; normally waitForCode already
        // completed by the time stop() is called.)
        gotRedirect.release();
    }

    /**
     * Drive file IDs the user picked during the consent screen's Picker step,
     * in selection order. Empty if the flow didn't trigger the Picker, or the
     * user selected nothing.
     *
     * <p>Only meaningful after {@link #waitForCode()} has returned — the value
     * arrives on the same redirect as the authorization code.
     */
    public List<String> pickedFileIds() {
        if (capturedPickedFileIds == null || capturedPickedFileIds.isBlank()) {
            return List.of();
        }
        final List<String> ids = new ArrayList<>();
        for (final String raw : capturedPickedFileIds.split(",")) {
            final String id = raw.trim();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return Collections.unmodifiableList(ids);
    }

    /** @return the redirect URI if started, or null. Public for tests/debugging. */
    public String getListeningUri() {
        return server == null ? null : "http://127.0.0.1:" + port + "/";
    }

    // -----------------------------------------------------------------

    private final class OAuthCallbackHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            try {
                final Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
                capturedCode = params.get("code");
                capturedError = params.get("error");
                capturedPickedFileIds = params.get("picked_file_ids");

                final String body;
                if (capturedError != null) {
                    body = "<html><body style='font-family:sans-serif;padding:2em'>"
                            + "<h2>WorldShare: Authorization failed</h2>"
                            + "<p>Google returned error: <code>"
                            + escapeHtml(capturedError) + "</code></p>"
                            + "<p>You can close this tab and try again in Minecraft.</p>"
                            + "</body></html>";
                } else if (capturedCode != null) {
                    // Mentions the wait deliberately. Everything after this point -
                    // exchanging the code, then creating twenty-six files - happens
                    // back in the game, and the gap between this page appearing and
                    // Minecraft reacting is long enough to read as nothing having
                    // happened.
                    body = "<html><body style='font-family:sans-serif;padding:2em'>"
                            + "<h2>WorldShare: Authorization successful ✅</h2>"
                            + "<p>You can close this tab and return to Minecraft.</p>"
                            + "<p style='color:#555'>Give it a moment to connect to "
                            + "Drive - the game may sit still for a few seconds "
                            + "before it responds.</p>"
                            + "</body></html>";
                } else {
                    body = "<html><body style='font-family:sans-serif;padding:2em'>"
                            + "<h2>WorldShare: Unexpected redirect</h2>"
                            + "<p>No 'code' or 'error' parameter. Try again.</p>"
                            + "</body></html>";
                }

                final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } finally {
                // Always release, even on exception, so the flow thread doesn't hang forever.
                gotRedirect.release();
            }
        }
    }

    private static Map<String, String> parseQuery(final String rawQuery) {
        final Map<String, String> out = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (final String pair : rawQuery.split("&")) {
            final int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            final String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            final String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    private static String escapeHtml(final String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
