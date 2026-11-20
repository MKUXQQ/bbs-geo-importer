package local.bbsgeoimporter;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
/** Conservative source dependency components. Shared namespaces and bytecode references join owners. */
final class CatalogCache {
    static ResourceCatalog load(List<GeoImporter.ModSource> input,EffectiveResources effective,Path directory)throws Exception {
        Map<String,List<GeoImporter.ModSource>> domains=new TreeMap<>();
        for(var source:input)domains.computeIfAbsent(source.resourceDomain(),k->new ArrayList<>()).add(source);
        List<ResourceCatalog> result=new ArrayList<>();
        for(var domain:domains.entrySet()){
            try{result.add(loadDomain(domain.getValue(),domain.getKey().equals("loaded")?effective:null,directory));}
            catch(Exception ex){if(domain.getKey().equals("loaded"))throw ex;var isolated=new ResourceCatalog(domain.getValue(),null);isolated.report.addProperty("domainCacheFailure",ex.toString());result.add(isolated);}
        }
        return ExternalDependencies.resolve(ResourceCatalog.merge(result));
    }
    private static ResourceCatalog loadDomain(List<GeoImporter.ModSource> input,EffectiveResources effective,Path directory)throws Exception {
        Files.createDirectories(directory);List<GeoImporter.ModSource> sources=input.stream().filter(s->!s.id().equals("bbs_geo_importer")).toList();Map<String,JsonObject> facts=new TreeMap<>();Map<String,String> fingerprints=new TreeMap<>();Map<Path,String> roots=new HashMap<>();
        for(var s:sources){
            StringBuilder fp=new StringBuilder(s.id()+s.name()+s.version()+s.kind()+s.displayModId()+s.containerPath()+s.resourceDomain());for(Path p:s.roots()){String f=roots.get(p);if(f==null){f=SourceFingerprint.cachedRoot(p);roots.put(p,f);}fp.append(f);}
            String hash=GeoImporter.hash(fp.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));fingerprints.put(s.id(),hash);
            Path path=directory.resolve(hash+".source5.json");JsonObject data=null;
            if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))try{
                var cached=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                if(!cached.has("error")&&cached.has("hasGeo")&&cached.has("resources")&&cached.has("provided")&&cached.has("references"))data=cached;
            }catch(RuntimeException|java.io.IOException ignored){}
            if(data==null){
                data=footprint(s);
                if(s.roots().stream().anyMatch(SourceCensus::failed))throw new java.io.IOException("Incomplete footprint for "+s.id());
                write(path,data);
            }
            facts.put(s.id(),data);
        }
        Map<String,List<GeoImporter.ModSource>> groups=dependencyGroups(sources,facts);
        List<ResourceCatalog> catalogs=new ArrayList<>();String effectiveId=effective==null?"raw":effective.fingerprint();
        record Pending(String owner,List<GeoImporter.ModSource> group,Path file){}List<Pending> pending=new ArrayList<>();
        for(var groupItem:groups.entrySet()){String target=groupItem.getKey();var group=groupItem.getValue();StringBuilder b=new StringBuilder("component-schema-"+SourceFingerprint.PARSER_SCHEMA+"|"+componentIdentity(group,facts,effective));group.forEach(s->b.append(fingerprints.get(s.id())));String id=GeoImporter.hash(b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));Path path=directory.resolve(target+"-"+id+".catalog.json");ResourceCatalog catalog=null;if(effectiveId!=null&&Files.isRegularFile(path)&&!Files.isSymbolicLink(path))try{var candidate=ResourceCatalog.thaw(JsonParser.parseString(Files.readString(path)).getAsJsonObject(),group);if(candidate.complete()){catalog=candidate;Performance.count("component.reuse",group.size());}}catch(Exception ignored){}if(catalog==null)pending.add(new Pending(target,group,path));else catalogs.add(catalog);}
        if(!pending.isEmpty()){Set<String> owners=new HashSet<>();for(var item:pending)for(var s:item.group())owners.add(s.id());var selected=sources.stream().filter(s->owners.contains(s.id())).toList();ResourceCatalog built=new ResourceCatalog(selected,effective);boolean complete=built.complete()&&!SourceCensus.incomplete();for(var item:pending){ResourceCatalog slice=built.project(item.owner());for(var dependency:item.group())if(built.entries.containsKey(dependency.id()))slice.entries.put(dependency.id(),built.entries.get(dependency.id()));slice.reindexAddresses();Performance.count("component.build",item.group().size());if(!complete)slice.report.addProperty("incompleteSources",1);else if(effectiveId!=null)write(item.file(),slice.freeze());catalogs.add(slice);}}
        ResourceCatalog result=ResourceCatalog.merge(catalogs);result.registerSources(sources);for(var s:sources)if(!facts.get(s.id()).get("hasGeo").getAsBoolean()){for(var row:facts.get(s.id()).getAsJsonArray("resources")){var r=row.getAsJsonObject();String path=r.get("path").getAsString();for(Path root:s.roots())if(Files.isRegularFile(root.resolve(path))){try{result.addCachedResource(new ResourceCatalog.Entry(s.id(),path,root.resolve(path),r.get("kind").getAsString()),effective);}catch(Exception ex){result.event(new ResourceCatalog.Entry(s.id(),path,root.resolve(path),r.get("kind").getAsString()),"unavailable_resource",ex.toString());}break;}}}return result;
    }
    private static String componentIdentity(List<GeoImporter.ModSource> group,Map<String,JsonObject> facts,EffectiveResources effective)throws Exception{if(effective==null)return "raw";Set<String> addresses=new TreeSet<>();for(var s:group)for(var v:facts.get(s.id()).getAsJsonArray("resources")){var row=v.getAsJsonObject();String address=ResourceEvidence.address(new ResourceCatalog.Entry(s.id(),row.get("path").getAsString(),s.roots().get(0),row.get("kind").getAsString()));if(address!=null)addresses.add(address);}return effective.fingerprint(addresses);}
    private static JsonObject footprint(GeoImporter.ModSource source)throws Exception {JsonArray resources=new JsonArray();Set<String> provided=new TreeSet<>(),references=new TreeSet<>();boolean hasGeo=false;List<Path> textual=new ArrayList<>();for(Path root:source.roots()){for(Path p:SourceCensus.files(root)){String rel=root.relativize(p).toString().replace('\\','/');String kind=ResourceCatalog.kind(rel,p);if(kind.equals("geo"))hasGeo=true;if(!kind.equals("other")||rel.endsWith(".json")){JsonObject row=new JsonObject();row.addProperty("path",rel);row.addProperty("kind",kind);resources.add(row);}if(rel.startsWith("assets/")){String[] parts=rel.split("/",3);if(parts.length==3)provided.add("ns:"+parts[1]);}if(rel.endsWith(".class")){String name=rel.substring(0,rel.length()-6);provided.add("class:"+name);textual.add(p);}else if(rel.endsWith(".json"))textual.add(p);}}
        var ns=java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-zA-Z0-9_/.-]+");var cls=java.util.regex.Pattern.compile("(?:[a-zA-Z_$][a-zA-Z0-9_$]*/)+[a-zA-Z_$][a-zA-Z0-9_$]*");for(Path p:textual){byte[] bytes=AnalysisInputs.bytes(p);Performance.count("component.footprintRead",bytes.length);String text=new String(bytes,java.nio.charset.StandardCharsets.ISO_8859_1);var matcher=ns.matcher(text);while(matcher.find()){String value=matcher.group();references.add("ns:"+value.substring(0,value.indexOf(':')));}matcher=cls.matcher(text);while(matcher.find()){String value=matcher.group();references.add("class:"+value);if(value.startsWith("assets/")){String[] parts=value.split("/",3);if(parts.length==3)references.add("ns:"+parts[1]);}}// Standalone namespace constructor strings are also dependencies.
            var words=java.util.regex.Pattern.compile("[a-z0-9_.-]{2,}").matcher(text);while(words.find())references.add("ns:"+words.group());}
        references.addAll(provided);JsonObject data=new JsonObject();data.add("resources",resources);data.addProperty("hasGeo",hasGeo);data.add("provided",new Gson().toJsonTree(provided));data.add("references",new Gson().toJsonTree(references));return data;
    }
    static Map<String,List<GeoImporter.ModSource>> dependencyGroups(List<GeoImporter.ModSource> sources,Map<String,JsonObject> facts){
        long start=Performance.start(),tokens=0;
        Map<String,Set<String>> providers=new HashMap<>(),outgoing=new HashMap<>(),incoming=new HashMap<>();
        Map<Path,Set<String>> mounted=new HashMap<>();
        for(var source:sources){
            for(Path root:source.roots())mounted.computeIfAbsent(root,k->new HashSet<>()).add(source.id());
            for(var token:facts.get(source.id()).getAsJsonArray("provided")){
                providers.computeIfAbsent(token.getAsString(),k->new HashSet<>()).add(source.id());tokens++;
            }
        }
        for(var source:sources){
            Set<String> deps=new HashSet<>();
            for(var ref:facts.get(source.id()).getAsJsonArray("references")){
                Set<String> matches=providers.get(ref.getAsString());if(matches!=null)deps.addAll(matches);tokens++;
            }
            outgoing.put(source.id(),deps);
            for(String provider:deps)incoming.computeIfAbsent(provider,k->new HashSet<>()).add(source.id());
        }
        Map<String,List<GeoImporter.ModSource>> groups=new TreeMap<>();
        for(var owner:sources){
            if(!ModelManagement.enabled(owner.id())||!facts.get(owner.id()).get("hasGeo").getAsBoolean())continue;
            // Incoming definitions and co-mounted sources seed this owner only; follow outgoing dependencies transitively.
            Set<String> closure=new HashSet<>(incoming.getOrDefault(owner.id(),Set.of()));
            closure.add(owner.id());for(Path root:owner.roots())closure.addAll(mounted.get(root));
            var todo=new ArrayDeque<>(closure);
            while(!todo.isEmpty())for(String id:outgoing.get(todo.removeFirst()))if(closure.add(id))todo.addLast(id);
            groups.put(owner.id(),sources.stream().filter(s->closure.contains(s.id())).toList());
        }
        Performance.end("component.groupClosure",start);Performance.count("component.footprintTokens",tokens);
        return groups;
    }
    
    
    private static void write(Path file,JsonObject data)throws Exception{if(Files.isSymbolicLink(file))throw new java.io.IOException("Unsafe parsed cache");Path temp=Files.createTempFile(file.getParent(),"parse-",".tmp");try{Files.writeString(temp,data.toString());Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}
}
