package local.bbsgeoimporter;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
/** Offline fixture adapter: metadata manifest is produced from installed jar metadata, never used by the mod. */
public final class InstalledScan {
    private static String hash(Path p) throws Exception {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
    public static void main(String[] args) throws Exception {
        var manifest=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
        List<FileSystem> opened=new ArrayList<>(); List<GeoImporter.ModSource> sources=new ArrayList<>();
        try {
            for(var element:manifest) {
                var m=element.getAsJsonObject();
                var fs=FileSystems.newFileSystem(Path.of(m.get("jar").getAsString()),Map.of()); opened.add(fs);
                sources.add(new GeoImporter.ModSource(m.get("id").getAsString(),m.get("name").getAsString(),m.get("version").getAsString(),List.of(fs.getPath("/"))));
            }
            Files.createDirectories(Path.of(args[1]));
            Path isolated=Files.createTempDirectory(Path.of(args[1]),"run-");
            Files.writeString(isolated.resolve("installed-manifest.json"),manifest.toString());
            GeoImporter importer=new GeoImporter(isolated,System.out::println);
            if(args.length>2&&args[2].equals("effective")){
                Map<String,Path> resourceFiles=new HashMap<>();
                for(var source:sources)for(Path root:source.roots())try(var paths=Files.walk(root)){for(Path file:paths.filter(Files::isRegularFile).toList()){
                    String relative=root.relativize(file).toString().replace('\\','/');String address=ResourceEvidence.address(new ResourceCatalog.Entry(source.id(),relative,file,"other"));if(address!=null)resourceFiles.putIfAbsent(address,file);
                }}
                importer.effectiveResources(new EffectiveResources(RuntimeCapture.generation(),new EffectiveResources.Reader(){public String fingerprint()throws Exception{return SourceFingerprint.sources(sources);}public ResourceEvidence.Snapshot read(long generation,String address)throws Exception{
                    Path file=resourceFiles.get(address);return file==null?null:new ResourceEvidence.Snapshot(generation,address,"archive-fixture",Files.readAllBytes(file));
                }},isolated.resolve("effective-snapshots")));
            }
            Performance.reset();var first=importer.scan(sources);Files.writeString(isolated.resolve("cold-performance.json"),new Gson().toJson(Performance.snapshot()));
            Map<String,SharedModelAssets.Binding> firstBindings=Map.copyOf(SharedModelAssets.bindingsForTest());
            Map<String,SharedModelAssets.Binding> samples=new LinkedHashMap<>();
            for(var binding:firstBindings.entrySet())if(!binding.getKey().contains("/skins/")&&(binding.getKey().endsWith("/vileplume")||List.of("butterfree_male","gloom_male","wolf_armor","arrival_helicopter").stream().anyMatch(binding.getKey()::contains)))samples.put(binding.getKey(),binding.getValue());
            if(samples.size()<(sources.stream().anyMatch(s->s.id().equals("cobblemon"))?5:2)||samples.values().stream().anyMatch(b->b.texture()==null))throw new AssertionError("Real sample default plans missing: "+samples);
            var custom=firstBindings.entrySet().stream().filter(e->e.getKey().endsWith("/models/custom/the_man_from_the_fog")).findFirst().orElseThrow();
            if(custom.getValue().texture()==null||!custom.getValue().texture().endsWith("/textures/entities/stalker.png"))throw new AssertionError("Exact custom model does not share proven entity texture: "+custom);
            BbsCpuAcceptance.run(isolated.resolve("bbs/assets"),firstBindings.entrySet().stream().filter(e->!e.getKey().contains("/skins/")).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,Map.Entry::getValue)));
            var restarted=new GeoImporter(isolated,s->{});restarted.bootstrap(sources);
            if(!firstBindings.equals(SharedModelAssets.bindingsForTest()))throw new AssertionError("Installed binding plans differ after restart");
            for(var binding:firstBindings.values())for(String link:binding.texture()==null?binding.materials().values():List.of(binding.texture())){Path file=isolated.resolve("bbs/assets").resolve(link);try(var in=Files.newInputStream(file)){if(javax.imageio.ImageIO.read(in)==null)throw new AssertionError("Invalid bound PNG: "+link);}}
            Path report=isolated.resolve("bbs/model-import-report.json");
            Files.copy(report,isolated.resolve("bbs/first-import-report.json"));
            Performance.reset();var second=importer.scan(sources);Files.writeString(isolated.resolve("hot-performance.json"),new Gson().toJson(Performance.snapshot()));
            Files.copy(report,isolated.resolve("bbs/repeat-import-report.json"));
            var firstSummary=JsonParser.parseString(Files.readString(isolated.resolve("bbs/first-import-report.json"))).getAsJsonObject().getAsJsonObject("summary");
            var repeatSummary=JsonParser.parseString(Files.readString(isolated.resolve("bbs/repeat-import-report.json"))).getAsJsonObject().getAsJsonObject("summary");
            if(firstSummary.get("importedGeo").getAsInt()!=first.imported() || second.skipped()!=first.discovered()) throw new AssertionError("Saved reports do not match scan receipts");
            if(first.imported()==0 || second.imported()!=0 || second.updated()!=0 || first.failed()!=0 || second.failed()!=0)
                throw new AssertionError("Installed-mod offline scan failed: "+first+" / "+second);
            var associations=JsonParser.parseString(Files.readString(isolated.resolve("bbs/first-import-report.json"))).getAsJsonObject().getAsJsonArray("associations");
            boolean metapod=false,butterfree=false,ribbitTexture=false,ribbitAnimation=false;
            for(var element:associations){var edge=element.getAsJsonObject();String model=edge.get("model").getAsString(),resource=edge.get("resource").getAsString(),evidence=edge.get("evidence").getAsString();
                if(evidence.startsWith("cobblemon_java_poser:")){if(model.contains("metapod"))metapod=true;if(model.contains("butterfree"))butterfree=true;}
                if(model.startsWith("ribbits:")&&evidence.startsWith("constant_bytecode:")){ribbitTexture|=resource.endsWith("textures/entity/ribbit.png");ribbitAnimation|=resource.endsWith("animations/ribbit.animation.json");}
            }
            if((sources.stream().anyMatch(s->s.id().equals("cobblemon"))&&(!metapod||!butterfree))||!ribbitTexture||!ribbitAnimation)throw new AssertionError("Real association regression: metapod="+metapod+" butterfree="+butterfree+" ribbitTexture="+ribbitTexture+" ribbitAnimation="+ribbitAnimation);
            // Recovery runs only inside this isolated fixture, never against instance models.
            var repairState=JsonParser.parseString(Files.readString(isolated.resolve("bbs/model-import-index.json"))).getAsJsonObject();
            Path outputRoot=isolated.resolve("bbs/assets/models");Map<Path,String> expected=new LinkedHashMap<>();
            for(var owner:repairState.getAsJsonObject("mods").entrySet())for(var f:owner.getValue().getAsJsonObject().getAsJsonObject("files").entrySet()){var rec=f.getValue().getAsJsonObject();if(rec.has("writtenHash"))expected.put(outputRoot.resolve(owner.getValue().getAsJsonObject().get("directory").getAsString()).resolve(rec.get("target").getAsString()),rec.get("writtenHash").getAsString());}
            Set<String> deletedKinds=new HashSet<>();
            for(Path p:expected.keySet()){String kind=p.toString().endsWith(".geo.json")?"geo":p.toString().endsWith(".png")?"png":"animation";if(deletedKinds.add(kind))Files.delete(p);}
            int selective=importer.repairMissing();if(selective<deletedKinds.size())throw new AssertionError("Selective repair failed");
            Path removed=outputRoot.resolve(repairState.getAsJsonObject("mods").getAsJsonObject("geckolib").get("directory").getAsString());
            if(!removed.normalize().startsWith(outputRoot.normalize()))throw new AssertionError("Unsafe fixture deletion");
            try(var walk=Files.walk(removed)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
            int directory=importer.repairMissing();
            for(var e:expected.entrySet())if(!Files.isRegularFile(e.getKey())||!hash(e.getKey()).equals(e.getValue()))throw new AssertionError("Repair hash mismatch: "+e.getKey());
            if(importer.repairMissing()!=0)throw new AssertionError("Repair not idempotent");
            JsonObject repairReceipt=new JsonObject();repairReceipt.addProperty("selectiveRestored",selective);repairReceipt.addProperty("directoryRestored",directory);repairReceipt.addProperty("verifiedOutputs",expected.size());repairReceipt.addProperty("repeatRestored",0);Files.writeString(isolated.resolve("repair-complete.json"),repairReceipt.toString());
            JsonObject complete=new JsonObject();
            for(String name:List.of("first-import-report.json","repeat-import-report.json","model-import-index.json")) complete.addProperty(name,hash(isolated.resolve("bbs").resolve(name)));
            complete.addProperty("manifestHash",hash(isolated.resolve("installed-manifest.json")));
            Files.writeString(isolated.resolve("scan-complete.json"),complete.toString());
            System.out.println("INSTALLED OFFLINE PASS: "+first+"; repeat="+second+"; run="+isolated);
        } finally { for(var fs:opened) fs.close(); }
    }
}
