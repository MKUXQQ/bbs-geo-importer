package local.bbsgeoimporter;
import org.objectweb.asm.*;
import java.nio.file.*;
import java.util.*;
/** Reads bytecode only. Accepts literal resource-return methods; rejects branches and dynamic construction. */
final class StaticModelReferences {
    static void analyze(ResourceCatalog catalog) {
        for(var files:catalog.entries.values()) for(var entry:files.values()) if(entry.path().endsWith(".class")) {
            try {
                Map<String,Set<String>> refs=new HashMap<>(); Set<String> rejected=new HashSet<>();
                new ClassReader(AnalysisInputs.bytes(entry.file())).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                        String kind=ResourceMethods.kind(name);
                        if(kind==null || (access&Opcodes.ACC_BRIDGE)!=0) return null;
                        Set<String> strings=refs.computeIfAbsent(kind,k->new LinkedHashSet<>());
                        List<String> code=new ArrayList<>(), literals=new ArrayList<>();
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitLdcInsn(Object value) {code.add("ldc");if(value instanceof String s) literals.add(s);else rejected.add(kind);}
                            @Override public void visitTypeInsn(int op,String type) {code.add(op==Opcodes.NEW&&ResourceMethods.location(type)?"new":"invalid");}
                            @Override public void visitInsn(int op) {code.add(op==Opcodes.DUP?"dup":op==Opcodes.ARETURN?"return":"invalid");}
                            @Override public void visitVarInsn(int op,int var) {rejected.add(kind);}
                            @Override public void visitIntInsn(int op,int operand) {rejected.add(kind);}
                            @Override public void visitEnd() {
                                String pattern=String.join(",",code);
                                if(pattern.equals("new,dup,ldc,init1,return")&&literals.size()==1) {String ref=literals.get(0);strings.add(ref.contains(":")?ref:"minecraft:"+ref);}
                                else if(pattern.equals("new,dup,ldc,ldc,init2,return")&&literals.size()==2) strings.add(literals.get(0)+":"+literals.get(1));
                                else rejected.add(kind);
                            }
                            @Override public void visitMethodInsn(int opcode,String owner,String method,String desc,boolean itf) {
                                if(opcode==Opcodes.INVOKESPECIAL && method.equals("<init>") && ResourceMethods.location(owner)) code.add(desc.equals("(Ljava/lang/String;)V")?"init1":desc.equals("(Ljava/lang/String;Ljava/lang/String;)V")?"init2":"invalid");
                                else rejected.add(kind);
                            }
                            @Override public void visitTableSwitchInsn(int min,int max,Label dflt,Label... labels) {rejected.add(kind);}
                            @Override public void visitLookupSwitchInsn(Label dflt,int[] keys,Label[] labels) {rejected.add(kind);}
                            @Override public void visitJumpInsn(int opcode,Label label) {rejected.add(kind);}
                            @Override public void visitInvokeDynamicInsn(String name,String descriptor,Handle h,Object... args) {rejected.add(kind);}
                            @Override public void visitFieldInsn(int opcode,String owner,String name,String desc) {rejected.add(kind);}
                        };
                    }
                },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
                Set<ResourceCatalog.Entry> models=resolve(catalog,entry,"geo",refs.getOrDefault("geo",Set.of()));
                if(rejected.contains("geo") || refs.getOrDefault("geo",Set.of()).size()!=1 || models.size()!=1) {
                    if(!refs.isEmpty()) catalog.event(entry,"dynamic_reference_unresolved","Resource-return methods not a unique literal model: "+refs);
                    continue;
                }
                var model=models.iterator().next();
                for(String kind:List.of("texture","animation")) {
                    if(rejected.contains(kind)) {catalog.event(entry,"dynamic_reference_unresolved",kind+" has branch/field/dynamic construction");continue;}
                    Set<ResourceCatalog.Entry> deps=resolve(catalog,entry,kind,refs.getOrDefault(kind,Set.of()));
                    if(refs.getOrDefault(kind,Set.of()).size()==1 && deps.size()==1) {
                        var dep=deps.iterator().next(); catalog.bundles.get(ResourceCatalog.key(model)).add(new ResourceCatalog.Edge(ResourceCatalog.key(model),ResourceCatalog.key(dep),"literal_resource_methods:"+ResourceCatalog.key(entry)));
                    } else if(refs.containsKey(kind)) {catalog.event(entry,"unresolved_literal_reference",kind+":"+refs.get(kind));for(String ref:refs.get(kind))catalog.requirements.add(new GenericResourceResolver.Requirement(ResourceCatalog.key(model),kind,ref,deps.isEmpty()?"MISSING":"AMBIGUOUS",deps.stream().map(ResourceCatalog::key).toList(),"literal_resource_methods:"+ResourceCatalog.key(entry),"SINGLE"));}
                }
            } catch(Exception ex) {catalog.event(entry,"class_analysis_failed",ex.toString());}
        }
    }
    private static Set<ResourceCatalog.Entry> resolve(ResourceCatalog catalog,ResourceCatalog.Entry owner,String kind,Set<String> strings) {
        Set<ResourceCatalog.Entry> result=new LinkedHashSet<>();
        if(strings.isEmpty()) return result;
        for(String address:strings)catalog.uniqueResource(address,kind).ifPresent(result::add);
        return result;
    }
}
