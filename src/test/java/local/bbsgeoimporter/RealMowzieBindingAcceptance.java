package local.bbsgeoimporter;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Real 1.8.2 source bytes + historical external plan replay into BBS 2.8; offline, not loaded-mod evidence. */
public final class RealMowzieBindingAcceptance {
    public static void main(String[] args)throws Exception {
        Path source=Path.of(args.length>0?args[0]:"E:/我的世界/.minecraft/versions/旧的铁魔法1.20.1的/mods/[Mowzie的生物] mowziesmobs-1.8.2.jar"),bbs=Path.of(args.length>1?args[1]:"E:/我的世界/.minecraft/versions/bbs1.20.1动画/mods/bbs-2.8-1.20.1.jar");
        Path historical=Path.of(args.length>2?args[2]:"build/external-domain-4105394956799519171/mowzie/bbs/model-import-index.json");
        String id="external_mowzie_fixture",geoPath="assets/mowziesmobs/geo/umvuthi.geo.json",prefix="com/bobmowzie/mowziesmobs/client/";
        JsonObject old=ImporterAcceptance.json(historical),oldMod=old.getAsJsonObject("mods").getAsJsonObject(id),geo=oldMod.getAsJsonObject("files").getAsJsonObject(geoPath).deepCopy(),plan=geo.getAsJsonObject("bindingPlan");
        JsonObject state=new JsonObject(),mods=new JsonObject(),mod=new JsonObject(),files=new JsonObject();state.add("mods",mods);mods.add(id,mod);mod.addProperty("directory",oldMod.get("directory").getAsString());mod.add("files",files);files.add(geoPath,geo);
        Path base=Files.createTempDirectory(Path.of("build"),"real-mowzie-bindings-"),assets=base.resolve("assets"),models=assets.resolve("models");String directory=mod.get("directory").getAsString(),key=directory+"/"+geo.get("target").getAsString().substring(0,geo.get("target").getAsString().lastIndexOf('/'));
        String animation=null;byte[] originalPng=null;
        try(JarFile jar=new JarFile(source.toFile())){
            ClassNode model=read(jar,prefix+"model/entity/ModelUmvuthi");check(exactResourceGetter(model,"getModelResource","mowziesmobs","geo/umvuthi.geo.json"),"real ModelUmvuthi getter proves exact Geo");check(exactResourceGetter(model,"getTextureResource","mowziesmobs","textures/entity/umvuthi.png"),"same real model getter proves PNG pairing");
            publish(models.resolve(directory).resolve(geo.get("target").getAsString()),bytes(jar,geoPath),geo);Files.writeString(models.resolve(directory).resolve(geo.get("target").getAsString()).resolveSibling("config.json"),"{}");
            for(var entry:plan.getAsJsonObject("resources").entrySet()){
                JsonObject ref=entry.getValue().getAsJsonObject(),record=oldMod.getAsJsonObject("files").getAsJsonObject("@resource/"+entry.getKey()).deepCopy();files.add("@resource/"+entry.getKey(),record);
                byte[] data=bytes(jar,entry.getKey().substring(entry.getKey().indexOf(':')+1));Path out=models.resolve(directory).resolve(record.get("target").getAsString());publish(out,data,record);
                if(record.get("type").getAsString().equals("texture"))originalPng=data;else if(record.get("type").getAsString().equals("animation"))animation=ref.get("link").getAsString();
            }
            remaining(jar,prefix);
        }
        check(originalPng!=null&&animation!=null,"actual source includes paired PNG and animation");String expected=plan.get("baseTexture").getAsString();
        SharedModelAssets.bootstrap(state,models);check(expected.equals(SharedModelAssets.bindingsForTest().get(key).texture()),"actual offline Umvuthi restored exact base");
        BbsCpuAcceptance.run(assets,Map.of(key,SharedModelAssets.bindingsForTest().get(key)),bbs);
        Files.delete(assets.resolve(animation));SharedModelAssets.bootstrap(state,models);var recovered=SharedModelAssets.bindingsForTest().get(key);
        check(recovered!=null&&expected.equals(recovered.texture())&&recovered.animations().isEmpty(),"real base survives independently missing animation instead of becoming wholly white");
        check(Arrays.equals(originalPng,Files.readAllBytes(assets.resolve(recovered.texture()))),"published real PNG equals original JAR bytes");
        BbsCpuAcceptance.run(assets,Map.of(key,recovered),bbs);
        check(plan.getAsJsonArray("layerDeclarations").size()==3,"unsupported special layers retained, never counted FULL");
        System.out.println("REAL MOWZIE BINDING CPU PASS: 1.8.2 ModelUmvuthi Geo -> explicit getter PNG -> owned output -> BBS final base Link, original PNG SHA256="+GeoImporter.hash(originalPng)+"; deleting optional animation retains base. Historical EXTERNAL plan + actual other-instance JAR replay, not current loaded containers or game rendering; 8 NO limitations audited; 3 special layers remain unsupported.");
    }
    private static void remaining(JarFile jar,String prefix)throws Exception {
        ClassNode sling=read(jar,prefix+"model/entity/ModelRockSling");
        check(method(sling,"getTextureResource").stream().anyMatch(m->Arrays.stream(m.instructions.toArray()).anyMatch(n->n instanceof FieldInsnNode field&&field.name.equals("texMap"))),"RockSling texture depends on entity BlockState-keyed map");
        check(sling.methods.stream().filter(m->m.name.equals("<clinit>")).flatMap(m->Arrays.stream(m.instructions.toArray())).anyMatch(n->n instanceof LdcInsnNode literal&&String.valueOf(literal.cst).contains("textures/block/")),"RockSling fallback references Minecraft block PNG");
        ClassNode pillar=read(jar,prefix+"model/entity/ModelPillar"),pillarRenderer=read(jar,prefix+"render/entity/RenderPillar");
        check(method(pillar,"getTextureResource").stream().allMatch(m->Arrays.stream(m.instructions.toArray()).anyMatch(n->n.getOpcode()==Opcodes.ACONST_NULL)),"Pillar model texture getter deliberately null");
        check(pillarRenderer.methods.stream().filter(m->m.name.equals("<clinit>")).flatMap(m->Arrays.stream(m.instructions.toArray())).anyMatch(n->n instanceof LdcInsnNode literal&&literal.cst.equals("textures/block/dirt.png")),"Pillar renderer explicitly references Minecraft dirt PNG");
        check(read(jar,prefix+"model/entity/ModelBoulder").superName.equals("com/ilexiconn/llibrary/client/model/tools/AdvancedModelBase"),"boulder live renderer uses Java LLibrary/block models");
        ClassNode gong=read(jar,prefix+"render/block/GongRenderer");check(gong.fields.stream().anyMatch(f->f.desc.equals("Lnet/minecraft/client/model/geom/ModelPart;")),"gong live renderer is Java ModelPart, not Geo");
        Set<String> unused=new HashSet<>(Set.of("geo/boulder_1.geo.json","geo/boulder_2.geo.json","geo/boulder_3.geo.json","geo/gong.geo.json"));
        for(var entry:Collections.list(jar.entries()))if(entry.getName().endsWith(".class")){ClassNode node=read(jar,entry.getName().substring(0,entry.getName().length()-6));for(MethodNode m:node.methods)for(AbstractInsnNode n:m.instructions)if(n instanceof LdcInsnNode literal&&literal.cst instanceof String text)check(unused.stream().noneMatch(path->text.equals(path)||text.equals("mowziesmobs:"+path)),"Geo has no exact constant consumer (constructed references remain unproven): "+text);}
        for(String name:List.of("GeckoRenderPlayer","GeckoFirstPersonRenderer")){
            ClassNode renderer=read(jar,prefix+"render/entity/player/"+name);check(method(renderer,"getTextureLocation").stream().flatMap(m->Arrays.stream(m.instructions.toArray())).anyMatch(n->n instanceof MethodInsnNode call&&(call.owner.equals("net/minecraft/client/player/AbstractClientPlayer")&&call.name.equals("m_108560_")||call.desc.equals("(Lnet/minecraft/client/player/AbstractClientPlayer;)Lnet/minecraft/resources/ResourceLocation;"))),name+" PNG is authenticated/dynamic client skin, not a JAR texture");
        }
        System.out.println("REAL MOWZIE NO audit: player Geo x2 -> AbstractClientPlayer.m_108560_ skin; boulder Geo x3/gong Geo -> no exact class literal consumers, actual renderers Java LLibrary/ModelPart; rock_sling -> BlockState texMap/Minecraft block PNG; pillar -> null model getter, renderer Minecraft dirt PNG unavailable in external source domain. No filename texture guess added.");
    }
    private static List<MethodNode> method(ClassNode n,String name){return n.methods.stream().filter(m->m.name.equals(name)&&(m.access&Opcodes.ACC_BRIDGE)==0).toList();}
    private static boolean exactResourceGetter(ClassNode n,String method,String namespace,String path){return method(n,method).stream().anyMatch(m->{List<String> values=new ArrayList<>();for(AbstractInsnNode instruction:m.instructions)if(instruction instanceof LdcInsnNode literal&&literal.cst instanceof String value)values.add(value);return values.equals(List.of(namespace,path))&&Arrays.stream(m.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode c&&c.owner.equals("net/minecraft/resources/ResourceLocation")&&c.name.equals("<init>")&&c.desc.equals("(Ljava/lang/String;Ljava/lang/String;)V"));});}
    private static void publish(Path target,byte[] data,JsonObject record)throws Exception{check(GeoImporter.hash(data).equals(record.get("writtenHash").getAsString()),"historical evidence matches actual original source bytes: "+target);Files.createDirectories(target.getParent());Files.write(target,data);}
    private static byte[] bytes(JarFile jar,String name)throws Exception{var entry=jar.getJarEntry(name);check(entry!=null,"actual resource exists: "+name);try(var in=jar.getInputStream(entry)){return in.readAllBytes();}}
    private static ClassNode read(JarFile jar,String name)throws Exception{ClassNode node=new ClassNode();new ClassReader(bytes(jar,name+".class")).accept(node,0);return node;}
    private static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
}
