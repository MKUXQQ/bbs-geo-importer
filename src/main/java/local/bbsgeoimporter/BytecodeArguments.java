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
    /** Track values through local copies to reachable ARETURNs, never unused literals. All return values must be exact. */
    static List<String> returnedLocations(MethodNode method){
        if(method.instructions.size()>512||!method.tryCatchBlocks.isEmpty())return List.of();
        try{
            var interpreter=new SourceInterpreter(Opcodes.ASM9){
                @Override public SourceValue copyOperation(AbstractInsnNode insn,SourceValue v){return v;}
            };
            var frames=new Analyzer<>(interpreter).analyze("java/lang/Object",method);
            Set<String> locations=new TreeSet<>();
            for(int i=0;i<frames.length;i++)if(frames[i]!=null&&method.instructions.get(i).getOpcode()==Opcodes.ARETURN){
                var v=frames[i].getStack(frames[i].getStackSize()-1);
                if(v.insns.isEmpty())return List.of();
                for(var source:v.insns){
                    if(!(source instanceof MethodInsnNode call)||call.getOpcode()!=Opcodes.INVOKESTATIC||!ResourceMethods.location(call.owner)||!call.name.equals("fromNamespaceAndPath")||
                        !call.desc.equals("(Ljava/lang/String;Ljava/lang/String;)L"+call.owner+";"))return List.of();
                    var f=frames[method.instructions.indexOf(call)];String[] parts=new String[2];
                    for(int j=0;j<2;j++){var arg=f.getStack(f.getStackSize()-2+j);if(arg.insns.size()!=1||(parts[j]=string(arg.insns.iterator().next()))==null)return List.of();}
                    locations.add(parts[0]+":"+parts[1]);
                }
            }
            return locations.size()>64?List.of():List.copyOf(locations);
        }catch(AnalyzerException|RuntimeException ex){return List.of();}
    }
}
