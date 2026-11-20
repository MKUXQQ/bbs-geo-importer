package local.bbsgeoimporter;

import java.lang.reflect.*;
import java.util.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Owns only VBOs created inside an add-on staged upload. Every GL call stays on its caller's GL thread. */
public final class VaoBufferOwnership {
    private static final String VAO="mchorse/bbs_mod/cubic/render/vao/ModelVAO",DATA="Lmchorse/bbs_mod/cubic/render/vao/ModelVAOData;";
    private static final String GL="org/lwjgl/opengl/GL30",BRIDGE="local/bbsgeoimporter/VaoBufferOwnership",FIELD="bbsGeoOwnedBuffers";
    private static final class Pending {Object owner;final LinkedHashSet<Integer> ids=new LinkedHashSet<>();}
    private static final ThreadLocal<Pending> pending=new ThreadLocal<>();
    private static volatile String lastCleanupFailure="";
    private record Access(Field buffers,Field vao,Field vao2,Method delete,Method deleteArrays) {}
    private static final ClassValue<Access> accesses=new ClassValue<>(){
        protected Access computeValue(Class<?> type){try{
            Field field=type.getDeclaredField(FIELD);
            if(field.getType()!=int[].class||Modifier.isStatic(field.getModifiers())||!field.isSynthetic())throw new NoSuchFieldException(FIELD);
            field.setAccessible(true);
            Method delete=Class.forName(GL.replace('/','.'),false,type.getClassLoader()).getMethod("glDeleteBuffers",int.class);
            if(delete.getReturnType()!=void.class||!Modifier.isStatic(delete.getModifiers()))throw new NoSuchMethodException("glDeleteBuffers(int)");
            Method deleteArrays=Class.forName(GL.replace('/','.'),false,type.getClassLoader()).getMethod("glDeleteVertexArrays",int.class);
            if(deleteArrays.getReturnType()!=void.class||!Modifier.isStatic(deleteArrays.getModifiers()))throw new NoSuchMethodException("glDeleteVertexArrays(int)");
            Field vao=integerField(type,"vao"),vao2=integerField(type,"vao2");
            return new Access(field,vao,vao2,delete,deleteArrays);
        }catch(ReflectiveOperationException ex){throw new IllegalStateException("Unverified native VBO ownership shape",ex);}}
    };
    private static Field integerField(Class<?> type,String name)throws ReflectiveOperationException{Field field=type.getDeclaredField(name);if(field.getType()!=int.class||Modifier.isStatic(field.getModifiers()))throw new NoSuchFieldException(name);field.setAccessible(true);return field;}
    /** Reflection only: does not initialize GL or issue any GL calls. */
    public static boolean supported(Class<?> type){try{accesses.get(type);return true;}catch(RuntimeException|LinkageError ex){return false;}}

