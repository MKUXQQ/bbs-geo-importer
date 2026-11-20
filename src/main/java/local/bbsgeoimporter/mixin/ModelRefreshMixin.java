package local.bbsgeoimporter.mixin;
import org.spongepowered.asm.mixin.*;
@Pseudo
@Mixin(targets="mchorse.bbs_mod.cubic.model.ModelManager",remap=false)
public abstract class ModelRefreshMixin {}
