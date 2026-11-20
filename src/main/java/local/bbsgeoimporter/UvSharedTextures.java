package local.bbsgeoimporter;
import com.google.gson.*;import java.util.*;
/** Layout compatibility only validates a proven resource family; it never chooses an unrelated skin. */
final class UvSharedTextures {
 static void analyzePoses(ResourceCatalog catalog){
  for(var files:catalog.entries.values())for(var pose:files.values()){
   String suffix="_sleeping.geo.json";
   if(!pose.kind().equals("geo")||!pose.path().endsWith(suffix))continue;
   String key=ResourceCatalog.key(pose);
   if(catalog.requirementsFor(key).stream().anyMatch(r->r.kind().equals("texture"))||catalog.bundles.getOrDefault(key,List.of()).stream().anyMatch(e->e.resource().endsWith(".png")))continue;
   var standing=files.get(pose.path().substring(0,pose.path().length()-suffix.length())+".geo.json");if(standing==null||!standing.kind().equals("geo"))continue;
   String parent=ResourceCatalog.key(standing);if(catalog.defaultTextureConflicts.contains(parent))continue;
   var parentTextures=catalog.requirementsFor(parent).stream().filter(r->r.kind().equals("texture")&&!Set.of("SIMULTANEOUS_OVERLAY","NAMED_SLOT").contains(r.role())).toList();
   if(parentTextures.stream().anyMatch(r->!r.status().equals("FOUND")))continue;
   Set<String> candidates=new TreeSet<>();parentTextures.forEach(r->candidates.addAll(r.candidates()));
   String texture=catalog.defaultTextures.get(parent);if(texture==null&&candidates.size()==1)texture=candidates.iterator().next();
   if(texture==null||candidates.size()!=1||!candidates.contains(texture))continue;
   if(!sameDeclaredIdentifier(pose,standing))continue;var signature=poseSignature(pose);if(signature==null||!signature.equals(poseSignature(standing)))continue;
   String evidence="pose_uv_identity:"+parent+"; same resource family, identical named cube size, mirror and UV";
   catalog.bundles.get(key).add(new ResourceCatalog.Edge(key,texture,evidence));catalog.defaultTextures.put(key,texture);
   catalog.requirements.add(new GenericResourceResolver.Requirement(key,"texture",texture,"FOUND",List.of(texture),evidence,"SINGLE"));
   var layers=catalog.layerPlans.get(parent);if(layers!=null&&!layers.isEmpty())catalog.layerPlans.put(key,layers.deepCopy());
   catalog.event(pose,"shared_pose_texture_resolved",evidence);
  }
 }
 private static JsonObject poseSignature(ResourceCatalog.Entry entry){
  try{
   var models=GeoFormats.json(entry.file()).getAsJsonObject().getAsJsonArray("minecraft:geometry");
   if(models==null||models.size()!=1)return null;
   var g=models.get(0).getAsJsonObject();var d=g.getAsJsonObject("description");if(!d.has("texture_width")||!d.has("texture_height"))return null;
   JsonObject result=new JsonObject(),bones=new JsonObject();result.add("width",d.get("texture_width"));result.add("height",d.get("texture_height"));result.add("bones",bones);
   for(var node:g.getAsJsonArray("bones")){
    var bone=node.getAsJsonObject();if(!bone.has("cubes")||bone.getAsJsonArray("cubes").isEmpty())continue;
    String name=bone.get("name").getAsString();if(bones.has(name)||bone.has("material_instance"))return null;
    JsonArray cubes=new JsonArray();bones.add(name,cubes);
    for(var value:bone.getAsJsonArray("cubes")){
     var cube=value.getAsJsonObject();if(!cube.has("size")||!cube.has("uv")||cube.has("material_instance"))return null;
     var uv=cube.get("uv");if(uv.isJsonObject()&&uv.getAsJsonObject().entrySet().stream().anyMatch(e->!e.getValue().isJsonObject()||e.getValue().getAsJsonObject().has("material_instance")))return null;
     JsonObject row=new JsonObject();row.add("size",cube.get("size"));row.add("uv",uv);row.addProperty("mirror",cube.has("mirror")?cube.get("mirror").getAsBoolean():bone.has("mirror")&&bone.get("mirror").getAsBoolean());cubes.add(row);
    }
   }
   return bones.size()==0?null:result;
  }catch(RuntimeException|java.io.IOException ex){return null;}
 }
 record Layout(String owner,String atlas,Map<String,String> cubes){}
 static Layout layout(ResourceCatalog.Entry entry){try{JsonObject json=GeoFormats.json(entry.file()).getAsJsonObject();JsonArray geometries=json.getAsJsonArray("minecraft:geometry");if(geometries==null||geometries.size()!=1)return null;JsonObject g=geometries.get(0).getAsJsonObject(),d=g.getAsJsonObject("description");if(d==null||!d.has("texture_width")||!d.has("texture_height"))return null;Map<String,String> cubes=new TreeMap<>();for(var bone:g.getAsJsonArray("bones")){JsonObject b=bone.getAsJsonObject();if(!b.has("cubes"))continue;int index=0;for(var value:b.getAsJsonArray("cubes")){JsonObject c=value.getAsJsonObject();JsonElement uv=c.get("uv");if(uv==null||!uv.isJsonObject()||uv.getAsJsonObject().size()!=6||c.has("material_instance"))return null;JsonObject normalized=new JsonObject();for(String face:List.of("north","south","east","west","up","down")){JsonObject f=uv.getAsJsonObject().getAsJsonObject(face);if(f==null||!f.has("uv")||!f.has("uv_size")||f.has("material_instance"))return null;normalized.add(face,f);}cubes.put(b.get("name").getAsString()+"#"+(index++),normalized.toString());}}return cubes.isEmpty()?null:new Layout(entry.owner(),d.get("texture_width")+"x"+d.get("texture_height"),Map.copyOf(cubes));}catch(Exception e){return null;}}
 static void analyze(ResourceCatalog catalog){Map<String,Layout> layouts=new HashMap<>();Map<String,ResourceCatalog.Entry> models=new HashMap<>();for(var files:catalog.entries.values())for(var e:files.values())if(e.kind().equals("geo")){String key=ResourceCatalog.key(e);models.put(key,e);Layout layout=layout(e);if(layout!=null)layouts.put(key,layout);}
 Map<String,List<String>> providers=new HashMap<>();for(var row:layouts.entrySet())if(catalog.defaultTextures.containsKey(row.getKey())&&!catalog.defaultTextureConflicts.contains(row.getKey()))providers.computeIfAbsent(row.getValue().owner()+"|"+row.getValue().atlas(),k->new ArrayList<>()).add(row.getKey());
 for(var row:layouts.entrySet()){String key=row.getKey();if(catalog.defaultTextures.containsKey(key)||catalog.defaultTextureConflicts.contains(key)||catalog.requirementsFor(key).stream().anyMatch(r->r.kind().equals("texture"))||catalog.bundles.getOrDefault(key,List.of()).stream().anyMatch(e->e.resource().endsWith(".png")))continue;Layout target=row.getValue();Map<String,List<String>> matches=new TreeMap<>();for(String source:providers.getOrDefault(target.owner()+"|"+target.atlas(),List.of())){Layout parent=layouts.get(source);if(!sameExportFamily(models.get(key),models.get(source))||!parent.cubes().entrySet().containsAll(target.cubes().entrySet()))continue;String texture=catalog.defaultTextures.get(source);matches.computeIfAbsent(texture,k->new ArrayList<>()).add(source);}if(matches.size()!=1)continue;var match=matches.entrySet().iterator().next();String evidence="uv_layout_identity:"+String.join(",",match.getValue())+"; all named cube face maps identical; cubes="+target.cubes().size();catalog.bundles.get(key).add(new ResourceCatalog.Edge(key,match.getKey(),evidence));catalog.defaultTextures.put(key,match.getKey());catalog.requirements.add(new GenericResourceResolver.Requirement(key,"texture",match.getKey(),"FOUND",List.of(match.getKey()),evidence,"SINGLE"));catalog.event(models.get(key),"shared_uv_texture_resolved",evidence);}
  analyzeExactCopies(catalog);
 }
 /**
  * Some mods ship the same Bedrock Geo twice: the renderer uses the copy under
  * geo/, while a models/block/ copy has no texture declaration. Exact parsed
  * content equality only verifies compatibility AFTER establishing an exact logical
  * export path alias in the same resource domain. Arbitrary identical cubes cannot choose a skin.
  */
 private record CopyPlan(String base,Set<String> semantics,Set<String> resources,JsonArray layers) {}
 private static void analyzeExactCopies(ResourceCatalog catalog){
  Map<String,List<ResourceCatalog.Entry>> copies=new TreeMap<>();
  for(var files:catalog.entries.values())for(var entry:files.values())if(entry.kind().equals("geo"))try{
   String address=ResourceEvidence.address(entry);
   if(address==null)continue;
   String namespace=address.substring(0,address.indexOf(':'));
   // Exact content, owner AND effective namespace; atlas size and names do not establish identity.
   String identity=entry.owner()+"|"+namespace+"|"+GeoImporter.hash(AnalysisInputs.bytes(entry.file()));
   copies.computeIfAbsent(identity,k->new ArrayList<>()).add(entry);
  }catch(Exception ignored){}
  for(var group:copies.values()){
   if(group.size()<2)continue;
   String provider=null;CopyPlan selected=null;boolean conflict=false;
   for(var source:group){
    String key=ResourceCatalog.key(source);
    if(!hasTextureClaims(catalog,key))continue;
    CopyPlan plan=copyPlan(catalog,key);
    if(plan==null||selected!=null&&!selected.equals(plan)){conflict=true;break;}
    if(selected==null){selected=plan;provider=key;}
   }
   if(conflict||selected==null)continue;
   // Freeze provider inputs; appending inherited requirements must not turn new copies into providers.
   var requirements=List.copyOf(catalog.requirementsFor(provider));
   var edges=List.copyOf(catalog.bundles.getOrDefault(provider,List.of()));
   for(var target:group){
    String key=ResourceCatalog.key(target);
    if(hasTextureClaims(catalog,key)||!sameExportFamily(target,entry(catalog,provider)))continue;
    String evidence="exact_geo_copy:"+provider+"; canonical_export_alias; byte_content_equal; unique_proven_binding_plan";
    Set<String> existing=new HashSet<>();catalog.bundles.get(key).forEach(e->existing.add(e.resource()));
    for(var edge:edges)if(existing.add(edge.resource()))
     catalog.bundles.get(key).add(new ResourceCatalog.Edge(key,edge.resource(),edge.evidence()+"; "+evidence));
    for(var requirement:requirements){
     // Keep the JSON pointer intact: NAMED_SLOT derives its material name from that pointer.
     String origin=requirement.evidence().replace("json:"+provider+"#","json:"+key+"#");
     var inherited=new GenericResourceResolver.Requirement(key,requirement.kind(),requirement.reference(),requirement.status(),requirement.candidates(),origin,requirement.role());
     if(!catalog.requirements.contains(inherited))catalog.requirements.add(inherited);
    }
    if(selected.base()!=null)catalog.defaultTextures.put(key,selected.base());
    if(!selected.layers().isEmpty())catalog.layerPlans.put(key,selected.layers().deepCopy());
    catalog.event(target,"exact_geo_copy_texture_resolved",evidence);
   }
  }
 }
 private static ResourceCatalog.Entry entry(ResourceCatalog c,String key){int colon=key.indexOf(':');return c.entries.getOrDefault(key.substring(0,colon),Map.of()).get(key.substring(colon+1));}
 /** geo/x and models/{block,entity,custom}/x are alternate exports of exactly the same logical path. */
 private static String exportPath(ResourceCatalog.Entry e){if(e==null)return null;String address=ResourceEvidence.address(e);if(address==null)return null;int colon=address.indexOf(':');String path=address.substring(colon+1);if(path.startsWith("models/")){path=path.substring(7);for(String folder:List.of("block/","entity/","custom/"))if(path.startsWith(folder)){path=path.substring(folder.length());break;}path="geo/"+path;}return address.substring(0,colon+1)+path;}
 private static boolean sameExportFamily(ResourceCatalog.Entry a,ResourceCatalog.Entry b){return a!=null&&b!=null&&!a.path().equals(b.path())&&a.owner().equals(b.owner())&&exportPath(a)!=null&&exportPath(a).equals(exportPath(b));}
 private static boolean sameDeclaredIdentifier(ResourceCatalog.Entry a,ResourceCatalog.Entry b){try{String x=GeoFormats.json(a.file()).getAsJsonObject().getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonObject("description").get("identifier").getAsString();String y=GeoFormats.json(b.file()).getAsJsonObject().getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonObject("description").get("identifier").getAsString();return x.equals(y)&&!Set.of("geometry.unknown","geometry.test","geometry.default").contains(x);}catch(Exception ex){return false;}}
 private static boolean hasTextureClaims(ResourceCatalog c,String key){
  return c.defaultTextures.containsKey(key)||c.defaultTextureConflicts.contains(key)
   ||!c.layerPlans.getOrDefault(key,new JsonArray()).isEmpty()
   ||c.requirementsFor(key).stream().anyMatch(r->r.kind().equals("texture"))
   ||c.bundles.getOrDefault(key,List.of()).stream().anyMatch(e->e.resource().endsWith(".png"));
 }
 private static CopyPlan copyPlan(ResourceCatalog c,String key){
  if(c.defaultTextureConflicts.contains(key))return null;
  Set<String> textures=new TreeSet<>(),overlays=new HashSet<>(),resources=new TreeSet<>(),semantics=new TreeSet<>();
  boolean proved=false;
  for(var req:c.requirementsFor(key)){
   if(!req.status().equals("FOUND")||req.candidates().isEmpty())return null;
   semantics.add(req.kind()+"|"+req.role()+"|"+req.slot()+"|"+new TreeSet<>(req.candidates()));
   if(req.kind().equals("texture")){
    proved=true;
    if(req.role().equals("SIMULTANEOUS_OVERLAY"))overlays.addAll(req.candidates());
   }
  }
  for(var edge:c.bundles.getOrDefault(key,List.of())){
   int colon=edge.resource().indexOf(':');if(colon<1)return null;
   var resource=c.entries.getOrDefault(edge.resource().substring(0,colon),Map.of()).get(edge.resource().substring(colon+1));
   if(resource==null)return null;
   resources.add(edge.resource());if(resource.kind().equals("texture"))textures.add(edge.resource());
  }
  if(!proved||textures.isEmpty())return null;
  for(var req:c.requirementsFor(key))if(!resources.containsAll(req.candidates()))return null;
  textures.removeAll(overlays);
  String base=c.defaultTextures.get(key);
  if(base==null&&textures.size()==1)base=textures.iterator().next();
  MaterialPlan material=MaterialPlan.read(c,key);
  if(!material.conflicts.isEmpty()||!material.confirmedSlots().keySet().containsAll(material.usedSlots()))return null;
  // Unselected skins without a default have no unique executable base to inherit.
  if(base==null&&!material.hasSelectors()||base!=null&&!textures.contains(base))return null;
  semantics.add("materials|"+material.confirmedSlots());
  return new CopyPlan(base,Set.copyOf(semantics),Set.copyOf(resources),c.layerPlans.getOrDefault(key,new JsonArray()));
 }
}