    /** Pair with end in finally, tightly around the native ModelVAO constructor, on the GL client thread. */
    public static void begin(){if(pending.get()!=null)throw new IllegalStateException("Nested staged VBO construction");pending.set(new Pending());}
    /** Injected after the verified native super() call, before upload can allocate either VAO. */
    public static void owner(Object owner){Pending value=pending.get();if(value==null)return;if(value.owner!=null&&value.owner!=owner)throw new IllegalStateException("Staged VAO owner changed");value.owner=owner;}
    /** Injected immediately after native glGenBuffers; the original generated value remains on its stack. */
    public static void track(Object owner,int id){
        Pending value=pending.get();if(value==null||id==0)return;
        if(value.owner==null)value.owner=owner;
        value.ids.add(id);
        if(value.owner!=owner)throw new IllegalStateException("Staged VBOs crossed native owners");
    }
    /** Failed constructors never publish an owner; both VAOs and already generated VBOs are released here. */
    public static void end(Object vao,boolean success){
        Pending value=pending.get();pending.remove();if(value==null)return;
        int[] ids=value.ids.stream().mapToInt(Integer::intValue).toArray();
        if(!success){failedConstruction(value.owner,ids);return;}
        if(vao==null||value.owner!=vao||ids.length!=5){failedConstruction(value.owner,ids);throw new IllegalStateException("Staged upload did not yield the five verified native VBOs");}
        Access access=accesses.get(vao.getClass());boolean transferred=false;
        try{synchronized(vao){if(access.buffers().get(vao)!=null)throw new IllegalStateException("Native VBO ownership already transferred");access.buffers().set(vao,ids);transferred=true;}}
        catch(IllegalAccessException ex){throw new IllegalStateException(ex);}
        finally{if(!transferred)failedConstruction(value.owner,ids);}
    }
    private static void failedConstruction(Object owner,int[] ids){
        if(owner==null)return;Throwable failure=null;
        try{Access access=accesses.get(owner.getClass());Set<Integer> arrays=new LinkedHashSet<>();
            synchronized(owner){arrays.add(access.vao().getInt(owner));arrays.add(access.vao2().getInt(owner));access.vao().setInt(owner,0);access.vao2().setInt(owner,0);}
            for(int id:arrays)if(id!=0)try{access.deleteArrays().invoke(null,id);}catch(Throwable ex){failure=combine(failure,ex);}
        }catch(Throwable ex){failure=combine(failure,ex);}
        try{deleteIds(owner,ids);}catch(Throwable ex){failure=combine(failure,ex);}
        // Called from a constructor's finally: cleanup must never replace its original exception.
        if(failure!=null)lastCleanupFailure=failure.getClass().getSimpleName()+":"+failure.getMessage();
    }
    public static String lastCleanupFailure(){return lastCleanupFailure;}
    private static Throwable combine(Throwable first,Throwable next){if(next instanceof InvocationTargetException target&&target.getCause()!=null)next=target.getCause();if(first==null)return next;if(first!=next)first.addSuppressed(next);return first;}
    /** Called after native VAO deletion. Clearing the owner's field before GL makes re-entry/double delete harmless. */
    public static void release(Object vao){
        if(vao==null)return;Access access=accesses.get(vao.getClass());int[] ids;
        try{synchronized(vao){ids=(int[])access.buffers().get(vao);access.buffers().set(vao,null);}}
        catch(IllegalAccessException ex){throw new IllegalStateException(ex);}
        deleteIds(vao,ids);
    }
    public static Throwable releaseAfterFailure(Object vao,Throwable original){
        try{release(vao);}catch(Throwable cleanup){if(cleanup!=original)original.addSuppressed(cleanup);}return original;
    }
    private static void deleteIds(Object owner,int[] ids){
        if(ids==null||ids.length==0)return;
        Method delete=accesses.get(owner.getClass()).delete();Throwable failure=null;
        for(int id:ids)try{delete.invoke(null,id);}catch(ReflectiveOperationException|RuntimeException|LinkageError ex){
            Throwable cause=ex instanceof InvocationTargetException target&&target.getCause()!=null?target.getCause():ex;
            if(failure==null)failure=cause;else if(cause!=failure)failure.addSuppressed(cause);
        }
        if(failure instanceof Error error)throw error;if(failure instanceof RuntimeException runtime)throw runtime;
        if(failure!=null)throw new IllegalStateException("Native VBO deletion failed",failure);
    }

