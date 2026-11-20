package local.bbsgeoimporter;
import com.google.gson.*;
import java.util.*;

/** Optional BBS bridge. Only keys owned by the importer are augmented. No BBS classes are linked here. */
public final class SharedModelAssets {
    record Binding(List<String> animations,String texture,Map<String,String> materials,List<MaterialPlan.Part> parts) {}
    private static final int BINDING_SCHEMA=2;
    static boolean planReusable(JsonObject record){JsonObject plan=record.getAsJsonObject("bindingPlan");return plan!=null&&plan.has("schema")&&plan.get("schema").getAsInt()==BINDING_SCHEMA&&plan.has("geometrySourceHash")&&record.has("sourceHash")&&plan.get("geometrySourceHash").equals(record.get("sourceHash"));}
    private static volatile Map<String,Binding> bindings=Map.of();
    private static volatile Map<String,String> revisions=Map.of();
    private record PreviewState(Binding binding,String revision,Object token) {}
    private static volatile Map<String,PreviewState> previewStates=Map.of();
    static Map<String,Binding> bindingsForTest(){return bindings;}
    static boolean hasPreparedBinding(String key){return bindings.containsKey(key);}
    static Object previewToken(String key){PreviewState state=previewStates.get(key);return state==null?null:state.token();}
    private static void refreshPreviewTokens(){Map<String,PreviewState> next=new HashMap<>();bindings.forEach((key,binding)->{String revision=revisions.getOrDefault(key,"");PreviewState old=previewStates.get(key);next.put(key,old!=null&&old.revision().equals(revision)&&old.binding().equals(binding)?old:new PreviewState(binding,revision,new Object()));});previewStates=Map.copyOf(next);}
    public static volatile String status="not_loaded";
    private static final java.util.concurrent.atomic.AtomicLong lookups=new java.util.concurrent.atomic.AtomicLong(),matched=new java.util.concurrent.atomic.AtomicLong(),texturesApplied=new java.util.concurrent.atomic.AtomicLong(),animationsAdded=new java.util.concurrent.atomic.AtomicLong();
    public static Map<String,Object> diagnostics(){return Map.of("state",status,"bindings",bindings.size(),"lookups",lookups.get(),"matched",matched.get(),"texturesApplied",texturesApplied.get(),"animationsAdded",animationsAdded.get());}
    static String verifyOutput(java.nio.file.Path models,String directory,JsonObject record){
        Verification pass=verification.get();if(pass!=null&&pass.models.equals(models))return pass.check(directory,record);return verifyOutputUncached(models,directory,record);
    }
    private static final ThreadLocal<Verification> verification=new ThreadLocal<>();
    static void beginVerification(java.nio.file.Path models){verification.set(new Verification(models));}
    static void endVerification(){verification.remove();}
    private static String verifyOutputUncached(java.nio.file.Path models,String directory,JsonObject record){
        if(record==null||!record.has("writtenHash"))return "NOT_PUBLISHED";
        String previous=record.get("status").getAsString();if(previous.equals("user_conflict")||previous.equals("failed"))return previous;
        try{var base=models.toAbsolutePath().normalize();var path=base.resolve(directory).resolve(record.get("target").getAsString()).normalize();if(!path.startsWith(base))return "INVALID";for(var p=path;p!=null;p=p.getParent())if(java.nio.file.Files.isSymbolicLink(p))return "INVALID";
            return java.nio.file.Files.isRegularFile(path)&&GeoImporter.hash(java.nio.file.Files.readAllBytes(path)).equals(record.get("writtenHash").getAsString())?previous:"USER_CONFLICT";
        }catch(Exception ex){return "INVALID";}
    }
    static JsonObject resourceOwner(JsonObject state,String resource){int colon=resource.indexOf(':');return colon<0?null:state.getAsJsonObject("mods").getAsJsonObject(resource.substring(0,colon));}
    static JsonObject resourceRecord(JsonObject state,String resource){JsonObject owner=resourceOwner(state,resource);return owner==null?null:owner.getAsJsonObject("files").getAsJsonObject("@resource/"+resource);}
    /** A publication pass verifies each owned output once. Never reused across passes or edits. */
    private static final class Verification {
        final java.nio.file.Path models;final Map<String,String> results=new HashMap<>();
        Verification(java.nio.file.Path models){this.models=models;}
        String check(String directory,JsonObject record){if(record==null)return "NOT_PUBLISHED";String key=directory+"/"+record.get("target")+"|"+record.get("writtenHash")+"|"+record.get("status");return results.computeIfAbsent(key,k->{long t=Performance.start();String result=verifyOutputUncached(models,directory,record);Performance.end("output.verify",t);return result;});}
    }
    static void update(ResourceCatalog catalog,JsonObject state,java.nio.file.Path models){update(catalog,state,models,null);}
    static void update(ResourceCatalog catalog,JsonObject state,java.nio.file.Path models,Set<String> only){
        long timing=Performance.start();
        Map<String,Binding> next=new HashMap<>();Map<String,String> nextRevisions=new HashMap<>();
        Verification verified=verification.get()==null?new Verification(models):verification.get();Map<String,MaterialPlan> plans=new HashMap<>();
        for(var modItem:state.getAsJsonObject("mods").entrySet()){
            var mod=modItem.getValue().getAsJsonObject();var files=mod.getAsJsonObject("files");String dir=mod.get("directory").getAsString();
            for(var item:files.entrySet())if((!item.getKey().startsWith("@")&&item.getValue().getAsJsonObject().has("modelSource"))||item.getKey().startsWith("@skin/")){
                var record=item.getValue().getAsJsonObject();if(record.has("inactiveReason"))continue;String candidate=record.has("modelSource")?record.get("modelSource").getAsString():modItem.getKey()+":"+item.getKey();if(only!=null&&!only.contains(candidate))continue;if(!List.of("imported","updated","unchanged").contains(verified.check(dir,record)))continue;
                String model=record.has("modelSource")?record.get("modelSource").getAsString():modItem.getKey()+":"+item.getKey(),target=record.get("target").getAsString();int slash=target.lastIndexOf('/');if(slash<0)continue;
                if(only!=null&&!only.contains(model))continue;
                if(!ModelManagement.enabled(modItem.getKey())){String key=dir+"/"+target.substring(0,slash);Binding existing=bindings.get(key);if(existing!=null){next.put(key,existing);nextRevisions.put(key,revisions.getOrDefault(key,""));}continue;}
                List<String> anims=new ArrayList<>(),textures=new ArrayList<>();Map<String,String> textureLinks=new HashMap<>();Set<String> overlays=new HashSet<>();for(var req:catalog.requirementsFor(model))if(req.role().equals("SIMULTANEOUS_OVERLAY"))overlays.addAll(req.candidates());
                for(var edge:catalog.bundles.getOrDefault(model,List.of())){
                    var provider=resourceOwner(state,edge.resource());var r=resourceRecord(state,edge.resource());if(provider==null||r==null||!List.of("imported","updated","unchanged").contains(verified.check(provider.get("directory").getAsString(),r)))continue;
                    String link="models/"+provider.get("directory").getAsString()+"/"+r.get("target").getAsString();
                    if(r.get("type").getAsString().equals("animation"))anims.add(link);
                    if(r.get("type").getAsString().equals("texture")){if(!overlays.contains(edge.resource()))textures.add(link);textureLinks.put(edge.resource(),link);}
                }
                var declared=catalog.requirementsFor(model).stream().filter(r->r.kind().equals("texture")&&!r.role().equals("SKIN_VARIANT")&&!r.role().equals("NAMED_SLOT")&&!r.role().equals("SIMULTANEOUS_OVERLAY")&&r.evidence().startsWith("json:"+model+"#")).toList();
                Set<String> direct=new HashSet<>();declared.stream().filter(r->r.status().equals("FOUND")).forEach(r->direct.addAll(r.candidates()));
                boolean explicit=!declared.isEmpty(),skin=record.has("textureSource");
                String preferred=skin?record.get("textureSource").getAsString():explicit?declared.stream().allMatch(r->r.status().equals("FOUND"))&&direct.size()==1?direct.iterator().next():null:catalog.defaultTextureConflicts.contains(model)?null:catalog.defaultTextures.get(model);
                String texture=preferred!=null?textureLinks.get(preferred):explicit||skin?null:new HashSet<>(textures).size()==1?textures.get(0):null;
                MaterialPlan plan=plans.computeIfAbsent(model,k->MaterialPlan.read(catalog,k));Map<String,String> materials=new TreeMap<>();
                plan.confirmedSlots().forEach((slot,resource)->{String link=textureLinks.get(resource);if(link!=null)materials.put(slot,link);});
                Binding binding=new Binding(anims.stream().distinct().sorted().toList(),texture,Map.copyOf(materials),plan.hasSelectors()?plan.exportedParts():List.of());
                record.add("bindingPlan",encode(binding,model,record,catalog,state,verified));
                String bbsKey=dir+"/"+target.substring(0,slash);next.put(bbsKey,binding);nextRevisions.put(bbsKey,revision(record));
            }
        }
        if(only==null)bindings=Map.copyOf(next);else{Map<String,Binding> merged=new HashMap<>(bindings);for(var modItem:state.getAsJsonObject("mods").entrySet()){String ownerDir=modItem.getValue().getAsJsonObject().get("directory").getAsString();for(var item:modItem.getValue().getAsJsonObject().getAsJsonObject("files").entrySet()){JsonObject r=item.getValue().getAsJsonObject();if(!r.has("modelSource")||!only.contains(r.get("modelSource").getAsString()))continue;String path=r.get("target").getAsString();int slash=path.lastIndexOf('/');if(slash>0)merged.remove(ownerDir+"/"+path.substring(0,slash));}}merged.putAll(next);bindings=Map.copyOf(merged);}
        Map<String,String> mergedRevisions=only==null?new HashMap<>():new HashMap<>(revisions);mergedRevisions.putAll(nextRevisions);revisions=Map.copyOf(mergedRevisions);refreshPreviewTokens();Performance.end("bindings.update",timing);
    }
    private static JsonObject encode(Binding binding,String model,JsonObject geo,ResourceCatalog catalog,JsonObject state,Verification verified){
        JsonObject plan=new JsonObject();plan.addProperty("schema",BINDING_SCHEMA);plan.addProperty("modelKey",model);plan.addProperty("geometrySourceHash",geo.has("sourceHash")?geo.get("sourceHash").getAsString():"");
        JsonArray animations=new JsonArray();binding.animations().forEach(animations::add);plan.add("animations",animations);if(binding.texture()!=null)plan.addProperty("baseTexture",binding.texture());JsonObject materials=new JsonObject();binding.materials().forEach(materials::addProperty);plan.add("materials",materials);JsonArray parts=new JsonArray();for(var p:binding.parts()){JsonObject row=new JsonObject();row.addProperty("bone",p.bone());row.addProperty("cube",p.cube());row.addProperty("slot",p.slot());JsonArray faces=new JsonArray();p.faces().forEach(faces::add);row.add("faces",faces);parts.add(row);}plan.add("parts",parts);
        JsonObject sources=new JsonObject();for(var edge:catalog.bundles.getOrDefault(model,List.of())){JsonObject provider=resourceOwner(state,edge.resource()),resource=resourceRecord(state,edge.resource());if(provider==null||resource==null)continue;String dir=provider.get("directory").getAsString();if(!List.of("imported","updated","unchanged").contains(verified.check(dir,resource)))continue;JsonObject entry=new JsonObject();entry.addProperty("provider",edge.resource().substring(0,edge.resource().indexOf(':')));entry.addProperty("source",edge.resource());entry.addProperty("target",resource.get("target").getAsString());entry.addProperty("link","models/"+dir+"/"+resource.get("target").getAsString());entry.addProperty("sourceHash",resource.get("sourceHash").getAsString());entry.addProperty("writtenHash",resource.get("writtenHash").getAsString());entry.addProperty("type",resource.get("type").getAsString());if(resource.has("validatedPngHash"))entry.add("validatedPngHash",resource.get("validatedPngHash"));entry.addProperty("evidence",edge.evidence());sources.add(edge.resource(),entry);}plan.add("resources",sources);JsonArray skins=new JsonArray();Set<String> seenSkins=new HashSet<>();for(var req:catalog.requirementsFor(model))if(req.kind().equals("texture")&&req.role().equals("SKIN_VARIANT")&&req.status().equals("FOUND"))for(String id:req.candidates()){if(!seenSkins.add(id))continue;JsonObject skin=new JsonObject();skin.addProperty("resource",id);skin.addProperty("evidence",req.evidence());JsonObject link=sources.has(id)?sources.getAsJsonObject(id):null;if(link!=null)skin.addProperty("link",link.get("link").getAsString());skins.add(skin);}if(geo.has("textureSource")){plan.addProperty("skinVariant",true);plan.addProperty("skinSource",geo.get("textureSource").getAsString());}plan.add("skins",skins);plan.add("layerDeclarations",catalog.layerPlans.getOrDefault(model,new JsonArray()).deepCopy());return plan;
    }
    private static String revision(JsonObject record){TreeMap<String,String> resources=new TreeMap<>();if(record.has("bindingPlan"))for(var e:record.getAsJsonObject("bindingPlan").getAsJsonObject("resources").entrySet()){if(e.getValue().isJsonObject()&&strings(e.getValue().getAsJsonObject(),"writtenHash"))resources.put(e.getKey(),e.getValue().getAsJsonObject().get("writtenHash").getAsString());}return GeoImporter.hash((record.get("writtenHash")+"|"+resources).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static boolean strings(JsonObject object,String... names){if(object==null)return false;for(String name:names){JsonElement value=object.get(name);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||value.getAsString().isEmpty())return false;}return true;}
    static void bootstrap(JsonObject state,java.nio.file.Path models){
        long timing=Performance.start();
        Verification verified=verification.get()==null?new Verification(models):verification.get();Map<String,Boolean> decoded=new HashMap<>();Map<String,Binding> next=new HashMap<>();Map<String,String> nextRevisions=new HashMap<>();JsonObject mods=state.getAsJsonObject("mods");
        for(var modItem:mods.entrySet()){
            JsonObject mod=modItem.getValue().getAsJsonObject();String dir=mod.get("directory").getAsString();
            for(var item:mod.getAsJsonObject("files").entrySet()){
                JsonObject geo=item.getValue().getAsJsonObject();if(geo.has("inactiveReason"))continue;if(!geo.has("modelSource")||!geo.has("bindingPlan"))continue;
                String target=geo.get("target").getAsString();if(!List.of("imported","updated","unchanged").contains(verified.check(dir,geo)))continue;
                JsonObject plan=geo.getAsJsonObject("bindingPlan");if(!plan.has("schema")||plan.get("schema").getAsInt()!=BINDING_SCHEMA||!plan.has("geometrySourceHash")||!geo.has("sourceHash")||!plan.get("geometrySourceHash").getAsString().equals(geo.get("sourceHash").getAsString()))continue;
                // A failed dependency must not discard independently verified base/material links.
                // Keep the persistent complete plan intact so repaired outputs recover on the next bootstrap.
                Set<String> validLinks=new HashSet<>();JsonObject sources=plan.getAsJsonObject("resources");if(sources==null)continue;
                for(var src:sources.entrySet()){
                    if(!src.getValue().isJsonObject())continue;
                    JsonObject ref=src.getValue().getAsJsonObject();
                    if(!strings(ref,"provider","source","target","type","link","sourceHash","writtenHash")||!src.getKey().equals(ref.get("source").getAsString()))continue;
                    String providerId=ref.get("provider").getAsString();
                    if(!src.getKey().startsWith(providerId+":")||!mods.has(providerId)||!mods.get(providerId).isJsonObject())continue;
                    JsonObject provider=mods.getAsJsonObject(providerId);
                    if(!strings(provider,"directory")||!provider.has("files")||!provider.get("files").isJsonObject())continue;
                    JsonElement entry=provider.getAsJsonObject("files").get("@resource/"+src.getKey());
                    if(entry==null||!entry.isJsonObject())continue;
                    JsonObject resource=entry.getAsJsonObject();
                    if(!strings(resource,"status","type","target","sourceHash","writtenHash")||!List.of("imported","updated","unchanged").contains(resource.get("status").getAsString()))continue;
                    String directory=provider.get("directory").getAsString(),expected="models/"+directory+"/"+resource.get("target").getAsString();
                    if(!expected.equals(ref.get("link").getAsString())||!resource.get("type").equals(ref.get("type"))||!resource.get("target").equals(ref.get("target"))||!resource.get("sourceHash").equals(ref.get("sourceHash"))||!resource.get("writtenHash").equals(ref.get("writtenHash")))continue;
                    if(!List.of("imported","updated","unchanged").contains(verified.check(directory,resource)))continue;
                    if(decoded.computeIfAbsent(expected+"|"+resource.get("writtenHash"),k->verifiedPngReadable(models,expected,resource)))validLinks.add(expected);
                }
                List<String> animations=new ArrayList<>();for(var v:plan.getAsJsonArray("animations"))if(validLinks.contains(v.getAsString()))animations.add(v.getAsString());Map<String,String> materials=new TreeMap<>();plan.getAsJsonObject("materials").entrySet().forEach(e->{if(validLinks.contains(e.getValue().getAsString()))materials.put(e.getKey(),e.getValue().getAsString());});
                List<MaterialPlan.Part> parts=new ArrayList<>();for(var v:plan.getAsJsonArray("parts")){JsonObject p=v.getAsJsonObject();List<String> faces=new ArrayList<>();p.getAsJsonArray("faces").forEach(f->faces.add(f.getAsString()));parts.add(new MaterialPlan.Part(p.get("bone").getAsString(),p.get("cube").getAsInt(),p.get("slot").getAsString(),List.copyOf(faces)));}
                String texture=plan.has("baseTexture")&&validLinks.contains(plan.get("baseTexture").getAsString())?plan.get("baseTexture").getAsString():null;int slash=target.lastIndexOf('/');if(slash>0){String key=dir+"/"+target.substring(0,slash);next.put(key,new Binding(animations.stream().distinct().sorted().toList(),texture,Map.copyOf(materials),List.copyOf(parts)));nextRevisions.put(key,revision(geo));}
            }
        }
        bindings=Map.copyOf(next);revisions=Map.copyOf(nextRevisions);refreshPreviewTokens();status="startup_snapshot";Performance.end("bindings.bootstrap",timing);
    }
    /** The caller has just verified the owned path and its actual bytes against writtenHash. */
    private static boolean verifiedPngReadable(java.nio.file.Path models,String link,JsonObject resource){
        if(!"texture".equals(resource.get("type").getAsString())||resource.get("writtenHash").equals(resource.get("validatedPngHash")))return true;
        var path=models.resolve(link.substring("models/".length()));
        Performance.count("binding.pngDecode",0);
        try(var in=java.nio.file.Files.newInputStream(path)){return javax.imageio.ImageIO.read(in)!=null;}catch(Exception ex){return false;}
    }
    private static final Map<Object,Map<String,Object>> loadedBindings=Collections.synchronizedMap(new WeakHashMap<>());
    public static void beforeGet(Object manager,String key){
        Binding current=bindings.get(key);Object token=previewToken(key);
        synchronized(loadedBindings){
            Map<String,Object> seen=loadedBindings.computeIfAbsent(manager,m->new HashMap<>());
            if(!seen.containsKey(key)&&current==null){seen.put(key,null);return;}
            if(Objects.equals(seen.get(key),token))return;
            try{boolean cached=true;try{var models=manager.getClass().getDeclaredField("models");models.setAccessible(true);cached=((Map<?,?>)models.get(manager)).containsKey(key);}catch(NoSuchFieldException ignored){}if(!cached){seen.put(key,token);return;}Performance.count("bbs.forget",0);var forget=manager.getClass().getDeclaredMethod("forget",String.class);forget.setAccessible(true);forget.invoke(manager,key);if(current==null)seen.remove(key);else seen.put(key,token);}
            catch(ReflectiveOperationException|LinkageError ex){status="refresh_incompatible:"+ex.getClass().getSimpleName();}
        }
    }
    public static Collection<?> augment(String key,Collection<?> original){
        lookups.incrementAndGet();Binding b=bindings.get(key);if(b==null)return original;matched.incrementAndGet();
        try{
            Class<?> link=Class.forName("mchorse.bbs_mod.resources.Link");var factory=link.getMethod("assets",String.class);
            Collection<Object> result=new LinkedHashSet<>(original);for(String path:b.animations())if(result.add(factory.invoke(null,path)))animationsAdded.incrementAndGet();status="active";return result;
        }catch(ReflectiveOperationException|LinkageError ex){status="incompatible:"+ex.getClass().getSimpleName();return original;}
    }
    static void applyParts(Object model,List<MaterialPlan.Part> parts)throws ReflectiveOperationException {
        var getGroup=model.getClass().getMethod("getGroup",String.class);
        for(var part:parts){
            Object group=getGroup.invoke(model,part.bone());if(group==null)throw new IllegalStateException("Material bone missing: "+part.bone());
            List<?> cubes=(List<?>)group.getClass().getField("cubes").get(group);
            if(part.cube()>=cubes.size())throw new IllegalStateException("Material cube missing: "+part.bone()+":"+part.cube());
            Object cube=cubes.get(part.cube());
            cube.getClass().getField("material").set(cube,part.slot());
            for(var face:MaterialPlan.FACES.entrySet())if(!part.faces().contains(face.getKey()))cube.getClass().getField(face.getValue()).set(cube,null);
        }
        model.getClass().getMethod("regenerateQuads").invoke(model);
    }
    public static void apply(String key,Object instance){
        Binding b=bindings.get(key);if(b==null||instance==null)return;
        try{
            var field=instance.getClass().getField("baseTexture");var factory=field.getType().getMethod("assets",String.class);
            if(b.texture()!=null){field.set(instance,factory.invoke(null,b.texture()));texturesApplied.incrementAndGet();}
            if(!b.parts().isEmpty()){
                Object model=instance.getClass().getField("model").get(instance);
                applyParts(model,b.parts());
                @SuppressWarnings("unchecked") Map<String,Object> materialTextures=(Map<String,Object>)instance.getClass().getField("materialTextures").get(instance);
                @SuppressWarnings("unchecked") List<String> materials=(List<String>)instance.getClass().getField("materials").get(instance);
                for(var item:b.materials().entrySet()){materialTextures.put(item.getKey(),factory.invoke(null,item.getValue()));if(!materials.contains(item.getKey()))materials.add(item.getKey());texturesApplied.incrementAndGet();}
            }
            status="active";
        }catch(ReflectiveOperationException|LinkageError|RuntimeException ex){status="incompatible:"+ex.getClass().getSimpleName();}
    }
    public static void beforeLoadReturn(String key,Object instance){Performance.count("bbs.actualLoad",0);apply(key,instance);}
}
