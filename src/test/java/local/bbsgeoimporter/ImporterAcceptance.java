package local.bbsgeoimporter;
import com.google.gson.*;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
public final class ImporterAcceptance {
    static int checks;
    static void check(boolean ok,String message) { checks++; if(!ok) throw new AssertionError(message); }
    static final String GEO = """
            {"minecraft:geometry":[{"description":{"identifier":"geometry.test"},"bones":[]}]}
            """.trim();
    static GeoImporter.ModSource source(String id,String name,Path... roots) { return new GeoImporter.ModSource(id,name,"1",List.of(roots)); }
    static void put(Path p,String content) throws Exception { Files.createDirectories(p.getParent()); Files.writeString(p,content); }
    static Path target(Path config,String id,String rel) throws Exception {
        JsonObject mod=JsonParser.parseString(Files.readString(config.resolve("bbs/model-import-index.json"))).getAsJsonObject().getAsJsonObject("mods").getAsJsonObject(id);
        return config.resolve("bbs/assets/models").resolve(mod.get("directory").getAsString()).resolve(mod.getAsJsonObject("files").getAsJsonObject(rel).get("target").getAsString());
    }
    static void png(Path p,int color) throws Exception {
        Files.createDirectories(p.getParent()); var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0,0,color); try(var out=Files.newOutputStream(p)){javax.imageio.ImageIO.write(image,"png",out);}
    }
    static JsonObject json(Path p) throws Exception {return JsonParser.parseString(Files.readString(p)).getAsJsonObject();}
    static long count(Path p,String suffix) throws Exception {try(var walk=Files.walk(p)) {return walk.filter(x->x.toString().endsWith(suffix)).count();}}
    static long countCanonical(Path p,String suffix) throws Exception {try(var walk=Files.walk(p)) {return walk.filter(x->x.toString().endsWith(suffix)).filter(x->!x.toString().contains("/skins/")&&!x.toString().contains("\\skins\\")).count();}}
    static void resourceChecks(Path base) throws Exception {
        Path a=base.resolve("a"),b=base.resolve("b"),config=base.resolve("config");
        String rel="assets/foreign/geo/deep/one.geo.json"; put(a.resolve(rel),GEO);
        png(b.resolve("assets/shared/textures/one.png"),0xff123456);
        png(b.resolve("assets/shared/textures/two.png"),0xffabcdef);
        put(a.resolve("assets/foreign/animations/walk.animation.json"),"{\"animations\":{\"walk\":{\"loop\":true}}}");
        put(a.resolve("assets/foreign/definitions/one.json"),"{\"model\":\"foreign:geo/deep/one.geo.json\",\"textures\":[\"shared:textures/one.png\",\"shared:textures/two.png\"],\"animations\":[\"foreign:animations/walk.animation.json\"]}");
        var sources=List.of(source("owner_a","Model Owner",a),source("owner_b","Textures",b));
        GeoImporter importer=new GeoImporter(config,s->{}); check(importer.scan(sources).imported()==1,"cross-owner first Geo");
        Path out=target(config,"owner_a",rel), mod=config.resolve("bbs/assets/models/Model Owner");
        check(count(config.resolve("bbs/assets/models/Textures"),".png")==2&&count(mod,".png")==0,"two explicit textures stored under provider without guessing variants");
        check(SharedModelAssets.bindingsForTest().values().stream().allMatch(x->x.texture()==null),"ambiguous base has no arbitrary texture");
        check(count(mod,".geo.json")==1,"one canonical geometry");
        check(count(mod,".animation.json")==1,"one shared canonical animation");
        JsonObject report=json(config.resolve("bbs/model-import-report.json"));
        check(report.getAsJsonArray("associations").size()==3,"explicit cross-Mod associations reported");
        check(report.getAsJsonObject("summary").get("discoveredGeo").getAsInt()==1,"report summary");
        check(!report.get("runtimeRenderingVerified").getAsBoolean(),"no runtime assertion");
        List<Path> textures; try(var walk=Files.walk(config.resolve("bbs/assets/models/Textures"))) {textures=walk.filter(p->p.getFileName().toString().equals("two.png")).toList();}
        Path edited=textures.get(0); Files.writeString(edited,"user texture"); importer.scan(sources);
        check(Files.readString(edited).equals("user texture"),"variant user texture protected");
        check(json(config.resolve("bbs/model-import-report.json")).toString().contains("user_conflict"),"dependency conflict visible");
        put(a.resolve("assets/foreign/definitions/one.json"),"{\"model\":\"foreign:geo/deep/one.geo.json\",\"texture\":\"shared:textures/one.png\"}");
        importer.scan(sources); check(SharedModelAssets.bindingsForTest().values().stream().anyMatch(x->x.texture()!=null),"unique confirmed default texture bridge");
        byte[] old=Files.readAllBytes(config.resolve("bbs/assets/models/Textures/animations/imported-resources/owner_b/assets/shared/textures/one.png")); Files.writeString(b.resolve("assets/shared/textures/one.png"),"bad png"); importer.scan(sources);
        check(Arrays.equals(old,Files.readAllBytes(config.resolve("bbs/assets/models/Textures/animations/imported-resources/owner_b/assets/shared/textures/one.png"))),"malformed PNG keeps old data");
        check(json(config.resolve("bbs/model-import-report.json")).toString().contains("dependency_failed"),"PNG error isolated");
        Path index=config.resolve("bbs/model-import-index.json"); JsonObject state=json(index);state.addProperty("schemaVersion",1);Files.writeString(index,state.toString()); importer.scan(sources);
        check(json(index).get("schemaVersion").getAsInt()==4,"index migrates to schema4");
        check(Files.exists(config.resolve("bbs/model-import-index.v1.backup.json")),"schema1 backup");
        check(target(config,"owner_a",rel).equals(out),"migration keeps bindings");
        Path extra=base.resolve("extra"); put(extra.resolve("assets/new/extra.geo.json"),GEO);
        check(importer.scan(List.of(source("same","Same",a),source("same","Same",extra))).discovered()==2,"same ID roots merged");
        Path bad=base.resolve("bad"),good=base.resolve("good");put(bad.resolve("x.geo.json"),"{");put(good.resolve("x.geo.json"),GEO);
        var isolated=new GeoImporter(base.resolve("isolated"),s->{}).scan(List.of(source("bad","Bad",bad),source("good","Good",good)));
        check(isolated.failed()==1 && isolated.imported()==1,"one broken Mod does not block another");
        Path d1=base.resolve("d1"),d2=base.resolve("d2"),d3=base.resolve("d3");put(d1.resolve(rel),GEO);put(d2.resolve(rel),GEO+" ");put(d3.resolve(rel),GEO);
        var catalog=new ResourceCatalog(List.of(source("three","Three",d1,d2,d3)));
        check(!catalog.entries.get("three").containsKey(rel),"third root cannot restore conflicted path");
        put(a.resolve("assets/foreign/definitions/ambiguous.json"),"{\"model\":[\"foreign:geo/deep/one.geo.json\",\"new:extra.geo.json\"],\"texture\":\"shared:textures/two.png\"}");
        catalog=new ResourceCatalog(List.of(source("owner_a","Owner",a,extra),source("owner_b","Textures",b)));
        check(catalog.report.toString().contains("ambiguous_definition"),"multi-Geo group not guessed");
        put(a.resolve("assets/foreign/definitions/non_geo.json"),"{\"model\":\"foreign:block/ordinary\"}");
        put(a.resolve("assets/foreign/definitions/missing_geo.json"),"{\"model\":\"foreign:geo/missing.geo.json\"}");
        catalog=new ResourceCatalog(List.of(source("owner_a","Owner",a)));
        boolean unsupported=false,missing=false;
        for(var event:catalog.report.getAsJsonArray("events")) {
            var e=event.getAsJsonObject();
            if(e.get("reason").getAsString().equals("geo:foreign:block/ordinary")) unsupported=e.get("status").getAsString().equals("unsupported_reference");
            if(e.get("reason").getAsString().equals("geo:foreign:geo/missing.geo.json")) missing=e.get("status").getAsString().equals("missing_reference");
        }
        check(!unsupported,"ordinary block model reference is excluded from Geo diagnostics");
        check(missing,"explicit missing Geo reference remains reported");
        Path priority=base.resolve("priority"),priorityConfig=base.resolve("priority-config");
        put(priority.resolve("assets/p/geo/a.geo.json"),GEO.substring(0,GEO.length()-1)+",\"texture\":\"p:textures/absent.png\"}");
        png(priority.resolve("assets/p/textures/fallback.png"),0xff000000);
        put(priority.resolve("assets/p/definition.json"),"{\"model\":\"p:geo/a.geo.json\",\"texture\":\"p:textures/fallback.png\"}");
        var prioritySources=List.of(source("priority","Priority",priority));new GeoImporter(priorityConfig,s->{}).scan(prioritySources);
        check(SharedModelAssets.bindingsForTest().values().stream().allMatch(v->v.texture()==null),"missing explicit Geo texture cannot be replaced by lower priority external reference");
        png(priority.resolve("assets/p/textures/absent.png"),0xffabcdef);new GeoImporter(priorityConfig,s->{}).scan(prioritySources);
        check(SharedModelAssets.bindingsForTest().values().stream().allMatch(v->v.texture()!=null&&v.texture().endsWith("/absent.png")),"Geo texture declaration takes priority over conflicting external selection");
        Path partial=base.resolve("partial");put(partial.resolve("assets/demo/geo/a.geo.json"),GEO);png(partial.resolve("assets/demo/textures/a.png"),0xff123456);
        put(partial.resolve("assets/demo/group.json"),"{\"model\":[\"demo:geo/a.geo.json\",\"demo:geo/missing.geo.json\"],\"texture\":\"demo:textures/a.png\"}");
        var partialCatalog=new ResourceCatalog(List.of(source("partial","Partial",partial)));
        check(partialCatalog.bundles.values().stream().allMatch(List::isEmpty),"partially resolved model group cannot assign textures to surviving model");
        check(partialCatalog.report.toString().contains("incomplete_definition"),"incomplete group reported separately");
        Path fixture=base.resolve("gecko"); put(fixture.resolve("assets/demo/geo/entity/bat.geo.json"),GEO);png(fixture.resolve("assets/demo/textures/entity/bat.png"),0xff000000);
        var cw=new org.objectweb.asm.ClassWriter(0);cw.visit(61,1,"fixture/Bat",null,"software/bernie/geckolib/model/DefaultedEntityGeoModel",null);
        var mv=cw.visitMethod(1,"<init>","()V",null,null);mv.visitCode();mv.visitVarInsn(25,0);mv.visitTypeInsn(187,"net/minecraft/resources/ResourceLocation");mv.visitInsn(89);mv.visitLdcInsn("demo");mv.visitLdcInsn("bat");mv.visitMethodInsn(183,"net/minecraft/resources/ResourceLocation","<init>","(Ljava/lang/String;Ljava/lang/String;)V",false);mv.visitMethodInsn(183,"software/bernie/geckolib/model/DefaultedEntityGeoModel","<init>","(Lnet/minecraft/resources/ResourceLocation;)V",false);mv.visitInsn(177);mv.visitMaxs(5,1);mv.visitEnd();cw.visitEnd();
        Files.write(fixture.resolve("Bat.class"),cw.toByteArray());catalog=new ResourceCatalog(List.of(source("gecko","Gecko",fixture)));
        check(catalog.bundles.values().stream().mapToInt(List::size).sum()==1,"Gecko default constructor adapter");
        check(catalog.requirements.stream().anyMatch(r->r.kind().equals("animation")&&r.status().equals("MISSING")),"default constructor missing animation is a required dependency");
        Path defaultConfig=base.resolve("default-missing-config");new GeoImporter(defaultConfig,s->{}).scan(List.of(source("gecko","Gecko",fixture)));
        check(GenericAcceptance.status(json(defaultConfig.resolve("bbs/model-import-report.json")).getAsJsonArray("models"),"bat.geo.json").equals("INCOMPLETE"),"default animation missing cannot report complete model");
        cw=new org.objectweb.asm.ClassWriter(0);cw.visit(61,1,"fixture/FalsePositive",null,"software/bernie/geckolib/model/DefaultedEntityGeoModel",null);
        mv=cw.visitMethod(1,"<init>","()V",null,null);mv.visitCode();mv.visitLdcInsn("demo");mv.visitInsn(87);mv.visitLdcInsn("bat");mv.visitInsn(87);mv.visitInsn(177);mv.visitMaxs(1,1);mv.visitEnd();cw.visitEnd();
        Files.write(fixture.resolve("Bat.class"),cw.toByteArray());catalog=new ResourceCatalog(List.of(source("gecko","Gecko",fixture)));
        check(catalog.bundles.values().stream().allMatch(List::isEmpty),"unrelated constructor strings never establish a resource association");
    }
    static void bootstrapChecks(Path base)throws Exception{
        Path config=base.resolve("config"),one=base.resolve("one"),two=base.resolve("two"),bad=base.resolve("bad");String a="assets/one/geo/a.geo.json",b="assets/two/geo/b.geo.json";put(one.resolve(a),GEO);put(two.resolve(b),GEO);var sources=List.of(source("one","One Mod",one),source("two","Two Mod",two));
        GeoImporter first=new GeoImporter(config,s->{});var initial=first.bootstrap(sources);check(initial.discovered()==2&&initial.imported()==2,"startup bootstrap discovers and publishes multiple Mods without a catalog");JsonObject state=json(config.resolve("bbs/model-import-index.json"));check(state.getAsJsonObject("mods").size()==2&&ImportedModelGroups.category("One Mod/assets/one/geo/a").equals("One Mod"),"startup bootstrap publishes Mod category snapshot");
        Path outA=target(config,"one",a),outB=target(config,"two",b);long stamp=Files.getLastModifiedTime(outA).toMillis();GeoImporter second=new GeoImporter(config,s->{});var repeated=second.bootstrap(sources);check(repeated.imported()==0&&repeated.skipped()==2&&Files.getLastModifiedTime(outA).toMillis()==stamp,"second startup reuses verified model outputs");
        Files.delete(outA);Files.writeString(outB,"user edit");put(two.resolve("assets/two/geo/new.geo.json"),GEO);put(bad.resolve("broken.geo.json"),"{");put(one.resolve("assets/one/geo/good.geo.json"),GEO);
        var recovered=new GeoImporter(config,s->{}).bootstrap(List.of(source("one","One Mod",one),source("two","Two Mod",two),source("bad","Bad Mod",bad)));
        check(Files.exists(outA)&&Files.readString(outB).equals("user edit"),"old index with a missing output repairs only the missing file and protects edits");
        check(recovered.discovered()==5&&recovered.imported()==3&&recovered.conflicts()==1&&recovered.failed()==1,"startup discovers new Mods and isolates invalid sources");
        Path newOut=target(config,"two","assets/two/geo/new.geo.json"),goodOut=target(config,"one","assets/one/geo/good.geo.json");check(Files.exists(newOut)&&Files.exists(goodOut),"one failed source does not block other Mod publication");
        var refreshed=new GeoImporter(config,s->{}).bootstrap(List.of(source("one","One Mod",one),source("two","Two Mod",two),source("bad","Bad Mod",bad)));check(refreshed.imported()==0&&Files.readString(outB).equals("user edit"),"repeated recovery remains idempotent and keeps user conflict");
    }
    static void cobblemonChecks(Path base) throws Exception {
        Path root=base.resolve("cobblemon"),config=base.resolve("cobblemon-config");
        String male="assets/cobblemon/bedrock/pokemon/models/0012/butterfree_male.geo.json",female=male.replace("male.geo","female.geo"),alolan=male.replace("male.geo","alolan.geo");
        put(root.resolve(male),GEO);put(root.resolve(female),GEO);put(root.resolve(alolan),GEO);
        png(root.resolve("assets/cobblemon/textures/normal.png"),0xffaabbcc);png(root.resolve("assets/cobblemon/textures/shiny.png"),0xffccbbaa);png(root.resolve("assets/cobblemon/textures/alolan.png"),0xffaaccbb);
        put(root.resolve("assets/cobblemon/bedrock/pokemon/resolvers/0012/base.json"),"{\"species\":\"cobblemon:butterfree\",\"order\":0,\"variations\":[{\"aspects\":[],\"model\":\"cobblemon:butterfree_male.geo\",\"texture\":\"cobblemon:textures/normal.png\"},{\"aspects\":[\"shiny\"],\"texture\":\"cobblemon:textures/shiny.png\"},{\"aspects\":[\"alolan\"],\"model\":\"cobblemon:butterfree_alolan.geo\",\"texture\":\"cobblemon:textures/alolan.png\"}]}");
        put(root.resolve("assets/cobblemon/bedrock/pokemon/resolvers/0012/female.json"),"{\"species\":\"cobblemon:butterfree\",\"order\":1,\"variations\":[{\"aspects\":[\"female\"],\"model\":\"cobblemon:butterfree_female.geo\"}]}");
        Path resolver=root.resolve("assets/cobblemon/bedrock/pokemon/resolvers/0012/base.json");
        Files.writeString(resolver,Files.readString(resolver).replace("\"model\":", "\"poser\":\"cobblemon:butterfree\",\"model\":"));
        put(root.resolve("assets/cobblemon/bedrock/pokemon/posers/butterfree.json"),"{\"animations\":[\"q.bedrock('shared_motion', 'idle')\"]}");
        put(root.resolve("assets/cobblemon/bedrock/pokemon/animations/group/shared_motion.animation.json"),"{\"animations\":{\"animation.shared_motion.idle\":{}}}");
        var sources=List.of(source("cobblemon","Cobblemon",root));var importer=new GeoImporter(config,s->{});importer.scan(sources);
        var rawCatalog=new ResourceCatalog(sources);
        long epoch=RuntimeCapture.generation();
        var effective=new EffectiveResources(epoch,(g,address)->new ResourceEvidence.Snapshot(g,address,"fixture-pack",Files.readAllBytes(root.resolve("assets/"+address.replace(':','/')))),base.resolve("hashed-snapshots"));
        var cachedCatalog=new ResourceCatalog(sources,effective);
        check(cachedCatalog.identities.values().stream().filter(x->x.has("effective")).allMatch(x->x.getAsJsonObject("effective").get("supplier").getAsString().equals("fixture-pack")),"effective catalog retains actual pack supplier independently of physical container");
        check(cachedCatalog.defaultTextures.equals(rawCatalog.defaultTextures),"logical defaults independent of hashed snapshot filenames");
        check(cachedCatalog.bundles.entrySet().stream().allMatch(e->new java.util.HashSet<>(e.getValue()).equals(new java.util.HashSet<>(rawCatalog.bundles.get(e.getKey())))),"texture and animation associations independent of physical snapshot paths");
        Path a=target(config,"cobblemon",male),b=target(config,"cobblemon",female);
        check(SharedModelAssets.bindingsForTest().values().stream().anyMatch(x->x.texture()!=null),"Cobblemon normal default despite shiny alternative");
        check(rawCatalog.defaultTextures.get("cobblemon:"+alolan).endsWith("textures/alolan.png"),"model-specific aspect override inherits its base texture as the geometry default");
        check(rawCatalog.defaultTextures.get("cobblemon:"+male)!=null&&rawCatalog.defaultTextures.get("cobblemon:"+male).endsWith("textures/normal.png"),"base geometry retains normal texture while shiny remains variant: "+rawCatalog.defaultTextures+" conflicts="+rawCatalog.defaultTextureConflicts);
        JsonObject overlayDefinition=json(resolver);overlayDefinition.getAsJsonArray("variations").get(0).getAsJsonObject().add("layers",JsonParser.parseString("[{\"name\":\"glow\",\"texture\":\"cobblemon:textures/glow.png\",\"emissive\":true}]"));
        png(root.resolve("assets/cobblemon/textures/glow.png"),0xff445566);Files.writeString(resolver,overlayDefinition.toString());
        var layered=new ResourceCatalog(sources);check(layered.layerPlans.containsKey("cobblemon:"+male)&&layered.bundles.get("cobblemon:"+male).stream().anyMatch(e->e.resource().endsWith("/glow.png")),"overlay dependencies and original semantics retained independently of skin variants");
        importer.scan(sources);check(SharedModelAssets.bindingsForTest().entrySet().stream().filter(e->!e.getKey().contains("/skins/")).allMatch(e->e.getValue().texture()!=null&&!e.getValue().texture().endsWith("/glow.png")),"overlay never becomes arbitrary default texture");
        check(json(config.resolve("bbs/model-import-index.json")).toString().contains("UNSUPPORTED_BBS_EMISSIVE_ANIMATED_OVERLAY"),"persistent plan records exact unsupported overlay semantics");
        check(SharedModelAssets.bindingsForTest().entrySet().stream().filter(e->!e.getKey().contains("/skins/")).filter(e->!e.getValue().animations().isEmpty()).count()==3,"explicit poser animation inherited by all canonical geometries");
        check(Arrays.equals(Files.readAllBytes(config.resolve("bbs/assets/models/Cobblemon/animations/imported-resources/cobblemon/assets/cobblemon/textures/normal.png")),Files.readAllBytes(root.resolve("assets/cobblemon/textures/normal.png"))),"female inherits base normal texture");
        check(countCanonical(config.resolve("bbs/assets/models/Cobblemon"),".geo.json")==3,"male female alolan canonical geometries, excluding explicit skin copies");
        String key="Cobblemon/"+config.resolve("bbs/assets/models/Cobblemon").relativize(a.getParent()).toString().replace('\\','/');
        check("Cobblemon".equals(ImportedModelGroups.category(key)),"imported models grouped under Mod root");
        check("Cobblemon".equals(ImportedModelGroups.category(key.substring(0,key.lastIndexOf('/')))),"BBS parent category keys retain their Mod category");
        check("Cobblemon".equals(ImportedModelGroups.category(key+"/nested")),"nested BBS model keys retain their Mod category");
        check(ImportedModelGroups.category("Cobblemon/manual_model")==null,"untracked manual models not regrouped");
        Files.writeString(config.resolve("bbs/assets/models/Cobblemon/animations/imported-resources/cobblemon/assets/cobblemon/textures/normal.png"),"user");importer.scan(sources);check(Files.readString(config.resolve("bbs/assets/models/Cobblemon/animations/imported-resources/cobblemon/assets/cobblemon/textures/normal.png")).equals("user"),"Cobblemon user texture preserved");
        put(root.resolve("assets/cobblemon/bedrock/pokemon/models/duplicate/butterfree_male.geo.json"),GEO);
        var catalog=new ResourceCatalog(sources);check(!catalog.defaultTextures.containsKey("cobblemon:"+male),"duplicate short model ID not guessed");
        check(catalog.report.toString().contains("cobblemon_reference_ambiguous"),"duplicate short model diagnostic");
    }
    static void resolverScaleChecks(Path base)throws Exception{
        Path root=base.resolve("resolver-scale");
        put(root.resolve("assets/odd/bedrock/pokemon/models/base.geo.json"),GEO.replace("geometry.test","odd:base"));
        put(root.resolve("assets/odd/bedrock/pokemon/models/female.geo.json"),GEO.replace("geometry.test","odd:female"));
        for(String name:List.of("base","female","shiny","female_shiny"))png(root.resolve("assets/odd/textures/"+name+".png"),0xff223344);
        StringBuilder json=new StringBuilder("{\"species\":\"odd:creature\",\"variations\":[{\"aspects\":[],\"model\":\"odd:base.geo\",\"texture\":\"odd:textures/base.png\"},{\"aspects\":[\"female\"],\"model\":\"odd:female.geo\",\"texture\":\"odd:textures/female.png\"},{\"aspects\":[\"shiny\"],\"texture\":\"odd:textures/shiny.png\"},{\"aspects\":[\"female\",\"shiny\"],\"texture\":\"odd:textures/female_shiny.png\"}");
        for(int i=0;i<300;i++)json.append(",{\"aspects\":[\"variant_").append(i).append("\"],\"texture\":\"odd:textures/shiny.png\"}");
        put(root.resolve("assets/odd/bedrock/pokemon/resolvers/creature.json"),json.append("]}").toString());
        var c=new ResourceCatalog(List.of(source("odd","Odd Mod",root)));
        String baseKey="odd:assets/odd/bedrock/pokemon/models/base.geo.json",femaleKey="odd:assets/odd/bedrock/pokemon/models/female.geo.json";
        check(c.defaultTextures.get(baseKey).endsWith("textures/base.png"),"resolver keeps the base model default");
        check(c.defaultTextures.get(femaleKey).endsWith("textures/female.png"),"specific model uses its own non-shiny default");
        check(c.bundles.get(baseKey).stream().filter(e->e.resource().endsWith(".png")).count()<=2,"base model receives only its default and compatible skins: "+c.bundles.get(baseKey));
        check(c.bundles.get(baseKey).stream().noneMatch(e->e.resource().endsWith("female.png")||e.resource().endsWith("female_shiny.png")),"mutually exclusive female textures never attach to base model");
        check(c.requirements.stream().noneMatch(r->r.model().equals(baseKey)&&r.reference().endsWith("female.png")),"female texture excluded from base model skin choices");
        check(c.bundles.get(femaleKey).stream().filter(e->e.resource().endsWith(".png")).count()<=3,"female model receives only its default and aspect-compatible skin rules");
    }
    static void literalChecks(Path base) throws Exception {
        Path root=base.resolve("literal");put(root.resolve("assets/demo/geo/a.geo.json"),GEO);png(root.resolve("assets/demo/textures/a.png"),0xff112233);
        for(int mode:List.of(0,1,2,3,4)) {
            boolean fake=mode==1;
            var cw=new org.objectweb.asm.ClassWriter(0);cw.visit(61,1,"fixture/Literal",null,"java/lang/Object",null);
            if(mode==4){
                cw.visitField(9,"TEXTURE","Lnet/minecraft/resources/ResourceLocation;",null,null).visitEnd();
                var init=cw.visitMethod(8,"<clinit>","()V",null,null);init.visitCode();
                init.visitTypeInsn(187,"net/minecraft/resources/ResourceLocation");init.visitInsn(89);init.visitLdcInsn("demo");init.visitLdcInsn("textures/a.png");
                init.visitMethodInsn(183,"net/minecraft/resources/ResourceLocation","<init>","(Ljava/lang/String;Ljava/lang/String;)V",false);
                init.visitFieldInsn(179,"fixture/Literal","TEXTURE","Lnet/minecraft/resources/ResourceLocation;");init.visitInsn(177);init.visitMaxs(4,0);init.visitEnd();
            }
            for(String method:List.of("getModelResource","getTextureResource")) {
                var mv=cw.visitMethod(1,method,"()Lnet/minecraft/resources/ResourceLocation;",null,null);mv.visitCode();
                if(mode==4&&method.equals("getTextureResource")){mv.visitFieldInsn(178,"fixture/Literal","TEXTURE","Lnet/minecraft/resources/ResourceLocation;");mv.visitInsn(176);mv.visitMaxs(1,1);mv.visitEnd();continue;}
                mv.visitTypeInsn(187,"net/minecraft/resources/ResourceLocation");mv.visitInsn(89);mv.visitLdcInsn("demo");mv.visitLdcInsn(method.equals("getModelResource")?"geo/a.geo.json":"textures/a.png");
                mv.visitMethodInsn(183,"net/minecraft/resources/ResourceLocation","<init>","(Ljava/lang/String;Ljava/lang/String;)V",false);
                if(fake) {mv.visitInsn(87);mv.visitInsn(1);} mv.visitInsn(176);mv.visitMaxs(4,1);mv.visitEnd();
            }
            if(mode==2||mode==3) {
                String method=mode==2?"getModelResource":"getTextureResource";
                var extra=cw.visitMethod(1,method,"(I)Lnet/minecraft/resources/ResourceLocation;",null,null);extra.visitCode();
                extra.visitTypeInsn(187,"net/minecraft/resources/ResourceLocation");extra.visitInsn(89);extra.visitLdcInsn("demo");extra.visitLdcInsn(mode==2?"geo/missing.geo.json":"textures/missing.png");
                extra.visitMethodInsn(183,"net/minecraft/resources/ResourceLocation","<init>","(Ljava/lang/String;Ljava/lang/String;)V",false);extra.visitInsn(176);extra.visitMaxs(4,2);extra.visitEnd();
            }
            cw.visitEnd();Files.createDirectories(root.resolve("fixture"));Files.write(root.resolve("fixture/Literal.class"),cw.toByteArray());var c=new ResourceCatalog(List.of(source("literal","Literal",root)));
            check(c.bundles.values().stream().flatMap(List::stream).map(e->e.resource()).distinct().count()==(mode==0||mode==4?1:0),mode>=2?"overloaded resource methods cannot partially resolve":"literal return safety mode "+mode);
        }
    }
    public static void main(String[] args) throws Exception {
        Path base=Path.of(args[0]); Files.createDirectories(base); base=Files.createTempDirectory(base,"run-");
        literalChecks(base);
        bootstrapChecks(base.resolve("bootstrap"));
        cobblemonChecks(base);
        resolverScaleChecks(base);
        Path dev=base.resolve("dev"), config=base.resolve("config");
        String rel="assets/other_namespace/geo/beast.geo.json"; put(dev.resolve(rel),GEO);
        put(dev.resolve("assets/x/animation.json"),"not geo"); put(dev.resolve("assets/x/model.png"),"not png");
        GeoImporter importer=new GeoImporter(config,s->{}); var sources=List.of(source("alpha","中文 Mod?",dev));
        check(importer.scan(sources).imported()==1,"first import");
        Path out=target(config,"alpha",rel); check(out.getFileName().toString().equals("beast.geo.json"),"original filename");
        check(Files.readString(out).equals(GEO),"byte preservation"); check(Files.exists(out.resolveSibling("config.json")),"BBS config");
        long stamp=Files.getLastModifiedTime(out).toMillis(); check(importer.scan(sources).skipped()==1,"idempotent"); check(Files.getLastModifiedTime(out).toMillis()==stamp,"no rewrite");
        put(dev.resolve(rel),GEO+" "); check(importer.scan(sources).updated()==1,"source update");
        put(out,"user edit"); put(dev.resolve(rel),GEO+"  "); check(importer.scan(sources).conflicts()==1,"user edit protected"); check(Files.readString(out).equals("user edit"),"user bytes retained");
        put(out,GEO+" "); put(dev.resolve(rel),"{"); check(importer.scan(sources).failed()==1,"bad source isolated"); check(Files.readString(out).equals(GEO+" "),"valid old retained");
        put(dev.resolve(rel),GEO+"   "); check(importer.scan(sources).updated()==1,"retry recovered");
        Files.delete(dev.resolve(rel)); importer.scan(sources); check(Files.exists(out),"source removal retained"); importer.scan(List.of()); check(Files.exists(out),"uninstall retained");
        put(dev.resolve(rel),GEO); Path second=base.resolve("second"); put(second.resolve(rel),GEO);
        check(importer.scan(List.of(source("alpha","renamed",dev),source("beta","中文 Mod?",second))).imported()==1,"same mod name separated");
        check(!target(config,"beta",rel).equals(out),"distinct targets"); check(target(config,"alpha",rel).equals(out),"name change stable");
        Path collision=dev.resolve("assets/other_namespace/geo/BEAST.geo.json"); put(collision,GEO);
        check(importer.scan(sources).failed()==0,"case-insensitive host scan"); importer.scan(sources);
        Path extra=dev.resolve("assets/new.geo.json"); put(extra,GEO);
        Path untracked=out.getParent().getParent().getParent().getParent().resolve("new/new.geo.json");
        // Explicit untracked destination under an already-bound Mod directory.
        JsonObject state=JsonParser.parseString(Files.readString(config.resolve("bbs/model-import-index.json"))).getAsJsonObject();
        Path modDir=config.resolve("bbs/assets/models").resolve(state.getAsJsonObject("mods").getAsJsonObject("alpha").get("directory").getAsString());
        untracked=modDir.resolve("assets/new/new.geo.json"); put(untracked,"untracked");
        check(importer.scan(sources).conflicts()==1,"untracked file protected"); check(Files.readString(untracked).equals("untracked"),"untracked unchanged");
        Path jar=base.resolve("fixture.jar");
        try(var zip=FileSystems.newFileSystem(URI.create("jar:"+jar.toUri()),Map.of("create","true"))) {
            put(zip.getPath("/assets/fixture/geo/box.geo.json"),GEO);
            check(importer.scan(List.of(source("zip","Jar Mod",zip.getPath("/")))).imported()==1,"jar root scanned");
            check(zip.isOpen(),"loader filesystem kept open");
            put(zip.getPath("/assets/fixture/geo/BOX.geo.json"),GEO);
            check(importer.scan(List.of(source("zip","Jar Mod",zip.getPath("/")))).conflicts()==1,"case collision in jar protected");
        }
        check(importer.scan(List.of(source("one","One",dev),source("two","Two",dev))).discovered()>0,"shared physical container retained without dropping Geo");
        check(GeoImporter.safeName("CON").equals("_CON"),"reserved name"); check(GeoImporter.safeName("a?. ").equals("a_"),"invalid name");
        Path index=config.resolve("bbs/model-import-index.json"); Files.writeString(index,"broken"); boolean refused=false;
        try { importer.scan(sources); } catch(Exception e) {refused=true;}
        check(refused,"corrupt index aborts"); check(Files.readString(untracked).equals("untracked"),"corrupt index preserves output");
        FactoryUvAcceptance.run(base.resolve("factory-uv"));
        resourceChecks(base.resolve("resources"));
        Path inline=base.resolve("inline");
        put(inline.resolve("assets/demo/odd/box.GEO.JSON"),GEO.replace("\"identifier\":\"geometry.test\"","\"identifier\":\"geometry.test\",\"textures\":{\"main\":\"../paint/skin.png\"},\"animation\":\"../motion/run.animation.json\""));
        png(inline.resolve("assets/demo/paint/skin.png"),0xff123456);
        put(inline.resolve("assets/demo/motion/run.animation.json"),"{\"animations\":{}}");
        var inlineCatalog=new ResourceCatalog(List.of(source("inline","Inline",inline)));
        check(inlineCatalog.bundles.values().stream().mapToInt(List::size).sum()==2,"inline description relative texture and animation references");
        check(inlineCatalog.uniqueResource("", "texture").isEmpty(),"unknown constant addresses ignored safely");
        Path structural=base.resolve("structural");
        String animatedGeo=GEO.replace("geometry.test","geometry.animated").replace("\"bones\":[]","\"bones\":[{\"name\":\"root\"}]");
        put(structural.resolve("assets/odd/models/creature.json"),animatedGeo);
        put(structural.resolve("assets/odd/motion/creature.json"),"{'animations':{'animation.creature.walk':{'bones':{'root':{'position':[0,0,0]}}}}}");
        check(ResourceCatalog.kind("assets/odd/models/creature.json",structural.resolve("assets/odd/models/creature.json")).equals("geo"),"Geo JSON discovered by structure outside standard filename");
        check(ResourceCatalog.kind("assets/odd/motion/creature.json",structural.resolve("assets/odd/motion/creature.json")).equals("animation"),"animation JSON discovered by structure outside standard filename");
        var structuralImport=new GeoImporter(structural.resolve("config"),s->{});check(structuralImport.scan(List.of(source("odd","Odd",structural))).imported()==1,"nonstandard Geo receives BBS-compatible output");
        Path nonstandardOutput=target(structural.resolve("config"),"odd","assets/odd/models/creature.json");
        check(nonstandardOutput.getFileName().toString().equals("creature.geo.json"),"nonstandard Geo extension adapted for BBS discovery");
        put(structural.resolve("assets/odd/geo/with-animation.geo.json"),"{'minecraft:geometry':[{'description':{'identifier':'geometry.animated'},'bones':[{'name':'root','cubes':[]}]}],'animation':'odd:motion/creature.json'}");
        put(structural.resolve("assets/odd/motion/creature.json"),"{'animations':{'animation.creature.walk':{'bones':{'missing_bone':{'position':[0,0,0]}}}}}");
        structuralImport.scan(List.of(source("odd","Odd",structural)));
        var animationReport=json(structural.resolve("config/bbs/model-import-report.json"));
        check(animationReport.toString().contains("missing model bone missing_bone"),"animation tracks to absent Geo bones are diagnosed");
        check(animationReport.toString().contains("animationCompatibility"),"BBS animation compatibility has a per-model result");
        FormattedConstructionAcceptance.run(base.resolve("formatted"));GenericAcceptance.run(base.resolve("generic"));
        AssociationAcceptance.run(base.resolve("associations"));
        MaterialAcceptance.run(base.resolve("materials"));
        RepairAcceptance.run(base.resolve("repair"));
        CaptureAcceptance.run(base.resolve("capture"));
        CrashAcceptance.run(base.resolve("crash"));
        PerformanceAcceptance.run(base.resolve("performance"));
        StartupOutputAcceptance.run(base.resolve("startup-outputs"));
        ManyToManyAcceptance.run(base.resolve("many-to-many"));
        LiveStartupAcceptance.run(base.resolve("live-startup"));
        EarlyPublicationAcceptance.run(base.resolve("early-publication"));
        System.out.println("ACCEPTANCE PASSED: "+checks+" assertions; fixtures="+base);
    }
}
