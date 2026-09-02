package com.worldshare.mod.util;

import com.worldshare.mod.WorldShareMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * Tells the player something, whether or not they are still in a world.
 *
 * <p>Chat is not good enough on its own here, and the reason is structural rather
 * than cosmetic. WorldShare's most important messages - "your world synced", "the
 * upload failed, your changes are still local" - are produced by code that runs
 * during or after world shutdown: the auto-push on {@code ServerStopping}, and the
 * save-and-quit screen, which hands control to the title screen. By the time those
 * have an answer, {@code Minecraft.player} is null and there is no chat to write
 * to. Every one of those messages was being swallowed into the log.
 *
 * <p>Toasts have no such constraint - they render over any screen, title screen
 * included - so this picks whichever surface currently exists.
 */
public final class PlayerNotice {

    /**
     * Separate toast identities so a sync result never silently replaces an error,
     * and so repeated notices of the same kind stack rather than overwrite.
     */
    private static final SystemToast.SystemToastId INFO_ID = new SystemToast.SystemToastId();
    private static final SystemToast.SystemToastId ERROR_ID =
            new SystemToast.SystemToastId(10_000L);   // errors linger; they need reading

    private PlayerNotice() {}

    /** Post an informational notice. */
    public static void info(final String message) {
        send(message, false);
    }

    /** Post a failure notice. Shown for longer, since it usually asks for an action. */
    public static void error(final String message) {
        send(message, true);
    }

    /**
     * Post a toast even when the player is in a world and chat is available.
     *
     * <p>For the few things too important to risk being scrolled past. Losing the
     * session lock mid-session is the case this exists for: everything from that
     * moment on is unsyncable, and a chat line three messages up is not enough
     * warning for work measured in hours.
     */
    public static void alsoToast(final String message) {
        try {
            final Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            mc.execute(() -> {
                try {
                    mc.getToasts().addToast(SystemToast.multiline(
                            mc, ERROR_ID, Component.literal("WorldShare"),
                            Component.literal(strip(message))));
                } catch (final Throwable t) {
                    WorldShareMod.LOGGER.warn("[notice] couldn't toast: {}", strip(message), t);
                }
            });
        } catch (final Throwable t) {
            WorldShareMod.LOGGER.debug("PlayerNotice.alsoToast failed", t);
        }
    }

    /**
     * Show ongoing progress above the hotbar, replacing whatever was there.
     *
     * <p>For work that takes long enough to look like a hang. Setup creates
     * twenty-six files in Drive one at a time, which is around half a minute of
     * nothing on screen - long enough that players concluded the command had
     * failed and ran it again.
     *
     * <p>The action bar rather than chat because each update overwrites the last.
     * Twenty-six chat lines would push the result off the top of the screen,
     * which is the problem setup already had once.
     *
     * <p>Silently does nothing when there is no player, which is correct: this
     * only ever carries progress, and the outcome is reported separately.
     */
    public static void progress(final String message) {
        try {
            final Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            mc.execute(() -> {
                try {
                    if (mc.player != null) {
                        mc.player.displayClientMessage(Component.literal(message), true);
                    }
                } catch (final Throwable t) {
                    WorldShareMod.LOGGER.debug("[notice] couldn't show progress", t);
                }
            });
        } catch (final Throwable t) {
            WorldShareMod.LOGGER.debug("PlayerNotice.progress failed", t);
        }
    }

    /**
     * @param message   may carry Minecraft colour codes; they are used in chat and
     *                  stripped for the toast, whose pale background makes the
     *                  darker ones effectively invisible
     * @param isError   selects the toast identity and how long it stays up
     */
    public static void send(final String message, final boolean isError) {
        try {
            final Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                WorldShareMod.LOGGER.info("[notice] {}", strip(message));
                return;
            }
            mc.execute(() -> {
                try {
                    if (mc.player != null) {
                        mc.player.displayClientMessage(Component.literal(message), false);
                        return;
                    }
                    mc.getToasts().addToast(SystemToast.multiline(
                            mc,
                            isError ? ERROR_ID : INFO_ID,
                            Component.literal("WorldShare"),
                            Component.literal(strip(message))));
                } catch (final Throwable t) {
                    // A notice failing must never take down a sync. Losing the
                    // message to the log is the acceptable outcome here.
                    WorldShareMod.LOGGER.warn("[notice] couldn't display: {}", strip(message), t);
                }
            });
        } catch (final Throwable t) {
            WorldShareMod.LOGGER.debug("PlayerNotice failed", t);
        }
    }

    /** Drop Minecraft's section-sign formatting codes. */
    private static String strip(final String message) {
        return message == null ? "" : message.replaceAll("§.", "");
    }
}
