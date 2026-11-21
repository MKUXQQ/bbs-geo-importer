package local.bbsgeoimporter;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Last-resort display evidence for unused exports; never override a declared or unresolved dependency. */
final class AtlasDisplayResolver {
    private record Source(ResourceCatalog.Entry geo,String texture) {}
    static void analyze(ResourceCatalog c){
        Set<String> hasConsumer=new HashSet<>();
        for(var v:c.report.getAsJsonArray("events")){
            var e=v.getAsJsonObject();String status=e.get("status").getAsString();
            if(status.equals("static_texture_unknown")||status.equals("model_reference_sites")&&!e.get("reason").getAsString().startsWith("No exact incoming"))hasConsumer.add(e.get("source").getAsString());
        }
        for(var files:c.entries.values()){
            Map<String,List<Source>> families=new HashMap<>();Map<String,Set<String>> mirrors=new HashMap<>();
            for(var geo:files.values())if(geo.kind().equals("geo")){
                String key=ResourceCatalog.key(geo),base=c.defaultTextures.get(key);
                if(base==null||c.defaultTextureConflicts.contains(key))continue;
                String path=base.substring(base.indexOf(':')+1);
                if(!path.startsWith(namespace(geo.path())+"/textures/")||!SharedModelAssets.displayEligible(path))continue;
                families.computeIfAbsent(family(geo.path()),k->new ArrayList<>()).add(new Source(geo,base));
                if(stem(geo.path()).equals(stem(path)))mirrors.computeIfAbsent(parent(geo.path()),k->new TreeSet<>()).add(parent(path));
            }
            for(var geo:files.values())if(geo.kind().equals("geo")){
                String key=ResourceCatalog.key(geo);
                if(hasConsumer.contains(key)||c.defaultTextureConflicts.contains(key)||c.defaultTextures.containsKey(key)||c.requirementsFor(key).stream().anyMatch(r->r.kind().equals("texture"))||
                    c.bundles.get(key).stream().anyMatch(e->e.resource().endsWith(".png"))||c.layerPlans.containsKey(key))continue;
                try{
                    var gs=GeoFormats.json(geo.file()).getAsJsonObject().getAsJsonArray("minecraft:geometry");if(gs==null||gs.size()!=1)continue;
                    var geometry=gs.get(0).getAsJsonObject();var d=geometry.getAsJsonObject("description");int w=d.get("texture_width").getAsInt(),h=d.get("texture_height").getAsInt();
                    if(w<=0||h<=0||MaterialPlan.read(c,key).hasSelectors())continue;
                    Set<String> exact=new TreeSet<>();String proof=null;String topology=null;JsonArray inheritedLayers=new JsonArray();
                    for(var source:families.getOrDefault(family(geo.path()),List.of())){
                        if(topology==null)topology=topology(geometry);
                        if(!topology.isEmpty()&&topology.equals(topology(GeoFormats.json(source.geo().file()).getAsJsonObject().getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()))){exact.add(source.texture());proof="same export family and complete cube UV multiset: "+source.geo().path();
                            for(var layer:c.layerPlans.getOrDefault(ResourceCatalog.key(source.geo()),new JsonArray()))if(!inheritedLayers.contains(layer))inheritedLayers.add(layer.deepCopy());}
                    }
                    if(exact.size()>1)continue;
                    if(exact.isEmpty()){
                        String dir=parent(geo.path()),ns=namespace(geo.path()),marker=ns+"/geo";
                        if(!dir.startsWith(marker+"/")&&!dir.equals(marker))continue;
                        String role=dir.substring(marker.length());Set<String> dirs=new TreeSet<>(mirrors.getOrDefault(dir,Set.of()));
                        if(!role.isEmpty())dirs.add(ns+"/textures"+role);
                        boolean entityId=d.has("identifier")&&d.get("identifier").getAsString().equals("geometry."+ns.substring("assets/".length())+":"+stem(geo.path()));
                        if(role.isEmpty()&&entityId)dirs.add(ns+"/textures/entity");
                        for(String candidate:dirs){
                            if(candidate.matches(".*/(?:item|gui|screens|icons)(?:/.*)?"))continue;
                            String name=stem(geo.path());for(String path:role.isEmpty()&&!mirrors.getOrDefault(dir,Set.of()).contains(candidate)?List.of(candidate+"/"+name+"/"+name+".png"):List.of(candidate+"/"+name+".png",candidate+"/"+name+"/"+name+".png")){
                                var png=files.get(path);if(png!=null&&SharedModelAssets.displayEligible(path)&&matchesAtlas(png,w,h)&&c.uniqueResource(ResourceEvidence.address(png),"texture").filter(png::equals).isPresent())exact.add(ResourceCatalog.key(png));}
                        }
                        proof="same namespace, mirrored resource directory, exact stem and compatible "+w+"x"+h+" atlas";
                        if(exact.isEmpty()&&role.isEmpty()){
                            String name=stem(geo.path());var descriptor=files.get(ns+"/models/item/"+name+".json");var png=files.get(ns+"/textures/item/"+name+".png");
                            if(descriptor!=null&&png!=null&&SharedModelAssets.displayEligible(png.path())&&c.uniqueResource(ResourceEvidence.address(png),"texture").filter(png::equals).isPresent()&&matchesAtlas(png,w,h)&&builtinItemAtlas(descriptor,geometry,w,h)){
                                exact.add(ResourceCatalog.key(png));proof="DISPLAY_ONLY: "+descriptor.path()+" parent=builtin/entity, matching texture_size and same-stem item atlas, per-face UV rectangles inside declared bounds";
                            }
                        }
                        if(exact.isEmpty()&&mirrors.getOrDefault(dir,Set.of()).size()==1){
                            String prefix=mirrors.get(dir).iterator().next()+"/"+stem(geo.path())+"_";
                            // A consecutive numbered family in a role directory proven by sibling getters.
                            // This is a static atlas display, not evidence of the original playback rate.
                            if(SharedModelAssets.displayEligible(prefix+"0.png")){
                                List<ResourceCatalog.Entry> frames=new ArrayList<>();for(int i=0;i<=64;i++){var f=files.get(prefix+i+".png");if(f==null)break;
                                    if(!matchesAtlas(f,w,h)||!c.uniqueResource(ResourceEvidence.address(f),"texture").filter(f::equals).isPresent()){frames.clear();break;}frames.add(f);}
                                if(frames.size()>=2&&frames.size()<=64)for(var f:frames)c.dependency(geo,"texture",ResourceEvidence.address(f),"FOUND",List.of(f),"mirror_atlas:"+proof+"; numbered frame family","DISPLAY_FRAME");
                            }
                        }
                    }
                    if(exact.size()==1){
                        String png=exact.iterator().next(),evidence="mirror_atlas:"+proof;
                        c.bundles.get(key).add(new ResourceCatalog.Edge(key,png,evidence));
                        c.requirements.add(new GenericResourceResolver.Requirement(key,"texture",png,"FOUND",List.of(png),evidence,"DISPLAY_ATLAS"));
                        if(!inheritedLayers.isEmpty())c.layerPlans.put(key,inheritedLayers);
                    }
                }catch(Exception ignored){/* malformed geometry/atlas never establishes a display association */}
            }
        }
    }
    private static String topology(JsonObject g){
        var d=g.getAsJsonObject("description");List<String> parts=new ArrayList<>();
        for(var b:g.getAsJsonArray("bones")){var bone=b.getAsJsonObject();if(!bone.has("cubes"))continue;
            for(var row:bone.getAsJsonArray("cubes")){var cube=row.getAsJsonObject();if(!cube.has("uv")||!cube.has("size")||cube.has("material_instance")||bone.has("material_instance"))return "";
                var uv=cube.get("uv");if(uv.isJsonObject()){var sorted=new TreeMap<String,JsonElement>();uv.getAsJsonObject().entrySet().forEach(e->sorted.put(e.getKey(),e.getValue()));uv=new Gson().toJsonTree(sorted);}
                parts.add(cube.get("size")+"|"+uv+"|"+(cube.has("mirror")?cube.get("mirror").getAsBoolean():bone.has("mirror")&&bone.get("mirror").getAsBoolean()));}}
        Collections.sort(parts);return parts.isEmpty()?"":d.get("texture_width")+"x"+d.get("texture_height")+parts;
    }
    private static boolean matchesAtlas(ResourceCatalog.Entry png,int w,int h)throws Exception{
        try(var in=Files.newInputStream(png.file());var image=ImageIO.createImageInputStream(in)){var readers=ImageIO.getImageReaders(image);if(!readers.hasNext())return false;
            var reader=readers.next();try{reader.setInput(image);int x=reader.getWidth(0),y=reader.getHeight(0);return x%w==0&&y%h==0&&x/w==y/h;}finally{reader.dispose();}}
    }
    private static boolean builtinItemAtlas(ResourceCatalog.Entry descriptor,JsonObject geo,int w,int h)throws Exception{
        JsonObject item=GeoFormats.json(descriptor.file()).getAsJsonObject();
        if(!item.has("parent")||!Set.of("builtin/entity","minecraft:builtin/entity").contains(item.get("parent").getAsString())||item.has("elements")||item.has("overrides")||item.has("textures"))return false;
        JsonArray declared=item.getAsJsonArray("texture_size");if(declared==null||declared.size()!=2||declared.get(0).getAsDouble()!=w||declared.get(1).getAsDouble()!=h)return false;
        Set<String> regions=new HashSet<>();int cubes=0;
        for(var b:geo.getAsJsonArray("bones")){var bone=b.getAsJsonObject();if(!bone.has("cubes"))continue;
            for(var c:bone.getAsJsonArray("cubes")){var uv=c.getAsJsonObject().get("uv");if(uv==null||!uv.isJsonObject()||uv.getAsJsonObject().size()!=6)return false;cubes++;
                for(String side:MaterialPlan.FACES.keySet()){var face=uv.getAsJsonObject().getAsJsonObject(side);if(face==null)return false;
                    var pos=face.getAsJsonArray("uv");var size=face.getAsJsonArray("uv_size");if(pos==null||size==null||pos.size()!=2||size.size()!=2)return false;
                    for(int i=0;i<2;i++){double from=pos.get(i).getAsDouble(),to=from+size.get(i).getAsDouble(),max=i==0?w:h;if(!Double.isFinite(from)||!Double.isFinite(to)||Math.min(from,to)<-1e-4||Math.max(from,to)>max+1e-4)return false;}
                    regions.add(pos+"|"+size);
                }
            }}
        return cubes>0&&regions.size()>1;
    }
    private static String stem(String path){return path.substring(path.lastIndexOf('/')+1).replaceFirst("(\\.geo\\.json|\\.png)$","");}
    private static String namespace(String p){int split=p.indexOf('/',7);return split<0?"":p.substring(0,split);}
    private static String parent(String p){return p.substring(0,p.lastIndexOf('/'));}
    private static String family(String p){return namespace(p)+"/"+stem(p);}
}
