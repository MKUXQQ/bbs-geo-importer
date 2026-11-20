package local.bbsgeoimporter;

import java.lang.reflect.*;
import java.util.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Observes the terminal result of BBS's one native loader call without changing its work loop. */
public final class NativeModelLoading {
    private static final String LOADER="mchorse/bbs_mod/cubic/model/ModelLoader";
    private static final String MANAGER="mchorse/bbs_mod/cubic/model/ModelManager";
    private static final String INSTANCE="mchorse/bbs_mod/cubic/ModelInstance";
    private static final String BRIDGE="local/bbsgeoimporter/NativeModelLoading";
    private static final String NATIVE_DESC="(Ljava/lang/String;)L"+INSTANCE+";";
    private static final String BRIDGE_DESC="(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;";
    private static final ClassValue<Method> methods=new ClassValue<>() {
        protected Method computeValue(Class<?> type){
            for(Class<?> current=type;current!=null;current=current.getSuperclass())try{
                Method method=current.getDeclaredMethod("loadModel",String.class);method.setAccessible(true);return method;
            }catch(NoSuchMethodException ignored){}
            throw new IllegalStateException("No native loadModel(String) on "+type.getName());
        }
    };

    /** Validated existing hooks count as success, and are left byte-for-byte unchanged. */
    public static boolean instrument(ClassNode node){
        if(node==null||!LOADER.equals(node.name))return false;
        List<MethodNode> work=node.methods.stream().filter(method->method.name.equals("work")&&method.desc.equals("()V")&&(method.access&Opcodes.ACC_PRIVATE)!=0&&(method.access&Opcodes.ACC_STATIC)==0).toList();
        if(work.size()!=1||node.fields.stream().filter(field->field.name.equals("manager")&&field.desc.equals("L"+MANAGER+";")).count()!=1)return false;
        MethodNode target=work.get(0);MethodInsnNode nativeCall=null,wrapped=null;int nativeCount=0,wrappedCount=0;
        for(MethodNode method:node.methods)for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode call){
            if(call.owner.equals(MANAGER)&&call.name.equals("loadModel")){
                if(method!=target||call.getOpcode()!=Opcodes.INVOKEVIRTUAL||call.itf||!call.desc.equals(NATIVE_DESC))return false;
                nativeCount++;nativeCall=call;
            }
            if(call.owner.equals(BRIDGE)&&call.name.equals("load")){
                if(method!=target||call.getOpcode()!=Opcodes.INVOKESTATIC||call.itf||!call.desc.equals(BRIDGE_DESC))return false;
                wrappedCount++;wrapped=call;
            }
        }
        if(nativeCount==1&&wrappedCount==0){
            if(!receiver(nativeCall)||next(nativeCall)==null||next(nativeCall).getOpcode()!=Opcodes.POP)return false;
            MethodInsnNode replacement=new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"load",BRIDGE_DESC,false);
            target.instructions.set(nativeCall,replacement);target.instructions.insert(replacement,new TypeInsnNode(Opcodes.CHECKCAST,INSTANCE));return true;
        }
        if(nativeCount!=0||wrappedCount!=1||!receiver(wrapped))return false;
        AbstractInsnNode cast=next(wrapped);return cast instanceof TypeInsnNode type&&type.getOpcode()==Opcodes.CHECKCAST&&type.desc.equals(INSTANCE)&&next(cast)!=null&&next(cast).getOpcode()==Opcodes.POP;
    }

    /** Invoked on BBS's existing worker, retaining the exact native return/cause. No native state is mutated. */
    public static Object load(Object manager,String key){
        Object result=null;Throwable error=null;
        try{result=methods.get(manager.getClass()).invoke(manager,key);return result;}
        catch(InvocationTargetException ex){error=ex.getCause()==null?ex:ex.getCause();throw propagate(error);}
        catch(ReflectiveOperationException|RuntimeException|Error ex){error=ex;throw propagate(ex);}
        finally{try{ListModelResidency.loaderFinished(manager,key,result,error);}catch(Throwable ignored){/* Never replace the native outcome with observer failure. */}}
    }
    private static RuntimeException propagate(Throwable cause){if(cause instanceof RuntimeException runtime)return runtime;if(cause instanceof Error error)throw error;return new IllegalStateException("Native BBS model load failed",cause);}
    private static boolean receiver(MethodInsnNode call){
        AbstractInsnNode key=previous(call),field=previous(key),owner=previous(field);
        return key instanceof VarInsnNode argument&&argument.getOpcode()==Opcodes.ALOAD&&argument.var==1&&field instanceof FieldInsnNode manager&&manager.getOpcode()==Opcodes.GETFIELD&&manager.owner.equals(LOADER)&&manager.name.equals("manager")&&manager.desc.equals("L"+MANAGER+";")&&owner instanceof VarInsnNode self&&self.getOpcode()==Opcodes.ALOAD&&self.var==0;
    }
    private static AbstractInsnNode previous(AbstractInsnNode instruction){if(instruction==null)return null;do{instruction=instruction.getPrevious();}while(instruction!=null&&instruction.getOpcode()<0);return instruction;}
    private static AbstractInsnNode next(AbstractInsnNode instruction){if(instruction==null)return null;do{instruction=instruction.getNext();}while(instruction!=null&&instruction.getOpcode()<0);return instruction;}
    private NativeModelLoading(){}
}
