package local.bbsgeoimporter;

import java.util.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Exact BBS cubic-build seams; array preparation and GL upload stay separate. */
public final class CubicSetupTransforms {
    private static final String BUILDER="mchorse/bbs_mod/cubic/render/CubicVAOBuilderRenderer";
    private static final String CUBES="mchorse/bbs_mod/cubic/render/CubicCubeRenderer";
    private static final String VAO="mchorse/bbs_mod/cubic/render/vao/ModelVAO";
    private static final String DATA="mchorse/bbs_mod/cubic/render/vao/ModelVAOData";
    private static final String GROUP="mchorse/bbs_mod/cubic/data/model/ModelGroup";
    private static final String MODEL="mchorse/bbs_mod/cubic/data/model/Model";
    private static final String BRIDGE="local/bbsgeoimporter/CubicMeshPreparation";
    private static final String RENDERING="mchorse/bbs_mod/client/BBSRendering";
    private static final String MARKER="bbsGeoPreparationIsolated";
    private static final String CAPTURE="(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;";
    private static final String SCRATCH="(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;";
    private static final String TANGENTS="([F[F[F)[F";
    private static final Map<String,String> BUILDER_SCRATCH=Map.of(
        "v1","Lorg/joml/Vector3f;","v2","Lorg/joml/Vector3f;","v3","Lorg/joml/Vector3f;",
        "n1","Lorg/joml/Vector3f;","n2","Lorg/joml/Vector3f;","n3","Lorg/joml/Vector3f;",
        "u1","Lorg/joml/Vector2f;","u2","Lorg/joml/Vector2f;","u3","Lorg/joml/Vector2f;");
    private static final Map<String,String> CUBE_SCRATCH=Map.of("modelM","Lorg/joml/Matrix4f;","normalM","Lorg/joml/Matrix3f;");

    private record CaptureSite(MethodNode method,TypeInsnNode allocation,AbstractInsnNode duplicate,MethodInsnNode constructor) {}
    private record ScratchSite(MethodNode method,FieldInsnNode field) {}

