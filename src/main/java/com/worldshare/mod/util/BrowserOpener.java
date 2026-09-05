package com.worldshare.mod.util;

import com.worldshare.mod.WorldShareMod;

import java.awt.Desktop;
import java.net.URI;

/**
 * Opens URLs in the user's default system browser. Used by the OAuth flow
 * to show the Google consent page.
 *
 * <p>Tries Minecraft's own opener first, and that ordering is not arbitrary.
 * {@link Desktop} does not work inside a running Minecraft client:
 * {@code Desktop.isDesktopSupported()} returns false, so the AWT path silently
 * fell through to "copy this URL from the log" - which, from a GUI screen that
 * has already said "waiting for you to finish in your browser", is
 * indistinguishable from the button doing nothing. Minecraft's
 * {@code Util.getPlatform().openUri} shells out to the OS instead
 * ({@code rundll32 url.dll,FileProtocolHandler} on Windows) and works fine.
 *
 * <p>{@link Desktop} remains as a fallback for contexts with no Minecraft
 * client - and the log line remains after that, because an OAuth flow that dies
 * silently on a browser launch failure is the worst kind of bug.
 */
public final class BrowserOpener {

    private BrowserOpener() {
        // utility class
    }

    /**
     * Attempt to open {@code url} in the system browser.
     *
     * @return true if the browser was successfully launched, false otherwise.
     *         In the false case, a loud WARN with the URL was logged.
     */
    public static boolean open(final String url) {
        WorldShareMod.LOGGER.info("Opening OAuth URL: {}", url);

        // Minecraft's opener, which is the one that actually works in-game.
        // Guarded because this class is also reachable from non-client contexts
        // where net.minecraft.Util may not be loadable.
        try {
            net.minecraft.Util.getPlatform().openUri(url);
            return true;
        } catch (final Throwable t) {
            WorldShareMod.LOGGER.debug(
                    "Minecraft's URI opener unavailable ({}); trying AWT Desktop",
                    t.getClass().getSimpleName());
        }

        try {
            if (Desktop.isDesktopSupported()) {
                final Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(URI.create(url));
                    return true;
                }
            }
        } catch (final Throwable t) {
            // Catch Throwable, not just Exception - on some Linux WMs this throws
            // java.lang.InternalError or similar AWT-related things.
            WorldShareMod.LOGGER.warn("Desktop.browse failed; falling back to manual URL", t);
        }

        WorldShareMod.LOGGER.warn(
                "Could not auto-open browser. Please copy this URL manually:\n  {}",
                url);
        return false;
    }
}
