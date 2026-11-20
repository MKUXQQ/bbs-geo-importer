package local.bbsgeoimporter;

import java.nio.*;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Bounded decode/accounting and real has(Link) side-effect regression; no game or GL. */
public final class ListTextureWarmupAcceptance {
    public static void main(String[] args)throws Exception {
        Path base=Files.createTempDirectory(Path.of("build"),"texture-warmup-1518-");Map<String,String> source=new LinkedHashMap<>();
        source.put("mchorse/bbs_mod/resources/Link","public class Link {public String source=\"assets\",path;public Link(String p){path=p;}public boolean equals(Object o){return o instanceof Link l&&source.equals(l.source)&&path.equals(l.path);}public int hashCode(){return java.util.Objects.hash(source,path);}}");
        source.put("mchorse/bbs_mod/resources/AssetProvider","public class AssetProvider {public final java.util.Map<Link,byte[]> data=new java.util.HashMap<>();public int reads;public java.io.InputStream getAsset(Link l){reads++;return data.containsKey(l)?new java.io.ByteArrayInputStream(data.get(l)):null;}public Link getLink(java.io.File f){return new Link(f.getName());}}");
        source.put("mchorse/bbs_mod/utils/resources/Pixels","public class Pixels {public static int decodes,deletes;public static String thread;public static Pixels fromPNGStream(java.io.InputStream in)throws Exception {in.readNBytes(24);decodes++;thread=Thread.currentThread().getName();return new Pixels();}public void delete(){deletes++;}}");
        source.put("mchorse/bbs_mod/graphics/texture/Texture","public class Texture {}");
        source.put("mchorse/bbs_mod/graphics/texture/TextureManager","public class TextureManager {public final java.util.Map<mchorse.bbs_mod.resources.Link,Texture> textures=new java.util.HashMap<>(),animatedTextures=new java.util.HashMap<>();private final java.util.Set<mchorse.bbs_mod.resources.Link> failed=new java.util.HashSet<>();public final mchorse.bbs_mod.resources.AssetProvider provider=new mchorse.bbs_mod.resources.AssetProvider();public int calls,synchronousDecodes;private final Texture error=new Texture();public Texture getError(){return error;}public boolean has(mchorse.bbs_mod.resources.Link l){throw new AssertionError();}public Texture getTexture(mchorse.bbs_mod.resources.Link l){calls++;Object p=local.bbsgeoimporter.ListTextureWarmup.take(this,l);if(p==null){synchronousDecodes++;throw new AssertionError(\"probe triggered cold native decode\");}try{p.getClass().getMethod(\"delete\").invoke(p);}catch(Exception e){throw new RuntimeException(e);}Texture t=new Texture();textures.put(l,t);return t;}}");
        source.put("mchorse/bbs_mod/BBSModClient","public class BBSModClient {public static final mchorse.bbs_mod.graphics.texture.TextureManager textures=new mchorse.bbs_mod.graphics.texture.TextureManager();public static mchorse.bbs_mod.graphics.texture.TextureManager getTextures(){return textures;}}");
        source.put("mchorse/bbs_mod/cubic/ModelInstance","public class ModelInstance {public mchorse.bbs_mod.resources.Link base;public final java.util.Map<String,mchorse.bbs_mod.resources.Link> materialTextures=new java.util.HashMap<>();public mchorse.bbs_mod.resources.Link getTexture(){return base;}}");
        source.put("mchorse/bbs_mod/settings/values/core/ValueLink","public class ValueLink {public Object v;public Object get(){return v;}}");
        source.put("mchorse/bbs_mod/forms/forms/ModelForm","public class ModelForm {public final mchorse.bbs_mod.settings.values.core.ValueLink texture=new mchorse.bbs_mod.settings.values.core.ValueLink();}");
        List<String> javac=new ArrayList<>(List.of("-proc:none","-encoding","UTF-8","-cp",System.getProperty("java.class.path"),"-d",base.toString()));
        for(var e:source.entrySet()){Path file=base.resolve(e.getKey()+".java");Files.createDirectories(file.getParent());int slash=e.getKey().lastIndexOf('/');Files.writeString(file,"package "+e.getKey().substring(0,slash).replace('/','.')+";"+e.getValue());javac.add(file.toString());}
        check(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,javac.toArray(String[]::new))==0,"warmup CPU seams compile");
        String managerName="mchorse/bbs_mod/graphics/texture/TextureManager";
        // Keep the actual BBS has method, so a future accidental 'has' probe calls getTexture and fails this test.
        try(JarFile jar=new JarFile("E:/我的世界/.minecraft/versions/bbs1.20.1动画/mods/bbs-2.7-1.20.1.jar")){
            ClassNode nativeManager=new ClassNode();new ClassReader(jar.getInputStream(jar.getJarEntry(managerName+".class"))).accept(nativeManager,0);
            MethodNode has=nativeManager.methods.stream().filter(m->m.name.equals("has")&&m.desc.equals("(Lmchorse/bbs_mod/resources/Link;)Z")).findFirst().orElseThrow();
            check(Arrays.stream(has.instructions.toArray()).anyMatch(n->n instanceof MethodInsnNode call&&call.name.equals("getTexture")),"actual BBS has is an IO/GL operation");
            Path file=base.resolve(managerName+".class");ClassNode facade=new ClassNode();new ClassReader(Files.readAllBytes(file)).accept(facade,0);facade.methods.removeIf(m->m.name.equals("has"));facade.methods.add(has);ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);facade.accept(writer);Files.write(file,writer.toByteArray());
        }
        try(URLClassLoader loader=new URLClassLoader(new URL[]{base.toUri().toURL()},ListTextureWarmupAcceptance.class.getClassLoader())){
            Class<?> link=loader.loadClass("mchorse.bbs_mod.resources.Link"),type=loader.loadClass(managerName.replace('/','.')),model=loader.loadClass("mchorse.bbs_mod.cubic.ModelInstance");Object manager=loader.loadClass("mchorse.bbs_mod.BBSModClient").getMethod("getTextures").invoke(null),instance=model.getConstructor().newInstance(),form=loader.loadClass("mchorse.bbs_mod.forms.forms.ModelForm").getConstructor().newInstance();
            Object body=link.getConstructor(String.class).newInstance("body.png"),eyes=link.getConstructor(String.class).newInstance("eyes.png");model.getField("base").set(instance,body);
            @SuppressWarnings("unchecked") Map<String,Object> materials=(Map<String,Object>)model.getField("materialTextures").get(instance);materials.put("eyes",eyes);
            Object provider=type.getField("provider").get(manager);@SuppressWarnings("unchecked") Map<Object,byte[]> data=(Map<Object,byte[]>)provider.getClass().getField("data").get(provider);
            data.put(body,header(2048,2048));data.put(eyes,header(1024,1024));
            check(!ListTextureWarmup.prepare(instance,form,null,"fixture/a"),"first step queues decode");check(type.getField("calls").getInt(manager)==0,"cache probe must not call native has/getTexture");
            boolean ready=false;for(int i=0;i<500&&!ready;i++){Thread.sleep(2);ready=ListTextureWarmup.prepare(instance,form,null,"fixture/a");}
            check(ready,"16MiB body plus 4MiB eyes reaches ready without eviction ping-pong");
            Class<?> pixels=loader.loadClass("mchorse.bbs_mod.utils.resources.Pixels");check(pixels.getField("decodes").getInt(null)==2,"each shared PNG decoded once");check(type.getField("calls").getInt(manager)==2&&type.getField("synchronousDecodes").getInt(manager)==0,"two budgeted native uploads, zero synchronous cold decodes");
            check(pixels.getField("thread").get(null).equals("BBS list PNG decoder"),"decode stays off the render/client thread");
            Object second=model.getConstructor().newInstance();model.getField("base").set(second,body);check(ListTextureWarmup.prepare(second,form,null,"fixture/b"),"shared PNG consumer reuses native resident texture");check(pixels.getField("decodes").getInt(null)==2,"second consumer adds no decode");
            ListTextureWarmup.invalidateAllForTest();
        }
        for(int shiftY:new int[]{0,100,-100}){int[] rect=ListThumbnailGpu.canonical(64,64,320,240,50,shiftY);check(rect[0]-50>=0&&rect[1]-shiftY>=0&&rect[2]-50<=320&&rect[3]-shiftY<=240,"canonical capture remains inside projection clip volume after scrolling");check(rect[2]-rect[0]==64&&rect[3]-rect[1]==64,"edge cell captures full logical dimensions");}
        System.out.println("LIST TEXTURE CPU PASS: native has side effects retained; zero probe IO; 16+4MiB materials finish in two decodes/two native uploads; shared PNG reused; canonical edge crop complete. No GL/game executed.");
    }
    private static byte[] header(int width,int height){return ByteBuffer.allocate(24).putLong(0x89504e470d0a1a0aL).putInt(13).putInt(0x49484452).putInt(width).putInt(height).array();}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
