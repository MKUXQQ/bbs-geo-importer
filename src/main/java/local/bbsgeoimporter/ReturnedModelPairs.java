package local.bbsgeoimporter;

import java.util.*;
import java.util.function.BiFunction;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Only allocations reaching ARETURN establish pairs. Short model ids must be unique in their original source. */
final class ReturnedModelPairs {
    private static final String PAIR="kotlin/Pair", CTOR="(Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String FACTORY_ARGS="(Ljava/lang/String;Ljava/lang/String;)";
    private final ResourceCatalog c;
    private final BiFunction<ResourceCatalog.Entry,String,ClassNode> nodes;
    private final Map<String,List<ResourceCatalog.Entry>> models=new HashMap<>();
    private final Set<String> indexed=new HashSet<>();
    private final Map<MethodNode,Optional<String>> namespaces=new IdentityHashMap<>();
    private record Fact(ResourceCatalog.Entry geo,ResourceCatalog.Entry png,String evidence) {}
    private final Map<String,List<Fact>> facts=new TreeMap<>();
    ReturnedModelPairs(ResourceCatalog c,BiFunction<ResourceCatalog.Entry,String,ClassNode> nodes){this.c=c;this.nodes=nodes;}

    private static Frame<SourceValue>[] frames(String owner,MethodNode m)throws AnalyzerException{
        return new Analyzer<>(new SourceInterpreter(Opcodes.ASM9){
            @Override public SourceValue copyOperation(AbstractInsnNode op,SourceValue v){return v;}
        }).analyze(owner,m);
    }
    private static AbstractInsnNode single(SourceValue v){return v.insns.size()==1?v.insns.iterator().next():null;}
    private static String literal(SourceValue v){return single(v) instanceof LdcInsnNode literal&&literal.cst instanceof String s?s:null;}
    private boolean factory(MethodInsnNode call){
        return call.getOpcode()==Opcodes.INVOKESTATIC&&ResourceMethods.location(call.owner)&&Set.of("fromNamespaceAndPath","method_60655").contains(call.name)&&call.desc.equals(FACTORY_ARGS+"L"+call.owner+";");
    }
    /** Prove the wrapper returns factory(literalNamespace, originalPath). Kotlin null-checks leave both operands intact. */
    private Optional<String> namespace(ClassNode owner,MethodNode wrapper){
        if(wrapper.instructions.size()>40||!wrapper.tryCatchBlocks.isEmpty()||(wrapper.access&Opcodes.ACC_STATIC)==0)return Optional.empty();
        try{
            AbstractInsnNode[] code=wrapper.instructions.toArray();
            var interpreter=new SourceInterpreter(Opcodes.ASM9){
                @Override public SourceValue copyOperation(AbstractInsnNode op,SourceValue v){
                    if(op instanceof VarInsnNode load&&load.getOpcode()==Opcodes.ALOAD&&load.var==0&&v.insns.isEmpty())return new SourceValue(1,op);
                    return v;
                }
            };
            var f=new Analyzer<>(interpreter).analyze(owner.name,wrapper);
            Set<String> result=new HashSet<>();
            for(int i=0;i<code.length;i++){
                if(code[i] instanceof VarInsnNode var&&var.getOpcode()==Opcodes.ASTORE&&var.var==0)return Optional.empty();
                if(code[i].getOpcode()!=Opcodes.ARETURN||f[i]==null)continue;
                if(!(single(f[i].getStack(f[i].getStackSize()-1)) instanceof MethodInsnNode call)||!factory(call))return Optional.empty();
                var args=f[wrapper.instructions.indexOf(call)];int end=args.getStackSize();String ns=literal(args.getStack(end-2));
                if(ns==null||!(single(args.getStack(end-1)) instanceof VarInsnNode v)||v.getOpcode()!=Opcodes.ALOAD||v.var!=0)return Optional.empty();
                result.add(ns);
            }
            return result.size()==1?Optional.of(result.iterator().next()):Optional.empty();
        }catch(AnalyzerException|RuntimeException ex){return Optional.empty();}
    }
    private String address(ResourceCatalog.Entry source,MethodNode m,Frame<SourceValue>[] f,SourceValue value){
        if(!(single(value) instanceof MethodInsnNode call)||call.getOpcode()!=Opcodes.INVOKESTATIC||!ResourceMethods.location(Type.getReturnType(call.desc).getInternalName()))return null;
        var args=f[m.instructions.indexOf(call)];int end=args.getStackSize();String ns=null,path;
        if(factory(call)){ns=literal(args.getStack(end-2));path=literal(args.getStack(end-1));}
        else{
            if(!call.desc.startsWith("(Ljava/lang/String;)"))return null;path=literal(args.getStack(end-1));if(path==null)return null;
            var owner=nodes.apply(source,call.owner);if(owner==null)return null;
            for(var method:owner.methods)if(method.name.equals(call.name)&&method.desc.equals(call.desc)){ns=namespaces.computeIfAbsent(method,k->namespace(owner,k)).orElse(null);break;}
        }
        if(ns==null||path==null)return null;
        try{return ResourceEvidence.address(ns+":"+path);}catch(IllegalArgumentException ex){return null;}
    }
    void collect(ResourceCatalog.Entry source,ClassNode cls,MethodNode method){
        if(method.instructions.size()>1024||!method.tryCatchBlocks.isEmpty())return;
        List<MethodInsnNode> ctors=new ArrayList<>();boolean hasGeo=false;
        for(var i:method.instructions){
            if(i instanceof MethodInsnNode m&&m.getOpcode()==Opcodes.INVOKESPECIAL&&m.owner.equals(PAIR)&&m.name.equals("<init>")&&m.desc.equals(CTOR))ctors.add(m);
            if(i instanceof LdcInsnNode ldc&&ldc.cst instanceof String s&&(s.endsWith(".geo")||s.endsWith(".geo.json")))hasGeo=true;
        }
        if(!hasGeo||ctors.isEmpty())return;
        try{
            var f=frames(cls.name,method);Set<AbstractInsnNode> returns=Collections.newSetFromMap(new IdentityHashMap<>());
            for(int i=0;i<f.length;i++)if(f[i]!=null&&method.instructions.get(i).getOpcode()==Opcodes.ARETURN)returns.addAll(f[i].getStack(f[i].getStackSize()-1).insns);
            for(var ctor:ctors){
                int index=method.instructions.indexOf(ctor);var args=f[index];if(args==null)continue;int end=args.getStackSize();
                AbstractInsnNode alloc=single(args.getStack(end-3));
                if(!(alloc instanceof TypeInsnNode type)||type.getOpcode()!=Opcodes.NEW||!type.desc.equals(PAIR)||!returns.contains(alloc))continue;
                String geoId=address(source,method,f,args.getStack(end-2)),pngId=address(source,method,f,args.getStack(end-1));
                if(geoId==null||!(geoId.endsWith(".geo")||geoId.endsWith(".geo.json")))continue;
                String site="returned_resource_pair:"+ResourceCatalog.key(source)+"."+method.name+method.desc+" @"+index;
                var geos=geometry(source,geoId);if(geos.size()!=1){c.event(source,"returned_pair_geometry_unresolved",site+" "+geoId+" candidates="+geos.size());continue;}
                var geo=geos.get(0);
                if(pngId==null){c.event(geo,"returned_pair_texture_unresolved",site+" second argument not statically proven");continue;}
                var png=pngId.endsWith(".png")&&SharedModelAssets.displayEligible(pngId)?c.uniqueResource(pngId,"texture").filter(e->e.owner().equals(source.owner())&&sameContainer(e,source)):Optional.<ResourceCatalog.Entry>empty();
                if(png.isEmpty()){
                    c.dependency(geo,"texture",pngId,"UNRESOLVED",List.of(),site,"SINGLE");
                    c.event(geo,"returned_pair_texture_unresolved",site+" "+pngId);continue;
                }
                facts.computeIfAbsent(ResourceCatalog.key(geo),k->new ArrayList<>()).add(new Fact(geo,png.get(),site));
            }
        }catch(AnalyzerException|RuntimeException ex){c.event(source,"returned_pair_analysis_failed",method.name+method.desc+" "+ex.getClass().getSimpleName());}
    }
    private boolean sameContainer(ResourceCatalog.Entry a,ResourceCatalog.Entry b){
        var x=c.identities.get(ResourceCatalog.key(a));var y=c.identities.get(ResourceCatalog.key(b));
        return x!=null&&y!=null&&x.has("physicalContainer")&&x.get("physicalContainer").equals(y.get("physicalContainer"));
    }
    private List<ResourceCatalog.Entry> geometry(ResourceCatalog.Entry origin,String id){
        if(indexed.add(origin.owner()))for(var e:c.entries.getOrDefault(origin.owner(),Map.of()).values())if(e.kind().equals("geo")){
            String addr=ResourceEvidence.address(e);if(addr==null||!addr.endsWith(".geo.json"))continue;
            int colon=addr.indexOf(':');String shortId=addr.substring(0,colon+1)+addr.substring(addr.lastIndexOf('/')+1);
            for(String name:new LinkedHashSet<>(List.of(addr,shortId)))models.computeIfAbsent(origin.owner()+"|"+name,k->new ArrayList<>()).add(e);
        }
        return models.getOrDefault(origin.owner()+"|"+(id.endsWith(".geo")?id+".json":id),List.of()).stream().filter(e->sameContainer(e,origin)).toList();
    }
    void publish(){
        for(var group:facts.entrySet()){
            String key=group.getKey();var list=group.getValue();var candidates=list.stream().map(Fact::png).distinct().toList();
            String prior=c.defaultTextures.get(key);
            if(candidates.size()!=1||c.defaultTextureConflicts.contains(key)||prior!=null&&!prior.equals(ResourceCatalog.key(candidates.get(0)))){
                c.dependency(list.get(0).geo(),"texture","returned ResourceLocation pair","AMBIGUOUS",candidates,list.get(0).evidence(),"SINGLE");
                c.event(list.get(0).geo(),"returned_pair_texture_conflict",candidates.stream().map(ResourceCatalog::key).toList().toString());continue;
            }
            for(var fact:list)c.dependency(fact.geo(),"texture",ResourceEvidence.address(fact.png()),"FOUND",List.of(fact.png()),fact.evidence(),"SINGLE");
            c.defaultTextures.putIfAbsent(key,ResourceCatalog.key(candidates.get(0)));
        }
    }
}
