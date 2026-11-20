package local.bbsgeoimporter.mixin;
import org.spongepowered.asm.mixin.*;
/** The plugin instruments verified library call sites without referencing mapped Minecraft types. */
@Pseudo
@Mixin(targets="software.bernie.geckolib.renderer.GeoRenderer",remap=false)
public interface GeckoRendererMixin {}
