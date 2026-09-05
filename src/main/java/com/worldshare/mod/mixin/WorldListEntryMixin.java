package com.worldshare.mod.mixin;

import com.worldshare.mod.ui.OpenWorldGateScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops a shared world being opened from the vanilla world list without warning.
 *
 * <p><b>Why a mixin.</b> A WorldShare world sits in the Singleplayer list looking
 * like any other, and opening it there can mean a whole session whose changes never
 * reach Drive - or, while an upload is running, a session that fights the upload for
 * the same files. The existing defence is a chat line printed after the world has
 * already loaded, which is both too late and easy to miss.
 *
 * <p>There are three ways to open a world from that list - the Play button,
 * double-clicking an entry, and the play icon on hover - and vanilla offers no
 * event covering them. They do all converge on {@code joinWorld()}, so one
 * injection there covers every route, including any this comment hasn't found.
 * Hooking the screen's buttons instead would have caught only the first.
 *
 * <p>Deliberately does no network work: the decision comes from local state only,
 * because this runs on the render thread in response to a click.
 */
@Mixin(WorldSelectionList.WorldListEntry.class)
public abstract class WorldListEntryMixin {

    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private SelectWorldScreen screen;
    @Shadow @Final LevelSummary summary;

    @Inject(method = "joinWorld", at = @At("HEAD"), cancellable = true)
    private void worldshare$gateSharedWorld(final CallbackInfo ci) {
        final OpenWorldGateScreen gate =
                OpenWorldGateScreen.forWorld(summary.getLevelId(), screen);
        if (gate == null) {
            return;   // not a WorldShare world, or nothing worth saying about it
        }
        ci.cancel();
        minecraft.setScreen(gate);
    }
}
