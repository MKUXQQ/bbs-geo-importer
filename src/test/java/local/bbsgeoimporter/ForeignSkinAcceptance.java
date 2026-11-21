package local.bbsgeoimporter;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
final class ForeignSkinAcceptance {
    static void run(Path root,Path bbs)throws Exception {
        String ns="unseen_"+UUID.randomUUID().toString().replace("-",""),model="sculptures/iris.geo.json",a="textures/pigments/cedar.png",b="textures/pigments/jade.png";
        Path pack=root.resolve("pack"),other=root.resolve("foreign"),assets=pack.resolve("assets/"+ns),config=root.resolve("config");
        String geo="{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.iris\",\"texture_width\":2,\"texture_height\":2},\"bones\":[{\"name\":\"root\",\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[0,0,0],\"size\":[1,1,1],\"uv\":[0,0]}]}]}]}";
        ImporterAcceptance.put(assets.resolve(model),geo);ImporterAcceptance.png(assets.resolve(a),0xff876530);ImporterAcceptance.png(assets.resolve(b),0xff203071);
        ImporterAcceptance.png(assets.resolve("textures/gui/cedar.png"),0xff33ee33);ImporterAcceptance.png(other.resolve("assets/other/"+a),0xffee0044);
        ImporterAcceptance.put(assets.resolve("defs/palette.json"),"{\"model\":\""+ns+":"+model+"\",\"skins\":[\""+ns+":"+b+"\",\""+ns+":"+a+"\"]}");
        ImporterAcceptance.put(assets.resolve("sculptures/lone.geo.json"),ImporterAcceptance.GEO);
        ImporterAcceptance.put(assets.resolve("defs/lone.json"),"{\"model\":\""+ns+":sculptures/lone.geo.json\",\"textures\":{\"eyes\":\""+ns+":"+a+"\"}}");
        String nested="resourcepacks/harbor/",folder="assets/"+ns+"/bedrock/anything/";
        ImporterAcceptance.put(pack.resolve(nested+"pack.mcmeta"),"{\"pack\":{\"pack_format\":15,\"description\":\"harbor\"}}");
        ImporterAcceptance.put(pack.resolve(nested+folder+"models/shape.geo.json"),geo);ImporterAcceptance.put(pack.resolve(folder+"models/shape.geo.json"),geo);
        String rule="{\"species\":\""+ns+":shape\",\"variations\":[{\"model\":\""+ns+":shape.geo\",\"texture\":\""+ns+":"+a+"\"}]}";
        ImporterAcceptance.put(pack.resolve(nested+folder+"resolvers/shape.json"),rule);
        ImporterAcceptance.put(pack.resolve(folder+"resolvers/shape.json"),rule.replace(a,b));
        ImporterAcceptance.put(pack.resolve(nested+folder+"models/shade.geo.json"),geo);
        ImporterAcceptance.put(pack.resolve(nested+folder+"resolvers/shade.json"),rule.replace("shape","shade").replace(a,b));
        ImporterAcceptance.png(pack.resolve(nested+"assets/"+ns+"/"+b),0xffa99388);
        var sources=List.of(ImporterAcceptance.source(ns,"Strangers",pack),ImporterAcceptance.source("other","Other",other));new GeoImporter(config,s->{}).scan(sources);
        var state=ImporterAcceptance.json(config.resolve("bbs/model-import-index.json"));var files=state.getAsJsonObject("mods").getAsJsonObject(ns).getAsJsonObject("files");var plan=files.getAsJsonObject("assets/"+ns+"/"+model).getAsJsonObject("bindingPlan");
        RealSkinBindingAcceptance.check(plan.get("baseSelection").getAsString().equals("DISPLAY_STABLE_SKIN")&&plan.getAsJsonArray("skins").size()==2,"no unique source default; both proven skins kept");
        RealSkinBindingAcceptance.check(Files.mismatch(assets.resolve(a),config.resolve("bbs/assets").resolve(plan.get("baseTexture").getAsString()))==-1,"real chosen same-domain PNG");
        for(String[] pair:List.of(new String[]{nested+folder+"models/shape.geo.json","assets/"+ns+"/"+a},new String[]{folder+"models/shape.geo.json","assets/"+ns+"/"+b},new String[]{nested+folder+"models/shade.geo.json",nested+"assets/"+ns+"/"+b})){
            var binding=files.getAsJsonObject(pair[0]).getAsJsonObject("bindingPlan");RealSkinBindingAcceptance.check(binding.has("baseTexture")&&Files.mismatch(pack.resolve(pair[1]),config.resolve("bbs/assets").resolve(binding.get("baseTexture").getAsString()))==-1,"nested model and resource isolation: "+pair[0]);
        }
        var report=ImporterAcceptance.json(config.resolve("bbs/model-import-report.json"));
        for(var item:report.getAsJsonArray("models")){var row=item.getAsJsonObject();if(row.get("sourcePath").getAsString().contains("lone.geo"))RealSkinBindingAcceptance.check(!row.get("baseUsable").getAsBoolean()&&!row.get("textureStatus").getAsString().equals("FULL_TEXTURES"),"unselected material never becomes base/FULL");}
        BbsCpuAcceptance.run(config.resolve("bbs/assets"),SharedModelAssets.bindingsForTest(),bbs);
        System.out.println("foreign domain: proven interchangeable skins, GUI/foreign decoys excluded, unselected material not FULL.");
    }
}