    /** Exact BBS 2.7 straight-line upload/delete only. All guards run before any mutation. */
    public static boolean instrument(ClassNode node){
        if(node==null||!VAO.equals(node.name)||node.fields.stream().anyMatch(f->f.name.equals(FIELD)))return false;
        for(String field:List.of("vao","vao2","count"))if(node.fields.stream().filter(f->f.name.equals(field)&&f.desc.equals("I")&&(f.access&Opcodes.ACC_STATIC)==0).count()!=1)return false;
        MethodNode upload=unique(node,"upload","("+DATA+")V"),delete=unique(node,"delete","()V"),constructor=unique(node,"<init>","("+DATA+")V");
        if(upload==null||delete==null||constructor==null||!"java/lang/Object".equals(node.superName)||!upload.tryCatchBlocks.isEmpty()||!delete.tryCatchBlocks.isEmpty()||!constructor.tryCatchBlocks.isEmpty())return false;
        AbstractInsnNode first=constructor.instructions.getFirst();while(first!=null&&first.getOpcode()<0)first=first.getNext();
        if(!(first instanceof VarInsnNode load)||load.getOpcode()!=Opcodes.ALOAD||load.var!=0||!(next(first) instanceof MethodInsnNode superCall)||superCall.getOpcode()!=Opcodes.INVOKESPECIAL||!superCall.owner.equals("java/lang/Object")||!superCall.name.equals("<init>")||!superCall.desc.equals("()V"))return false;
        List<MethodInsnNode> generated=new ArrayList<>();Set<Integer> locals=new HashSet<>();int returns=0,arrays=0,constructorCalls=0;
        for(AbstractInsnNode instruction:upload.instructions){
            if(instruction instanceof JumpInsnNode||instruction instanceof TableSwitchInsnNode||instruction instanceof LookupSwitchInsnNode||instruction.getOpcode()==Opcodes.ATHROW)return false;
            if(instruction.getOpcode()==Opcodes.RETURN)returns++;
            if(instruction instanceof MethodInsnNode call){
                if(call.owner.equals(BRIDGE)||call.name.equals("glDeleteBuffers"))return false;
                if(call.name.equals("glGenBuffers")){if(!call(call,GL,"glGenBuffers","()I"))return false;AbstractInsnNode next=next(call);if(!(next instanceof VarInsnNode store)||store.getOpcode()!=Opcodes.ISTORE||store.var<2||store.var>6||!locals.add(store.var))return false;generated.add(call);}
                if(call(call,GL,"glGenVertexArrays","()I"))arrays++;
            }
        }
        for(AbstractInsnNode instruction:constructor.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(VAO)&&call.name.equals("upload")){if(call.getOpcode()!=Opcodes.INVOKEVIRTUAL||!call.desc.equals(upload.desc))return false;constructorCalls++;}
        List<AbstractInsnNode> body=new ArrayList<>();for(AbstractInsnNode instruction:delete.instructions)if(instruction.getOpcode()>=0)body.add(instruction);
        if(generated.size()!=5||arrays!=2||returns!=1||constructorCalls!=1||body.size()!=7||!deleteHalf(body,0,"vao")||!deleteHalf(body,3,"vao2")||body.get(6).getOpcode()!=Opcodes.RETURN)return false;
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_TRANSIENT|Opcodes.ACC_SYNTHETIC,FIELD,"[I",null,null));
        InsnList ownerHook=new InsnList();ownerHook.add(new VarInsnNode(Opcodes.ALOAD,0));ownerHook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"owner","(Ljava/lang/Object;)V",false));constructor.instructions.insert(superCall,ownerHook);constructor.maxStack=Math.max(constructor.maxStack,1);
        for(MethodInsnNode call:generated){InsnList hook=new InsnList();hook.add(new InsnNode(Opcodes.DUP));hook.add(new VarInsnNode(Opcodes.ALOAD,0));hook.add(new InsnNode(Opcodes.SWAP));hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"track","(Ljava/lang/Object;I)V",false));upload.instructions.insert(call,hook);}upload.maxStack+=2;
        LabelNode start=new LabelNode(),end=new LabelNode(),handler=new LabelNode();delete.instructions.insert(start);
        InsnList complete=new InsnList();complete.add(end);complete.add(new VarInsnNode(Opcodes.ALOAD,0));complete.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"release","(Ljava/lang/Object;)V",false));delete.instructions.insertBefore(body.get(6),complete);
        delete.instructions.add(handler);delete.instructions.add(new FrameNode(Opcodes.F_SAME1,0,null,1,new Object[]{"java/lang/Throwable"}));delete.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));delete.instructions.add(new InsnNode(Opcodes.SWAP));delete.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"releaseAfterFailure","(Ljava/lang/Object;Ljava/lang/Throwable;)Ljava/lang/Throwable;",false));delete.instructions.add(new InsnNode(Opcodes.ATHROW));
        delete.tryCatchBlocks.add(new TryCatchBlockNode(start,end,handler,null));delete.maxStack=Math.max(delete.maxStack,2);return true;
    }
    private static MethodNode unique(ClassNode node,String name,String descriptor){List<MethodNode> methods=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(descriptor)&&(m.access&(Opcodes.ACC_STATIC|Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0).toList();return methods.size()==1?methods.get(0):null;}
    private static boolean deleteHalf(List<AbstractInsnNode> body,int index,String name){return body.get(index) instanceof VarInsnNode load&&load.getOpcode()==Opcodes.ALOAD&&load.var==0&&body.get(index+1) instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETFIELD&&field.owner.equals(VAO)&&field.name.equals(name)&&field.desc.equals("I")&&body.get(index+2) instanceof MethodInsnNode call&&call(call,GL,"glDeleteVertexArrays","(I)V");}
    private static boolean call(MethodInsnNode call,String owner,String name,String descriptor){return call.getOpcode()==Opcodes.INVOKESTATIC&&!call.itf&&call.owner.equals(owner)&&call.name.equals(name)&&call.desc.equals(descriptor);}
    private static AbstractInsnNode next(AbstractInsnNode instruction){do{instruction=instruction.getNext();}while(instruction!=null&&instruction.getOpcode()<0);return instruction;}
    private VaoBufferOwnership(){}
}
