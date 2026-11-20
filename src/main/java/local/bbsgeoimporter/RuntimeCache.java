package local.bbsgeoimporter;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
/** Untrusted cache: validate addresses, container identities and every effective resource hash on replay. */
final class RuntimeCache {
    private final Path file;private final ResourceCatalog catalog;private final String identity;private final Consumer<String> log;
    private final LinkedHashMap<String,JsonObject> records=new LinkedHashMap<>();
    RuntimeCache(Path file,ResourceCatalog catalog,List<GeoImporter.ModSource> sources,Consumer<String> log){this.file=file;this.catalog=catalog;this.log=log;identity=GeoImporter.hash(sources.stream().sorted(Comparator.comparing(GeoImporter.ModSource::id)).map(s->s.id()+"="+s.version()+"="+s.roots()).reduce("",(a,b)->a+"\n"+b).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private void safe()throws Exception{for(Path p=file;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new java.io.IOException("Unsafe runtime cache");}
    private String fingerprint(ResourceEvidence.Observation event)throws Exception{
        StringBuilder b=new StringBuilder(identity);String[] addresses={event.model(),event.texture(),event.animation()};String[] kinds={"geo","texture","animation"};
        for(int i=0;i<3;i++)if(addresses[i]!=null){var e=catalog.uniqueResource(addresses[i],kinds[i]).orElseThrow();b.append(e.owner()).append(':').append(e.path()).append(':').append(GeoImporter.hash(Files.readAllBytes(e.file())));}
        return GeoImporter.hash(b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    List<ResourceEvidence.Observation> load(){List<ResourceEvidence.Observation> valid=new ArrayList<>();try{
        safe();if(!Files.exists(file))return valid;if(Files.size(file)>16*1024*1024)throw new java.io.IOException("Runtime cache too large");
        JsonObject data=JsonParser.parseString(Files.readString(file)).getAsJsonObject();if(data.get("schema").getAsInt()!=1)return valid;
        for(var value:data.getAsJsonArray("observations")){if(valid.size()>=16384)break;try{JsonObject o=value.getAsJsonObject();var e=new ResourceEvidence.Observation(RuntimeCapture.generation(),o.get("model").getAsString(),string(o,"texture"),string(o,"animation"),o.get("adapter").getAsString());
            if(fingerprint(e).equals(o.get("fingerprint").getAsString())){valid.add(e);records.put(key(e),o);}
        }catch(Exception ignored){}}
    }catch(Exception ex){log.accept("Runtime cache ignored: "+ex);}return valid;}
    private static String string(JsonObject o,String k){return !o.has(k)||o.get(k).isJsonNull()?null:o.get(k).getAsString();}
    private static String key(ResourceEvidence.Observation e){return e.model()+"|"+e.texture()+"|"+e.animation();}
    void add(Collection<ResourceEvidence.Observation> events)throws Exception{
        for(var e:events){JsonObject o=new JsonObject();o.addProperty("model",e.model());o.addProperty("texture",e.texture());o.addProperty("animation",e.animation());o.addProperty("adapter",e.adapter());o.addProperty("fingerprint",fingerprint(e));records.put(key(e),o);}
        while(records.size()>16384)records.remove(records.keySet().iterator().next());safe();Files.createDirectories(file.getParent());
        JsonObject data=new JsonObject();data.addProperty("schema",1);JsonArray array=new JsonArray();records.values().forEach(array::add);data.add("observations",array);
        Path temp=Files.createTempFile(file.getParent(),"capture-",".tmp");try{Files.writeString(temp,new GsonBuilder().setPrettyPrinting().create().toJson(data));Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
    }
}
