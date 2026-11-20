package local.bbsgeoimporter;
import java.nio.file.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import local.bbsgeoimporter.mixin.CaptureMixinPlugin;
public final class HookAcceptance {
    static int count(ClassNode c,String owner){int n=0;for(var m:c.methods)for(var i:m.instructions)if(i instanceof MethodInsnNode call&&call.owner.equals(owner))n++;return n;}
    static void availableRuntime()throws Exception{
        ClassNode node=new ClassNode();node.version=61;node.access=1;node.name="fixture/AvailableManager";node.superName="java/lang/Object";
        MethodNode constructor=new MethodNode(1,"<init>","()V",null,null);constructor.instructions.add(new VarInsnNode(25,0));constructor.instructions.add(new MethodInsnNode(183,"java/lang/Object","<init>","()V",false));constructor.instructions.add(new InsnNode(177));constructor.maxStack=1;constructor.maxLocals=1;node.methods.add(constructor);
        MethodNode keys=new MethodNode(1,"getAvailableKeys","()Ljava/util/List;",null,null);keys.instructions.add(new MethodInsnNode(184,"java/util/Collections","emptyList","()Ljava/util/List;",false));keys.instructions.add(new InsnNode(176));keys.maxStack=1;keys.maxLocals=1;node.methods.add(keys);
        if(!CaptureMixinPlugin.instrumentAvailable(node))throw new AssertionError("available hook");ClassWriter writer=new ClassWriter(0);node.accept(writer);byte[] bytes=writer.toByteArray();
        Class<?> type=new ClassLoader(HookAcceptance.class.getClassLoader()){Class<?> define(){return defineClass("fixture.AvailableManager",bytes,0,bytes.length);}}.define();Object manager=type.getConstructor().newInstance();long before=PerformanceAcceptance.count("bbs.nativeKeyScan");for(int i=0;i<20;i++)type.getMethod("getAvailableKeys").invoke(manager);if(PerformanceAcceptance.count("bbs.nativeKeyScan")!=before+1)throw new AssertionError("20 manager hook calls repeated native enumeration");ImportedModelGroups.nativeAssetsChanged();type.getMethod("getAvailableKeys").invoke(manager);if(PerformanceAcceptance.count("bbs.nativeKeyScan")!=before+2)throw new AssertionError("real native change must invalidate cached available keys");System.out.println("AVAILABLE JVM HOOK PASS: 20 calls, 1 native enumeration; native change invalidates once");
    }
    public static void main(String[] args)throws Exception{
        availableRuntime();
        var policy=new CaptureMixinPlugin();
        if(!policy.shouldApplyMixin("unused", "SharedAssetsMixin")||!policy.shouldApplyMixin("unused", "ModelRefreshMixin"))throw new AssertionError("BBS shared asset and refresh hooks must be enabled");
        if(!policy.shouldApplyMixin("unused", "ModelCategoryMixin"))throw new AssertionError("Model categories must remain enabled");
        if(SharedModelAssets.status.equals("disabled_white_screen_diagnostic"))throw new AssertionError("Diagnostic bypass remains active");
        System.out.println("FORMAL POLICY PASS: BBS shared asset and refresh hooks enabled");
        for(String path:args)try(JarFile jar=new JarFile(path)){
            if(jar.getJarEntry("mchorse/bbs_mod/cubic/model/loaders/GeoCubicModelLoader.class")!=null){
                ClassNode loader=new ClassNode();new ClassReader(jar.getInputStream(jar.getJarEntry("mchorse/bbs_mod/cubic/model/loaders/GeoCubicModelLoader.class"))).accept(loader,0);
                if(!CaptureMixinPlugin.instrumentSharedAssets(loader))throw new AssertionError(SharedModelAssets.status);
                for(MethodNode method:loader.methods)if((method.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0)new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(loader.name,method);
                if(count(loader,"local/bbsgeoimporter/SharedModelAssets")<2)throw new AssertionError("BBS bridge calls missing");
                MethodNode load=loader.methods.stream().filter(m->m.name.equals("load")&&m.desc.equals("(Ljava/lang/String;Lmchorse/bbs_mod/cubic/model/ModelManager;Lmchorse/bbs_mod/resources/Link;Ljava/util/Collection;Lmchorse/bbs_mod/data/types/MapType;)Lmchorse/bbs_mod/cubic/ModelInstance;")).findFirst().orElseThrow();int configAt=-1,applyAt=-1,position=0;for(var insn:load.instructions){if(insn instanceof MethodInsnNode call&&call.owner.equals("mchorse/bbs_mod/cubic/ModelInstance")&&call.name.equals("applyConfig"))configAt=position;if(insn instanceof MethodInsnNode call&&call.owner.equals("local/bbsgeoimporter/SharedModelAssets")&&call.name.equals("beforeLoadReturn"))applyAt=position;position++;}if(configAt<0||applyAt<configAt)throw new AssertionError("final texture binding must be applied after BBS applyConfig");
                ClassNode manager=new ClassNode();new ClassReader(jar.getInputStream(jar.getJarEntry("mchorse/bbs_mod/cubic/model/ModelManager.class"))).accept(manager,0);
                for(String target:java.util.List.of("mchorse/bbs_mod/cubic/model/ModelManager","mchorse/bbs_mod/forms/sections/ModelFormSection")){ClassNode watched=new ClassNode();new ClassReader(jar.getInputStream(jar.getJarEntry(target+".class"))).accept(watched,0);if(watched.methods.stream().noneMatch(m->m.name.equals("accept")&&m.desc.equals("(Ljava/nio/file/Path;Lmchorse/bbs_mod/utils/watchdog/WatchDogEvent;)V")))throw new AssertionError("Watcher signature changed: "+target);}
                if(!CaptureMixinPlugin.instrumentRefresh(manager))throw new AssertionError("Unsupported model refresh flow");
                for(MethodNode method:manager.methods)if((method.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0)new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(manager.name,method);
                System.out.println("BBS HOOK PASS: real loader asset listing augmented; ASM verified; rendering not tested");continue;
            }
            ClassNode render=new ClassNode(),model=new ClassNode();new ClassReader(jar.getInputStream(jar.getJarEntry("software/bernie/geckolib/renderer/GeoRenderer.class"))).accept(render,0);new ClassReader(jar.getInputStream(jar.getJarEntry("software/bernie/geckolib/model/GeoModel.class"))).accept(model,0);
            int calls=count(render,"software/bernie/geckolib/model/GeoModel");if(!CaptureMixinPlugin.instrument(render))throw new AssertionError(CaptureMixinPlugin.status);
            if(calls!=count(render,"software/bernie/geckolib/model/GeoModel"))throw new AssertionError("resource calls changed");
            if(!CaptureMixinPlugin.instrumentAnimation(model))throw new AssertionError(CaptureMixinPlugin.animationStatus);
            for(ClassNode node:new ClassNode[]{render,model}){for(MethodNode method:node.methods)if((method.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0)new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(node.name,method);ClassWriter writer=new ClassWriter(0);node.accept(writer);byte[] result=writer.toByteArray();new ClassReader(result).accept(new org.objectweb.asm.util.CheckClassAdapter(new ClassWriter(0),false),0);}
            System.out.println("HOOK PASSED "+path+"; original resource calls preserved; ASM structure verified");
        }
    }
}
