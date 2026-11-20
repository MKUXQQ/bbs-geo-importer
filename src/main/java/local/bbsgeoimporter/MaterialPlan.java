package local.bbsgeoimporter;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Only explicit part/face selectors prove simultaneous materials. Texture lists alone do not. */
final class MaterialPlan {
    record Part(String bone,int cube,String slot,List<String> faces) {}
    final List<Part> parts=new ArrayList<>();
    final Map<String,String> slots=new TreeMap<>();
    final Set<String> conflicts=new TreeSet<>();
    final List<String> unsupported=new ArrayList<>();
    JsonObject geometry;
    int geometryCount;
    static final Map<String,String> FACES=Map.of("north","front","east","right","south","back","west","left","up","top","down","bottom");
    static MaterialPlan read(ResourceCatalog catalog,String key){
        MaterialPlan p=new MaterialPlan();
        int colon=key.indexOf(':');var entry=catalog.entries.getOrDefault(key.substring(0,colon),Map.of()).get(key.substring(colon+1));
        if(entry==null)return p;
        try{
            JsonObject root=GeoFormats.json(entry.file()).getAsJsonObject();
            JsonArray gs=root.getAsJsonArray("minecraft:geometry");p.geometryCount=gs==null?0:gs.size();
            if(gs!=null&&gs.size()>0)p.geometry=gs.get(0).getAsJsonObject();
            for(var req:catalog.requirementsFor(key))if(req.kind().equals("texture")&&req.role().equals("NAMED_SLOT")&&!req.slot().isEmpty()){
                String slot=req.slot();if(req.status().equals("FOUND")){String old=p.slots.putIfAbsent(slot,req.candidates().get(0));if(old!=null&&!old.equals(req.candidates().get(0)))p.conflicts.add(slot);}
            }
            if(p.geometry!=null&&p.geometry.has("bones"))for(var b:p.geometry.getAsJsonArray("bones")){
                JsonObject bone=b.getAsJsonObject();if(!bone.has("cubes"))continue;
                String name=bone.get("name").getAsString();int index=0;
                for(var c:bone.getAsJsonArray("cubes")){
                    JsonObject cube=c.getAsJsonObject();String inherited=selector(cube,selector(bone,selector(p.geometry,"")));
                    Map<String,List<String>> faceSlots=new LinkedHashMap<>();
                    JsonElement uv=cube.get("uv");
                    if(uv!=null&&uv.isJsonObject())for(String face:new TreeSet<>(FACES.keySet()))if(uv.getAsJsonObject().has(face)){
                        JsonElement side=uv.getAsJsonObject().get(face);String slot=side.isJsonObject()?selector(side.getAsJsonObject(),inherited):inherited;
                        faceSlots.computeIfAbsent(slot,k->new ArrayList<>()).add(face);
                    }
                    if(faceSlots.isEmpty())faceSlots.put(inherited,new ArrayList<>(FACES.keySet()));
                    for(var group:faceSlots.entrySet())p.parts.add(new Part(name,index,group.getKey(),List.copyOf(group.getValue())));
                    index++;
                }
            }
            detectUnsupported(root,p.unsupported,"$");
        }catch(Exception ex){p.unsupported.add("INVALID_GEOMETRY: "+ex.getMessage());}
        return p;
    }
    private static String selector(JsonObject o,String fallback){for(String k:List.of("material_instance","material","texture"))if(o.has(k)&&o.get(k).isJsonPrimitive()){String s=o.get(k).getAsString();return s.startsWith("#")?s.substring(1):s;}return fallback;}
    private static void detectUnsupported(JsonElement e,List<String> out,String path){
        if(e.isJsonObject())for(var x:e.getAsJsonObject().entrySet()){
            if(List.of("render_controllers","poly_mesh","texture_meshes","shaders").contains(x.getKey()))out.add("UNSUPPORTED_"+x.getKey()+" at "+path);
            detectUnsupported(x.getValue(),out,path+"/"+x.getKey());
        }else if(e.isJsonArray())for(var x:e.getAsJsonArray())detectUnsupported(x,out,path+"[]");
    }
    boolean hasSelectors(){return parts.stream().anyMatch(p->!p.slot().isEmpty());}
    Set<String> usedSlots(){Set<String> result=new TreeSet<>();for(var part:parts)if(!part.slot().isEmpty())result.add(part.slot());return result;}
    Map<String,String> confirmedSlots(){Map<String,String> result=new TreeMap<>();for(String slot:usedSlots())if(slots.containsKey(slot)&&!conflicts.contains(slot))result.put(slot,slots.get(slot));return result;}
    /** Split cubes by face selector, keeping transforms/UV intact. Runtime removes omitted faces explicitly. */
    JsonObject export(JsonObject root){
        JsonObject result=root.deepCopy();JsonArray gs=result.getAsJsonArray("minecraft:geometry");if(gs==null||gs.size()==0)return result;
        JsonObject g=gs.get(0).getAsJsonObject();if(!g.has("bones"))return result;
        for(var b:g.getAsJsonArray("bones")){
            JsonObject bone=b.getAsJsonObject();if(!bone.has("cubes"))continue;
            JsonArray cubes=new JsonArray();String name=bone.get("name").getAsString();int index=0;
            for(var c:bone.getAsJsonArray("cubes")){
                final int i=index++;List<Part> matches=parts.stream().filter(p->p.bone().equals(name)&&p.cube()==i).toList();
                if(matches.size()<2){cubes.add(c.deepCopy());continue;}
                for(var part:matches){JsonObject copy=c.getAsJsonObject().deepCopy();JsonObject uv=copy.getAsJsonObject("uv");for(String face:FACES.keySet())if(!part.faces().contains(face))uv.remove(face);cubes.add(copy);}
            }
            bone.add("cubes",cubes);
        }
        return result;
    }
    List<Part> exportedParts(){List<Part> result=new ArrayList<>();Map<String,Integer> indices=new HashMap<>();for(var p:parts){int i=indices.getOrDefault(p.bone(),0);result.add(new Part(p.bone(),i,p.slot(),p.faces()));indices.put(p.bone(),i+1);}return List.copyOf(result);}
}
