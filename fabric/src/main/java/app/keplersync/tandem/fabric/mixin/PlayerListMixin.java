package app.keplersync.tandem.fabric.mixin;

import app.keplersync.tandem.fabric.play.Hosting;
import app.keplersync.tandem.fabric.play.WorldRules;
import com.mojang.authlib.GameProfile;
import java.net.SocketAddress;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.WorldData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A synced world's players and operators: see {@link WorldRules}. */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {
	@Shadow
	@Final
	private MinecraftServer server;

	// Whoever hosts loads their own player file, not the one level.dat keeps for the last host. Wrapped, not
	// redirected, so another mod changing the same call doesn't stop the game from starting.
	@WrapOperation(method = "load", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/WorldData;getLoadedPlayerTag()Lnet/minecraft/nbt/CompoundTag;"))
	private CompoundTag tandem$ownPlayerFile(WorldData data, Operation<CompoundTag> original) {
		return WorldRules.of(server) != null ? null : original.call(data);
	}

	// Only invited friends (or the synced world's members) get in: turned away at login, before Minecraft places them.
	@Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
	private void tandem$invitedOnly(SocketAddress address, GameProfile profile, CallbackInfoReturnable<Component> cir) {
		Component no = Hosting.checkedRefusal(server, profile);
		if (no != null) cir.setReturnValue(no);
	}

	@Inject(method = "isOp", at = @At("HEAD"), cancellable = true)
	private void tandem$worldOperators(GameProfile profile, CallbackInfoReturnable<Boolean> cir) {
		WorldRules rules = WorldRules.of(server);
		if (rules != null) cir.setReturnValue(rules.isOperator(profile.getId()));
	}

	@Inject(method = "op", at = @At("HEAD"), cancellable = true)
	private void tandem$op(GameProfile profile, CallbackInfo ci) {
		WorldRules rules = WorldRules.of(server);
		if (rules != null) {
			rules.request(profile, true);
			ci.cancel();
		}
	}

	@Inject(method = "deop", at = @At("HEAD"), cancellable = true)
	private void tandem$deop(GameProfile profile, CallbackInfo ci) {
		WorldRules rules = WorldRules.of(server);
		if (rules != null) {
			rules.request(profile, false);
			ci.cancel();
		}
	}
}
