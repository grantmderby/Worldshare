package com.worldshare.mod.util;

import com.worldshare.mod.WorldShareMod;

import java.awt.Desktop;
import java.net.URI;

/**
 * Opens URLs in the user's default system browser. Used by the OAuth flow
 * to show the Google consent page.
 *
 * <p>Written defensively: if {@link Desktop#browse(URI)} fails (headless system,
 * missing browser, SecurityException, etc) we do NOT throw — we log the URL
 * loudly so the user can copy-paste it manually. An OAuth flow that dies
 * silently because of a browser launch failure is the worst kind of bug.
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
