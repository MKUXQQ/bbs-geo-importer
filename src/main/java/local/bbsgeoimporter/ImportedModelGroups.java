package local.bbsgeoimporter;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
/** UI-only grouping; model keys and on-disk assets remain unchanged. */
public final class ImportedModelGroups {
    record Snapshot(long generation,long version,Set<String> keys,Set<String> modelKeys,Set<String> inactive) {}
    private static volatile Snapshot snapshot=new Snapshot(0,0,Set.of(),Set.of(),Set.of());
    private static volatile Map<String,Set<String>> sourceModels=Map.of();
    private static volatile Set<String> preparedSources=Set.of();
    private static volatile Set<String> sourceInactive=Set.of();
    static Snapshot snapshot(){return snapshot;}
    public static boolean inactive(String key){return snapshot.inactive().contains(key);}
    /** Unloaded/deleted owned resources, distinct from the list-only source visibility switches. */
    public static boolean unavailable(String key){return sourceInactive.contains(key);}
    static long version(){return snapshot.version();}
    private static final Map<Object,AvailableKeys> available=Collections.synchronizedMap(new WeakHashMap<>());
    private static final class AvailableKeys {
        List<String> raw;
        Set<String> mergedModels,mergedInactive;
        List<String> merged;
        AvailableKeys(List<String> raw){this.raw=List.copyOf(raw);}
        boolean sameRaw(List<String> candidate){
            if(raw.size()!=candidate.size())return false;
            for(int i=0;i<raw.size();i++)if(!Objects.equals(raw.get(i),candidate.get(i)))return false;
            return true;
        }
        synchronized List<String> visible(){
            Snapshot view=snapshot;
            if(merged==null||mergedModels!=view.modelKeys()||mergedInactive!=view.inactive()){
                Set<String> result=new TreeSet<>(raw);result.removeAll(view.inactive());result.addAll(view.modelKeys());
                merged=List.copyOf(result);mergedModels=view.modelKeys();mergedInactive=view.inactive();
            }
            return new ArrayList<>(merged); // ModelFormSection sorts the result in place.
        }
        synchronized boolean change(String key,boolean present){
            boolean exists=raw.contains(key);if(exists==present)return false;
            var next=new ArrayList<>(raw);if(present)next.add(key);else next.remove(key);
            raw=List.copyOf(next);merged=null;return true;
        }
    }
    public static List<String> cachedAvailable(Object manager){var result=available.get(manager);return result==null?null:result.visible();}
    public static void nativeAssetsChanged(){available.clear();BbsCategoryRefresh.nativeAssetsChanged();}
    /** Native user-model watcher events update the cached keys, never rescan a provider. */
    public static synchronized void nativeModelChanged(String key,boolean present){
        boolean changed=false;synchronized(available){for(var cache:available.values())changed|=cache.change(key,present);}
        if(changed)bumpVersion();
    }
    public static void nativeContentsChanged(String key){ListPreviewRequests.invalidate(key);Performance.count("bbs.nativeContentsChanged",0);}
    private static void bumpVersion(){var old=snapshot;snapshot=new Snapshot(old.generation(),old.version()+1,old.keys(),old.modelKeys(),old.inactive());}
    public static boolean previewAllowed(String key){return key==null||!snapshot.inactive().contains(key);}
    static boolean sourceReady(String id){return preparedSources.contains(id);}
    static synchronized boolean setSourceEnabled(String id,boolean ignored){return publishVisible();}
    public static List<String> filterAvailable(Object manager,List<String> raw){
        Performance.count("bbs.nativeKeyScan",raw.size());
        AvailableKeys result=available.get(manager);
        if(result==null||!result.sameRaw(raw)){result=new AvailableKeys(raw);available.put(manager,result);}
        return result.visible();
    }
    static Set<String> modelKeys(){return snapshot.modelKeys();}
    public static boolean update(JsonObject state) {
        return update(state,null);
    }
    static synchronized boolean update(JsonObject state,java.nio.file.Path models) {
        Map<String,Set<String>> nextSources=new HashMap<>();Set<String> hidden=new HashSet<>();
        for(var value:state.getAsJsonObject("mods").entrySet()) {
            var mod=value.getValue().getAsJsonObject();String dir=mod.get("directory").getAsString();
            for(var item:mod.getAsJsonObject("files").entrySet()) {
                var f=item.getValue().getAsJsonObject();String target=f.get("target").getAsString();
                if(!f.has("writtenHash")||!target.toLowerCase(Locale.ROOT).endsWith(".geo.json"))continue;
                if(f.has("inactiveReason")){int slash=target.lastIndexOf('/');if(slash>0&&models!=null&&List.of("imported","updated","unchanged").contains(SharedModelAssets.verifyOutput(models,dir,f)))hidden.add(dir+"/"+target.substring(0,slash));continue;}
                if(models!=null&&!List.of("imported","updated","unchanged").contains(SharedModelAssets.verifyOutput(models,dir,f)))continue;
                int slash=target.lastIndexOf('/');if(slash>0){
                    String modelDir=dir+"/"+target.substring(0,slash);nextSources.computeIfAbsent(value.getKey(),k->new HashSet<>()).add(modelDir);
                }
            }
        }
        Map<String,Set<String>> frozen=new HashMap<>();nextSources.forEach((id,keys)->frozen.put(id,Set.copyOf(keys)));
        sourceModels=Map.copyOf(frozen);
        Set<String> ready=new HashSet<>();sourceModels.forEach((id,keys)->{if(ModelManagement.enabled(id)||keys.stream().allMatch(SharedModelAssets::hasPreparedBinding))ready.add(id);});
        preparedSources=Set.copyOf(ready);sourceInactive=Set.copyOf(hidden);return publishVisible();
    }
    private static boolean publishVisible(){
        Set<String> next=new HashSet<>(),modelsOnly=new HashSet<>(),hidden=new HashSet<>(sourceInactive);
        sourceModels.forEach((id,keys)->{if(ModelManagement.enabled(id)&&preparedSources.contains(id))modelsOnly.addAll(keys);else hidden.addAll(keys);});
        for(String key:modelsOnly){next.add(key);int parent=key.lastIndexOf('/');if(parent>0)next.add(key.substring(0,parent));}
        Snapshot old=snapshot;
        if(old.keys().equals(next)&&old.modelKeys().equals(modelsOnly)&&old.inactive().equals(hidden)){snapshot=new Snapshot(RuntimeCapture.generation(),old.version(),old.keys(),old.modelKeys(),old.inactive());return false;}
        snapshot=new Snapshot(RuntimeCapture.generation(),old.version()+1,Set.copyOf(next),Set.copyOf(modelsOnly),Set.copyOf(hidden));
        Performance.count("sources.filtered",hidden.size());return true;
    }
    public static String category(String modelKey) {
        if(modelKey==null)return null;
        String match=modelKey;Snapshot view=snapshot;
        while(!view.keys().contains(match)){int slash=match.lastIndexOf('/');if(slash<0)return null;match=match.substring(0,slash);}
        if(match==null)return null;
        int slash=match.indexOf('/');return slash>0?match.substring(0,slash):null;
    }
}
