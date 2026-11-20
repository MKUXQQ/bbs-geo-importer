package local.bbsgeoimporter;
import com.google.gson.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
/** Complete file census followed by evidence-bearing associations, never namespace ownership inference. */
public final class ResourceCatalog {
    public record Entry(String owner,String path,Path file,String kind) {}
    public record Edge(String model,String resource,String evidence) {}
    public final Map<String,Map<String,Entry>> entries=new TreeMap<>();
    public final Map<String,List<Edge>> bundles=new TreeMap<>();
    Map<String,Set<String>> textureConsumers(){Map<String,Set<String>> out=new TreeMap<>();for(var group:bundles.entrySet())for(var edge:group.getValue())if(edge.resource().endsWith(".png"))out.computeIfAbsent(edge.resource(),k->new TreeSet<>()).add(group.getKey());return out;}
    public final JsonObject report=new JsonObject();
    final Map<String,JsonObject> identities=new TreeMap<>();
    final List<GenericResourceResolver.Requirement> requirements=new ArrayList<>();
    private Map<String,List<GenericResourceResolver.Requirement>> requirementsIndex; private int indexedRequirements=-1;
    List<GenericResourceResolver.Requirement> requirementsFor(String model){if(requirementsIndex==null||indexedRequirements!=requirements.size()){indexedRequirements=requirements.size();Map<String,List<GenericResourceResolver.Requirement>> index=new HashMap<>();for(var r:requirements)index.computeIfAbsent(r.model(),k->new ArrayList<>()).add(r);requirementsIndex=index;}return requirementsIndex.getOrDefault(model,List.of());}
    final Map<String,String> defaultTextures=new HashMap<>();
    final Set<String> defaultTextureConflicts=new HashSet<>();
    final Map<String,JsonArray> layerPlans=new HashMap<>();
    private final Map<String,String> ownerDomains=new HashMap<>();
    private String resolutionDomain="loaded";
    String domain(String owner){return ownerDomains.getOrDefault(owner,"loaded");}
    void registerSources(List<GeoImporter.ModSource> sources){sources.forEach(s->ownerDomains.put(s.id(),s.resourceDomain()));}
    void invalidateRequirements(){requirementsIndex=null;indexedRequirements=-1;}
    private final Map<String,List<Entry>> resolvedReferences=new HashMap<>();
    private final Map<String,Set<Entry>> addresses=new HashMap<>();
    private final Map<String,Set<Entry>> referenceIndex=new HashMap<>();
    private void indexReference(String kind,String ref,Entry e){referenceIndex.computeIfAbsent(kind+":"+ref,k->new LinkedHashSet<>()).add(e);}
    private final JsonArray containers=new JsonArray(), events=new JsonArray();
    boolean complete(){if(report.has("incompleteSources"))return false;for(var v:containers)if(v.getAsJsonObject().has("error"))return false;for(var v:events)if(Set.of("effective_resource_unavailable","resolver_failed").contains(v.getAsJsonObject().get("status").getAsString()))return false;return true;}
    private ResourceCatalog(){}
    static ResourceCatalog merge(List<ResourceCatalog> catalogs){ResourceCatalog c=new ResourceCatalog();c.report.add("containers",c.containers);c.report.add("events",c.events);for(var part:catalogs){c.ownerDomains.putAll(part.ownerDomains);part.entries.forEach((owner,files)->c.entries.computeIfAbsent(owner,k->new TreeMap<>()).putAll(files));c.bundles.putAll(part.bundles);c.identities.putAll(part.identities);c.requirements.addAll(part.requirements);c.defaultTextures.putAll(part.defaultTextures);c.defaultTextureConflicts.addAll(part.defaultTextureConflicts);c.layerPlans.putAll(part.layerPlans);part.addresses.forEach((key,values)->c.addresses.computeIfAbsent(key,k->new LinkedHashSet<>()).addAll(values));for(var row:part.containers)if(!c.containers.contains(row))c.containers.add(row);for(var row:part.events)if(!c.events.contains(row))c.events.add(row);for(var r:part.report.entrySet()){if(r.getKey().equals("containers")||r.getKey().equals("events"))continue;if(r.getValue().isJsonArray()){JsonArray array=c.report.has(r.getKey())?c.report.getAsJsonArray(r.getKey()):new JsonArray();r.getValue().getAsJsonArray().forEach(array::add);c.report.add(r.getKey(),array);}else if(r.getValue().isJsonPrimitive()&&r.getValue().getAsJsonPrimitive().isNumber()){long previous=c.report.has(r.getKey())?c.report.get(r.getKey()).getAsLong():0;c.report.addProperty(r.getKey(),previous+r.getValue().getAsLong());}}}return c;}
    void addCachedResource(Entry entry,EffectiveResources effective)throws Exception{var files=entries.computeIfAbsent(entry.owner(),k->new TreeMap<>());if(files.containsKey(entry.path()))return;Entry e=effective==null?entry:new Entry(entry.owner(),entry.path(),effective.resolve(entry),entry.kind());files.put(e.path(),e);String address=ResourceEvidence.address(e);if(address!=null)addresses.computeIfAbsent(address,k->new LinkedHashSet<>()).add(e);}
    void reindexAddresses(){addresses.clear();for(var files:entries.values())for(var e:files.values()){String address=ResourceEvidence.address(e);if(address!=null)addresses.computeIfAbsent(address,k->new LinkedHashSet<>()).add(e);}}
    ResourceCatalog project(String owner){ResourceCatalog c=new ResourceCatalog();c.ownerDomains.putAll(ownerDomains);c.resolutionDomain=ownerDomains.getOrDefault(owner,"loaded");if(entries.containsKey(owner))c.entries.put(owner,entries.get(owner));for(var e:bundles.entrySet())if(e.getKey().startsWith(owner+":"))c.bundles.put(e.getKey(),e.getValue());identities.forEach((k,v)->{if(k.startsWith(owner+":"))c.identities.put(k,v);});requirements.stream().filter(r->r.model().startsWith(owner+":")).forEach(c.requirements::add);defaultTextures.forEach((k,v)->{if(k.startsWith(owner+":"))c.defaultTextures.put(k,v);});defaultTextureConflicts.stream().filter(k->k.startsWith(owner+":")).forEach(c.defaultTextureConflicts::add);layerPlans.forEach((k,v)->{if(k.startsWith(owner+":"))c.layerPlans.put(k,v);});for(var files:c.entries.values())for(var e:files.values()){String address=ResourceEvidence.address(e);if(address!=null)c.addresses.computeIfAbsent(address,k->new LinkedHashSet<>()).add(e);}for(var v:containers)if(v.getAsJsonObject().get("id").getAsString().equals(owner))c.containers.add(v);for(var v:events)if(v.getAsJsonObject().has("source")&&v.getAsJsonObject().get("source").getAsString().startsWith(owner+":"))c.events.add(v);c.report.add("containers",c.containers);c.report.add("events",c.events);return c;}
    JsonObject freeze(){
        Gson g=new Gson();JsonObject data=new JsonObject();JsonArray rows=new JsonArray();for(var files:entries.values())for(var e:files.values()){if(e.kind().equals("other"))continue;JsonObject row=new JsonObject();row.addProperty("owner",e.owner());row.addProperty("path",e.path());row.addProperty("kind",e.kind());row.addProperty("file",e.file().toUri().toString());rows.add(row);}data.add("entries",rows);data.add("bundles",g.toJsonTree(bundles));data.add("requirements",g.toJsonTree(requirements));data.add("defaults",g.toJsonTree(defaultTextures));data.add("conflicts",g.toJsonTree(defaultTextureConflicts));data.add("layers",g.toJsonTree(layerPlans));data.add("identities",g.toJsonTree(identities));data.add("report",report.deepCopy());return data;
    }
    static ResourceCatalog thaw(JsonObject data,List<GeoImporter.ModSource> sources)throws Exception{
        ResourceCatalog c=new ResourceCatalog();Map<String,GeoImporter.ModSource> owners=new HashMap<>();sources.forEach(s->{owners.put(s.id(),s);c.ownerDomains.put(s.id(),s.resourceDomain());});if(!sources.isEmpty()&&sources.stream().map(GeoImporter.ModSource::resourceDomain).distinct().count()==1)c.resolutionDomain=sources.get(0).resourceDomain();
        for(var value:data.getAsJsonArray("entries")){JsonObject r=value.getAsJsonObject();String owner=r.get("owner").getAsString(),path=r.get("path").getAsString();Path file=null;try{file=Path.of(java.net.URI.create(r.get("file").getAsString()));}catch(Exception ignored){}if(file==null||!Files.isRegularFile(file)){var source=owners.get(owner);if(source!=null)for(Path root:source.roots()){Path candidate=root.resolve(path);if(Files.isRegularFile(candidate)){file=candidate;break;}}}if(file==null||!Files.isRegularFile(file))throw new IOException("Cached source missing: "+owner+":"+path);Entry e=new Entry(owner,path,file,r.get("kind").getAsString());c.entries.computeIfAbsent(owner,k->new TreeMap<>()).put(path,e);String address=ResourceEvidence.address(e);if(address!=null)c.addresses.computeIfAbsent(address,k->new LinkedHashSet<>()).add(e);}
        Gson g=new Gson();for(var e:data.getAsJsonObject("bundles").entrySet()){List<Edge> edges=new ArrayList<>();e.getValue().getAsJsonArray().forEach(v->edges.add(g.fromJson(v,Edge.class)));c.bundles.put(e.getKey(),edges);}data.getAsJsonArray("requirements").forEach(v->c.requirements.add(g.fromJson(v,GenericResourceResolver.Requirement.class)));data.getAsJsonObject("defaults").entrySet().forEach(e->c.defaultTextures.put(e.getKey(),e.getValue().getAsString()));data.getAsJsonArray("conflicts").forEach(v->c.defaultTextureConflicts.add(v.getAsString()));data.getAsJsonObject("layers").entrySet().forEach(e->c.layerPlans.put(e.getKey(),e.getValue().getAsJsonArray()));data.getAsJsonObject("identities").entrySet().forEach(e->c.identities.put(e.getKey(),e.getValue().getAsJsonObject()));data.getAsJsonObject("report").entrySet().forEach(e->c.report.add(e.getKey(),e.getValue()));c.report.getAsJsonArray("containers").forEach(c.containers::add);c.report.getAsJsonArray("events").forEach(c.events::add);c.report.add("events",c.events);c.report.add("containers",c.containers);return c;
    }
    public ResourceCatalog(List<GeoImporter.ModSource> sources){this(sources,null);}
    public ResourceCatalog(List<GeoImporter.ModSource> sources,EffectiveResources effective) {
        Map<String,List<GeoImporter.ModSource>> domains=new TreeMap<>();
        for(var source:sources){ownerDomains.put(source.id(),source.resourceDomain());domains.computeIfAbsent(source.resourceDomain(),k->new ArrayList<>()).add(source);}
        if(domains.size()>1){
            List<ResourceCatalog> parts=new ArrayList<>();for(var domain:domains.entrySet())parts.add(new ResourceCatalog(domain.getValue(),domain.getKey().equals("loaded")?effective:null));
            ResourceCatalog merged=merge(parts);entries.putAll(merged.entries);bundles.putAll(merged.bundles);identities.putAll(merged.identities);requirements.addAll(merged.requirements);defaultTextures.putAll(merged.defaultTextures);defaultTextureConflicts.addAll(merged.defaultTextureConflicts);layerPlans.putAll(merged.layerPlans);merged.addresses.forEach((k,v)->addresses.put(k,v));merged.report.entrySet().forEach(e->report.add(e.getKey(),e.getValue()));merged.containers.forEach(containers::add);merged.events.forEach(events::add);report.add("containers",containers);report.add("events",events);ExternalDependencies.resolve(this);return;
        }
        if(!domains.isEmpty())resolutionDomain=domains.keySet().iterator().next();
        if(!resolutionDomain.equals("loaded"))effective=null;
        long start=System.nanoTime(); report.add("containers",containers); report.add("events",events);
        Map<String,Set<String>> owners=new HashMap<>();
        for(var s:sources) for(Path p:s.roots()) owners.computeIfAbsent(p.toUri().toString(),k->new TreeSet<>()).add(s.id());
        long censusStart=System.nanoTime();
        Set<String> scannedPhysicalRoots=new HashSet<>();
        for(var s:sources) {
            Map<String,Entry> files=entries.computeIfAbsent(s.id(),k->new TreeMap<>());
            Set<String> roots=new HashSet<>(), conflicted=new HashSet<>();
            for(Path root:s.roots()) {
                if(!roots.add(root.toUri().toString())) continue;
                JsonObject c=new JsonObject(); c.addProperty("id",s.id()); c.addProperty("name",s.name()); c.addProperty("version",s.version()); c.addProperty("root",root.toUri().toString());c.addProperty("kind",s.kind());c.addProperty("displayModId",s.displayModId());c.addProperty("resourceDomain",s.resourceDomain());c.addProperty("containerPath",s.containerPath()); containers.add(c);
                JsonObject counts=new JsonObject(); c.add("counts",counts); int total=0;
                boolean shared=owners.get(root.toUri().toString()).size()>1;
                if(shared&&!scannedPhysicalRoots.add(root.toUri().toString())){c.addProperty("ownership","shared_container_alias");c.addProperty("status","enumerated_via_container");c.add("logicalMods",new Gson().toJsonTree(owners.get(root.toUri().toString())));continue;}
                c.addProperty("ownership",shared?"shared_container":"loader_container");
                try {
                    for(Path p:SourceCensus.files(root)) {
                        total++; String rel=root.relativize(p).toString().replace('\\','/'); String kind=kind(rel,p);
                        counts.addProperty(kind,counts.has(kind)?counts.get(kind).getAsInt()+1:1);
                        Entry e=new Entry(s.id(),rel,p,kind);
                        if(effective!=null&&rel.startsWith("assets/")&&(rel.toLowerCase(Locale.ROOT).endsWith(".json")||kind.equals("texture")))try{e=new Entry(s.id(),rel,effective.resolve(e),kind);}catch(Exception ex){event(e,"effective_resource_unavailable",ex.toString());continue;}
                        if(shared){identityContainer(e,owners.get(root.toUri().toString()));}
                        if(conflicted.contains(rel)) continue;
                        JsonObject identity=new JsonObject();identity.addProperty("logicalOwner",s.id());identity.addProperty("physicalContainer",root.toUri().toString());identity.addProperty("physicalFile",p.toUri().toString());identity.addProperty("kind",s.kind());identity.addProperty("resourceDomain",s.resourceDomain());identity.addProperty("displayModId",s.displayModId());identity.addProperty("version",s.version());
                        String address=ResourceEvidence.address(e);if(address!=null)identity.addProperty("address",address);
                        var actual=effective==null?null:effective.provenance(e);if(actual!=null)identity.add("effective",actual);else identity.addProperty("supplierStatus","RAW_CONTAINER_ONLY_RUNTIME_PRIORITY_UNVERIFIED");
                        identities.putIfAbsent(key(e),identity);
                        Entry old=files.putIfAbsent(rel,e);
                        if(old!=null) {
                            if(!GeoImporter.hash(AnalysisInputs.bytes(old.file())).equals(GeoImporter.hash(AnalysisInputs.bytes(e.file())))) { files.remove(rel); conflicted.add(rel); event(e,"root_conflict","Same path differs across roots"); }
                            else event(e,"duplicate","Identical source path across roots");
                        }
                    }
                    c.addProperty("status","scanned");
                } catch(Exception ex) {c.addProperty("status","failed"); c.addProperty("error",ex.toString());}
                c.addProperty("totalFiles",total);
            }
        }
        report.addProperty("censusMillis",(System.nanoTime()-censusStart)/1_000_000);
        long indexStart=System.nanoTime();
        for(var files:entries.values())for(var e:files.values())if(!e.kind().equals("other")) {
            if(e.kind().equals("geo"))bundles.put(key(e),new ArrayList<>());
            String address=ResourceEvidence.address(e);
            if(address!=null)addresses.computeIfAbsent(address,k->new LinkedHashSet<>()).add(e);
            String resource=e.path().startsWith("assets/")?e.path().substring(7):e.path();int slash=resource.indexOf('/');
            String id=slash<0?resource:resource.substring(0,slash)+":"+resource.substring(slash+1);
            indexReference(e.kind(),"id/"+id,e);indexReference(e.kind(),e.owner()+"/"+e.path(),e);
            indexReference(e.kind(),e.owner()+"/"+resource.substring(Math.max(0,slash+1)),e);
            if(e.kind().equals("geo"))try {
                var geometry=GeoFormats.json(e.file()).getAsJsonObject().getAsJsonArray("minecraft:geometry");
                if(geometry!=null)for(var g:geometry){var o=g.getAsJsonObject();if(o.has("description")&&o.getAsJsonObject("description").has("identifier"))indexReference("geo",e.owner()+"/"+o.getAsJsonObject("description").get("identifier").getAsString(),e);}
            }catch(Exception ignored) { /* Import validation reports invalid Geo separately. */ }
        }
        report.addProperty("resourceIndexMillis",(System.nanoTime()-indexStart)/1_000_000);
        long resolverStart=System.nanoTime();new GenericResourceResolver().resolve(this);
        report.addProperty("jsonAssociationMillis",(System.nanoTime()-resolverStart)/1_000_000);
        resolverStart=System.nanoTime();
        for(var resolver:ServiceLoader.load(ResourceAssociationResolver.class))try{resolver.resolve(this);}catch(Exception | LinkageError ex){JsonObject error=new JsonObject();error.addProperty("source",resolver.getClass().getName());error.addProperty("status","resolver_failed");error.addProperty("reason",ex.toString());events.add(error);}
        StaticModelReferences.analyze(this);report.addProperty("literalBytecodeMillis",(System.nanoTime()-resolverStart)/1_000_000);
        resolverStart=System.nanoTime();GeckoDefaults.analyze(this);report.addProperty("geckoBytecodeMillis",(System.nanoTime()-resolverStart)/1_000_000);
        resolverStart=System.nanoTime();ConstantModelReferences.analyze(this);report.addProperty("constantBytecodeMillis",(System.nanoTime()-resolverStart)/1_000_000);
        resolverStart=System.nanoTime();CobblemonResolvers.analyze(this);report.addProperty("cobblemonResolverMillis",(System.nanoTime()-resolverStart)/1_000_000);
        UvSharedTextures.analyzePoses(this);
        UvSharedTextures.analyze(this);
        resolverStart=System.nanoTime();TextureCandidates.append(this);report.addProperty("candidateDiagnosticsMillis",(System.nanoTime()-resolverStart)/1_000_000);
        report.addProperty("elapsedMillis",(System.nanoTime()-start)/1_000_000);Performance.end("catalog.build",start);for(String key:List.of("censusMillis","resourceIndexMillis","jsonAssociationMillis","literalBytecodeMillis","geckoBytecodeMillis","constantBytecodeMillis","cobblemonResolverMillis","candidateDiagnosticsMillis"))Performance.event("catalog."+key,report.get(key).getAsLong()*1_000_000,0);
    }
    private void identityContainer(Entry entry,Set<String> mods){if(entry.kind().equals("geo"))event(entry,"shared_container","Logical Mods: "+mods+"; container representative="+entry.owner());}
    public Optional<Entry> uniqueResource(String address,String kind) {
        try { address=ResourceEvidence.address(address); } catch(IllegalArgumentException|NullPointerException invalid) { return Optional.empty(); }
        var matches=addresses.getOrDefault(address,Set.of()).stream().filter(e->e.kind().equals(kind)&&ownerDomains.getOrDefault(e.owner(),"loaded").equals(resolutionDomain)).toList();
        return matches.size()==1?Optional.of(matches.get(0)):Optional.empty();
    }
    public boolean observe(ResourceEvidence.Observation observation,long generation) {
        if(observation.generation()!=generation)return false;
        var model=uniqueResource(observation.model(),"geo");
        if(model.isEmpty())return false;
        List<Edge> additions=new ArrayList<>();
        for(String kind:List.of("texture","animation")) {
            String address=kind.equals("texture")?observation.texture():observation.animation();
            if(address==null)continue;
            var resource=uniqueResource(address,kind);
            if(resource.isEmpty()){event(model.get(),"runtime_resource_unresolved",address);return false;}
            additions.add(new Edge(key(model.get()),key(resource.get()),"runtime:"+observation.adapter()));
        }
        var edges=bundles.get(key(model.get()));
        for(var edge:additions)if(!edges.contains(edge))edges.add(edge);
        if(observation.texture()!=null){
            String modelKey=key(model.get()),textureKey=additions.stream().filter(e->e.resource().endsWith(".png")).map(Edge::resource).findFirst().orElse(null);
            if(textureKey!=null){String previous=defaultTextures.putIfAbsent(modelKey,textureKey);if(previous!=null&&!previous.equals(textureKey)){defaultTextureConflicts.add(modelKey);event(model.get(),"runtime_texture_variants","Multiple observed textures; default selection requires context");}}
        }
        return true;
    }
    static String kind(String p) { String s=p.toLowerCase(Locale.ROOT); return s.endsWith(".geo.json")?"geo":s.endsWith(".animation.json")?"animation":s.endsWith(".png")?"texture":s.endsWith(".tbl")?"unsupported_tbl":"other"; }
    static String kind(String path,Path file) {String extension=kind(path);return extension.equals("other")?GeoFormats.classify(path,file):extension;}
    public static String key(Entry e) {return e.owner()+":"+e.path();}
    public void dependency(Entry model,String kind,String reference,String status,List<Entry> candidates,String evidence,String role) {
        if(!bundles.containsKey(key(model))||!List.of("texture","animation").contains(kind)||!List.of("FOUND","MISSING","AMBIGUOUS","UNRESOLVED","INVALID","UNSUPPORTED").contains(status))throw new IllegalArgumentException("Invalid dependency");
        if(status.equals("FOUND")&&(candidates.size()!=1||!candidates.get(0).kind().equals(kind)))throw new IllegalArgumentException("FOUND requires one matching resource");
        var requirement=new GenericResourceResolver.Requirement(key(model),kind,reference,status,candidates.stream().map(ResourceCatalog::key).toList(),evidence,role);if(!requirements.contains(requirement))requirements.add(requirement);
        if(status.equals("FOUND")){var edge=new Edge(key(model),key(candidates.get(0)),evidence);if(!bundles.get(key(model)).contains(edge))bundles.get(key(model)).add(edge);}
    }
    void event(Entry e,String status,String reason) {JsonObject o=new JsonObject(); o.addProperty("source",key(e)); o.addProperty("status",status); o.addProperty("reason",reason); events.add(o);}
    private void inspectDefinition(Entry definition,JsonElement data) throws IOException {
        // Explicit resource definition groups: geometry/model + textures + animations. No arbitrary string co-occurrence.
        if(data.isJsonObject()) {
            JsonObject o=data.getAsJsonObject();
            if(o.has("geometry") || o.has("model") || definition.kind().equals("geo")) {
                Set<Entry> models=new LinkedHashSet<>(), deps=new LinkedHashSet<>();
                boolean complete=true;
                if(definition.kind().equals("geo")) models.add(definition);
                if(o.has("geometry")) complete &= resolveValues(definition,o.get("geometry"),"geo",models);
                if(o.has("model")) complete &= resolveValues(definition,o.get("model"),"geo",models);
                if(o.has("textures")) resolveValues(definition,o.get("textures"),"texture",deps);
                if(o.has("texture")) resolveValues(definition,o.get("texture"),"texture",deps);
                if(o.has("animations")) resolveValues(definition,o.get("animations"),"animation",deps);
                if(o.has("animation")) resolveValues(definition,o.get("animation"),"animation",deps);
                if(!complete) event(definition,"incomplete_definition","At least one geometry reference is unresolved; dependencies not assigned");
                else if(models.size()==1) {
                    Entry model=models.iterator().next();
                    for(Entry dep:deps) bundles.get(key(model)).add(new Edge(key(model),key(dep),"explicit_definition:"+key(definition)));
                } else if(models.size()>1) event(definition,"ambiguous_definition","Multiple geometries in one group; texture/animation pairing not proven");
            }
            for(var x:o.entrySet()) inspectDefinition(definition,x.getValue());
        } else if(data.isJsonArray()) for(var x:data.getAsJsonArray()) inspectDefinition(definition,x);
    }
    private static String normalize(String path) {
        Deque<String> parts=new ArrayDeque<>();
        for(String p:path.replace('\\','/').split("/")) {
            if(p.equals("..")){if(parts.isEmpty())return null;parts.removeLast();}
            else if(!p.equals(".")&&!p.isEmpty())parts.add(p);
        }
        return String.join("/",parts);
    }
    private boolean resolveValues(Entry def,JsonElement value,String kind,Set<Entry> out) throws IOException {
        if(value.isJsonObject()) {boolean complete=value.getAsJsonObject().size()!=0;for(var x:value.getAsJsonObject().entrySet()) complete &= resolveValues(def,x.getValue(),kind,out);return complete;}
        if(value.isJsonArray()) {boolean complete=value.getAsJsonArray().size()!=0;for(var x:value.getAsJsonArray()) complete &= resolveValues(def,x,kind,out);return complete;}
        if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())return false;
        String ref=value.getAsString(); String cacheKey=key(def)+":"+kind+":"+ref;
        List<Entry> matches=resolvedReferences.get(cacheKey);
        if(matches==null) { matches=new ArrayList<>();
        matches.addAll(referenceIndex.getOrDefault(kind+":id/"+ref,Set.of()));
        matches.addAll(referenceIndex.getOrDefault(kind+":"+def.owner()+"/"+ref,Set.of()));
        if(matches.isEmpty() && !ref.contains(":")) {
            String suffix=kind.equals("texture")?".png":kind.equals("animation")?".animation.json":".geo.json";
            String expanded=ref.toLowerCase(Locale.ROOT).endsWith(suffix)?ref:ref+suffix;
            String base=def.path().contains("/")?def.path().substring(0,def.path().lastIndexOf('/')+1):"";
            String relative=normalize(base+expanded);
            var own=entries.getOrDefault(def.owner(),Map.of());
            if(relative!=null && own.containsKey(relative) && own.get(relative).kind().equals(kind))matches.add(own.get(relative));
            if(matches.isEmpty()) {
                String address=ResourceEvidence.address(def);
                if(address!=null)matches.addAll(referenceIndex.getOrDefault(kind+":id/"+address.substring(0,address.indexOf(':')+1)+expanded,Set.of()));
            }
        }
        matches=new ArrayList<>(new LinkedHashSet<>(matches));
        resolvedReferences.put(cacheKey,List.copyOf(matches));
        }
        if(matches.size()==1) out.add(matches.get(0));
        else {
            String lower=ref.toLowerCase(Locale.ROOT);
            boolean supported=kind.equals("geo")?(lower.endsWith(".geo.json") || ref.startsWith("geometry.")):kind.equals("texture")?lower.endsWith(".png"):lower.endsWith(".animation.json");
            event(def,!matches.isEmpty()?"ambiguous_reference":supported?"missing_reference":"unsupported_reference",kind+":"+ref);
        }
        return matches.size()==1;
    }
}
