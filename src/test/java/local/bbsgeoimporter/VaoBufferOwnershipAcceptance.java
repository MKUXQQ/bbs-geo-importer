package local.bbsgeoimporter;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.util.CheckClassAdapter;

/** Real native constructor/upload/delete plus an isolated fake GL facade. No GL context or game is used. */
public final class VaoBufferOwnershipAcceptance {
    private static final String VAO="mchorse/bbs_mod/cubic/render/vao/ModelVAO",DATA="mchorse/bbs_mod/cubic/render/vao/ModelVAOData",GL="org/lwjgl/opengl/GL30",SELF="local/bbsgeoimporter/VaoBufferOwnershipAcceptance",BRIDGE="local/bbsgeoimporter/VaoBufferOwnership";
    private static final String CONTRACT="mchorse/bbs_mod/cubic/render/vao/IModelVAO",FORMAT="net/minecraft/class_293",FORMATS="net/minecraft/class_290",RENDERING="mchorse/bbs_mod/client/BBSRendering";
    private static int generated,vertexArrays,deletedArrays;private static boolean failUpload,failDelete,failBeforeBuffers;
    private static final Map<Integer,Integer> deleted=new LinkedHashMap<>();
    public static int nextBuffer(){return ++generated;}
    public static int nextVao(){return 1000+(++vertexArrays);}
    public static void deleteBuffer(int id){deleted.merge(id,1,Integer::sum);}
    public static void deleteVao(int id){deletedArrays++;if(failDelete)throw new IllegalStateException("fake native VAO delete failure");}
    public static void bindVao(int id){if(failBeforeBuffers&&generated==0&&vertexArrays==2)throw new IllegalStateException("fake before VBO failure");}
    public static void upload(){if(failUpload)throw new IllegalStateException("fake native upload failure");}
    private static void reset(){generated=vertexArrays=deletedArrays=0;deleted.clear();failUpload=failDelete=failBeforeBuffers=false;}

