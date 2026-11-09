package app.keplersync.tandem.fabric.mixin;

import java.util.BitSet;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Which of Minecraft's toast slots are in use, so Tandem's in-game toast goes below them instead of under them. */
@Mixin(ToastComponent.class)
public interface ToastComponentAccess {
	@Accessor("occupiedSlots")
	BitSet tandem$occupiedSlots();
}
