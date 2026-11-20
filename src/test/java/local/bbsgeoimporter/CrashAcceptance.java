package local.bbsgeoimporter;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class CrashAcceptance {
    static void check(boolean ok,String message){ImporterAcceptance.check(ok,message);}
    static Object field(Object target,String name)throws Exception{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    static void flush(ScanService service)throws Exception{var m=ScanService.class.getDeclaredMethod("flush");m.setAccessible(true);m.invoke(service);}
    static void waitFor(java.util.concurrent.Callable<Boolean> check,String message)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);while(!check.call()&&System.nanoTime()<end)Thread.sleep(20);check(check.call(),message);}
    public static void run(Path base)throws Exception{
        AtomicInteger startupSourceReads=new AtomicInteger(),refreshAttempts=new AtomicInteger();var startupQueue=new LinkedBlockingQueue<Runnable>();var startupRoot=base.resolve("startup-mod");String startupGeo="assets/startup/geo/model.geo.json";ImporterAcceptance.put(startupRoot.resolve(startupGeo),ImporterAcceptance.GEO);var startupService=new ScanService(base.resolve("startup-service"),()->{startupSourceReads.incrementAndGet();return List.of(ImporterAcceptance.source("startup_mod","Startup Mod",startupRoot));},s->{});startupService.bbsRefresh(startupQueue::add);
        startupService.bbsRefreshActionForTest(()->{if(refreshAttempts.incrementAndGet()==1)return false;var modelRoot=base.resolve("startup-service/bbs/assets/models");try(var walk=Files.walk(modelRoot)){boolean enumerated=walk.anyMatch(p->p.getFileName().toString().equals("model.geo.json"));return enumerated&&"Startup Mod".equals(ImportedModelGroups.category("Startup Mod/assets/startup/geo/model"));}catch(java.io.IOException ex){throw new java.io.UncheckedIOException(ex);}});
        try{startupService.startup();startupService.apply(startupService.prepare(null,Runnable::run).get());startupService.request().get(5,TimeUnit.SECONDS);startupService.startup().get(5,TimeUnit.SECONDS);check(startupSourceReads.get()==1,"startup recovery and association share one discovery; duplicate setup is coalesced");check(startupService.status().contains("ready"),"startup without reload automatically completes resource association");Runnable firstRefresh=startupQueue.poll(2,TimeUnit.SECONDS);check(firstRefresh!=null,"completed startup recovery queues a BBS client-thread refresh");firstRefresh.run();Runnable retryRefresh=startupQueue.poll(2,TimeUnit.SECONDS);check(retryRefresh!=null&&refreshAttempts.get()==1,"empty BBS list schedules client-thread refresh retry");retryRefresh.run();check(refreshAttempts.get()==2,"cached empty list is refreshed after startup model publication");}finally{((ScheduledExecutorService)field(startupService,"worker")).shutdownNow();((ScheduledExecutorService)field(startupService,"statusWorker")).shutdownNow();}
        Path root=base.resolve("source");
        ImporterAcceptance.put(root.resolve("assets/test/geo/box.geo.json"),ImporterAcceptance.GEO);
        ImporterAcceptance.png(root.resolve("assets/test/textures/skin.png"),0xff123456);
        var sources=List.of(ImporterAcceptance.source("test","Test",root));
        var catalog=new ResourceCatalog(sources);
        var entry=catalog.entries.get("test").get("assets/test/geo/box.geo.json");
        var resolve=ResourceCatalog.class.getDeclaredMethod("resolveValues",ResourceCatalog.Entry.class,JsonElement.class,String.class,Set.class);resolve.setAccessible(true);
        for(String json:List.of("{}","[]","{\"main\":\"test:geo/box.geo.json\"}","[\"test:geo/box.geo.json\"]")){
            var value=JsonParser.parseString(json);var out=new HashSet<ResourceCatalog.Entry>();
            boolean complete=(boolean)resolve.invoke(catalog,entry,value,"geo",out);
            boolean populated=json.contains("main")||json.contains("test:");
            check(complete==populated&&out.size()==(populated?1:0),"Gson 2.10 reference container: "+json);
        }
        var open=new AtomicBoolean(true);long generation=RuntimeCapture.generation();
        var frozen=ReloadSnapshot.capture(generation,sources,(g,address)->{if(!open.get())throw new AssertionError("Live manager accessed after prepare");Path p=root.resolve("assets/"+address.replace(':','/'));return Files.exists(p)?new ResourceEvidence.Snapshot(g,address,"test-supplier",Files.readAllBytes(p)):null;},base.resolve("frozen"));
        open.set(false);
        check(frozen.reader().read(generation,"test:geo/box.geo.json").supplier().equals("test-supplier"),"snapshot retains supplier without accessing retired manager");
        check(Arrays.equals(frozen.reader().read(generation,"test:geo/box.geo.json").content(),Files.readAllBytes(entry.file())),"snapshot retains exact effective bytes");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var lifecycle=new ScanService(base.resolve("lifecycle"),()->{entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("blocked fixture timeout");}catch(InterruptedException e){throw new RuntimeException(e);}return sources;},s->{});
        var prepareWorker=Executors.newSingleThreadExecutor();
        try{
            var preparing=lifecycle.prepare(null,prepareWorker);
            check(entered.await(2,TimeUnit.SECONDS)&&!preparing.isDone(),"prepare returns while resource capture is blocked off caller thread");
            release.countDown();var old=preparing.get(5,TimeUnit.SECONDS);RuntimeCapture.reload();lifecycle.apply(old);
            check(field(lifecycle,"snapshot")==null,"obsolete prepared generation is not applied or queued");
            var importer=new GeoImporter(base.resolve("obsolete"),s->{});var epochField=GeoImporter.class.getDeclaredField("epoch");epochField.setAccessible(true);epochField.setLong(importer,old.generation());
            var publish=GeoImporter.class.getDeclaredMethod("publishCatalog",List.class,ResourceCatalog.class,Set.class,long.class);publish.setAccessible(true);boolean rejected=false;
            try{publish.invoke(importer,sources,catalog,null,System.nanoTime());}catch(java.lang.reflect.InvocationTargetException e){rejected=e.getCause() instanceof java.io.IOException;}
            check(rejected&&!Files.exists(base.resolve("obsolete/bbs/model-import-index.json")),"obsolete generation cannot publish an index");
        }finally{release.countDown();prepareWorker.shutdownNow();((ScheduledExecutorService)field(lifecycle,"worker")).shutdownNow();((ScheduledExecutorService)field(lifecycle,"statusWorker")).shutdownNow();}
        AtomicReference<Throwable> failure=new AtomicReference<>(new NoSuchMethodError("fixture JsonObject.isEmpty"));
        List<String> logs=new CopyOnWriteArrayList<>();
        var service=new ScanService(base.resolve("config"),()->{var e=failure.get();if(e instanceof LinkageError l)throw l;if(e instanceof RuntimeException r)throw r;return sources;},logs::add);
        try{
            var future=service.reload(null);future.get(10,TimeUnit.SECONDS);service.request().get(10,TimeUnit.SECONDS);
            check(!future.isCompletedExceptionally()&&service.status().contains("scan_failed"),"reload linkage failure completes normally and is not ready");
            check(logs.stream().anyMatch(s->s.contains("NoSuchMethodError")),"linkage cause logged");
            waitFor(()->!Files.exists(base.resolve("config/bbs/runtime-capture-status.json"))?false:ImporterAcceptance.json(base.resolve("config/bbs/runtime-capture-status.json")).get("state").getAsString().equals("scan_failed"),"failure visible in status file");
            failure.set(null);service.rescan().get(10,TimeUnit.SECONDS);service.request().get(10,TimeUnit.SECONDS);check(service.status().contains("ready"),"manual retry recovers after linkage error");
            failure.set(new IllegalStateException("fixture ordinary failure"));service.rescan().get(10,TimeUnit.SECONDS);service.request().get(10,TimeUnit.SECONDS);
            check(service.status().contains("scan_failed")&&logs.stream().anyMatch(s->s.contains("fixture ordinary failure")),"ordinary exception isolated");
            failure.set(null);service.rescan().get(10,TimeUnit.SECONDS);service.request().get(10,TimeUnit.SECONDS);
            var importer=(GeoImporter)field(service,"importer");
            importer.effectiveResources(new EffectiveResources(RuntimeCapture.generation(),(g,a)->{throw new NoSuchMethodError("fixture supplement linkage");},base.resolve("snapshots")));
            RuntimeCapture.enqueue(new ResourceEvidence.Observation(RuntimeCapture.generation(),"test:geo/box.geo.json","test:textures/skin.png",null,"crash-test"));
            flush(service);
            check(service.status().contains("ready")&&logs.stream().noneMatch(s->s.contains("fixture supplement linkage")),"render observations cannot initiate IO or scan after startup");
            service.request().get(10,TimeUnit.SECONDS);check(service.status().contains("ready"),"rescan recovers supplement failure");
        }finally{((ScheduledExecutorService)field(service,"worker")).shutdownNow();((ScheduledExecutorService)field(service,"statusWorker")).shutdownNow();RuntimeCapture.reload();}
    }
}
