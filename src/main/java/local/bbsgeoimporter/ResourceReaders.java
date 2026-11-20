package local.bbsgeoimporter;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
/** Uses public resource-provider methods, never concrete reload-manager internals. */
public final class ResourceReaders {
    public static EffectiveResources.Reader create(Object manager,Class<?> provider,Class<?> id,Class<?> resource,String get,String open,String pack)throws Exception {
        Method lookup=provider.getMethod(get,id),stream=resource.getMethod(open),supplier=resource.getMethod(pack);
        Constructor<?> identifier=id.getConstructor(String.class);
        Method source;try{source=resource.getMethod("m_247173_");}catch(NoSuchMethodException ex){source=resource.getMethod("source");}
        Method winningPack=source;
        return new EffectiveResources.Reader(){
            final Map<Object,String> packVersions=new IdentityHashMap<>();
            final Map<java.nio.file.Path,String> checked=new HashMap<>();
            Object lookup(String address)throws Exception{return ((Optional<?>)lookup.invoke(manager,identifier.newInstance(address))).orElse(null);}
            public String version(String address)throws Exception{
                Object value=lookup(address);if(value==null)return null;Object winner=winningPack.invoke(value);
                if(!packVersions.containsKey(winner)){StringBuilder data=new StringBuilder();String version=null;try{if(packIdentity(winner,data,checked))version=GeoImporter.hash(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}packVersions.put(winner,version);}
                return packVersions.get(winner);
            }
            public ResourceEvidence.Snapshot read(long generation,String address)throws Exception{
            Object value=lookup(address);
            if(value==null)return null;
            try(InputStream input=(InputStream)stream.invoke(value)){
                byte[] bytes=input.readNBytes(64*1024*1024+1);
                if(bytes.length>64*1024*1024)throw new IOException("Resource exceeds 64 MiB");
                return new ResourceEvidence.Snapshot(generation,address,String.valueOf(supplier.invoke(value)),bytes);
            }
            }
        };
    }
    public static EffectiveResources.Reader enumerate(EffectiveResources.Reader reader,Object manager,Class<?> managerType,String name)throws Exception{
        Method list=managerType.getMethod(name,String.class,java.util.function.Predicate.class);
        return new EffectiveResources.Reader(){
            public ResourceEvidence.Snapshot read(long g,String a)throws Exception{return reader.read(g,a);}
            public String version(String a)throws Exception{return reader.version(a);}
            public Object identity(){return manager;}
            public String fingerprint()throws Exception{try{return packFingerprint(manager,managerType);}catch(Exception ex){return null;}}
            public Set<String> addresses()throws Exception{return addresses(Set.of("geo","models","textures","animations","bedrock"));}
            public Set<String> addresses(Set<String> prefixes)throws Exception{
                Set<String> values=new TreeSet<>();java.util.function.Predicate<Object> filter=id->{String s=id.toString().toLowerCase(Locale.ROOT);return s.endsWith(".json")||s.endsWith(".png");};
                // Forge rejects an empty directory; include actual container prefixes supplied by the census.
                for(String prefix:prefixes){if(prefix.isEmpty()||prefix.contains("/")||prefix.equals(".."))throw new IllegalArgumentException("Invalid resource directory: "+prefix);for(Object id:((Map<?,?>)list.invoke(manager,prefix,filter)).keySet()){try{String address=ResourceEvidence.address(id.toString());if(address!=null)values.add(address);}catch(IllegalArgumentException invalid){/* Invalid game IDs cannot be opened through ResourceManager; raw-container scan still includes them. */}}}
                return values;
            }
        };
    }
    /** Disk entries are only lookup hints; the active manager still chooses bytes and supplier. */
    public static EffectiveResources.Reader localPacks(EffectiveResources.Reader reader,java.nio.file.Path directory){
        return new EffectiveResources.Reader(){
            public ResourceEvidence.Snapshot read(long g,String a)throws Exception{return reader.read(g,a);}
            public String version(String a)throws Exception{return reader.version(a);}
            public String fingerprint()throws Exception{return reader.fingerprint();}
            public Set<String> addresses()throws Exception{return addresses(Set.of("geo","models","textures","animations","bedrock"));}
            public Set<String> addresses(Set<String> prefixes)throws Exception{
                Set<String> values=new TreeSet<>(reader.addresses(prefixes));
                if(!java.nio.file.Files.isDirectory(directory,java.nio.file.LinkOption.NOFOLLOW_LINKS))return values;
                try(var packs=java.nio.file.Files.list(directory)){
                    for(var pack:packs.toList()){
                        if(java.nio.file.Files.isSymbolicLink(pack))continue;
                        if(java.nio.file.Files.isDirectory(pack,java.nio.file.LinkOption.NOFOLLOW_LINKS)){
                            try(var files=java.nio.file.Files.walk(pack)){for(var file:files.filter(p->java.nio.file.Files.isRegularFile(p,java.nio.file.LinkOption.NOFOLLOW_LINKS)).toList())addPackAddress(values,pack.relativize(file).toString().replace('\\','/'));}
                        }else if(pack.toString().toLowerCase(Locale.ROOT).endsWith(".zip")){
                            try(var zip=new java.util.zip.ZipFile(pack.toFile())){var entries=zip.entries();while(entries.hasMoreElements()){var entry=entries.nextElement();if(!entry.isDirectory())addPackAddress(values,entry.getName());}}
                        }
                    }
                }
                return values;
            }
        };
    }
    private static void addPackAddress(Set<String> values,String path){
        if(!path.startsWith("assets/")||!(path.endsWith(".json")||path.endsWith(".png")))return;
        int slash=path.indexOf('/',7);if(slash<0)return;
        try{values.add(ResourceEvidence.address(path.substring(7,slash)+":"+path.substring(slash+1)));}catch(IllegalArgumentException invalid){/* Invalid paths cannot be requested from the active manager. */}
    }
    private ResourceReaders(){}
    private static String packFingerprint(Object manager,Class<?> type)throws Exception{
        Method method;try{method=type.getMethod("m_7536_");}catch(NoSuchMethodException ex){method=type.getMethod("listPacks");}
        StringBuilder b=new StringBuilder();Map<java.nio.file.Path,String> checked=new HashMap<>();
        try(var packs=(java.util.stream.Stream<?>)method.invoke(manager)){for(Object pack:packs.toList())if(!packIdentity(pack,b,checked))return null;}
        return GeoImporter.hash(b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static boolean packIdentity(Object pack,StringBuilder b,Map<java.nio.file.Path,String> checked)throws Exception{
        Class<?> api=Class.forName("net.minecraft.server.packs.PackResources");Method id;try{id=api.getMethod("m_5542_");}catch(NoSuchMethodException ex){id=api.getMethod("packId");}b.append(pack.getClass().getName()).append('|').append(id.invoke(pack)).append('\n');
        boolean known=false;for(Class<?> c=pack.getClass();c!=null;c=c.getSuperclass())for(var f:c.getDeclaredFields()){if(java.lang.reflect.Modifier.isStatic(f.getModifiers()))continue;f.setAccessible(true);Object value=f.get(pack);List<java.nio.file.Path> paths=new ArrayList<>();if(value instanceof java.nio.file.Path p)paths.add(p);else if(value instanceof java.io.File p)paths.add(p.toPath());else if(value instanceof List<?> list){for(Object entry:list){if(entry instanceof java.nio.file.Path p)paths.add(p);else if(api.isInstance(entry)){if(!packIdentity(entry,b,checked))return false;known=true;}}}
            for(var p:paths){String fp=checked.get(p);if(fp==null){fp=SourceFingerprint.cachedRoot(p);checked.put(p,fp);}b.append(fp).append('\n');known=true;}
        }return known; // Unknown/generated packs are always recaptured, never trusted by pack name alone.
    }
}
