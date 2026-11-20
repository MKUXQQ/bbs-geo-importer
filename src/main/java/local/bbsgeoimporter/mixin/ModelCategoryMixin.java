package local.bbsgeoimporter.mixin;
import local.bbsgeoimporter.ImportedModelGroups;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Pseudo
@Mixin(targets="mchorse.bbs_mod.forms.sections.SubFormSection",remap=false)
public abstract class ModelCategoryMixin {
    @Inject(method="getKey(Ljava/lang/String;)Ljava/lang/String;",at=@At("HEAD"),cancellable=true,require=0,remap=false)
    private void bbsGeoImporter$group(String key,CallbackInfoReturnable<String> cir) {
        if(!getClass().getName().equals("mchorse.bbs_mod.forms.sections.ModelFormSection"))return;
        String group=ImportedModelGroups.category(key);
        if(group!=null)cir.setReturnValue(group);
    }
}
