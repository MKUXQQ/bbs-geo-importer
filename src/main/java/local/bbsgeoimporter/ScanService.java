package local.bbsgeoimporter;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
public final class ScanService {
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"bbs-geo-importer");t.setDaemon(true);return t;});
    private final Supplier<List<GeoImporter.ModSource>> sources;
    private final GeoImporter importer;
    private final Consumer<String> log;
    private final Path config;
    private CompletableFuture<Void> running;private final CompletableFuture<Void> startupCompletion=new CompletableFuture<>();
    private long queuedEpoch=-1;
    private ReloadSnapshot snapshot;private volatile boolean startupFinished;
    private Executor clientExecutor=Runnable::run;
    private ExternalSources.Session externalSources;
    private final ScheduledExecutorService statusWorker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"bbs-geo-status");t.setDaemon(true);return t;});
    private volatile String state="waiting_reload"; private volatile String lastError="";
    private volatile java.util.function.Consumer<Runnable> bbsRefresh=task->{};
    private volatile java.util.function.BooleanSupplier bbsRefreshAction=this::refreshBbsOnClient;
    private final java.util.concurrent.atomic.AtomicBoolean startupQueued=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean refreshQueued=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicLong refreshVersion=new java.util.concurrent.atomic.AtomicLong();
    private int refreshFailures;
    public ScanService(Path config,Supplier<List<GeoImporter.ModSource>> sources,Consumer<String> log){
        this.config=config;this.sources=sources;this.log=log;importer=new GeoImporter(config,log);
        ModelManagement.configure(config);
        ModelManagement.onChanged((id,enabled)->{if(ImportedModelGroups.setSourceEnabled(id,enabled))scheduleBbsRefresh();});
        importer.publicationListener(this::scheduleBbsRefresh);
        // No periodic source scans or runtime disk supplements.
        statusWorker.scheduleWithFixedDelay(()->{try{writeStatus();}catch(Exception | LinkageError e){log.accept("Status unavailable: "+e);}},0,5,TimeUnit.SECONDS);
    }
    public synchronized CompletableFuture<Void> reload(EffectiveResources.Reader reader){
        return prepare(reader,ForkJoinPool.commonPool()).thenCompose(value->applyAndAwait(value,Runnable::run));
    }
    public void bbsRefresh(java.util.function.Consumer<Runnable> refresh){bbsRefresh=refresh==null?task->{}:refresh;}
    void bbsRefreshActionForTest(java.util.function.BooleanSupplier action){bbsRefreshAction=action==null?this::refreshBbsOnClient:action;}
    public synchronized CompletableFuture<Void> startup(){startupQueued.set(true);return startupCompletion;}
    private void scheduleBbsRefresh(){refreshVersion.incrementAndGet();if(!startupFinished)return;refreshFailures=0;queueRefresh(500);}
    private void queueRefresh(long delay){if(!refreshQueued.compareAndSet(false,true))return;try{statusWorker.schedule(()->{try{bbsRefresh.accept(this::attemptBbsRefresh);}catch(RuntimeException|LinkageError ex){refreshQueued.set(false);log.accept("BBS refresh request failed: "+ex);}},delay,TimeUnit.MILLISECONDS);}catch(RejectedExecutionException ex){refreshQueued.set(false);}}
    private void attemptBbsRefresh(){long version=refreshVersion.get(),start=System.nanoTime();boolean refreshed=false;try{refreshed=bbsRefreshAction.getAsBoolean();}catch(RuntimeException|LinkageError ex){log.accept("BBS refresh deferred: "+ex);}finally{refreshQueued.set(false);}Performance.end("bbs.categoryRefresh",start);if(refreshed){refreshFailures=0;log.accept("BBS model section refreshed on "+Thread.currentThread().getName()+" in "+((System.nanoTime()-start)/1_000_000)+"ms");if(refreshVersion.get()!=version)queueRefresh(500);}else if(++refreshFailures<8)queueRefresh(1000);else log.accept("BBS category attach deferred after 8 attempts; next section open or changed snapshot will retry from memory");}
    private final BbsCategoryRefresh categoryRefresh=new BbsCategoryRefresh();
    private boolean refreshBbsOnClient(){try{Class<?> client=Class.forName("mchorse.bbs_mod.BBSModClient");Object categories=client.getMethod("getFormCategories").invoke(null);return categories!=null&&categoryRefresh.refresh(categories);}catch(ReflectiveOperationException|LinkageError ex){log.accept("BBS refresh deferred: "+ex);return false;}}
    private CompletableFuture<ReloadSnapshot> preparing;
    public synchronized CompletableFuture<ReloadSnapshot> prepare(EffectiveResources.Reader reader,Executor executor){
        if(preparing!=null){Performance.count("startup.reloadCoalesced",0);return preparing;}
        final long epoch=RuntimeCapture.reload();state="snapshotting";
        return preparing=CompletableFuture.supplyAsync(()->{
            SourceCensus.begin();AnalysisInputs.begin();SourceFingerprint.begin(config.resolve("bbs/source-fingerprints.json"));try{Performance.count("startup.sourceEnumerate",0);LoaderSources.beginDiscovery();var discovered=new ArrayList<>(sources.get());try{externalSources=ExternalSources.discover(config.resolveSibling("bbs-geo-sources"),config.resolve("bbs/external-archives"),config.resolveSibling("mods"),discovered,log);discovered.addAll(externalSources.sources());importer.sourceDiagnostics(externalSources.failures());}catch(Exception externalFailure){var failure=new com.google.gson.JsonObject();failure.addProperty("kind","EXTERNAL_SOURCE");failure.addProperty("scope","EXTERNAL_ROOT");failure.addProperty("containerPath",config.resolveSibling("bbs-geo-sources").toString());failure.addProperty("stage","FAILED");failure.addProperty("reason",externalFailure.toString());importer.sourceDiagnostics(List.of(failure));log.accept("External resource discovery failed; loaded Mods continue: "+externalFailure);}state="geometry_recovery";importer.bootstrap(discovered);state="snapshotting";try{return ReloadSnapshot.capture(epoch,List.copyOf(discovered),reader==null?null:ResourceReaders.localPacks(reader,config.resolveSibling("resourcepacks")),config.resolve("bbs/resource-snapshots/prepared"));}catch(Exception|LinkageError captureFailure){log.accept("Snapshot unavailable, retaining authoritative loaded sources: "+captureFailure);return new ReloadSnapshot(epoch,List.copyOf(discovered),null);}}
            catch(Exception | LinkageError e){if(externalSources!=null){externalSources.close();externalSources=null;}importer.releaseStartupAnalysis();if(epoch==RuntimeCapture.generation())state="scan_failed";lastError=trace(e);log.accept("Resource snapshot failed: "+lastError);return null;}finally{SourceFingerprint.end();AnalysisInputs.end();SourceCensus.end();}
        },executor);
    }
    public synchronized void apply(ReloadSnapshot value){
        applyAndAwait(value,Runnable::run);
    }
    /** The reload listener awaits this future without ever blocking its apply/render executor. */
    public synchronized CompletableFuture<Void> applyAndAwait(ReloadSnapshot value,Executor applyExecutor){
        if(value==null)return CompletableFuture.completedFuture(null);
        if(value.generation()!=RuntimeCapture.generation())return CompletableFuture.completedFuture(null);
        if(snapshot==null){snapshot=value;clientExecutor=applyExecutor;}
        return request();
    }
    public synchronized CompletableFuture<Void> request(){
        if(snapshot==null)return running==null?CompletableFuture.completedFuture(null):running;
        if(running!=null&&snapshot.generation()==queuedEpoch)return running;
        // Queue behind a previous generation rather than losing a reload request.
        final long epoch=RuntimeCapture.generation();queuedEpoch=epoch;final var prepared=snapshot;final var view=prepared.reader();
        running=CompletableFuture.runAsync(()->{
            if(epoch!=RuntimeCapture.generation())return;
            Performance.count("startup.scan",0);state="scanning";SourceCensus.resume(prepared.census());SourceFingerprint.resume(prepared.fingerprints());
            try{
                var effective=view==null?null:new EffectiveResources(epoch,view,config.resolve("bbs/resource-snapshots/effective"));
                if(effective!=null)effective.sources(prepared.sources());importer.effectiveResources(effective);var all=new ArrayList<>(prepared.sources());if(effective!=null)try{all.addAll(effective.additionalSources(all.stream().filter(s->!s.external()).toList()));}catch(Exception|LinkageError ex){lastError=trace(ex);log.accept("Additional resource discovery isolated: "+lastError);}
                importer.scan(all);state=epoch==RuntimeCapture.generation()?"publishing_client":"waiting_reload";
            }catch(Exception | LinkageError e){if(epoch==RuntimeCapture.generation())state="scan_failed";lastError=trace(e);log.accept("Geo scan failed; previously published files remain protected by the incremental index: "+lastError);}finally{if(externalSources!=null){externalSources.close();externalSources=null;}SourceFingerprint.end();SourceCensus.end();releaseStartupAnalysis(epoch);}
        },worker).thenRunAsync(()->{
            if(epoch!=RuntimeCapture.generation())return;
            ModelManagement.publishManifest(importer.sourceManifest());
            ModelManagement.registerOnClient();
            // BBS can be not initialized yet. Its first section initiation attaches this memory snapshot.
            // Do not wait for a ClientTick: the first resource reload must finish before ticks begin.
            if(!state.equals("scan_failed")){long start=Performance.start();try{bbsRefreshAction.getAsBoolean();}catch(RuntimeException|LinkageError ex){log.accept("Initial BBS attach deferred to first section: "+ex);}finally{Performance.end("bbs.categoryRefresh",start);}state="ready";}
            startupFinished=true;startupCompletion.complete(null);
        },clientExecutor);return running;
    }
    private synchronized void releaseStartupAnalysis(long epoch){
        if(snapshot==null||snapshot.generation()!=epoch)return;
        importer.releaseStartupAnalysis();
        // Keep a completed coalescing token, not the resource/census/fingerprint graph. Clearing preparing
        // would turn the next ordinary resource reload into another source enumeration and import.
        snapshot=new ReloadSnapshot(epoch,List.of(),null,Map.of(),new SourceCensus.View(Map.of(),Map.of(),Set.of()));
        preparing=CompletableFuture.completedFuture(snapshot);
    }
    synchronized CompletableFuture<Void> rescan(){preparing=null;snapshot=null;queuedEpoch=-1;startupFinished=false;return prepare(null,worker).thenCompose(value->applyAndAwait(value,clientExecutor));}
    private void flush(){ /* Startup-only: render observations do not initiate disk publication. */ }
    private static String trace(Throwable e){var text=new java.io.StringWriter();e.printStackTrace(new java.io.PrintWriter(text));return text.toString();}
    private String lastStatus;
    private synchronized void writeStatus()throws Exception{
        Path path=config.resolve("bbs/runtime-capture-status.json");for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))return;
        Files.createDirectories(path.getParent());var data=new com.google.gson.JsonObject();data.addProperty("state",state);data.addProperty("lastError",lastError);data.addProperty("hook",local.bbsgeoimporter.mixin.CaptureMixinPlugin.status);data.addProperty("animationHook",local.bbsgeoimporter.mixin.CaptureMixinPlugin.animationStatus);data.add("capture",new com.google.gson.Gson().toJsonTree(RuntimeCapture.status()));data.add("bbsBridge",new com.google.gson.Gson().toJsonTree(SharedModelAssets.diagnostics()));data.add("progress",new com.google.gson.Gson().toJsonTree(importer.progress()));data.add("repair",new com.google.gson.Gson().toJsonTree(importer.repairStatus()));data.addProperty("runtimeRenderingVerified",false);
        data.add("modelManagement",new com.google.gson.Gson().toJsonTree(ModelManagement.diagnostics()));
        data.add("listThumbnails",new com.google.gson.Gson().toJsonTree(ListPreviewRequests.diagnostics()));
        var metrics=new TreeMap<>(Performance.snapshot());metrics.remove("status.write");data.add("performance",new com.google.gson.Gson().toJsonTree(metrics));
        String statusKey=data.toString();if(statusKey.equals(lastStatus))return;String serialized=statusKey;Path temp=Files.createTempFile(path.getParent(),"status-",".tmp");try{Files.writeString(temp,serialized);Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);lastStatus=statusKey;Performance.count("status.write",serialized.length());}finally{Files.deleteIfExists(temp);}
    }
    public String status(){return "BBS Geo: "+state+"; progress="+importer.progress()+"; repair="+importer.repairStatus()+"; hook="+local.bbsgeoimporter.mixin.CaptureMixinPlugin.status+"; bridge="+SharedModelAssets.diagnostics()+"; "+RuntimeCapture.status();}
}
