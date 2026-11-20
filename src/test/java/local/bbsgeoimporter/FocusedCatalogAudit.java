package local.bbsgeoimporter;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read-only resource association audit for a selected set of loader containers. */
public final class FocusedCatalogAudit {
    public static void main(String[] args)throws Exception{
        JsonArray manifest=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
        Set<String> wanted=Set.of(args[2].split(","));List<FileSystem> opened=new ArrayList<>();List<GeoImporter.ModSource> sources=new ArrayList<>();
        try{
            for(var value:manifest){JsonObject mod=value.getAsJsonObject();if(!wanted.contains(mod.get("id").getAsString()))continue;FileSystem fs=FileSystems.newFileSystem(Path.of(mod.get("jar").getAsString()),Map.of());opened.add(fs);sources.add(new GeoImporter.ModSource(mod.get("id").getAsString(),mod.get("name").getAsString(),mod.get("version").getAsString(),List.of(fs.getPath("/"))));}
            ResourceCatalog catalog=new ResourceCatalog(sources);JsonObject out=catalog.report.deepCopy();JsonArray rows=new JsonArray();
            for(var item:catalog.bundles.entrySet())for(var edge:item.getValue()){JsonObject row=new JsonObject();row.addProperty("model",edge.model());row.addProperty("resource",edge.resource());row.addProperty("evidence",edge.evidence());rows.add(row);}
            out.add("associations",rows);JsonArray geos=new JsonArray();catalog.bundles.keySet().forEach(geos::add);out.add("geoModels",geos);out.addProperty("geoCount",catalog.bundles.size());out.addProperty("associationCount",rows.size());
            Files.writeString(Path.of(args[1]),out.toString(),StandardCharsets.UTF_8);System.out.println("FOCUSED AUDIT PASS: geo="+catalog.bundles.size()+" associations="+rows.size()+" phases="+out);
        }finally{for(FileSystem fs:opened)fs.close();}
    }
}
