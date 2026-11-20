package local.bbsgeoimporter;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Exercises the same asynchronous barrier helper used by the real platform listeners. No game launch. */
final class StartupGateAcceptance {
    public static void main(String[] args)throws Exception{run(Files.createTempDirectory(Path.of("build"),"startup-gate-1519-"));}
    static void run(Path base)throws Exception{
        Path mods=base.resolve("mods"),root=mods.resolve("loader_pack"),config=base.resolve("config");
        ImporterAcceptance.put(root.resolve("assets/gate/geo/a.geo.json"),ImporterAcceptance.GEO);
        for(String dir:List.of("test_loose",".connector","backup","test.disabled","saved.bak"))ImporterAcceptance.put(mods.resolve(dir+"/assets/test/geo/example.geo.json"),ImporterAcceptance.GEO);
        Files.writeString(mods.resolve("not_loaded.jar"),"invalid archive must not be opened");
        var sources=List.of(ImporterAcceptance.source("gate_source","Gate",root));
        AtomicInteger enumerations=new AtomicInteger(),clientCommits=new AtomicInteger();
        ScanService service=new ScanService(config,()->{enumerations.incrementAndGet();return sources;},s->{});
        service.bbsRefreshActionForTest(()->{clientCommits.incrementAndGet();return true;});
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        var worker=(ScheduledExecutorService)CrashAcceptance.field(service,"worker");
        worker.submit(()->{entered.countDown();try{release.await();}catch(InterruptedException ex){Thread.currentThread().interrupt();}});
        entered.await(3,TimeUnit.SECONDS);
        List<Runnable> preparation=new ArrayList<>();BlockingQueue<Runnable> apply=new LinkedBlockingQueue<>();
        CompletableFuture<ReloadSnapshot> barrier=new CompletableFuture<>();
        try{
            Performance.reset();service.startup();
            var reload=StartupReload.await(service,null,preparation::add,value->barrier,apply::add);
            ImporterAcceptance.check(preparation.size()==1&&!reload.isDone(),"actual reload helper waits for preparation");
            preparation.remove(0).run();
            var prepared=service.prepare(null,Runnable::run).get();
            var external=prepared.sources().stream().filter(GeoImporter.ModSource::external).toList();
            ImporterAcceptance.check(external.size()==1&&external.get(0).roots().contains(mods.resolve("test_loose").toAbsolutePath().normalize())||external.size()==1&&external.get(0).roots().contains(mods.resolve("test_loose")),"startup adds only the loose pack, excludes backups, JARs and loader-owned directories");
            barrier.complete(prepared);
            ImporterAcceptance.check(!reload.isDone()&&!service.startup().isDone()&&apply.isEmpty(),"barrier cannot complete reload while import worker is blocked");
            ImporterAcceptance.check(!service.status().startsWith("BBS Geo: ready"),"blocked startup never reports ready");
            release.countDown();Runnable client=apply.poll(12,TimeUnit.SECONDS);
            ImporterAcceptance.check(client!=null&&!reload.isDone()&&!service.startup().isDone(),"import completion still awaits one client publication without a tick/join");
            ImporterAcceptance.check(service.status().contains("publishing_client"),"worker completion has explicit client publication state");
            client.run();reload.get(3,TimeUnit.SECONDS);
            ImporterAcceptance.check(service.startup().isDone()&&service.status().startsWith("BBS Geo: ready")&&clientCommits.get()==1,"single final client attach precedes startup completion");
            ReloadSnapshot completed=service.prepare(null,Runnable::run).get();
            ImporterAcceptance.check(completed.sources().isEmpty()&&completed.reader()==null&&completed.fingerprints().isEmpty()&&completed.census().files().isEmpty()&&completed.census().kinds().isEmpty(),"completed startup retains only a light reload token, not resource/fingerprint/census graphs");
            GeoImporter importer=(GeoImporter)CrashAcceptance.field(service,"importer");
            for(String field:List.of("lastCatalog","runtimeCache","effective","savedIndex","activeFingerprint"))ImporterAcceptance.check(CrashAcceptance.field(importer,field)==null,"startup releases retained analysis field: "+field);
            for(String field:List.of("readCache","journalRecords"))ImporterAcceptance.check(((Map<?,?>)CrashAcceptance.field(importer,field)).isEmpty(),"startup releases analysis buffer: "+field);
            ImporterAcceptance.check(!importer.sourceManifest().isEmpty()&&!ImportedModelGroups.modelKeys().isEmpty(),"analysis release preserves the source settings manifest and published model snapshot");
            long scan=PerformanceAcceptance.count("startup.scan"),hash=PerformanceAcceptance.count("source.hash");
            for(int i=0;i<20;i++)StartupReload.await(service,null,Runnable::run,CompletableFuture::completedFuture,apply::add).get(3,TimeUnit.SECONDS);
            ImporterAcceptance.check(enumerations.get()==1&&PerformanceAcceptance.count("startup.scan")==scan&&PerformanceAcceptance.count("source.hash")==hash&&apply.isEmpty()&&clientCommits.get()==1,"later reload/open consumers reuse frozen startup future with zero source work");
            for(String platform:List.of("forge","main")){
                Path entry=Path.of("build/classes/java",platform,"local/bbsgeoimporter",platform.equals("forge")?"ForgeEntrypoint.class":"FabricEntrypoint.class");
                ClassNode node=new ClassNode();new ClassReader(Files.readAllBytes(entry)).accept(node,0);
                boolean awaited=false;for(MethodNode method:node.methods)for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals("local/bbsgeoimporter/StartupReload")&&call.name.equals("await"))awaited=true;
                ImporterAcceptance.check(awaited,"actual "+platform+" compiled reload listener calls the tested await chain");
            }
            System.out.println("STARTUP GATE PASS: worker+client publication awaited, one enumeration/scan, 20 later reloads zero source/hash work, no tick dependency");
        }finally{release.countDown();PerformanceAcceptance.stop(service);}
    }
}