    public static void main(String[] args)throws Exception{
        Path jarPath=Path.of(args.length==0?"E:/我的世界/.minecraft/versions/bbs1.20.1动画/mods/bbs-2.7-1.20.1.jar":args[0]);
        ClassNode raw;byte[] data,contract;
        try(JarFile jar=new JarFile(jarPath.toFile())){raw=read(jar,VAO);try(InputStream in=jar.getInputStream(jar.getJarEntry(DATA+".class"))){data=in.readAllBytes();}try(InputStream in=jar.getInputStream(jar.getJarEntry(CONTRACT+".class"))){contract=in.readAllBytes();}}
        check(calls(raw,GL,"glGenBuffers")==5&&calls(raw,GL,"glDeleteBuffers")==0&&calls(raw,GL,"glDeleteVertexArrays")==2,"real BBS has five local VBO IDs and only two native VAO deletions");
        ClassNode marker=compiled("local/bbsgeoimporter/mixin/VaoOwnershipMixin"),preview=compiled("local/bbsgeoimporter/mixin/ListPreviewMixin");
        check(targets(marker).equals(Set.of(VAO.replace('/','.')))&&marker.fields.isEmpty()&&marker.methods.stream().allMatch(m->m.name.equals("<init>"))&&marker.methods.stream().noneMatch(VaoBufferOwnershipAcceptance::injected),"compiled ownership mixin is an empty marker targeting only native ModelVAO");
        check(!targets(preview).contains(VAO.replace('/','.')),"compiled general preview mixin cannot inject into ModelVAO before strict ownership transformation");
        ClassNode direct=fromBytes(bytes(raw));check(VaoBufferOwnership.instrument(direct),"exact native shape is supported");
        ClassNode changed=fromBytes(bytes(raw));new local.bbsgeoimporter.mixin.CaptureMixinPlugin().postApply(VAO.replace('/','.'),changed,"local.bbsgeoimporter.mixin.VaoOwnershipMixin",null);
        check(calls(changed,BRIDGE,"owner")==1&&calls(changed,BRIDGE,"track")==5&&calls(changed,BRIDGE,"release")==1&&calls(changed,BRIDGE,"releaseAfterFailure")==1,"actual production postApply captures owner, all five allocations and normal/exceptional native deletion exits");
        verify(changed);byte[] already=bytes(changed);check(!VaoBufferOwnership.instrument(changed)&&Arrays.equals(already,bytes(changed)),"repeat transformation cannot add a second ownership layer");
        ClassNode unknown=fromBytes(bytes(raw));MethodInsnNode gen=first(unknown,GL,"glGenBuffers");gen.name="unknownGeneration";reject(unknown,"unknown upload allocation shape");
        ClassNode deleteUnknown=fromBytes(bytes(raw));deleteUnknown.methods.stream().filter(m->m.name.equals("delete")).findFirst().orElseThrow().instructions.insert(new InsnNode(Opcodes.NOP));reject(deleteUnknown,"unknown native deletion shape");
        // Retain the real interface and every native method. Only non-executed Minecraft/GL dependencies are facades.
        Map<String,byte[]> definitions=Map.of(VAO.replace('/','.'),bytes(changed),DATA.replace('/','.'),data,CONTRACT.replace('/','.'),contract,GL.replace('/','.'),fakeGl(),FORMAT.replace('/','.'),dependency(FORMAT),FORMATS.replace('/','.'),dependency(FORMATS),RENDERING.replace('/','.'),dependency(RENDERING));
        ClassLoader loader=new ClassLoader(VaoBufferOwnershipAcceptance.class.getClassLoader()){
            protected synchronized Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{byte[] value=definitions.get(name);if(value==null)return super.loadClass(name,resolve);Class<?> type=findLoadedClass(name);if(type==null)type=defineClass(name,value,0,value.length);if(resolve)resolveClass(type);return type;}
        };
        Class<?> type=loader.loadClass(VAO.replace('/','.')),dataType=loader.loadClass(DATA.replace('/','.'));
        check(loader.loadClass(CONTRACT.replace('/','.')).isAssignableFrom(type)&&type.getMethod("render",loader.loadClass(FORMAT.replace('/','.')),float.class,float.class,float.class,float.class,int.class,int.class).getDeclaringClass()==type,"real IModelVAO contract and native render implementation remain present");
        Constructor<?> constructor=type.getConstructor(dataType);Method delete=type.getMethod("delete");Object mesh=dataType.getConstructor(float[].class,float[].class,float[].class,float[].class).newInstance(new float[9],new float[9],new float[12],new float[6]);
        check(VaoBufferOwnership.supported(type)&&generated==0&&vertexArrays==0,"supported verifies reflected owner/GL signatures without issuing any GL calls");
        check(loader.loadClass(GL.replace('/','.')).getMethod("glDeleteBuffers",int.class).getReturnType()==void.class,"exact reflected GL30.glDeleteBuffers(int) API is available");
        reset();Object outside=constructor.newInstance(mesh);delete.invoke(outside);check(generated==5&&deleted.isEmpty(),"unscoped native model VBO ownership is untouched");
        reset();Object owner=construct(constructor,mesh);check(generated==5&&deleted.isEmpty(),"successful constructor transfers all five IDs without early deletion");delete.invoke(owner);delete.invoke(owner);allDeletedOnce(5,"double native delete");
        reset();List<Object> staged=List.of(construct(constructor,mesh),construct(constructor,mesh));for(Object vao:staged)delete.invoke(vao);for(Object vao:staged)delete.invoke(vao);allDeletedOnce(10,"staged cancellation reuses native deletion without repeated VBO deletion");
        reset();failBeforeBuffers=true;boolean failed=false;try{construct(constructor,mesh);}catch(InvocationTargetException expected){failed=expected.getCause().getMessage().equals("fake before VBO failure");}check(failed&&generated==0&&vertexArrays==2&&deletedArrays==2&&deleted.isEmpty(),"failure before the first VBO still deletes both native VAOs and preserves its original exception");
        reset();failUpload=true;failed=false;try{construct(constructor,mesh);}catch(InvocationTargetException expected){failed=expected.getCause().getMessage().equals("fake native upload failure");}check(failed&&deletedArrays==2,"native upload failure is preserved and both native VAOs are released");VaoBufferOwnership.end(null,false);check(deletedArrays==2,"repeated failed end does not delete either VAO again");allDeletedOnce(5,"failed construction releases allocated buffers once");
        reset();failUpload=failDelete=true;failed=false;try{construct(constructor,mesh);}catch(InvocationTargetException expected){failed=expected.getCause().getMessage().equals("fake native upload failure");}check(failed&&deletedArrays==2&&!VaoBufferOwnership.lastCleanupFailure().isEmpty(),"cleanup failure attempts both native VAOs and does not replace the constructor exception");allDeletedOnce(5,"VBO cleanup proceeds after VAO cleanup errors");
        reset();Object failing=construct(constructor,mesh);failDelete=true;failed=false;try{delete.invoke(failing);}catch(InvocationTargetException expected){failed=expected.getCause().getMessage().equals("fake native VAO delete failure");}check(failed,"native deletion failure remains the primary exception");allDeletedOnce(5,"native deletion finally releases every owned VBO");failDelete=false;delete.invoke(failing);allDeletedOnce(5,"retry after failed native deletion does not double delete VBOs");
        System.out.println("VBO OWNERSHIP CPU PASS: real five-allocation shape; unscoped unchanged; exact ownership; double-delete/cancel/constructor-failure/delete-failure; fake GL only, no GPU verification.");
    }
    private static Object construct(Constructor<?> constructor,Object data)throws Exception{Object vao=null;boolean success=false;VaoBufferOwnership.begin();try{vao=constructor.newInstance(data);success=true;return vao;}finally{VaoBufferOwnership.end(vao,success);}}
    private static void allDeletedOnce(int count,String why){check(generated==count&&deleted.size()==count&&deleted.values().stream().allMatch(n->n==1),why+": "+deleted);}
    private static void reject(ClassNode node,String why){byte[] before=bytes(node);check(!VaoBufferOwnership.instrument(node)&&Arrays.equals(before,bytes(node)),why+" remains byte-for-byte unchanged");}
    private static long calls(ClassNode node,String owner,String name){long n=0;for(MethodNode method:node.methods)for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(owner)&&call.name.equals(name))n++;return n;}
    private static MethodInsnNode first(ClassNode node,String owner,String name){for(MethodNode method:node.methods)for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(owner)&&call.name.equals(name))return call;throw new AssertionError(name);}
    private static ClassNode read(JarFile jar,String name)throws Exception{try(InputStream input=jar.getInputStream(jar.getJarEntry(name+".class"))){return fromBytes(input.readAllBytes());}}
    private static ClassNode compiled(String name)throws Exception{try(InputStream input=VaoBufferOwnershipAcceptance.class.getClassLoader().getResourceAsStream(name+".class")){if(input==null)throw new AssertionError("Compiled production class missing: "+name);return fromBytes(input.readAllBytes());}}
    private static List<AnnotationNode> annotations(List<AnnotationNode> visible,List<AnnotationNode> invisible){List<AnnotationNode> result=new ArrayList<>();if(visible!=null)result.addAll(visible);if(invisible!=null)result.addAll(invisible);return result;}
    private static boolean injected(MethodNode method){return annotations(method.visibleAnnotations,method.invisibleAnnotations).stream().anyMatch(a->a.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/"));}
    private static Set<String> targets(ClassNode node){Set<String> result=new HashSet<>();for(AnnotationNode annotation:annotations(node.visibleAnnotations,node.invisibleAnnotations))if(annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")&&annotation.values!=null)for(int i=0;i<annotation.values.size();i+=2){String key=(String)annotation.values.get(i);Object value=annotation.values.get(i+1);if((key.equals("targets")||key.equals("value"))&&value instanceof List<?> values)for(Object target:values)result.add(target instanceof Type type?type.getClassName():target.toString().replace('/','.'));}return result;}
    private static ClassNode fromBytes(byte[] data){ClassNode node=new ClassNode();new ClassReader(data).accept(node,0);return node;}
    private static byte[] bytes(ClassNode node){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(new CheckClassAdapter(writer,false));return writer.toByteArray();}
    private static void verify(ClassNode node)throws Exception{for(ClassNode pass:List.of(node,fromBytes(bytes(node))))for(MethodNode method:pass.methods)if((method.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0)new Analyzer<>(new BasicVerifier()).analyze(pass.name,method);}
    private static byte[] fakeGl(){
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,GL,null,"java/lang/Object",null);
        facade(writer,"glGetInteger","(I)I",null);facade(writer,"glGenVertexArrays","()I","nextVao");facade(writer,"glGenBuffers","()I","nextBuffer");
        facade(writer,"glDeleteVertexArrays","(I)V","deleteVao");facade(writer,"glDeleteBuffers","(I)V","deleteBuffer");facade(writer,"glBufferData","(I[FI)V","upload");
        facade(writer,"glBindVertexArray","(I)V","bindVao");for(String name:List.of("glEnableVertexAttribArray","glDisableVertexAttribArray"))facade(writer,name,"(I)V",null);
        facade(writer,"glBindBuffer","(II)V",null);facade(writer,"glVertexAttribPointer","(IIIZIJ)V",null);facade(writer,"glVertexAttrib4f","(IFFFF)V",null);facade(writer,"glVertexAttribI2i","(III)V",null);facade(writer,"glDrawArrays","(III)V",null);writer.visitEnd();return writer.toByteArray();
    }
    private static byte[] dependency(String name){
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,name,null,"java/lang/Object",null);
        if(name.equals(FORMATS))writer.visitField(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"field_1580","L"+FORMAT+";",null,null).visitEnd();
        if(name.equals(RENDERING)){MethodVisitor method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"isIrisShadersEnabled","()Z",null,null);method.visitCode();method.visitInsn(Opcodes.ICONST_0);method.visitInsn(Opcodes.IRETURN);method.visitMaxs(0,0);method.visitEnd();}
        writer.visitEnd();return writer.toByteArray();
    }
    private static void facade(ClassWriter writer,String name,String descriptor,String target){
        MethodVisitor method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,name,descriptor,null,null);method.visitCode();
        if(target!=null){boolean argument=target.startsWith("delete")||target.equals("bindVao");if(argument)method.visitVarInsn(Opcodes.ILOAD,0);String signature=argument?"(I)V":target.equals("upload")?"()V":"()I";method.visitMethodInsn(Opcodes.INVOKESTATIC,SELF,target,signature,false);}
        else if(Type.getReturnType(descriptor).getSort()==Type.INT)method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Type.getReturnType(descriptor).getSort()==Type.INT?Opcodes.IRETURN:Opcodes.RETURN);method.visitMaxs(0,0);method.visitEnd();
    }
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
}
