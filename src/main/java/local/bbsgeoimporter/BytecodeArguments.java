package local.bbsgeoimporter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.util.*;
/** Merged or computed arguments remain unresolved. */
final class BytecodeArguments {
    static Map<MethodInsnNode,List<AbstractInsnNode>> calls(ClassNode owner,MethodNode method) {
        Map<MethodInsnNode,List<AbstractInsnNode>> result=new IdentityHashMap<>();
        try {
            var frames=new Analyzer<SourceValue>(new SourceInterpreter()).analyze(owner.name,method);
            var code=method.instructions.toArray();
            for(int i=0;i<code.length;i++)if(code[i] instanceof MethodInsnNode call&&frames[i]!=null) {
                int count=Type.getArgumentTypes(call.desc).length;var frame=frames[i];
                if(frame.getStackSize()<count)continue;
                List<AbstractInsnNode> args=new ArrayList<>();
                for(int j=0;j<count;j++){var value=frame.getStack(frame.getStackSize()-count+j);AbstractInsnNode source=value.insns.size()==1?value.insns.iterator().next():null;int depth=0;while(source!=null&&source.getOpcode()==Opcodes.CHECKCAST&&depth++<8){var before=frames[method.instructions.indexOf(source)];if(before==null||before.getStackSize()==0){source=null;break;}var input=before.getStack(before.getStackSize()-1);source=input.insns.size()==1?input.insns.iterator().next():null;}args.add(source);}
                result.put(call,args);
            }
        }catch(AnalyzerException|RuntimeException ignored){}
        return result;
    }
    static String string(AbstractInsnNode instruction){return instruction instanceof LdcInsnNode ldc&&ldc.cst instanceof String s?s:null;}
}
