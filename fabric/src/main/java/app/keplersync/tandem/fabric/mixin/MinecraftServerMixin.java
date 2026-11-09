package app.keplersync.tandem.fabric.mixin;

import app.keplersync.tandem.fabric.play.WorldRules;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** In a synced world, commands come from being one of its operators, not from hosting it. */
@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
	@Shadow
	public abstract boolean isSingleplayerOwner(GameProfile profile);

	@Inject(method = "getProfilePermissions", at = @At("HEAD"), cancellable = true)
	private void tandem$worldPermissions(GameProfile profile, CallbackInfoReturnable<Integer> cir) {
		WorldRules rules = WorldRules.of((MinecraftServer) (Object) this);
		if (rules != null) cir.setReturnValue(rules.permission(profile, isSingleplayerOwner(profile)));
	}
}
