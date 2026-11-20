package local.bbsgeoimporter;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
/** Unknown names and archive roots exercise reference semantics, never a Mod allowlist. */
final class GenericAcceptance {
    static void run(Path base)throws Exception{
        Path suggestions=base.resolve("suggestions");geo(suggestions,"strange/shared_dragon.geo.json","{}");ImporterAcceptance.png(suggestions.resolve("arbitrary/shared_dragon.png"),0xff112233);
        var diagnostic=new ResourceCatalog(List.of(ImporterAcceptance.source("unknown_candidates","Unknown",suggestions)));
        ImporterAcceptance.check(diagnostic.report.getAsJsonArray("textureCandidates").get(0).getAsJsonObject().getAsJsonArray("candidates").size()==1,"name candidate includes PNG outside fixed directories");
        ImporterAcceptance.check(diagnostic.bundles.values().stream().allMatch(List::isEmpty),"exact basename similarity does not confirm a texture binding");
        Path consumer=base.resolve("consumer"),provider=base.resolve("provider");ImporterAcceptance.png(provider.resolve("assets/library/pixels/common.png"),0xff112233);
        for(String name:List.of("one","two","three"))geo(consumer,"assets/consumer/geo/"+name+".geo.json","{'texture':'library:pixels/common'}");
        var cross=new GeoImporter(base.resolve("cross-config"),s->{});cross.scan(List.of(ImporterAcceptance.source("consumer","Consumer",consumer),ImporterAcceptance.source("provider","Provider",provider)));
        ImporterAcceptance.check(ImporterAcceptance.count(base.resolve("cross-config/bbs/assets/models/Provider"),".png")==1&&ImporterAcceptance.count(base.resolve("cross-config/bbs/assets/models/Consumer"),".png")==0,"three Geo share one PNG under actual provider Mod");
        ImporterAcceptance.check(SharedModelAssets.bindingsForTest().values().stream().filter(b->b.texture()!=null&&b.texture().startsWith("models/Provider/")).count()==3,"cross-Mod BBS links point to provider for all consumers");
        Path crossIndex=base.resolve("cross-config/bbs/model-import-index.json");var crossState=ImporterAcceptance.json(crossIndex);var oldFiles=crossState.getAsJsonObject("mods").getAsJsonObject("consumer").getAsJsonObject("files");
        String resourceKey="@resource/provider:assets/library/pixels/common.png";Path oldCopy=base.resolve("cross-config/bbs/assets/models/Consumer/legacy/common.png");ImporterAcceptance.png(oldCopy,0xff112233);
        JsonObject legacyCopy=new JsonObject();legacyCopy.addProperty("target","legacy/common.png");legacyCopy.addProperty("type","texture");legacyCopy.addProperty("writtenHash",GeoImporter.hash(Files.readAllBytes(oldCopy)));oldFiles.add(resourceKey,legacyCopy);crossState.addProperty("schemaVersion",3);Files.writeString(crossIndex,crossState.toString());
        var crossSources=List.of(ImporterAcceptance.source("consumer","Consumer",consumer),ImporterAcceptance.source("provider","Provider",provider));cross.scan(crossSources);
        ImporterAcceptance.check(!Files.exists(oldCopy)&&Files.exists(base.resolve("cross-config/bbs/migration-v4-backup/Consumer/legacy/common.png")),"schema4 retires only owned cross-owner copy after provider publication with backup");
        ImporterAcceptance.check(Files.exists(base.resolve("cross-config/bbs/model-import-index.v3.backup.json")),"schema4 saves original schema3 index");
        crossState=ImporterAcceptance.json(crossIndex);crossState.getAsJsonObject("mods").getAsJsonObject("consumer").getAsJsonObject("files").add(resourceKey,legacyCopy);Files.writeString(oldCopy,"user edit");Files.writeString(crossIndex,crossState.toString());cross.scan(crossSources);
        ImporterAcceptance.check(Files.readString(oldCopy).equals("user edit")&&ImporterAcceptance.json(crossIndex).toString().contains("USER_MODIFIED_LEGACY_PRESERVED"),"schema4 keeps user-modified legacy copies");
        Path typed=base.resolve("typed");
        ImporterAcceptance.png(typed.resolve("assets/random/pixels/body.png"),0xff112233);
        ImporterAcceptance.put(typed.resolve("assets/random/motion/dance.animation.json"),"{'animations':{'dance':{}}}");
        geo(typed,"assets/random/shapes/typed.geo.json","{'textures':{'same':'random:pixels/body'},'animations':{'same':'random:motion/dance'},'texture':'#same','animation':'#same'}");
        ImporterAcceptance.put(typed.resolve("assets/random/blockstates/noise.json"),"{'variants':{'':{'model':'random:block/stone'}}}");
        var typedCatalog=new ResourceCatalog(List.of(ImporterAcceptance.source("random_owner","Random",typed)));
        ImporterAcceptance.check(typedCatalog.requirements.stream().allMatch(q->q.status().equals("FOUND")),"texture and animation aliases with same name use distinct scopes");
        ImporterAcceptance.check(!typedCatalog.report.toString().contains("unsupported_reference"),"ordinary block model references do not pollute Geo diagnostics");
        ImporterAcceptance.png(typed.resolve("assets/random/shapes/Local.PNG"),0xff112233);
        ImporterAcceptance.png(typed.resolve("assets/random/Local.png"),0xffabcdef);
        geo(typed,"assets/random/shapes/relative.geo.json","{'texture':'./Local.png'}");
        geo(typed,"assets/random/shapes/skins.geo.json","{'skins':['random:pixels/body','./Local.PNG']}");
        var priorities=new ResourceCatalog(List.of(ImporterAcceptance.source("random_owner","Random",typed)));
        ImporterAcceptance.check(priorities.bundles.get("random_owner:assets/random/shapes/relative.geo.json").stream().allMatch(e->e.resource().endsWith("shapes/Local.PNG")),"explicit relative location precedes lower priority exact case path");
        ImporterAcceptance.check(priorities.requirements.stream().filter(q->q.role().equals("SKIN_VARIANT")).count()==2,"explicit skin alternatives retain semantic role");
        ImporterAcceptance.check(priorities.report.getAsJsonArray("referenceLookups").asList().stream().anyMatch(x->x.getAsJsonObject().getAsJsonArray("attempts").toString().contains("CASE_FALLBACK")),"reference diagnostics retain actual case fallback search paths");
        var skinImporter=new GeoImporter(base.resolve("skin-config"),s->{});skinImporter.scan(List.of(ImporterAcceptance.source("random_owner","Random",typed)));
        ImporterAcceptance.check(SharedModelAssets.bindingsForTest().entrySet().stream().filter(e->e.getKey().contains("/skins/")).filter(e->e.getValue().texture()!=null).count()==2,"explicit skins publish selectable bindings without copying shared PNGs");
        String owner="unseen_"+UUID.randomUUID().toString().replace("-","");
        Path jar=base.resolve("never-adapted.jar");Files.createDirectories(base);
        try(var zip=FileSystems.newFileSystem(java.net.URI.create("jar:"+jar.toUri()),Map.of("create","true"))){
            Path r=zip.getPath("/");
            ImporterAcceptance.png(r.resolve("assets/alpha/odd/Body.PNG"),0xff112233);
            ImporterAcceptance.png(r.resolve("assets/alpha/odd/eyes.png"),0xffabcdef);
            ImporterAcceptance.png(r.resolve("assets/beta/odd/eyes.png"),0xff112233);
            ImporterAcceptance.put(r.resolve("assets/alpha/motion/shared.animation.json"),"{'animations':{'shared.idle':{},'shared.walk':{}}}");
            geo(r,"assets/alpha/shapes/same.geo.json","{'texture':'../odd/Body','animations':['alpha:motion/shared']}");
            geo(r,"assets/alpha/shapes/different.geo.json","{'textures':['alpha:odd/Body.PNG','beta:odd/eyes'],'animation':'shared.walk'}");
            geo(r,"assets/alpha/shapes/shared.geo.json","{'texture':'alpha:odd/Body.PNG','animation':'alpha:motion/shared.animation.json'}");
            geo(r,"assets/alpha/shapes/missing.geo.json","{'textures':['alpha:odd/Body.PNG','alpha:odd/missing']}");
            geo(r,"assets/alpha/shapes/alias.geo.json","{'textures':{'a':'#b','b':'#a'}}");
            ImporterAcceptance.png(r.resolve("assets/beta/shapes/local.png"),0xff123456);
            geo(r,"assets/beta/shapes/local.geo.json","{'texture':'local.png'}");
            var sources=List.of(ImporterAcceptance.source(owner,"Never Adapted",r));
            var importer=new GeoImporter(base.resolve("config"),s->{});var first=importer.scan(sources);
            ImporterAcceptance.check(first.discovered()==6&&first.failed()==0,"unknown archive recursively discovers all geometries across namespaces");
            var report=ImporterAcceptance.json(base.resolve("config/bbs/model-import-report.json"));var models=report.getAsJsonArray("models");
            ImporterAcceptance.check(status(models,"same.geo.json").equals("COMPLETE"),"relative path and case fallback resolve different texture name");
            ImporterAcceptance.check(status(models,"shared.geo.json").equals("COMPLETE"),"shared texture and animation references resolve");
            ImporterAcceptance.check(status(models,"local.geo.json").equals("COMPLETE"),"same directory PNG reference resolves");
            ImporterAcceptance.check(status(models,"different.geo.json").equals("UNRESOLVED"),"multiple explicit texture roles never become arbitrary variants");
            ImporterAcceptance.check(status(models,"missing.geo.json").equals("INCOMPLETE"),"missing declared PNG prevents COMPLETE");
            ImporterAcceptance.check(status(models,"alias.geo.json").equals("INCOMPLETE"),"alias cycle terminates and is reported invalid");
            Path out=base.resolve("config/bbs/assets/models/Never Adapted");
            ImporterAcceptance.check(ImporterAcceptance.count(out,".animation.json")==1,"shared multi-animation source copied once");
            var index=ImporterAcceptance.json(base.resolve("config/bbs/model-import-index.json"));var mod=index.getAsJsonObject("mods").getAsJsonObject(owner);var files=mod.getAsJsonObject("files");
            Path legacy=out.resolve("legacy/model.png");ImporterAcceptance.png(legacy,0xff998877);JsonObject legacyRecord=new JsonObject();legacyRecord.addProperty("target","legacy/model.png");legacyRecord.addProperty("type","texture");legacyRecord.addProperty("writtenHash",GeoImporter.hash(Files.readAllBytes(legacy)));files.add("@old/texture",legacyRecord);index.addProperty("schemaVersion",2);Files.writeString(base.resolve("config/bbs/model-import-index.json"),index.toString());
            var repeat=importer.scan(sources);
            ImporterAcceptance.check(!Files.exists(legacy)&&Files.exists(base.resolve("config/bbs/migration-v3-backup/Never Adapted/legacy/model.png")),"schema migration backs up owned redundant PNG before retirement");ImporterAcceptance.check(repeat.imported()==0&&repeat.updated()==0&&repeat.skipped()==6,"unknown archive repeat has no model rewrites");
            var saved=ImporterAcceptance.json(base.resolve("config/bbs/model-import-index.json"));var owned=saved.getAsJsonObject("mods").getAsJsonObject(owner);var resource=owned.getAsJsonObject("files").entrySet().stream().filter(e->e.getKey().startsWith("@resource/")&&e.getValue().getAsJsonObject().get("type").getAsString().equals("texture")).findFirst().orElseThrow().getValue().getAsJsonObject();
            Files.write(out.resolve(resource.get("target").getAsString()),new byte[]{1,2,3});
            ImporterAcceptance.check(SharedModelAssets.verifyOutput(base.resolve("config/bbs/assets/models"),"Never Adapted",resource).equals("USER_CONFLICT"),"published hash drift cannot be reported complete");
            Path second=base.resolve("second");geo(second,"assets/alpha/shapes/same.geo.json","{'texture':'alpha:odd/Body.PNG'}");ImporterAcceptance.png(second.resolve("assets/alpha/odd/Body.PNG"),0xff998877);
            var catalog=new ResourceCatalog(List.of(sources.get(0),ImporterAcceptance.source("another_unknown","Other",second)));
            ImporterAcceptance.check(catalog.requirements.stream().anyMatch(q->q.status().equals("AMBIGUOUS")),"same namespace path in distinct Mods is not silently selected");
            ImporterAcceptance.check(catalog.bundles.get(owner+":assets/beta/shapes/local.geo.json").size()==1,"local resources remain owner-scoped");
        }
    }
    static void geo(Path root,String path,String description)throws Exception{
        JsonObject d=JsonParser.parseString(description).getAsJsonObject();d.addProperty("identifier",path);
        JsonObject geometry=new JsonObject();geometry.add("description",d);geometry.add("bones",new JsonArray());JsonArray geometries=new JsonArray();geometries.add(geometry);JsonObject result=new JsonObject();result.add("minecraft:geometry",geometries);ImporterAcceptance.put(root.resolve(path),result.toString());
    }
    static String status(JsonArray rows,String suffix){for(var row:rows){var r=row.getAsJsonObject();if(r.get("model").getAsString().endsWith("/"+suffix))return r.get("status").getAsString();}throw new AssertionError(suffix);}
}
