package app.keplersync.tandem.fabric.mixin;

import app.keplersync.tandem.fabric.Guard;
import app.keplersync.tandem.fabric.ui.Ingame;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Quit Game, the window's X and Alt+F4 all stop Minecraft here: with a session still uploading, Tandem asks first. */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "stop", at = @At("HEAD"), cancellable = true)
	private void tandem$askBeforeQuitting(CallbackInfo ci) {
		// Fails open: a broken question must never keep Minecraft from closing.
		if (Guard.get("quit check", false, Ingame::askBeforeQuitting)) ci.cancel();
	}
}
