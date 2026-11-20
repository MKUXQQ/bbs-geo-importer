package local.bbsgeoimporter;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;

/** Compiled, never loaded third-party fixtures exercise constructor, enum and branch correlation. */
final class AssociationAcceptance {
    static void run(Path base)throws Exception{
        Path root=base.resolve("source"),java=base.resolve("java");Files.createDirectories(root);
        Map<String,String> sources=Map.of(
            "net/minecraft/resources/ResourceLocation.java","package net.minecraft.resources; public class ResourceLocation {public ResourceLocation(String a,String b){}}",
            "fixture/NovelModel.java","""
                package fixture; import net.minecraft.resources.ResourceLocation;
                public class NovelModel {
                  enum Choice { A("q71"),B("r92"); final String id; Choice(String id){this.id=id;} }
                  static class Entity {Choice choose(){throw new Error();} boolean other(){throw new Error();}}
                  public ResourceLocation getModelResource(Entity e){return new ResourceLocation("novel","geo/"+e.choose().id+".geo.json");}
                  public ResourceLocation getTextureResource(Entity e){return new ResourceLocation("novel","pixels/"+e.choose().id+".png");}
                }
                """,
            "fixture/ConditionalModel.java","""
                package fixture; import net.minecraft.resources.ResourceLocation;
                public class ConditionalModel {
                  public ResourceLocation getModelResource(NovelModel.Entity e){return new ResourceLocation("novel",e.other()?"geo/q71.geo.json":"geo/r92.geo.json");}
                  public ResourceLocation getTextureResource(NovelModel.Entity e){return new ResourceLocation("novel",e.other()?"pixels/q71.png":"pixels/r92.png");}
                }
                """,
            "fixture/RegistryModel.java","""
                package fixture; import net.minecraft.resources.ResourceLocation;
                public class RegistryModel {
                  static class Row {final String id;Row(String s){id=s;}String id(){return id;}}
                  static final Row A=new Row("s83"),B=new Row("t44");
                  static class Entity {Row pick(){throw new Error();}}
                  public ResourceLocation getModelResource(Entity e){return new ResourceLocation("novel","geo/"+e.pick().id()+".geo.json");}
                  public ResourceLocation getTextureResource(Entity e){return new ResourceLocation("novel","pixels/"+e.pick().id()+".png");}
                }
                """);
        List<String> args=new ArrayList<>(List.of("-proc:none","--release","17","-d",root.toString()));
        for(var item:sources.entrySet()){Path file=java.resolve(item.getKey());ImporterAcceptance.put(file,item.getValue());args.add(file.toString());}
        ImporterAcceptance.check(ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new))==0,"compile unknown static association fixtures");
        String shape=ImporterAcceptance.GEO.replace("\"bones\":[]","\"bones\":[{\"name\":\"body\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[1,1,1],\"uv\":[0,0]}]}]");
        for(String id:List.of("q71","r92","s83","t44")){ImporterAcceptance.put(root.resolve("assets/novel/geo/"+id+".geo.json"),shape);ImporterAcceptance.png(root.resolve("assets/novel/pixels/"+id+".png"),0xff123456);}
        ImporterAcceptance.put(root.resolve("assets/novel/bedrock/fruit/z23.geo.json"),shape);ImporterAcceptance.png(root.resolve("assets/novel/textures/crops/p81.png"),0xffabcdef);
        ImporterAcceptance.put(root.resolve("assets/novel/atlases/crop.json"),"{'sources':[{'type':'directory','source':'crops','prefix':''}]}");
        ImporterAcceptance.put(root.resolve("data/novel/plants/odd.json"),"{'fruitModel':'novel:z23.geo','fruitTexture':'novel:p81'}");
        String owner="unseen_"+UUID.randomUUID().toString().replace("-","");var mods=List.of(ImporterAcceptance.source(owner,"Novel",root));var catalog=new ResourceCatalog(mods);
        for(String id:List.of("q71","r92")){var edges=catalog.bundles.get(owner+":assets/novel/geo/"+id+".geo.json");ImporterAcceptance.check(!edges.isEmpty()&&edges.stream().allMatch(e->e.resource().endsWith("/"+id+".png")),"enum and matching condition paths pair resources without Cartesian products: "+id);}
        ImporterAcceptance.check(catalog.bundles.get(owner+":assets/novel/bedrock/fruit/z23.geo.json").stream().anyMatch(e->e.resource().endsWith("/crops/p81.png")),"paired properties use declared atlas directory with unrelated filenames");
        ImporterAcceptance.check(catalog.bundles.get(owner+":assets/novel/geo/s83.geo.json").stream().anyMatch(e->e.evidence().contains("RegistryModel.class")),"registered receiver instances preserve field/getter pairing without a Mod adapter");
        Path orphan=root.resolve("assets/novel/geo/unregistered.geo.json");ImporterAcceptance.put(orphan,shape);ImporterAcceptance.png(root.resolve("assets/novel/pixels/unregistered.png"),0xff111111);
        var orphanCatalog=new ResourceCatalog(mods);ImporterAcceptance.check(orphanCatalog.bundles.get(owner+":assets/novel/geo/unregistered.geo.json").isEmpty(),"a dynamic path does not infer unrelated same-namespace Geo or PNG");Files.delete(orphan);
        Path config=base.resolve("config");new GeoImporter(config,s->{}).scan(mods);var before=Map.copyOf(SharedModelAssets.bindingsForTest());
        ImporterAcceptance.check(before.values().stream().allMatch(b->b.texture()!=null),"all proven fixture plans reach final default or skin binding");
        new GeoImporter(config,s->{}).bootstrap(mods);ImporterAcceptance.check(before.equals(SharedModelAssets.bindingsForTest()),"restart restores full enum/atlas plans before association");
        BbsCpuAcceptance.run(config.resolve("bbs/assets"),before);
        var state=ImporterAcceptance.json(config.resolve("bbs/model-import-index.json"));Path indexed=ImporterAcceptance.target(config,owner,"assets/novel/geo/q71.geo.json");
        var saveRecord=GeoImporter.class.getDeclaredMethod("saveRecord",com.google.gson.JsonObject.class,String.class,String.class);saveRecord.setAccessible(true);saveRecord.invoke(new GeoImporter(config,s->{}),state,owner,"assets/novel/geo/q71.geo.json");
        var without=state.deepCopy();without.getAsJsonObject("mods").getAsJsonObject(owner).getAsJsonObject("files").remove("assets/novel/geo/q71.geo.json");Files.writeString(config.resolve("bbs/model-import-index.json"),without.toString());
        new GeoImporter(config,s->{}).bootstrap(mods);ImporterAcceptance.check(Files.exists(indexed)&&ImporterAcceptance.target(config,owner,"assets/novel/geo/q71.geo.json").equals(indexed),"ownership journal replays a published file omitted by interrupted index checkpoint");
        // Real BBS Link -> AssetProvider -> external source pack reads the actual emitted PNG.
        Path bbs=Path.of("E:/我的世界/.minecraft/versions/bbs1.20.1动画/mods/bbs-2.7-1.20.1.jar");
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{bbs.toUri().toURL()},AssociationAcceptance.class.getClassLoader())){
            Class<?> link=loader.loadClass("mchorse.bbs_mod.resources.Link"),providerClass=loader.loadClass("mchorse.bbs_mod.resources.AssetProvider"),pack=loader.loadClass("mchorse.bbs_mod.resources.packs.ExternalAssetsSourcePack"),sourcePack=loader.loadClass("mchorse.bbs_mod.resources.ISourcePack");
            Object provider=providerClass.getConstructor().newInstance(),source=pack.getConstructor(String.class,java.io.File.class).newInstance("assets",config.resolve("bbs/assets").toFile());providerClass.getMethod("register",sourcePack).invoke(provider,source);
            int checked=0;for(var binding:before.values()){Object address=link.getMethod("assets",String.class).invoke(null,binding.texture());try(var stream=(java.io.InputStream)providerClass.getMethod("getAsset",link).invoke(provider,address)){byte[] actual=stream.readAllBytes(),expected=Files.readAllBytes(config.resolve("bbs/assets").resolve(binding.texture()));ImporterAcceptance.check(Arrays.equals(actual,expected)&&javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(actual))!=null,"real BBS Link/provider reads exact decodable PNG");checked++;}}
            ImporterAcceptance.check(checked==before.size(),"every fixture final binding read through real BBS provider");
        }
    }
}
