package local.bbsgeoimporter;
import java.nio.file.*;
import java.util.*;
final class RepairAcceptance {
    static void run(Path base)throws Exception {
        Path root=base.resolve("source"),config=base.resolve("config");
        var geometry=com.google.gson.JsonParser.parseString(ImporterAcceptance.GEO).getAsJsonObject();
        var textures=new com.google.gson.JsonObject();textures.addProperty("main","x:textures/a");geometry.add("textures",textures);geometry.addProperty("animation","x:animations/a.animation.json");
        ImporterAcceptance.put(root.resolve("assets/x/animations/a.animation.json"),"{\"animations\":{}}");
        ImporterAcceptance.put(root.resolve("assets/x/a.geo.json"),geometry.toString());
        ImporterAcceptance.png(root.resolve("assets/x/textures/a.png"),0xffabcdef);
        var importer=new GeoImporter(config,s->{});importer.scan(List.of(ImporterAcceptance.source("repair","Repair",root)));
        var state=ImporterAcceptance.json(config.resolve("bbs/model-import-index.json"));var mod=state.getAsJsonObject("mods").getAsJsonObject("repair");
        Path dir=config.resolve("bbs/assets/models").resolve(mod.get("directory").getAsString());
        List<Path> outputs=new ArrayList<>();for(var f:mod.getAsJsonObject("files").entrySet())outputs.add(dir.resolve(f.getValue().getAsJsonObject().get("target").getAsString()));
        Path geo=outputs.stream().filter(p->p.toString().endsWith(".geo.json")).findFirst().orElseThrow();
        ImporterAcceptance.check(importer.repairMissing()==0,"existing outputs not rewritten by patrol");
        long stamp=Files.getLastModifiedTime(geo).toMillis();Path png=outputs.stream().filter(p->p.toString().endsWith(".png")).findFirst().orElseThrow();Files.delete(png);
        ImporterAcceptance.check(importer.repairMissing()==1&&Files.exists(png),"deleted shared PNG repaired once");
        ImporterAcceptance.check(Files.getLastModifiedTime(geo).toMillis()==stamp,"PNG repair does not rewrite Geo");
        Path animation=outputs.stream().filter(p->p.toString().endsWith(".animation.json")).findFirst().orElseThrow();Files.delete(animation);
        ImporterAcceptance.check(importer.repairMissing()==1&&Files.exists(animation),"deleted animation restored");
        Files.writeString(geo,"user edit");Files.delete(png);importer.repairMissing();
        ImporterAcceptance.check(Files.readString(geo).equals("user edit"),"patrol preserves user model edits while repairing dependency");
        Path cfg=geo.resolveSibling("config.json");Files.writeString(cfg,"user config");Files.delete(geo);importer.repairMissing();
        ImporterAcceptance.check(Files.exists(geo)&&Files.readString(cfg).equals("user config"),"deleted Geo restored and user config retained");
        try(var walk=Files.walk(dir)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        ImporterAcceptance.check(importer.repairMissing()>=outputs.size()&&outputs.stream().allMatch(Files::exists),"whole directory restored at original paths");
        ImporterAcceptance.check(importer.repairMissing()==0,"second repair patrol is idempotent");
        Files.delete(geo);Files.delete(root.resolve("assets/x/a.geo.json"));
        importer.repairMissing();ImporterAcceptance.check(!Files.exists(geo)&&((Number)importer.repairStatus().get("failed")).intValue()>0,"missing source reported rather than fabricated");
    }
}
