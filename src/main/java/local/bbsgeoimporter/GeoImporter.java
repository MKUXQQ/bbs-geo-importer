package local.bbsgeoimporter;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;

/** Loader-independent importer. The index is the only authority for replacing a file. */
public final class GeoImporter {
    public record ModSource(String id, String name, String version, List<Path> roots,
                            String kind, String displayModId, String containerPath, String resourceDomain) {
        public ModSource(String id,String name,String version,List<Path> roots){
            this(id,name,version,roots,"LOADED_MOD",id,roots.isEmpty()?"":roots.get(0).toUri().toString(),"loaded");
        }
        public ModSource { roots=List.copyOf(roots); }
        public boolean external(){return !kind.equals("LOADED_MOD");}
    }
    public record Result(int discovered, int imported, int updated, int skipped, int conflicts, int failed) {}
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson INDEX_JSON=new Gson();
    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private final Path bbs, index; private Path models;
    private final Consumer<String> log;
    private ResourceCatalog lastCatalog;
    private List<ModSource> lastSources=List.of();
    private final Map<String,ModelManagement.SourceInfo> sourceManifest=new LinkedHashMap<>();
    synchronized List<ModelManagement.SourceInfo> sourceManifest(){return sourceManifest.values().stream().map(s->new ModelManagement.SourceInfo(s.id(),s.name(),s.version(),s.geoCount(),ImportedModelGroups.sourceReady(s.id()))).sorted(Comparator.comparing(ModelManagement.SourceInfo::id)).toList();}
    private EffectiveResources effective;
    private RuntimeCache runtimeCache;
    private final Map<Path,byte[]> readCache=new HashMap<>();
    private final Set<String> dependencyValidation=new HashSet<>(),publishedDependencies=new HashSet<>();
    private final Map<String,String> journalRecords=new HashMap<>();
    private Runnable publicationListener=()->{};
    private List<JsonObject> sourceDiagnostics=List.of();
    void sourceDiagnostics(List<JsonObject> diagnostics){sourceDiagnostics=diagnostics.stream().map(JsonObject::deepCopy).toList();}
    void publicationListener(Runnable listener){publicationListener=listener;}
    private volatile Map<String,Object> repairStatus=Map.of("checked",0,"restored",0,"failed",0);
    public Map<String,Object> repairStatus(){return repairStatus;}
    /** Existence-only patrol. Existing bytes are never rewritten or adopted. */
    public synchronized int repairMissing()throws Exception {
        if(lastCatalog==null)return 0;current();
        if(!Files.exists(index,NOFOLLOW))throw new IOException("Import index missing; request a full rescan");
        rejectLinks(index);int checked=0,restored=0,failed=0;
        try(FileChannel channel=FileChannel.open(bbs.resolve("model-import.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()){
            if(lock==null)throw new IOException("Another importer is running");
            JsonObject state=JsonParser.parseString(Files.readString(index)).getAsJsonObject();recoverJournal(state);
            if(state.get("schemaVersion").getAsInt()!=4)throw new IOException("Repair requires schema 4");
            for(var owner:state.getAsJsonObject("mods").entrySet()){
                var mod=owner.getValue().getAsJsonObject();String dir=mod.get("directory").getAsString();
                for(var file:mod.getAsJsonObject("files").entrySet()){
                    var record=file.getValue().getAsJsonObject();if(!record.has("writtenHash"))continue;
                    Path target=resolve(dir,record.get("target").getAsString());rejectLinks(target);checked++;
                    String source=record.has("modelSource")?record.get("modelSource").getAsString():record.has("source")?record.get("source").getAsString():owner.getKey()+":"+file.getKey();
                    int colon=source.indexOf(':');var entry=lastCatalog.entries.getOrDefault(source.substring(0,colon),Map.of()).get(source.substring(colon+1));
                    try{
                        if(!Files.exists(target,NOFOLLOW)){
                            if(entry==null||!Files.isRegularFile(entry.file()))throw new IOException("Current source unavailable: "+source);
                            byte[] bytes=readEffective(entry,entry.file());
                            if(entry.kind().equals("geo")){validate(bytes);var plan=MaterialPlan.read(lastCatalog,source);if(plan.hasSelectors())bytes=GSON.toJson(plan.export(JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject())).getBytes(StandardCharsets.UTF_8);}
                            else validateDependency(entry,bytes);
                            synchronized(RuntimeCapture.publicationLock){current();safeDirectories(target.getParent());publish(target,bytes,null);record.addProperty("writtenHash",hash(bytes));record.addProperty("status","imported");save(state);restored++;}
                        }
                        if(entry!=null&&entry.kind().equals("geo")){
                            Path config=target.resolveSibling("config.json");rejectLinks(config);
                            if(!Files.exists(config,NOFOLLOW))synchronized(RuntimeCapture.publicationLock){current();safeDirectories(config.getParent());publish(config,"{}\n".getBytes(StandardCharsets.UTF_8),null);restored++;}
                        }
                    }catch(Exception ex){current();failed++;log.accept("Missing output repair failed: "+target+" "+ex);}
                }
            }
            if(restored>0)synchronized(RuntimeCapture.publicationLock){current();ImportedModelGroups.update(state);SharedModelAssets.update(lastCatalog,state,models);}
        }
        repairStatus=Map.of("checked",checked,"restored",restored,"failed",failed,"updatedAt",System.currentTimeMillis());
        return restored;
    }
    private long epoch;
    static volatile String uiProgress="";
    private volatile Map<String,Object> progress=Map.of("phase","idle");
    public Map<String,Object> progress(){return progress;}
    private void progress(String phase,String model,int checked,int created,int skipped,int conflicts,int failed){
        uiProgress=phase.equals("ready")?"":(phase.startsWith("startup_")?"发现模型":phase.startsWith("geometry_ready")?"模型已可用，关联贴图":"关联资源")+" · "+checked+" 项";
        progress=Map.of("phase",phase,"model",model,"checked",checked,"created",created,"kept",skipped,"conflicts",conflicts,"failed",failed,"output",models.toString(),"updatedAt",System.currentTimeMillis());
    }
    private void current()throws IOException{if(epoch!=RuntimeCapture.generation())throw new IOException("Obsolete import generation");}
    public GeoImporter(Path config, Consumer<String> log) {
        bbs = config.toAbsolutePath().normalize().resolve("bbs");
        models = bbs.resolve("assets/models"); index = bbs.resolve("model-import-index.json"); this.log = log;
    }
    private void resolveActiveModels(){try{Class<?> api=Class.forName("mchorse.bbs_mod.BBSMod");Object folder=api.getMethod("getAssetsFolder").invoke(null);if(folder instanceof java.io.File file){models=file.toPath().toAbsolutePath().normalize().resolve("models");log.accept("BBS active model output: "+models);}}catch(ReflectiveOperationException|LinkageError ignored){/* BBS not initialized: its documented config/bbs/assets default remains valid. */}}
    public synchronized Result scan(List<ModSource> sources) throws Exception {
        epoch=RuntimeCapture.generation(); resolveActiveModels();
        safeDirectories(bbs);
        try (FileChannel channel = FileChannel.open(bbs.resolve("model-import.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another importer is running");
            AnalysisInputs.begin();SourceFingerprint.begin(bbs.resolve("source-fingerprints.json"));try{return scanLocked(sources);}finally{SourceFingerprint.end();AnalysisInputs.end();}
        }
    }
    public synchronized void effectiveResources(EffectiveResources resources){effective=resources;}
    /** Published bindings/groups own their immutable plans; the startup analysis graph is not a runtime cache. */
    synchronized void releaseStartupAnalysis(){
        lastCatalog=null;lastSources=List.of();runtimeCache=null;effective=null;
        readCache.clear();dependencyValidation.clear();publishedDependencies.clear();journalRecords.clear();
        savedIndex=null;activeFingerprint=null;sourceDiagnostics=List.of();
    }
    public synchronized boolean recoverSnapshot(){return false;}
    private boolean externalDiscoveryUnknown(String id,JsonObject mod){
        String origin=mod.has("containerPath")?mod.get("containerPath").getAsString():"";
        for(JsonObject failure:sourceDiagnostics){
            if(failure.has("scope")&&failure.get("scope").getAsString().equals("EXTERNAL_ROOT"))return true;
            if(failure.has("sourceId")&&failure.get("sourceId").getAsString().equals(id))return true;
            if(!origin.isEmpty()&&failure.has("containerPath")){String failed=failure.get("containerPath").getAsString();if(origin.equals(failed)||origin.startsWith(failed+"!/"))return true;}
        }
        return false;
    }
    private void reconcileLive(JsonObject state,List<ModSource> sources,ResourceCatalog catalog){
        Map<String,List<Path>> live=new HashMap<>();sources.forEach(s->live.put(s.id(),s.roots()));
        for(ModSource source:sources)if(state.getAsJsonObject("mods").has(source.id())){JsonObject mod=state.getAsJsonObject("mods").getAsJsonObject(source.id());mod.addProperty("sourceKind",source.kind());mod.addProperty("resourceDomain",source.resourceDomain());mod.addProperty("containerPath",source.containerPath());}
        for(var owner:state.getAsJsonObject("mods").entrySet())for(var item:owner.getValue().getAsJsonObject().getAsJsonObject("files").entrySet()){
            JsonObject r=item.getValue().getAsJsonObject();if(item.getKey().startsWith("@resource/"))continue;
            String source=r.has("modelSource")?r.get("modelSource").getAsString():owner.getKey()+":"+item.getKey();int colon=source.indexOf(':');if(colon<1)continue;
            String id=source.substring(0,colon),path=source.substring(colon+1);JsonObject mod=owner.getValue().getAsJsonObject();boolean external=id.startsWith("external_")||mod.has("resourceDomain")&&mod.get("resourceDomain").getAsString().startsWith("external:");boolean present=false,unknown=!live.containsKey(id)&&(external?externalDiscoveryUnknown(id,mod):!LoaderSources.discoveryComplete());
            if(live.containsKey(id)){if(catalog!=null&&ModelManagement.enabled(id)){var e=catalog.entries.getOrDefault(id,Map.of()).get(path);present=e!=null&&e.kind().equals("geo");
                if(catalog.report.has("containers"))for(var c:catalog.report.getAsJsonArray("containers")){var row=c.getAsJsonObject();if(row.has("id")&&row.get("id").getAsString().equals(id)&&row.has("status")&&row.get("status").getAsString().equals("failed"))unknown=true;}
            }else for(Path root:live.get(id)){if(!Files.exists(root))unknown=true;if(Files.isRegularFile(root.resolve(path)))present=true;}}
            if(live.containsKey(id)&&live.get(id).stream().anyMatch(root->!Files.exists(root)||SourceCensus.failed(root)))unknown=true;
            if(unknown&&!present)continue;
            if(present)r.remove("inactiveReason");else r.addProperty("inactiveReason",live.containsKey(id)?"SOURCE_GEO_REMOVED":"SOURCE_UNLOADED");
        }
    }
    private void beginSnapshotVerification(){SharedModelAssets.beginVerification(models);}
    synchronized void withholdHistoricalSnapshot()throws Exception{
        resolveActiveModels();if(!Files.isRegularFile(index,NOFOLLOW))return;rejectLinks(index);
        JsonObject state=JsonParser.parseString(Files.readString(index)).getAsJsonObject();
        for(var owner:state.getAsJsonObject("mods").entrySet())for(var file:owner.getValue().getAsJsonObject().getAsJsonObject("files").entrySet())file.getValue().getAsJsonObject().addProperty("inactiveReason","AWAITING_LIVE_SOURCE_VALIDATION");
        ImportedModelGroups.update(state,models);
    }
    public synchronized void recoverMissingBasics(List<ModSource> sources)throws Exception{
        resolveActiveModels();boolean missing=!Files.isRegularFile(index,NOFOLLOW);
        if(!missing){var state=JsonParser.parseString(Files.readString(index)).getAsJsonObject();Set<String> live=new HashSet<>();sources.forEach(s->live.add(s.id()));for(var owner:state.getAsJsonObject("mods").entrySet())if(live.contains(owner.getKey())){var mod=owner.getValue().getAsJsonObject();for(var f:mod.getAsJsonObject("files").entrySet()){var record=f.getValue().getAsJsonObject();if(!f.getKey().startsWith("@")&&record.get("target").getAsString().endsWith(".geo.json")&&!Files.isRegularFile(resolve(mod.get("directory").getAsString(),record.get("target").getAsString()),NOFOLLOW)){missing=true;break;}}}}
        if(missing){bootstrap(sources);publicationListener.run();}
    }
    public synchronized Result bootstrap(List<ModSource> input)throws Exception {
        epoch=RuntimeCapture.generation();resolveActiveModels();long started=System.nanoTime();List<ModSource> sources=LoaderSources.physicalViews(input);safeDirectories(bbs);
        try(FileChannel channel=FileChannel.open(bbs.resolve("model-import.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()){
            if(lock==null)throw new IOException("Another importer is publishing");
            JsonObject state;
            if(Files.exists(index,NOFOLLOW)){rejectLinks(index);state=JsonParser.parseString(Files.readString(index)).getAsJsonObject();if(!state.has("schemaVersion")||state.get("schemaVersion").getAsInt()<1||state.get("schemaVersion").getAsInt()>4||!state.has("mods")||!state.get("mods").isJsonObject())throw new IOException("Unsupported or damaged import index; existing models preserved");}
            else{state=new JsonObject();state.addProperty("schemaVersion",4);state.add("mods",new JsonObject());}
            savedIndex=INDEX_JSON.toJson(state);recoverJournal(state);JsonObject mods=state.getAsJsonObject("mods");Set<String> folders=new HashSet<>(),targets=new HashSet<>();
            for(var owner:mods.entrySet()){var mod=owner.getValue().getAsJsonObject();String dir=mod.get("directory").getAsString();if(!safeName(dir).equals(dir)||!folders.add(fold(dir)))throw new IOException("Invalid directory binding in import index");for(var file:mod.getAsJsonObject("files").entrySet())targets.add(fold(resolve(dir,file.getValue().getAsJsonObject().get("target").getAsString()).toString()));}
            int found=0,created=0,kept=0,conflicts=0,failed=0,checked=0;Set<String> live=new HashSet<>();sourceManifest.clear();
            progress("startup_discovery","",0,0,0,0,0);
            for(ModSource source:sources){if(source.id().equals("bbs_geo_importer"))continue;JsonObject mod=mods.has(source.id())?mods.getAsJsonObject(source.id()):null;Map<String,Path> geos=new TreeMap<>();
                for(Path root:source.roots())try{for(Path file:SourceCensus.files(root))if(file.toString().toLowerCase(Locale.ROOT).endsWith(".json")&&GeoFormats.classify(file.getFileName().toString(),file).equals("geo"))geos.putIfAbsent(root.relativize(file).toString().replace((char)92,'/'),file);}catch(Exception ex){log.accept("Startup source isolated: "+source.id()+" "+ex);}
                if(!geos.isEmpty())sourceManifest.put(source.id(),new ModelManagement.SourceInfo(source.id(),source.name(),source.version(),geos.size(),false));
                if(!ModelManagement.enabled(source.id())){found+=geos.size();continue;}
                for(var candidate:geos.entrySet()){String relative=candidate.getKey(),modelKey=source.id()+":"+relative;live.add(modelKey);found++;checked++;progress("startup_publish",modelKey,checked,created,kept,conflicts,failed);
                    try{byte[] bytes=GeoFormats.normalize(AnalysisInputs.bytes(candidate.getValue()));validate(bytes);String sourceHash=hash(bytes);
                        if(mod==null){mod=new JsonObject();String base=safeName(source.name()==null||source.name().isBlank()?source.id():source.name()),dir=base;int suffix=0;while(folders.contains(fold(dir))||Files.exists(models.resolve(dir),NOFOLLOW))dir=base+"__"+safeName(source.id())+(++suffix==1?"":"_"+suffix);folders.add(fold(dir));mod.addProperty("directory",dir);mod.add("files",new JsonObject());mods.add(source.id(),mod);}
                        mod.addProperty("name",source.name());mod.addProperty("version",source.version());JsonObject files=mod.getAsJsonObject("files"),record=files.has(relative)?files.getAsJsonObject(relative):null;
                        if(record==null){record=new JsonObject();record.addProperty("target",targetRelative(relative));files.add(relative,record);Path target=resolve(mod.get("directory").getAsString(),record.get("target").getAsString());if(!targets.add(fold(target.toString()))){files.remove(relative);conflicts++;continue;}}
                        Path target=resolve(mod.get("directory").getAsString(),record.get("target").getAsString());rejectLinks(target);boolean exists=Files.isRegularFile(target,NOFOLLOW);String current=exists?hash(Files.readAllBytes(target)):null;String priorSource=record.has("sourceHash")?record.get("sourceHash").getAsString():null;boolean sourceChanged=priorSource!=null&&!priorSource.equals(sourceHash);
                        if(exists&&(!record.has("writtenHash")||!current.equals(record.get("writtenHash").getAsString()))){record.addProperty("status","user_conflict");record.addProperty("bootstrapStatus","USER_MODIFIED_PRESERVED");conflicts++;continue;}
                        if(!exists){safeDirectories(target.getParent());publish(target,bytes,current);record.addProperty("sourceHash",sourceHash);record.addProperty("writtenHash",sourceHash);record.addProperty("status","imported");record.addProperty("modelSource",modelKey);saveRecord(state,source.id(),relative);created++;}else{kept++;}
                        record.addProperty("bootstrapStatus","VERIFIED");Path modelConfig=target.resolveSibling("config.json");if(!Files.exists(modelConfig,NOFOLLOW))publish(modelConfig,"{}\n".getBytes(StandardCharsets.UTF_8),null);
                    }catch(Exception ex){failed++;log.accept("Startup Geo isolated: "+modelKey+" "+ex);}
                }
            }
            ModelManagement.publishManifest(sourceManifest());
            if(created>0)state.remove("catalogFingerprint");
            synchronized(RuntimeCapture.publicationLock){reconcileLive(state,sources,null);SharedModelAssets.beginVerification(models);try{if(ImportedModelGroups.update(state,models))publicationListener.run();}finally{SharedModelAssets.endVerification();}}
            progress("geometry_ready_resolving_textures",found+" Geo",checked,created,kept,conflicts,failed);
            int restoredDependencies=restoreIndexedDependencies(sources,state);
            synchronized(RuntimeCapture.publicationLock){reconcileLive(state,sources,null);SharedModelAssets.beginVerification(models);try{SharedModelAssets.bootstrap(state,models);if(ImportedModelGroups.update(state,models))publicationListener.run();}finally{SharedModelAssets.endVerification();}}
            save(state);progress("geometry_ready_resolving_textures",found+" Geo; dependencies="+restoredDependencies,checked,created,kept,conflicts,failed);
            log.accept("BBS Geo startup recovery: discovered="+found+", published="+created+", reused="+kept+", conflicts="+conflicts+", failed="+failed+", elapsedMs="+((System.nanoTime()-started)/1_000_000));
            return new Result(found,created,0,kept,conflicts,failed);
        }
    }
    private int restoreIndexedDependencies(List<ModSource> sources,JsonObject state){
        Set<String> required=requiredResources(state);Map<String,List<Path>> roots=new HashMap<>();for(ModSource source:sources)for(Path root:source.roots())roots.computeIfAbsent(source.id()+":",k->new ArrayList<>()).add(root);
        int restored=0;for(var owner:state.getAsJsonObject("mods").entrySet()){JsonObject mod=owner.getValue().getAsJsonObject();String dir=mod.get("directory").getAsString();for(var item:mod.getAsJsonObject("files").entrySet()){
            if(!item.getKey().startsWith("@resource/")||!required.contains(item.getKey().substring(10)))continue;JsonObject record=item.getValue().getAsJsonObject();if(!record.has("target")||!record.has("source")||!record.has("writtenHash"))continue;
            try{Path target=resolve(dir,record.get("target").getAsString());if(Files.isRegularFile(target,NOFOLLOW)){if(hash(Files.readAllBytes(target)).equals(record.get("writtenHash").getAsString()))continue;record.addProperty("status","user_conflict");continue;}
            String sourceKey=record.get("source").getAsString();int colon=sourceKey.indexOf(':');if(colon<1)continue;List<Path> candidates=new ArrayList<>();for(Path root:roots.getOrDefault(sourceKey.substring(0,colon)+":",List.of())){Path candidate=root.resolve(sourceKey.substring(colon+1));if(Files.isRegularFile(candidate,NOFOLLOW))candidates.add(candidate);}if(candidates.size()!=1)continue;Path source=candidates.get(0);byte[] bytes=Files.readAllBytes(source);if(!record.has("sourceHash")||!hash(bytes).equals(record.get("sourceHash").getAsString()))continue;validateDependency(new ResourceCatalog.Entry(sourceKey.substring(0,colon),sourceKey.substring(colon+1),source,record.get("type").getAsString()),bytes);safeDirectories(target.getParent());publish(target,bytes,null);restored++;record.addProperty("status","imported");}
            catch(Exception ex){log.accept("Startup dependency isolated: "+item.getKey()+" "+ex);}
        }}return restored;
    }
    private byte[] readEffective(ResourceCatalog.Entry entry,Path fallback)throws Exception {
        Path path=effective==null||entry==null?fallback:effective.resolve(entry);byte[] bytes=readCache.get(path);if(bytes==null){bytes=Files.readAllBytes(path);readCache.put(path,bytes);Performance.count("import.read",bytes.length);}return entry!=null&&entry.kind().equals("geo")?GeoFormats.normalize(bytes):bytes;
    }
    public synchronized Set<ResourceEvidence.Observation> supplement(List<ResourceEvidence.Observation> observations)throws Exception {
        if(lastCatalog==null)return Set.of();current();
        Set<String> affected=new HashSet<>();Set<ResourceEvidence.Observation> accepted=new LinkedHashSet<>();
        for(var observation:observations)if(lastCatalog.observe(observation,RuntimeCapture.generation())){
            var model=lastCatalog.uniqueResource(observation.model(),"geo").orElseThrow();affected.add(ResourceCatalog.key(model));accepted.add(observation);

        }
        if(affected.isEmpty())return Set.of();readCache.clear();dependencyValidation.clear();publishedDependencies.clear();journalRecords.clear();
        try(FileChannel channel=FileChannel.open(bbs.resolve("model-import.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()){
            if(lock==null)throw new IOException("Another importer is running");
            publishCatalog(lastSources,lastCatalog,affected,System.nanoTime());
        }
        if(runtimeCache!=null)runtimeCache.add(accepted);
        readCache.clear();dependencyValidation.clear();publishedDependencies.clear();
        return accepted;
    }
    private static void applyObservation(ResourceCatalog catalog,ResourceEvidence.Observation observation){
        if(!catalog.observe(observation,RuntimeCapture.generation()))return;

    }
    private Result scanLocked(List<ModSource> sources) throws Exception {
        long scanStart=System.nanoTime();
        if(effective!=null)effective.sources(sources);writeSourceSet(sources);
        sources = LoaderSources.physicalViews(sources);if(effective!=null)effective.sources(sources);
        progress("resolving_resources","",0,0,0,0,0);
        readCache.clear();dependencyValidation.clear();publishedDependencies.clear();journalRecords.clear();String resourceIdentity=effective==null?"raw":effective.fingerprint();String fingerprint=resourceIdentity==null?null:fingerprintSources(sources,resourceIdentity);Path cache=bbs.resolve("parsed-resource-cache.json");ResourceCatalog catalog=null;boolean reused=false;
        if(fingerprint!=null&&!SourceCensus.incomplete()&&LoaderSources.discoveryComplete()&&Files.isRegularFile(cache,NOFOLLOW))try{rejectLinks(cache);JsonObject data=JsonParser.parseString(Files.readString(cache)).getAsJsonObject();if(fingerprint.equals(data.get("fingerprint").getAsString())){long t=Performance.start();var candidate=ResourceCatalog.thaw(data.getAsJsonObject("catalog"),sources);if(candidate.complete()){catalog=candidate;Performance.end("catalog.reuse",t);}}}catch(Exception ex){log.accept("Parsed cache invalidated: "+ex);}
        reused=catalog!=null;if(catalog==null){try{catalog=CatalogCache.load(sources,effective,bbs.resolve("parsed-components"));}catch(Exception ex){log.accept("Component cache bypassed: "+ex);catalog=new ResourceCatalog(sources,effective);catalog.report.addProperty("incompleteSources",1);}if(!catalog.complete()||SourceCensus.incomplete()||!LoaderSources.discoveryComplete())fingerprint=null;if(fingerprint!=null){JsonObject data=new JsonObject();data.addProperty("fingerprint",fingerprint);data.add("catalog",catalog.freeze());rejectLinks(cache);publish(cache,data.toString().getBytes(StandardCharsets.UTF_8),Files.exists(cache,NOFOLLOW)?hash(Files.readAllBytes(cache)):null);}}
        SharedModelAssets.beginVerification(models);
        try {
        runtimeCache=new RuntimeCache(bbs.resolve("runtime-capture-cache.json"),catalog,sources,log);
        for(var observation:runtimeCache.load())applyObservation(catalog,observation);
        lastCatalog=catalog; lastSources=List.copyOf(sources);
        for(var source:sources){int count=(int)catalog.entries.getOrDefault(source.id(),Map.of()).values().stream().filter(e->e.kind().equals("geo")).count();if(count>0)sourceManifest.put(source.id(),new ModelManagement.SourceInfo(source.id(),source.name(),source.version(),count,false));}
        if(reused&&Files.isRegularFile(index,NOFOLLOW)){JsonObject state=JsonParser.parseString(Files.readString(index)).getAsJsonObject();savedIndex=INDEX_JSON.toJson(state);recoverJournal(state);reconcileLive(state,sources,catalog);if(state.get("schemaVersion").getAsInt()==4&&state.has("catalogFingerprint")&&fingerprint.equals(state.get("catalogFingerprint").getAsString())&&outputsReusable(state)){repairMissingLocked(state);SharedModelAssets.endVerification();SharedModelAssets.beginVerification(models);SharedModelAssets.bootstrap(state,models);if(ImportedModelGroups.update(state,models))publicationListener.run();save(state);ModelCompleteness.append(catalog,state,models);appendSourceStages(catalog,state,sources);writeReport(catalog,sources);int count=catalog.bundles.size();progress("ready","cached plans",count,0,count,0,0);Performance.count("import.unchanged",count);return new Result(count,0,0,count,0,0);}}
        activeFingerprint=fingerprint;
        return publishCatalog(sources,catalog,null,scanStart);
        }finally{SharedModelAssets.endVerification();readCache.clear();dependencyValidation.clear();Performance.end("import.scan",scanStart);}
    }
    private static Set<String> requiredResources(JsonObject state){Set<String> result=new HashSet<>();for(var owner:state.getAsJsonObject("mods").entrySet())if(ModelManagement.enabled(owner.getKey()))for(var f:owner.getValue().getAsJsonObject().getAsJsonObject("files").entrySet()){JsonObject r=f.getValue().getAsJsonObject();if(r.has("inactiveReason")||!r.has("modelSource")||!r.has("bindingPlan"))continue;JsonObject resources=r.getAsJsonObject("bindingPlan").getAsJsonObject("resources");if(resources!=null)result.addAll(resources.keySet());}return result;}
    private static boolean recordEnabled(String owner,String key,JsonObject record,Set<String> required){return !record.has("inactiveReason")&&(key.startsWith("@resource/")?required.contains(key.substring(10)):ModelManagement.enabled(owner));}
    private boolean outputsReusable(JsonObject state){Set<String> required=requiredResources(state);for(var owner:state.getAsJsonObject("mods").entrySet()){var mod=owner.getValue().getAsJsonObject();for(var f:mod.getAsJsonObject("files").entrySet()){var r=f.getValue().getAsJsonObject();if(!recordEnabled(owner.getKey(),f.getKey(),r,required))continue;if(!r.has("writtenHash")||r.has("modelSource")&&!r.has("inactiveReason")&&!SharedModelAssets.planReusable(r))return false;try{Path p=resolve(mod.get("directory").getAsString(),r.get("target").getAsString());if(Files.exists(p,NOFOLLOW)&&!List.of("imported","updated","unchanged").contains(SharedModelAssets.verifyOutput(models,mod.get("directory").getAsString(),r)))return false;}catch(Exception ex){return false;}}}return true;}
    private String fingerprintSources(List<ModSource> sources,String resourceIdentity){try{return SourceFingerprint.sources(sources)+resourceIdentity+"|enabled:"+sources.stream().filter(v->ModelManagement.enabled(v.id())).map(ModSource::id).sorted().toList();}catch(Exception ex){log.accept("Fingerprint unavailable; cache bypassed: "+ex);return null;}}
    private void appendSourceStages(ResourceCatalog catalog,JsonObject state,List<ModSource> sources){
        JsonArray stages=new JsonArray();for(var source:sources){JsonObject row=new JsonObject();row.addProperty("modId",source.id());row.addProperty("name",source.name());row.addProperty("loaded",!source.external());sourceMetadata(row,source);row.add("roots",new Gson().toJsonTree(source.roots().stream().map(p->p.toUri().toString()).toList()));int geos=0,outputs=0,enumerable=0;for(var entry:catalog.entries.getOrDefault(source.id(),Map.of()).values())if(entry.kind().equals("geo")){geos++;var mod=state.getAsJsonObject("mods").getAsJsonObject(source.id());var record=mod==null?null:mod.getAsJsonObject("files").getAsJsonObject(entry.path());if(record!=null&&List.of("imported","updated","unchanged").contains(SharedModelAssets.verifyOutput(models,mod.get("directory").getAsString(),record))){outputs++;String target=record.get("target").getAsString();int slash=target.lastIndexOf('/');if(slash>0&&ImportedModelGroups.modelKeys().contains(mod.get("directory").getAsString()+"/"+target.substring(0,slash)))enumerable++;}}row.addProperty("geoIdentified",geos);row.addProperty("outputPresent",outputs);row.addProperty("bbsEnumerable",enumerable);row.addProperty("stage",geos==0?"ENUMERATED_NO_SUPPORTED_GEO":enumerable==geos?"BBS_ENUMERABLE":"OUTPUT_INCOMPLETE");stages.add(row);}catalog.report.add("sourceStages",stages);
    }
    private static void sourceMetadata(JsonObject row,ModSource source){row.addProperty("kind",source.kind());row.addProperty("displayModId",source.displayModId());row.addProperty("version",source.version());row.addProperty("containerPath",source.containerPath());row.addProperty("resourceDomain",source.resourceDomain());}
    private void writeSourceSet(List<ModSource> sources)throws Exception{
        Path file=bbs.resolve("loaded-sources.json");JsonObject previous=Files.isRegularFile(file)?JsonParser.parseString(Files.readString(file)).getAsJsonObject():new JsonObject(),next=new JsonObject();
        for(var source:sources){JsonObject row=new JsonObject();row.addProperty("name",source.name());row.addProperty("version",source.version());row.addProperty("state",source.external()?"RESOURCE_ONLY":"LOADED");sourceMetadata(row,source);row.add("roots",new Gson().toJsonTree(source.roots().stream().map(p->p.toUri().toString()).toList()));next.add(source.id(),row);}String text=next.toString();if(!text.equals(previous.toString()))publish(file,text.getBytes(StandardCharsets.UTF_8),Files.exists(file)?hash(Files.readAllBytes(file)):null);
    }
    private String activeFingerprint;
    private void repairMissingLocked(JsonObject state)throws Exception{
        Set<String> required=requiredResources(state);int checked=0,restored=0;for(var owner:state.getAsJsonObject("mods").entrySet()){JsonObject mod=owner.getValue().getAsJsonObject();for(var item:mod.getAsJsonObject("files").entrySet()){JsonObject r=item.getValue().getAsJsonObject();if(!recordEnabled(owner.getKey(),item.getKey(),r,required)||!r.has("writtenHash"))continue;Path target=resolve(mod.get("directory").getAsString(),r.get("target").getAsString());checked++;if(!Files.exists(target,NOFOLLOW)){String source=r.has("modelSource")?r.get("modelSource").getAsString():r.has("source")?r.get("source").getAsString():owner.getKey()+":"+item.getKey();int colon=source.indexOf(':');var entry=lastCatalog.entries.getOrDefault(source.substring(0,colon),Map.of()).get(source.substring(colon+1));if(entry==null)continue;byte[] bytes=readEffective(entry,entry.file());if(entry.kind().equals("geo")){var plan=MaterialPlan.read(lastCatalog,source);if(plan.hasSelectors())bytes=GSON.toJson(plan.export(JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject())).getBytes(StandardCharsets.UTF_8);}if(!hash(bytes).equals(r.get("writtenHash").getAsString()))continue;safeDirectories(target.getParent());publish(target,bytes,null);restored++;}if(target.toString().endsWith(".geo.json")&&!Files.exists(target.resolveSibling("config.json"),NOFOLLOW)){safeDirectories(target.getParent());publish(target.resolveSibling("config.json"),"{}\n".getBytes(StandardCharsets.UTF_8),null);restored++;}}}repairStatus=Map.of("checked",checked,"restored",restored,"failed",0);Performance.count("startup.existenceChecks",checked);
    }
    private Result publishCatalog(List<ModSource> sources,ResourceCatalog catalog,Set<String> only,long scanStart) throws Exception {
        JsonObject state;
        current();
        if (Files.exists(index, NOFOLLOW)) {
            rejectLinks(index);
            state = JsonParser.parseString(Files.readString(index)).getAsJsonObject();
            if ((state.get("schemaVersion").getAsInt() != 1 && state.get("schemaVersion").getAsInt() != 2 && state.get("schemaVersion").getAsInt() != 3 && state.get("schemaVersion").getAsInt() != 4) || !state.get("mods").isJsonObject())
                throw new IOException("Unsupported or damaged import index; existing models preserved");
        } else {
            state = new JsonObject(); state.addProperty("schemaVersion", 4); state.add("mods", new JsonObject());
        }
        recoverJournal(state);JsonObject mods = state.getAsJsonObject("mods");
        Set<String> usedFolders = new HashSet<>();
        Set<String> ownedTargets = new HashSet<>();
        for (var item : mods.entrySet()) {
            JsonObject m = item.getValue().getAsJsonObject();
            String dir = m.get("directory").getAsString();
            if (!safeName(dir).equals(dir) || !usedFolders.add(fold(dir))) throw new IOException("Invalid directory binding in index");
            for (var e : m.getAsJsonObject("files").entrySet()) {
                JsonObject f = e.getValue().getAsJsonObject();
                Path t = resolve(m.get("directory").getAsString(), f.get("target").getAsString());
                if (!ownedTargets.add(fold(t.toString()))) throw new IOException("Duplicate target in index");
                if (f.has("writtenHash") && !f.get("writtenHash").getAsString().matches("[a-f0-9]{64}")) throw new IOException("Invalid hash in index");
            }
        }
        // Schema 1 entries keep their exact target and ownership hash. No ownership is inferred.
        synchronized(RuntimeCapture.publicationLock){current();
        if(state.get("schemaVersion").getAsInt()==1) {
            Path backup=bbs.resolve("model-import-index.v1.backup.json");
            if(!Files.exists(backup,NOFOLLOW)) publish(backup,Files.readAllBytes(index),null);
            state.addProperty("schemaVersion",2); save(state);
        }
        if(state.get("schemaVersion").getAsInt()<3){
            Path backup=bbs.resolve("model-import-index.v2.backup.json");
            if(!Files.exists(backup,NOFOLLOW))publish(backup,Files.readAllBytes(index),null);
            // Preserve user-created/changed files. Only owned redundant dependency copies are retired.
            for(var m:mods.entrySet()){
                var mod=m.getValue().getAsJsonObject();var files=mod.getAsJsonObject("files");
                for(String k:new ArrayList<>(files.keySet()))if(k.startsWith("@")&&!k.startsWith("@resource/")){
                    var r=files.getAsJsonObject(k);if(!r.has("type")||!List.of("texture","animation").contains(r.get("type").getAsString()))continue;
                    Path target=resolve(mod.get("directory").getAsString(),r.get("target").getAsString());rejectLinks(target);
                    if(!Files.exists(target,NOFOLLOW)){files.remove(k);continue;}
                    if(r.has("writtenHash")&&hash(Files.readAllBytes(target)).equals(r.get("writtenHash").getAsString())){
                        Path saved=bbs.resolve("migration-v3-backup").resolve(mod.get("directory").getAsString()).resolve(r.get("target").getAsString());safeDirectories(saved.getParent());if(!Files.exists(saved,NOFOLLOW))publish(saved,Files.readAllBytes(target),null);
                        if(!hash(Files.readAllBytes(saved)).equals(r.get("writtenHash").getAsString()))throw new IOException("Migration backup differs; original preserved");Files.delete(target);files.remove(k);
                    }else r.addProperty("migrationStatus","USER_MODIFIED_LEGACY_PRESERVED");
                }
            }
            state.addProperty("schemaVersion",3);save(state);
        }
        if(state.get("schemaVersion").getAsInt()==3){
            Path backup=bbs.resolve("model-import-index.v3.backup.json");if(!Files.exists(backup,NOFOLLOW))publish(backup,Files.readAllBytes(index),null);
            for(var owner:mods.entrySet())for(var file:owner.getValue().getAsJsonObject().getAsJsonObject("files").entrySet())if(file.getKey().startsWith("@resource/")){
                var record=file.getValue().getAsJsonObject();String resource=file.getKey().substring("@resource/".length());
                if(!resource.startsWith(owner.getKey()+":"))record.addProperty("migrationStatus","LEGACY_CROSS_OWNER_COPY_PRESERVED_PENDING_PROVIDER_PUBLICATION");
            }
            state.addProperty("schemaVersion",4);save(state);
        }
        }
        int found=0, added=0, updated=0, skipped=0, conflicts=0, failed=0;Set<String> pendingBindings=new HashSet<>();
        if(only==null) for(var container:catalog.report.getAsJsonArray("containers"))
            if(container.getAsJsonObject().get("ownership").getAsString().equals("ambiguous_shared_root")) conflicts++;
        Set<String> seenMods = new HashSet<>();
        List<ModSource> ordered = new ArrayList<>(sources); ordered.sort(Comparator.comparing(ModSource::id));
        for (ModSource source : ordered) {
            if (source.id().equals("bbs_geo_importer") || !ModelManagement.enabled(source.id()) || !seenMods.add(source.id())) continue;
            JsonObject mod = mods.has(source.id()) ? mods.getAsJsonObject(source.id()) : null;
            Map<String, Path> candidates = new TreeMap<>();
            for(var entry:catalog.entries.getOrDefault(source.id(),Map.of()).values())
                if(entry.kind().equals("geo")) candidates.put(entry.path(),entry.file());
            for (var candidate : candidates.entrySet()) {
                if(only!=null&&!only.contains(source.id()+":"+candidate.getKey()))continue;
                synchronized(RuntimeCapture.publicationLock){current();
                found++; String relative = candidate.getKey(); JsonObject record = mod != null && mod.getAsJsonObject("files").has(relative) ? mod.getAsJsonObject("files").getAsJsonObject(relative) : null;
                try {
                    var sourceEntry=catalog.entries.getOrDefault(source.id(),Map.of()).get(relative);
                    progress("publishing",source.id()+":"+relative,found,added,skipped,conflicts,failed);
                    byte[] bytes = readEffective(sourceEntry,candidate.getValue()); validate(bytes);
                    String sourceHash = hash(bytes);
                    MaterialPlan materialPlan=MaterialPlan.read(catalog,ResourceCatalog.key(sourceEntry));
                    if(materialPlan.hasSelectors())bytes=GSON.toJson(materialPlan.export(JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject())).getBytes(StandardCharsets.UTF_8);
                    String outputHash=hash(bytes);
                    if (mod == null) {
                        mod = new JsonObject();
                        String folder = safeName(source.name() == null || source.name().isBlank() ? source.id() : source.name());
                        String base = folder; int n=0;
                        while (usedFolders.contains(fold(folder)) || Files.exists(models.resolve(folder), NOFOLLOW))
                            folder = base + "__" + safeName(source.id()) + (++n == 1 ? "" : "_" + n);
                        usedFolders.add(fold(folder)); mod.addProperty("directory", folder); mod.add("files", new JsonObject()); mods.add(source.id(), mod);
                    }
                    mod.addProperty("name", source.name()); mod.addProperty("version", source.version());
                    JsonObject files = mod.getAsJsonObject("files");
                    if (files.has(relative)) record = files.getAsJsonObject(relative);
                    else {
                        record = new JsonObject(); record.addProperty("target", targetRelative(relative)); files.add(relative, record);
                        String targetKey = fold(resolve(mod.get("directory").getAsString(), record.get("target").getAsString()).toString());
                        if (!ownedTargets.add(targetKey)) { files.remove(relative); conflicts++; log.accept("Path collision: " + source.id() + ":" + relative); save(state); continue; }
                    }
                    record.addProperty("sourceHash", sourceHash);
                    Path target = resolve(mod.get("directory").getAsString(), record.get("target").getAsString());
                    rejectLinks(target);
                    boolean exists = Files.exists(target, NOFOLLOW);
                    String current = exists ? hash(Files.readAllBytes(target)) : null;
                    if (exists && (!record.has("writtenHash") || !current.equals(record.get("writtenHash").getAsString()))) {
                        record.addProperty("status", "user_conflict"); conflicts++; catalog.event(new ResourceCatalog.Entry(source.id(),relative,candidate.getValue(),"geo"),"user_conflict","Destination changed by user; dependencies preserved"); save(state); continue;
                    }
                    if (exists && current.equals(outputHash)) { skipped++; record.addProperty("status", "unchanged"); }
                    else {
                        safeDirectories(target.getParent());
                        publish(target, bytes, current);
                        record.addProperty("writtenHash", outputHash); record.addProperty("status", exists ? "updated" : "imported");
                        if (exists) updated++; else added++;
                        // Commit ownership before auxiliary config; a config failure must not orphan a published model.
                        saveRecord(state,source.id(),relative);
                    }
                    Path config = target.getParent().resolve("config.json");
                    if (!Files.exists(config, NOFOLLOW)) publish(config, "{}\n".getBytes(StandardCharsets.UTF_8), null);
                    catalog.event(new ResourceCatalog.Entry(source.id(),relative,candidate.getValue(),"geo"),record.get("status").getAsString(),target.toString());
                    record.remove("error");
                    ResourceCatalog.Entry modelEntry=catalog.entries.getOrDefault(source.id(),Map.of()).get(relative);
                    if(modelEntry!=null) {
                        record.addProperty("modelSource",ResourceCatalog.key(modelEntry));
                        record.add("sourceIdentity",catalog.identities.get(ResourceCatalog.key(modelEntry)));
                        importDependencies(sources,catalog,modelEntry,mod,state,target.getParent(),ownedTargets);
                        synchronized(RuntimeCapture.publicationLock){current();pendingBindings.add(ResourceCatalog.key(modelEntry));if(found%32==0){SharedModelAssets.update(catalog,state,models,pendingBindings);pendingBindings.clear();}}
                    }
                } catch (Exception e) {
                    failed++; log.accept("Model failed: " + source.id() + ":" + relative + " " + e);
                    catalog.event(new ResourceCatalog.Entry(source.id(),relative,candidate.getValue(),"geo"),"failed",e.toString());
                    if (record != null) { record.addProperty("status", "failed"); record.addProperty("error", e.toString()); }
                    // An index write failure stops this scan rather than granting ownership from an uncommitted state.
                    save(state);
                }
            }
        }
            /* Publish category changes once after the complete batch. */
        }
                /* Per-model publication and ownership commit share the reload lock. */
        synchronized(RuntimeCapture.publicationLock){current();
        retireCrossOwnerCopies(catalog,state);
        reconcileLive(state,sources,catalog);SharedModelAssets.update(catalog,state,models);boolean categoriesChanged=ImportedModelGroups.update(state,models);if(categoriesChanged)publicationListener.run();
        if(only==null&&activeFingerprint!=null)state.addProperty("catalogFingerprint",activeFingerprint);
        save(state);
        catalog.report.addProperty("totalElapsedMillis",(System.nanoTime()-scanStart)/1_000_000);
        JsonObject totals=new JsonObject(); totals.addProperty("discoveredGeo",found); totals.addProperty("importedGeo",added); totals.addProperty("updatedGeo",updated); totals.addProperty("skippedGeo",skipped); totals.addProperty("conflicts",conflicts); totals.addProperty("failedGeo",failed); catalog.report.add("summary",totals);
        ModelCompleteness.append(catalog,state,models);
        catalog.report.add("textureConsumers",new Gson().toJsonTree(catalog.textureConsumers()));
        appendSourceStages(catalog,state,sources);
        writeReport(catalog, sources);
        if(only==null) { /* Full report already published. */ }
        else {
            JsonObject batch=catalog.report.deepCopy();batch.addProperty("scope","runtime_supplement");
            Path batchPath=bbs.resolve("runtime-import-report.json");
            publish(batchPath,GSON.toJson(batch).getBytes(StandardCharsets.UTF_8),Files.exists(batchPath,NOFOLLOW)?hash(Files.readAllBytes(batchPath)):null);
        }
        }
        progress("ready","",found,added,skipped,conflicts,failed);
        Result result = new Result(found, added, updated, skipped, conflicts, failed);
        log.accept("BBS Geo scan: "+result);
        return result;
    }
    private void importDependencies(List<ModSource> sources,ResourceCatalog catalog, ResourceCatalog.Entry model, JsonObject mod, JsonObject state, Path modelDir, Set<String> ownedTargets) throws Exception {
        Set<String> done=new HashSet<>();Map<String,String> animationKeys=new HashMap<>();
        for(var edge:catalog.bundles.getOrDefault(ResourceCatalog.key(model),List.of())) {
            if(!done.add(edge.resource()))continue;
            int colon=edge.resource().indexOf(':');var dep=catalog.entries.getOrDefault(edge.resource().substring(0,colon),Map.of()).get(edge.resource().substring(colon+1));if(dep==null)continue;
            try {
                byte[] bytes=readEffective(dep,dep.file());if(!dependencyValidation.contains(ResourceCatalog.key(dep))){validateDependency(dep,bytes);dependencyValidation.add(ResourceCatalog.key(dep));}
                if(dep.kind().equals("animation")){
                    var animations=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("animations");
                    boolean conflict=false;for(var a:animations.entrySet()){String old=animationKeys.putIfAbsent(a.getKey(),a.getValue().toString());if(old!=null&&!old.equals(a.getValue().toString()))conflict=true;}
                    if(conflict){catalog.event(model,"animation_key_conflict",edge.resource()+": different values share animation names");continue;}
                }
                String dependencyPath=dep.path();
                if(dep.kind().equals("animation")&&!dependencyPath.toLowerCase(Locale.ROOT).endsWith(".animation.json")){
                    int slash=dependencyPath.lastIndexOf('/'),dot=dependencyPath.lastIndexOf('.');
                    String stem=dependencyPath.substring(slash+1,dot>slash?dot:dependencyPath.length());
                    dependencyPath=(slash<0?"":dependencyPath.substring(0,slash+1))+stem+".animation.json";
                }
                String original="animations/imported-resources/"+safeName(dep.owner())+"/"+dependencyPath;
                JsonObject provider=dependencyOwner(sources,state,dep.owner());
                if(publishedDependencies.add(ResourceCatalog.key(dep)))managedDependency(catalog,dep,provider,state,"@resource/"+ResourceCatalog.key(dep),original,bytes,ownedTargets,edge.evidence());
            }catch(Exception ex){catalog.event(model,"dependency_failed",edge.resource()+": "+ex);}
        }
        Set<String> skins=new TreeSet<>();
        for(var req:catalog.requirementsFor(ResourceCatalog.key(model)))if(req.kind().equals("texture")&&DeclaredFrames.firstSkin(req.role())&&req.status().equals("FOUND"))skins.addAll(req.candidates());
        for(String skin:skins){
            var provider=SharedModelAssets.resourceOwner(state,skin);var texture=SharedModelAssets.resourceRecord(state,skin);
            if(provider==null||!List.of("imported","updated","unchanged").contains(SharedModelAssets.verifyOutput(models,provider.get("directory").getAsString(),texture)))continue;
            String original=mod.getAsJsonObject("files").getAsJsonObject(model.path()).get("target").getAsString();
            String tag=hash(skin.getBytes(StandardCharsets.UTF_8)).substring(0,16);
            String relative=original.substring(0,original.lastIndexOf('/'))+"/skins/"+tag+"/model.geo.json";
            String recordKey="@skin/"+ResourceCatalog.key(model)+"/"+tag;
            byte[] skinBytes=readEffective(model,model.file());MaterialPlan skinPlan=MaterialPlan.read(catalog,ResourceCatalog.key(model));
            if(skinPlan.hasSelectors())skinBytes=GSON.toJson(skinPlan.export(JsonParser.parseString(new String(skinBytes,StandardCharsets.UTF_8)).getAsJsonObject())).getBytes(StandardCharsets.UTF_8);
            managedDependency(catalog,model,mod,state,recordKey,relative,skinBytes,ownedTargets,"explicit_skin:"+skin);
            var record=mod.getAsJsonObject("files").getAsJsonObject(recordKey);record.addProperty("modelSource",ResourceCatalog.key(model));record.addProperty("textureSource",skin);saveRecord(state,model.owner(),recordKey);
        }
    }

    private void retireCrossOwnerCopies(ResourceCatalog catalog,JsonObject state)throws Exception{
        for(var owner:state.getAsJsonObject("mods").entrySet()){
            var mod=owner.getValue().getAsJsonObject();var files=mod.getAsJsonObject("files");
            for(String key:new ArrayList<>(files.keySet()))if(key.startsWith("@resource/")&&!key.substring(10).startsWith(owner.getKey()+":")){
                String resource=key.substring(10);var record=files.getAsJsonObject(key);var provider=SharedModelAssets.resourceOwner(state,resource);var canonical=SharedModelAssets.resourceRecord(state,resource);
                if(provider==null||!List.of("imported","updated","unchanged").contains(SharedModelAssets.verifyOutput(models,provider.get("directory").getAsString(),canonical))){record.addProperty("migrationStatus","PROVIDER_NOT_PUBLISHED_LEGACY_PRESERVED");continue;}
                Path target=resolve(mod.get("directory").getAsString(),record.get("target").getAsString());rejectLinks(target);
                if(!Files.exists(target,NOFOLLOW)){files.remove(key);continue;}
                String current=hash(Files.readAllBytes(target));
                if(!record.has("writtenHash")||!current.equals(record.get("writtenHash").getAsString())){record.addProperty("migrationStatus","USER_MODIFIED_LEGACY_PRESERVED");continue;}
                Path backup=bbs.resolve("migration-v4-backup").resolve(mod.get("directory").getAsString()).resolve(record.get("target").getAsString());safeDirectories(backup.getParent());
                if(!Files.exists(backup,NOFOLLOW))publish(backup,Files.readAllBytes(target),null);rejectLinks(backup);
                if(!current.equals(hash(Files.readAllBytes(backup))))throw new IOException("Migration backup differs; original preserved");
                if(!current.equals(hash(Files.readAllBytes(target))))throw new IOException("Legacy file changed during migration; preserved");
                Files.delete(target);files.remove(key);JsonObject event=new JsonObject();event.addProperty("source",resource);event.addProperty("status","legacy_cross_owner_retired");event.addProperty("detail",target+"; backup="+backup);catalog.report.getAsJsonArray("events").add(event);save(state);
            }
        }
    }
    private JsonObject dependencyOwner(List<ModSource> sources,JsonObject state,String owner)throws IOException{
        JsonObject mods=state.getAsJsonObject("mods");if(mods.has(owner))return mods.getAsJsonObject(owner);
        ModSource source=sources.stream().filter(s->s.id().equals(owner)).findFirst().orElseThrow(()->new IOException("Unknown dependency owner: "+owner));
        String base=safeName(source.name()==null||source.name().isBlank()?owner:source.name()),folder=base;Set<String> used=new HashSet<>();for(var item:mods.entrySet())used.add(fold(item.getValue().getAsJsonObject().get("directory").getAsString()));
        int n=0;while(used.contains(fold(folder))||Files.exists(models.resolve(folder),NOFOLLOW))folder=base+"__"+safeName(owner)+"_"+(++n);
        JsonObject mod=new JsonObject();mod.addProperty("directory",folder);mod.addProperty("name",source.name());mod.addProperty("version",source.version());mod.add("files",new JsonObject());mods.add(owner,mod);return mod;
    }
    private void managedDependency(ResourceCatalog catalog,ResourceCatalog.Entry dep,JsonObject mod,JsonObject state,String key,String relative,byte[] bytes,Set<String> ownedTargets,String evidence) throws Exception {
        JsonObject files=mod.getAsJsonObject("files"); JsonObject r=files.has(key)?files.getAsJsonObject(key):null;
        Path target=resolve(mod.get("directory").getAsString(),relative);
        if(r==null) {
            if(!ownedTargets.add(fold(target.toString()))) throw new IOException("Dependency target collision: "+relative);
            r=new JsonObject(); r.addProperty("target",relative); files.add(key,r);
        } else if(!r.get("target").getAsString().equals(relative)) throw new IOException("Dependency target binding changed");
        r.addProperty("type",dep.kind()); r.addProperty("source",ResourceCatalog.key(dep)); r.addProperty("evidence",evidence);
        String sourceHash=hash(bytes), current=Files.exists(target,NOFOLLOW)?hash(Files.readAllBytes(target)):null;
        r.addProperty("sourceHash",sourceHash);if(dep.kind().equals("texture"))r.addProperty("validatedPngHash",sourceHash);r.add("sourceIdentity",catalog.identities.get(ResourceCatalog.key(dep)));
        String status;
        if(current!=null && (!r.has("writtenHash") || !current.equals(r.get("writtenHash").getAsString()))) status="user_conflict";
        else if(sourceHash.equals(current)) status="unchanged";
        else {safeDirectories(target.getParent()); publish(target,bytes,current); r.addProperty("writtenHash",sourceHash); status=current==null?"imported":"updated";}
        r.addProperty("status",status);String owner=state.getAsJsonObject("mods").entrySet().stream().filter(e->e.getValue()==mod).map(Map.Entry::getKey).findFirst().orElseThrow();saveRecord(state,owner,key);catalog.event(dep,status,relative+"; "+evidence);
    }
    private static void validateDependency(ResourceCatalog.Entry dep,byte[] bytes) throws IOException {
        Performance.count("dependency.validate."+dep.kind(),bytes.length);
        if(dep.kind().equals("texture")) {
            if(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes))==null) throw new IOException("Invalid PNG");
        } else GeoFormats.validateAnimations(bytes);
    }
    private void writeReport(ResourceCatalog catalog,List<ModSource> sources) throws Exception {
        JsonArray sourceFailures=new JsonArray();sourceDiagnostics.forEach(sourceFailures::add);catalog.report.add("sourceFailures",sourceFailures);
        JsonArray resources=new JsonArray(); Set<String> linked=new HashSet<>();
        JsonArray associations=new JsonArray();
        for(var edges:catalog.bundles.values()) for(var edge:edges) {linked.add(edge.resource()); JsonObject o=new JsonObject();o.addProperty("model",edge.model());o.addProperty("resource",edge.resource());o.addProperty("evidence",edge.evidence());associations.add(o);}
        for(var files:catalog.entries.values()) for(var entry:files.values()) if(!entry.kind().equals("other")) {JsonObject o=new JsonObject();o.addProperty("source",ResourceCatalog.key(entry));o.addProperty("kind",entry.kind());o.add("sourceIdentity",catalog.identities.get(ResourceCatalog.key(entry)));o.addProperty("association",entry.kind().equals("geo")?"model":linked.contains(ResourceCatalog.key(entry))?"linked":"unassociated");resources.add(o);}
        catalog.report.add("resources",resources);catalog.report.add("associations",associations);
        JsonArray perMod=new JsonArray();
        for(var source:sources){
            JsonObject row=new JsonObject();row.addProperty("id",source.id());row.addProperty("name",source.name());sourceMetadata(row,source);
            for(String kind:List.of("geo","texture","animation")){
                var files=catalog.entries.getOrDefault(source.id(),Map.of()).values().stream().filter(e->e.kind().equals(kind)).toList();
                row.addProperty("discovered_"+kind,files.size());
                row.addProperty("linked_"+kind,files.stream().filter(e->linked.contains(ResourceCatalog.key(e))).count());
            }
            JsonObject statuses=new JsonObject();
            for(var ev:catalog.report.getAsJsonArray("events")){var e=ev.getAsJsonObject();if(e.get("source").getAsString().startsWith(source.id()+":")){String status=e.get("status").getAsString();statuses.addProperty(status,statuses.has(status)?statuses.get(status).getAsInt()+1:1);}}
            JsonObject completeness=new JsonObject();for(var value:catalog.report.getAsJsonArray("models")){var model=value.getAsJsonObject();if(model.get("model").getAsString().startsWith(source.id()+":")){String status=model.get("status").getAsString();completeness.addProperty(status,completeness.has(status)?completeness.get(status).getAsInt()+1:1);}}
            row.add("modelCompleteness",completeness);row.add("publicationEvents",statuses);perMod.add(row);
        }
        catalog.report.add("perMod",perMod);
        catalog.report.addProperty("runtimeRenderingVerified",false);catalog.report.addProperty("generation",RuntimeCapture.generation());catalog.report.addProperty("generatedAt",java.time.Instant.now().toString());
        Path report=bbs.resolve("model-import-report.json");
        publish(report,GSON.toJson(catalog.report).getBytes(StandardCharsets.UTF_8),Files.exists(report,NOFOLLOW)?hash(Files.readAllBytes(report)):null);
    }
    private String savedIndex;
    private void save(JsonObject state) throws Exception {
        long timing=Performance.start();String serialized=INDEX_JSON.toJson(state);Performance.end("index.serialize",timing);if(serialized.equals(savedIndex)&&Files.isRegularFile(index,NOFOLLOW)&&Files.readString(index).equals(serialized)){clearJournal();return;}
        rejectLinks(index);
        publish(index, serialized.getBytes(StandardCharsets.UTF_8), Files.exists(index, NOFOLLOW) ? hash(Files.readAllBytes(index)) : null);
        savedIndex=serialized;Performance.end("index.commit",timing);Performance.count("index.bytes",serialized.length());
        clearJournal();
    }
    private void clearJournal()throws Exception{Path journal=bbs.resolve("model-import-journal");if(Files.isDirectory(journal,NOFOLLOW)){rejectLinks(journal);try(var files=Files.list(journal)){for(Path file:files.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}[.]json")).toList()){rejectLinks(file);Files.delete(file);}}}
    }
    private void saveRecord(JsonObject state,String owner,String key)throws Exception{
        // Each published file has an atomic ownership receipt; full index checkpoints are bounded batches.
        JsonObject mod=state.getAsJsonObject("mods").getAsJsonObject(owner),delta=new JsonObject(),meta=new JsonObject();for(var field:mod.entrySet())if(!field.getKey().equals("files"))meta.add(field.getKey(),field.getValue());delta.addProperty("owner",owner);delta.addProperty("key",key);delta.add("mod",meta);delta.add("record",mod.getAsJsonObject("files").get(key));
        String serialized=delta.toString(),identity=owner+":"+key;if(serialized.equals(journalRecords.get(identity)))return;Path directory=bbs.resolve("model-import-journal");safeDirectories(directory);Path file=directory.resolve(hash(identity.getBytes(StandardCharsets.UTF_8))+".json");rejectLinks(file);publish(file,serialized.getBytes(StandardCharsets.UTF_8),Files.exists(file,NOFOLLOW)?hash(Files.readAllBytes(file)):null);journalRecords.put(identity,serialized);Performance.count("journal.commit",serialized.length());
    }
    private void recoverJournal(JsonObject state)throws Exception{
        Path directory=bbs.resolve("model-import-journal");if(!Files.isDirectory(directory,NOFOLLOW))return;rejectLinks(directory);try(var files=Files.list(directory)){for(Path file:files.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}[.]json")).sorted().toList()){rejectLinks(file);JsonObject delta=JsonParser.parseString(Files.readString(file)).getAsJsonObject();String owner=delta.get("owner").getAsString(),key=delta.get("key").getAsString();if(!file.getFileName().toString().equals(hash((owner+":"+key).getBytes(StandardCharsets.UTF_8))+".json"))throw new IOException("Invalid ownership journal");JsonObject mods=state.getAsJsonObject("mods"),mod=mods.has(owner)?mods.getAsJsonObject(owner):delta.getAsJsonObject("mod").deepCopy();if(!mod.has("files"))mod.add("files",new JsonObject());if(!mod.get("directory").equals(delta.getAsJsonObject("mod").get("directory")))throw new IOException("Journal directory changed");mods.add(owner,mod);mod.getAsJsonObject("files").add(key,delta.get("record"));}}
    }
    static void validate(byte[] bytes) throws IOException {
        try {
            JsonObject o = JsonParser.parseString(new String(GeoFormats.normalize(bytes), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!GeoFormats.isGeo(o)) throw new IllegalArgumentException("missing or malformed minecraft:geometry definition");
            JsonArray geometry = o.getAsJsonArray("minecraft:geometry");
            for (JsonElement g : geometry) {
                JsonObject description=g.getAsJsonObject().getAsJsonObject("description");
                if(!description.has("identifier")||!description.get("identifier").isJsonPrimitive()||!description.get("identifier").getAsJsonPrimitive().isString()||description.get("identifier").getAsString().isBlank())throw new IllegalArgumentException("geometry is missing description.identifier");
                if((description.has("texture_width")&&description.get("texture_width").getAsInt()<=0)||(description.has("texture_height")&&description.get("texture_height").getAsInt()<=0))throw new IllegalArgumentException("texture dimensions must be positive");
            }
        } catch (RuntimeException e) { throw new IOException("Invalid Geo JSON", e); }
    }
    static String safeName(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").replaceAll("[ .]+$", "").trim();
        if (s.isEmpty() || s.equals(".") || s.equals("..")) s = "unnamed";
        if (s.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")) s = "_" + s;
        if (s.length() > 90) s = s.substring(0, 72) + "_" + hash(s.getBytes(StandardCharsets.UTF_8)).substring(0, 12);
        return s;
    }
    private static String targetRelative(String relative) throws IOException {
        String[] parts = relative.split("/", -1); List<String> clean = new ArrayList<>();
        for (String p : parts) { if (p.isEmpty() || p.equals(".") || p.equals("..")) throw new IOException("Unsafe source path"); clean.add(safeName(p)); }
        String file = clean.remove(clean.size()-1);
        // Each Geo gets its own model directory; preserve the original file name.
        String modelName=file.toLowerCase(Locale.ROOT).endsWith(".geo.json")?file.substring(0,file.length()-9):file.substring(0,file.lastIndexOf('.'));
        clean.add(safeName(modelName));
        clean.add(safeName(modelName)+".geo.json");
        return String.join("/", clean);
    }
    private Path resolve(String directory, String relative) throws IOException {
        Path base = models.resolve(directory).normalize(); Path p = base.resolve(relative).normalize();
        if (!base.startsWith(models) || base.equals(models) || !p.startsWith(base) || p.equals(base) || relative.contains("\\")) throw new IOException("Unsafe index target");
        rejectLinks(p); return p;
    }
    private static String rootKey(Path root) { return root.toAbsolutePath().normalize().toUri().toString(); }
    private static String fold(String s) { return s.toLowerCase(Locale.ROOT); }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static void rejectLinks(Path path) throws IOException {
        for (Path p = path; p != null; p = p.getParent()) if (Files.isSymbolicLink(p)) throw new IOException("Symbolic link target refused: " + p);
    }
    private static void safeDirectories(Path p) throws IOException { rejectLinks(p); Files.createDirectories(p); rejectLinks(p); }
    private static void publish(Path target, byte[] bytes, String expected) throws Exception {
        rejectLinks(target);
        Path temp = Files.createTempFile(target.getParent(), ".bbsgeo-", ".tmp");
        try {
            Files.write(temp, bytes); if (!hash(Files.readAllBytes(temp)).equals(hash(bytes))) throw new IOException("Staging hash mismatch");
            if (target.getFileName().toString().endsWith(".geo.json")) validate(Files.readAllBytes(temp));
            if (expected == null) {
                // Hard-link publication is atomic and CREATE_NEW: ATOMIC_MOVE alone can replace an existing file.
                Files.createLink(target, temp);
            } else {
                if (!Files.exists(target, NOFOLLOW) || !hash(Files.readAllBytes(target)).equals(expected)) throw new IOException("Target changed during import");
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            ManagedWrites.published(target);
        } finally { Files.deleteIfExists(temp); }
    }
}
