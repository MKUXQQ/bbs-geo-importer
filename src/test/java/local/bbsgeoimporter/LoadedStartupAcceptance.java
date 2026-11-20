package local.bbsgeoimporter;
import java.nio.file.*;import java.util.*;import com.google.gson.*;
/** Last-session loader roots reconstructed read-only; all outputs stay inside build. */
public final class LoadedStartupAcceptance {
 public static void main(String[] args)throws Exception{
  Path base=Files.createTempDirectory(Path.of("build"),"1511-loaded-");List<FileSystem> opened=new ArrayList<>();Map<Path,FileSystem> mounted=new HashMap<>();List<GeoImporter.ModSource> sources=new ArrayList<>();
  try{
   for(var element:JsonParser.parseString(Files.readString(Path.of(System.getProperty("bbs.fixture.manifest","build/1511-loaded-manifest.json")))).getAsJsonArray()){var m=element.getAsJsonObject();if(args.length>0&&!Set.of(args[0].split(",")).contains(m.get("id").getAsString()))continue;List<Path> roots=new ArrayList<>();for(var j:m.getAsJsonArray("jars")){Path jar=Path.of(j.getAsString());if(Files.isDirectory(jar)){roots.add(jar);continue;}var fs=mounted.get(jar);if(fs==null){fs=FileSystems.newFileSystem(jar,Map.of());mounted.put(jar,fs);opened.add(fs);}roots.add(fs.getPath("/"));}sources.add(new GeoImporter.ModSource(m.get("id").getAsString(),m.get("name").getAsString(),m.get("version").getAsString(),roots));}
   var importer=new GeoImporter(base,s->{});Map<String,Object> receipt=new LinkedHashMap<>();
   for(int round=0;round<2;round++){
    Performance.reset();SourceCensus.begin();AnalysisInputs.begin();SourceFingerprint.begin(base.resolve("bbs/source-fingerprints.json"));long begin=System.nanoTime();
    try{
     var recovered=importer.bootstrap(sources);long geometry=System.nanoTime();var result=importer.scan(sources);long complete=System.nanoTime();
     Map<String,Object> metrics=new TreeMap<>(Performance.snapshot());metrics.put("geometryWallMs",(geometry-begin)/1e6);metrics.put("totalWallMs",(complete-begin)/1e6);receipt.put(round==0?"cold":"hot",metrics);
     Object manager=new Object();Set<String> nativeKeys=new HashSet<>(ImportedModelGroups.filterAvailable(manager,List.of()));List<Object> rows=new ArrayList<>();
     var state=JsonParser.parseString(Files.readString(base.resolve("bbs/model-import-index.json"))).getAsJsonObject();
     for(var owner:state.getAsJsonObject("mods").entrySet()){var mod=owner.getValue().getAsJsonObject();String dir=mod.get("directory").getAsString();int geo=0,present=0,available=0,category=0;for(var file:mod.getAsJsonObject("files").entrySet()){var r=file.getValue().getAsJsonObject();String target=r.get("target").getAsString();if(file.getKey().startsWith("@")||!target.endsWith(".geo.json")||r.has("inactiveReason"))continue;geo++;if(Files.isRegularFile(base.resolve("bbs/assets/models").resolve(dir).resolve(target)))present++;String key=dir+"/"+target.substring(0,target.lastIndexOf('/'));if(nativeKeys.contains(key))available++;if(dir.equals(ImportedModelGroups.category(key)))category++;}if(geo>0){if(geo!=present||geo!=available||geo!=category)throw new AssertionError("source chain mismatch "+owner.getKey());rows.add(Map.of("mod",owner.getKey(),"foundGeo",geo,"outputPresent",present,"nativeHookAvailable",available,"grouped",category));}}
     var report=JsonParser.parseString(Files.readString(base.resolve("bbs/model-import-report.json"))).getAsJsonObject();
     for(var value:report.getAsJsonArray("models")){var row=value.getAsJsonObject();if(row.get("sourceMod").getAsString().equals("golemoverhaul")&&row.get("sourceTextureCount").getAsInt()==0)throw new AssertionError("installed golem association regression: "+row.get("model"));}
     receipt.put("sources",rows);receipt.put("keys",nativeKeys.size());receipt.put("bindings",SharedModelAssets.bindingsForTest().size());
     System.out.println("LOADED "+round+" geometryMs="+(geometry-begin)/1000000+" totalMs="+(complete-begin)/1000000+" "+result+" rows="+new Gson().toJson(rows));
     if(round==1){BbsCategoryCpuAcceptance.run(base.resolve("real-section"));BbsCpuAcceptance.run(base.resolve("bbs/assets"),SharedModelAssets.bindingsForTest());}
    }finally{SourceFingerprint.end();AnalysisInputs.end();SourceCensus.end();}
   }
   Files.writeString(base.resolve("receipt.json"),new GsonBuilder().setPrettyPrinting().create().toJson(receipt));System.out.println("LOADED STARTUP PASS "+base);
  }finally{for(var fs:opened)fs.close();}
 }
}
