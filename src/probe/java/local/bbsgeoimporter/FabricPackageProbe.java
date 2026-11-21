package local.bbsgeoimporter;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.*;
import net.fabricmc.api.*;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.loader.impl.metadata.*;
import net.fabricmc.fabric.api.resource.*;
import net.fabricmc.fabric.impl.resource.loader.ResourceManagerHelperImpl;
import net.minecraft.*;

/** Runs only with the final JAR, pure Fabric APIs, and the real intermediary Minecraft classes. */
public class FabricPackageProbe {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Path jar=Path.of(args[0]),dir=Files.createTempDirectory(Path.of(args[1]),"fabric-");
        ClassLoader loader=FabricPackageProbe.class.getClassLoader();
        try{Class.forName("net.minecraftforge.fml.loading.FMLLoader",false,loader);throw new AssertionError("Forge in pure Fabric probe");}catch(ClassNotFoundException expected){}
        try(JarFile archive=new JarFile(jar.toFile());var input=archive.getInputStream(archive.getJarEntry("fabric.mod.json"))){
            var meta=ModMetadataParser.parseMetadata(input,jar.toString(),List.of(),new VersionOverrides(),new DependencyOverrides(dir),false);
            var entrypoints=meta.getEntrypoints("client");
            check(meta.getId().equals("bbs_geo_importer")&&entrypoints.size()==1&&meta.getMixinConfigs(EnvType.CLIENT).size()==1,"Fabric metadata");
            var entry=Class.forName(entrypoints.get(0).getValue()).getConstructor().newInstance();
            check(entry instanceof ClientModInitializer&&ClientBootstrap.claim(false,loader)&&!ClientBootstrap.claim(false,loader),"single native Fabric ownership");
        }
        MappingResolver mapping=(MappingResolver)Proxy.newProxyInstance(loader,new Class[]{MappingResolver.class},(p,m,a)->switch(m.getName()){
            case "mapFieldName","mapMethodName"->a[2];case "mapClassName"->a[1];default->throw new AssertionError(m);});
        Path packRoot=dir.resolve("source"),geo=packRoot.resolve("assets/example/geo/unit.geo.json");Files.createDirectories(geo.getParent());
        byte[] body="{\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.probe\",\"texture_width\":16,\"texture_height\":16},\"bones\":[]}]}".getBytes();
        Files.write(geo,body);
        var type=class_3264.field_14188;var id=new class_2960("example","geo/unit.geo.json");
        try(var pack=new class_3259("fixture-provider",packRoot,false)){
            var resource=new class_3298(pack,pack.method_14405(type,id));
            Object manager=Proxy.newProxyInstance(loader,new Class[]{class_3300.class},(p,m,a)->switch(m.getName()){
                case "method_14486"->Objects.equals(id,a[0])?Optional.of(resource):Optional.empty();
                case "method_14488"->"geo".equals(a[0])?Map.of(id,resource):Map.of();
                case "method_29213"->java.util.stream.Stream.of(pack);
                default->throw new AssertionError(m);});
            var reader=ResourceReaders.platform(manager,class_5912.class,class_2960.class,class_3298.class,class_3300.class,class_3262.class,"method_14486","method_14482","method_14480","method_45304","method_14488","method_29213","method_14409");
            var bytes=reader.read(1,id.toString());check(Arrays.equals(body,bytes.content())&&bytes.supplier().equals("fixture-provider")&&reader.version(id.toString())!=null&&reader.fingerprint()!=null&&reader.addresses().contains(id.toString()),"real Fabric bytes, provider and fingerprints");
            AtomicInteger scans=new AtomicInteger(),barriers=new AtomicInteger(),commits=new AtomicInteger();
            var service=new ScanService(dir.resolve("config"),()->{scans.incrementAndGet();return List.of(new GeoImporter.ModSource("example","Example","1",List.of(packRoot)));},System.out::println);
            service.bbsRefreshActionForTest(()->{commits.incrementAndGet();return true;});
            FabricEntrypoint.registerReload(service,mapping);
            var helper=ResourceManagerHelper.get(type);var field=ResourceManagerHelperImpl.class.getDeclaredField("addedListeners");field.setAccessible(true);
            var listeners=((Collection<?>)field.get(helper)).stream().filter(x->((IdentifiableResourceReloadListener)x).getFabricId().toString().equals("bbs_geo_importer:scan")).toList();check(listeners.size()==1,"one listener registered with actual Fabric API");
            var listener=(IdentifiableResourceReloadListener)listeners.get(0);
            var barrier=new class_3302.class_4045(){public <T> CompletableFuture<T> method_18352(T value){barriers.incrementAndGet();return CompletableFuture.completedFuture(value);}};
            for(int i=0;i<2;i++)listener.method_25931(barrier,(class_3300)manager,null,null,Runnable::run,Runnable::run).get(30,TimeUnit.SECONDS);
            check(scans.get()==1&&commits.get()==1&&barriers.get()==2&&service.status().startsWith("BBS Geo: ready")&&!ImportedModelGroups.modelKeys().isEmpty(),"listener must publish once and honor every barrier");
            System.out.println("FABRIC PACKAGE PASS: native metadata, entrypoint, actual API registration, resource bytes/provider/fingerprints, one scan and one publication through two reloads.");
        }
    }
}
