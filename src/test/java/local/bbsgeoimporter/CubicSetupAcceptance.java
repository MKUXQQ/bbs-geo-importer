package local.bbsgeoimporter;

import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.util.CheckClassAdapter;

/** Real BBS 2.7 bytecode seams only; does not initialize Minecraft or make GL calls. */
public final class CubicSetupAcceptance {
    private static final String BUILDER="mchorse/bbs_mod/cubic/render/CubicVAOBuilderRenderer";
    private static final String CUBES="mchorse/bbs_mod/cubic/render/CubicCubeRenderer";
    private static final String VAO="mchorse/bbs_mod/cubic/render/vao/ModelVAO";
    private static final String DATA="mchorse/bbs_mod/cubic/render/vao/ModelVAOData";
    private static final String BRIDGE="local/bbsgeoimporter/CubicMeshPreparation";
    private static final String MARKER="bbsGeoPreparationIsolated";

    public static void main(String[] args)throws Exception {
        Path jar=Path.of(args.length==0?"E:/我的世界/.minecraft/versions/bbs1.20.1动画/mods/bbs-2.7-1.20.1.jar":args[0]);
        try(JarFile archive=new JarFile(jar.toFile())){
            ClassNode rawBuilder=read(archive,BUILDER),rawCubes=read(archive,CUBES);
            MethodNode original=group(rawBuilder);
            long slot8=Arrays.stream(original.instructions.toArray()).filter(i->i instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ASTORE&&v.var==8).count();
            check(slot8>=3,"real BBS reuses local 8 for cube material, mesh material, then Entry");
            int builderReads=scratchReads(rawBuilder,false),cubeReads=scratchReads(rawCubes,true);
            check(builderReads>9&&cubeReads>=2,"actual scratch fields have repeated reads");
            check(calls(rawBuilder,"mchorse/bbs_mod/client/BBSRendering","calculateTangents","([F[F[F)[F")==1,"one exact real tangent call");
            check(CubicSetupTransforms.instrument(rawBuilder),"real BBS builder accepts nonoverlapping local 8 lifetimes");
            check(CubicSetupTransforms.instrument(rawCubes),"real BBS cube matrix helper is isolated");
            check(calls(rawBuilder,VAO,"<init>","(L"+DATA+";)V")==0&&allocations(rawBuilder,VAO)==0,"GL constructor allocation removed from CPU builder seam");
            check(calls(rawBuilder,DATA,"<init>","([F[F[F[F)V")==1,"native ModelVAOData array construction preserved");
            check(calls(rawBuilder,BRIDGE,"captureVao","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;")==1,"one captured material data factory");
            check(calls(rawBuilder,BRIDGE,"tangents","([F[F[F)[F")==1&&calls(rawBuilder,"mchorse/bbs_mod/client/BBSRendering","calculateTangents","([F[F[F)[F")==0,"tangent timing wrapper replaces only the same native signature");
            check(calls(rawBuilder,BRIDGE,"scratch","(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;")==builderReads,"every actual builder scratch read is isolated");
            check(calls(rawCubes,BRIDGE,"scratch","(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;")==cubeReads,"every actual cube matrix read is isolated");
            marker(rawBuilder);marker(rawCubes);verify(rawBuilder);verify(rawCubes);
            byte[] builderOnce=bytes(rawBuilder),cubesOnce=bytes(rawCubes);
            check(CubicSetupTransforms.instrument(rawBuilder)&&CubicSetupTransforms.instrument(rawCubes),"both transforms accept their exact existing hooks");
            check(Arrays.equals(builderOnce,bytes(rawBuilder))&&Arrays.equals(cubesOnce,bytes(rawCubes)),"second transform is byte-for-byte idempotent");
            verify(rawBuilder);verify(rawCubes);

            ClassNode bad=read(archive,BUILDER);group(bad).name="differentRenderGroup";reject(bad,"unknown method name");
            bad=read(archive,BUILDER);group(bad).desc=group(bad).desc.replace("Lmchorse/bbs_mod/cubic/data/model/ModelGroup;","Ljava/lang/Object;");reject(bad,"unknown group descriptor");
            bad=read(archive,BUILDER);call(bad,VAO,"<init>").desc="(Ljava/lang/Object;)V";reject(bad,"unknown VAO constructor signature");
            bad=read(archive,BUILDER);call(bad,DATA,"<init>").desc="([F[F[F)V";reject(bad,"unknown data constructor signature");
            bad=read(archive,BUILDER);entryStore(group(bad)).var=9;reject(bad,"Entry moved away from the exact material receiver local");
            bad=read(archive,BUILDER);InsnList overwrite=new InsnList();overwrite.add(new LdcInsnNode("later material overwrite"));overwrite.add(new VarInsnNode(Opcodes.ASTORE,8));group(bad).instructions.insert(entryStore(group(bad)),overwrite);reject(bad,"a later String write cannot stand in for the current Entry");
            bad=read(archive,BUILDER);call(bad,"mchorse/bbs_mod/client/BBSRendering","calculateTangents").desc="([F[F[F[F)[F";reject(bad,"unknown tangent overload");
            bad=read(archive,BUILDER);bad.fields.stream().filter(f->f.name.equals("v1")).findFirst().orElseThrow().desc="Lorg/joml/Vector4f;";reject(bad,"unexpected mutable scratch field shape");
            bad=read(archive,CUBES);bad.methods.stream().filter(m->m.name.equals("rotate")).findFirst().orElseThrow().name="differentRotate";reject(bad,"unknown cube helper method");
            ClassNode untouchedVao=read(archive,VAO);reject(untouchedVao,"native VAO upload class is outside this transform");
            ClassNode broken=fromBytes(builderOnce);call(broken,BRIDGE,"captureVao").desc="(Ljava/lang/Object;)Ljava/lang/Object;";reject(broken,"malformed preexisting capture hook");
            System.out.println("CUBIC SETUP ASM PASS: actual BBS2.7 builder/cube; local8 stores="+slot8+", scratch reads="+builderReads+"/"+cubeReads+"; exact factory+tangent hooks, verifier, markers, idempotence and unsupported-shape guards; no game/GL execution");
        }
    }

