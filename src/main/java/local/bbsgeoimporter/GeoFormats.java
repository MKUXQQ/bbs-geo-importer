package local.bbsgeoimporter;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Structural Bedrock Geo and animation recognition independent of resource filenames. */
final class GeoFormats {
    private GeoFormats() {}
    static String classify(String path, Path file) {
        return SourceCensus.kind(path,file,()->classifyUncached(path,file));
    }
    private static String classifyUncached(String path,Path file){
        String lower=path.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".geo.json"))return "geo";
        if(lower.endsWith(".animation.json"))return "animation";
        if(!lower.endsWith(".json"))return "other";
        try {
            String text=AnalysisInputs.text(file);
            if(!text.contains("minecraft:geometry")&&!text.contains("geometry.")&&!text.contains("animations")&&!text.contains("\\u"))return "other";
            JsonElement root=JsonParser.parseString(text);
            if(!root.isJsonObject())return "other";
            JsonObject object=root.getAsJsonObject();
            if(isGeo(object))return "geo";
            JsonElement animations=object.get("animations");
            if(animations!=null&&animations.isJsonObject()&&animations.getAsJsonObject().size()>0)return "animation";
        } catch(IOException ex) {
            SourceCensus.readFailed(file);
        } catch(RuntimeException ignored) {
            // Malformed resources are diagnosed when referenced; unrelated JSON remains indexable.
        }
        return "other";
    }
    static boolean isGeo(JsonObject object) {
        if(legacy(object))return true;
        JsonElement geometries=object.get("minecraft:geometry");
        if(geometries==null||!geometries.isJsonArray()||geometries.getAsJsonArray().isEmpty())return false;
        for(JsonElement element:geometries.getAsJsonArray()) {
            if(!element.isJsonObject())return false;
            JsonObject geometry=element.getAsJsonObject();
            if(!geometry.has("description")||!geometry.get("description").isJsonObject()||!geometry.has("bones")||!geometry.get("bones").isJsonArray())return false;
        }
        return true;
    }
    private static boolean legacy(JsonObject object){
        return object.entrySet().stream().anyMatch(e->e.getKey().startsWith("geometry.")&&e.getValue().isJsonObject());
    }
    /** Translate only the documented legacy Bedrock geometry envelope; cube data stays intact. */
    static JsonObject normalize(JsonObject source){
        if(source.has("minecraft:geometry")||!legacy(source))return source;
        JsonObject result=source.deepCopy();JsonArray geometries=new JsonArray();
        for(var item:source.entrySet())if(item.getKey().startsWith("geometry.")&&item.getValue().isJsonObject()){
            JsonObject geometry=item.getValue().getAsJsonObject().deepCopy(),description=new JsonObject();
            if(!geometry.has("bones")||!geometry.get("bones").isJsonArray())throw new IllegalArgumentException("Unsupported legacy geometry without bones: "+item.getKey());
            description.addProperty("identifier",item.getKey());
            for(String[] mapping:new String[][]{{"texturewidth","texture_width"},{"textureheight","texture_height"},{"texture_width","texture_width"},{"texture_height","texture_height"},{"visible_bounds_width","visible_bounds_width"},{"visible_bounds_height","visible_bounds_height"},{"visible_bounds_offset","visible_bounds_offset"}}){
                JsonElement value=geometry.remove(mapping[0]);if(value==null)value=source.get(mapping[0]);if(value!=null)description.add(mapping[1],value.deepCopy());
            }
            geometry.add("description",description);geometries.add(geometry);result.remove(item.getKey());
        }
        result.addProperty("format_version","1.12.0");result.add("minecraft:geometry",geometries);return result;
    }
    static JsonElement json(Path file)throws IOException{
        JsonElement value=JsonParser.parseString(AnalysisInputs.text(file));return value.isJsonObject()?normalize(value.getAsJsonObject()):value;
    }
    static byte[] normalize(byte[] bytes)throws IOException{
        try{JsonObject object=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();JsonObject normalized=normalize(object);return normalized==object?bytes:normalized.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);}
        catch(RuntimeException ex){throw new IOException("Unsupported or invalid Geo JSON",ex);}
    }
    static List<String> validateAnimations(byte[] bytes)throws IOException {
        try {
            JsonObject root=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject animations=root.getAsJsonObject("animations");
            if(animations==null)throw new IOException("Missing animations object");
            List<String> issues=new ArrayList<>();
            for(var entry:animations.entrySet()) {
                if(!entry.getValue().isJsonObject()){issues.add(entry.getKey()+": animation entry is not an object");continue;}
                JsonObject animation=entry.getValue().getAsJsonObject();
                for(String field:List.of("animation_length","loop","override_previous_animation","blend_weight","start_delay","loop_delay","anim_time_update","timeline"))
                    if(animation.has(field)&&!animation.get(field).isJsonPrimitive()&&!animation.get(field).isJsonObject())issues.add(entry.getKey()+": unsupported "+field+" shape");
                JsonElement bones=animation.get("bones");
                if(bones!=null&&!bones.isJsonObject())issues.add(entry.getKey()+": bones must be an object");
                else if(bones!=null)for(var bone:bones.getAsJsonObject().entrySet()) {
                    if(!bone.getValue().isJsonObject())issues.add(entry.getKey()+": bone track "+bone.getKey()+" is not an object");
                    else for(String channel:bone.getValue().getAsJsonObject().keySet())if(!Set.of("position","rotation","scale").contains(channel))issues.add(entry.getKey()+": unsupported bone channel "+channel);
                }
            }
            return issues;
        } catch(RuntimeException ex){throw new IOException("Invalid animation JSON",ex);}
    }
}
