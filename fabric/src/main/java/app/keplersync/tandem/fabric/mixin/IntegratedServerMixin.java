package app.keplersync.tandem.fabric.mixin;

import app.keplersync.tandem.fabric.play.WorldRules;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Open to LAN puts everyone who joins in one game mode; in a synced world each person keeps their own. */
@Mixin(IntegratedServer.class)
abstract class IntegratedServerMixin {
	@Inject(method = "getForcedGameType", at = @At("HEAD"), cancellable = true)
	private void tandem$ownGameModes(CallbackInfoReturnable<GameType> cir) {
		if (WorldRules.of((IntegratedServer) (Object) this) != null) cir.setReturnValue(null);
	}
}
