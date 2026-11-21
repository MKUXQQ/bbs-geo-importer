package local.bbsgeoimporter;

import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import static org.objectweb.asm.Opcodes.*;

/** Real switch arms, an extensionless branch, and a decoy constructor in the same return method. */
final class ReturnedPairAcceptance {
    static void run(Path workspace,Path mods,Path bbsJar)throws Exception{
        Path source;try(var children=Files.list(mods)){source=children.filter(p->Files.exists(p.resolve("com/cobblemon/mod/common/entity/PlatformType$Companion.class"))).findFirst().orElseThrow();}
        Path subset=workspace.resolve("input"),config=workspace.resolve("config");
        for(String file:List.of("com/cobblemon/mod/common/entity/PlatformType$Companion.class","com/cobblemon/mod/common/util/MiscUtilsKt.class"))
            copy(source.resolve(file),subset.resolve(file));
        for(String size:List.of("xs","s","m","l","xl"))for(String path:List.of(realGeo(size),realPng(size)))copy(source.resolve(path),subset.resolve(path));
        String ns="pair_"+UUID.randomUUID().toString().replace("-","");
        Path artificial=workspace.resolve("synthetic");
        for(String name:List.of("discarded","returned"))copy(source.resolve(realGeo("s")),artificial.resolve("assets/"+ns+"/unusual/"+name+".geo.json"));
        ImporterAcceptance.png(artificial.resolve("assets/"+ns+"/paint/right.png"),0xff118833);
        ImporterAcceptance.png(artificial.resolve("assets/"+ns+"/paint/decoy.png"),0xff773388);
        ClassWriter writer=new ClassWriter(0);writer.visit(61,ACC_PUBLIC,"samples/Fabricator",null,"java/lang/Object",null);
        var m=writer.visitMethod(ACC_PUBLIC|ACC_STATIC,"pairs","()Lkotlin/Pair;",null,null);
        m.visitCode();pair(m,ns,"discarded","decoy");m.visitInsn(POP);pair(m,ns,"returned","right");m.visitInsn(ARETURN);m.visitMaxs(5,0);m.visitEnd();writer.visitEnd();
        Path generated=artificial.resolve("samples/Fabricator.class");Files.createDirectories(generated.getParent());Files.write(generated,writer.toByteArray());
        new GeoImporter(config,s->{}).scan(List.of(ImporterAcceptance.source("real_pair_fixture","Real pair",subset),ImporterAcceptance.source(ns,"Synthetic pair",artificial)));
        var proof=new TreeMap<String,SharedModelAssets.Binding>();
        var bindings=SharedModelAssets.bindingsForTest();Path assets=config.resolve("bbs/assets");
        for(String size:List.of("xs","s","m","l","xl")){
            var b=bindings.entrySet().stream().filter(e->e.getKey().endsWith("/water_platform_"+size)).findFirst().orElseThrow();
            check(b.getValue().texture()!=null&&Files.mismatch(source.resolve(realPng(size)),assets.resolve(b.getValue().texture()))==-1,"same returned arm: "+size);proof.put(b.getKey(),b.getValue());
        }
        var wanted=bindings.entrySet().stream().filter(e->e.getKey().endsWith("/returned")).findFirst().orElseThrow();proof.put(wanted.getKey(),wanted.getValue());
        check(wanted.getValue().texture()!=null&&wanted.getValue().texture().endsWith("/paint/right.png"),"returned Pair from unknown namespace");
        check(bindings.entrySet().stream().filter(e->e.getKey().endsWith("/discarded")).findFirst().orElseThrow().getValue().texture()==null,"discarded NEW Pair in same method never becomes a binding");
        var report=ImporterAcceptance.json(config.resolve("bbs/model-import-report.json"));var rows=report.getAsJsonArray("models");
        boolean extensionless=false;
        for(var v:rows){var row=v.getAsJsonObject();if(!row.get("sourcePath").getAsString().equals(realGeo("xs")))continue;
            for(var r:row.getAsJsonArray("textureReferences")){var req=r.getAsJsonObject();if(req.get("reference").getAsString().endsWith("/water_platform_xs")&&"UNRESOLVED".equals(req.get("status").getAsString()))extensionless=true;}
            check(!"FULL_TEXTURES".equals(row.get("textureStatus").getAsString()),"XS incomplete branch remains reported");}
        check(extensionless,"extensionless default branch diagnosed without guessed suffix");
        BbsCpuAcceptance.run(assets,proof,bbsJar);
        System.out.println("Returned resource Pair: all five real water-platform branches and foreign pair verified after BBS applyConfig; discarded Pair excluded and extensionless XS branch recorded.");
    }
    private static String realGeo(String s){return "assets/cobblemon/bedrock/misc/models/water_platform_"+s+".geo.json";}
    private static String realPng(String s){return "assets/cobblemon/textures/platforms/water_platform_"+s+".png";}
    private static void pair(MethodVisitor m,String ns,String geo,String texture){
        m.visitTypeInsn(NEW,"kotlin/Pair");m.visitInsn(DUP);
        for(String path:List.of(geo+".geo","paint/"+texture+".png")){
            m.visitLdcInsn(ns);m.visitLdcInsn(path);m.visitMethodInsn(INVOKESTATIC,"net/minecraft/resources/ResourceLocation","fromNamespaceAndPath","(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;",false);}
        m.visitMethodInsn(INVOKESPECIAL,"kotlin/Pair","<init>","(Ljava/lang/Object;Ljava/lang/Object;)V",false);
    }
    private static void copy(Path src,Path dest)throws Exception{Files.createDirectories(dest.getParent());Files.copy(src,dest);}
    private static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
}
