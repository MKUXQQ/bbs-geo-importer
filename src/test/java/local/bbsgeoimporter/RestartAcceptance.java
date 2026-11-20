package local.bbsgeoimporter;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Fresh JVMs share only disk state; the archives and all outputs are isolated fixtures. */
public final class RestartAcceptance {
    private static final String GEO="{'minecraft:geometry':[{'description':{'identifier':'geometry.restart','texture_width':2,'texture_height':2,'texture':'restart_paint:textures/body.png'},'bones':[{'name':'body','cubes':[{'origin':[0,0,0],'size':[1,1,1],'uv':[0,0]}]}]}]}";
    private static final String A="assets/restart_paint/geo/base.geo.json", B="assets/foreign_namespace/models/shape.json";

    public static void main(String[] args)throws Exception{
        if(args.length==0){
            Path base=Files.createTempDirectory(Path.of("build").toAbsolutePath(),"restart-");
            Path png=base.resolve("paint.png");ImporterAcceptance.png(png,0xff247abd);
            archive(base.resolve("a.jar"),Map.of(A,GEO.getBytes(java.nio.charset.StandardCharsets.UTF_8),"assets/restart_paint/textures/body.png",Files.readAllBytes(png)));
            for(String phase:List.of("initial","failed","added","removed")){
                if(phase.equals("failed"))archive(base.resolve("b.jar"),Map.of(B,GEO.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                var command=new ArrayList<String>();command.add(Path.of(System.getProperty("java.home"),"bin","java").toString());command.addAll(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());command.addAll(List.of("-cp",System.getProperty("java.class.path"),RestartAcceptance.class.getName(),base.toString(),phase));
                if(new ProcessBuilder(command).inheritIO().start().waitFor()!=0)throw new AssertionError(phase);
            }
            return;
        }
        Path base=Path.of(args[0]),config=base.resolve("config");String phase=args[1];
        var provider=new cpw.mods.niofs.union.UnionFileSystemProvider();
        try(var a=provider.newFileSystem(null,base.resolve("a.jar"));var b=Files.exists(base.resolve("b.jar"))?provider.newFileSystem(null,base.resolve("b.jar")):null){
            var sources=new ArrayList<>(List.of(ImporterAcceptance.source("loaded_a","A",a.getRoot())));
            var left=ImporterAcceptance.source("added_b","B",b==null?a.getRoot():b.getRoot());
            var right=ImporterAcceptance.source("also_b","B companion",left.roots().get(0));
            if(phase.equals("added"))sources.addAll(List.of(left,right));
            var physical=LoaderSources.physicalViews(sources);
            var importer=new GeoImporter(config,System.out::println);
            Performance.reset();SourceCensus.begin();SourceFingerprint.begin();AnalysisInputs.begin();
            try{
                String id=physical.get(0).id();
                if(phase.equals("failed")){
                    // Seed the exact bad footprint emitted by 1.5.12; then simulate an unreadable root
                    // after its fingerprint was obtained. The archive on disk is not changed.
                    var source=physical.stream().filter(s->s.id().equals(id)).findFirst().orElseThrow();
                    String fp=GeoImporter.hash((source.id()+source.name()+source.version()+SourceFingerprint.cachedRoot(source.roots().get(0))).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    var old=JsonParser.parseString("{\"error\":\"transient read failure\",\"hasGeo\":false,\"resources\":[],\"provided\":[],\"references\":[]}").getAsJsonObject();
                    Path cached=config.resolve("bbs/parsed-components").resolve(fp+".source4.json");Files.createDirectories(cached.getParent());Files.writeString(cached,old.toString());
                    check(importer.scan(sources).discovered()==1,"failed cache does not prevent A");
                    check(Files.readString(cached).equals(old.toString()),"failed footprint was not committed");
                    return;
                }
                importer.bootstrap(sources);
                var result=importer.scan(sources);
                check(result.discovered()==(phase.equals("added")?2:1),"expected Geo count "+result);
                check(PerformanceAcceptance.count("source.unionZipMetadata")>0,"Forge union ZIP metadata path exercised");
                check(PerformanceAcceptance.count("source.hash")<=2,"no per-entry content hashes on ZIP-backed union");
                if(phase.equals("added")){
                    check(PerformanceAcceptance.count("source.hash")<=2,"added Mod does not rehash all source entries");
                    JsonObject state=JsonParser.parseString(Files.readString(config.resolve("bbs/model-import-index.json"))).getAsJsonObject();
                    String ownerId=null;
                    for(var entry:state.getAsJsonObject("mods").entrySet()) {
                        if(entry.getValue().getAsJsonObject().getAsJsonObject("files").has(B)) { ownerId=entry.getKey(); break; }
                    }
                    check(ownerId!=null,"new Mod owner recorded");
                    Path imported=ImporterAcceptance.target(config,ownerId,B);
                    check(Files.isRegularFile(imported),"structural Geo with foreign namespace published");
                    var key=SharedModelAssets.bindingsForTest().entrySet().stream().filter(e->e.getKey().startsWith("Container")).findFirst().orElseThrow();
                    check(key.getValue().texture()!=null,"new Geo shares A texture");
                    BbsCpuAcceptance.run(config.resolve("bbs/assets"),Map.of(key.getKey(),key.getValue()));
                    Files.writeString(base.resolve("asset.txt"),imported.toString());
                    Files.writeString(base.resolve("key.txt"),key.getKey());
                    Files.writeString(imported.resolveSibling("user-owned.txt"),"keep");
                }
                if(phase.equals("removed")){
                    Path imported=Path.of(Files.readString(base.resolve("asset.txt")));
                    String key=Files.readString(base.resolve("key.txt"));
                    check(!ImportedModelGroups.modelKeys().contains(key)&&!SharedModelAssets.bindingsForTest().containsKey(key),"unloaded B removed from model and binding views");
                    check(Files.isRegularFile(imported)&&Files.readString(imported.resolveSibling("user-owned.txt")).equals("keep"),"unloading preserves disk assets");
                    check(SharedModelAssets.bindingsForTest().values().stream().anyMatch(binding->binding.texture()!=null),"A retains shared PNG");
                }
                System.out.println("RESTART "+phase+" "+Performance.snapshot());
                BbsCategoryCpuAcceptance.run(base.resolve("native-"+phase));
            }finally{AnalysisInputs.end();SourceFingerprint.end();SourceCensus.end();}
        }
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void archive(Path file,Map<String,byte[]> entries)throws Exception{
        try(var zip=new ZipOutputStream(Files.newOutputStream(file))){for(var e:entries.entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}}
    }
}
