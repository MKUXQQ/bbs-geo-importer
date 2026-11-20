package local.bbsgeoimporter;
import java.nio.file.Path;
import java.lang.reflect.*;
import java.util.*;
/** Optional loader APIs are reflected without initializing third-party mod classes. */
public final class LoaderSources {
    private static volatile boolean incomplete;
    static void beginDiscovery(){incomplete=false;}
    static boolean discoveryComplete(){return !incomplete;}
    static void markIncomplete(){incomplete=true;}
    public static List<GeoImporter.ModSource> collect(List<GeoImporter.ModSource> primary) {
        incomplete=false;
        List<GeoImporter.ModSource> all=new ArrayList<>(primary);
        try {
            Object list=Class.forName("net.minecraftforge.fml.ModList").getMethod("get").invoke(null);
            for(Object mod:(List<?>)list.getClass().getMethod("getMods").invoke(list)) {
                try {
                Class<?> api=Class.forName("net.minecraftforge.forgespi.language.IModInfo");
                Object owner=api.getMethod("getOwningFile").invoke(mod);
                Object file=Class.forName("net.minecraftforge.forgespi.language.IModFileInfo").getMethod("getFile").invoke(owner);
                Path root=(Path)Class.forName("net.minecraftforge.forgespi.locating.IModFile").getMethod("findResource",String[].class).invoke(file,(Object)new String[0]);
                all.add(new GeoImporter.ModSource((String)api.getMethod("getModId").invoke(mod),(String)api.getMethod("getDisplayName").invoke(mod),api.getMethod("getVersion").invoke(mod).toString(),List.of(root)));
                } catch(ReflectiveOperationException | RuntimeException | LinkageError e) {incomplete=true;System.getLogger("bbs_geo_importer").log(System.Logger.Level.WARNING,"Forge container skipped; continuing discovery: "+e);}
            }
        } catch(ClassNotFoundException absent) { /* Pure Fabric. */ }
        catch(ReflectiveOperationException | LinkageError e) { incomplete=true;System.getLogger("bbs_geo_importer").log(System.Logger.Level.WARNING,"Forge container discovery unavailable: "+e); }
        return merge(all);
    }
    static List<GeoImporter.ModSource> physicalViews(List<GeoImporter.ModSource> input){
        Map<String,List<GeoImporter.ModSource>> groups=new TreeMap<>();
        for(var source:merge(input)){String signature=source.resourceDomain()+"|"+source.roots().stream().map(p->p.toUri().toString()).sorted().collect(java.util.stream.Collectors.joining("|"));groups.computeIfAbsent(signature,k->new ArrayList<>()).add(source);}
        List<GeoImporter.ModSource> result=new ArrayList<>();for(var values:groups.values()){if(values.size()==1){result.add(values.get(0));continue;}var ids=values.stream().map(GeoImporter.ModSource::id).sorted().toList();String id="container_"+GeoImporter.hash(String.join("|",ids).getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,16);result.add(new GeoImporter.ModSource(id,"Container - "+String.join(" + ",ids),values.stream().map(GeoImporter.ModSource::version).collect(java.util.stream.Collectors.joining("|")),values.get(0).roots(),values.get(0).kind(),String.join("+",ids),values.get(0).containerPath(),values.get(0).resourceDomain()));}return result;
    }
    static List<GeoImporter.ModSource> merge(List<GeoImporter.ModSource> all) {
        Map<String,GeoImporter.ModSource> merged=new TreeMap<>();
        for(var s:all) {
            var old=merged.get(s.id()); LinkedHashMap<String,Path> roots=new LinkedHashMap<>();
            if(old!=null) for(Path p:old.roots()) roots.put(p.toUri().toString(),p);
            for(Path p:s.roots()) roots.put(p.toUri().toString(),p);
            if(old!=null && (!Objects.equals(old.name(),s.name()) || !Objects.equals(old.version(),s.version())))
                System.getLogger("bbs_geo_importer").log(System.Logger.Level.WARNING,"Container metadata differs for "+s.id()+"; retaining primary metadata and merging roots");
            merged.put(s.id(),new GeoImporter.ModSource(s.id(),old==null?s.name():old.name(),old==null?s.version():old.version(),List.copyOf(roots.values()),s.kind(),s.displayModId(),s.containerPath(),s.resourceDomain()));
        }
        return new ArrayList<>(merged.values());
    }
}
