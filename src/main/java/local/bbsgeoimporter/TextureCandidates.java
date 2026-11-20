package local.bbsgeoimporter;
import com.google.gson.*;
import java.util.*;
/** Diagnostic suggestions only: similarity never grants resource ownership or a binding. */
final class TextureCandidates {
    private static String stem(String path){String name=path.substring(path.lastIndexOf('/')+1).toLowerCase(Locale.ROOT);return name.replaceFirst("[.]geo[.]json$|[.]png$","");}
    private static Set<String> tokens(String value){Set<String> result=new TreeSet<>(Arrays.asList(value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")));result.removeIf(s->s.length()<3);return result;}
    private static void collectIdentifiers(JsonElement value,Set<String> result){if(value.isJsonObject()){for(var entry:value.getAsJsonObject().entrySet()){if(entry.getKey().equals("identifier")&&entry.getValue().isJsonPrimitive()&&entry.getValue().getAsJsonPrimitive().isString())result.add(entry.getValue().getAsString());collectIdentifiers(entry.getValue(),result);}}else if(value.isJsonArray())for(var child:value.getAsJsonArray())collectIdentifiers(child,result);}
    static void append(ResourceCatalog catalog){
        JsonArray rows=new JsonArray();
        for(var files:catalog.entries.values()){
            Map<String,Set<ResourceCatalog.Entry>> textureTokens=new HashMap<>();
            for(var texture:files.values())if(texture.kind().equals("texture"))for(String token:tokens(stem(texture.path())))textureTokens.computeIfAbsent(token,k->new LinkedHashSet<>()).add(texture);
            for(var model:files.values())if(model.kind().equals("geo")){
            List<JsonObject> candidates=new ArrayList<>();String name=stem(model.path());Set<String> words=tokens(name),ids=new TreeSet<>();
            try{collectIdentifiers(JsonParser.parseString(AnalysisInputs.text(model.file())),ids);}catch(Exception ignored){/* Catalog already reports parse errors. */}
            for(String id:ids)words.addAll(tokens(id));
            Set<ResourceCatalog.Entry> possible=new LinkedHashSet<>();for(String word:words)possible.addAll(textureTokens.getOrDefault(word,Set.of()));
            for(var png:possible){
                String texture=stem(png.path());Set<String> common=tokens(texture);common.retainAll(words);boolean exact=name.equals(texture);
                boolean identifier=ids.stream().anyMatch(id->id.equalsIgnoreCase(texture)||id.substring(id.lastIndexOf(':')+1).equalsIgnoreCase(texture));
                if(!exact&&!identifier&&common.isEmpty())continue;
                JsonObject row=new JsonObject();row.addProperty("resource",ResourceCatalog.key(png));row.addProperty("status","CANDIDATE");row.addProperty("score",exact?100:identifier?80:Math.min(70,common.size()*10));row.addProperty("reason",exact?"EXACT_BASENAME_NOT_REFERENCE":identifier?"IDENTIFIER_NOT_REFERENCE":"SHARED_NAME_TOKENS:"+String.join(",",common));row.addProperty("autoBind",false);candidates.add(row);
            }
            candidates.sort(Comparator.comparingInt((JsonObject r)->r.get("score").getAsInt()).reversed().thenComparing(r->r.get("resource").getAsString()));
            JsonObject row=new JsonObject();row.addProperty("model",ResourceCatalog.key(model));JsonArray values=new JsonArray();candidates.forEach(values::add);row.add("candidates",values);row.addProperty("evidencePolicy","DIAGNOSTIC_ONLY_NOT_CONFIRMED");rows.add(row);
            }
        }
        catalog.report.add("textureCandidates",rows);
    }
}
