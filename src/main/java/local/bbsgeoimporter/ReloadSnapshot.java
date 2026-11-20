package local.bbsgeoimporter;
import java.nio.file.*;
import java.util.*;

/** Captures only bytes and supplier identity; never retains the live ResourceManager. */
public record ReloadSnapshot(long generation,List<GeoImporter.ModSource> sources,EffectiveResources.Reader reader,Map<Path,String> fingerprints,SourceCensus.View census) {
    public ReloadSnapshot(long generation,List<GeoImporter.ModSource> sources,EffectiveResources.Reader reader){this(generation,sources,reader,SourceFingerprint.snapshot(),SourceCensus.snapshot());}
    public static ReloadSnapshot capture(long epoch,List<GeoImporter.ModSource> sources,EffectiveResources.Reader live,Path cache)throws Exception{
        if(live==null)return new ReloadSnapshot(epoch,List.copyOf(sources),null);
        long timing=Performance.start();String signature;try{signature=live.fingerprint();}catch(Exception ex){signature=null;}if(signature!=null)signature=SourceFingerprint.sources(sources.stream().filter(s->!s.external()).toList())+signature;
        for(Path p=cache;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new java.io.IOException("Unsafe snapshot path");Files.createDirectories(cache);
        Path manifest=cache.resolve("snapshot.json");var previous=new com.google.gson.JsonObject();
        if(Files.isRegularFile(manifest,LinkOption.NOFOLLOW_LINKS))try{
            previous=com.google.gson.JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
            if(signature!=null&&previous.has("fingerprint")&&signature.equals(previous.get("fingerprint").getAsString())){
                var snapshot=restore(epoch,sources,cache,previous);Performance.end("snapshot.reuse",timing);return snapshot;
            }
        }catch(Exception ignored){}
        Set<String> addresses=new TreeSet<>(),prefixes=new TreeSet<>(List.of("geo","models","textures","animations","bedrock"));
        for(var source:sources)if(!source.external())for(Path root:source.roots()){
            for(Path file:SourceCensus.files(root)){
                String path=root.relativize(file).toString().replace('\\','/');
                if(!(path.endsWith(".json")||path.endsWith(".png")))continue;
                String address=ResourceEvidence.address(new ResourceCatalog.Entry(source.id(),path,file,"other"));
                if(address!=null){addresses.add(address);String part=address.substring(address.indexOf(':')+1);int slash=part.indexOf('/');if(slash>0)prefixes.add(part.substring(0,slash));}
            }
        }
        addresses.addAll(live.addresses(prefixes));
        for(Path p=cache;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new java.io.IOException("Unsafe snapshot path");
        Files.createDirectories(cache);
        var rows=new com.google.gson.JsonObject();var oldRows=previous.getAsJsonObject("resources");
        for(String address:addresses){
            if(epoch!=RuntimeCapture.generation())throw new java.io.IOException("Expired snapshot preparation");
            String version=null;try{version=live.version(address);}catch(Exception ignored){}
            if(version!=null&&oldRows!=null&&oldRows.has(address)){
                var old=oldRows.getAsJsonObject(address);
                if(old.has("version")&&version.equals(old.get("version").getAsString())&&validBlob(cache,old.get("file").getAsString())){
                    rows.add(address,old);Performance.count("snapshot.addressReuse",0);continue;
                }
            }
            var value=live.read(epoch,address);if(value==null)continue;
            if(value.generation()!=epoch||!address.equals(value.address()))throw new java.io.IOException("Invalid snapshot identity");
            String hash=GeoImporter.hash(value.content()),name=hash+".resource";
            if(!validBlob(cache,name)||!SourceFingerprint.digest(cache.resolve(name)).equals(hash)){
                Path tmp=Files.createTempFile(cache,"resource-",".tmp");try{Files.write(tmp,value.content());Files.move(tmp,cache.resolve(name),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}
                Performance.count("snapshot.write",value.content().length);
            }
            Performance.count("snapshot.read",value.content().length);var row=new com.google.gson.JsonObject();row.addProperty("file",name);row.addProperty("supplier",value.supplier());if(version!=null)row.addProperty("version",version);rows.add(address,row);
        }
        var data=new com.google.gson.JsonObject();data.addProperty("fingerprint",signature);data.add("resources",rows);Path temp=Files.createTempFile(cache,"snapshot-",".tmp");try{Files.writeString(temp,data.toString());Files.move(temp,manifest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}Performance.end("snapshot.capture",timing);return restore(epoch,sources,cache,data);
    }
    private static boolean validBlob(Path cache,String name){return name.matches("[a-f0-9]{64}[.]resource")&&Files.isRegularFile(cache.resolve(name),LinkOption.NOFOLLOW_LINKS);}
    private static ReloadSnapshot restore(long epoch,List<GeoImporter.ModSource> sources,Path cache,com.google.gson.JsonObject data)throws Exception{
        Map<String,Path> files=new HashMap<>();Map<String,String> suppliers=new HashMap<>();StringBuilder signature=new StringBuilder();for(var e:new java.util.TreeMap<String,com.google.gson.JsonElement>(data.getAsJsonObject("resources").asMap()).entrySet()){var row=e.getValue().getAsJsonObject();String name=row.get("file").getAsString();if(!validBlob(cache,name))throw new java.io.IOException("Invalid snapshot target");files.put(e.getKey(),cache.resolve(name));suppliers.put(e.getKey(),row.get("supplier").getAsString());signature.append(e.getKey()).append('|').append(name).append('|').append(suppliers.get(e.getKey())).append('\n');}String identity=GeoImporter.hash(signature.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));var captured=Map.copyOf(files);var identities=Map.copyOf(suppliers);
        EffectiveResources.Reader frozen=new EffectiveResources.Reader(){
            public String fingerprint(){return identity;}
            public String fingerprint(Set<String> addresses){StringBuilder b=new StringBuilder();for(String address:new TreeSet<>(addresses)){Path p=captured.get(address);b.append(address).append('|').append(p==null?"missing":p.getFileName()).append('|').append(identities.get(address)).append('\n');}return GeoImporter.hash(b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            public ResourceEvidence.Snapshot read(long g,String a)throws Exception{
                if(g!=epoch)throw new java.io.IOException("Expired disk snapshot");
                Path file=captured.get(a);if(file==null)return null;byte[] bytes=Files.readAllBytes(file);
                if(!GeoImporter.hash(bytes).equals(file.getFileName().toString().substring(0,64)))throw new java.io.IOException("Snapshot content changed: "+a);
                return new ResourceEvidence.Snapshot(g,a,identities.get(a),bytes);
            }
            public Path frozenFile(String address){return captured.get(address);}
            public String frozenSupplier(String address){return identities.get(address);}
            public Set<String> addresses(){return captured.keySet();}
        };
        return new ReloadSnapshot(epoch,List.copyOf(sources),frozen);
    }
}
