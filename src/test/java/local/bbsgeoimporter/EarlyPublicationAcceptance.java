package local.bbsgeoimporter;
import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
final class EarlyPublicationAcceptance {
 static void run(Path base)throws Exception{
  Path root=base.resolve("source"),config=base.resolve("config");String rel="assets/novel/geo/first.geo.json";
  ImporterAcceptance.put(root.resolve(rel),ImporterAcceptance.GEO);var sources=List.of(ImporterAcceptance.source("novel","Novel",root));
  new GeoImporter(base.resolve("empty"),s->{}).bootstrap(List.of());Object manager=new Object();ImporterAcceptance.check(ImportedModelGroups.filterAvailable(manager,List.of()).isEmpty(),"native empty cache established before startup");
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var reads=new AtomicInteger();var ui=new LinkedBlockingQueue<Runnable>();var service=new ScanService(config,()->{reads.incrementAndGet();return sources;},s->{});service.bbsRefresh(ui::add);var executor=Executors.newSingleThreadExecutor();
  EffectiveResources.Reader slow=new EffectiveResources.Reader(){public String fingerprint()throws Exception{entered.countDown();if(!release.await(30,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");return "slow-association";}public ResourceEvidence.Snapshot read(long g,String a)throws Exception{return a.equals("novel:geo/first.geo.json")?new ResourceEvidence.Snapshot(g,a,"fixture",Files.readAllBytes(root.resolve(rel))):null;}};
  try{Performance.reset();service.startup();var pending=new java.util.concurrent.atomic.AtomicReference<CompletableFuture<ReloadSnapshot>>();
   BbsCategoryCpuAcceptance.run(base.resolve("real-category"),()->{pending.set(service.prepare(slow,executor));try{ImporterAcceptance.check(entered.await(4,TimeUnit.SECONDS)&&!pending.get().isDone(),"early section is open before geometry publication");}catch(InterruptedException ex){throw new RuntimeException(ex);}});
   var preparing=pending.get();ImporterAcceptance.check(!preparing.isDone(),"snapshot blocked after fast geometry recovery");
   var keys=ImportedModelGroups.cachedAvailable(manager);ImporterAcceptance.check(keys.size()==1&&Files.isRegularFile(ImporterAcceptance.target(config,"novel",rel)),"current Geo and directory published before slow snapshot/catalog finishes");
   AtomicInteger attaches=new AtomicInteger();service.bbsRefreshActionForTest(()->{attaches.incrementAndGet();return true;});Runnable refresh=ui.poll(2,TimeUnit.SECONDS);ImporterAcceptance.check(refresh!=null,"early snapshot schedules client refresh while preparation is pending");refresh.run();
   ImporterAcceptance.check(attaches.get()==1&&reads.get()==1,"cached empty BBS is notified without reopening or rediscovery");
   release.countDown();service.apply(preparing.get(10,TimeUnit.SECONDS));service.request().get(10,TimeUnit.SECONDS);
   long hashes=PerformanceAcceptance.count("source.hash"),scans=PerformanceAcceptance.count("import.scan");for(int i=0;i<20;i++){service.startup();service.apply(service.prepare(slow,Runnable::run).get());service.request().get();}
   ImporterAcceptance.check(reads.get()==1&&hashes==PerformanceAcceptance.count("source.hash")&&scans==PerformanceAcceptance.count("import.scan"),"20 opens/reloads do not enumerate/hash/scan again");
  }finally{release.countDown();executor.shutdownNow();PerformanceAcceptance.stop(service);}
 }
}
