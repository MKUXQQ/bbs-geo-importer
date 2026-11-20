package local.bbsgeoimporter;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Pure resource views. No external class is loaded or initialized. */
final class ExternalSources {
    static final class Session implements AutoCloseable {
        private final List<FileSystem> mounted=new ArrayList<>();
        private final List<GeoImporter.ModSource> sources=new ArrayList<>();
        private final List<JsonObject> failures=new ArrayList<>();
        private final Set<String> visited=new HashSet<>();
        private final Consumer<String> log;
        private final Path cache;
        private static final class NestedBudget { int members; long bytes; }
        Session(Path cache,Consumer<String> log){this.cache=cache;this.log=log;}
        List<GeoImporter.ModSource> sources(){return List.copyOf(sources);}
        List<JsonObject> failures(){return failures.stream().map(JsonObject::deepCopy).toList();}
        private void failure(String origin,String domain,String reason){JsonObject row=new JsonObject();row.addProperty("kind","EXTERNAL_SOURCE");row.addProperty("sourceId",sourceId(origin));row.addProperty("containerPath",origin);row.addProperty("resourceDomain",domain);row.addProperty("stage","FAILED");row.addProperty("reason",reason);failures.add(row);log.accept("External source isolated: "+origin+" "+reason);}
        public void close(){for(int i=mounted.size()-1;i>=0;i--)try{mounted.get(i).close();}catch(IOException e){log.accept("External archive close failed: "+e);}mounted.clear();}
        private void add(Path container,String origin,String domain,int depth,NestedBudget budget)throws IOException {
            add(container,origin,domain,depth,budget,false);
        }
        private void add(Path container,String origin,String domain,int depth,NestedBudget budget,boolean unpackedMod)throws IOException {
            if(!visited.add(origin))return;
            if(depth>0){if(depth>3||budget.members>=64){failure(origin,domain,"NESTED_SOURCE_LIMIT");return;}budget.members++;}
            Path root=container;
            boolean archive=Files.isRegularFile(container);
            if(archive){FileSystem fs=FileSystems.newFileSystem(container,Map.of());mounted.add(fs);root=fs.getPath("/");}
            List<Path> roots=new ArrayList<>();
            if(unpackedMod)roots.add(root);
            else try(var walk=Files.walk(root,8)){
                for(Path p:walk.filter(p->Files.isDirectory(p)&&p.getFileName()!=null&&p.getFileName().toString().equals("assets")).toList())roots.add(p.getParent());
            }
            if(roots.isEmpty())roots.add(root); // Metadata-free collections may use relative paths directly.
            roots=roots.stream().distinct().sorted().toList();
            boolean relevant=depth==0;
            if(!relevant)for(Path view:roots){for(Path file:SourceCensus.files(view)){String name=file.getFileName().toString().toLowerCase(Locale.ROOT);if(name.endsWith(".png")||name.endsWith(".json")&&!GeoFormats.classify(name,file).equals("other")){relevant=true;break;}}if(relevant)break;}
            if(relevant){
                String filename=origin.substring(origin.lastIndexOf('/')+1);
                if(filename.isEmpty())filename=container.getFileName()==null?"resources":container.getFileName().toString();
                String[] metadata=metadata(root,filename);
                if(metadata[2].equals("resource"))for(Path view:roots)if(!view.equals(root)){
                    String[] candidate=metadata(view,filename);
                    if(!candidate[2].equals("resource")||!candidate[0].equals(metadata[0])){metadata=candidate;break;}
                }
                String id=sourceId(origin);
                String label=GeoImporter.safeName(metadata[1])+" ["+metadata[2]+" · "+id.substring(9,17)+"]";
                sources.add(new GeoImporter.ModSource(id,label,metadata[2],roots,archive?"EXTERNAL_ARCHIVE":"EXTERNAL_DIRECTORY",metadata[0],origin,domain));
            }
            // A loose mods directory is a single effective pack root, not a request to open more JARs.
            if(unpackedMod)return;
            // Nested packages are inspected only under this explicitly selected source, with finite depth/bytes.
            List<Path> nested;
            try(var walk=Files.walk(root,8)){nested=walk.filter(Files::isRegularFile).filter(p->isArchive(p.getFileName().toString())).limit(65).toList();}
            for(Path child:nested){
                String childOrigin=origin+"!/"+root.relativize(child).toString().replace((char)92,'/');
                if(depth>=3||budget.members>=64){failure(childOrigin,domain,"NESTED_SOURCE_LIMIT; remaining nested packages not traversed");break;}
                long size=Files.size(child);if(size>128L*1024*1024||budget.bytes+size>512L*1024*1024){failure(childOrigin,domain,"NESTED_ARCHIVE_BYTE_BUDGET");continue;}
                budget.bytes+=size;
                try{
                    Path mountedChild=child;
                    if(child.getFileSystem()!=FileSystems.getDefault()){
                        byte[] bytes=Files.readAllBytes(child);Files.createDirectories(cache);mountedChild=cache.resolve(GeoImporter.hash(bytes)+".zip");
                        if(!Files.isRegularFile(mountedChild))Files.write(mountedChild,bytes,StandardOpenOption.CREATE_NEW);
                    }
                    add(mountedChild,childOrigin,domain,depth+1,budget);
                }catch(IOException|RuntimeException ex){failure(childOrigin,domain,ex.toString());}
            }
        }
    }
    static boolean isArchive(String name){String s=name.toLowerCase(Locale.ROOT);return s.endsWith(".jar")||s.endsWith(".zip");}
    private static String sourceId(String origin){return "external_"+GeoImporter.hash(origin.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,20);}
    static Session discover(Path directory,Path cache,Consumer<String> log)throws IOException {
        return discover(directory,cache,null,List.of(),log);
    }
    static Session discover(Path directory,Path cache,Path mods,Collection<GeoImporter.ModSource> loaded,Consumer<String> log)throws IOException {
        Files.createDirectories(directory);Session session=new Session(cache,log);
        try(var items=Files.list(directory)){
            for(Path p:items.sorted().toList())if(Files.isDirectory(p)||isArchive(p.getFileName().toString())){
                String origin=p.toAbsolutePath().normalize().toUri().toString();String domain="external:"+GeoImporter.hash(origin.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                try{session.add(p,origin,domain,0,new Session.NestedBudget());}catch(IOException|RuntimeException ex){session.failure(origin,domain,ex.toString());}
            }
        }catch(IOException|RuntimeException ex){session.close();throw ex;}
        if(mods==null||!Files.isDirectory(mods,LinkOption.NOFOLLOW_LINKS))return session;
        Set<Path> owned=new HashSet<>();
        for(var source:loaded)for(Path root:source.roots()){
            if(root.getFileSystem()==FileSystems.getDefault())owned.add(physical(root));
            else {Path backing=SourceFingerprint.archive(root);if(backing!=null&&backing.getFileSystem()==FileSystems.getDefault())owned.add(physical(backing));}
        }
        try(var items=Files.list(mods)){
            for(Path p:items.sorted().toList()){
                String name=p.getFileName().toString().toLowerCase(Locale.ROOT);
                if(!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)||name.startsWith(".")||name.endsWith("~")||
                   name.matches(".*(?:^|[\\s._()\\[\\]-])(?:disabled|backup|backups|bak)(?:$|[\\s._()\\[\\]-]).*")||
                   name.contains("备份")||name.contains("已禁用"))continue;
                String origin=p.toAbsolutePath().normalize().toUri().toString(),domain="external:"+GeoImporter.hash(origin.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                try{
                    if(Files.isHidden(p)||!Files.isDirectory(p.resolve("assets"),LinkOption.NOFOLLOW_LINKS))continue;
                    Path actual=physical(p);if(owned.stream().anyMatch(root->root.equals(actual)||root.startsWith(actual)))continue;
                    session.add(p,origin,domain,0,new Session.NestedBudget(),true);
                }catch(IOException|RuntimeException ex){session.failure(origin,domain,ex.toString());}
            }
        }catch(IOException|RuntimeException ex){session.failure(mods.toString(),"external:mods-directories",ex.toString());}
        return session;
    }
    private static Path physical(Path path){try{return path.toRealPath();}catch(IOException ex){return path.toAbsolutePath().normalize();}}
    private static String[] metadata(Path root,String fallback){
        String id=fallback.replaceFirst("(?i)[.](jar|zip)$",""),name=id,version="resource";
        try{Path fabric=root.resolve("fabric.mod.json");if(Files.isRegularFile(fabric)){JsonObject j=JsonParser.parseString(Files.readString(fabric)).getAsJsonObject();id=j.has("id")?j.get("id").getAsString():id;name=j.has("name")?j.get("name").getAsString():id;version=j.has("version")?j.get("version").getAsString():version;return new String[]{id,name,version};}
            for(String path:List.of("META-INF/neoforge.mods.toml","META-INF/mods.toml")){Path toml=root.resolve(path);if(!Files.isRegularFile(toml))continue;String text=Files.readString(toml);id=tomlValue(text,"modId",id);name=tomlValue(text,"displayName",id);version=tomlValue(text,"version",version);return new String[]{id,name,version};}
            Path info=root.resolve("mcmod.info");if(Files.isRegularFile(info)){JsonElement j=JsonParser.parseString(Files.readString(info));JsonArray a=j.isJsonArray()?j.getAsJsonArray():j.getAsJsonObject().getAsJsonArray("modList");if(a!=null&&!a.isEmpty()){JsonObject m=a.get(0).getAsJsonObject();id=m.has("modid")?m.get("modid").getAsString():id;name=m.has("name")?m.get("name").getAsString():id;version=m.has("version")?m.get("version").getAsString():version;}}
        }catch(IOException|RuntimeException ignored){}
        return new String[]{id,name,version};
    }
    private static String tomlValue(String text,String key,String fallback){var m=java.util.regex.Pattern.compile("(?m)^\\s*"+key+"\\s*=\\s*[\"']([^\"']+)[\"']").matcher(text);return m.find()?m.group(1):fallback;}
}
