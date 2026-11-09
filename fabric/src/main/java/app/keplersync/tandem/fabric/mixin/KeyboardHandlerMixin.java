package app.keplersync.tandem.fabric.mixin;

import app.keplersync.tandem.fabric.Guard;
import app.keplersync.tandem.fabric.ui.PausePanel;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Letters typed on the pause screen reach a Tandem field there (Send logs, Report): Fabric has no event for them. */
@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
	@Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
	private void tandem$typeIntoPausePanel(long window, int codePoint, int modifiers, CallbackInfo ci) {
		if (Guard.get("pause card", false, () -> PausePanel.typed(codePoint))) ci.cancel();
	}
}
