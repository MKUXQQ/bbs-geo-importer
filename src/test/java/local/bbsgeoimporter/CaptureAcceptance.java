package local.bbsgeoimporter;
import java.nio.file.*;
import java.util.*;
public final class CaptureAcceptance {
    static void check(boolean ok,String message){ImporterAcceptance.check(ok,message);}
    public static final class ManagerFixture { int forgotten; private void forget(String key){forgotten++;} }
    public static final class DirectoryFixture {
        final Set<String> visited=new TreeSet<>();
        public Map<String,Object> list(String prefix,java.util.function.Predicate<Object> filter){
            if(prefix.isEmpty())throw new IllegalArgumentException("Empty path");visited.add(prefix);
            String resource="fresh:odd_directory/body.png";
            return prefix.equals("odd_directory")&&filter.test(resource)?Map.of(resource,new Object()):Map.of();
        }
    }
    public static void run(Path base)throws Exception{
        var directories=new DirectoryFixture();
        var enumerated=ResourceReaders.enumerate((g,address)->null,directories,DirectoryFixture.class,"list");
        check(enumerated.addresses(Set.of("geo","odd_directory")).contains("fresh:odd_directory/body.png"),"nonempty discovered prefix enumerates pack resources case insensitively");
        check(!directories.visited.contains(""),"Forge enumeration never requests empty directory");
        { Path packs=base.resolve("pack-discovery");Files.createDirectories(packs.resolve("folder/assets/fresh/previously_unknown"));
        Files.writeString(packs.resolve("folder/assets/fresh/previously_unknown/body.geo.json"),"{}");
        try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(packs.resolve("inactive.zip")))){zip.putNextEntry(new java.util.zip.ZipEntry("assets/fresh/other_unknown/body.png"));zip.write(new byte[]{1});zip.closeEntry();}
        var packReader=ResourceReaders.localPacks((g,address)->address.endsWith("body.geo.json")?new ResourceEvidence.Snapshot(g,address,"active-fixture",new byte[]{1}):null,packs);
        check(packReader.addresses().containsAll(Set.of("fresh:previously_unknown/body.geo.json","fresh:other_unknown/body.png")),"local directory and ZIP discover arbitrary pack paths without empty manager queries");
        var effectivePack=new EffectiveResources(RuntimeCapture.generation(),packReader,base.resolve("pack-snapshots"));
        var discoveredPacks=effectivePack.additionalSources(List.of());
        check(discoveredPacks.size()==1&&Files.exists(discoveredPacks.get(0).roots().get(0).resolve("assets/fresh/previously_unknown/body.geo.json")),"active manager alone supplies pack bytes and identity; inactive disk candidates excluded"); }
        Object renderer=new Object(),a=new Object(),b=new Object();
        long epoch=RuntimeCapture.reload();
        RuntimeCapture.begin(renderer,a);RuntimeCapture.model(renderer,a,"newmod:geo/a.geo.json");
        RuntimeCapture.texture(renderer,b,"newmod:textures/wrong.png");
        check(RuntimeCapture.drain().isEmpty(),"different entity never paired");
        RuntimeCapture.texture(renderer,a,"newmod:textures/red.png");RuntimeCapture.end();
        var events=RuntimeCapture.drain();check(events.size()==1,"dynamic texture captured");
        RuntimeCapture.acknowledge(events.get(0));RuntimeCapture.enqueue(events.get(0));check(RuntimeCapture.drain().isEmpty(),"completed observation deduplicated");
        RuntimeCapture.begin(renderer,a);RuntimeCapture.modelResult("newmod:geo/a.geo.json");RuntimeCapture.begin(renderer,b);
        RuntimeCapture.textureResult("newmod:textures/blue.png");RuntimeCapture.end();RuntimeCapture.textureResult("newmod:textures/red.png");
        check(RuntimeCapture.drain().isEmpty(),"nested incomplete outer context discarded");
        RuntimeCapture.begin(renderer,a);RuntimeCapture.modelResult("newmod:geo/a.geo.json");RuntimeCapture.reload();RuntimeCapture.textureResult("newmod:textures/red.png");RuntimeCapture.end();
        check(RuntimeCapture.drain().isEmpty(),"reload invalidates in-flight observation");
        check(!RuntimeCapture.enqueue(new ResourceEvidence.Observation(epoch,"newmod:geo/a.geo.json","newmod:textures/red.png",null,"fixture")),"stale event rejected");
        boolean refused=false;try{ResourceEvidence.address("newmod:../evil.png");}catch(IllegalArgumentException e){refused=true;}
        check(refused,"resource traversal rejected");
        Path model=base.resolve("models"),skin=base.resolve("skins");
        ImporterAcceptance.put(model.resolve("assets/newmod/geo/a.geo.json"),ImporterAcceptance.GEO);
        ImporterAcceptance.png(skin.resolve("assets/different/textures/red.png"),0xff123456);
        var catalog=new ResourceCatalog(List.of(ImporterAcceptance.source("true_model_owner","Model",model),ImporterAcceptance.source("true_skin_owner","Skin",skin)));
        var observation=new ResourceEvidence.Observation(RuntimeCapture.generation(),"newmod:geo/a.geo.json","different:textures/red.png",null,"fixture");
        check(catalog.observe(observation,RuntimeCapture.generation()),"unknown Mod cross-owner dynamic association accepted");
        check(catalog.bundles.get("true_model_owner:assets/newmod/geo/a.geo.json").get(0).resource().startsWith("true_skin_owner:"),"namespace never determines container owner");
        check(!catalog.observe(observation,RuntimeCapture.generation()+1),"catalog rejects obsolete generation");
        Path config=base.resolve("config");var sources=List.of(ImporterAcceptance.source("true_model_owner","Model",model),ImporterAcceptance.source("true_skin_owner","Skin",skin));
        GeoImporter importer=new GeoImporter(config,s->{});importer.scan(sources);
        check(importer.supplement(List.of(observation)).size()==1,"dynamic unknown Mod publishes supplement");
        Path out=ImporterAcceptance.target(config,"true_model_owner","assets/newmod/geo/a.geo.json");
        check(SharedModelAssets.bindingsForTest().values().stream().anyMatch(x->x.texture()!=null),"captured skin becomes BBS default through shared binding");
        var manager=new ManagerFixture();String managedKey=SharedModelAssets.bindingsForTest().keySet().iterator().next();SharedModelAssets.beforeGet(manager,managedKey);SharedModelAssets.beforeGet(manager,managedKey);check(manager.forgotten==1,"first managed binding evicts an unobserved cached white model once; repeated access preserves cache");
        importer=new GeoImporter(config,s->{});importer.scan(sources);
        check(ImporterAcceptance.json(config.resolve("bbs/model-import-report.json")).toString().contains("runtime:fixture"),"cache survives restart with hashes validated");
        ImporterAcceptance.png(skin.resolve("assets/different/textures/red.png"),0xff654321);
        importer=new GeoImporter(config,s->{});importer.scan(sources);
        check(!ImporterAcceptance.json(config.resolve("bbs/model-import-report.json")).toString().contains("runtime:fixture"),"changed resource invalidates cached association");
        SharedModelAssets.beforeGet(manager,managedKey);SharedModelAssets.beforeGet(manager,"unmanaged/user");check(manager.forgotten==2,"changed binding invalidates exact managed key only on model access");
        ImporterAcceptance.put(model.resolve("assets/newmod/animations/a.animation.json"),"{\"animations\":{}}");
        RuntimeCapture.begin(renderer,a);RuntimeCapture.modelResult("newmod:geo/a.geo.json");
        RuntimeCapture.animationResult(new Object(),b,"newmod:animations/a.animation.json");check(RuntimeCapture.drain().isEmpty(),"other entity animation rejected");
        RuntimeCapture.animationResult(null,a,"newmod:animations/a.animation.json");check(RuntimeCapture.drain().isEmpty(),"failed animation ignored");
        RuntimeCapture.animationResult(new Object(),a,"newmod:animations/a.animation.json");check(RuntimeCapture.drain().size()==1,"successful animation captured");RuntimeCapture.end();
        Object equalA=new String("equal"),equalB=new String("equal");
        RuntimeCapture.begin(renderer,equalA);RuntimeCapture.modelResult("newmod:geo/a.geo.json");RuntimeCapture.end();
        RuntimeCapture.animationResult(new Object(),equalB,"newmod:animations/a.animation.json");check(RuntimeCapture.drain().isEmpty(),"equal distinct objects never share cached model");
        RuntimeCapture.animationResult(new Object(),equalA,"newmod:animations/a.animation.json");check(RuntimeCapture.drain().size()==1,"same identity cached model survives frame");
        RuntimeCapture.reload();RuntimeCapture.animationResult(new Object(),equalA,"newmod:animations/a.animation.json");check(RuntimeCapture.drain().isEmpty(),"reload invalidates cached generation");
        RuntimeCapture.begin(renderer,equalA);RuntimeCapture.reload();RuntimeCapture.modelResult("newmod:geo/stale.geo.json");RuntimeCapture.end();
        RuntimeCapture.animationResult(new Object(),equalA,"newmod:animations/a.animation.json");check(RuntimeCapture.drain().isEmpty(),"old frame cannot repopulate reloaded cache");
        EffectiveResources.Reader packReader=new EffectiveResources.Reader(){public ResourceEvidence.Snapshot read(long g,String address){return new ResourceEvidence.Snapshot(g,address,"new-pack",ImporterAcceptance.GEO.getBytes(java.nio.charset.StandardCharsets.UTF_8));}public Set<String> addresses(){return Set.of("packonly:geo/new.geo.json");}};
        var packView=new EffectiveResources(RuntimeCapture.generation(),packReader,base.resolve("pack-snapshots"));var packs=packView.additionalSources(sources);
        check(packs.size()==1&&packs.get(0).id().startsWith("resource_pack_"),"pack-only assets have supplier identity not namespace owner");
        check(Files.exists(packs.get(0).roots().get(0).resolve("assets/packonly/geo/new.geo.json")),"new effective Geo discovered");
        long current=RuntimeCapture.generation();EffectiveResources effective=new EffectiveResources(current,(g,address)->new ResourceEvidence.Snapshot(g,address,"test-pack",ImporterAcceptance.GEO.replace("geometry.test","geometry.override").getBytes(java.nio.charset.StandardCharsets.UTF_8)),base.resolve("snapshots"));
        var entry=new ResourceCatalog.Entry("owner","assets/newmod/geo/a.geo.json",model.resolve("assets/newmod/geo/a.geo.json"),"geo");
        check(Files.readString(effective.resolve(entry)).contains("geometry.override"),"effective resource pack bytes replace source bytes");
        RuntimeCapture.reload();boolean expired=false;try{effective.resolve(entry);}catch(java.io.IOException ex){expired=true;}check(expired,"expired snapshot rejected");
        for(int i=0;i<4096;i++)RuntimeCapture.enqueue(new ResourceEvidence.Observation(RuntimeCapture.generation(),"newmod:geo/a.geo.json","newmod:textures/skin"+i+".png",null,"fixture"));
        var overflow=new ResourceEvidence.Observation(RuntimeCapture.generation(),"newmod:geo/a.geo.json","newmod:textures/overflow.png",null,"fixture");
        check(!RuntimeCapture.enqueue(overflow),"queue bounded");RuntimeCapture.drain();check(RuntimeCapture.enqueue(overflow),"overflow can retry after draining");RuntimeCapture.reload();
    }
}
