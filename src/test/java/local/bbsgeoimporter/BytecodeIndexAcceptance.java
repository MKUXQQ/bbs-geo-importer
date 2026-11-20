package local.bbsgeoimporter;

import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/** Immutable interpreter indexes must preserve resource edges and avoid indexing unrelated method code. */
public final class BytecodeIndexAcceptance {
    public static void main(String[] args)throws Exception {
        Path base=Files.createTempDirectory(Path.of("build"),"bytecode-index-");
        EnumSelectorAcceptance.run(base.resolve("enum"));
        FactoryUvAcceptance.run(base.resolve("factory"));
        Path source=base.resolve("enum/source");
        String namespace;
        try(var names=Files.list(source.resolve("assets"))){namespace=names.findFirst().orElseThrow().getFileName().toString();}
        syncedDefaultFixture(source,namespace);

        // These methods are intentionally irrelevant: traversing class metadata must not create code arrays.
        for(int i=0;i<96;i++){
            String name="unrelated/Library"+i;
            ClassWriter writer=new ClassWriter(0);
            writer.visit(61,Opcodes.ACC_PUBLIC,name,null,"java/lang/Object",null);
            for(int j=0;j<40;j++){
                var method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"number"+j,"()I",null,null);
                method.visitCode();method.visitLdcInsn(j);method.visitInsn(Opcodes.IRETURN);method.visitMaxs(1,0);method.visitEnd();
            }
            writer.visitEnd();
            Path file=source.resolve(name+".class");Files.createDirectories(file.getParent());Files.write(file,writer.toByteArray());
        }
        ResourceCatalog catalog=new ResourceCatalog(List.of(ImporterAcceptance.source(namespace,"Unfamiliar selector",source)));
        long classes;
        try(var files=Files.walk(source)){classes=files.filter(p->p.toString().endsWith(".class")).count();}
        long parsed=count(catalog,"bytecodeClassesParsed"),built=count(catalog,"bytecodeCodeBuilds"),reused=count(catalog,"bytecodeCodeReuses"),evaluated=count(catalog,"bytecodeMethodEvaluations");
        ImporterAcceptance.check(parsed==classes,"each present class is parsed once");
        ImporterAcceptance.check(built<classes,"unrelated class methods never allocate interpreter code indexes");
        ImporterAcceptance.check(reused>0&&evaluated>built,"repeated method evaluation reuses its instruction and label index");
        ImporterAcceptance.check(count(catalog,"bytecodeSyncedDefaultReuses")>0,"same immutable synced default analysis is reused");
        for(var bundle:catalog.bundles.entrySet()){
            String color=bundle.getKey().contains("shape_ochre")?"ochre":"indigo";
            var textures=bundle.getValue().stream().filter(e->e.resource().endsWith(".png")).map(ResourceCatalog.Edge::resource).toList();
            ImporterAcceptance.check(textures.size()==1&&textures.get(0).endsWith("/textures/palettes/"+color+"_skin.png"),"same finite selector retains the exact model-texture relation");
        }
        ImporterAcceptance.check(catalog.bundles.size()==2,"all finite selector geometries remain present");
        System.out.println("BYTECODE INDEX PASS: classes="+parsed+", methodEvaluations="+evaluated+", codeIndexes="+built+", codeReuses="+reused+", unrelatedMethods=3840; enum/factory final BBS CPU Links preserved");
    }
    private static long count(ResourceCatalog catalog,String name){return catalog.report.get(name).getAsLong();}
    private static void syncedDefaultFixture(Path source,String namespace)throws Exception {
        String carrier="unfamiliar/SyncedCarrier",data="net/minecraft/network/syncher/SynchedEntityData",accessor="Lnet/minecraft/network/syncher/EntityDataAccessor;";
        ClassWriter writer=new ClassWriter(0);writer.visit(61,Opcodes.ACC_PUBLIC,carrier,null,"java/lang/Object",null);
        writer.visitField(Opcodes.ACC_STATIC,"TYPE",accessor,null,null).visitEnd();writer.visitField(0,"data","L"+data+";",null,null).visitEnd();
        var getter=writer.visitMethod(Opcodes.ACC_PUBLIC,"kind","()Ljava/lang/String;",null,null);getter.visitCode();getter.visitVarInsn(Opcodes.ALOAD,0);getter.visitFieldInsn(Opcodes.GETFIELD,carrier,"data","L"+data+";");getter.visitFieldInsn(Opcodes.GETSTATIC,carrier,"TYPE",accessor);getter.visitMethodInsn(Opcodes.INVOKEVIRTUAL,data,"get","("+accessor+")Ljava/lang/Object;",false);getter.visitTypeInsn(Opcodes.CHECKCAST,"java/lang/String");getter.visitInsn(Opcodes.ARETURN);getter.visitMaxs(2,1);getter.visitEnd();
        var define=writer.visitMethod(Opcodes.ACC_PUBLIC,"defineSynchedData","()V",null,null);define.visitCode();define.visitVarInsn(Opcodes.ALOAD,0);define.visitFieldInsn(Opcodes.GETFIELD,carrier,"data","L"+data+";");define.visitFieldInsn(Opcodes.GETSTATIC,carrier,"TYPE",accessor);define.visitLdcInsn("ochre");define.visitMethodInsn(Opcodes.INVOKEVIRTUAL,data,"define","("+accessor+"Ljava/lang/Object;)V",false);define.visitInsn(Opcodes.RETURN);define.visitMaxs(3,1);define.visitEnd();writer.visitEnd();
        Path file=source.resolve(carrier+".class");Files.createDirectories(file.getParent());Files.write(file,writer.toByteArray());
        writer=new ClassWriter(0);writer.visit(61,Opcodes.ACC_PUBLIC,"unfamiliar/SyncedModel",null,"java/lang/Object",null);
        Handle concat=new Handle(Opcodes.H_INVOKESTATIC,"java/lang/invoke/StringConcatFactory","makeConcatWithConstants","(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",false);
        for(String name:List.of("getModelResource","getTextureResource")){
            var method=writer.visitMethod(Opcodes.ACC_PUBLIC,name,"(L"+carrier+";)Lnet/minecraft/resources/ResourceLocation;",null,null);method.visitCode();method.visitLdcInsn(namespace);method.visitVarInsn(Opcodes.ALOAD,1);method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,carrier,"kind","()Ljava/lang/String;",false);method.visitInvokeDynamicInsn("makeConcatWithConstants","(Ljava/lang/String;)Ljava/lang/String;",concat,name.equals("getModelResource")?"geo/shape_\u0001.geo.json":"textures/palettes/\u0001_skin.png");method.visitMethodInsn(Opcodes.INVOKESTATIC,"net/minecraft/resources/ResourceLocation","fromNamespaceAndPath","(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;",false);method.visitInsn(Opcodes.ARETURN);method.visitMaxs(2,2);method.visitEnd();
        }
        writer.visitEnd();Files.write(source.resolve("unfamiliar/SyncedModel.class"),writer.toByteArray());
    }
}
