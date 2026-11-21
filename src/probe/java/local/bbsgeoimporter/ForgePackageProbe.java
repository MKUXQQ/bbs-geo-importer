package local.bbsgeoimporter;

import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import cpw.mods.jarhandling.SecureJar;
import net.minecraftforge.fml.loading.moddiscovery.*;
import net.minecraftforge.forgespi.language.ModFileScanData;
import org.objectweb.asm.*;

public class ForgePackageProbe {
    public static void main(String[] args)throws Exception{
        Path jar=Path.of(args[0]);var loader=ForgePackageProbe.class.getClassLoader();
        try{Class.forName("net.fabricmc.api.ClientModInitializer",false,loader);throw new AssertionError("Fabric API on pure Forge classpath");}catch(ClassNotFoundException expected){}
        var version=net.minecraftforge.fml.loading.FMLLoader.class.getDeclaredField("versionInfo");version.setAccessible(true);
        version.set(null,new net.minecraftforge.fml.loading.VersionInfo("47.4.28","1.20.1","20230612.114412","net.minecraftforge"));
        var file=new ModFile(SecureJar.from(jar),null,ModFileParser::modsTomlParser);
        var mods=file.getModInfos();if(mods.size()!=1||!mods.get(0).getModId().equals("bbs_geo_importer")||!mods.get(0).getVersion().toString().equals("1.5.22"))throw new AssertionError("Forge metadata");
        var data=new ModFileScanData();
        try(var archive=new JarFile(jar.toFile())){
            for(var entry:Collections.list(archive.entries()))if(entry.getName().endsWith(".class"))try(var stream=archive.getInputStream(entry)){
                var visitor=new ModClassVisitor();new ClassReader(stream).accept(visitor,ClassReader.SKIP_CODE);visitor.buildData(data.getClasses(),data.getAnnotations());
            }
        }
        var annotated=data.getAnnotations().stream().filter(a->a.annotationType().getClassName().equals("net.minecraftforge.fml.common.Mod")&&a.annotationData().get("value").equals("bbs_geo_importer")).toList();
        if(annotated.size()!=1||!annotated.get(0).clazz().getClassName().equals(ForgeEntrypoint.class.getName()))throw new AssertionError("JavaFML class scan");
        ForgeEntrypoint.class.getConstructor();
        if(ClientBootstrap.claim(false,loader)||!ClientBootstrap.claim(true,loader)||ClientBootstrap.claim(true,loader))throw new AssertionError("mixed-loader ownership");
        System.out.println("FORGE PACKAGE PASS: real Forge ModFile parser and class scanner, one JavaFML entrypoint, no Fabric API, Forge owns mixed startup once.");
    }
}
