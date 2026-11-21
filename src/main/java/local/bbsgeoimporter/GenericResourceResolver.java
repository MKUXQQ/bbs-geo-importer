package local.bbsgeoimporter;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Mod-independent JSON references. Case folding is a fallback and never hides collisions. */
final class GenericResourceResolver implements ResourceAssociationResolver {
    record Requirement(String model,String kind,String reference,String status,List<String> candidates,String evidence,String role) {
        String slot(){String p=evidence.split("; ",2)[0].split(":alias:",2)[0];int i=p.lastIndexOf("/textures/");if(i>=0)return p.substring(i+10).split("/",2)[0];i=p.lastIndexOf("/material_instances/");return i<0?"":p.substring(i+20).split("/",2)[0];}
    }
    private final Map<String,Set<ResourceCatalog.Entry>> paths=new HashMap<>(), folded=new HashMap<>(), identifiers=new HashMap<>(), animationNames=new HashMap<>();
    private ResourceCatalog catalog;
    private final Map<String,Set<ResourceCatalog.Entry>> shortGeometry=new HashMap<>();
    private final Map<String,Set<String>> atlasDirectories=new HashMap<>();
    private final Map<String,JsonArray> attempts=new HashMap<>();
    private final JsonArray referenceLookups=new JsonArray();
    private void add(Map<String,Set<ResourceCatalog.Entry>> index,String key,ResourceCatalog.Entry e){index.computeIfAbsent(key,k->new LinkedHashSet<>()).add(e);}
    @Override public void resolve(ResourceCatalog c) {
        catalog=c;c.report.add("referenceLookups",referenceLookups);
        for(var files:c.entries.values())for(var e:files.values()) {
            if(e.kind().equals("geo")&&e.path().startsWith("assets/")){String[] p=e.path().split("/",3);String name=e.path().substring(e.path().lastIndexOf('/')+1);if(name.endsWith(".geo.json"))add(shortGeometry,p[1]+":"+name.substring(0,name.length()-5),e);}
            if(e.path().matches("assets/[^/]+/atlases/.+[.]json"))try{JsonObject atlas=GeoFormats.json(e.file()).getAsJsonObject();if(atlas.has("sources"))for(var v:atlas.getAsJsonArray("sources")){JsonObject s=v.getAsJsonObject();if(s.has("type")&&Set.of("directory","minecraft:directory").contains(s.get("type").getAsString())&&s.has("source")){String ns=e.path().split("/",3)[1];atlasDirectories.computeIfAbsent(ns,k->new LinkedHashSet<>()).add(s.get("source").getAsString());}}}catch(Exception ex){c.event(e,"atlas_parse_failed",ex.toString());}
            if(!e.kind().equals("other")){add(paths,e.path(),e);add(folded,e.path().toLowerCase(Locale.ROOT),e);}
            if(e.kind().equals("geo")||e.kind().equals("animation"))try {
                JsonElement data=GeoFormats.json(e.file());
                if(e.kind().equals("geo"))indexIdentifiers(e,data);
                else if(data.isJsonObject()&&data.getAsJsonObject().has("animations"))for(String name:data.getAsJsonObject().getAsJsonObject("animations").keySet())add(animationNames,name,e);
            }catch(Exception ex){c.event(e,"resource_parse_failed",ex.toString());}
        }
        for(var files:c.entries.values())for(var e:files.values())if(e.path().toLowerCase(Locale.ROOT).endsWith(".json")&&!e.kind().equals("animation"))try {
            if(Files.size(e.file())<=8*1024*1024)walk(e,GeoFormats.json(e.file()),e.kind().equals("geo")?Set.of(e):Set.of(),Map.of(),"$");
        }catch(Exception ex){c.event(e,"definition_unreadable",ex.toString());}
    }
    private void indexIdentifiers(ResourceCatalog.Entry e,JsonElement value){
        if(value.isJsonObject())for(var x:value.getAsJsonObject().entrySet()){
            if(x.getKey().equals("identifier")&&x.getValue().isJsonPrimitive())add(identifiers,e.owner()+":"+x.getValue().getAsString(),e);
            indexIdentifiers(e,x.getValue());
        }else if(value.isJsonArray())for(var x:value.getAsJsonArray())indexIdentifiers(e,x);
    }
    private void walk(ResourceCatalog.Entry def,JsonElement value,Set<ResourceCatalog.Entry> inherited,Map<String,JsonElement> aliases,String pointer){
        if(value.isJsonArray()){int i=0;for(var x:value.getAsJsonArray())walk(def,x,inherited,aliases,pointer+"/"+i++);return;}
        if(!value.isJsonObject())return;
        var o=value.getAsJsonObject();Map<String,JsonElement> local=new HashMap<>(aliases);
        if(o.has("variations")&&o.get("variations").isJsonArray()&&def.path().matches("(?:resourcepacks/[^/]+/)?assets/[^/]+/bedrock/.+/(?:resolvers|variations)/.+\\.json"))return; // the aspect resolver owns this format, including its layers
        // Paired named properties describe the same instance (fruitModel/fruitTexture, etc.).
        // Short texture ids are resolved only through an actual atlas directory declaration.
        for(var property:o.entrySet())if(property.getKey().endsWith("Model")&&property.getValue().isJsonPrimitive()&&property.getValue().getAsJsonPrimitive().isString()){
            String textureField=property.getKey().substring(0,property.getKey().length()-5)+"Texture";JsonElement texture=o.get(textureField);
            if(texture==null||!texture.isJsonPrimitive()||!texture.getAsJsonPrimitive().isString())continue;
            String ref=property.getValue().getAsString();var selected=matches(def,ref,"geo");if(selected.isEmpty()&&ref.endsWith(".geo"))selected=shortGeometry.getOrDefault(ref,Set.of());
            if(selected.size()!=1)continue;var model=selected.iterator().next();String png=texture.getAsString();var resolved=matches(def,png,"texture");String evidence="json:"+ResourceCatalog.key(def)+"#"+pointer+"/"+textureField;
            if(resolved.isEmpty()&&png.contains(":")){int colon=png.indexOf(':');String ns=png.substring(0,colon),path=png.substring(colon+1);Set<ResourceCatalog.Entry> atlas=new LinkedHashSet<>();for(String directory:atlasDirectories.getOrDefault(ns,Set.of()))atlas.addAll(matches(def,ns+":textures/"+directory+"/"+path,"texture"));resolved=atlas;evidence+=":atlas_directory";}
            catalog.dependency(model,"texture",png,resolved.size()==1?"FOUND":resolved.isEmpty()?"MISSING":"AMBIGUOUS",new ArrayList<>(resolved),evidence,"SINGLE");
        }
        for(String field:List.of("variables","textures","animations"))if(o.has(field)&&o.get(field).isJsonObject())for(var alias:o.getAsJsonObject(field).entrySet()){if(!field.equals("animations"))local.put("texture:"+alias.getKey(),alias.getValue());if(!field.equals("textures"))local.put("animation:"+alias.getKey(),alias.getValue());}
        Set<ResourceCatalog.Entry> models=inherited;
        for(String field:List.of("model","geometry"))if(o.has(field)&&!o.get(field).isJsonObject()){
            boolean[] geoContext={!inherited.isEmpty()};flatten(o.get(field),r->{if(r.toLowerCase(Locale.ROOT).contains(".geo")||!matches(def,r,"geo").isEmpty())geoContext[0]=true;});
            if(!geoContext[0])continue;
            Set<ResourceCatalog.Entry> found=new LinkedHashSet<>();boolean[] all={true};
            flatten(o.get(field),r->{var m=matches(def,r,"geo");found.addAll(m);if(m.size()!=1){all[0]=false;catalog.event(def,r.toLowerCase(Locale.ROOT).endsWith(".geo.json")?"missing_reference":"unsupported_reference","geo:"+r);}});
            if(found.size()>1){catalog.event(def,"ambiguous_definition","Multiple geometries in singular model/geometry field; pairing not proven");models=Set.of();}
            else if(all[0]&&!found.isEmpty())models=found;
            else {models=Set.of();catalog.event(def,"incomplete_definition","geo references unresolved at "+pointer);}
        }
        // A plural model list explicitly shares its sibling dependencies; arbitrary nested strings do not.
        if(o.has("models")&&o.get("models").isJsonArray()){
            Set<ResourceCatalog.Entry> found=new LinkedHashSet<>();boolean[] all={true};flatten(o.get("models"),r->{var m=matches(def,r,"geo");if(m.size()!=1)all[0]=false;found.addAll(m);});if(all[0])models=found;
        }
        for(String field:List.of("texture","textures","animation","animations","skins"))if(o.has(field)&&!models.isEmpty()&&!(field.equals("texture")&&pointer.contains("/bones/"))) {
            String kind=field.startsWith("texture")||field.equals("skins")?"texture":"animation";
            String role=field.equals("skins")?"SKIN_VARIANT":o.get(field).isJsonObject()&&!DeclaredFrames.object(o.get(field))?"NAMED_SLOT":o.get(field).isJsonArray()?"MULTIPLE_REFERENCES":"SINGLE";
            if(kind.equals("texture")&&pointer.matches(".*/layers(?:/.*)?"))role="SIMULTANEOUS_OVERLAY";
            for(var model:models){String evidence="json:"+ResourceCatalog.key(def)+"#"+pointer+"/"+field;
                resolveValue(def,model,o.get(field),kind,local,new HashSet<>(),evidence,role);
            }
        }
        if(o.has("material_instances")&&o.get("material_instances").isJsonObject())for(var slot:o.getAsJsonObject("material_instances").entrySet())if(slot.getValue().isJsonObject()&&slot.getValue().getAsJsonObject().has("texture"))for(var model:models)resolveValue(def,model,slot.getValue().getAsJsonObject().get("texture"),"texture",local,new HashSet<>(),"json:"+ResourceCatalog.key(def)+"#"+pointer+"/material_instances/"+slot.getKey()+"/texture","NAMED_SLOT");
        for(var x:o.entrySet())if(!List.of("texture","textures","animation","animations","skins","material_instances").contains(x.getKey()))walk(def,x.getValue(),models,local,pointer+"/"+x.getKey());
    }
    private void resolveValue(ResourceCatalog.Entry def,ResourceCatalog.Entry model,JsonElement value,String kind,Map<String,JsonElement> aliases,Set<String> chain,String evidence,String role){
        if(kind.equals("texture")&&DeclaredFrames.object(value)){
            String first=switch(role){case "SINGLE"->DeclaredFrames.BASE;case "SKIN_VARIANT"->DeclaredFrames.SKIN;default->role;};
            DeclaredFrames.read(value,evidence,first,(ref,where,use,valid)->{
                if(valid)resolveValue(def,model,new JsonPrimitive(ref),kind,aliases,new HashSet<>(chain),where,use);
                else require(model,kind,ref,"INVALID",List.of(),where,use);
            });return;
        }
        if(value.isJsonObject()){for(var x:value.getAsJsonObject().entrySet())resolveValue(def,model,x.getValue(),kind,aliases,new HashSet<>(chain),evidence+"/"+x.getKey(),role);return;}
        if(value.isJsonArray()){for(var x:value.getAsJsonArray())resolveValue(def,model,x,kind,aliases,new HashSet<>(chain),evidence,role);return;}
        if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())return;
        String ref=value.getAsString();
        if(ref.startsWith("#")||ref.startsWith("${")&&ref.endsWith("}")){
            String alias=ref.startsWith("#")?ref.substring(1):ref.substring(2,ref.length()-1);if(!chain.add(alias)){require(model,kind,ref,"INVALID",List.of(),evidence+":alias_cycle",role);return;}
            if(aliases.containsKey(kind+":"+alias))resolveValue(def,model,aliases.get(kind+":"+alias),kind,aliases,chain,evidence+":alias:"+alias,role);
            else require(model,kind,ref,"MISSING",List.of(),evidence+":unknown_alias",role);return;
        }
        var matches=matches(def,ref,kind);String status=matches.size()==1?"FOUND":matches.isEmpty()?"MISSING":"AMBIGUOUS";
        if(ref.contains("$")||ref.contains("query.")||ref.contains("variable."))status="UNSUPPORTED";
        require(model,kind,ref,status,matches.stream().map(ResourceCatalog::key).toList(),evidence,role);
        JsonObject lookup=new JsonObject();lookup.addProperty("model",ResourceCatalog.key(model));lookup.addProperty("definition",ResourceCatalog.key(def));lookup.addProperty("reference",ref);lookup.addProperty("kind",kind);lookup.addProperty("evidence",evidence);lookup.addProperty("status",status);lookup.add("attempts",attempts.getOrDefault(kind+":"+ResourceCatalog.key(def)+":"+ref,new JsonArray()).deepCopy());referenceLookups.add(lookup);
        if(status.equals("FOUND")){
            var dep=matches.iterator().next();var edge=new ResourceCatalog.Edge(ResourceCatalog.key(model),ResourceCatalog.key(dep),evidence);
            if(!catalog.bundles.get(ResourceCatalog.key(model)).contains(edge))catalog.bundles.get(ResourceCatalog.key(model)).add(edge);
        }else catalog.event(model,status.equals("AMBIGUOUS")?"ambiguous_reference":"missing_reference",kind+":"+ref+" @ "+evidence);
    }
    private void require(ResourceCatalog.Entry model,String kind,String ref,String status,List<String> candidates,String evidence,String role){catalog.dependency(model,kind,ref,status,candidates.stream().map(k->{int colon=k.indexOf(':');return catalog.entries.get(k.substring(0,colon)).get(k.substring(colon+1));}).toList(),evidence,role);}
    private static void flatten(JsonElement value,java.util.function.Consumer<String> action){if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString())action.accept(value.getAsString());else if(value.isJsonArray())for(var x:value.getAsJsonArray())flatten(x,action);}
    Set<ResourceCatalog.Entry> matches(ResourceCatalog.Entry def,String raw,String kind){
        JsonArray trace=new JsonArray();attempts.put(kind+":"+ResourceCatalog.key(def)+":"+raw,trace);
        String ref=raw.replace('\\','/');if(ref.startsWith("/")||ref.contains("://")||ref.matches("^[A-Za-z]:/.*"))return Set.of();
        String suffix=kind.equals("texture")?".png":kind.equals("animation")?".animation.json":".geo.json";
        String expanded=ref.toLowerCase(Locale.ROOT).endsWith(suffix)||kind.equals("animation")&&ref.toLowerCase(Locale.ROOT).endsWith(".json")||kind.equals("geo")&&ref.toLowerCase(Locale.ROOT).endsWith(".json")?ref:ref+suffix;
        if(kind.equals("geo")){var id=identifiers.getOrDefault(def.owner()+":"+ref,Set.of());if(!id.isEmpty())return id;}
        List<String> locations=new ArrayList<>();int colon=expanded.indexOf(':');
        if(colon>=0)locations.add("assets/"+expanded.substring(0,colon)+"/"+expanded.substring(colon+1));
        else {
            if(expanded.startsWith("assets/"))locations.add(expanded);
            if(ref.startsWith("./")||ref.startsWith("../")){int parent=def.path().lastIndexOf('/');locations.add((parent<0?"":def.path().substring(0,parent+1))+expanded);}
            String address=def.path();int slash=address.startsWith("assets/")?address.indexOf('/',7):-1;
            if(slash>=0)locations.add(address.substring(0,slash+1)+expanded);
            int last=address.lastIndexOf('/');locations.add((last<0?"":address.substring(0,last+1))+expanded);locations.add(expanded);
        }
        for(String p:locations){String safe=normalize(p);if(safe==null)continue;
            var exact=select(paths.getOrDefault(safe,Set.of()),def,kind,colon>=0);recordAttempt(trace,safe,"EXACT",exact);if(!exact.isEmpty())return exact;
            var caseMatches=select(folded.getOrDefault(safe.toLowerCase(Locale.ROOT),Set.of()),def,kind,colon>=0);recordAttempt(trace,safe,"CASE_FALLBACK",caseMatches);if(!caseMatches.isEmpty())return caseMatches;
        }
        Set<ResourceCatalog.Entry> found=new LinkedHashSet<>();
        if(found.isEmpty()&&kind.equals("animation"))found.addAll(select(animationNames.getOrDefault(ref,Set.of()),def,kind,false));
        return found;
    }
    private static void recordAttempt(JsonArray trace,String path,String rule,Set<ResourceCatalog.Entry> matches){JsonObject row=new JsonObject();row.addProperty("path",path);row.addProperty("rule",rule);JsonArray candidates=new JsonArray();matches.forEach(e->candidates.add(ResourceCatalog.key(e)));row.add("candidates",candidates);trace.add(row);}
    private Set<ResourceCatalog.Entry> select(Set<ResourceCatalog.Entry> candidates,ResourceCatalog.Entry def,String kind,boolean namespaced){
        Set<ResourceCatalog.Entry> all=new LinkedHashSet<>(),own=new LinkedHashSet<>();for(var e:candidates)if(e.kind().equals(kind)){all.add(e);if(e.owner().equals(def.owner()))own.add(e);}
        return !namespaced&&!own.isEmpty()?own:all;
    }
    private static String normalize(String value){Deque<String> parts=new ArrayDeque<>();for(String p:value.split("/")){if(p.equals("..")){if(parts.isEmpty())return null;parts.removeLast();}else if(!p.isEmpty()&&!p.equals("."))parts.add(p);}return String.join("/",parts);}
}
