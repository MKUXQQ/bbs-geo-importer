package local.bbsgeoimporter;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

public final class FrameBindingAcceptance {
    private static final List<String> FAMILIES=List.of("0935_charcadet","0936_armarouge","0937_ceruledge","0623_golurk");
    static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
    public static void main(String[] args)throws Exception{
        Path mods=Path.of(args[0]),bbs=Path.of(args[1]),out=Files.createTempDirectory(Path.of("build"),"declared-frames-"),sample=out.resolve("sample");
        Path cob,cat;try(var ls=Files.list(mods)){var dirs=ls.filter(Files::isDirectory).toList();cob=dirs.stream().filter(p->Files.exists(p.resolve("assets/cobblemon/bedrock/pokemon/resolvers/0935_charcadet"))).findFirst().orElseThrow();
            cat=dirs.stream().filter(p->Files.exists(p.resolve("assets/cataclysm_spellbooks/geo/the_berserker.geo.json"))).findFirst().orElseThrow();}
        for(String family:FAMILIES)for(String dir:List.of("bedrock/pokemon/resolvers","bedrock/pokemon/models","textures/pokemon")){
            Path in=cob.resolve("assets/cobblemon/"+dir+"/"+family);try(var walk=Files.walk(in)){for(Path f:walk.filter(Files::isRegularFile).toList()){
                Path to=sample.resolve(cob.relativize(f));Files.createDirectories(to.getParent());Files.copy(f,to);
            }}
        }
        String ns="fresh_"+UUID.randomUUID().toString().replace("-","");Path fresh=out.resolve("novel"),resources=fresh.resolve("assets/"+ns);
        Path original=Files.walk(sample.resolve("assets/cobblemon/bedrock/pokemon/models/0935_charcadet")).filter(p->p.toString().endsWith(".geo.json")).findFirst().orElseThrow();
        String geo=Files.readString(original);
        String texture=ns+":textures/arbitrary/";for(String f:List.of("2","10","s2","s10","aux","unused"))ImporterAcceptance.png(resources.resolve("textures/arbitrary/"+f+".png"),0xff001133+f.hashCode());
        for(String k:List.of("ordered","missing","broken","plain"))ImporterAcceptance.put(resources.resolve("bedrock/novel/models/"+k+".geo.json"),geo);
        for(String k:List.of("ordered","missing","broken")){
            String first=k.equals("missing")?"absent":"2",next=k.equals("broken")?"absent":"10";
            String rule="{\"name\":\""+k+"\",\"variations\":[{\"aspects\":[],\"model\":\""+ns+":"+k+".geo\",\"texture\":{\"frames\":[\""+texture+first+".png\",\""+texture+next+".png\"],\"fps\":8},\"layers\":[{\"emissive\":true,\"texture\":{\"frames\":[\""+texture+"aux.png\",\""+texture+"ghost.png\"]}}]},{\"aspects\":[\"bright\"],\"texture\":{\"frames\":[\""+texture+"s2.png\",\""+texture+"s10.png\"]}}]}";
            ImporterAcceptance.put(resources.resolve("bedrock/novel/resolvers/"+k+".json"),rule);
        }
        ImporterAcceptance.put(resources.resolve("binding/plain.json"),"{\"model\":\""+ns+":bedrock/novel/models/plain.geo.json\",\"texture\":{\"frames\":[\""+texture+"2.png\",\""+texture+"10.png\"],\"loop\":true}}");
        String itemGeo=Files.readString(cat.resolve("assets/cataclysm_spellbooks/geo/the_berserker.geo.json"));
        for(String n:List.of("generated","bad_uv")){
            JsonObject shape=JsonParser.parseString(itemGeo).getAsJsonObject();
            if(n.equals("bad_uv")){var bones=shape.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones");for(var b:bones)if(b.getAsJsonObject().has("cubes")){b.getAsJsonObject().getAsJsonArray("cubes").get(0).getAsJsonObject().getAsJsonObject("uv").getAsJsonObject("north").add("uv",JsonParser.parseString("[999,999]"));break;}}
            ImporterAcceptance.put(resources.resolve("geo/"+n+".geo.json"),shape.toString());
            ImporterAcceptance.put(resources.resolve("models/item/"+n+".json"),"{\"parent\":\""+(n.equals("generated")?"item/generated":"builtin/entity")+"\",\"texture_size\":[64,64]}");
            Path pic=resources.resolve("textures/item/"+n+".png");Files.createDirectories(pic.getParent());Files.copy(cat.resolve("assets/cataclysm_spellbooks/textures/item/the_berserker.png"),pic);
        }
        Path config=out.resolve("config"),assets=config.resolve("bbs/assets");var sources=List.of(ImporterAcceptance.source("real","Real frames",sample),ImporterAcceptance.source("novel","Other fixtures",fresh),ImporterAcceptance.source("weapons","Real weapons",cat));
        var result=new GeoImporter(config,s->{}).scan(sources);check(result.failed()==0,"valid geo imports");
        var state=ImporterAcceptance.json(config.resolve("bbs/model-import-index.json"));var report=ImporterAcceptance.json(config.resolve("bbs/model-import-report.json"));Map<String,JsonObject> rows=new HashMap<>();
        for(var i:report.getAsJsonArray("models")){var r=i.getAsJsonObject();rows.put(r.get("model").getAsString(),r);}
        var samples=new TreeMap<String,SharedModelAssets.Binding>();
        for(String family:FAMILIES){
            String name=family.substring(5),path="assets/cobblemon/bedrock/pokemon/models/"+family+"/"+name+".geo.json",key="real:"+path;
            JsonObject files=files(state,"real"),record=files.getAsJsonObject(path),plan=record.getAsJsonObject("bindingPlan");
            finalLink(state,"real",record,cob,"assets/cobblemon/textures/pokemon/"+family+"/"+name+"_1.png",assets,samples);
            check(plan.get("baseSelection").getAsString().equals("DISPLAY_DECLARED_FIRST_FRAME"),key);
            check(!rows.get(key).get("textureStatus").getAsString().equals("FULL_TEXTURES"),"static only: "+key);
            int shiny=0;for(var item:files.entrySet()){var r=item.getValue().getAsJsonObject();if(!item.getKey().startsWith("@skin/"+key+"/"))continue;
                String source=r.get("textureSource").getAsString();if(source.contains("_shiny")){finalLink(state,"real",r,cob,source.substring(source.indexOf(':')+1),assets,samples);check(source.endsWith("_shiny_1.png"),"actual shiny first");shiny++;}}
            check(shiny==1,"one shiny first frame "+key);
            if(name.equals("charcadet")){
                check(plan.getAsJsonObject("resources").entrySet().stream().filter(e->e.getValue().getAsJsonObject().get("type").getAsString().equals("texture")).count()==17,"all 4 base + 4 shiny + 8 emissive + alpha eyes present");
            }
        }
        var nf=files(state,"novel");
        for(String name:List.of("plain","ordered","missing","broken")){
            String path="assets/"+ns+"/bedrock/novel/models/"+name+".geo.json";var r=nf.getAsJsonObject(path);var p=r.getAsJsonObject("bindingPlan");var row=rows.get("novel:"+path);
            if(name.equals("missing"))check(!p.has("baseTexture"),"missing first never uses later, overlay or shiny");
            else finalLink(state,"novel",r,fresh,"assets/"+ns+"/textures/arbitrary/2.png",assets,samples);
            check(!row.get("textureStatus").getAsString().equals("FULL_TEXTURES"),"no dynamic playback");
            if(name.equals("broken"))check(row.getAsJsonArray("textureReasons").toString().contains("absent.png")&&row.getAsJsonArray("textureReasons").toString().contains("ghost.png"),"missing body/layer frames diagnosed");
        }
        for(String n:List.of("gauntlet_of_gattling","the_berserker")){
            String path="assets/cataclysm_spellbooks/geo/"+n+".geo.json";var r=files(state,"weapons").getAsJsonObject(path);
            finalLink(state,"weapons",r,cat,"assets/cataclysm_spellbooks/textures/item/"+n+".png",assets,samples);
            check(rows.get("weapons:"+path).getAsJsonArray("textureReferences").toString().contains("parent=builtin/entity"),"evidence reported");
        }
        for(String n:List.of("generated","bad_uv"))check(!nf.getAsJsonObject("assets/"+ns+"/geo/"+n+".geo.json").getAsJsonObject("bindingPlan").has("baseTexture"),"not an item atlas: "+n);
        SharedModelAssets.bootstrap(state,assets.resolve("models"));for(var e:samples.entrySet())check(e.getValue().equals(SharedModelAssets.bindingsForTest().get(e.getKey())),"restart "+e.getKey());
        BbsCpuAcceptance.run(assets,samples,bbs);
        System.out.println("Declared frames PASS: real Charcadet, Armarouge, Ceruledge, Golurk normal/shiny first Links; all 17 Charcadet PNGs tracked. Declared [2,10] and missing first/later/layer never promote shiny/overlay; ordinary framed definition, restart, builtin/entity item atlas x2, negative icons/UV; BBS CPU final Links="+samples.size()+". Report "+config.resolve("bbs/model-import-report.json"));
        ReturnedPairAcceptance.run(out.resolve("pairs"),mods,bbs);
    }
    private static JsonObject files(JsonObject state,String id){return state.getAsJsonObject("mods").getAsJsonObject(id).getAsJsonObject("files");}
    private static void finalLink(JsonObject state,String id,JsonObject record,Path source,String expected,Path assets,Map<String,SharedModelAssets.Binding> samples)throws Exception{
        String target=record.get("target").getAsString(),key=state.getAsJsonObject("mods").getAsJsonObject(id).get("directory").getAsString()+"/"+target.substring(0,target.lastIndexOf('/'));var b=SharedModelAssets.bindingsForTest().get(key);
        check(b!=null&&b.texture()!=null&&b.texture().endsWith("/"+expected)&&Files.mismatch(source.resolve(expected),assets.resolve(b.texture()))==-1,"wrong final link for "+key+" expected "+expected+" got "+b);
        samples.put(key,b);
    }
}
