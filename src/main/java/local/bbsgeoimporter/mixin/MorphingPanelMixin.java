package local.bbsgeoimporter.mixin;
import org.spongepowered.asm.mixin.*;
@Pseudo
@Mixin(targets="mchorse.bbs_mod.ui.morphing.UIMorphingPanel",remap=false)
public abstract class MorphingPanelMixin {}
