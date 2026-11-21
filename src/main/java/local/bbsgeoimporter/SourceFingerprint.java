package local.bbsgeoimporter;
import java.nio.file.*;
import java.util.*;
import java.security.*;
/** Content fingerprints are computed on the IO worker, never the client thread. */
final class SourceFingerprint {
    static final String PARSER_SCHEMA="1522-frames-pairs";
    private static final ThreadLocal<Map<Path,String>> scope=new ThreadLocal<>();
    private static final ThreadLocal<byte[]> readBuffer=new ThreadLocal<>();
    private static final ThreadLocal<com.google.gson.JsonObject> metadata=new ThreadLocal<>();
    private static final ThreadLocal<Path> metadataFile=new ThreadLocal<>();
    private static final ThreadLocal<Integer> depth=ThreadLocal.withInitial(()->0);
    static void begin(){int n=depth.get();depth.set(n+1);if(n==0)scope.set(new HashMap<>());}
    static Map<Path,String> snapshot(){return scope.get()==null?Map.of():Map.copyOf(scope.get());}
    static void resume(Map<Path,String> values){begin();scope.get().putAll(values);}
    static void begin(Path file){begin();if(depth.get()>1)return;try{for(Path ancestor=file;ancestor!=null;ancestor=ancestor.getParent())if(Files.isSymbolicLink(ancestor))return;metadataFile.set(file);metadata.set(Files.isRegularFile(file)?com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject():new com.google.gson.JsonObject());}catch(Exception ignored){metadata.set(new com.google.gson.JsonObject());}}
    static void end(){int n=depth.get()-1;depth.set(n);if(n>0)return;try{Path file=metadataFile.get();var data=metadata.get();if(file!=null&&data!=null){String value=data.toString();if(!Files.isRegularFile(file)||!Files.readString(file).equals(value)){Files.createDirectories(file.getParent());Path temp=Files.createTempFile(file.getParent(),"fingerprint-",".tmp");try{Files.writeString(temp,value);Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}}}catch(Exception ignored){}finally{scope.remove();readBuffer.remove();depth.remove();metadata.remove();metadataFile.remove();}}
    private static String directoryFile(Path p)throws Exception{return digest(p);}
    static String digest(Path file)throws Exception {Map<Path,String> memo=scope.get();Path key=file.toAbsolutePath().normalize();String cached=memo==null?null:memo.get(key);if(cached!=null){Performance.count("source.hashReuse",0);return cached;}long t=Performance.start(),size=0;MessageDigest d=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(file)){byte[] b=memo==null?null:readBuffer.get();if(b==null){b=new byte[131072];if(memo!=null)readBuffer.set(b);Performance.count("source.hashBufferAllocate",b.length);}int n;while((n=in.read(b))>=0){d.update(b,0,n);size+=n;}}Performance.event("source.hash",System.nanoTime()-t,size);String value=HexFormat.of().formatHex(d.digest());if(memo!=null)memo.put(key,value);return value;}
    static Path archive(Path root){try{if(root.getFileSystem().provider().getScheme().equals("union"))return (Path)root.getFileSystem().getClass().getMethod("getPrimaryPath").invoke(root.getFileSystem());String uri=root.toUri().toString();if(uri.startsWith("jar:")){int split=uri.indexOf("!");return Path.of(java.net.URI.create(uri.substring(4,split).replace("[","%5B").replace("]","%5D")));}}catch(Exception ignored){}return null;}
    private static final java.util.regex.Pattern ZIP_CRC=java.util.regex.Pattern.compile("(?m)^\\s*crc\\s*:\\s*([0-9a-fA-F]{1,8})\\s*$");
    private static String unionView(Path root)throws Exception{
        long start=Performance.start();Path primary=archive(root);
        StringBuilder visible=new StringBuilder("union-view-v2\n");
        if(primary!=null)visible.append(cachedRoot(primary)).append('\n');
        int count=0;
        for(Path file:SourceCensus.files(root).stream().sorted().toList()){
            visible.append(root.relativize(file)).append('|');
            var attrs=Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            // SecureJarHandler exposes the winning ZIP entry attributes through its public NIO API.
            // Java 17 zipfs does not export its CRC accessor; reject an unrecognized representation.
            if(attrs.getClass().getModule().getName()!=null&&attrs.getClass().getModule().getName().equals("jdk.zipfs")){
                var crc=ZIP_CRC.matcher(attrs.toString());
                if(crc.find()){visible.append("zip:").append(attrs.size()).append(':').append(crc.group(1)).append('\n');count++;continue;}
            }
            visible.append("sha:").append(digest(file)).append('\n');
        }
        Performance.event("source.unionZipMetadata",System.nanoTime()-start,count);
        return GeoImporter.hash(visible.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    static String root(Path root)throws Exception {
        String scheme=root.getFileSystem().provider().getScheme();
        if(Files.isRegularFile(root)&&scheme.equals("file"))return digest(root);
        if(scheme.equals("union"))return unionView(root);
        Path jar=archive(root);
        if(jar!=null&&Files.isRegularFile(jar)){
            StringBuilder entries=new StringBuilder();
            for(Path file:SourceCensus.files(root).stream().sorted().toList())entries.append(root.relativize(file)).append('|').append(Files.size(file)).append('|').append(Files.getLastModifiedTime(file)).append('\n');
            return jar.toAbsolutePath().normalize()+"|"+digest(jar)+"|"+GeoImporter.hash(entries.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        StringBuilder b=new StringBuilder();for(Path p:SourceCensus.files(root).stream().sorted().toList())b.append(root.relativize(p)).append('|').append(directoryFile(p)).append('\n');
        return GeoImporter.hash(b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    static String cachedRoot(Path p)throws Exception{Map<Path,String> cache=scope.get();if(cache==null)return root(p);Path key=p.toAbsolutePath().normalize();String fp=cache.get(key);if(fp==null){fp=root(p);cache.put(key,fp);}return fp;}
    static String sources(List<GeoImporter.ModSource> sources)throws Exception {StringBuilder b=new StringBuilder("catalog-schema-3-parser-"+PARSER_SCHEMA+"\n");for(var s:LoaderSources.merge(sources)){if(s.id().equals("bbs_geo_importer"))continue;b.append(s.id()).append('|').append(s.name()).append('|').append(s.version()).append('|').append(s.kind()).append('|').append(s.displayModId()).append('|').append(s.containerPath()).append('|').append(s.resourceDomain()).append('\n');for(Path p:s.roots())b.append(cachedRoot(p)).append('\n');}return GeoImporter.hash(b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private SourceFingerprint(){}
}
