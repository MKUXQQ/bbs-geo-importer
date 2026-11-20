package local.bbsgeoimporter;
import com.google.gson.*;
import java.nio.file.Files;
import java.util.*;

/** Source completeness and publication are separate from runtime rendering. */
final class ModelCompleteness {
    private static Set<String> geometryBones(ResourceCatalog.Entry model){
        Set<String> bones=new HashSet<>();
        try{JsonArray geometries=GeoFormats.json(model.file()).getAsJsonObject().getAsJsonArray("minecraft:geometry");if(geometries!=null)for(var geometry:geometries){JsonArray list=geometry.getAsJsonObject().getAsJsonArray("bones");if(list!=null)for(var bone:list)if(bone.getAsJsonObject().has("name"))bones.add(bone.getAsJsonObject().get("name").getAsString());}}catch(Exception ignored){}
        return bones;
    }
    private static void appendTextureStatus(ResourceCatalog c,JsonObject state,java.nio.file.Path output,String key,JsonObject row,MaterialPlan plan,String publication){
        Set<String> resources=new TreeSet<>(),published=new TreeSet<>();JsonArray refs=new JsonArray(),reasons=new JsonArray(),parts=new JsonArray();boolean unresolved=false,unsupported=!plan.unsupported.isEmpty();
        for(var r:c.requirementsFor(key))if(r.kind().equals("texture")){
            JsonObject ref=new JsonObject();ref.addProperty("reference",r.reference());ref.addProperty("status",r.status());ref.addProperty("role",r.role());ref.addProperty("slot",r.slot());ref.addProperty("evidence",r.evidence());JsonArray candidates=new JsonArray();r.candidates().forEach(candidates::add);ref.add("candidates",candidates);refs.add(ref);
            if(!r.status().equals("FOUND")){unresolved=true;reasons.add(r.status()+": "+r.reference()+" @ "+r.evidence());}if(r.status().equals("UNSUPPORTED"))unsupported=true;
        }
        for(var edge:c.bundles.getOrDefault(key,List.of())){
            int colon=edge.resource().indexOf(':');var e=c.entries.getOrDefault(edge.resource().substring(0,colon),Map.of()).get(edge.resource().substring(colon+1));if(e==null||!e.kind().equals("texture"))continue;
            resources.add(edge.resource());var owner=SharedModelAssets.resourceOwner(state,edge.resource());String status=SharedModelAssets.verifyOutput(output,owner==null?"":owner.get("directory").getAsString(),SharedModelAssets.resourceRecord(state,edge.resource()));
            if(List.of("imported","updated","unchanged").contains(status))published.add(edge.resource());else reasons.add("PUBLICATION_"+status+": "+edge.resource());
        }
        Map<String,String> slots=plan.confirmedSlots();
        for(var p:plan.parts){JsonObject part=new JsonObject();part.addProperty("bone",p.bone());part.addProperty("cube",p.cube());part.addProperty("slot",p.slot());JsonArray faces=new JsonArray();p.faces().forEach(faces::add);part.add("faces",faces);part.addProperty("resource",slots.get(p.slot()));parts.add(part);}
        for(String slot:plan.usedSlots())if(!slots.containsKey(slot)){unresolved=true;reasons.add("UNRESOLVED_MATERIAL_SLOT: "+slot);}
        long skins=c.requirementsFor(key).stream().filter(r->r.kind().equals("texture")&&r.role().equals("SKIN_VARIANT")&&r.status().equals("FOUND")).flatMap(r->r.candidates().stream()).distinct().count();
        boolean defaultKnown=resources.size()==1||c.defaultTextures.containsKey(key)&&!c.defaultTextureConflicts.contains(key);
        boolean paired=plan.hasSelectors()&&slots.keySet().containsAll(plan.usedSlots())&&plan.parts.stream().allMatch(p->!p.slot().isEmpty()||defaultKnown);
        boolean complete=!resources.isEmpty()&&published.containsAll(resources)&&!unresolved&&(paired||defaultKnown||skins==resources.size());
        if(resources.size()>1&&!paired&&!defaultKnown&&skins!=resources.size())reasons.add("MULTIPLE_TEXTURES_WITHOUT_PROVEN_PART_OR_SKIN_BINDING");
        if(resources.isEmpty())reasons.add("NO_CONFIRMED_TEXTURE_REFERENCE; filename candidates are diagnostic only");
        plan.unsupported.forEach(reasons::add);
        JsonArray layers=c.layerPlans.get(key);if(layers!=null&&!layers.isEmpty()){unsupported=true;complete=false;for(var layer:layers)reasons.add("UNSUPPORTED_RENDER_LAYER: "+layer);}
        row.addProperty("fileVerificationOnly",true);
        if(plan.geometryCount>1){unsupported=true;reasons.add("MULTIPLE_GEOMETRIES_REQUIRE_SEPARATE_PUBLICATION");}
        if(!List.of("imported","updated","unchanged").contains(publication)){complete=false;reasons.add("MODEL_PUBLICATION_"+publication);}
        row.addProperty("textureStatus",unsupported?"UNSUPPORTED":complete?"FULL_TEXTURES":published.isEmpty()?"NO_TEXTURES":"PARTIAL_TEXTURES");
        row.addProperty("textureSemantics",paired?"SIMULTANEOUS_MATERIALS":skins>0?"EXPLICIT_SKIN_VARIANTS":defaultKnown?"CONFIRMED_BASE_TEXTURE":"UNRESOLVED");
        row.addProperty("sourceTextureCount",resources.size());row.addProperty("publishedTextureCount",published.size());row.addProperty("geometryCount",plan.geometryCount);
        JsonArray resolutionTrace=new JsonArray();for(var value:c.report.getAsJsonArray("events")){var event=value.getAsJsonObject();if(event.has("source")&&event.get("source").getAsString().equals(key)&&event.has("status")&&Set.of("model_reference_sites","static_texture_unknown").contains(event.get("status").getAsString()))resolutionTrace.add(event.deepCopy());}row.add("resolutionTrace",resolutionTrace);
        row.add("textureReferences",refs);row.add("materialParts",parts);row.add("textureReasons",reasons);
    }
    static void append(ResourceCatalog catalog,JsonObject state,java.nio.file.Path outputModels){
        JsonArray models=new JsonArray();Map<String,Integer> counts=new TreeMap<>();
        for(var files:catalog.entries.values())for(var model:files.values())if(model.kind().equals("geo")){
            String key=ResourceCatalog.key(model);JsonObject row=new JsonObject();row.addProperty("model",key);
            JsonArray dependencies=new JsonArray();Set<String> linked=new HashSet<>();boolean missing=false,uncertain=false,failed=false;int textures=0,animations=0;
            for(var req:catalog.requirementsFor(key)){
                JsonObject d=new JsonObject();d.addProperty("kind",req.kind());d.addProperty("reference",req.reference());d.addProperty("status",req.status());d.addProperty("evidence",req.evidence());d.addProperty("role",req.role());
                JsonArray candidates=new JsonArray();req.candidates().forEach(candidates::add);d.add("candidates",candidates);dependencies.add(d);
                if(!req.status().equals("FOUND")){if(req.status().equals("MISSING")||req.status().equals("INVALID"))missing=true;else uncertain=true;}
            }
            for(var edge:catalog.bundles.getOrDefault(key,List.of()))if(linked.add(edge.resource())){
                int colon=edge.resource().indexOf(':');var dep=catalog.entries.getOrDefault(edge.resource().substring(0,colon),Map.of()).get(edge.resource().substring(colon+1));if(dep==null)continue;
                if(dep.kind().equals("texture"))textures++;if(dep.kind().equals("animation"))animations++;
                JsonObject owner=SharedModelAssets.resourceOwner(state,edge.resource());JsonObject published=SharedModelAssets.resourceRecord(state,edge.resource());
                String output=SharedModelAssets.verifyOutput(outputModels,owner==null?"":owner.get("directory").getAsString(),published);if(!List.of("imported","updated","unchanged").contains(output))missing=true;
                JsonObject d=new JsonObject();d.addProperty("kind",dep.kind());d.addProperty("resource",edge.resource());d.addProperty("status","FOUND");d.addProperty("publicationStatus",output);d.addProperty("evidence",edge.evidence());dependencies.add(d);
            }
            JsonObject mod=state.getAsJsonObject("mods").getAsJsonObject(model.owner());JsonObject record=mod==null?null:mod.getAsJsonObject("files").getAsJsonObject(model.path());
            String publication=SharedModelAssets.verifyOutput(outputModels,mod==null?"":mod.get("directory").getAsString(),record);
            if(publication.equals("failed")||publication.equals("NOT_PUBLISHED"))failed=true;if(!List.of("imported","updated","unchanged","failed","NOT_PUBLISHED").contains(publication))missing=true;
            for(var ev:catalog.report.getAsJsonArray("events")){
                var e=ev.getAsJsonObject();String source=e.get("source").getAsString(),status=e.get("status").getAsString();
                if(source.equals(key)&&status.equals("failed"))failed=true;
                if((source.equals(key)||linked.contains(source))&&List.of("dependency_failed","animation_key_conflict","user_conflict","default_texture_failed","variant_failed").contains(status)){
                    missing=true;JsonObject d=new JsonObject();d.addProperty("resource",source);d.addProperty("status",status.equals("user_conflict")?"USER_CONFLICT":"INVALID");d.addProperty("reason",e.get("reason").getAsString());dependencies.add(d);
                }
            }
            if(textures==0)uncertain=true;
            boolean defaultKnown=textures==1||catalog.defaultTextures.containsKey(key)&&!catalog.defaultTextureConflicts.contains(key);
            if(textures>1&&!defaultKnown)uncertain=true;
            String status=failed?"FAILED":missing?"INCOMPLETE":uncertain?"UNRESOLVED":"COMPLETE";
            row.addProperty("status",status);row.addProperty("sourceTexturesFound",textures);row.addProperty("sourceAnimationsFound",animations);
            row.addProperty("animationRequirement",catalog.requirementsFor(key).stream().anyMatch(r->r.kind().equals("animation"))?"DECLARED":animations>0?"ASSOCIATED":"NOT_DECLARED");
            long skinVariants=catalog.requirementsFor(key).stream().filter(r->r.kind().equals("texture")&&r.role().equals("SKIN_VARIANT")&&r.status().equals("FOUND")).flatMap(r->r.candidates().stream()).distinct().count();
            row.addProperty("selectableSkinReferences",skinVariants);
            row.addProperty("textureSelection",textures==0?"NO_CONFIRMED_REFERENCE":defaultKnown?"DEFAULT_CONFIRMED":skinVariants==textures?"EXPLICIT_SKINS_AVAILABLE_NO_DEFAULT":"MULTIPLE_REFERENCES_SEMANTICS_UNRESOLVED");
            if(textures>1&&!defaultKnown&&skinVariants!=textures)row.addProperty("materialSupport","UNSUPPORTED_WITHOUT_PROVEN_FACE_SLOT_BINDING");
            MaterialPlan plan=MaterialPlan.read(catalog,key);
            appendTextureStatus(catalog,state,outputModels,key,row,plan,publication);
            JsonArray animationDiagnostics=new JsonArray();Set<String> geoBones=geometryBones(model);boolean incompatibleAnimation=false;
            for(var edge:catalog.bundles.getOrDefault(key,List.of()))if(edge.resource().contains(":")){
                String resourceOwner=edge.resource().substring(0,edge.resource().indexOf(':'));
                var animation=catalog.entries.getOrDefault(resourceOwner,Map.of()).get(edge.resource().substring(edge.resource().indexOf(':')+1));
                if(animation==null||!animation.kind().equals("animation"))continue;
                try{
                    byte[] bytes=Files.readAllBytes(animation.file());
                    for(String issue:GeoFormats.validateAnimations(bytes)){animationDiagnostics.add(edge.resource()+": "+issue);incompatibleAnimation=true;}
                    JsonObject definitions=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("animations");
                    for(var definition:definitions.entrySet())if(definition.getValue().isJsonObject()&&definition.getValue().getAsJsonObject().has("bones")&&definition.getValue().getAsJsonObject().get("bones").isJsonObject())
                        for(String bone:definition.getValue().getAsJsonObject().getAsJsonObject("bones").keySet())if(!geoBones.contains(bone)){animationDiagnostics.add(edge.resource()+" / "+definition.getKey()+": missing model bone "+bone);incompatibleAnimation=true;}
                }catch(Exception ex){animationDiagnostics.add(edge.resource()+": "+ex.getMessage());incompatibleAnimation=true;}
            }
            row.add("animationDiagnostics",animationDiagnostics);
            if(incompatibleAnimation){row.addProperty("animationCompatibility","UNSUPPORTED");row.addProperty("animationStatus","MISSING_OR_INCOMPATIBLE");status="UNRESOLVED";row.addProperty("status",status);}
            if(row.get("textureSemantics").getAsString().equals("SIMULTANEOUS_MATERIALS"))row.addProperty("materialSupport","EXPLICIT_PART_FACE_BINDINGS");
            row.addProperty("sourceMod",model.owner());row.addProperty("sourcePath",model.path());
            row.addProperty("publicationStatus",publication);row.addProperty("runtimeRenderingVerified",false);row.add("dependencies",dependencies);models.add(row);counts.merge(status,1,Integer::sum);
        }
        JsonObject textureTotals=new JsonObject();for(String s:List.of("FULL_TEXTURES","PARTIAL_TEXTURES","NO_TEXTURES","UNSUPPORTED"))textureTotals.addProperty(s,0);JsonArray failedTextures=new JsonArray();for(var value:models){var r=value.getAsJsonObject();String s=r.get("textureStatus").getAsString();textureTotals.addProperty(s,textureTotals.has(s)?textureTotals.get(s).getAsInt()+1:1);if(!s.equals("FULL_TEXTURES"))failedTextures.add(r.get("model"));}catalog.report.add("textureStatusCounts",textureTotals);catalog.report.add("modelsWithoutFullTextures",failedTextures);
        catalog.report.add("models",models);JsonObject totals=new JsonObject();counts.forEach(totals::addProperty);catalog.report.add("modelCompleteness",totals);
    }
}
