package local.bbsgeoimporter.mixin;

import local.bbsgeoimporter.ModelManagement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Initialization/language hooks only: registration consumes the startup manifest, never source files. */
@Pseudo
@Mixin(targets="mchorse.bbs_mod.BBSModClient",remap=false)
public abstract class ModelManagementMixin {
    @Inject(method="onInitializeClient()V",at=@At("RETURN"),require=0,remap=false)
    private void bbsGeoImporter$settingsReady(CallbackInfo ci){ModelManagement.registerOnClient();}

    @Inject(method="reloadLanguage(Ljava/lang/String;)V",at=@At("RETURN"),require=0,remap=false)
    private static void bbsGeoImporter$settingsLanguage(String language,CallbackInfo ci){ModelManagement.registerOnClient();}
}
