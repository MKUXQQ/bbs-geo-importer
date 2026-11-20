package local.bbsgeoimporter;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Disabled startup sources retain published scene bindings and provide dependencies to enabled owners. */
final class SourceToggleStartupAcceptance {
    static void run(Path base)throws Exception{
        Path a=base.resolve("a"),b=base.resolve("b"),cold=base.resolve("cold"),config=base.resolve("config");
        JsonObject shape=JsonParser.parseString(ImporterAcceptance.GEO).getAsJsonObject();shape.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones").add(JsonParser.parseString("{name:root,pivot:[0,0,0],cubes:[]}"));shape.addProperty("texture","shared:textures/body.png");String geo=shape.toString();
        String relA="assets/random_a/geo/a.geo.json",relB="assets/shared/geo/b.geo.json";
        ImporterAcceptance.put(a.resolve(relA),geo);ImporterAcceptance.put(b.resolve(relB),geo);
        ImporterAcceptance.png(b.resolve("assets/shared/textures/body.png"),0xff123456);
        var A=ImporterAcceptance.source("switch_a","Switch A",a);var B=ImporterAcceptance.source("switch_b","Switch B",b);
        var C=ImporterAcceptance.source("switch_cold","Cold disabled",cold);
        LiveStartupAcceptance.boot(config,List.of(A,B));
        String bkey=ImportedModelGroups.modelKeys().stream().filter(k->k.startsWith("Switch B/")).findFirst().orElseThrow();
        var oldBinding=SharedModelAssets.bindingsForTest().get(bkey);String oldPlan=ImporterAcceptance.json(config.resolve("bbs/model-import-index.json")).getAsJsonObject("mods").getAsJsonObject(B.id()).getAsJsonObject("files").getAsJsonObject(relB).getAsJsonObject("bindingPlan").toString();
        ImporterAcceptance.put(cold.resolve("assets/unseen/geo/new.geo.json"),ImporterAcceptance.GEO);
        Files.writeString(config.resolve("bbs/mod-model-management.json"),"{\"sources\":{\"switch_b\":false,\"switch_cold\":false}}");
        ModelManagement.configure(base.resolve("simulate_process_reset"));
        LiveStartupAcceptance.boot(config,List.of(A,B,C));
        ImporterAcceptance.check(ImportedModelGroups.modelKeys().stream().noneMatch(k->k.startsWith("Switch B/"))&&ImportedModelGroups.inactive(bkey)&&!ImportedModelGroups.unavailable(bkey),"disabled source hidden only from automatic list, not scene availability");
        ImporterAcceptance.check(Objects.equals(oldBinding,SharedModelAssets.bindingsForTest().get(bkey)),"disabled cached source keeps complete scene binding");
        ImporterAcceptance.check(ImportedModelGroups.sourceReady(B.id())&&!ImportedModelGroups.sourceReady(C.id())&&ModelManagement.manifest().stream().anyMatch(s->s.id().equals(C.id())&&s.geoCount()==1&&!s.ready()),"startup manifest retains disabled Geo counts and prepared/unprepared distinction");
        var state=ImporterAcceptance.json(config.resolve("bbs/model-import-index.json"));
        ImporterAcceptance.check(!state.getAsJsonObject("mods").has(C.id())&&oldPlan.equals(state.getAsJsonObject("mods").getAsJsonObject(B.id()).getAsJsonObject("files").getAsJsonObject(relB).getAsJsonObject("bindingPlan").toString()),"unprepared disabled source is not imported and existing source plan stays intact");
        // B remains a shared PNG provider to enabled A even when its model category is disabled.
        ImporterAcceptance.png(b.resolve("assets/shared/textures/body.png"),0xffabcdef);
        ModelManagement.configure(base.resolve("reset_again"));LiveStartupAcceptance.boot(config,List.of(A,B,C));
        var active=SharedModelAssets.bindingsForTest().entrySet().stream().filter(e->e.getKey().startsWith("Switch A/")).findFirst().orElseThrow();
        Path published=config.resolve("bbs/assets").resolve(active.getValue().texture());
        ImporterAcceptance.check(Arrays.equals(Files.readAllBytes(published),Files.readAllBytes(b.resolve("assets/shared/textures/body.png"))),"enabled consumer imports changed PNG from disabled provider");
        BbsCpuAcceptance.run(config.resolve("bbs/assets"),Map.of(active.getKey(),active.getValue()));
        // A geometry-only historical cache is not called ready when its old plan cannot be restored.
        state=ImporterAcceptance.json(config.resolve("bbs/model-import-index.json"));
        var records=state.getAsJsonObject("mods").getAsJsonObject(B.id()).getAsJsonObject("files");
        for(var row:records.entrySet())if(row.getValue().getAsJsonObject().has("modelSource"))row.getValue().getAsJsonObject().remove("bindingPlan");
        Files.writeString(config.resolve("bbs/model-import-index.json"),state.toString());
        ModelManagement.configure(base.resolve("reset_empty_plan"));LiveStartupAcceptance.boot(config,List.of(A,B,C));
        ImporterAcceptance.check(!ImportedModelGroups.sourceReady(B.id()),"disabled historical geometry with no reusable plan requires next startup load");
        System.out.println("SOURCE TOGGLE STARTUP PASS: cached scene plans retained, disabled unprepared owner skipped, shared provider PNG and actual BBS Link verified");
    }
}
