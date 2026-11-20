package local.bbsgeoimporter;

import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** The 1.5.16 limit and failure-scope regressions, without the full resource suite. */
public final class ExternalLimitsAcceptance {
    private static byte[] archive(String namespace)throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var out=new ZipOutputStream(bytes)){
            out.putNextEntry(new ZipEntry("assets/"+namespace+"/geo/a.geo.json"));
            out.write(ImporterAcceptance.GEO.getBytes(StandardCharsets.UTF_8));out.closeEntry();
        }
        return bytes.toByteArray();
    }
    private static void topLevel(Path base)throws Exception {
        Files.createDirectories(base);Path sources=base.resolve("sources");Files.createDirectories(sources);
        for(int i=0;i<65;i++){
            if(i%2==0){Path dir=sources.resolve(String.format("p%02d",i));ImporterAcceptance.put(dir.resolve("assets/tiny_"+i+"/geo/a.geo.json"),ImporterAcceptance.GEO);}
            else Files.write(sources.resolve(String.format("p%02d.jar",i)),archive("tiny_"+i));
        }
        Path nested=sources.resolve("p64/bundles/inner.zip");Files.createDirectories(nested.getParent());Files.write(nested,archive("nested_only"));
        try(var session=ExternalSources.discover(sources,base.resolve("cache"),x->{})){
            ImporterAcceptance.check(session.sources().size()==66,"all 65 top-level packages plus the nested resource package discovered");
            ImporterAcceptance.check(session.failures().isEmpty(),"top-level packages never consume the nested-member limit");
            ImporterAcceptance.check(session.sources().stream().filter(s->!s.containerPath().contains("!/")).count()==65,"top-level JARs and directories all remain authoritative sources");
        }
        System.out.println("EXTERNAL LIMIT PASS: topLevel=65 nested=1 failures=0");
    }
    private static void removedWithBadNeighbor(Path base)throws Exception {
        Path version=base.resolve("version"),config=version.resolve("config"),sources=version.resolve("bbs-geo-sources");Files.createDirectories(sources);
        Path a=sources.resolve("a.zip"),b=sources.resolve("b.zip");Files.write(a,archive("removed_a"));Files.write(b,archive("failed_b"));
        Path loaded=base.resolve("loaded");ImporterAcceptance.put(loaded.resolve("assets/removed_loaded/geo/a.geo.json"),ImporterAcceptance.GEO);var live=ImporterAcceptance.source("removed_loaded","Loaded removable",loaded);
        LiveStartupAcceptance.boot(config,List.of(live));
        String keyA=ImportedModelGroups.modelKeys().stream().filter(k->k.contains("/assets/removed_a/")).findFirst().orElseThrow();
        String keyB=ImportedModelGroups.modelKeys().stream().filter(k->k.contains("/assets/failed_b/")).findFirst().orElseThrow();
        String keyLoaded=ImportedModelGroups.modelKeys().stream().filter(k->k.contains("/assets/removed_loaded/")).findFirst().orElseThrow();
        Path output=config.resolve("bbs/assets/models").resolve(keyA).resolve("a.geo.json");byte[] original=Files.readAllBytes(output);
        Files.move(a,base.resolve("retained-a.zip"));Files.writeString(b,"broken ZIP fixture");
        LiveStartupAcceptance.boot(config,List.of());
        ImporterAcceptance.check(ImportedModelGroups.inactive(keyA)&&!ImportedModelGroups.modelKeys().contains(keyA),"removed A is hidden despite an unrelated bad B");
        ImporterAcceptance.check(ImportedModelGroups.inactive(keyLoaded)&&!ImportedModelGroups.modelKeys().contains(keyLoaded),"bad external B does not invalidate complete loaded-Mod absence");
        ImporterAcceptance.check(!ImportedModelGroups.inactive(keyB)&&ImportedModelGroups.modelKeys().contains(keyB),"only unreadable B retains its own prior model as unknown");
        ImporterAcceptance.check(Files.isRegularFile(output)&&Arrays.equals(original,Files.readAllBytes(output)),"A historical owned file remains byte-for-byte on disk");
        var nativeKeys=ImportedModelGroups.filterAvailable(new Object(),new ArrayList<>(List.of(keyA,keyB,keyLoaded,"user/custom")));
        ImporterAcceptance.check(!nativeKeys.contains(keyA)&&!nativeKeys.contains(keyLoaded)&&nativeKeys.contains(keyB)&&nativeKeys.contains("user/custom"),"native BBS enumeration removes only inactive exact managed keys");
        ImporterAcceptance.check(LoaderSources.discoveryComplete(),"bad external archive never changes loader discovery completeness");
        System.out.println("EXTERNAL FAILURE SCOPE PASS: removed external/loaded A hidden; bad B alone unknown; owned disk and user keys preserved");
    }
    public static void main(String[] args)throws Exception {
        Path base=Files.createTempDirectory(Path.of("build"),"external-limits-");
        topLevel(base.resolve("limits"));removedWithBadNeighbor(base.resolve("failure"));
    }
}
