package local.bbsgeoimporter.mixin;
import org.spongepowered.asm.mixin.*;

/** Empty marker: ownership must inspect native delete before any injected callback changes its shape. */
@Pseudo
@Mixin(targets="mchorse.bbs_mod.cubic.render.vao.ModelVAO",remap=false)
public abstract class VaoOwnershipMixin {}
