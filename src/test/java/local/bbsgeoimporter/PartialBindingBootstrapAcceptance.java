package local.bbsgeoimporter;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Offline restart binding recovery with the real BBS CPU loader; no game/GL. */
public final class PartialBindingBootstrapAcceptance {
    public static void main(String[] args)throws Exception {
        Path bbs=Path.of(args.length==0?"E:/我的世界/.minecraft/versions/bbs1.20.1动画/mods/bbs-2.8-1.20.1.jar":args[0]);
        Path base=Files.createTempDirectory(Path.of("build"),"partial-binding-bootstrap-"),config=base.resolve("config"),assets=config.resolve("bbs/assets"),models=assets.resolve("models");
        String owner="strange_owner_a71",shared="unfamiliar_provider_f03",bodyId=shared+":assets/shared_ns/textures/body.png",eyeId=shared+":assets/shared_ns/textures/eyes.png";
        JsonObject state=new JsonObject(),mods=new JsonObject();state.add("mods",mods);JsonObject mod=mod("Novel"),provider=mod("Shared");mods.add(owner,mod);mods.add(shared,provider);
        byte[] body=png(models.resolve("Shared/body.png"),0xff123456),eye=png(models.resolve("Shared/eyes.png"),0xffabcdef);
        JsonObject bodyRecord=record("body.png",body,"texture"),eyeRecord=record("eyes.png",eye,"texture");provider.getAsJsonObject("files").add("@resource/"+bodyId,bodyRecord);provider.getAsJsonObject("files").add("@resource/"+eyeId,eyeRecord);
        for(String name:List.of("first","second")){
            String rel="assets/odd/geo/"+name+".geo.json",source=owner+":"+rel;
            byte[] geo="{\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.partial\",\"texture_width\":2,\"texture_height\":2},\"bones\":[{\"name\":\"body\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[1,1,1],\"uv\":[0,0]}]}]}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Path out=models.resolve("Novel/"+name+"/model.geo.json");Files.createDirectories(out.getParent());Files.write(out,geo);Files.writeString(out.resolveSibling("config.json"),"{}");
            JsonObject geoRecord=record(name+"/model.geo.json",geo,"geo");geoRecord.addProperty("modelSource",source);JsonObject plan=new JsonObject();plan.addProperty("schema",2);plan.addProperty("geometrySourceHash",geoRecord.get("sourceHash").getAsString());plan.addProperty("baseTexture","models/Shared/body.png");plan.add("animations",new JsonArray());
            JsonObject materials=new JsonObject();materials.addProperty("eyes","models/Shared/eyes.png");plan.add("materials",materials);JsonArray parts=new JsonArray();JsonObject part=new JsonObject();part.addProperty("bone","body");part.addProperty("cube",0);part.addProperty("slot","eyes");JsonArray faces=new JsonArray();for(String face:List.of("north","south","east","west","up","down"))faces.add(face);part.add("faces",faces);parts.add(part);plan.add("parts",parts);
            JsonObject refs=new JsonObject();refs.add(bodyId,ref(shared,bodyId,"models/Shared/body.png",bodyRecord));refs.add(eyeId,ref(shared,eyeId,"models/Shared/eyes.png",eyeRecord));plan.add("resources",refs);geoRecord.add("bindingPlan",plan);mod.getAsJsonObject("files").add(rel,geoRecord);
        }
        SharedModelAssets.bootstrap(state,models);check(SharedModelAssets.bindingsForTest().size()==2,"both consumers restore shared binding");
        String initial=SharedModelAssets.bindingsForTest().get("Novel/first").texture();check(initial!=null,"base initially available");Object token=SharedModelAssets.previewToken("Novel/first");
        JsonElement missingHash=eyeRecord.remove("writtenHash");eyeRecord.addProperty("status","user_conflict");SharedModelAssets.bootstrap(state,models);
        check(SharedModelAssets.bindingsForTest().size()==2&&SharedModelAssets.bindingsForTest().values().stream().allMatch(b->initial.equals(b.texture())&&b.materials().isEmpty()),"unpublished eye without writtenHash isolates dependency for both models");
        eyeRecord.add("writtenHash",missingHash);eyeRecord.addProperty("status","imported");
        Files.delete(models.resolve("Shared/eyes.png"));SharedModelAssets.bootstrap(state,models);
        for(String key:List.of("Novel/first","Novel/second")){var binding=SharedModelAssets.bindingsForTest().get(key);check(binding!=null&&initial.equals(binding.texture()),"one missing material does not discard another valid shared base: "+key);check(binding.materials().isEmpty(),"missing material is not restored as a false valid Link");check(!binding.parts().isEmpty(),"material selectors remain available for repair");}
        check(token!=SharedModelAssets.previewToken("Novel/first"),"partial dependency loss invalidates only changed binding token");
        BbsCpuAcceptance.run(assets,Map.copyOf(SharedModelAssets.bindingsForTest()),bbs);
        check(mod.getAsJsonObject("files").entrySet().stream().allMatch(e->e.getValue().getAsJsonObject().getAsJsonObject("bindingPlan").getAsJsonObject("materials").size()==1),"partial restore does not erase persisted complete plan");
        Files.write(models.resolve("Shared/eyes.png"),eye);SharedModelAssets.bootstrap(state,models);
        check(SharedModelAssets.bindingsForTest().values().stream().allMatch(b->b.materials().size()==1&&initial.equals(b.texture())),"same persisted schema recovers missing material after file repair without losing base");
        BbsCpuAcceptance.run(assets,Map.copyOf(SharedModelAssets.bindingsForTest()),bbs);
        Files.writeString(models.resolve("Shared/body.png"),"user changed texture");SharedModelAssets.bootstrap(state,models);
        check(SharedModelAssets.bindingsForTest().values().stream().allMatch(b->b.texture()==null&&b.materials().size()==1),"user-modified base is excluded while independent material remains valid");check(Files.readString(models.resolve("Shared/body.png")).equals("user changed texture"),"bootstrap never overwrites user output");
        System.out.println("PARTIAL BINDING BOOTSTRAP CPU PASS: two shared consumers retain verified base after material loss; repaired old schema restores material; native BBS loader final Links read exact PNG; user-changed base isolated. Offline fixture, no game/GL.");
    }
    private static JsonObject mod(String directory){JsonObject mod=new JsonObject();mod.addProperty("directory",directory);mod.add("files",new JsonObject());return mod;}
    private static byte[] png(Path path,int color)throws Exception{ImporterAcceptance.png(path,color);return Files.readAllBytes(path);}
    private static JsonObject record(String target,byte[] bytes,String type){JsonObject r=new JsonObject();r.addProperty("target",target);r.addProperty("type",type);r.addProperty("status","imported");r.addProperty("sourceHash",GeoImporter.hash(bytes));r.addProperty("writtenHash",GeoImporter.hash(bytes));return r;}
    private static JsonObject ref(String provider,String source,String link,JsonObject record){JsonObject ref=new JsonObject();ref.addProperty("provider",provider);ref.addProperty("source",source);ref.addProperty("link",link);for(String name:List.of("target","sourceHash","writtenHash","type"))ref.add(name,record.get(name));return ref;}
    private static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
}
