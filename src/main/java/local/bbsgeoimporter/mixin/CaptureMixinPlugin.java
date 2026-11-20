package local.bbsgeoimporter.mixin;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.extensibility.*;

/** Only transforms an exact, checked library flow. Original calls and return values are preserved. */
public final class CaptureMixinPlugin implements IMixinConfigPlugin {
    public static volatile String status="not_loaded";
    private static final String BRIDGE="local/bbsgeoimporter/RuntimeCapture";
    public void onLoad(String pkg){}
    public String getRefMapperConfig(){return null;}
    public boolean shouldApplyMixin(String target,String mixin){
        if(!mixin.endsWith("GeckoRendererMixin")&&!mixin.endsWith("GeckoModelMixin"))return true;
        boolean found=getClass().getClassLoader().getResource(target.replace('.','/')+".class")!=null;
        status=found?"awaiting_signature_check":"library_absent";return found;
    }
    public void acceptTargets(Set<String> mine,Set<String> others){}
    public List<String> getMixins(){return null;}
    public void preApply(String n,ClassNode c,String m,IMixinInfo i){}
    public void postApply(String n,ClassNode c,String m,IMixinInfo i){if(m.endsWith("GeckoRendererMixin"))instrument(c);else if(m.endsWith("GeckoModelMixin"))instrumentAnimation(c);else if(m.endsWith("SharedAssetsMixin"))instrumentSharedAssets(c);else if(m.endsWith("ModelRefreshMixin"))instrumentRefresh(c);else if(m.endsWith("MorphingPanelMixin"))instrumentMorphing(c);else if(m.endsWith("ListPreviewMixin"))instrumentListPreview(c);else if(m.endsWith("FormGridViewportMixin"))local.bbsgeoimporter.FormGridViewportTransforms.instrument(c);else if(m.endsWith("VaoOwnershipMixin"))local.bbsgeoimporter.VaoBufferOwnership.instrument(c);else if(m.endsWith("SettingsManagementMixin"))instrumentSettingsManagement(c);}
    public static boolean instrumentSettingsManagement(ClassNode node){
        if(!node.name.equals("mchorse/bbs_mod/settings/ui/UISettingsOverlayPanel"))return false;
        MethodNode constructor=null;MethodInsnNode settings=null;
        for(MethodNode method:node.methods)if(method.name.equals("<init>")&&method.desc.equals("()V"))constructor=method;
        if(constructor==null)return false;
        for(var instruction:constructor.instructions)if(instruction instanceof MethodInsnNode call){
            if(call.owner.equals("local/bbsgeoimporter/ModelManagementUi")&&call.name.equals("beforeSettings"))return true;
            if(call.owner.equals("mchorse/bbs_mod/BBSMod")&&call.name.equals("getSettings")&&call.desc.equals("()Lmchorse/bbs_mod/settings/SettingsManager;")){if(settings!=null)return false;settings=call;}
        }
        if(settings==null)return false;
        InsnList before=new InsnList();before.add(new VarInsnNode(Opcodes.ALOAD,0));before.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"local/bbsgeoimporter/ModelManagementUi","beforeSettings","(Ljava/lang/Object;)V",false));constructor.instructions.insertBefore(settings,before);
        appendManagementUiHook(constructor,"settingsReady");return true;
    }
    private static void appendManagementUiHook(MethodNode method,String hook){
        for(var instruction:method.instructions.toArray())if(instruction.getOpcode()==Opcodes.RETURN){InsnList tail=new InsnList();tail.add(new VarInsnNode(Opcodes.ALOAD,0));tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"local/bbsgeoimporter/ModelManagementUi",hook,"(Ljava/lang/Object;)V",false));method.instructions.insertBefore(instruction,tail);}
        method.maxStack=Math.max(method.maxStack,1);
    }
    public static boolean instrumentListPreview(ClassNode node){
        if(local.bbsgeoimporter.NativeModelLoading.instrument(node))return true;
        if(local.bbsgeoimporter.CubicSetupTransforms.instrument(node))return true;
        if(node.name.equals("mchorse/bbs_mod/cubic/ModelInstance")){
            int count=0;
            for(MethodNode method:node.methods)if(method.name.equals("setup")&&method.desc.equals("()V"))for(var in:method.instructions.toArray())if(in instanceof MethodInsnNode call&&call.owner.equals("mchorse/bbs_mod/cubic/model/ModelSetupQueue")&&call.name.equals("add")&&call.desc.equals("(Ljava/lang/Runnable;)V")){
                if(call.getPrevious() instanceof MethodInsnNode prior&&prior.owner.equals("local/bbsgeoimporter/ListPreviewSetup"))continue;
                InsnList wrap=new InsnList();wrap.add(new VarInsnNode(Opcodes.ALOAD,0));wrap.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"local/bbsgeoimporter/ListPreviewSetup","wrap","(Ljava/lang/Runnable;Ljava/lang/Object;)Ljava/lang/Runnable;",false));method.instructions.insertBefore(call,wrap);method.maxStack++;count++;
            }
            return count==2;
        }
        if(!node.name.equals("mchorse/bbs_mod/ui/forms/FormCellRenderer"))return false;
        List<MethodInsnNode> calls=new ArrayList<>();
        String descriptor="(Lmchorse/bbs_mod/forms/forms/Form;Lmchorse/bbs_mod/ui/framework/UIContext;IIII)V";
        for(MethodNode method:node.methods)if(method.name.equals("render"))for(var in:method.instructions)
            if(in instanceof MethodInsnNode call){if(call.owner.equals("local/bbsgeoimporter/ListPreviewRequests")&&call.name.equals("render"))return true;if(call.getOpcode()==Opcodes.INVOKESTATIC&&call.owner.equals("mchorse/bbs_mod/forms/FormUtilsClient")&&call.name.equals("renderPreview")&&call.desc.equals(descriptor))calls.add(call);}
        if(calls.size()!=1)return false;
        MethodInsnNode call=calls.get(0);call.owner="local/bbsgeoimporter/ListPreviewRequests";call.name="render";call.desc="(Ljava/lang/Object;Ljava/lang/Object;IIII)V";return true;
    }
    public static boolean instrumentMorphing(ClassNode node){
        String list="mchorse/bbs_mod/ui/forms/UIFormList", categories="Lmchorse/bbs_mod/forms/FormCategories;";
        var calls=new ArrayList<MethodInsnNode>();
        for(var method:node.methods)if(method.name.equals("enterMorphing")&&method.desc.equals("()V"))
            for(var in:method.instructions)if(in instanceof MethodInsnNode call&&call.getOpcode()==Opcodes.INVOKEVIRTUAL&&call.owner.equals(list)&&call.name.equals("setupForms")&&call.desc.equals("("+categories+")V"))calls.add(call);
        if(calls.size()!=1)return false;
        var call=calls.get(0);call.setOpcode(Opcodes.INVOKESTATIC);call.owner="local/bbsgeoimporter/MorphingListCache";call.desc="(Ljava/lang/Object;Ljava/lang/Object;)V";return true;
    }
    public static boolean instrumentRefresh(ClassNode node){
        instrumentAvailable(node);
        instrumentInactive(node);
        var methods=node.methods.stream().filter(m->m.name.equals("getModel")&&m.desc.equals("(Ljava/lang/String;)Lmchorse/bbs_mod/cubic/ModelInstance;")).toList();
        if(methods.size()!=1||node.methods.stream().noneMatch(m->m.name.equals("forget")&&m.desc.equals("(Ljava/lang/String;)V")))return false;
        MethodNode method=methods.get(0);for(var in:method.instructions)if(in instanceof MethodInsnNode call&&call.owner.equals("local/bbsgeoimporter/SharedModelAssets")&&call.name.equals("beforeGet"))return true;
        InsnList enter=new InsnList();enter.add(new VarInsnNode(Opcodes.ALOAD,0));enter.add(new VarInsnNode(Opcodes.ALOAD,1));enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"local/bbsgeoimporter/ListModelResidency","requested","(Ljava/lang/Object;Ljava/lang/String;)V",false));enter.add(new VarInsnNode(Opcodes.ALOAD,0));enter.add(new VarInsnNode(Opcodes.ALOAD,1));enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"local/bbsgeoimporter/SharedModelAssets","beforeGet","(Ljava/lang/Object;Ljava/lang/String;)V",false));method.instructions.insert(enter);
        for(var instruction:method.instructions.toArray())if(instruction.getOpcode()==Opcodes.ARETURN){InsnList gate=new InsnList();gate.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"local/bbsgeoimporter/ListPreviewSetup","readyModel","(Ljava/lang/Object;)Ljava/lang/Object;",false));gate.add(new TypeInsnNode(Opcodes.CHECKCAST,"mchorse/bbs_mod/cubic/ModelInstance"));method.instructions.insertBefore(instruction,gate);}
        method.maxStack=Math.max(method.maxStack,2);return true;
    }
    private static void instrumentInactive(ClassNode node){
        for(MethodNode m:node.methods)if(Set.of("getModel","loadModel").contains(m.name)&&m.desc.equals("(Ljava/lang/String;)Lmchorse/bbs_mod/cubic/ModelInstance;")){
            for(var in:m.instructions)if(in instanceof MethodInsnNode call&&call.owner.equals("local/bbsgeoimporter/ImportedModelGroups")&&call.name.equals("unavailable"))return;
            InsnList enter=new InsnList();enter.add(new VarInsnNode(Opcodes.ALOAD,1));enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"local/bbsgeoimporter/ImportedModelGroups","unavailable","(Ljava/lang/String;)Z",false));LabelNode valid=new LabelNode();enter.add(new JumpInsnNode(Opcodes.IFEQ,valid));enter.add(new InsnNode(Opcodes.ACONST_NULL));enter.add(new InsnNode(Opcodes.ARETURN));enter.add(valid);enter.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));m.instructions.insert(enter);m.maxStack=Math.max(m.maxStack,1);
        }
    }
    public static boolean instrumentAvailable(ClassNode node){
        for(MethodNode m:node.methods)if(m.name.equals("getAvailableKeys")&&m.desc.equals("()Ljava/util/List;")){
            String bridge="local/bbsgeoimporter/ImportedModelGroups";for(var in:m.instructions)if(in instanceof MethodInsnNode call&&call.owner.equals(bridge))return true;
            for(var in:m.instructions.toArray())if(in.getOpcode()==Opcodes.ARETURN){InsnList exit=new InsnList();exit.add(new VarInsnNode(Opcodes.ALOAD,0));exit.add(new InsnNode(Opcodes.SWAP));exit.add(new MethodInsnNode(Opcodes.INVOKESTATIC,bridge,"filterAvailable","(Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;",false));m.instructions.insertBefore(in,exit);}
            InsnList enter=new InsnList();enter.add(new VarInsnNode(Opcodes.ALOAD,0));enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC,bridge,"cachedAvailable","(Ljava/lang/Object;)Ljava/util/List;",false));enter.add(new InsnNode(Opcodes.DUP));LabelNode miss=new LabelNode();enter.add(new JumpInsnNode(Opcodes.IFNULL,miss));enter.add(new InsnNode(Opcodes.ARETURN));enter.add(miss);enter.add(new FrameNode(Opcodes.F_SAME1,0,null,1,new Object[]{"java/util/List"}));enter.add(new InsnNode(Opcodes.POP));m.instructions.insert(enter);m.maxStack=Math.max(m.maxStack,2);
            // Real native watchdog events invalidate this memory view. UI opens never enumerate disk.
            return true;
        }return false;
    }
    public static boolean instrumentSharedAssets(ClassNode node){
        var methods=node.methods.stream().filter(m->m.name.equals("load")&&m.desc.equals("(Ljava/lang/String;Lmchorse/bbs_mod/cubic/model/ModelManager;Lmchorse/bbs_mod/resources/Link;Ljava/util/Collection;Lmchorse/bbs_mod/data/types/MapType;)Lmchorse/bbs_mod/cubic/ModelInstance;")).toList();
        if(methods.size()!=1){local.bbsgeoimporter.SharedModelAssets.status="unsupported_loader_signature";return false;}
        MethodNode method=methods.get(0);String bridge="local/bbsgeoimporter/SharedModelAssets";
        for(var in:method.instructions)if(in instanceof MethodInsnNode call&&call.owner.equals(bridge))return true;
        List<VarInsnNode> listings=new ArrayList<>();for(var in:method.instructions)if(in instanceof MethodInsnNode call&&call.owner.equals("mchorse/bbs_mod/resources/AssetProvider")&&call.name.equals("getLinksFromPath")&&in.getNext() instanceof VarInsnNode store&&store.getOpcode()==Opcodes.ASTORE)listings.add(store);
        if(listings.size()!=1){local.bbsgeoimporter.SharedModelAssets.status="unsupported_asset_listing";return false;}
        long configCalls=method.instructions.iterator().hasNext()?java.util.stream.StreamSupport.stream(method.instructions.spliterator(),false).filter(in->in instanceof MethodInsnNode call&&call.owner.equals("mchorse/bbs_mod/cubic/ModelInstance")&&call.name.equals("applyConfig")).count():0;
        if(configCalls!=1){local.bbsgeoimporter.SharedModelAssets.status="unsupported_config_flow";return false;}
        VarInsnNode listing=listings.get(0);InsnList enter=new InsnList();enter.add(new VarInsnNode(Opcodes.ALOAD,1));enter.add(new VarInsnNode(Opcodes.ALOAD,listing.var));enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC,bridge,"augment","(Ljava/lang/String;Ljava/util/Collection;)Ljava/util/Collection;",false));enter.add(new VarInsnNode(Opcodes.ASTORE,listing.var));method.instructions.insert(listing,enter);
        for(var in:method.instructions.toArray())if(in.getOpcode()==Opcodes.ARETURN){InsnList apply=new InsnList();apply.add(new InsnNode(Opcodes.DUP));apply.add(new VarInsnNode(Opcodes.ALOAD,1));apply.add(new InsnNode(Opcodes.SWAP));apply.add(new MethodInsnNode(Opcodes.INVOKESTATIC,bridge,"beforeLoadReturn","(Ljava/lang/String;Ljava/lang/Object;)V",false));method.instructions.insertBefore(in,apply);}
        method.maxStack+=2;local.bbsgeoimporter.SharedModelAssets.status="instrumented_waiting_load";return true;
    }
    public static boolean instrument(ClassNode node){
        List<MethodNode> methods=node.methods.stream().filter(m->m.name.equals("defaultRender")&&(m.access&Opcodes.ACC_STATIC)==0).toList();
        if(methods.size()!=1){status="unsupported_render_signature";return false;}
        MethodNode method=methods.get(0);
        Type[] args=Type.getArgumentTypes(method.desc);
        if(args.length!=8||!args[1].getDescriptor().equals("Lsoftware/bernie/geckolib/core/animatable/GeoAnimatable;")||Type.getReturnType(method.desc)!=Type.VOID_TYPE){status="unsupported_render_signature";return false;}
        List<MethodInsnNode> models=new ArrayList<>(),textures=new ArrayList<>();
        for(AbstractInsnNode in:method.instructions)if(in instanceof MethodInsnNode call){
            if(call.owner.equals(BRIDGE)){status="already_instrumented";return true;}
            if(call.owner.equals("software/bernie/geckolib/model/GeoModel")&&call.name.equals("getModelResource")&&Type.getArgumentTypes(call.desc).length==2)models.add(call);
            if(call.owner.equals("software/bernie/geckolib/renderer/GeoRenderer")&&call.name.equals("getTextureLocation")&&Type.getArgumentTypes(call.desc).length==1)textures.add(call);
        }
        if(models.size()!=1||textures.size()!=1){status="unsupported_resource_calls";return false;}
        for(var call:List.of(models.get(0),textures.get(0)))if(Type.getReturnType(call.desc).getSort()!=Type.OBJECT){status="unsupported_return_type";return false;}
        var start=new LabelNode();var end=new LabelNode();var handler=new LabelNode();
        InsnList enter=new InsnList();enter.add(new VarInsnNode(Opcodes.ALOAD,0));enter.add(new VarInsnNode(Opcodes.ALOAD,2));enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"begin","(Ljava/lang/Object;Ljava/lang/Object;)V",false));enter.add(start);
        method.instructions.insert(enter);
        for(var call:models)method.instructions.insert(call,capture("modelResult"));
        for(var call:textures)method.instructions.insert(call,capture("textureResult"));
        for(var in:method.instructions.toArray())if(in.getOpcode()==Opcodes.RETURN)method.instructions.insertBefore(in,new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"end","()V",false));
        method.instructions.add(end);method.instructions.add(handler);
        List<Object> locals=new ArrayList<>();locals.add(node.name);
        for(Type type:args)locals.add(switch(type.getSort()){case Type.BOOLEAN,Type.BYTE,Type.CHAR,Type.SHORT,Type.INT->Opcodes.INTEGER;case Type.FLOAT->Opcodes.FLOAT;case Type.LONG->Opcodes.LONG;case Type.DOUBLE->Opcodes.DOUBLE;default->type.getInternalName();});
        method.instructions.add(new FrameNode(Opcodes.F_FULL,locals.size(),locals.toArray(),1,new Object[]{"java/lang/Throwable"}));
        // Leave the original Throwable on stack while clearing our context, then rethrow unchanged.
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"end","()V",false));method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start,end,handler,"java/lang/Throwable"));
        method.maxStack=Math.max(method.maxStack+1,2);
        status="instrumented_waiting_observation";return true;
    }
    public static volatile String animationStatus="not_loaded";
    public static boolean instrumentAnimation(ClassNode node){
        var methods=node.methods.stream().filter(m->m.name.equals("getAnimation")&&m.desc.equals("(Lsoftware/bernie/geckolib/core/animatable/GeoAnimatable;Ljava/lang/String;)Lsoftware/bernie/geckolib/core/animation/Animation;")).toList();
        if(methods.size()!=1){animationStatus="unsupported_signature";return false;}
        MethodNode method=methods.get(0);List<MethodInsnNode> calls=new ArrayList<>();boolean source=false;
        for(var in:method.instructions)if(in instanceof MethodInsnNode call){
            if(call.owner.equals(BRIDGE)){animationStatus="already_instrumented";return true;}
            if(call.name.equals("getAnimationResource")&&call.getNext() instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ASTORE&&v.var==3)source=true;
            if(call.owner.equals("software/bernie/geckolib/loading/object/BakedAnimations")&&call.name.equals("getAnimation")&&call.desc.equals("(Ljava/lang/String;)Lsoftware/bernie/geckolib/core/animation/Animation;"))calls.add(call);
        }
        if(!source||calls.size()!=2){animationStatus="unsupported_flow";return false;}
        for(var call:calls){InsnList list=new InsnList();list.add(new InsnNode(Opcodes.DUP));list.add(new VarInsnNode(Opcodes.ALOAD,1));list.add(new VarInsnNode(Opcodes.ALOAD,3));list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"animationResult","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",false));method.instructions.insert(call,list);}
        method.maxStack+=3;animationStatus="instrumented_waiting_observation";return true;
    }
    private static InsnList capture(String method){var list=new InsnList();list.add(new InsnNode(Opcodes.DUP));list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,method,"(Ljava/lang/Object;)V",false));return list;}
}
