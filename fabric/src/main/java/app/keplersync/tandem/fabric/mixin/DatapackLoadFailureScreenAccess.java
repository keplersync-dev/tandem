package app.keplersync.tandem.fabric.mixin;

import net.minecraft.client.gui.screens.DatapackLoadFailureScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Its Cancel, which closes the world it couldn't load, for when Tandem takes over. */
@Mixin(DatapackLoadFailureScreen.class)
public interface DatapackLoadFailureScreenAccess {
	@Accessor("cancelCallback")
	Runnable tandem$cancel();
}
