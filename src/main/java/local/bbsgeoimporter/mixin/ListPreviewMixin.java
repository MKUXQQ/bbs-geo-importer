package local.bbsgeoimporter.mixin;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Pseudo
@Mixin(targets={"mchorse.bbs_mod.ui.forms.FormCellRenderer","mchorse.bbs_mod.ui.framework.UIContext","mchorse.bbs_mod.ui.framework.elements.UIScrollView","mchorse.bbs_mod.cubic.ModelInstance","mchorse.bbs_mod.graphics.texture.TextureManager","mchorse.bbs_mod.cubic.render.CubicVAOBuilderRenderer","mchorse.bbs_mod.cubic.render.CubicCubeRenderer","mchorse.bbs_mod.cubic.model.ModelLoader","mchorse.bbs_mod.client.BBSRendering"},remap=false)
public abstract class ListPreviewMixin {
    @Inject(method="postRender()V",at=@At("RETURN"),require=0,remap=false)
    private void bbsGeoImporter$endPreviewFrame(CallbackInfo ci){local.bbsgeoimporter.ListPreviewRequests.endFrame(this);}
    @Inject(method="delete()V",at=@At("HEAD"),require=0,remap=false)
    private void bbsGeoImporter$cancelSetup(CallbackInfo ci){local.bbsgeoimporter.ListPreviewSetup.cancel(this);}
    @Inject(method="onWorldRenderBegin()V",at=@At("HEAD"),require=0,remap=false)
    private static void bbsGeoImporter$worldSetup(CallbackInfo ci){local.bbsgeoimporter.ListPreviewSetup.worldFrame();}
    @Inject(method="render(Lmchorse/bbs_mod/ui/framework/UIContext;)V",at=@At(value="INVOKE",target="Lmchorse/bbs_mod/ui/utils/Scroll;drag(II)V",shift=At.Shift.AFTER),require=0,remap=false)
    private void bbsGeoImporter$scrollPreview(@Coerce Object context,CallbackInfo ci){local.bbsgeoimporter.ListPreviewRequests.scroll(this,context);}
    @Inject(method="getPixels(Lmchorse/bbs_mod/resources/Link;)Lmchorse/bbs_mod/utils/resources/Pixels;",at=@At("HEAD"),cancellable=true,require=0,remap=false)
    private void bbsGeoImporter$decodedListPng(@Coerce Object link,CallbackInfoReturnable<Object> ci){Object pixels=local.bbsgeoimporter.ListTextureWarmup.take(this,link);if(pixels!=null)ci.setReturnValue(pixels);}
    @Inject(method="accept(Ljava/nio/file/Path;Lmchorse/bbs_mod/utils/watchdog/WatchDogEvent;)V",at=@At("HEAD"),require=0,remap=false)
    private void bbsGeoImporter$listTextureChanged(java.nio.file.Path path,@Coerce Object event,CallbackInfo ci){local.bbsgeoimporter.ListTextureWarmup.changed(this,path);}
}