    private static void reject(ClassNode node,String why){byte[] before=bytes(node);check(!CubicSetupTransforms.instrument(node),why+" is rejected");check(Arrays.equals(before,bytes(node)),why+" leaves all bytecode unchanged");}
    private static void marker(ClassNode node){List<FieldNode> fields=node.fields.stream().filter(f->f.name.equals(MARKER)).toList();check(fields.size()==1,"one successful-isolation marker");FieldNode field=fields.get(0);int flags=Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_FINAL|Opcodes.ACC_SYNTHETIC;check(field.desc.equals("Z")&&(field.access&flags)==flags&&Objects.equals(field.value,1),"marker is publicly reflectable true constant");}
    private static int scratchReads(ClassNode node,boolean matricesOnly){int count=0;for(MethodNode method:node.methods)for(AbstractInsnNode in:method.instructions)if(in instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETSTATIC&&field.owner.equals(node.name)&&field.desc.startsWith(matricesOnly?"Lorg/joml/Matrix":"Lorg/joml/"))count++;return count;}
    private static long calls(ClassNode node,String owner,String name,String desc){long count=0;for(MethodNode method:node.methods)for(AbstractInsnNode in:method.instructions)if(in instanceof MethodInsnNode call&&call.owner.equals(owner)&&call.name.equals(name)&&call.desc.equals(desc))count++;return count;}
    private static int allocations(ClassNode node,String owner){int count=0;for(MethodNode method:node.methods)for(AbstractInsnNode in:method.instructions)if(in instanceof TypeInsnNode type&&type.getOpcode()==Opcodes.NEW&&type.desc.equals(owner))count++;return count;}
    private static MethodInsnNode call(ClassNode node,String owner,String name){for(MethodNode method:node.methods)for(AbstractInsnNode in:method.instructions)if(in instanceof MethodInsnNode call&&call.owner.equals(owner)&&call.name.equals(name))return call;throw new AssertionError("call not found: "+owner+"."+name);}
    private static MethodNode group(ClassNode node){return node.methods.stream().filter(m->m.name.equals("renderGroup")).findFirst().orElseThrow();}
    private static VarInsnNode entryStore(MethodNode method){for(AbstractInsnNode in:method.instructions)if(in instanceof VarInsnNode store&&store.getOpcode()==Opcodes.ASTORE&&store.var==8){AbstractInsnNode prior=store.getPrevious();while(prior!=null&&prior.getOpcode()<0)prior=prior.getPrevious();if(prior instanceof TypeInsnNode cast&&cast.getOpcode()==Opcodes.CHECKCAST&&cast.desc.equals("java/util/Map$Entry"))return store;}throw new AssertionError("real Entry store not found");}
    private static void verify(ClassNode node)throws Exception {for(MethodNode method:node.methods)if((method.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0)new Analyzer<>(new BasicVerifier()).analyze(node.name,method);ClassNode roundtrip=fromBytes(bytes(node));for(MethodNode method:roundtrip.methods)if((method.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0)new Analyzer<>(new BasicVerifier()).analyze(roundtrip.name,method);}
    private static byte[] bytes(ClassNode node){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(new CheckClassAdapter(writer,false));return writer.toByteArray();}
    private static ClassNode fromBytes(byte[] bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
    private static ClassNode read(JarFile jar,String name)throws Exception {var entry=jar.getJarEntry(name+".class");if(entry==null)throw new AssertionError("class missing: "+name);try(var stream=jar.getInputStream(entry)){ClassNode node=new ClassNode();new ClassReader(stream).accept(node,0);return node;}}
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
}
