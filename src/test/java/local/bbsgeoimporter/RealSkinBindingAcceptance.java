package local.bbsgeoimporter;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Upgrade a copy of the live 1.5.21 owned outputs, using the original extracted pack read-only. */
public final class RealSkinBindingAcceptance {
    static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
    public static void main(String[] args)throws Exception {
        Path instance=Path.of(args[0]),live=instance.resolve("config/bbs"),source=instance.resolve("mods/hazennstuff-1.4.0.14"),
            out=Files.createTempDirectory(Path.of("build"),"skin-upgrade-"),bbs=out.resolve("bbs"),assets=bbs.resolve("assets");
        Files.createDirectories(assets);
        JsonObject baseline=ImporterAcceptance.json(live.resolve("model-import-index.json"));
        var entry=baseline.getAsJsonObject("mods").entrySet().stream().filter(e->e.getValue().getAsJsonObject().has("containerPath")&&e.getValue().getAsJsonObject().get("containerPath").getAsString().contains("/hazennstuff-")).findFirst().orElseThrow();
        var mod=entry.getValue().getAsJsonObject();String dir=mod.get("directory").getAsString(),id=entry.getKey();
        var s=new GeoImporter.ModSource(id,mod.get("name").getAsString(),mod.get("version").getAsString(),List.of(source),"EXTERNAL_DIRECTORY","hazennstuff",mod.get("containerPath").getAsString(),mod.get("resourceDomain").getAsString());
        Path folder=live.resolve("assets/models").resolve(dir);
        try(var walk=Files.walk(folder)){for(Path path:walk.filter(Files::isRegularFile).toList()){
            Path copy=assets.resolve("models").resolve(dir).resolve(folder.relativize(path));Files.createDirectories(copy.getParent());Files.copy(path,copy);
        }}
        for(String f:List.of("model-import-index.json","parsed-resource-cache.json"))Files.copy(live.resolve(f),bbs.resolve(f));
        String moth="assets/hazennstuff/geo/armor/mothic_witch_armor.geo.json";
        SharedModelAssets.bootstrap(baseline,assets.resolve("models"));
        var old=new TreeMap<>(SharedModelAssets.bindingsForTest());System.out.println("upgrade before "+coverage(old));
        var importer=new GeoImporter(out,System.out::println);var result=importer.scan(List.of(s));check(result.failed()==0&&result.discovered()==198,"198 current primary Geo");
        var next=SharedModelAssets.bindingsForTest();
        for(var oldKey:old.entrySet())if(oldKey.getValue().texture()!=null)check(next.containsKey(oldKey.getKey())&&oldKey.getValue().texture().equals(next.get(oldKey.getKey()).texture()),"lost or changed existing base: "+oldKey.getKey());
        var state=ImporterAcceptance.json(bbs.resolve("model-import-index.json"));var files=state.getAsJsonObject("mods").getAsJsonObject(id).getAsJsonObject("files");
        var proof=new TreeMap<String,SharedModelAssets.Binding>();int variants=0;
        for(var e:files.entrySet()){
            String path=e.getKey();var r=e.getValue().getAsJsonObject();if(!r.has("modelSource")||r.has("inactiveReason"))continue;
            String target=r.get("target").getAsString(),key=dir+"/"+target.substring(0,target.lastIndexOf('/'));
            var b=next.get(key);check(b!=null,"binding record missing "+key);var plan=r.getAsJsonObject("bindingPlan");
            if(b.texture()!=null){
                String orig=plan.get("baseResource").getAsString();Path png=source.resolve(orig.substring(orig.indexOf(':')+1));
                check(Files.mismatch(png,assets.resolve(b.texture()))==-1,"published PNG differs "+key);
                check(javax.imageio.ImageIO.read(assets.resolve(b.texture()).toFile())!=null,"unreadable PNG "+key);
                proof.put(key,b);
            }
            if(r.get("modelSource").getAsString().equals(id+":"+moth)){
                if(path.startsWith("@skin/")){variants++;check(b.texture().endsWith("/"+r.get("textureSource").getAsString().split(":",2)[1]),"skin Link");}
                else check(b.texture()!=null&&b.texture().endsWith("/textures/armor/mothic_witch_armor.png")&&plan.get("baseSelection").getAsString().equals("DISPLAY_MATCHING_SKIN"),"moth main selects proven standard appearance");
            }
        }
        check(variants==2,"both original skin variants");
        JsonObject report=ImporterAcceptance.json(bbs.resolve("model-import-report.json"));
        for(var item:report.getAsJsonArray("models")){
            var row=item.getAsJsonObject();if(!row.get("baseUsable").getAsBoolean()&&row.get("appliedMaterialSlots").getAsInt()==0){check(!row.get("textureStatus").getAsString().equals("FULL_TEXTURES"),"empty primary cannot be FULL");System.out.println("unbound "+row.get("sourcePath")+" "+row.get("textureReasons"));}
        }
        System.out.println("upgrade after "+coverage(next)+" statuses="+report.get("textureStatusCounts")+" report="+bbs.resolve("model-import-report.json"));
        SharedModelAssets.bootstrap(state,assets.resolve("models"));check(next.equals(SharedModelAssets.bindingsForTest()),"all bindings identical after restart");
        Path bbsJar=instance.resolve("mods/bbs-2.7-1.20.1-zh_CN.jar");BbsCpuAcceptance.run(assets,proof,bbsJar);
        System.out.println("real BBS 2.7 loader after applyConfig read "+proof.size()+" primary/skin PNG Links; both Mothic skins and main verified; CPU only.");
        ForeignSkinAcceptance.run(out.resolve("foreign"),bbsJar);
        if(args.length>1)extraPacks(Path.of(args[1]),out.resolve("other-packs"),bbsJar);
    }
    private static String coverage(Map<String,SharedModelAssets.Binding> bindings){int primary=0,base=0,material=0,skins=0,boundSkins=0;for(var e:bindings.entrySet()){
        var b=e.getValue();if(e.getKey().contains("/skins/")){skins++;if(b.texture()!=null)boundSkins++;continue;}primary++;if(b.texture()!=null)base++;else if(b.parts().stream().anyMatch(p->b.materials().containsKey(p.slot())))material++;
    }return "primary="+primary+" base="+base+" materialOnly="+material+" neither="+(primary-base-material)+" skin="+boundSkins+"/"+skins;}
    private static void extraPacks(Path mods,Path fixture,Path bbs)throws Exception{
        Path cob,cat;try(var ls=Files.list(mods)){var dirs=ls.filter(Files::isDirectory).toList();cob=dirs.stream().filter(d->Files.exists(d.resolve("resourcepacks/regionbiasforms/pack.mcmeta"))).findFirst().orElseThrow();
            cat=dirs.stream().filter(d->Files.exists(d.resolve("assets/cataclysm_spellbooks/geo/abyssal_gnawers.geo.json"))).findFirst().orElseThrow();}
        String prefix="resourcepacks/regionbiasforms/",bedrock="assets/cobblemon/bedrock/pokemon/",png="assets/cobblemon/textures/pokemon/0025_pikachu/",catPng="assets/cataclysm_spellbooks/textures/entity/abyssal_gnawers/abyssal_gnawers.png";
        Path sub=fixture.resolve("source");for(String f:List.of(prefix+"pack.mcmeta",prefix+bedrock+"resolvers/0025_pikachu/1_pikachu_alolan_bias.json",prefix+bedrock+"models/0025_pikachu/pikachu_alola_bias_male.geo.json",png+"pikachu.png",png+"pikachu_shiny.png",png+"pikachu_emissive.png",png+"pikachu_emissive_shiny.png")){
            Path dest=sub.resolve(f);Files.createDirectories(dest.getParent());Files.copy(cob.resolve(f),dest);}
        Path config=fixture.resolve("config");
        new GeoImporter(config,s->{}).scan(List.of(ImporterAcceptance.source("nested_fixture","Nested real resolver",sub),ImporterAcceptance.source("other_fixture","Other real pack",cat)));
        var bindings=SharedModelAssets.bindingsForTest();var sample=new TreeMap<String,SharedModelAssets.Binding>();
        for(var e:bindings.entrySet())if(!e.getKey().contains("/skins/")&&(e.getKey().endsWith("/abyssal_gnawers")||e.getKey().endsWith("/pikachu_alola_bias_male"))){
            Path actual=e.getKey().endsWith("/abyssal_gnawers")?cat.resolve(catPng):cob.resolve(png+"pikachu.png");
            check(e.getValue().texture()!=null&&Files.mismatch(actual,config.resolve("bbs/assets").resolve(e.getValue().texture()))==-1,"other real namespace PNG "+e.getKey());sample.put(e.getKey(),e.getValue());}
        check(sample.size()==2,"both real sources");BbsCpuAcceptance.run(config.resolve("bbs/assets"),sample,bbs);
        System.out.println("Real source comparison: nested Pikachu male -> base pack pikachu.png; Abyssal Gnawers -> entity/abyssal_gnawers/abyssal_gnawers.png; BBS CPU final Links verified.");
    }
}
