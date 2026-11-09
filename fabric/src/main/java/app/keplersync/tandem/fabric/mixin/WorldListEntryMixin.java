package app.keplersync.tandem.fabric.mixin;

import app.keplersync.tandem.fabric.Guard;
import app.keplersync.tandem.fabric.ui.RealWorlds;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A synced world opened from Minecraft's own Singleplayer list goes through Tandem, so it's always the latest copy. */
@Mixin(WorldSelectionList.WorldListEntry.class)
abstract class WorldListEntryMixin {
	@Shadow
	@Final
	LevelSummary summary;

	// If Tandem's side breaks, Minecraft opens it as usual; Tandem then offers to upload what was played.
	@Inject(method = "joinWorld", at = @At("HEAD"), cancellable = true)
	private void tandem$openSyncedThroughTandem(CallbackInfo ci) {
		if (Guard.get("opening synced worlds from the list", false, () -> RealWorlds.openSynced(summary.getLevelId()))) ci.cancel();
	}
}
