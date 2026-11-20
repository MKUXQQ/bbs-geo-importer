package local.bbsgeoimporter.mixin;
import org.spongepowered.asm.mixin.*;
@Pseudo
@Mixin(targets="software.bernie.geckolib.model.GeoModel",remap=false)
public abstract class GeckoModelMixin {}
