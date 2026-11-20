package local.bbsgeoimporter;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Changes only the exact known native BBS 2.7/2.8 renderCells loop; unknown bytecode stays intact. */
public final class FormGridViewportTransforms {
    private static final String GRID="mchorse/bbs_mod/ui/framework/elements/input/items/UIItemGrid";
    private static final String BRIDGE="local/bbsgeoimporter/FormGridViewport";
    private static final String DESC="(Lmchorse/bbs_mod/ui/framework/UIContext;)V";
    private static final String RANGE="bbsGeoImporter$viewportRange";
    private static final String EXPECTED="a5f3249e21d15ea61542a8c5a52609cc2d510ea9bcf67a9d28e8f2c5801ce0aa";
    public static boolean instrument(ClassNode node){
        if(node==null||!GRID.equals(node.name))return false;
        List<MethodNode> methods=node.methods.stream().filter(m->m.name.equals("renderCells")&&m.desc.equals(DESC)).toList();
        if(methods.size()!=1)return false;
        MethodNode method=methods.get(0);
        if(method.instructions.size()==0||node.fields.stream().anyMatch(field->field.name.equals(RANGE)))return false;
        for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(BRIDGE))return false;
        if(!EXPECTED.equals(signature(method)))return false;
        AbstractInsnNode zero=null;MethodInsnNode size=null;
        for(AbstractInsnNode instruction:method.instructions){
            if(zero==null&&instruction.getOpcode()==Opcodes.ICONST_0)zero=instruction;
            if(instruction instanceof MethodInsnNode call&&call.owner.equals("java/util/List")&&call.name.equals("size"))size=call;
        }
        if(zero==null||size==null||method.maxLocals<12)return false;
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_SYNTHETIC,RANGE,"J",null,null));
        InsnList begin=new InsnList();begin.add(new VarInsnNode(Opcodes.ALOAD,0));begin.add(new VarInsnNode(Opcodes.ALOAD,0));begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"range","(Ljava/lang/Object;)J",false));begin.add(new FieldInsnNode(Opcodes.PUTFIELD,GRID,RANGE,"J"));method.instructions.insert(begin);
        InsnList first=new InsnList();first.add(new VarInsnNode(Opcodes.ALOAD,0));first.add(new FieldInsnNode(Opcodes.GETFIELD,GRID,RANGE,"J"));first.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"start","(J)I",false));method.instructions.insertBefore(zero,first);method.instructions.remove(zero);
        InsnList end=new InsnList();end.add(new VarInsnNode(Opcodes.ALOAD,0));end.add(new FieldInsnNode(Opcodes.GETFIELD,GRID,RANGE,"J"));end.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"limit","(Ljava/util/List;J)I",false));method.instructions.insertBefore(size,end);method.instructions.remove(size);
        method.maxStack=Math.max(method.maxStack,3);return true;
    }
    /** Full code fingerprint ignores only debug metadata and StackMap representation. */
    static String signature(MethodNode method){
        Map<LabelNode,Integer> labels=new IdentityHashMap<>();int offset=0;
        for(AbstractInsnNode instruction:method.instructions){if(instruction instanceof LabelNode label)labels.put(label,offset);if(instruction.getOpcode()>=0)offset++;}
        StringBuilder code=new StringBuilder().append(method.access).append('|').append(method.desc).append('|');
        for(AbstractInsnNode instruction:method.instructions){int opcode=instruction.getOpcode();if(opcode<0)continue;code.append(opcode).append(':');
            if(instruction instanceof VarInsnNode var)code.append(var.var);
            else if(instruction instanceof FieldInsnNode field)code.append(field.owner).append('.').append(field.name).append(field.desc);
            else if(instruction instanceof MethodInsnNode call)code.append(call.owner).append('.').append(call.name).append(call.desc).append(call.itf);
            else if(instruction instanceof JumpInsnNode jump)code.append(labels.get(jump.label));
            else if(instruction instanceof IincInsnNode increment)code.append(increment.var).append(',').append(increment.incr);
            else if(instruction instanceof TypeInsnNode type)code.append(type.desc);
            else if(instruction instanceof IntInsnNode value)code.append(value.operand);
            else if(instruction instanceof LdcInsnNode constant)code.append(constant.cst.getClass().getName()).append('=').append(constant.cst);
            else if(instruction.getType()!=AbstractInsnNode.INSN)return "unknown_instruction";
            code.append(';');
        }
        for(TryCatchBlockNode handler:method.tryCatchBlocks)code.append("catch:").append(labels.get(handler.start)).append(',').append(labels.get(handler.end)).append(',').append(labels.get(handler.handler)).append(',').append(handler.type);
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.toString().getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private FormGridViewportTransforms(){}
}
