package local.bbsgeoimporter;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

final class MaterialAcceptance {
    static void run(Path base)throws Exception {
        Path root=base.resolve("source"),provider=base.resolve("provider"),collision=base.resolve("other-provider");
        ImporterAcceptance.png(root.resolve("assets/x/textures/body.png"),0xff112233);
        ImporterAcceptance.png(root.resolve("assets/x/textures/eyes.png"),0xffabcdef);
        ImporterAcceptance.png(provider.resolve("assets/cross_space/textures/shared.png"),0xff223344);ImporterAcceptance.png(collision.resolve("assets/unrelated/textures/shared.png"),0xff556677);
        String fictional="zz_unseen_7f3a91";ImporterAcceptance.put(root.resolve("assets/odd_folder/strange/creature.geo.json"),"{'minecraft:geometry':[{'description':{'identifier':'geometry.odd.creature','texture_width':16,'texture_height':16},'bones':[{'name':'body','cubes':[{'origin':[0,0,0],'size':[1,1,1],'uv':{'north':{'uv':[0,0],'uv_size':[1,1],'material_instance':'body'},'south':{'uv':[0,0],'uv_size':[1,1],'material_instance':'eyes'}}}]}]}],'textures':{'body':'cross_space:textures/shared','eyes':'x:textures/eyes'}}");
        var sources=List.of(ImporterAcceptance.source(fictional,"Unseen Material",root),ImporterAcceptance.source("provider_alpha_61","Provider A",provider),ImporterAcceptance.source("provider_beta_92","Provider B",collision));var catalog=new ResourceCatalog(sources);
        String key=fictional+":assets/odd_folder/strange/creature.geo.json";MaterialPlan p=MaterialPlan.read(catalog,key);
        ImporterAcceptance.check(p.confirmedSlots().size()==2&&p.parts.size()==2,"explicit faces prove two simultaneous material slots: slots="+p.slots+", parts="+p.parts+", unsupported="+p.unsupported+", requirements="+catalog.requirements);
        var importer=new GeoImporter(base.resolve("config"),s->{});var first=importer.scan(sources);
        var binding=SharedModelAssets.bindingsForTest().values().iterator().next();
        ImporterAcceptance.check(binding.materials().size()==2&&binding.parts().size()==2,"published BBS binding preserves both material links");
        var state=ImporterAcceptance.json(base.resolve("config/bbs/model-import-index.json"));var mod=state.getAsJsonObject("mods").getAsJsonObject(fictional);
        Path output=base.resolve("config/bbs/assets/models").resolve(mod.get("directory").getAsString()).resolve(mod.getAsJsonObject("files").getAsJsonObject("assets/odd_folder/strange/creature.geo.json").get("target").getAsString());
        var cubes=ImporterAcceptance.json(output).getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones").get(0).getAsJsonObject().getAsJsonArray("cubes");
        ImporterAcceptance.check(cubes.size()==2&&cubes.get(0).getAsJsonObject().getAsJsonObject("uv").size()==1,"face split keeps one visible face per derived cube");
        var fixture=new ModelFixture();SharedModelAssets.applyParts(fixture,binding.parts());
        ImporterAcceptance.check(fixture.regenerated&&fixture.group.cubes.get(0).back==null&&fixture.group.cubes.get(1).front==null,"runtime removes omitted faces before regenerating quads");
        var geoRecord=mod.getAsJsonObject("files").getAsJsonObject("assets/odd_folder/strange/creature.geo.json");ImporterAcceptance.check(geoRecord.has("bindingPlan")&&geoRecord.getAsJsonObject("bindingPlan").getAsJsonObject("materials").size()==2,"per-model binding plan is committed with provider links and both material slots");ImporterAcceptance.check(geoRecord.getAsJsonObject("bindingPlan").getAsJsonObject("materials").get("body").getAsString().contains("Provider A")&&!geoRecord.getAsJsonObject("bindingPlan").getAsJsonObject("materials").get("body").getAsString().contains("Provider B"),"cross-namespace duplicate basename binds only the explicit namespace provider");
        var restarted=new GeoImporter(base.resolve("config"),s->{});JsonObject restartState=ImporterAcceptance.json(base.resolve("config/bbs/model-import-index.json"));restarted.bootstrap(sources);var restored=SharedModelAssets.bindingsForTest().values().stream().filter(x->x.materials().size()==2).findFirst().orElseThrow(()->new AssertionError("material binding absent after restart bootstrap"));ImporterAcceptance.check(restored.materials().equals(binding.materials())&&restored.parts().equals(binding.parts()),"restart restores complete material links and face parts from validated persistent plan without resource catalog");
        ModelInstanceFixture runtime=new ModelInstanceFixture();String bbsKey=SharedModelAssets.bindingsForTest().entrySet().stream().filter(e->e.getValue().materials().size()==2).map(Map.Entry::getKey).findFirst().orElseThrow();SharedModelAssets.apply(bbsKey,runtime);String bodyLink=restored.materials().get("body");var linkPath=base.resolve("config/bbs/assets/models").resolve(bodyLink.substring("models/".length()));byte[] pngBytes=Files.readAllBytes(linkPath);ImporterAcceptance.check(runtime.materialTextures.get("body").value.equals(bodyLink)&&GeoImporter.hash(pngBytes).equals(geoRecord.getAsJsonObject("bindingPlan").getAsJsonObject("resources").get("provider_alpha_61:assets/cross_space/textures/shared.png").getAsJsonObject().get("writtenHash").getAsString()),"BBS apply bridge leaves final material Link pointing to the exact decoded provider PNG bytes");
        BbsCpuAcceptance.run(base.resolve("config/bbs/assets"),SharedModelAssets.bindingsForTest());
        JsonObject legacy=restartState.deepCopy();legacy.getAsJsonObject("mods").getAsJsonObject(fictional).getAsJsonObject("files").getAsJsonObject("assets/odd_folder/strange/creature.geo.json").remove("bindingPlan");SharedModelAssets.bootstrap(legacy,base.resolve("config/bbs/assets/models"));ImporterAcceptance.check(SharedModelAssets.bindingsForTest().isEmpty(),"legacy index without binding evidence does not guess a texture during startup migration");
        var repeat=importer.scan(sources);ImporterAcceptance.check(first.failed()==0&&repeat.updated()==0&&repeat.skipped()==1,"derived material geometry has stable incremental hash");
        var report=ImporterAcceptance.json(base.resolve("config/bbs/model-import-report.json"));
        var row=report.getAsJsonArray("models").get(0).getAsJsonObject();
        ImporterAcceptance.check(row.get("textureStatus").getAsString().equals("FULL_TEXTURES")&&row.get("textureSemantics").getAsString().equals("SIMULTANEOUS_MATERIALS")&&row.getAsJsonArray("materialParts").size()==2,"diagnostics prove complete simultaneous materials");
        ImporterAcceptance.check(report.getAsJsonObject("textureStatusCounts").size()==4&&report.getAsJsonArray("modelsWithoutFullTextures").isEmpty(),"diagnostic totals include every outcome and complete failure list");
        Files.writeString(output,"user model");importer.scan(sources);ImporterAcceptance.check(Files.readString(output).equals("user model"),"material regeneration preserves edited user model");
    }
    public static final class ModelFixture {public GroupFixture group=new GroupFixture();public boolean regenerated;public GroupFixture getGroup(String name){return group;}public void regenerateQuads(){regenerated=true;}}
    public static final class ModelInstanceFixture {public LinkFixture baseTexture;public ModelFixture model=new ModelFixture();public Map<String,LinkFixture> materialTextures=new HashMap<>();public List<String> materials=new ArrayList<>();}
    public static final class LinkFixture {public final String value;private LinkFixture(String value){this.value=value;}public static LinkFixture assets(String value){return new LinkFixture(value);}}
    public static final class GroupFixture {public List<CubeFixture> cubes=List.of(new CubeFixture(),new CubeFixture());}
    public static final class CubeFixture {public String material;public Object front=new Object(),right=new Object(),back=new Object(),left=new Object(),top=new Object(),bottom=new Object();}
}
