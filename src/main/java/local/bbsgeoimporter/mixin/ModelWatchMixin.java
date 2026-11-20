package local.bbsgeoimporter.mixin;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo
@Mixin(targets={"mchorse.bbs_mod.forms.sections.ModelFormSection","mchorse.bbs_mod.cubic.model.ModelManager"},remap=false)
public abstract class ModelWatchMixin {
    @Inject(method="getTitle()Lmchorse/bbs_mod/l10n/keys/IKey;",at=@At("RETURN"),cancellable=true,require=0,remap=false)
    private void bbsGeoImporter$progress(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Object> cir){cir.setReturnValue(local.bbsgeoimporter.BbsCategoryRefresh.progressTitle(cir.getReturnValue()));}

    @Inject(method="initiate()V",at=@At("HEAD"),cancellable=true,require=0,remap=false)
    private void bbsGeoImporter$reuseList(CallbackInfo ci){if(local.bbsgeoimporter.BbsCategoryRefresh.beforeInitiate(this))ci.cancel();}
    @Inject(method="initiate()V",at=@At("RETURN"),require=0,remap=false)
    private void bbsGeoImporter$listRebuilt(CallbackInfo ci){local.bbsgeoimporter.BbsCategoryRefresh.afterInitiate(this);}
    @Inject(method="accept(Ljava/nio/file/Path;Lmchorse/bbs_mod/utils/watchdog/WatchDogEvent;)V",at=@At("HEAD"),cancellable=true,require=0,remap=false)
    private void bbsGeoImporter$ownEvent(java.nio.file.Path path,@org.spongepowered.asm.mixin.injection.Coerce Object event,CallbackInfo ci){if(local.bbsgeoimporter.ModelWatchEvents.beforeAccept(this,path,event))ci.cancel();}
}
