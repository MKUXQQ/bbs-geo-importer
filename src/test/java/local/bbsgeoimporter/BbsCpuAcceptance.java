package local.bbsgeoimporter;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Executes installed BBS loader/parser/config on CPU. Test-only seams remove render entry points. */
final class BbsCpuAcceptance {
    static void run(Path assets,Map<String,SharedModelAssets.Binding> bindings)throws Exception{
        run(assets,bindings,Path.of("E:/我的世界/.minecraft/versions/bbs1.20.1动画/mods/bbs-2.7-1.20.1.jar"));
    }
    static void run(Path assets,Map<String,SharedModelAssets.Binding> bindings,Path jar)throws Exception{
        List<URL> urls=new ArrayList<>();urls.add(jar.toUri().toURL());
        try(var walk=Files.walk(Path.of("E:/我的世界/.minecraft/libraries"))){for(Path p:walk.filter(p->p.toString().endsWith(".jar")&&(p.toString().contains("joml")||p.toString().contains("guava")||p.toString().contains("fastutil")||p.toString().contains("commons-lang3")||p.toString().contains("datafixerupper")||p.toString().contains("slf4j"))).toList())urls.add(p.toUri().toURL());}
        try(var loader=new URLClassLoader(urls.toArray(URL[]::new),BbsCpuAcceptance.class.getClassLoader()){
            @Override protected Class<?> findClass(String name)throws ClassNotFoundException{
                try{
                    if(name.equals("mchorse.bbs_mod.BBSMod"))return defineClass(name,providerFacade(),0,providerFacade().length);
                    if(name.equals("mchorse.bbs_mod.cubic.model.ModelManager")){byte[] b=managerFacade();return defineClass(name,b,0,b.length);}
                    if(name.equals("mchorse.bbs_mod.cubic.render.WeldGeometryCache")){byte[] b=empty(name.replace('.','/'));return defineClass(name,b,0,b.length);}
                    if(name.equals("net.minecraft.class_1304")){ClassNode n=new ClassNode();new ClassReader(empty(name.replace('.','/'))).accept(n,0);for(String field:List.of("field_6169","field_6174","field_6172","field_6166"))n.fields.add(new FieldNode(9,field,"Lnet/minecraft/class_1304;",null,null));ClassWriter w=new ClassWriter(0);n.accept(w);byte[] b=w.toByteArray();return defineClass(name,b,0,b.length);}
                    if(name.equals("mchorse.bbs_mod.cubic.ModelInstance")||name.equals("mchorse.bbs_mod.cubic.model.loaders.GeoCubicModelLoader")){
                        ClassNode n=new ClassNode();try(var in=getResourceAsStream(name.replace('.','/')+".class")){new ClassReader(in).accept(n,0);}
                        if(name.endsWith("ModelInstance")){n.interfaces.clear();n.methods.removeIf(m->!Set.of("<init>","applyConfig","getTexture","setSourceFile").contains(m.name));}
                        else if(!local.bbsgeoimporter.mixin.CaptureMixinPlugin.instrumentSharedAssets(n))throw new AssertionError("real BBS loader seam");
                        ClassWriter out=new ClassWriter(0);n.accept(out);byte[] b=out.toByteArray();return defineClass(name,b,0,b.length);
                    }
                }catch(Exception ex){throw new ClassNotFoundException(name,ex);}return super.findClass(name);
            }
        }){
            Thread thread=Thread.currentThread();ClassLoader previous=thread.getContextClassLoader();thread.setContextClassLoader(loader);
            try{
                Class<?> link=loader.loadClass("mchorse.bbs_mod.resources.Link"),providerType=loader.loadClass("mchorse.bbs_mod.resources.AssetProvider"),sourceType=loader.loadClass("mchorse.bbs_mod.resources.ISourcePack"),pack=loader.loadClass("mchorse.bbs_mod.resources.packs.ExternalAssetsSourcePack");
                Object provider=providerType.getConstructor().newInstance();providerType.getMethod("register",sourceType).invoke(provider,pack.getConstructor(String.class,java.io.File.class).newInstance("assets",assets.toFile()));loader.loadClass("mchorse.bbs_mod.BBSMod").getField("provider").set(null,provider);
                Class<?> managerType=loader.loadClass("mchorse.bbs_mod.cubic.model.ModelManager"),parser=loader.loadClass("mchorse.bbs_mod.math.molang.MolangParser");Object manager=managerType.getConstructor().newInstance();managerType.getField("parser").set(manager,parser.getConstructor().newInstance());
                Class<?> map=loader.loadClass("mchorse.bbs_mod.data.types.MapType"),loadType=loader.loadClass("mchorse.bbs_mod.cubic.model.loaders.GeoCubicModelLoader");Object load=loadType.getConstructor().newInstance();var method=loadType.getMethod("load",String.class,managerType,link,Collection.class,map);
                int checks=0;for(var item:bindings.entrySet()){if(item.getValue().texture()==null&&item.getValue().materials().isEmpty())continue;Object folder=link.getMethod("assets",String.class).invoke(null,"models/"+item.getKey());Object listing=providerType.getMethod("getLinksFromPath",link,boolean.class).invoke(provider,folder,false);Object instance=method.invoke(load,item.getKey(),manager,folder,listing,map.getConstructor().newInstance());if(instance==null)throw new AssertionError("BBS CPU loader returned null: "+item.getKey());if(item.getValue().texture()!=null){Object finalLink=instance.getClass().getField("baseTexture").get(instance);String path=(String)link.getField("path").get(finalLink);ImporterAcceptance.check(path.equals(item.getValue().texture()),"real BBS loader after applyConfig retains final default Link");try(var in=(java.io.InputStream)providerType.getMethod("getAsset",link).invoke(provider,finalLink)){ImporterAcceptance.check(Arrays.equals(in.readAllBytes(),Files.readAllBytes(assets.resolve(path))),"real BBS loaded instance Link reads exact PNG");}}@SuppressWarnings("unchecked") Map<String,Object> actualMaterials=(Map<String,Object>)instance.getClass().getField("materialTextures").get(instance);for(var material:item.getValue().materials().entrySet()){Object finalLink=actualMaterials.get(material.getKey());ImporterAcceptance.check(finalLink!=null&&link.getField("path").get(finalLink).equals(material.getValue()),"real BBS applyConfig retains final material Link");try(var in=(java.io.InputStream)providerType.getMethod("getAsset",link).invoke(provider,finalLink)){ImporterAcceptance.check(Arrays.equals(in.readAllBytes(),Files.readAllBytes(assets.resolve(material.getValue()))),"real BBS material Link reads exact PNG");}}checks++;}
                ImporterAcceptance.check(checks>0,"real BBS CPU loader exercised");
            }finally{thread.setContextClassLoader(previous);}
        }
    }
    static void replay(Path config)throws Exception{var data=ImporterAcceptance.json(config.resolve("bbs/model-import-index.json"));SharedModelAssets.bootstrap(data,config.resolve("bbs/assets/models"));var bindings=SharedModelAssets.bindingsForTest();run(config.resolve("bbs/assets"),bindings.entrySet().stream().filter(e->!e.getKey().contains("/skins/")).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,Map.Entry::getValue)));System.out.println("PERSISTED BBS REPLAY: bindings="+bindings.size()+"; canonical="+bindings.keySet().stream().filter(k->!k.contains("/skins/")).count());}
    public static void main(String[] args)throws Exception{replay(Path.of(args[0]));}
    static byte[] empty(String name){ClassWriter w=new ClassWriter(0);w.visit(61,1,name,null,"java/lang/Object",null);var m=w.visitMethod(1,"<init>","()V",null,null);m.visitCode();m.visitVarInsn(25,0);m.visitMethodInsn(183,"java/lang/Object","<init>","()V",false);m.visitInsn(177);m.visitMaxs(1,1);m.visitEnd();w.visitEnd();return w.toByteArray();}
    static byte[] providerFacade(){ClassWriter w=new ClassWriter(0);String n="mchorse/bbs_mod/BBSMod",p="Lmchorse/bbs_mod/resources/AssetProvider;";w.visit(61,1,n,null,"java/lang/Object",null);w.visitField(9,"provider",p,null,null).visitEnd();var m=w.visitMethod(9,"getProvider","()"+p,null,null);m.visitCode();m.visitFieldInsn(178,n,"provider",p);m.visitInsn(176);m.visitMaxs(1,0);m.visitEnd();w.visitEnd();return w.toByteArray();}
    static byte[] managerFacade(){ClassNode n=new ClassNode();new ClassReader(empty("mchorse/bbs_mod/cubic/model/ModelManager")).accept(n,0);n.fields.add(new FieldNode(1,"parser","Lmchorse/bbs_mod/math/molang/MolangParser;",null,null));ClassWriter w=new ClassWriter(0);n.accept(w);return w.toByteArray();}
}
