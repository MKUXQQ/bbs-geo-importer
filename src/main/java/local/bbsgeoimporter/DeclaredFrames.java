package local.bbsgeoimporter;

import com.google.gson.*;
import java.util.*;

/** Frame positions belong to the parent texture declaration, not independent selectable skins. */
final class DeclaredFrames {
    static final String BASE="BASE_SEQUENCE_FIRST", SKIN="SKIN_SEQUENCE_FIRST", MEMBER="SEQUENCE_FRAME";
    static boolean object(JsonElement v){return v!=null&&v.isJsonObject()&&v.getAsJsonObject().has("frames");}
    static boolean role(String role){return BASE.equals(role)||SKIN.equals(role)||MEMBER.equals(role);}
    interface Consumer {void accept(String ref,String evidence,String role,boolean valid);}
    static void read(JsonElement value,String evidence,String use,Consumer consume){
        JsonObject declaration=value.getAsJsonObject();JsonElement frames=declaration.get("frames");
        if(frames==null||!frames.isJsonArray()||frames.getAsJsonArray().isEmpty()){
            consume.accept("<empty or invalid frames>",evidence+"/frames",use,false);return;
        }
        int i=0;for(var f:frames.getAsJsonArray()){
            boolean valid=f.isJsonPrimitive()&&f.getAsJsonPrimitive().isString()&&!f.getAsString().isBlank();
            consume.accept(valid?f.getAsString():"<invalid frame>",evidence+"/frames/"+i,
                i==0?use:use.equals("SIMULTANEOUS_OVERLAY")?use:MEMBER,valid);i++;
        }
    }
    static boolean firstSkin(String role){return role.equals("SKIN_VARIANT")||role.equals(SKIN);}
    static JsonArray plan(List<GenericResourceResolver.Requirement> reqs){
        var sequences=new JsonArray();for(var r:reqs)if(role(r.role())){
            JsonObject f=new JsonObject();f.addProperty("reference",r.reference());f.addProperty("position",r.evidence());
            f.addProperty("role",r.role());f.addProperty("status",r.status());
            if(r.candidates().size()==1)f.addProperty("resource",r.candidates().get(0));sequences.add(f);
        }return sequences;
    }
}
