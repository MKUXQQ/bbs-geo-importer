package local.bbsgeoimporter;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
/** Resolves static Cobblemon Pokemon aspect overrides without loading mod classes. */
final class CobblemonResolvers {
    record Rule(ResourceCatalog.Entry source,int order,Set<String> aspects,JsonObject value) {}
    private final Map<String,List<ResourceCatalog.Entry>> lookup=new HashMap<>();
    static void analyze(ResourceCatalog c) {new CobblemonResolvers().run(c);}
    private void run(ResourceCatalog c) {
        for(var files:c.entries.values())for(var e:files.values()) {
            String[] parts=e.path().split("/",3);if(parts.length!=3||!parts[0].equals("assets"))continue;
            String id=null;
            if(e.kind().equals("texture"))id="texture:"+parts[1]+":"+parts[2];
            if(e.kind().equals("geo")&&parts[2].startsWith("bedrock/")){String filename=e.path().substring(e.path().lastIndexOf('/')+1);id="geo:"+parts[1]+":"+filename.substring(0,filename.length()-5);}
            if(id!=null)lookup.computeIfAbsent(id,k->new ArrayList<>()).add(e);
        }
        Map<String,List<Rule>> groups=new TreeMap<>();
        for(var files:c.entries.values()) for(var e:files.values()) {
            if(!e.path().matches("assets/[^/]+/bedrock/.+/(?:resolvers|variations)/.+\\.json"))continue;
            try {
                var o=JsonParser.parseString(AnalysisInputs.text(e.file())).getAsJsonObject();
                String species=o.has("species")?o.get("species").getAsString():o.has("name")?o.get("name").getAsString():e.path(); int order=o.has("order")?o.get("order").getAsInt():0;
                for(var v:o.getAsJsonArray("variations")) {
                    var value=v.getAsJsonObject(); Set<String> aspects=new TreeSet<>();
                    if(value.has("aspects"))for(var a:value.getAsJsonArray("aspects"))aspects.add(a.getAsString());
                    groups.computeIfAbsent(e.owner()+":"+species,k->new ArrayList<>()).add(new Rule(e,order,Set.copyOf(aspects),value));
                }
            }catch(Exception ex){c.event(e,"cobblemon_resolver_failed",ex.toString());}
        }
        for(var rules:groups.values()) {
            rules.sort(Comparator.comparingInt(Rule::order).thenComparingInt(r->r.aspects().size()).thenComparing(r->r.source().path()));
            List<Rule> modelRules=rules.stream().filter(r->r.value().has("model")&&r.value().get("model").isJsonPrimitive()&&r.value().getAsJsonPrimitive("model").isString()).toList();
            for(var modelRule:modelRules)resolveModel(c,rules,modelRules,modelRule);
        }
    }
    private void resolveModel(ResourceCatalog c,List<Rule> rules,List<Rule> modelRules,Rule modelRule) {
        String model=modelRule.value().get("model").getAsString();var geo=find(c,modelRule.source(),model,true);
        if(geo==null)return;
        List<Rule> textures=rules.stream().filter(r->r.value().has("texture")&&r.value().get("texture").isJsonPrimitive()&&r.value().getAsJsonPrimitive("texture").isString()).toList();
        Rule base=mostSpecificApplicable(textures,modelRule.aspects());
        if(base==null){c.event(modelRule.source(),"cobblemon_default_texture_missing","No literal texture rule applies to model aspects "+new TreeSet<>(modelRule.aspects()));return;}
        String poser=null;Rule poserRule=null;
        for(var r:rules)if(r.aspects().stream().allMatch(modelRule.aspects()::contains)&&r.value().has("poser")&&r.value().get("poser").isJsonPrimitive()&&r.value().getAsJsonPrimitive("poser").isString()){poser=r.value().get("poser").getAsString();poserRule=r;}
        if(poserRule!=null)linkPoserAnimations(c,geo,poserRule.source(),poser);
        boolean inheritedGeometry=modelRules.stream().anyMatch(r->r!=modelRule&&r.value().get("model").getAsString().equals(model)&&modelRule.aspects().containsAll(r.aspects())&&r.aspects().size()<modelRule.aspects().size());
        if(!inheritedGeometry)publishTexture(c,geo,base,"DEFAULT",modelRule);
        if(inheritedGeometry||modelRules.stream().filter(r->r.value().get("model").getAsString().equals(model)).count()>1||!modelRule.source().path().contains("/pokemon/"))publishTexture(c,geo,base,"SKIN_VARIANT",modelRule);
        Set<String> emitted=new HashSet<>();emitted.add(base.value().get("texture").getAsString());
        for(var skin:textures){
            if(skin==base||skin.aspects().isEmpty()||conflictsWithOtherModel(skin,modelRule,modelRules)||!emitted.add(skin.value().get("texture").getAsString()))continue;
            publishTexture(c,geo,skin,"SKIN_VARIANT",modelRule);
        }
        for(var r:rules)if(!conflictsWithOtherModel(r,modelRule,modelRules)&&r.value().has("layers")&&r.value().get("layers").isJsonArray()&&!r.value().getAsJsonArray("layers").isEmpty()){
            String key=ResourceCatalog.key(geo),evidence="resolver_layers:"+ResourceCatalog.key(r.source());
            JsonObject declaration=new JsonObject();declaration.addProperty("source",ResourceCatalog.key(r.source()));declaration.add("aspects",r.value().has("aspects")?r.value().get("aspects").deepCopy():new JsonArray());declaration.add("layers",r.value().get("layers").deepCopy());declaration.addProperty("status","UNSUPPORTED_BBS_EMISSIVE_ANIMATED_OVERLAY");
            JsonArray declarations=c.layerPlans.computeIfAbsent(key,k->new JsonArray());if(!declarations.contains(declaration))declarations.add(declaration);
            collectLayers(c,geo,r.source(),r.value().get("layers"),evidence);
            var requirement=new GenericResourceResolver.Requirement(key,"texture",ResourceCatalog.key(r.source())+"#/variations/layers","UNSUPPORTED",List.of(),evidence,"SIMULTANEOUS_OVERLAY");if(!c.requirements.contains(requirement))c.requirements.add(requirement);
        }
    }
    private void collectLayers(ResourceCatalog c,ResourceCatalog.Entry geo,ResourceCatalog.Entry definition,JsonElement value,String evidence){
        if(value.isJsonArray()){int i=0;for(var v:value.getAsJsonArray())collectLayers(c,geo,definition,v,evidence+"/"+i++);}
        else if(value.isJsonObject()){for(var entry:value.getAsJsonObject().entrySet())if(entry.getKey().equals("texture")||entry.getKey().equals("frames"))collectLayers(c,geo,definition,entry.getValue(),evidence+"/"+entry.getKey());}
        else if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()){var png=find(c,definition,value.getAsString(),false);if(png!=null)c.dependency(geo,"texture",value.getAsString(),"FOUND",List.of(png),evidence,"SIMULTANEOUS_OVERLAY");}
    }
    private static Rule mostSpecificApplicable(List<Rule> textures,Set<String> modelAspects){
        Rule chosen=null;
        for(var r:textures)if(modelAspects.containsAll(r.aspects())){
            if(chosen==null||r.order()>chosen.order()||r.order()==chosen.order()&&r.aspects().size()>chosen.aspects().size())chosen=r;
        }
        return chosen;
    }
    private static boolean conflictsWithOtherModel(Rule texture,Rule selected,List<Rule> models){
        for(String aspect:texture.aspects())if(!selected.aspects().contains(aspect))for(var other:models)if(other!=selected&&other.aspects().contains(aspect))return true;
        return false;
    }
    private void publishTexture(ResourceCatalog c,ResourceCatalog.Entry geo,Rule texture,String role,Rule model){
        String ref=texture.value().get("texture").getAsString();var png=find(c,texture.source(),ref,false);
        if(png==null)return;
        String evidence="cobblemon_resolver:"+ResourceCatalog.key(model.source())+";texture="+ResourceCatalog.key(texture.source())+";modelAspects="+new TreeSet<>(model.aspects())+";textureAspects="+new TreeSet<>(texture.aspects());
        String modelKey=ResourceCatalog.key(geo),resourceKey=ResourceCatalog.key(png);
        var edges=c.bundles.get(modelKey);var edge=new ResourceCatalog.Edge(modelKey,resourceKey,evidence);if(!edges.contains(edge))edges.add(edge);
        if(role.equals("SKIN_VARIANT")){var requirement=new GenericResourceResolver.Requirement(modelKey,"texture",ref,"FOUND",List.of(resourceKey),evidence,role);if(!c.requirements.contains(requirement))c.requirements.add(requirement);return;}
        if(c.defaultTextureConflicts.contains(modelKey))return;
        String previous=c.defaultTextures.putIfAbsent(modelKey,resourceKey);
        if(previous!=null&&!previous.equals(resourceKey)){c.defaultTextures.remove(modelKey);c.defaultTextureConflicts.add(modelKey);c.event(geo,"cobblemon_default_conflict","Multiple most-specific default texture rules apply");}
    }
    private Map<String,ResourceCatalog.Entry> javaOwner;
    private final Map<String,org.objectweb.asm.tree.ClassNode> javaNodes=new HashMap<>();
    private final Set<String> missingNodes=new HashSet<>();
    private final Map<String,Map<String,Set<String>>> javaRegistrations=new HashMap<>();
    private final Map<String,Set<String>> javaGroupCache=new HashMap<>();
    private org.objectweb.asm.tree.ClassNode javaNode(Map<String,ResourceCatalog.Entry> files,String name){
        if(javaNodes.containsKey(name))return javaNodes.get(name);if(missingNodes.contains(name))return null;
        var file=files.get(name+".class");if(file==null){missingNodes.add(name);return null;}
        try{var node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(AnalysisInputs.bytes(file.file())).accept(node,org.objectweb.asm.ClassReader.SKIP_DEBUG|org.objectweb.asm.ClassReader.SKIP_FRAMES);javaNodes.put(name,node);return node;}catch(Exception ex){missingNodes.add(name);return null;}
    }
    private Set<String> javaGroups(Map<String,ResourceCatalog.Entry> files,String id){
        if(javaOwner!=files){javaNodes.clear();missingNodes.clear();javaRegistrations.clear();javaGroupCache.clear();javaOwner=files;}
        String base="com/cobblemon/mod/common/client/render/models/blockbench/";
        var repository=javaNode(files,base+"repository/PokemonModelRepository");
        if(repository==null)return Set.of();
        Map<String,Set<String>> registrations=javaRegistrations.get(repository.name);
        if(registrations==null){registrations=new HashMap<>();
        for(var method:repository.methods)if(method.name.equals("registerInBuiltPosers"))for(var entry:BytecodeArguments.calls(repository,method).entrySet()){
            var call=entry.getKey();var args=entry.getValue();
            if(call.name.equals("inbuilt")&&call.desc.equals("(Ljava/lang/String;Lkotlin/jvm/functions/Function1;)V")&&args.size()==2&&BytecodeArguments.string(args.get(0))!=null&&args.get(1) instanceof org.objectweb.asm.tree.FieldInsnNode field&&field.getOpcode()==org.objectweb.asm.Opcodes.GETSTATIC)registrations.computeIfAbsent(BytecodeArguments.string(args.get(0)),k->new LinkedHashSet<>()).add(field.owner);
        }
        javaRegistrations.put(repository.name,registrations);}
        Set<String> factories=registrations.getOrDefault(id,Set.of());
        if(factories.size()!=1)return Set.of();
        var factory=javaNode(files,factories.iterator().next());if(factory==null)return Set.of();Set<String> models=new LinkedHashSet<>();
        for(var method:factory.methods)if(method.name.equals("invoke"))for(var in:method.instructions)if(in instanceof org.objectweb.asm.tree.TypeInsnNode type&&type.getOpcode()==org.objectweb.asm.Opcodes.NEW&&type.desc.startsWith(base+"pokemon/"))models.add(type.desc);
        if(models.size()!=1)return Set.of();
        String model=models.iterator().next();String cacheKey=model;
        if(javaGroupCache.containsKey(cacheKey))return javaGroupCache.get(cacheKey);
        Set<String> groups=new LinkedHashSet<>();Set<String> visited=new HashSet<>();
        while(model!=null&&model.startsWith(base)&&visited.add(model)&&visited.size()<16){
            var node=javaNode(files,model);if(node==null)break;
            collectJavaGroups(node,groups);
            for(String path:files.keySet())if(path.startsWith(model+"$")&&path.endsWith(".class")){var nested=javaNode(files,path.substring(0,path.length()-6));if(nested!=null)collectJavaGroups(nested,groups);}
            model=node.superName;
        }
        javaGroupCache.put(cacheKey,Set.copyOf(groups));return groups;
    }
    private static void collectJavaGroups(org.objectweb.asm.tree.ClassNode node,Set<String> groups){
        for(var method:node.methods)for(var entry:BytecodeArguments.calls(node,method).entrySet()){
            var call=entry.getKey();
            if(!call.owner.startsWith("com/cobblemon/mod/common/client/render/models/blockbench/")||!call.name.matches("bedrock(?:Stateful)?(?:[$]default)?"))continue;
            var types=org.objectweb.asm.Type.getArgumentTypes(call.desc);var args=entry.getValue();
            int index=call.getOpcode()==org.objectweb.asm.Opcodes.INVOKESTATIC?1:0;
            if(types.length<=index||!types[index].getDescriptor().equals("Ljava/lang/String;"))continue;
            String group=BytecodeArguments.string(args.get(index));if(group!=null&&group.matches("[a-z0-9_/.-]+"))groups.add(group);
        }
    }
    private final Set<String> processedPosers=new HashSet<>();
    private void linkPoserAnimations(ResourceCatalog c,ResourceCatalog.Entry geo,ResourceCatalog.Entry source,String ref) {
        if(!processedPosers.add(ResourceCatalog.key(geo)+":"+ref))return;
        int colon=ref.indexOf(':');if(colon<1)return;
        String prefix="assets/"+ref.substring(0,colon)+"/bedrock/pokemon/";
        String path=prefix+"posers/"+ref.substring(colon+1)+".json";
        var files=c.entries.getOrDefault(source.owner(),Map.of());
        var poser=files.get(path);
        try {
            Set<String> groups=new LinkedHashSet<>();
            if(poser!=null)collectAnimationGroups(JsonParser.parseString(AnalysisInputs.text(poser.file())),groups);
            else groups.addAll(javaGroups(files,ref.substring(colon+1)));
            if(groups.isEmpty()){c.event(geo,"cobblemon_poser_unresolved",ref+" has no proven literal Bedrock group");return;}
            var evidenceSource=poser==null?source:poser;
            for(String group:groups) {
                List<ResourceCatalog.Entry> found=new ArrayList<>();
                for(var e:files.values())if(e.kind().equals("animation")&&e.path().startsWith(prefix+"animations/")) {
                    String relative=e.path().substring((prefix+"animations/").length());
                    if(relative.equals(group+".animation.json")||(!group.contains("/")&&e.path().substring(e.path().lastIndexOf('/')+1).equals(group+".animation.json")))found.add(e);
                }
                if(found.size()==1)c.bundles.get(ResourceCatalog.key(geo)).add(new ResourceCatalog.Edge(ResourceCatalog.key(geo),ResourceCatalog.key(found.get(0)),(poser==null?"cobblemon_java_poser:":"cobblemon_poser:")+ResourceCatalog.key(evidenceSource)+";poser="+ref+";bedrock="+group));
                else c.event(evidenceSource,"cobblemon_animation_unresolved",group+" candidates="+found.size());
            }
        }catch(Exception ex){c.event(poser==null?source:poser,"cobblemon_poser_failed",ex.toString());}
    }
    private static final java.util.regex.Pattern BEDROCK=java.util.regex.Pattern.compile("(?:q[.])?bedrock(?:_primary|_stateful|_quirk)?[(]\\s*(?:'([a-z0-9_/.-]+)'|([a-z0-9_/.-]+))\\s*,");
    private static void collectAnimationGroups(JsonElement value,Set<String> groups) {
        if(value.isJsonObject())for(var e:value.getAsJsonObject().entrySet())collectAnimationGroups(e.getValue(),groups);
        else if(value.isJsonArray())for(var e:value.getAsJsonArray())collectAnimationGroups(e,groups);
        else if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()) {
            var m=BEDROCK.matcher(value.getAsString());while(m.find())groups.add(m.group(1)!=null?m.group(1):m.group(2));
        }
    }
    ResourceCatalog.Entry find(ResourceCatalog c,ResourceCatalog.Entry source,String ref,boolean model) {
        int colon=ref.indexOf(':');if(colon<1){c.event(source,"cobblemon_reference_unresolved",ref);return null;}
        List<ResourceCatalog.Entry> found=lookup.getOrDefault((model?"geo:":"texture:")+ref,List.of());
        if(found.size()==1)return found.get(0);
        c.event(source,found.isEmpty()?"cobblemon_reference_unresolved":"cobblemon_reference_ambiguous",ref);return null;
    }
}
