package local.bbsgeoimporter;

import com.google.gson.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Compare both algorithms on identical inputs. No game directory is written. */
public final class StartupWorkAcceptance {
    private static long visits;
    public static void main(String[] args)throws Exception{
        List<GeoImporter.ModSource> sources=new ArrayList<>();Map<String,JsonObject> facts=new TreeMap<>();
        for(int i=0;i<36;i++){
            String id="source"+i;Path root=Path.of("r"+(i==1?0:i));
            sources.add(new GeoImporter.ModSource(id,id,"1",List.of(root)));
            JsonObject fact=new JsonObject();fact.addProperty("hasGeo",i%3!=2);
            JsonArray provided=new JsonArray(),refs=new JsonArray();provided.add("ns:"+i);
            refs.add("ns:"+i);if(i%4==2||i%4==3)refs.add("ns:"+(i-1));
            for(int j=0;j<800;j++)refs.add("class:unprovided/"+j);
            fact.add("provided",provided);fact.add("references",refs);facts.put(id,fact);
        }
        var expected=legacy(sources,facts);var actual=CatalogCache.dependencyGroups(sources,facts);
        if(!expected.equals(actual)||!actual.get("source0").equals(List.of(sources.get(0),sources.get(1))))throw new AssertionError("Owner closure or order changed");
        long[] before=new long[5],after=before.clone();Performance.reset();
        for(int i=0;i<5;i++){
            visits=0;long t=System.nanoTime();if(!legacy(sources,facts).equals(expected))throw new AssertionError();before[i]=System.nanoTime()-t;
            t=System.nanoTime();if(!CatalogCache.dependencyGroups(sources,facts).equals(expected))throw new AssertionError();after[i]=System.nanoTime()-t;
        }
        long indexed=((Number)((Map<?,?>)Performance.snapshot().get("component.footprintTokens")).get("bytes")).longValue()/5;
        if(indexed>=visits)throw new AssertionError("No reduction");
        Arrays.sort(before);Arrays.sort(after);
        System.out.printf(Locale.ROOT,"Closure: 36 sources/24 Geo owners, identical ordered closures; reference tokens visited %,d -> %,d; median %.2f -> %.2f ms.%n",visits,indexed,before[2]/1e6,after[2]/1e6);
        if(args.length!=0)try(var zip=FileSystems.newFileSystem(Path.of(args[0]),Map.of());var walk=Files.walk(zip.getPath("/"))){
            List<Path> files=walk.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".json")||p.toString().endsWith(".class")).sorted().limit(1200).toList();
            if(files.size()<50)throw new AssertionError("Too few real entries");
            Map<Path,String> sums=new HashMap<>();long[] oldTimes=new long[3],newTimes=new long[3];
            for(int i=0;i<4;i++){
                long t=System.nanoTime();
                for(Path f:files){MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] buf=new byte[131072];try(var in=Files.newInputStream(f)){int n;while((n=in.read(buf))>=0)hash.update(buf,0,n);}sums.put(f,HexFormat.of().formatHex(hash.digest()));}
                long oldNanos=System.nanoTime()-t;t=System.nanoTime();Performance.reset();SourceFingerprint.begin();
                try{for(Path f:files)if(!sums.get(f).equals(SourceFingerprint.digest(f)))throw new AssertionError("Content changed "+f);}finally{SourceFingerprint.end();}
                if(i>0){newTimes[i-1]=System.nanoTime()-t;oldTimes[i-1]=oldNanos;}
            }
            var stats=(Map<?,?>)Performance.snapshot().get("source.hashBufferAllocate");if(((Number)stats.get("count")).intValue()!=1)throw new AssertionError("Fingerprint buffer not reused");
            Arrays.sort(oldTimes);Arrays.sort(newTimes);
            System.out.printf(Locale.ROOT,"SHA-256: %s, %d real entries, identical digests; input buffers %,d -> %,d bytes; median %.2f -> %.2f ms (warm filesystem, not total startup).%n",Path.of(args[0]).getFileName(),files.size(),131072L*files.size(),131072,oldTimes[1]/1e6,newTimes[1]/1e6);
        }
    }
    private static Map<String,List<GeoImporter.ModSource>> legacy(List<GeoImporter.ModSource> sources,Map<String,JsonObject> facts){
        Map<String,Set<String>> providers=new HashMap<>(),deps=new HashMap<>();
        for(var f:facts.entrySet())for(String s:strings(f.getValue(),"provided"))providers.computeIfAbsent(s,k->new HashSet<>()).add(f.getKey());
        for(var f:facts.entrySet()){Set<String> out=new HashSet<>();for(String s:strings(f.getValue(),"references"))out.addAll(providers.getOrDefault(s,Set.of()));deps.put(f.getKey(),out);}
        Map<String,List<GeoImporter.ModSource>> result=new TreeMap<>();
        for(var s:sources)if(facts.get(s.id()).get("hasGeo").getAsBoolean()){
            Set<String> closure=new HashSet<>(List.of(s.id()));boolean changed=true;
            while(changed){changed=false;for(String id:new ArrayList<>(closure))changed|=closure.addAll(deps.get(id));
                var own=strings(facts.get(s.id()),"provided");for(var other:sources)if(!other.id().equals(s.id())&&(other.roots().stream().anyMatch(s.roots()::contains)||strings(facts.get(other.id()),"references").stream().anyMatch(own::contains)))changed|=closure.add(other.id());}
            result.put(s.id(),sources.stream().filter(o->closure.contains(o.id())).toList());
        }return result;
    }
    private static Set<String> strings(JsonObject fact,String key){Set<String> result=new HashSet<>();for(var e:fact.getAsJsonArray(key)){visits++;result.add(e.getAsString());}return result;}
}
