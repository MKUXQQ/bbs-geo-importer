package local.bbsgeoimporter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;
/** Bounded bytecode evaluation; never loads or executes a mod class. Unknown values never establish edges. */
final class ConstantModelReferences {
    private static final Object UNKNOWN=new Object();
    private record Resource(String value) {}
    private record Registry(String namespace) {}
    private record Registered(Resource id) {}
    private record EnumValue(String owner,String field) {}
    private record Lambda(Handle target,Object[] captured) {}
    private record Template(String prefix,Symbol symbol,String suffix) {}
    private record ResourceTemplate(String namespace,Template template) {}
    private record Tick(String field,int divisor,int frames) {}
    private static final class Instance {
        final String type; final Map<String,Object> fields=new HashMap<>();
        Instance(String type){this.type=type;}
    }
    private final ResourceCatalog catalog;
    private final Map<String,ResourceCatalog.Entry> classes=new HashMap<>();
    private final Map<String,ClassNode> nodes=new HashMap<>();
    private static final class ClassIndex {
        Map<String,MethodNode> methods;
        final Map<String,List<MethodNode>> named=new HashMap<>();
        Map<String,List<FieldNode>> statics;
        Map<String,FieldNode> fields;
        final boolean model,renderer;
        ClassIndex(ClassNode node){
            boolean model=false,renderer=false;
            for(MethodNode method:node.methods){model|="geo".equals(ResourceMethods.kind(method.name));renderer|=method.name.equals("getTextureLocation");}
            this.model=model||node.name.endsWith("GeoModel");this.renderer=renderer||node.name.startsWith("software/bernie/geckolib/renderer/")||node.name.startsWith("mod/azure/azurelib/renderer/");
        }
    }
    private static final class MethodCode {
        final AbstractInsnNode[] instructions;
        final Map<LabelNode,Integer> labels=new IdentityHashMap<>();
        final Type[] argumentTypes;
        MethodCode(MethodNode method){instructions=method.instructions.toArray();argumentTypes=Type.getArgumentTypes(method.desc);for(int i=0;i<instructions.length;i++)if(instructions[i] instanceof LabelNode label)labels.put(label,i);}
    }
    private final Map<String,ClassIndex> indexes=new HashMap<>();
    private final Map<MethodNode,MethodCode> methodCode=new IdentityHashMap<>();
    private final Map<MethodNode,String> fieldGetters=new IdentityHashMap<>();
    private final Map<MethodNode,List<String>> returnedTextures=new IdentityHashMap<>();
    private final Map<String,Boolean> rendererTypes=new HashMap<>();
    private final Map<String,Object> syncedDefaults=new HashMap<>();
    private final Map<String,Set<String>> selectorValues=new HashMap<>();
    private Map<String,List<String>> staticOwners;
    private long classParses,codeBuilds,codeReuses,methodEvaluations,registeredIndexReuses,syncedReuses,selectorReuses;
    private final Set<String> emitted=new HashSet<>();
    private final Map<String,Object> staticFields=new HashMap<>();
    private final Set<String> initialized=new HashSet<>();
    private final Map<String,List<Object>> finiteInstances=new HashMap<>();
    private int budget;
    private final Map<String,Boolean> modelTypes=new HashMap<>();
    private record Symbol(String path) {}
    private static final Object NULL=new Object();
    private Map<String,Object> choices=Map.of();
    private String pendingChoice;
    private List<Object> pendingValues;
    private boolean associating;
    private final Set<String> rendererOverrides=new HashSet<>();
    private final Map<Instance,ResourceCatalog.Entry> contexts=new LinkedHashMap<>();
    private final Set<Instance> rendererModels=Collections.newSetFromMap(new IdentityHashMap<>());
    private ConstantModelReferences(ResourceCatalog c){catalog=c;for(var files:c.entries.values())for(var e:files.values())if(e.path().endsWith(".class")&&!e.path().startsWith("META-INF/"))classes.putIfAbsent(e.path().substring(0,e.path().length()-6),e);}
    static void analyze(ResourceCatalog c){var analysis=new ConstantModelReferences(c);try{analysis.run();}finally{analysis.metrics();}}
    private void metrics(){catalog.report.addProperty("bytecodeClassesParsed",classParses);catalog.report.addProperty("bytecodeCodeBuilds",codeBuilds);catalog.report.addProperty("bytecodeCodeReuses",codeReuses);catalog.report.addProperty("bytecodeMethodEvaluations",methodEvaluations);catalog.report.addProperty("bytecodeRegisteredIndexReuses",registeredIndexReuses);catalog.report.addProperty("bytecodeSyncedDefaultReuses",syncedReuses);catalog.report.addProperty("bytecodeSelectorReuses",selectorReuses);}
    private MethodNode declaredMethod(ClassNode owner,String name,String desc){ClassIndex index=indexes.get(owner.name);if(index.methods==null){index.methods=new HashMap<>();for(MethodNode method:owner.methods)index.methods.putIfAbsent(method.name+method.desc,method);}return index.methods.get(name+desc);}
    private List<MethodNode> namedMethods(ClassNode owner,String name){return indexes.get(owner.name).named.computeIfAbsent(name,k->owner.methods.stream().filter(m->m.name.equals(k)).toList());}
    private FieldNode declaredField(ClassNode owner,String name){ClassIndex index=indexes.get(owner.name);if(index.fields==null){index.fields=new HashMap<>();for(FieldNode field:owner.fields)if(field.value!=null)index.fields.put(field.name,field);else index.fields.putIfAbsent(field.name,field);}return index.fields.get(name);}
    private List<FieldNode> staticFields(ClassNode owner,String descriptor){ClassIndex index=indexes.get(owner.name);if(index.statics==null){index.statics=new HashMap<>();for(FieldNode field:owner.fields)if((field.access&Opcodes.ACC_STATIC)!=0)index.statics.computeIfAbsent(field.desc,k->new ArrayList<>()).add(field);}return index.statics.getOrDefault(descriptor,List.of());}
    private MethodCode code(MethodNode method){MethodCode existing=methodCode.get(method);if(existing!=null){codeReuses++;return existing;}MethodCode result=new MethodCode(method);methodCode.put(method,result);codeBuilds++;return result;}
    private ClassNode node(String name){
        if(nodes.containsKey(name))return nodes.get(name);
        var e=classes.get(name);if(e==null){nodes.put(name,null);return null;}
        try{var n=new ClassNode();new ClassReader(AnalysisInputs.bytes(e.file())).accept(n,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);nodes.put(name,n);indexes.put(name,new ClassIndex(n));classParses++;return n;}catch(Exception ex){nodes.put(name,null);catalog.event(e,"class_analysis_failed",ex.toString());return null;}
    }
    private boolean isModel(String type){Boolean cached=modelTypes.get(type);if(cached!=null)return cached;boolean result=modelType(type);modelTypes.put(type,result);return result;}
    private static boolean azureBuilder(MethodInsnNode c){
        return c.getOpcode()==Opcodes.INVOKESTATIC&&c.name.equals("builder")&&c.owner.endsWith("RendererConfig")&&
            (c.owner.startsWith("mod/azure/azurelib/render/")||c.owner.startsWith("mod/azure/azurelib/common/render/"))&&
            c.desc.equals("(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/resources/ResourceLocation;)L"+c.owner+"$Builder;");
    }
    private boolean modelType(String type){Set<String> seen=new HashSet<>();for(ClassNode n=node(type);n!=null&&seen.add(n.name);n=node(n.superName))if(indexes.get(n.name).model)return true;return false;}
    private boolean isRenderer(String type){Boolean cached=rendererTypes.get(type);if(cached!=null)return cached;Set<String> seen=new HashSet<>();boolean result=false;for(ClassNode n=node(type);n!=null&&seen.add(n.name);n=node(n.superName))if(indexes.get(n.name).renderer){result=true;break;}rendererTypes.put(type,result);return result;}
    private void run(){
        var pairs=new ReturnedModelPairs(catalog,(entry,owner)->{var source=classes.get(owner);return source!=null&&source.owner().equals(entry.owner())?node(owner):null;});
        for(var entry:classes.entrySet()) {
            // Restrict evaluation to resource-bearing classes and their callers, not library implementation details.
            try{
                ClassNode n=node(entry.getKey());if(n==null)continue;
                for(MethodNode m:n.methods){
                    if(m.desc.endsWith(")Lkotlin/Pair;"))pairs.collect(entry.getValue(),n,m);
                    boolean resourceMethod=ResourceMethods.kind(m.name)!=null;
                    boolean modelConstruction=false,configCall=false;for(var in:m.instructions){if(in instanceof MethodInsnNode call&&azureBuilder(call))configCall=true;if(in instanceof TypeInsnNode type&&type.getOpcode()==Opcodes.NEW&&isModel(type.desc)&&classes.containsKey(type.desc))modelConstruction=true;if(modelConstruction&&configCall)break;}
                    boolean constructor=m.name.equals("<init>")&&(n.name.contains("Renderer")||n.name.contains("Model")||modelConstruction);
                    if(!resourceMethod&&!modelConstruction&&!constructor&&!configCall)continue;
                    budget=12000;Instance self=new Instance(n.name);Object[] args=new Object[Type.getArgumentTypes(m.desc).length];Arrays.fill(args,UNKNOWN);
                    if(resourceMethod){eval(n.name,m.name,m.desc,self,args,0);associate(self,entry.getValue());}
                    if(constructor||configCall){eval(n.name,m.name,m.desc,self,args,0);associate(self,entry.getValue());}
                    var code=code(m).instructions;
                    for(int pc=0;pc<code.length;pc++)if(code[pc] instanceof TypeInsnNode t&&t.getOpcode()==Opcodes.NEW&&isModel(t.desc)&&classes.containsKey(t.desc)){
                        budget=12000;evalRange(n.name,m.name,m.desc,new Instance(n.name),args,0,pc);
                    }
                }
            }catch(Exception ignored){/* Unknown control flow remains reported by the literal analyzer. */}
        }
        
        appendReferenceDiagnostics();
        // Renderer overrides suppress speculative standalone contexts, never shared edges.
        var pending=new ArrayList<>(contexts.entrySet());
        for(var context:pending)if(!isModel(context.getKey().type)&&context.getKey().fields.containsKey("@model"))associateNow(context.getKey(),context.getValue());
        for(var context:pending)if(!context.getKey().fields.containsKey("@model"))associateNow(context.getKey(),context.getValue());
        pairs.publish();
    }
    /** Exact incoming literals are diagnostics only, never texture pairing evidence. */
    private void appendReferenceDiagnostics(){
        Map<String,List<String>> sites=new HashMap<>();
        for(var owner:classes.keySet()){var cn=node(owner);if(cn==null)continue;for(var method:cn.methods){int pc=0;for(var insn:method.instructions){if(insn instanceof LdcInsnNode ldc&&ldc.cst instanceof String literal)sites.computeIfAbsent(literal,k->new ArrayList<>()).add(owner+"."+method.name+method.desc+" @instruction "+pc);pc++;}}}
        for(var group:catalog.entries.values())for(var model:group.values())if(model.kind().equals("geo")){
            String address=ResourceEvidence.address(model);String path=address==null?model.path():address.substring(address.indexOf(':')+1);List<String> incoming=new ArrayList<>(sites.getOrDefault(path,List.of()));incoming.addAll(sites.getOrDefault(address,List.of()));
            catalog.event(model,"model_reference_sites",incoming.isEmpty()?"No exact incoming model resource literal in current class graph; constructed/dynamic references require proven getter evaluation; no filename inference":String.join("; ",incoming));
        }
    }
    private void initialize(String owner){int previous=budget;boolean active=associating;String pending=pendingChoice;List<Object> values=pendingValues;var selected=choices;budget=24000;associating=false;pendingChoice=null;choices=Map.of();try{eval(owner,"<clinit>","()V",null,new Object[0],0);}finally{budget=previous;associating=active;pendingChoice=pending;pendingValues=values;choices=selected;}}
    private Object eval(String owner,String name,String desc,Instance self,Object[] args,int depth){return evalRange(owner,name,desc,self,args,depth,0);}
    private Object evalRange(String owner,String name,String desc,Instance self,Object[] args,int depth,int start){
        if(depth>16||budget<=0)return UNKNOWN;
        ClassNode n=node(owner);if(n==null)return UNKNOWN;
        MethodNode m=declaredMethod(n,name,desc);
        if(m==null)return n.superName==null?UNKNOWN:eval(n.superName,name,desc,self,args,depth+1);
        methodEvaluations++;MethodCode indexed=code(m);
        Object[] locals=new Object[Math.max(m.maxLocals,16)];Arrays.fill(locals,UNKNOWN);int slot=0;
        if((m.access&Opcodes.ACC_STATIC)==0)locals[slot++]=self;
        Type[] types=indexed.argumentTypes;for(int i=0;i<args.length;i++){locals[slot]=args[i];slot+=types[i].getSize();}
        List<Object> stack=new ArrayList<>();
        AbstractInsnNode[] code=indexed.instructions;Map<LabelNode,Integer> labels=indexed.labels;
        try{for(int pc=start;pc<code.length&&--budget>0;pc++){
            if(associating&&pendingChoice!=null&&!choices.containsKey(pendingChoice))return UNKNOWN;var in=code[pc];int op=in.getOpcode();if(op<0)continue;
            if(in instanceof LdcInsnNode ldc){stack.add(ldc.cst);continue;}
            if(in instanceof VarInsnNode v){if(op>=Opcodes.ILOAD&&op<=Opcodes.ALOAD)stack.add(locals[v.var]);else if(op>=Opcodes.ISTORE&&op<=Opcodes.ASTORE)locals[v.var]=pop(stack);else return UNKNOWN;continue;}
            if(in instanceof TypeInsnNode t){if(op==Opcodes.NEW)stack.add(new Instance(t.desc));else if(op==Opcodes.ANEWARRAY){Object size=pop(stack);if(!(size instanceof Integer count)||count<0||count>256)return UNKNOWN;Object[] array=new Object[count];Arrays.fill(array,UNKNOWN);stack.add(array);}else if(op==Opcodes.INSTANCEOF){Object value=pop(stack);stack.add(value instanceof Symbol sym?new Symbol(sym.path()+" instanceof "+t.desc):value instanceof Instance i?i.type.equals(t.desc)?1:0:UNKNOWN);}else if(op!=Opcodes.CHECKCAST)return UNKNOWN;continue;}
            if(in instanceof IntInsnNode x){if(op==Opcodes.BIPUSH||op==Opcodes.SIPUSH)stack.add(x.operand);else return UNKNOWN;continue;}
            if(in instanceof FieldInsnNode f){
                if(op==Opcodes.GETFIELD){Object obj=pop(stack);if(obj instanceof Symbol symbol){if(f.desc.equals("I")&&Set.of("tickCount","f_19797_","field_6012").contains(f.name)){stack.add(new Tick(symbol.path()+":"+f.owner+"."+f.name,1,0));continue;}ClassNode enumNode=node(f.owner);if(enumNode!=null&&(enumNode.access&Opcodes.ACC_ENUM)!=0){if(initialized.add(f.owner))initialize(f.owner);List<Object> values=new ArrayList<>();for(var field:enumNode.fields)if((field.access&Opcodes.ACC_ENUM)!=0){Object v=staticFields.get(f.owner+"."+field.name);if(v instanceof Instance)values.add(v);}if(!values.isEmpty()){pendingChoice=symbol.path();pendingValues=values;}return UNKNOWN;}}stack.add(obj instanceof Instance i?i.fields.getOrDefault(f.owner+"."+f.name,UNKNOWN):UNKNOWN);}
                else if(op==Opcodes.PUTFIELD){Object value=pop(stack),obj=pop(stack);if(obj instanceof Instance i)i.fields.put(f.owner+"."+f.name,value);}
                else if(op==Opcodes.GETSTATIC){ClassNode cn=node(f.owner);Object value=UNKNOWN;if(f.owner.equals("java/util/Locale"))value=switch(f.name){case "ROOT"->Locale.ROOT;case "ENGLISH"->Locale.ENGLISH;case "US"->Locale.US;default->UNKNOWN;};if(cn!=null){FieldNode field=declaredField(cn,f.name);if(field!=null&&field.value!=null)value=field.value;if(value==UNKNOWN&&initialized.add(f.owner))initialize(f.owner);value=staticFields.getOrDefault(f.owner+"."+f.name,value);if(value==UNKNOWN&&(cn.access&Opcodes.ACC_ENUM)!=0&&f.desc.equals("L"+f.owner+";"))value=new EnumValue(f.owner,f.name);}stack.add(value);}
                else if(op==Opcodes.PUTSTATIC)staticFields.put(f.owner+"."+f.name,pop(stack));else return UNKNOWN;continue;
            }
            if(in instanceof InvokeDynamicInsnNode d){
                Type[] at=Type.getArgumentTypes(d.desc);Object[] av=arguments(stack,at.length);Object value=UNKNOWN;
                if(associating&&"texture".equals(ResourceMethods.kind(name))&&d.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory"))
                    for(int i=0;i<av.length;i++)if(av[i] instanceof Tick t&&t.frames()>0&&at[i].equals(Type.INT_TYPE)){
                        String selector="display-frame:"+owner+"."+name+desc+"@"+pc+":"+t;
                        if(!choices.containsKey(selector)){pendingChoice=selector;pendingValues=new ArrayList<>();for(int j=0;j<t.frames();j++)pendingValues.add(j);return UNKNOWN;}
                        av[i]=choices.get(selector);
                    }
                if(d.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")&&d.bsmArgs.length>1&&d.bsmArgs[1] instanceof Handle handle)value=new Lambda(handle,av);
                else if(d.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")&&d.bsmArgs.length>0&&d.bsmArgs[0] instanceof String recipe){StringBuilder b=new StringBuilder();int ai=0,ci=1;boolean known=true;Symbol variable=null;String prefix=null;for(char ch:recipe.toCharArray()){Object x=ch==1?av[ai++]:ch==2?d.bsmArgs[ci++]:String.valueOf(ch);if(x instanceof Symbol symbol&&variable==null){variable=symbol;prefix=b.toString();b.setLength(0);}else if(x instanceof Symbol symbol){variable=new Symbol(variable.path()+"+"+b+"+"+symbol.path());b.setLength(0);}else if(x instanceof String||x instanceof Number||x instanceof Character)b.append(x);else{known=false;break;}}if(known)value=variable==null?b.toString():choices.containsKey(variable.path())?prefix+choices.get(variable.path())+b:new Template(prefix,variable,b.toString());}stack.add(value);continue;
            }
            if(in instanceof MethodInsnNode call){
                Object[] av=arguments(stack,Type.getArgumentTypes(call.desc).length);Object receiver=op==Opcodes.INVOKESTATIC?null:pop(stack);Object value=UNKNOWN;
                if(call.name.equals("<init>")&&receiver instanceof Instance instance)for(Object arg:av)if(arg instanceof Instance model&&isModel(model.type)&&isRenderer(instance.type)){instance.fields.put("@model",model);if(!isModel(instance.type))rendererModels.add(model);}
                if(call.name.equals("addRenderLayer")&&receiver instanceof Instance instance&&av.length==1&&av[0] instanceof Instance layer){@SuppressWarnings("unchecked") List<Instance> layers=(List<Instance>)instance.fields.computeIfAbsent("@layers",k->new ArrayList<Instance>());layers.add(layer);}
                boolean resource=ResourceMethods.location(call.owner);
                if(call.owner.equals("java/lang/Enum")&&call.name.equals("<init>")&&receiver instanceof Instance i&&av.length==2&&av[0] instanceof String enumName&&av[1] instanceof Integer ordinal){i.fields.put("@enumName",enumName);i.fields.put("@enumOrdinal",ordinal);}
                else if(receiver instanceof Instance i&&av.length==0&&call.name.equals("name")&&call.desc.equals("()Ljava/lang/String;")&&i.fields.containsKey("@enumName"))value=i.fields.get("@enumName");
                else if(receiver instanceof Instance i&&av.length==0&&call.name.equals("ordinal")&&call.desc.equals("()I")&&i.fields.containsKey("@enumOrdinal"))value=i.fields.get("@enumOrdinal");
                else if(resource&&call.name.equals("<init>")&&receiver instanceof Instance i){if(av.length==1&&av[0] instanceof String s)i.fields.put("@resource",new Resource(s.contains(":")?s:"minecraft:"+s));else if(av.length==2&&av[0] instanceof String a&&av[1] instanceof String b)i.fields.put("@resource",new Resource(a+":"+b));else if(av.length==2&&av[0] instanceof String ns&&av[1] instanceof Template template)i.fields.put("@resource",new ResourceTemplate(ns,template));}
                else if(resource&&op==Opcodes.INVOKESTATIC&&call.name.equals("fromNamespaceAndPath")&&Type.getArgumentTypes(call.desc).length==2){
                    if(av[0] instanceof String ns&&av[1] instanceof String path)value=new Resource(ns+":"+path);
                    else if(av[0] instanceof String ns&&av[1] instanceof Template template)value=new ResourceTemplate(ns,template);
                }
                else if(resource&&op==Opcodes.INVOKESTATIC&&Set.of("parse","tryParse","withDefaultNamespace").contains(call.name)&&Type.getArgumentTypes(call.desc).length==1&&av[0] instanceof String path)value=new Resource(call.name.equals("withDefaultNamespace")?"minecraft:"+path:path.contains(":")?path:"minecraft:"+path);
                else if(azureBuilder(call)){
                    Instance config=new Instance(call.owner+"$Builder");config.fields.put("@geometry",av[0]);config.fields.put("@texture",av[1]);value=config;
                }
                else if(receiver instanceof Instance config&&config.fields.containsKey("@geometry")&&call.owner.equals(config.type)&&config.type.endsWith("RendererConfig$Builder")){
                    value=config; // documented fluent configuration identity, no third-party execution
                    if(call.name.startsWith("set")&&!Set.of("setScale","setAlpha","setAnimatorProvider").contains(call.name))config.fields.put("@unsupported:"+call.name,call.owner+"."+call.name+call.desc);
                    if(call.name.equals("build"))associate(config,classes.get(owner));
                }
                else if(call.owner.equals("java/lang/String")&&Set.of("format","formatted").contains(call.name)){
                    Object format=call.name.equals("formatted")?receiver:av.length==2?av[0]:UNKNOWN;Object array=av.length>0?av[av.length-1]:UNKNOWN;
                    if(format instanceof String f&&array instanceof Object[] values&&Arrays.stream(values).allMatch(v->v instanceof String||v instanceof Number||v instanceof Boolean||v instanceof Character))try{value=String.format(Locale.ROOT,f,values);}catch(IllegalFormatException ignored){}
                }
                else if(call.owner.equals("com/teamresourceful/resourcefullib/common/registry/ResourcefulRegistries")&&call.name.equals("create")){
                    if(av.length==2&&av[1] instanceof String ns)value=new Registry(ns);else if(av.length==1&&av[0] instanceof Registry registry)value=registry;
                }
                else if(call.owner.equals("com/teamresourceful/resourcefullib/common/registry/ResourcefulRegistry")&&call.name.equals("register")&&receiver instanceof Registry registry&&av.length==2&&av[0] instanceof String path)value=new Registered(new Resource(registry.namespace()+":"+path));
                else if(call.owner.equals("com/teamresourceful/resourcefullib/common/registry/RegistryEntry")&&call.name.equals("get")&&receiver instanceof Registered registered)value=registered;
                else if(Set.of("net/minecraft/core/DefaultedRegistry","net/minecraft/core/Registry").contains(call.owner)&&Set.of("getKey","m_7981_").contains(call.name)&&av.length==1&&av[0] instanceof Registered registered)value=registered.id();
                else if(receiver instanceof Lambda lambda&&Set.of("get","apply","create").contains(call.name)){
                    List<Object> values=new ArrayList<>(Arrays.asList(lambda.captured()));values.addAll(Arrays.asList(av));Handle h=lambda.target();if(h.getTag()==Opcodes.H_NEWINVOKESPECIAL){
                        Instance made=new Instance(h.getOwner());eval(h.getOwner(),h.getName(),h.getDesc(),made,values.toArray(),depth+1);value=made;
                        if(!associating)associate(made,classes.get(made.type));
                    }else if(h.getTag()==Opcodes.H_INVOKESTATIC)value=eval(h.getOwner(),h.getName(),h.getDesc(),null,values.toArray(),depth+1);
                    else if(!values.isEmpty()&&values.get(0) instanceof Instance bound)value=eval(h.getTag()==Opcodes.H_INVOKESPECIAL?h.getOwner():bound.type,h.getName(),h.getDesc(),bound,values.subList(1,values.size()).toArray(),depth+1);
                }
                else if(call.owner.equals("java/lang/String")&&call.name.equals("concat")&&receiver instanceof String a&&av.length==1&&av[0] instanceof String b)value=a+b;
                else if(call.owner.equals("java/lang/String")&&receiver instanceof String s&&av.length==1&&av[0] instanceof Locale locale&&Set.of("toLowerCase","toUpperCase").contains(call.name))value=call.name.equals("toLowerCase")?s.toLowerCase(locale):s.toUpperCase(locale);
                else if(call.owner.equals("java/lang/String")&&call.name.equals("valueOf")&&av.length==1&&(av[0] instanceof String||av[0] instanceof Number||av[0] instanceof Boolean||av[0] instanceof Character))value=String.valueOf(av[0]);
                else if(call.owner.equals("java/lang/String")&&call.name.equals("replace")&&receiver instanceof String a&&av.length==2&&av[0] instanceof String from&&av[1] instanceof String to)value=a.replace(from,to);
                else if(call.owner.equals("java/lang/String")&&call.name.equals("endsWith")&&receiver instanceof String a&&av.length==1&&av[0] instanceof String suffix)value=a.endsWith(suffix)?1:0;
                else if(call.owner.equals("java/lang/String")&&call.name.equals("equals")&&receiver instanceof String a&&av.length==1&&av[0] instanceof String b)value=a.equals(b)?1:0;
                else if(resource&&(receiver instanceof Resource||receiver instanceof Instance i&&i.fields.get("@resource") instanceof Resource)&&av.length==0){Resource r=receiver instanceof Resource direct?direct:(Resource)((Instance)receiver).fields.get("@resource");int colon=r.value().indexOf(':');if(Set.of("getNamespace","m_135827_","method_12836").contains(call.name))value=r.value().substring(0,colon);else if(Set.of("getPath","m_135815_","method_12832").contains(call.name))value=r.value().substring(colon+1);}
                else if(node(call.owner)!=null){ClassNode calledOwner=node(call.owner);String dispatchOwner=receiver instanceof Instance i&&op!=Opcodes.INVOKESPECIAL?i.type:call.owner;value=receiver instanceof Symbol?syncedDefault(call.owner,call.name,call.desc):UNKNOWN;MethodNode getter=declaredMethod(calledOwner,call.name,call.desc);if(value==UNKNOWN&&receiver instanceof Symbol symbol&&getter!=null&&!simpleFieldGetter(getter).isEmpty()){List<Object> finite=(calledOwner.access&Opcodes.ACC_ENUM)!=0?List.of():registeredInstances(call.owner,depth);if(!finite.isEmpty()){pendingChoice=symbol.path();pendingValues=finite;return UNKNOWN;}}if(value==UNKNOWN&&!(receiver instanceof Symbol))value=eval(dispatchOwner,call.name,call.desc,receiver instanceof Instance i?i:null,av,depth+1);}
                if(value==UNKNOWN&&receiver instanceof Symbol symbol&&!call.name.equals("<init>")){String path=symbol.path()+"/"+call.name+call.desc;value=choices.getOrDefault(path,new Symbol(path));}
                if(value==UNKNOWN&&op==Opcodes.INVOKESTATIC&&Type.getReturnType(call.desc).getSort()==Type.OBJECT&&Arrays.stream(av).anyMatch(v->v instanceof Symbol)){String path=call.owner+"."+call.name+call.desc+Arrays.toString(av);value=choices.getOrDefault(path,new Symbol(path));}
                if(call.name.equals("<init>")&&receiver instanceof Instance i&&classes.containsKey(i.type)&&!associating&&call.owner.equals(i.type))associate(i,classes.get(i.type));
                if(Type.getReturnType(call.desc)!=Type.VOID_TYPE)stack.add(value);continue;
            }
            if(in instanceof TableSwitchInsnNode sw){Object v=pop(stack);String path="switch:"+owner+"."+name+":"+pc;if(v instanceof Symbol symbol)path=symbol.path();if(!(v instanceof Integer)){if(!choices.containsKey(path)){pendingChoice=path;pendingValues=new ArrayList<>();for(int i=sw.min;i<=sw.max&&pendingValues.size()<64;i++)pendingValues.add(i);return UNKNOWN;}v=choices.get(path);}int k=(Integer)v;pc=labels.get(k>=sw.min&&k<=sw.max?sw.labels.get(k-sw.min):sw.dflt);continue;}
            if(in instanceof LookupSwitchInsnNode sw){Object v=pop(stack);String path=v instanceof Symbol sym?sym.path():"switch:"+owner+"."+name+":"+pc;if(!(v instanceof Integer)){if(!choices.containsKey(path)){pendingChoice=path;pendingValues=new ArrayList<>(sw.keys);return UNKNOWN;}v=choices.get(path);}int k=sw.keys.indexOf(v);pc=labels.get(k<0?sw.dflt:sw.labels.get(k));continue;}
            if(in instanceof JumpInsnNode jump){
                if(op==Opcodes.GOTO){pc=labels.get(jump.label);continue;}
                Object value=pop(stack);if(value instanceof Symbol symbol&&(op==Opcodes.IFEQ||op==Opcodes.IFNE||op==Opcodes.IFNULL||op==Opcodes.IFNONNULL)){String path=(op==Opcodes.IFNULL||op==Opcodes.IFNONNULL)?symbol.path()+"@nonnull":symbol.path();if(!choices.containsKey(path)){pendingChoice=path;pendingValues=List.of(0,1);return UNKNOWN;}value=choices.get(path);}if(value==UNKNOWN&&op!=Opcodes.IF_ACMPEQ&&op!=Opcodes.IF_ACMPNE)return UNKNOWN;boolean branch;
                if(op==Opcodes.IFEQ||op==Opcodes.IFNE){if(!(value instanceof Integer x))return UNKNOWN;branch=op==Opcodes.IFEQ?x==0:x!=0;}
                else if(op==Opcodes.IF_ACMPEQ||op==Opcodes.IF_ACMPNE){Object left=pop(stack);if(left==UNKNOWN||left instanceof Symbol||value instanceof Symbol){String path=left instanceof Symbol sym&&value instanceof EnumValue e?"enum:"+sym.path()+"="+e.owner()+"."+e.field():"compare:"+owner+"."+name+":"+pc;if(!choices.containsKey(path)){pendingChoice=path;pendingValues=List.of(0,1);return UNKNOWN;}branch=op==Opcodes.IF_ACMPEQ?((Integer)choices.get(path))!=0:((Integer)choices.get(path))==0;}else {boolean same=left==value||left instanceof EnumValue&&left.equals(value);branch=op==Opcodes.IF_ACMPEQ?same:!same;}}
                else if(op==Opcodes.IFNULL||op==Opcodes.IFNONNULL){boolean nonnull=value instanceof Integer x?x!=0:value!=NULL;branch=op==Opcodes.IFNULL?!nonnull:nonnull;}
                else return UNKNOWN;
                if(branch)pc=labels.get(jump.label);continue;
            }
            switch(op){
                case Opcodes.IDIV:case Opcodes.IREM:{
                    Object right=pop(stack),left=pop(stack);Object result=UNKNOWN;
                    if(right instanceof Integer k&&k>0){
                        if(left instanceof Integer v)result=op==Opcodes.IDIV?v/k:v%k;
                        else if(left instanceof Tick t&&t.frames()==0){
                            if(op==Opcodes.IDIV&&t.divisor()==1)result=new Tick(t.field(),k,0);
                            else if(op==Opcodes.IREM&&k<=64)result=new Tick(t.field(),t.divisor(),k);
                        }
                    }
                    stack.add(result);break;}
                case Opcodes.NOP:break;
                case Opcodes.ACONST_NULL:stack.add(NULL);break;
                case Opcodes.ICONST_M1:case Opcodes.ICONST_0:case Opcodes.ICONST_1:case Opcodes.ICONST_2:case Opcodes.ICONST_3:case Opcodes.ICONST_4:case Opcodes.ICONST_5:stack.add(op-Opcodes.ICONST_0);break;
                case Opcodes.FCONST_0:case Opcodes.FCONST_1:case Opcodes.FCONST_2:stack.add((float)(op-Opcodes.FCONST_0));break;
                case Opcodes.DCONST_0:case Opcodes.DCONST_1:stack.add((double)(op-Opcodes.DCONST_0));break;
                case Opcodes.AASTORE:{Object value=pop(stack),index=pop(stack),array=pop(stack);if(!(array instanceof Object[] values)||!(index instanceof Integer i)||i<0||i>=values.length)return UNKNOWN;values[i]=value;break;}
                case Opcodes.AALOAD:case Opcodes.IALOAD:{Object index=pop(stack),array=pop(stack);if(array instanceof Object[] values&&index instanceof Integer i&&i>=0&&i<values.length)stack.add(values[i]);else {pendingChoice=index instanceof Symbol sym?"enum-switch:"+sym.path():"array:"+owner+"."+name+":"+pc;pendingValues=List.of(1,2,3,4);stack.add(choices.getOrDefault(pendingChoice,new Symbol(pendingChoice)));}break;}
                case Opcodes.DUP:stack.add(stack.get(stack.size()-1));break;
                case Opcodes.POP:pop(stack);break;
                case Opcodes.ARETURN:case Opcodes.IRETURN:return pop(stack);
                case Opcodes.RETURN:return UNKNOWN;
                default:return UNKNOWN;
            }
        }}catch(RuntimeException ignored){}return UNKNOWN;
    }
    private void associate(Instance self,ResourceCatalog.Entry origin){if(!associating&&origin!=null)contexts.put(self,origin);}
    private void associateNow(Instance self,ResourceCatalog.Entry origin){
        Map<String,Object> previous=choices;
        boolean previousAssociating=associating;associating=true;
        Deque<Map<String,Object>> queue=new ArrayDeque<>();queue.add(Map.of());int variants=0;
        while(!queue.isEmpty()&&variants++<256){
            budget=12000;
            choices=queue.removeFirst();pendingChoice=null;pendingValues=null;
            associateChoice(self,origin);
            if(pendingChoice!=null&&!choices.containsKey(pendingChoice))for(Object value:pendingValues){Map<String,Object> next=new HashMap<>(choices);next.put(pendingChoice,value);queue.add(next);}
        }
        choices=previous;associating=previousAssociating;
    }
    private void associateChoice(Instance self,ResourceCatalog.Entry origin){
        if(origin==null||self==null||budget<=0)return;
        Map<String,String> refs=new HashMap<>();String preferredTexture=null;boolean rendererOverride=false;
        if(self.fields.containsKey("@geometry")){
            Object geometry=resourceValue(self.fields.get("@geometry")),texture=resourceValue(self.fields.get("@texture"));
            if(geometry instanceof Resource r)refs.put("getModelResource",r.value());
            if(texture instanceof Resource r){refs.put("getTextureResource",r.value());preferredTexture=r.value();}
        }
        for(String method:self.fields.containsKey("@geometry")?List.<String>of():List.of("getModelResource","getTextureResource","getAnimationResource")){
            Instance subject=self.fields.get("@model") instanceof Instance model?model:self;
            boolean useRenderer=method.equals("getTextureResource")&&self!=subject;String actualMethod=useRenderer?"getTextureLocation":method;
            ClassNode cn=node(method.equals("getTextureResource")&&self!=subject?self.type:subject.type);Set<String> seen=new HashSet<>();List<String> descriptors=new ArrayList<>();
            while(cn!=null&&seen.add(cn.name)){for(var m:namedMethods(cn,actualMethod))if((m.access&Opcodes.ACC_BRIDGE)==0)descriptors.add(m.desc);if(!descriptors.isEmpty())break;cn=node(cn.superName);}
            if(descriptors.size()>1&&actualMethod.equals("getTextureLocation"))descriptors.removeIf(d->d.contains("Lnet/minecraft/world/entity/Entity;"));if(descriptors.size()>1){Set<String> signatures=new HashSet<>();for(var candidate:namedMethods(cn,actualMethod))if(descriptors.contains(candidate.desc))signatures.add(simpleFieldGetter(candidate));if(signatures.size()!=1||signatures.contains(""))return;descriptors=List.of(descriptors.get(0));}
            if(descriptors.isEmpty()&&actualMethod.equals("getTextureLocation")){useRenderer=false;actualMethod=method;cn=node(subject.type);seen.clear();while(cn!=null&&seen.add(cn.name)){for(var m:namedMethods(cn,actualMethod))if((m.access&Opcodes.ACC_BRIDGE)==0)descriptors.add(m.desc);if(!descriptors.isEmpty())break;cn=node(cn.superName);}}
            if(descriptors.isEmpty()){
                String kind=method.equals("getModelResource")?"geo":method.equals("getTextureResource")?"texture":"animation";
                for(String alias:ResourceMethods.names(kind)){
                    cn=node(subject.type);seen.clear();
                    while(cn!=null&&seen.add(cn.name)){for(var m:namedMethods(cn,alias))if((m.access&Opcodes.ACC_BRIDGE)==0)descriptors.add(m.desc);if(!descriptors.isEmpty())break;cn=node(cn.superName);}
                    if(!descriptors.isEmpty()){useRenderer=false;actualMethod=alias;break;}
                }
            }
            if(descriptors.size()!=1)continue;
            Object[] av=new Object[Type.getArgumentTypes(descriptors.get(0)).length];for(int a=0;a<av.length;a++)av[a]=new Symbol("arg"+a);
            Object value=eval(useRenderer?self.type:subject.type,actualMethod,descriptors.get(0),useRenderer?self:subject,av,0);if(value instanceof Instance i)value=i.fields.get("@resource");
            if(pendingChoice!=null&&!choices.containsKey(pendingChoice))return;
            if(value==UNKNOWN&&method.equals("getTextureResource")&&refs.containsKey("getModelResource")&&cn!=null){
                MethodNode getter=declaredMethod(cn,actualMethod,descriptors.get(0));
                if(getter!=null){
                    var returns=returnedTextures.computeIfAbsent(getter,m->BytecodeArguments.returnedLocations(m));
                    if(!returns.isEmpty()){
                        String selector="return:"+cn.name+"."+actualMethod+getter.desc;
                        if(!choices.containsKey(selector)){pendingChoice=selector;pendingValues=new ArrayList<>(returns);return;}
                        value=new Resource((String)choices.get(selector));
                    }
                }
            }
            if(method.equals("getModelResource")&&value instanceof ResourceTemplate resource){Template template=resource.template();List<Object> values=new ArrayList<>();Set<String> literals=selectorLiterals(template.symbol());for(var files:catalog.entries.values())for(var entry:files.values())if(entry.kind().equals("geo")){String address=ResourceEvidence.address(entry);String prefix=resource.namespace()+":"+template.prefix();if(address!=null&&address.startsWith(prefix)&&address.endsWith(template.suffix())&&address.length()>prefix.length()+template.suffix().length()){String candidate=address.substring(prefix.length(),address.length()-template.suffix().length());if(composed(candidate,literals))values.add(candidate);}}if(!values.isEmpty()&&values.size()<=64){pendingChoice=template.symbol().path();pendingValues=values.stream().distinct().toList();}return;}
            if(value instanceof Resource r){if(useRenderer)rendererOverride=true;refs.put(method,r.value());Object base=subject.fields.get("software/bernie/geckolib/model/DefaultedGeoModel.texturePath");if(base instanceof Instance i)base=i.fields.get("@resource");if(method.equals("getTextureResource")&&(choices.isEmpty()||base instanceof Resource texture&&texture.value().equals(r.value())))preferredTexture=r.value();}
        }
        Set<String> models=new LinkedHashSet<>();
        if(refs.containsKey("getModelResource"))models.add(refs.get("getModelResource"));
        if(!models.isEmpty()&&!refs.containsKey("getTextureResource")&&emitted.add("unknown:"+origin.path()+refs+choices))catalog.event(origin,"static_texture_unknown",refs+" choices="+choices+" budget="+budget);

        for(String modelAddress:models){
        var model=catalog.uniqueResource(modelAddress,"geo");if(model.isEmpty())continue;if(!refs.containsKey("getTextureResource"))catalog.event(model.get(),"static_texture_unknown",origin.path()+" model getter resolved "+modelAddress+"; texture getter unresolved; selectors="+choices);if(rendererOverride)rendererOverrides.add(ResourceCatalog.key(model.get()));
        for(var unsupported:self.fields.entrySet())if(unsupported.getKey().startsWith("@unsupported:")){
            var declarations=catalog.layerPlans.computeIfAbsent(ResourceCatalog.key(model.get()),k->new com.google.gson.JsonArray());var declaration=new com.google.gson.JsonObject();declaration.addProperty("status","UNSUPPORTED_RENDER_CALLBACK");declaration.addProperty("callSite",String.valueOf(unsupported.getValue()));if(!declarations.contains(declaration))declarations.add(declaration);
        }
        if(self.fields.get("@layers") instanceof List<?> layers)for(Object layer:layers)if(layer instanceof Instance instance){var declarations=catalog.layerPlans.computeIfAbsent(ResourceCatalog.key(model.get()),k->new com.google.gson.JsonArray());var declaration=new com.google.gson.JsonObject();declaration.addProperty("status","UNSUPPORTED_RENDER_LAYER");declaration.addProperty("callSite",origin.path()+" -> addRenderLayer("+instance.type+")");if(!declarations.contains(declaration))declarations.add(declaration);}
        for(String kind:List.of("texture","animation")){
            String address=refs.get(kind.equals("texture")?"getTextureResource":"getAnimationResource");if(address==null)continue;
            var dep=catalog.uniqueResource(address,kind);if(dep.isEmpty()&&kind.equals("texture")&&isModel(self.type)&&rendererOverrides.contains(ResourceCatalog.key(model.get())))continue;if(dep.isEmpty()){boolean frame=kind.equals("texture")&&choices.keySet().stream().anyMatch(s->s.startsWith("display-frame:"));var missing=new GenericResourceResolver.Requirement(ResourceCatalog.key(model.get()),kind,address,"UNRESOLVED",List.of(),"constant_bytecode:"+ResourceCatalog.key(origin),frame?"DISPLAY_FRAME":"SINGLE");if(!catalog.requirements.contains(missing))catalog.requirements.add(missing);continue;}
            String identity=ResourceCatalog.key(model.get())+"|"+ResourceCatalog.key(dep.get());
            if(emitted.add(identity))catalog.bundles.get(ResourceCatalog.key(model.get())).add(new ResourceCatalog.Edge(ResourceCatalog.key(model.get()),ResourceCatalog.key(dep.get()),"constant_bytecode:"+ResourceCatalog.key(origin)));
            if(kind.equals("texture")){String key=ResourceCatalog.key(model.get()),texture=ResourceCatalog.key(dep.get());if(address.equals(preferredTexture)){String old=catalog.defaultTextures.putIfAbsent(key,texture);if(old!=null&&!old.equals(texture)){catalog.defaultTextureConflicts.add(key);catalog.defaultTextures.remove(key);}}boolean frame=choices.keySet().stream().anyMatch(s->s.startsWith("display-frame:"));var req=new GenericResourceResolver.Requirement(key,kind,address,"FOUND",List.of(texture),"constant_bytecode:"+ResourceCatalog.key(origin)+(choices.isEmpty()?"":" selectors="+choices),frame?"DISPLAY_FRAME":"SKIN_VARIANT");if(!catalog.requirements.contains(req))catalog.requirements.add(req);}
        }
    }
    }
    private Object syncedDefault(String owner,String name,String desc){String key=owner+"."+name+desc;Object cached=syncedDefaults.get(key);if(cached!=null){syncedReuses++;return cached;}Object value=readSyncedDefault(owner,name,desc);syncedDefaults.put(key,value);return value;}
    private Object readSyncedDefault(String owner,String name,String desc){
        ClassNode cn=node(owner);if(cn==null||!desc.equals("()Ljava/lang/String;"))return UNKNOWN;
        MethodNode getter=declaredMethod(cn,name,desc);if(getter==null)return UNKNOWN;
        List<FieldInsnNode> accessors=new ArrayList<>();boolean dataRead=false;
        for(var in:getter.instructions){if(in instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.GETSTATIC&&f.desc.contains("EntityDataAccessor"))accessors.add(f);if(in instanceof MethodInsnNode call&&call.owner.endsWith("/SynchedEntityData")&&Set.of("get","m_135370_").contains(call.name))dataRead=true;}
        if(!dataRead||accessors.size()!=1)return UNKNOWN;FieldInsnNode accessor=accessors.get(0);Set<String> defaults=new LinkedHashSet<>();
        for(var method:cn.methods)for(var entry:BytecodeArguments.calls(cn,method).entrySet()){var call=entry.getKey();var args=entry.getValue();if(!call.owner.endsWith("/SynchedEntityData")||!Set.of("define","m_135372_").contains(call.name)||args.size()!=2)continue;if(args.get(0) instanceof FieldInsnNode field&&field.owner.equals(accessor.owner)&&field.name.equals(accessor.name)){String literal=BytecodeArguments.string(args.get(1));if(literal!=null)defaults.add(literal);}}
        if(defaults.size()!=1)return UNKNOWN;var source=classes.get(owner);if(source!=null&&emitted.add("synced:"+owner+"."+name))catalog.event(source,"synced_default_resolved",owner+"."+name+desc+" default="+defaults.iterator().next()+"; arbitrary NBT/server overrides remain dynamic");return defaults.iterator().next();
    }
    /** Finite, explicitly registered objects of the receiver type, interpreted without class loading. */
    private List<Object> registeredInstances(String type,int depth){
        if(finiteInstances.containsKey(type))return finiteInstances.get(type);
        finiteInstances.put(type,List.of());List<Object> result=new ArrayList<>();String descriptor="L"+type+";";
        for(String owner:staticOwners(descriptor)){ClassNode cn=node(owner);
            if(initialized.add(owner))initialize(owner);
            for(var field:staticFields(cn,descriptor)){Object value=staticFields.get(owner+"."+field.name);if(value instanceof Instance i&&i.type.equals(type)&&!result.contains(i))result.add(i);}
            if(result.size()>64)return List.of();
        }
        List<Object> values=List.copyOf(result);finiteInstances.put(type,values);return values;
    }
    private List<String> staticOwners(String descriptor){
        if(staticOwners==null){staticOwners=new HashMap<>();for(String owner:classes.keySet()){ClassNode cn=node(owner);if(cn==null)continue;Set<String> descriptors=new HashSet<>();for(FieldNode field:cn.fields)if((field.access&Opcodes.ACC_STATIC)!=0&&descriptors.add(field.desc))staticOwners.computeIfAbsent(field.desc,k->new ArrayList<>()).add(owner);}}else registeredIndexReuses++;
        return staticOwners.getOrDefault(descriptor,List.of());
    }
    private Set<String> selectorLiterals(Symbol symbol){Set<String> cached=selectorValues.get(symbol.path());if(cached!=null){selectorReuses++;return cached;}Set<String> values=Set.copyOf(readSelectorLiterals(symbol));selectorValues.put(symbol.path(),values);return values;}
    private Set<String> readSelectorLiterals(Symbol symbol){
        Set<String> result=new HashSet<>(),visited=new HashSet<>();Deque<String[]> queue=new ArrayDeque<>();
        for(String owner:classes.keySet())if(symbol.path().contains(owner+".")){ClassNode cn=node(owner);if(cn!=null)for(var method:cn.methods)if(symbol.path().contains(owner+"."+method.name+method.desc))queue.add(new String[]{owner,method.name,method.desc});}
        int steps=0;while(!queue.isEmpty()&&steps++<128){String[] target=queue.removeFirst();if(!visited.add(String.join("|",target)))continue;ClassNode cn=node(target[0]);if(cn==null)continue;boolean enumeration=(cn.access&Opcodes.ACC_ENUM)!=0;for(var method:cn.methods)if(method.name.equals(target[1])&&method.desc.equals(target[2])||enumeration&&(method.name.equals("<init>")||method.name.equals("<clinit>")))for(var in:method.instructions){if(in instanceof LdcInsnNode ldc&&ldc.cst instanceof String s&&s.matches("[a-z0-9_/.-]+")&&!s.isEmpty())result.add(s);if(in instanceof InvokeDynamicInsnNode concat&&concat.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")&&concat.bsmArgs.length>0&&concat.bsmArgs[0] instanceof String recipe)for(String s:recipe.split("[\u0001\u0002]"))if(!s.isEmpty()&&s.matches("[a-z0-9_/.-]+"))result.add(s);if(in instanceof MethodInsnNode call&&classes.containsKey(call.owner))queue.add(new String[]{call.owner,call.name,call.desc});}}return result;
    }
    private static boolean composed(String candidate,Set<String> literals){boolean[] reachable=new boolean[candidate.length()+1];reachable[0]=true;for(int i=0;i<candidate.length();i++)if(reachable[i])for(String literal:literals)if(candidate.startsWith(literal,i))reachable[i+literal.length()]=true;return reachable[candidate.length()];}
    private String simpleFieldGetter(MethodNode method){String cached=fieldGetters.get(method);if(cached!=null)return cached;AbstractInsnNode[] code=new AbstractInsnNode[3];int count=0;for(var in:method.instructions)if(in.getOpcode()>=0){if(count==3){count++;break;}code[count++]=in;}String value="";if(count==3&&code[0] instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ALOAD&&v.var==0&&code[1] instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETFIELD&&code[2].getOpcode()==Opcodes.ARETURN)value=field.owner+"."+field.name+field.desc;fieldGetters.put(method,value);return value;}
    private static Object resourceValue(Object value){return value instanceof Instance i?i.fields.getOrDefault("@resource",UNKNOWN):value;}
    private static Object pop(List<Object> s){return s.remove(s.size()-1);}
    private static Object[] arguments(List<Object>s,int n){Object[] a=new Object[n];for(int i=n-1;i>=0;i--)a[i]=pop(s);return a;}
}
