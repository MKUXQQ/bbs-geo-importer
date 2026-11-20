package local.bbsgeoimporter;

import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/** Synthetic names and unrelated PNG basenames prove one finite selector pairs both getters. */
final class EnumSelectorAcceptance {
    static void run(Path base)throws Exception {
        String ns="finite_"+UUID.randomUUID().toString().replace("-","");
        String type="arbitrary/Mode_"+UUID.randomUUID().toString().replace("-","");
        Path root=base.resolve("source"),config=base.resolve("config");
        for(String name:List.of("ochre","indigo")){
            String geometry="{\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":2,\"texture_height\":2},\"bones\":[{\"name\":\"root\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[1,1,1],\"uv\":[0,0]}]}]}]}";
            ImporterAcceptance.put(root.resolve("assets/"+ns+"/geo/shape_"+name+".geo.json"),geometry);
            ImporterAcceptance.png(root.resolve("assets/"+ns+"/textures/palettes/"+name+"_skin.png"),name.equals("ochre")?0xff123456:0xff987654);
        }
        ClassWriter e=new ClassWriter(0);e.visit(61,Opcodes.ACC_PUBLIC|Opcodes.ACC_FINAL|Opcodes.ACC_ENUM,type,null,"java/lang/Enum",null);
        e.visitField(Opcodes.ACC_PUBLIC|Opcodes.ACC_FINAL,"label","Ljava/lang/String;",null,null).visitEnd();
        for(String name:List.of("OCHRE","INDIGO"))e.visitField(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_FINAL|Opcodes.ACC_ENUM,name,"L"+type+";",null,null).visitEnd();
        var ctor=e.visitMethod(Opcodes.ACC_PRIVATE,"<init>","(Ljava/lang/String;I)V",null,null);ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD,0);ctor.visitVarInsn(Opcodes.ALOAD,1);ctor.visitVarInsn(Opcodes.ILOAD,2);ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Enum","<init>","(Ljava/lang/String;I)V",false);
        ctor.visitVarInsn(Opcodes.ALOAD,0);ctor.visitVarInsn(Opcodes.ALOAD,0);ctor.visitMethodInsn(Opcodes.INVOKEVIRTUAL,type,"name","()Ljava/lang/String;",false);
        ctor.visitFieldInsn(Opcodes.GETSTATIC,"java/util/Locale","ROOT","Ljava/util/Locale;");ctor.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/lang/String","toLowerCase","(Ljava/util/Locale;)Ljava/lang/String;",false);ctor.visitFieldInsn(Opcodes.PUTFIELD,type,"label","Ljava/lang/String;");ctor.visitInsn(Opcodes.RETURN);ctor.visitMaxs(3,3);ctor.visitEnd();
        var init=e.visitMethod(Opcodes.ACC_STATIC,"<clinit>","()V",null,null);init.visitCode();int ordinal=0;
        for(String name:List.of("OCHRE","INDIGO")){init.visitTypeInsn(Opcodes.NEW,type);init.visitInsn(Opcodes.DUP);init.visitLdcInsn(name);init.visitInsn(Opcodes.ICONST_0+ordinal++);init.visitMethodInsn(Opcodes.INVOKESPECIAL,type,"<init>","(Ljava/lang/String;I)V",false);init.visitFieldInsn(Opcodes.PUTSTATIC,type,name,"L"+type+";");}
        init.visitInsn(Opcodes.RETURN);init.visitMaxs(4,0);init.visitEnd();e.visitEnd();
        ImporterAcceptance.put(root.resolve(type+".class"),"");Files.write(root.resolve(type+".class"),e.toByteArray());
        ClassWriter model=new ClassWriter(0);model.visit(61,Opcodes.ACC_PUBLIC,"arbitrary/PairedModel",null,"java/lang/Object",null);
        Handle concat=new Handle(Opcodes.H_INVOKESTATIC,"java/lang/invoke/StringConcatFactory","makeConcatWithConstants","(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",false);
        for(String method:List.of("getModelResource","getTextureResource")){
            var m=model.visitMethod(Opcodes.ACC_PUBLIC,method,"(Larbitrary/Carrier;)Lnet/minecraft/resources/ResourceLocation;",null,null);m.visitCode();m.visitLdcInsn(ns);
            m.visitVarInsn(Opcodes.ALOAD,1);m.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"arbitrary/Carrier","kind","()L"+type+";",false);m.visitFieldInsn(Opcodes.GETFIELD,type,"label","Ljava/lang/String;");
            m.visitInvokeDynamicInsn("makeConcatWithConstants","(Ljava/lang/String;)Ljava/lang/String;",concat,method.equals("getModelResource")?"geo/shape_\u0001.geo.json":"textures/palettes/\u0001_skin.png");
            m.visitMethodInsn(Opcodes.INVOKESTATIC,"net/minecraft/resources/ResourceLocation","fromNamespaceAndPath","(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;",false);m.visitInsn(Opcodes.ARETURN);m.visitMaxs(2,2);m.visitEnd();
        }
        model.visitEnd();ImporterAcceptance.put(root.resolve("arbitrary/PairedModel.class"),"");Files.write(root.resolve("arbitrary/PairedModel.class"),model.toByteArray());
        var sources=List.of(ImporterAcceptance.source(ns,"Finite selector",root));new GeoImporter(config,x->{}).scan(sources);
        var bindings=new TreeMap<String,SharedModelAssets.Binding>();SharedModelAssets.bindingsForTest().forEach((k,v)->{if(!k.contains("/skins/"))bindings.put(k,v);});
        ImporterAcceptance.check(bindings.size()==2,"both enum-selected geometries remain visible");
        for(var entry:bindings.entrySet()){
            String name=entry.getKey().contains("shape_ochre")?"ochre":"indigo";
            ImporterAcceptance.check(entry.getValue().texture()!=null&&entry.getValue().texture().endsWith("/textures/palettes/"+name+"_skin.png"),"enum fields pair model and texture from the same selected instance");
        }
        BbsCpuAcceptance.run(config.resolve("bbs/assets"),bindings);
        System.out.println("ENUM SELECTOR PASS: two finite instances, paired getters, final BBS CPU Links");
    }
}