    public static boolean instrument(ClassNode node){
        if(node==null||(!BUILDER.equals(node.name)&&!CUBES.equals(node.name)))return false;
        boolean marked=false;
        for(FieldNode field:node.fields)if(field.name.equals(MARKER)){
            int required=Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_FINAL|Opcodes.ACC_SYNTHETIC;
            if(marked||!field.desc.equals("Z")||(field.access&required)!=required||!Objects.equals(field.value,1))return false;
            marked=true;
        }
        Map<String,String> fields=BUILDER.equals(node.name)?BUILDER_SCRATCH:CUBE_SCRATCH;
        if(!declaredScratchMatches(node,fields))return false;
        CaptureSite capture=null;
        MethodInsnNode nativeTangents=null;
        if(BUILDER.equals(node.name)){
            List<MethodNode> methods=node.methods.stream().filter(m->m.name.equals("renderGroup")&&renderGroupDescriptor(m.desc)&&(m.access&Opcodes.ACC_STATIC)==0).toList();
            if(methods.size()!=1)return false;
            MethodNode method=methods.get(0);
            int allocations=0,nativeConstructors=0,bridges=0,tangents=0;
            MethodInsnNode capturedFactory=null;
            for(MethodNode candidate:node.methods)for(AbstractInsnNode instruction:candidate.instructions){
                if(instruction instanceof TypeInsnNode type&&type.getOpcode()==Opcodes.NEW&&type.desc.equals(VAO)){
                    if(candidate!=method)return false;allocations++;
                }
                if(instruction instanceof MethodInsnNode call){
                    if(call.owner.equals(VAO)&&call.name.equals("<init>")){if(candidate!=method||!nativeVaoConstructor(call))return false;nativeConstructors++;}
                    if(call.owner.equals(BRIDGE)&&call.name.equals("captureVao")){
                        if(candidate!=method||!capturedShape(call))return false;bridges++;capturedFactory=call;
                    }
                    if(call.owner.equals(RENDERING)&&call.name.equals("calculateTangents")){
                        if(candidate!=method||call.getOpcode()!=Opcodes.INVOKESTATIC||!call.desc.equals(TANGENTS)||call.itf)return false;
                        tangents++;nativeTangents=call;
                    }
                    if(call.owner.equals(BRIDGE)&&call.name.equals("tangents")){
                        if(candidate!=method||call.getOpcode()!=Opcodes.INVOKESTATIC||!call.desc.equals(TANGENTS)||call.itf)return false;tangents++;
                    }
                }
            }
            if(bridges==1){if(allocations!=0||nativeConstructors!=0)return false;}
            else if(bridges==0&&allocations==1&&nativeConstructors==1){
                capture=rawCaptureSite(method);if(capture==null)return false;
            }else return false;
            if(tangents!=1||!entryLocalMatches(method,capture==null?capturedFactory:capture.allocation()))return false;
        }else if(!cubeRotateMatches(node))return false;

        // Complete every guard before changing the class: an unsupported target remains intact.
        List<ScratchSite> scratch=new ArrayList<>();
        for(MethodNode method:node.methods)for(AbstractInsnNode instruction:method.instructions){
            if(instruction instanceof MethodInsnNode call&&call.owner.equals(BRIDGE)&&call.name.equals("scratch")){
                if(!existingScratchMatches(node.name,fields,call))return false;
            }
            if(!(instruction instanceof FieldInsnNode field)||field.getOpcode()!=Opcodes.GETSTATIC||!field.owner.equals(node.name)||!fields.containsKey(field.name))continue;
            if(!fields.get(field.name).equals(field.desc))return false;
            AbstractInsnNode next=nextCode(field);
            if(next instanceof LdcInsnNode key&&Objects.equals(key.cst,node.name+"."+field.name)){
                AbstractInsnNode hook=nextCode(key);
                if(!(hook instanceof MethodInsnNode call)||!existingScratchMatches(node.name,fields,call))return false;
            }else scratch.add(new ScratchSite(method,field));
        }
        if(marked&&(capture!=null||nativeTangents!=null||!scratch.isEmpty()))return false;
        if(capture!=null)applyCapture(capture);
        if(nativeTangents!=null){nativeTangents.owner=BRIDGE;nativeTangents.name="tangents";}
        Set<MethodNode> changed=Collections.newSetFromMap(new IdentityHashMap<>());
        for(ScratchSite site:scratch){
            FieldInsnNode field=site.field();InsnList hook=new InsnList();
            hook.add(new LdcInsnNode(field.owner+"."+field.name));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"scratch",SCRATCH,false));
            hook.add(new TypeInsnNode(Opcodes.CHECKCAST,Type.getType(field.desc).getInternalName()));
            site.method().instructions.insert(field,hook);changed.add(site.method());
        }
        for(MethodNode method:changed)method.maxStack++;
        if(!marked)node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_FINAL|Opcodes.ACC_SYNTHETIC,MARKER,"Z",null,1));
        return true;
    }

    private static boolean declaredScratchMatches(ClassNode node,Map<String,String> expected){
        Map<String,String> actual=new HashMap<>();
        for(FieldNode field:node.fields){
            if((field.access&Opcodes.ACC_STATIC)==0)continue;
            boolean relevant=BUILDER.equals(node.name)?field.desc.startsWith("Lorg/joml/"):field.desc.startsWith("Lorg/joml/Matrix");
            if(!relevant&&!expected.containsKey(field.name))continue;
            if(actual.put(field.name,field.desc)!=null)return false;
        }
        return actual.equals(expected);
    }

    private static boolean renderGroupDescriptor(String descriptor){
        Type[] args=Type.getArgumentTypes(descriptor);
        if(args.length!=4||Type.getReturnType(descriptor).getSort()!=Type.BOOLEAN)return false;
        String first=args[0].getDescriptor(),second=args[1].getDescriptor();
        boolean minecraft=first.equals("Lnet/minecraft/class_287;")&&second.equals("Lnet/minecraft/class_4587;")
            ||first.equals("Lcom/mojang/blaze3d/vertex/BufferBuilder;")&&second.equals("Lcom/mojang/blaze3d/vertex/PoseStack;")
            ||first.equals("Lnet/minecraft/client/render/BufferBuilder;")&&second.equals("Lnet/minecraft/client/util/math/MatrixStack;");
        return minecraft&&args[2].getDescriptor().equals("L"+GROUP+";")&&args[3].getDescriptor().equals("L"+MODEL+";");
    }

    private static boolean cubeRotateMatches(ClassNode node){
        List<MethodNode> matches=node.methods.stream().filter(m->m.name.equals("rotate")&&(m.access&Opcodes.ACC_STATIC)!=0&&Set.of(
            "(Lnet/minecraft/class_4587;Lorg/joml/Vector3f;)V",
            "(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Vector3f;)V",
            "(Lnet/minecraft/client/util/math/MatrixStack;Lorg/joml/Vector3f;)V").contains(m.desc)).toList();
        if(matches.size()!=1)return false;
        Set<String> reads=new HashSet<>();
        for(AbstractInsnNode instruction:matches.get(0).instructions)if(instruction instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETSTATIC&&field.owner.equals(CUBES)&&CUBE_SCRATCH.containsKey(field.name))reads.add(field.name);
        return reads.equals(CUBE_SCRATCH.keySet());
    }

    private static boolean entryLocalMatches(MethodNode method,AbstractInsnNode factory){
        int entries=0;VarInsnNode entryStore=null;
        for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof VarInsnNode store&&store.getOpcode()==Opcodes.ASTORE&&store.var==8){
            AbstractInsnNode cast=previousCode(store),call=previousCode(cast);
            // The earlier cube/mesh loops reuse slot 8 for a material String.
            if(!(cast instanceof TypeInsnNode type)||type.getOpcode()!=Opcodes.CHECKCAST||!type.desc.equals("java/util/Map$Entry"))continue;
            if(!(call instanceof MethodInsnNode next)||next.getOpcode()!=Opcodes.INVOKEINTERFACE||!next.owner.equals("java/util/Iterator")||!next.name.equals("next")||!next.desc.equals("()Ljava/lang/Object;"))return false;
            entries++;entryStore=store;
        }
        if(entries!=1||factory==null)return false;
        // Only the final Entry lifetime may reach this exact constructor/factory.
        for(AbstractInsnNode instruction=previousCode(factory);instruction!=null;instruction=previousCode(instruction))
            if(instruction instanceof VarInsnNode store&&store.getOpcode()==Opcodes.ASTORE&&store.var==8)return store==entryStore;
        return false;
    }

    private static CaptureSite rawCaptureSite(MethodNode method){
        TypeInsnNode allocation=null;
        for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof TypeInsnNode type&&type.getOpcode()==Opcodes.NEW&&type.desc.equals(VAO))allocation=type;
        if(allocation==null||!entryKeyCast(previousCode(allocation)))return null;
        AbstractInsnNode duplicate=nextCode(allocation),dataNew=nextCode(duplicate),dataDuplicate=nextCode(dataNew);
        if(duplicate==null||duplicate.getOpcode()!=Opcodes.DUP||!(dataNew instanceof TypeInsnNode type)||type.getOpcode()!=Opcodes.NEW||!type.desc.equals(DATA)||dataDuplicate==null||dataDuplicate.getOpcode()!=Opcodes.DUP)return null;
        AbstractInsnNode cursor=dataDuplicate;
        for(int i=0;i<4;i++){cursor=nextCode(cursor);if(!(cursor instanceof VarInsnNode load)||load.getOpcode()!=Opcodes.ALOAD)return null;}
        cursor=nextCode(cursor);if(!(cursor instanceof MethodInsnNode data)||!dataConstructor(data))return null;
        cursor=nextCode(cursor);if(!(cursor instanceof MethodInsnNode constructor)||!nativeVaoConstructor(constructor))return null;
        if(!mapPut(nextCode(constructor)))return null;
        return new CaptureSite(method,allocation,duplicate,constructor);
    }

    private static boolean capturedShape(MethodInsnNode call){
        if(call.getOpcode()!=Opcodes.INVOKESTATIC||!call.desc.equals(CAPTURE)||call.itf)return false;
        AbstractInsnNode material=previousCode(call);if(!entryKeyCast(material))return false;
        AbstractInsnNode entryLoad=previousCode(previousCode(material)),groupLoad=previousCode(entryLoad);
        if(!(groupLoad instanceof VarInsnNode group)||group.getOpcode()!=Opcodes.ALOAD||group.var!=3)return false;
        AbstractInsnNode data=previousCode(groupLoad);if(!(data instanceof MethodInsnNode constructor)||!dataConstructor(constructor))return false;
        AbstractInsnNode cast=nextCode(call);
        return cast instanceof TypeInsnNode type&&type.getOpcode()==Opcodes.CHECKCAST&&type.desc.equals(VAO)&&mapPut(nextCode(cast));
    }

    private static boolean entryKeyCast(AbstractInsnNode instruction){
        if(!(instruction instanceof TypeInsnNode cast)||cast.getOpcode()!=Opcodes.CHECKCAST||!cast.desc.equals("java/lang/String"))return false;
        AbstractInsnNode previous=previousCode(cast);
        if(!(previous instanceof MethodInsnNode key)||key.getOpcode()!=Opcodes.INVOKEINTERFACE||!key.owner.equals("java/util/Map$Entry")||!key.name.equals("getKey")||!key.desc.equals("()Ljava/lang/Object;"))return false;
        AbstractInsnNode load=previousCode(key);return load instanceof VarInsnNode entry&&entry.getOpcode()==Opcodes.ALOAD&&entry.var==8;
    }

    private static boolean nativeVaoConstructor(MethodInsnNode call){return call.getOpcode()==Opcodes.INVOKESPECIAL&&call.owner.equals(VAO)&&call.name.equals("<init>")&&call.desc.equals("(L"+DATA+";)V")&&!call.itf;}
    private static boolean dataConstructor(MethodInsnNode call){return call.getOpcode()==Opcodes.INVOKESPECIAL&&call.owner.equals(DATA)&&call.name.equals("<init>")&&call.desc.equals("([F[F[F[F)V")&&!call.itf;}
    private static boolean mapPut(AbstractInsnNode instruction){return instruction instanceof MethodInsnNode call&&call.getOpcode()==Opcodes.INVOKEINTERFACE&&call.owner.equals("java/util/Map")&&call.name.equals("put")&&call.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");}

    private static void applyCapture(CaptureSite site){
        MethodNode method=site.method();method.instructions.remove(site.allocation());method.instructions.remove(site.duplicate());
        InsnList hook=new InsnList();hook.add(new VarInsnNode(Opcodes.ALOAD,3));hook.add(new VarInsnNode(Opcodes.ALOAD,8));
        hook.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,"java/util/Map$Entry","getKey","()Ljava/lang/Object;",true));
        hook.add(new TypeInsnNode(Opcodes.CHECKCAST,"java/lang/String"));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"captureVao",CAPTURE,false));
        hook.add(new TypeInsnNode(Opcodes.CHECKCAST,VAO));
        method.instructions.insertBefore(site.constructor(),hook);method.instructions.remove(site.constructor());method.maxStack+=2;
    }

    private static boolean existingScratchMatches(String owner,Map<String,String> expected,MethodInsnNode call){
        if(call.getOpcode()!=Opcodes.INVOKESTATIC||!call.owner.equals(BRIDGE)||!call.name.equals("scratch")||!call.desc.equals(SCRATCH)||call.itf)return false;
        AbstractInsnNode prior=previousCode(call),read=previousCode(prior),next=nextCode(call);
        if(!(prior instanceof LdcInsnNode key)||!(read instanceof FieldInsnNode field)||field.getOpcode()!=Opcodes.GETSTATIC||!field.owner.equals(owner)||!Objects.equals(expected.get(field.name),field.desc)||!Objects.equals(key.cst,owner+"."+field.name))return false;
        return next instanceof TypeInsnNode cast&&cast.getOpcode()==Opcodes.CHECKCAST&&cast.desc.equals(Type.getType(field.desc).getInternalName());
    }
    private static AbstractInsnNode nextCode(AbstractInsnNode instruction){if(instruction==null)return null;do{instruction=instruction.getNext();}while(instruction!=null&&instruction.getOpcode()<0);return instruction;}
    private static AbstractInsnNode previousCode(AbstractInsnNode instruction){if(instruction==null)return null;do{instruction=instruction.getPrevious();}while(instruction!=null&&instruction.getOpcode()<0);return instruction;}
    private CubicSetupTransforms(){}
}
