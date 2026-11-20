package local.bbsgeoimporter;
import net.fabricmc.loader.api.FabricLoader;
import java.util.*;
public final class FabricSources {
    public static List<GeoImporter.ModSource> collect() {
        boolean incomplete=false;List<GeoImporter.ModSource> result=new ArrayList<>();
        for (var c : FabricLoader.getInstance().getAllMods()) {
            try {
                var m=c.getMetadata();
                result.add(new GeoImporter.ModSource(m.getId(),m.getName(),m.getVersion().getFriendlyString(),c.getRootPaths()));
            } catch(RuntimeException | LinkageError e) {incomplete=true;System.getLogger("bbs_geo_importer").log(System.Logger.Level.WARNING,"Fabric container skipped; continuing discovery: "+e);}
        }
        var merged=LoaderSources.collect(result);if(incomplete)LoaderSources.markIncomplete();return merged;
    }
}
