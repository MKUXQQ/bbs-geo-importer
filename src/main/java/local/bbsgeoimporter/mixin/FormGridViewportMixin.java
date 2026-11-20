package local.bbsgeoimporter.mixin;
import org.spongepowered.asm.mixin.*;
/** Empty dispatch marker. The plugin applies an exact-shape guarded transform; unknown versions stay native. */
@Pseudo
@Mixin(targets="mchorse.bbs_mod.ui.framework.elements.input.items.UIItemGrid",remap=false)
public abstract class FormGridViewportMixin {}
