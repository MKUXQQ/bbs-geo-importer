package local.bbsgeoimporter.mixin;
import org.spongepowered.asm.mixin.*;
/** Bytecode bridge is signature-checked by the plugin, so this mixin has no linked BBS types. */
@Pseudo
@Mixin(targets="mchorse.bbs_mod.cubic.model.loaders.GeoCubicModelLoader",remap=false)
public abstract class SharedAssetsMixin {}
