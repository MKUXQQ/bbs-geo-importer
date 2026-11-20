package local.bbsgeoimporter.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
/** The checked real constructor registration and native-icon tooltip hooks are installed by CaptureMixinPlugin. */
@Pseudo
@Mixin(targets="mchorse.bbs_mod.settings.ui.UISettingsOverlayPanel",remap=false)
public abstract class SettingsManagementMixin {}
