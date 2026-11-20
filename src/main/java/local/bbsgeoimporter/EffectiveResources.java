package local.bbsgeoimporter;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** A reload-scoped resource view. Adapters must open and close streams inside read(). */
public final class EffectiveResources {
    @FunctionalInterface public interface Reader { ResourceEvidence.Snapshot read(long generation,String address) throws Exception; default Object identity(){return this;} default String fingerprint()throws Exception{return null;} default String fingerprint(Set<String> addresses)throws Exception{return fingerprint();} default Set<String> addresses()throws Exception{return Set.of();} default Set<String> addresses(Set<String> prefixes)throws Exception{return addresses();} default String version(String address)throws Exception{return null;} default Path frozenFile(String address){return null;} default String frozenSupplier(String address){return null;} }
    String fingerprint(Set<String> addresses)throws Exception{return reader==null?"raw":reader.fingerprint(addresses);}
    String fingerprint()throws Exception{return reader==null?"raw":reader.fingerprint();}
    private final long generation;
    private final Reader reader;
    private final Path cache;
    private final Map<String,Path> materialized=new HashMap<>();
    private final Set<String> externalOwners=new HashSet<>();
    void sources(List<GeoImporter.ModSource> sources){for(var s:sources)if(s.external())externalOwners.add(s.id());}
    private final Map<String,com.google.gson.JsonObject> provenance=new HashMap<>();
    public com.google.gson.JsonObject provenance(ResourceCatalog.Entry entry){String address=ResourceEvidence.address(entry);var value=provenance.get(address);return value==null?null:value.deepCopy();}
    private void remember(ResourceEvidence.Snapshot snapshot){var value=new com.google.gson.JsonObject();value.addProperty("address",snapshot.address());value.addProperty("supplier",snapshot.supplier());value.addProperty("generation",snapshot.generation());value.addProperty("effectiveHash",GeoImporter.hash(snapshot.content()));provenance.put(snapshot.address(),value);}
    public EffectiveResources(long generation,Reader reader,Path cache){this.generation=generation;this.reader=reader;this.cache=cache;}
    public List<GeoImporter.ModSource> additionalSources(List<GeoImporter.ModSource> sources)throws Exception{
        if(reader==null)return List.of();long timing=Performance.start();Set<String> known=new HashSet<>();String signature=SourceFingerprint.sources(sources);Path census=cache.resolve("container-addresses.json");boolean reused=false;if(Files.isRegularFile(census)&&!Files.isSymbolicLink(census))try{var data=com.google.gson.JsonParser.parseString(Files.readString(census)).getAsJsonObject();if(signature.equals(data.get("fingerprint").getAsString())){data.getAsJsonArray("addresses").forEach(v->known.add(v.getAsString()));reused=true;}}catch(Exception ignored){}if(!reused)for(var s:sources)for(Path root:s.roots()){for(Path p:SourceCensus.files(root)){String rel=root.relativize(p).toString().replace('\\','/');String address=ResourceEvidence.address(new ResourceCatalog.Entry(s.id(),rel,p,"other"));if(address!=null)known.add(address);}}
        if(!reused){Files.createDirectories(cache);var data=new com.google.gson.JsonObject();data.addProperty("fingerprint",signature);data.add("addresses",new com.google.gson.Gson().toJsonTree(known));Path temp=Files.createTempFile(cache,"addresses-",".tmp");try{Files.writeString(temp,data.toString());Files.move(temp,census,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}Set<String> prefixes=new TreeSet<>(List.of("geo","models","textures","animations","bedrock"));
        for(String address:known){String path=address.substring(address.indexOf(':')+1);int slash=path.indexOf('/');if(slash>0)prefixes.add(path.substring(0,slash));}
        Map<String,Path> roots=new TreeMap<>();Map<String,String> names=new HashMap<>();
        for(String address:reader.addresses(prefixes))if(!known.contains(address)){
            if(generation!=RuntimeCapture.generation())throw new IOException("Expired resource enumeration");var snapshot=reader.read(generation,address);if(snapshot==null)continue;
            remember(snapshot);
            String owner="resource_pack_"+GeoImporter.hash(snapshot.supplier().getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,16);
            Path root=cache.resolve("pack-roots").resolve(owner),target=root.resolve("assets/"+address.replace(':','/'));for(Path p=target;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Unsafe pack snapshot");Files.createDirectories(target.getParent());if(!Files.isRegularFile(target)||!GeoImporter.hash(Files.readAllBytes(target)).equals(GeoImporter.hash(snapshot.content())))Files.write(target,snapshot.content());roots.put(owner,root);names.put(owner,"Resource Pack - "+snapshot.supplier());
        }
        List<GeoImporter.ModSource> result=new ArrayList<>();roots.forEach((id,root)->result.add(new GeoImporter.ModSource(id,names.get(id),"effective",List.of(root))));Performance.end("snapshot.additionalSources",timing);return result;
    }
    public Path resolve(ResourceCatalog.Entry entry)throws Exception{
        String address=ResourceEvidence.address(entry);
        if(reader==null||address==null||externalOwners.contains(entry.owner()))return entry.file();
        if(generation!=RuntimeCapture.generation())throw new IOException("Resource generation expired");
        if(materialized.containsKey(address))return materialized.get(address);
        Path frozen=reader.frozenFile(address);
        if(frozen!=null){
            String hash=frozen.getFileName().toString().substring(0,64);
            if(!SourceFingerprint.digest(frozen).equals(hash))throw new IOException("Snapshot content changed: "+address);
            var value=new com.google.gson.JsonObject();value.addProperty("address",address);value.addProperty("supplier",reader.frozenSupplier(address));value.addProperty("generation",generation);value.addProperty("effectiveHash",hash);
            provenance.put(address,value);materialized.put(address,frozen);return frozen;
        }
        var snapshot=reader.read(generation,address);
        if(snapshot==null)throw new FileNotFoundException(address);
        if(snapshot.generation()!=generation||!snapshot.address().equals(address)||generation!=RuntimeCapture.generation())throw new IOException("Stale resource snapshot");
        for(Path p=cache;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Unsafe cache ancestor");
        Files.createDirectories(cache);
        Path target=cache.resolve(GeoImporter.hash(address.getBytes(java.nio.charset.StandardCharsets.UTF_8))+".resource");
        if(Files.isSymbolicLink(cache)||Files.isSymbolicLink(target))throw new IOException("Unsafe resource cache");
        if(!Files.isRegularFile(target)||!GeoImporter.hash(Files.readAllBytes(target)).equals(GeoImporter.hash(snapshot.content())))Files.write(target,snapshot.content());remember(snapshot);materialized.put(address,target);return target;
    }
}
